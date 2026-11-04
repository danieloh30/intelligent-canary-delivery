package dev.danieloh.canary.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.model.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

class RolloutProgressServiceTest {
    static class Cluster extends ClusterService {
        ObjectMapper mapper=new ObjectMapper();GenericKubernetesResource resource;KubernetesClient client;
        boolean enabled=true,changeStep;int reads;
        @Override public boolean isEnabled(){return enabled;}
        @Override public ObjectMapper mapper(){return mapper;}
        @Override public String namespace(){return "canary-demo";}
        @Override public KubernetesClient client(){return client;}
        @Override public GenericKubernetesResource rollout(){
            if(changeStep&&++reads>1){var status=(Map<String,Object>)resource.getAdditionalProperties().get("status");status.put("currentStepIndex",5);}
            return resource;
        }
    }
    static class Collector extends EvidenceCollector {
        long stable=30,canary=30;List<String> missing=List.of();int calls;
        @Override public Evidence collect(String revision,GenericKubernetesResource r){calls++;
            int step=((Number)((Map<?,?>)r.getAdditionalProperties().get("status")).get("currentStepIndex")).intValue();
            return new Evidence("window",Instant.now(),"LIVE_KUBERNETES_REQUESTS",revision,"stable",step,new Signal(stable,0,5,1),new Signal(canary,0,5,1),List.of(Map.of("podHash","candidate")),List.of(),missing,"v2");
        }
    }
    static class Setup implements AutoCloseable {
        Cluster cluster=new Cluster();Collector collector=new Collector();RolloutProgressService service=new RolloutProgressService();HttpServer server;int analysisStep;
        Setup(int step,String phase) throws Exception {
            analysisStep=step;
            var json=cluster.mapper;ObjectNode rollout=(ObjectNode)json.readTree(Files.readString(Path.of("deploy/gitops/rollout.yaml")));
            for(var env:rollout.at("/spec/template/spec/containers/0/env"))if(env.path("name").asText().equals("DEMO_RELEASE"))((ObjectNode)env).put("value","v2");
            rollout.set("status",json.valueToTree(Map.of("phase","Progressing","currentPodHash","candidate","stableRS","stable","currentStepIndex",step,"canary",Map.of("currentStepAnalysisRunStatus",Map.of("name","analysis-current")))));
            cluster.resource=json.convertValue(rollout,GenericKubernetesResource.class);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                byte[] body=json.writeValueAsBytes(Map.of("apiVersion","argoproj.io/v1alpha1","kind","AnalysisRun","metadata",Map.of("name","analysis-current","labels",Map.of("rollouts-pod-template-hash","candidate","step-index",Integer.toString(analysisStep))),"spec",Map.of("metrics",List.of(Map.of("name","governed-recommendation","count",180,"interval","5s"))),"status",Map.of("phase",phase,"metricResults",List.of(Map.of("name","governed-recommendation","count",12)))));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
            cluster.client=new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl("http://127.0.0.1:"+server.getAddress().getPort()).build()).build();
            service.cluster=cluster;service.collector=collector;service.store=new DemoStore();
        }
        public void close(){cluster.client.close();server.stop(0);}
    }
    @Test void readyRequiresAnActiveAnalysisAndBothCohortsAtThirty() throws Exception {
        try(var s=new Setup(2,"Running")){var p=s.service.snapshot();assertTrue(p.ready());assertEquals("READY",p.state());assertEquals(10,p.targetWeight());assertEquals(30,p.stableRequests());assertEquals(180,p.checksTotal());assertEquals(12,p.checksCompleted());assertEquals("5s",p.interval());assertEquals("15s",p.pauseDuration());assertEquals(List.of("active","pending","pending"),p.stages().stream().map(RolloutProgressService.Stage::state).toList());assertNull(s.service.store.latest());assertTrue(s.service.store.events().isEmpty());assertSame(p,s.service.snapshot());assertEquals(1,s.collector.calls);}
        try(var s=new Setup(2,"Running")){s.collector.stable=29;assertFalse(s.service.snapshot().ready());}
        try(var s=new Setup(2,"Running")){s.collector.canary=29;assertFalse(s.service.snapshot().ready());}
    }
    @Test void warmupIsNotTheInvestigationWindow() throws Exception {
        try(var s=new Setup(1,"Running")){var p=s.service.snapshot();assertEquals("WARMUP",p.state());assertEquals("15s",p.pauseDuration());assertFalse(p.ready());assertEquals(10,p.targetWeight());}
    }
    @Test void fiftyPercentRequiresANewStageDecision() throws Exception {
        try(var s=new Setup(5,"Running")){
            var e=new dev.danieloh.canary.model.Evaluation(new Evidence("prior",Instant.now(),"LIVE_KUBERNETES_REQUESTS","v2","stable",2,new Signal(100,0,5,1),new Signal(100,0,5,1),List.of(Map.of("podHash","candidate")),List.of(),List.of(),"v2"),"development","live");
            e.status="COMPLETE";e.decision=new Decision(Verdict.GO,Verdict.GO,"accepted",false,Instant.now().plusSeconds(100),"development");s.service.store.add(e);
            var p=s.service.snapshot();assertTrue(p.ready());assertEquals(50,p.targetWeight());assertEquals(List.of("complete","active","pending"),p.stages().stream().map(RolloutProgressService.Stage::state).toList());
        }
    }
    @Test void completedAnalysisAndMissingTelemetryCannotAdvertiseReady() throws Exception {
        for(String phase:List.of("Inconclusive","Failed","Error","Successful"))try(var s=new Setup(2,phase)){assertFalse(s.service.snapshot().ready(),phase);}
        try(var s=new Setup(2,"Running")){s.collector.missing=List.of("Pod unavailable");assertFalse(s.service.snapshot().ready());}
        try(var s=new Setup(2,"Running")){s.analysisStep=5;assertFalse(s.service.snapshot().ready());assertEquals("Starting",s.service.snapshot().analysisPhase());}
        try(var s=new Setup(2,"Running")){s.cluster.changeStep=true;var p=s.service.snapshot();assertFalse(p.ready());assertTrue(p.missing().stream().anyMatch(x->x.contains("Rollout changed")));}
    }
    @Test void runningAgentsBlockDuplicateInvestigations() throws Exception {
        try(var s=new Setup(2,"Running")){
            var e=new dev.danieloh.canary.model.Evaluation(s.collector.collect("v2",s.cluster.resource),"development","live");s.service.store.add(e);
            var p=s.service.snapshot();assertFalse(p.ready());assertEquals("INVESTIGATING",p.state());
        }
    }
    @Test void baselineAndOfflineDoNotCollectCanaryEvidence() throws Exception {
        try(var s=new Setup(7,"Running")){
            var status=(Map<String,Object>)s.cluster.resource.getAdditionalProperties().get("status");status.put("phase","Healthy");status.put("currentPodHash","stable");
            var root=s.cluster.mapper.valueToTree(s.cluster.resource);for(var env:root.at("/spec/template/spec/containers/0/env"))if(env.path("name").asText().equals("DEMO_RELEASE"))((ObjectNode)env).put("value","v1");s.cluster.resource=s.cluster.mapper.convertValue(root,GenericKubernetesResource.class);
            assertEquals("BASELINE",s.service.snapshot().state());assertEquals(0,s.collector.calls);
        }
        try(var s=new Setup(2,"Running")){s.cluster.enabled=false;assertEquals("OFFLINE",s.service.snapshot().state());assertEquals(0,s.collector.calls);}
    }
    @Test void newReleaseInvalidatesCompletedCacheAndClearsStages() throws Exception {
        try(var s=new Setup(8,"Successful")) {
            var status=(Map<String,Object>)s.cluster.resource.getAdditionalProperties().get("status");
            status.put("phase","Healthy");status.put("currentPodHash","stable");
            assertEquals("COMPLETE",s.service.snapshot().state());
            s.service.store.released("v2",Instant.now().plusSeconds(1).toString(),false);
            var p=s.service.snapshot();assertEquals("RECONCILING",p.state());assertFalse(p.ready());
            assertEquals(0,p.targetWeight());assertFalse(p.samplesAvailable());assertEquals("",p.analysisName());
            assertTrue(p.stages().stream().allMatch(x->x.state().equals("pending")));
        }
    }
    @Test void appliedManifestCannotReuseUnobservedHealthyStatus() throws Exception {
        try(var s=new Setup(8,"Successful")) {
            s.cluster.resource.getMetadata().setGeneration(12L);
            var status=(Map<String,Object>)s.cluster.resource.getAdditionalProperties().get("status");
            status.put("phase","Healthy");status.put("currentPodHash","stable");status.put("observedGeneration","11");
            var p=s.service.snapshot();assertEquals("RECONCILING",p.state());assertFalse(p.ready());
            assertTrue(p.stages().stream().allMatch(x->x.state().equals("pending")));
            assertEquals(0,s.collector.calls);
        }
    }

}

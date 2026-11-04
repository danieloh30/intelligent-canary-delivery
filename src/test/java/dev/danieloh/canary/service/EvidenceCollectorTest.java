package dev.danieloh.canary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceCollectorTest {
    @Test void snapshotReadsCohortsConcurrentlyAndFiltersTheLiveWindow() throws Exception {
        var testMapper=new ObjectMapper();var barrier=new CountDownLatch(2);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var pods=new ArrayList<Map<String,Object>>();
        for(String cohort:List.of("stable","candidate"))pods.add(Map.of("metadata",Map.of("name",cohort,"labels",Map.of("rollouts-pod-template-hash",cohort)),"spec",Map.of("containers",List.of(Map.of("name","checkout"))),"status",Map.of("phase","Running","podIP",cohort)));
        server.createContext("/",exchange->{byte[] body=testMapper.writeValueAsBytes(Map.of("apiVersion","v1","kind","PodList","items",pods));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(var testClient=new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl("http://127.0.0.1:"+server.getAddress().getPort()).build()).build()) {
            var cluster=new ClusterService(){
                @Override public KubernetesClient client(){return testClient;}
                @Override public ObjectMapper mapper(){return testMapper;}
                @Override public String namespace(){return "canary-demo";}
                @Override public String request(String url,String method)throws Exception {
                    barrier.countDown();assertTrue(barrier.await(2,TimeUnit.SECONDS),"Pod reads should fan out concurrently");
                    boolean candidate=url.contains("candidate");var observations=new ArrayList<Map<String,Object>>();
                    int count=candidate?31:1;
                    for(int i=0;i<count;i++)observations.add(Map.of("at",Instant.now().toString(),"release",candidate?"v2":"v1","traceId","trace-"+i,"latencyMs",5,"attempts",1,"success",true));
                    observations.add(Map.of("at",Instant.now().minusSeconds(90).toString(),"release",candidate?"v2":"v1","traceId","old","latencyMs",1000,"attempts",5,"success",false));
                    return testMapper.writeValueAsString(observations);
                }
            };
            var rollout=new GenericKubernetesResource();rollout.setAdditionalProperty("status",Map.of("currentStepIndex",2,"stableRS","stable","currentPodHash","candidate"));
            var collector=new EvidenceCollector();collector.cluster=cluster;
            try {
                var evidence=collector.collect("v2",rollout);
                assertTrue(evidence.missing().isEmpty(),evidence.missing().toString());assertEquals(1,evidence.stable().requests());assertEquals(31,evidence.canary().requests());
                assertEquals("candidate",evidence.canaryPodHash());assertTrue(evidence.matchesPodHash("candidate"));
                assertEquals(5,evidence.canary().p95Ms());assertEquals(1,evidence.canary().attemptsPerCheckout());
                assertEquals(11,evidence.traces().size());assertTrue(evidence.traces().stream().noneMatch(x->x.get("traceId").equals("old")));
            }finally{collector.shutdown();}
        }finally{server.stop(0);}
    }
}

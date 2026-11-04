package dev.danieloh.canary.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.danieloh.canary.model.Domain.Evidence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;

/** Read-only presenter guidance. Polling this service never starts an agent or changes a rollout. */
@ApplicationScoped
public class RolloutProgressService {
    public record Stage(int weight,String state) {}
    public record Progress(boolean connected,String state,String message,String phase,String revision,String podHash,
            int stepIndex,int targetWeight,String pauseDuration,String analysisName,String analysisPhase,
            int checksCompleted,int checksTotal,String interval,Instant capturedAt,long stableRequests,long canaryRequests,double stableP95Ms,double canaryP95Ms,
            boolean samplesAvailable,boolean ready,List<String> missing,List<Stage> stages) {}
    @Inject ClusterService cluster;
    @Inject EvidenceCollector collector;
    @Inject DemoStore store;
    private Progress cached;
    private Instant cachedAt=Instant.EPOCH;
    private DemoStore.ReleaseIntent cachedIntent;

    public synchronized Progress snapshot() {
        if(!cluster.isEnabled())return unavailable(false,"OFFLINE","Live rollout progress is available when connected to Kubernetes.");
        var intent=store.releaseIntent();
        if(cached!=null && Objects.equals(intent,cachedIntent) && Instant.now().isBefore(cachedAt.plusSeconds(3)))return cached;
        try {cached=read();}catch(Exception ex){cached=unavailable(false,"UNAVAILABLE","Cannot read live rollout progress. Investigation readiness is unavailable.");}
        cachedAt=Instant.now();
        cachedIntent=intent;
        return cached;
    }
    private Progress unavailable(boolean connected,String state,String message) {
        return new Progress(connected,state,message,"","","",-1,0,"","","",0,0,"",Instant.now(),0,0,0,0,false,false,List.of(),List.of());
    }
    private Progress read() {
        var resource=cluster.rollout();
        if(resource==null)return unavailable(false,"UNAVAILABLE","Checkout rollout was not found.");
        JsonNode tree=cluster.mapper().valueToTree(resource.getAdditionalProperties());
        String revision=revision(tree),hash=tree.at("/status/currentPodHash").asText(),stable=tree.at("/status/stableRS").asText();
        String phase=tree.at("/status/phase").asText();
        int index=tree.at("/status/currentStepIndex").asInt(-1);
        var steps=tree.at("/spec/strategy/canary/steps");
        int weight=0;
        for(int i=0;i<steps.size()&&i<=index;i++)if(steps.get(i).has("setWeight"))weight=steps.get(i).path("setWeight").asInt();
        boolean complete=!hash.isBlank()&&hash.equals(stable)&&phase.equals("Healthy");
        boolean baseline=complete&&revision.equals("v1");
        boolean failed=Set.of("Degraded","Failed","Error").contains(phase)||tree.at("/status/abort").asBoolean();
        String state=baseline?"BASELINE":complete?"COMPLETE":failed?"FAILED":"COLLECTING";
        String message=baseline?"Healthy v1 baseline. Release a candidate to begin.":complete?revision+" is fully promoted and stable.":failed?"The rollout failed or was aborted. Inspect the decision and controller logs here.":"Collecting live observations for this rollout stage.";
        var intent=store.releaseIntent();
        boolean pending=intent!=null&&pending(intent.releasedAt(),tree.at("/spec/template/metadata/annotations/demo.canary~1released-at").asText());
        boolean controllerPending=resource.getMetadata().getGeneration()!=null
            &&tree.path("status").has("observedGeneration")
            &&tree.at("/status/observedGeneration").asLong()<resource.getMetadata().getGeneration();
        if(pending||controllerPending)return new Progress(true,"RECONCILING",
            pending?"Release committed to Git. Waiting for Argo CD to apply the new candidate.":"New manifest applied. Waiting for Argo Rollouts to observe this release.",
            phase,revision,hash,index,0,"","","",0,0,"",Instant.now(),0,0,0,0,false,false,List.of(),stages(steps,-1,true,false,false));
        JsonNode current=index>=0&&index<steps.size()?steps.get(index):cluster.mapper().createObjectNode();
        String pause=current.has("pause")?current.path("pause").path("duration").asText():current.has("analysis")&&index>0?steps.get(index-1).path("pause").path("duration").asText():"";
        String analysisName="",analysisPhase="",interval="";int checks=0,total=0;
        List<String> missing=new ArrayList<>();
        boolean active=!hash.isBlank()&&!hash.equals(stable)&&!complete&&!failed&&!pending;
        Evidence evidence=null;
        boolean analysisStep=current.has("analysis")&&active;
        if(active) {
            if(current.has("pause")){state="WARMUP";message="Warm-up pause"+(pause.isBlank()?"":" ("+pause+")")+" before the analysis gate. You do not need to click during this pause.";}
            if(analysisStep) {
                analysisName=tree.at("/status/canary/currentStepAnalysisRunStatus/name").asText();
                if(!analysisName.isBlank()) {
                    var run=cluster.client().genericKubernetesResources(ClusterService.context("analysisruns","AnalysisRun"))
                        .inNamespace(cluster.namespace()).withName(analysisName).get();
                    if(run==null)missing.add("AnalysisRun is not available yet");
                    else if(run.getMetadata().getLabels()==null
                            ||!hash.equals(run.getMetadata().getLabels().get("rollouts-pod-template-hash"))
                            ||!Integer.toString(index).equals(run.getMetadata().getLabels().get("step-index"))) {
                        missing.add("AnalysisRun belongs to another rollout revision or stage; waiting for the current run");
                        analysisPhase="Starting";
                    } else {
                        JsonNode analysis=cluster.mapper().valueToTree(run.getAdditionalProperties());
                        analysisPhase=analysis.at("/status/phase").asText("Pending");
                        for(var metric:analysis.at("/spec/metrics"))if(metric.path("name").asText().equals("governed-recommendation")){
                            total=metric.path("count").asInt();interval=metric.path("interval").asText();
                        }
                        for(var result:analysis.at("/status/metricResults"))if(result.path("name").asText().equals("governed-recommendation"))checks=result.path("count").asInt();
                    }
                }else missing.add("AnalysisRun is starting");
            }
            evidence=collector.collect(revision,resource);
            missing.addAll(evidence.missing());
            // Reject a window read across a stage, pod-template, or desired-release change.
            JsonNode after=cluster.mapper().valueToTree(cluster.rollout().getAdditionalProperties());
            if(!hash.equals(after.at("/status/currentPodHash").asText())||index!=after.at("/status/currentStepIndex").asInt(-1)
                    ||!revision.equals(revision(after)))missing.add("Rollout changed while observations were read; refreshing");
        }
        long stableCount=evidence==null?0:evidence.stable().requests(),canaryCount=evidence==null?0:evidence.canary().requests();
        boolean sampled=evidence!=null&&missing.isEmpty()&&stableCount>=30&&canaryCount>=30;
        boolean ready=analysisStep&&Set.of("Pending","Running").contains(analysisPhase)&&sampled;
        if(analysisStep) {
            if(Set.of("Failed","Error").contains(analysisPhase)){state="FAILED";message="Analysis failed. Promotion is blocked; inspect the decision and live controller logs.";ready=false;}
            else if(analysisPhase.equals("Inconclusive")){state="INCONCLUSIVE";message="This analysis has finished Inconclusive. Use the release/reset control to create a fresh run; an investigation cannot restart this finished gate.";ready=false;}
            else if(analysisPhase.equals("Successful")){state="ADVANCING";message="Analysis passed. Argo Rollouts is advancing to the next stage.";ready=false;}
            else if(!missing.isEmpty()){state="COLLECTING";message="Waiting for complete, current telemetry and an active analysis gate.";}
            else if(!sampled){state="COLLECTING";message="Analysis gate is waiting. Collect at least 30 observations for BOTH stable and canary.";}
            else {state="READY";message="Ready to investigate at "+weight+"% canary. The analysis gate is waiting for your decision.";}
            var latest=store.latest();
            boolean same=latest!=null&&latest.evidence.rolloutStep()==index&&latest.evidence.revision().equals(revision)
                &&latest.evidence.matchesPodHash(hash);
            if(same&&ready) {
                if(latest.status.equals("RUNNING")){state="INVESTIGATING";message="Java agents are investigating this stage. Follow Decision activity below.";ready=false;}
                else if(latest.decision!=null&&Instant.now().isBefore(latest.decision.expiresAt())) {
                    switch(latest.decision.effective()) {
                        case GO -> {state="ADVANCING";message="GO recorded for this stage. Waiting for Argo Rollouts to consume the decision.";ready=false;}
                        case NO_GO -> {state="FAILED";message="NO-GO recorded. Argo Rollouts will abort the candidate.";ready=false;}
                        case HOLD -> {state=latest.decision.approvalRequired()?"APPROVAL":"HOLD";message=latest.decision.approvalRequired()?"Production approval is required. Review the evidence and use Approve promotion below.":"HOLD at this stage. Address the cause, wait for fresh observations, then investigate again.";ready=!latest.decision.approvalRequired();}
                    }
                }
            }
        }
        return new Progress(true,state,message,phase,revision,hash,index,weight,pause,analysisName,analysisPhase,checks,total,interval,
            Instant.now(),stableCount,canaryCount,evidence==null?0:evidence.stable().p95Ms(),evidence==null?0:evidence.canary().p95Ms(),evidence!=null,ready,List.copyOf(missing),stages(steps,index,baseline,complete,failed));
    }
    static boolean pending(String requested,String actual) {
        if(requested.equals(actual))return false;
        try{return Instant.parse(actual).isBefore(Instant.parse(requested));}catch(Exception ex){return true;}
    }
    static String revision(JsonNode tree) {
        for(var env:tree.at("/spec/template/spec/containers/0/env"))if(env.path("name").asText().equals("DEMO_RELEASE"))return env.path("value").asText();
        return "";
    }
    static List<Stage> stages(JsonNode steps,int index,boolean baseline,boolean complete,boolean failed) {
        List<Stage> result=new ArrayList<>();
        for(int i=0;i<steps.size();i++)if(steps.get(i).has("setWeight")){
            int next=steps.size();for(int j=i+1;j<steps.size();j++)if(steps.get(j).has("setWeight")){next=j;break;}
            String state=baseline?"pending":complete||index>=next?"complete":index>=i?(failed?"failed":"active"):"pending";
            result.add(new Stage(steps.get(i).path("setWeight").asInt(),state));
        }
        return List.copyOf(result);
    }
}

package dev.danieloh.canary.resource;

import dev.danieloh.canary.model.Evaluation;
import dev.danieloh.canary.service.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.model.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MissingEvidenceBindingTest {
    Evidence evidence(){return new Evidence("binding",Instant.now(),"LIVE_KUBERNETES_REQUESTS","v2","stable",2,
        new Signal(100,0,10,1),new Signal(40,0,12,1),List.of(Map.of("podHash","candidate","cohort","canary")),List.of(),List.of("Existing collection gap"),"v2 notes","candidate");}
    static class Cluster extends ClusterService {
        String hash="candidate";int step=2;
        @Override public boolean isEnabled(){return true;}
        @Override public GenericKubernetesResource rollout(){var r=new GenericKubernetesResource();r.setAdditionalProperty("status",Map.of("currentStepIndex",step,"currentPodHash",hash));return r;}
    }
    @Test void withholdingTracesPreservesIdentityAndAllExistingGaps(){
        var original=evidence();var missing=original.withoutTraceEvidence("Presenter-injected trace evidence outage");
        assertTrue(missing.traces().isEmpty());assertEquals(original.canaryPodHash(),missing.canaryPodHash());assertTrue(missing.matchesPodHash("candidate"));assertFalse(missing.matchesPodHash("different"));assertFalse(missing.matchesPodHash(""));
        assertEquals(List.of("Existing collection gap","Presenter-injected trace evidence outage"),missing.missing());
        var go=new Recommendation(Verdict.GO,"Optimistic model",List.of(missing.id()),List.of());var decision=new DecisionPolicy().evaluate(missing,go,"production",true,Instant.now());
        assertEquals(Verdict.HOLD,decision.effective());assertFalse(decision.approvalRequired());assertTrue(decision.reason().contains("trace evidence outage"));
    }
    @Test void missingSceneEndpointRetainsCollectorIdentityWithoutStartingLiveAgents(){
        var resource=new DemoResource();resource.role="control";resource.store=new DemoStore();resource.cluster=new Cluster();
        resource.collector=new EvidenceCollector(){@Override public Evidence collect(String revision){return evidence();}};
        resource.investigations=new InvestigationService(){@Override public synchronized Evaluation start(Evidence evidence,String profile){return new Evaluation(evidence,profile,"live");}};
        var result=resource.evaluate("missing","development","v2");assertTrue(result.evidence.traces().isEmpty());assertEquals("candidate",result.evidence.canaryPodHash());assertEquals(2,result.evidence.rolloutStep());assertEquals(2,result.evidence.missing().size());
    }
    @Test void analysisReturnsTheSavedHoldReasonWithoutTracesButStillRejectsWrongStageHashAndExpiry(){
        var cluster=new Cluster();var resource=new DemoResource();resource.role="control";resource.cluster=cluster;resource.store=new DemoStore();
        var missing=evidence().withoutTraceEvidence("Presenter-injected trace evidence outage");var e=new Evaluation(missing,"development","live");
        e.status="COMPLETE";e.decision=new DecisionPolicy().evaluate(missing,null,"development",false,Instant.now());resource.store.add(e);
        assertEquals(e.decision.reason(),resource.analysis("v2").get("reason"));assertEquals(e.id,resource.analysis("v2").get("evaluationId"));assertEquals("HOLD",resource.analysis("v2").get("verdict"));
        cluster.step=5;assertTrue(resource.analysis("v2").get("reason").toString().contains("rollout step"));
        cluster.step=2;cluster.hash="different";assertTrue(resource.analysis("v2").get("reason").toString().contains("revision differs"));
        cluster.hash="candidate";e.decision=new Decision(Verdict.GO,Verdict.GO,"old",false,Instant.now().minusSeconds(1),"development");assertEquals("HOLD",resource.analysis("v2").get("verdict"));
    }
}

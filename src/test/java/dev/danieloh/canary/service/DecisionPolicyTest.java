package dev.danieloh.canary.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.model.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionPolicyTest {
    final DecisionPolicy policy=new DecisionPolicy();
    Recommendation go(Evidence e){return new Recommendation(Verdict.GO,"Evidence supports progression",List.of(e.id()),List.of());}
    @Test void healthyDevelopmentCanProceed(){var e=RehearsalEvidence.forScene("healthy");assertEquals(Verdict.GO,policy.evaluate(e,go(e),"development",false,Instant.now()).effective());}
    @Test void productionRequiresExplicitApproval(){var e=RehearsalEvidence.forScene("healthy");assertTrue(policy.evaluate(e,go(e),"production",false,Instant.now()).approvalRequired());assertEquals(Verdict.GO,policy.evaluate(e,go(e),"production",true,Instant.now()).effective());}
    @Test void approvalCannotOverrideStaleEvidence(){var e=RehearsalEvidence.forScene("healthy");assertEquals(Verdict.HOLD,policy.evaluate(e,go(e),"production",true,Instant.now().plusSeconds(121)).effective());}
    @Test void approvalCannotOverrideMissingTelemetry(){var e=RehearsalEvidence.forScene("missing");assertEquals(Verdict.HOLD,policy.evaluate(e,go(e),"production",true,Instant.now()).effective());}
    @Test void activeSharedSlowdownStillHolds(){var e=RehearsalEvidence.forScene("slowdown");assertEquals(Verdict.HOLD,policy.evaluate(e,go(e),"development",false,Instant.now()).effective());}
    @Test void inventedEvidenceCannotAuthorizeGo(){var e=RehearsalEvidence.forScene("healthy");var r=new Recommendation(Verdict.GO,"Looks good",List.of("invented"),List.of());assertEquals(Verdict.HOLD,policy.evaluate(e,r,"development",false,Instant.now()).effective());}
    @Test void modelCannotOverrideHardFailure(){var e=RehearsalEvidence.forScene("healthy");var bad=new Evidence(e.id(),e.capturedAt(),e.source(),e.revision(),e.stableRevision(),2,e.stable(),new Signal(100,.15,80,1),e.traces(),e.logs(),List.of(),e.releaseNotes());assertEquals(Verdict.NO_GO,policy.evaluate(bad,go(e),"development",true,Instant.now()).effective());}
    @Test void lowSampleCountHolds(){var e=RehearsalEvidence.forScene("healthy");var sparse=new Evidence(e.id(),e.capturedAt(),e.source(),e.revision(),e.stableRevision(),2,e.stable(),new Signal(2,0,80,1),e.traces(),e.logs(),List.of(),e.releaseNotes());assertEquals(Verdict.HOLD,policy.evaluate(sparse,go(e),"development",false,Instant.now()).effective());}
    @Test void p95UsesNearestRankAndRealAttemptCounts(){var samples=new ArrayList<Map<String,Object>>();for(int i=1;i<=20;i++)samples.add(Map.of("latencyMs",i,"attempts",5,"success",true));var s=EvidenceCollector.summarize(samples);assertEquals(19,s.p95Ms());assertEquals(5,s.attemptsPerCheckout());assertEquals(20,s.requests());}
    Evidence signals(double stableLatency,double canaryLatency,double stableAttempts,double canaryAttempts,double canaryErrors){
        var e=RehearsalEvidence.forScene("healthy");return new Evidence(e.id(),e.capturedAt(),e.source(),e.revision(),e.stableRevision(),2,new Signal(1500,0,stableLatency,stableAttempts),new Signal(56,canaryErrors,canaryLatency,canaryAttempts),e.traces(),e.logs(),List.of(),e.releaseNotes());
    }
    Recommendation noGo(Evidence e){return new Recommendation(Verdict.NO_GO,"Latency threshold breached",List.of(e.id()),List.of());}
    @Test void comparableSharedSlowdownHoldsEvenIfModelRejectsTheRelease(){
        var e=signals(651.9,653.46,1,1,0);
        for(var r:List.of(go(e),noGo(e))){var d=policy.evaluate(e,r,"development",false,Instant.now());assertEquals(Verdict.HOLD,d.effective());assertTrue(d.reason().contains("Both revisions exceed 500 ms"));assertEquals(r.verdict(),d.recommendation());}
    }
    @Test void actualAsymmetricWindowIsNotRelabeledAsSharedSlowdown(){
        var e=signals(4.300255,653.42488,1,1,0);assertEquals(Verdict.NO_GO,policy.evaluate(e,noGo(e),"development",false,Instant.now()).effective());
    }
    @Test void candidateSpecificRetriesErrorsAndLargeLatencyRegressionsRemainRejectable(){
        for(var e:List.of(signals(650,653,1,5,0),signals(650,653,1,1,.005),signals(650,900,1,1,0))){assertEquals(Verdict.NO_GO,policy.evaluate(e,noGo(e),"development",false,Instant.now()).effective());}
    }
    @Test void sharedSlowdownCannotOverrideHardCapOrBePromotedByHumanApproval(){
        var failed=signals(650,653,1,1,.10);assertEquals(Verdict.NO_GO,policy.evaluate(failed,go(failed),"production",true,Instant.now()).effective());
        var e=signals(650,653,1,1,0);var d=policy.evaluate(e,go(e),"production",true,Instant.now());assertEquals(Verdict.HOLD,d.effective());assertFalse(d.approvalRequired());
    }
    @Test void comparableLatencyBoundaryIsExplicit(){
        var comparable=signals(650,780,1,1,0);assertEquals(Verdict.HOLD,policy.evaluate(comparable,noGo(comparable),"development",false,Instant.now()).effective());
        var worse=signals(650,780.01,1,1,0);assertEquals(Verdict.NO_GO,policy.evaluate(worse,noGo(worse),"development",false,Instant.now()).effective());
    }

}

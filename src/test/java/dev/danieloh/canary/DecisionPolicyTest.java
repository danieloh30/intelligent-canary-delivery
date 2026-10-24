package dev.danieloh.canary;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.Domain.*;
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
}

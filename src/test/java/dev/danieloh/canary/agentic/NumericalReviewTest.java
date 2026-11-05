package dev.danieloh.canary.agentic;

import dev.danieloh.canary.model.Domain.*;
import dev.danieloh.canary.model.Evaluation;
import dev.danieloh.canary.service.RehearsalEvidence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NumericalReviewTest {
    Evidence snapshot(long requests,double latency) {
        var e=RehearsalEvidence.forScene("healthy");
        return new Evidence(e.id(),e.capturedAt(),e.source(),"v2","v1",5,
            new Signal(898,0,2.227856,1),new Signal(requests,0,latency,1),e.traces(),e.logs(),List.of(),e.releaseNotes());
    }
    InvestigationWorkflow.Report report(Evidence e,String text) {
        var f=new Finding(text,List.of(e.id()),List.of());
        return new InvestigationWorkflow.Report(f,f,f,new Recommendation(Verdict.HOLD,text,List.of(e.id()),List.of()));
    }
    @Test void capturedHealthyWindowHasExplicitPassingComparisons() {
        var e=snapshot(539,7.797314);var facts=NumericalReview.facts(new Evaluation(e,"development","live"));
        var latency=(NumericalReview.Check)facts.get("canaryLatency");
        assertTrue(latency.passes());assertEquals("7.797314 ms <= 500 ms",latency.comparison());
        assertFalse(NumericalReview.issues(e,report(e,"Canary latency exceeding the 500 ms threshold")).isEmpty());
        assertTrue(NumericalReview.issues(e,report(e,"Canary latency is higher than stable but does not exceed the 500 ms limit")).isEmpty());
    }
    @Test void captured58RequestsAreNotBelow30() {
        var e=snapshot(58,583.638849);
        assertFalse(NumericalReview.issues(e,report(e,"Insufficient requests in canary revision (58 requests, minimum required is 30)")).isEmpty());
        assertFalse(NumericalReview.issues(e,report(e,"58 requests does not meet the minimum 30 requests")).isEmpty());
        assertTrue(NumericalReview.issues(e,report(e,"Canary p95 latency exceeds the 500 ms limit")).isEmpty());
    }
    @Test void realShortageAndGenuineUnknownsAreNotSilentlyCleared() {
        var e=snapshot(29,7.8);
        assertTrue(NumericalReview.issues(e,report(e,"Insufficient requests in canary revision")).isEmpty());
        assertTrue(NumericalReview.issues(e,report(e,"Dependency cause cannot be determined; trace coverage is incomplete")).isEmpty());
    }
    @Test void capturedSuccessfulRequestAndLowLatencyClaimsAreNotSampleShortages() {
        var e=snapshot(562,6.1);
        for(var claim:List.of(
            "The investigation into dependencies for the canary revision (v2) shows that all requests were successful with latencies well below the 500 ms threshold",
            "The runbook confirms that the prerequisites for proceeding with the release are met, as both revisions have sufficient requests and latency is below the threshold",
            "The canary revision has sufficient requests and maintains stable performance with latencies well below the 500 ms limit",
            "Both revisions exceed the minimum request requirement of 30 requests and maintain latencies well below the 500 ms limit",
            "The investigation into dependencies for the canary revision (v2) indicates that all requests were successful, with latencies well below the 500 ms threshold",
            "Latency is 6 ms and sample counts are above the minimum threshold of 30 requests",
            "The request count is sufficient, while latency is less than 500 ms"))
            assertTrue(NumericalReview.issues(e,report(e,claim)).isEmpty(),claim);
    }
    @Test void onlyDirectThresholdClaimsAreFlaggedAcrossMixedMetrics() {
        var e=snapshot(58,6.1);
        for(var claim:List.of("Requests are below the minimum 30", "58 requests does not meet the minimum 30 requests", "Insufficient canary requests", "Canary p95 latency is above the 500 ms threshold", "Canary latency of 6.1 ms exceeds the 500 ms safety limit"))
            assertFalse(NumericalReview.issues(e,report(e,claim)).isEmpty(),claim);
        assertTrue(NumericalReview.issues(e,report(e,"Canary latency is higher than stable but below the 500 ms limit")).isEmpty());
    }

}

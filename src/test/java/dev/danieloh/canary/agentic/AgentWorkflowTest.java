package dev.danieloh.canary.agentic;

import dev.danieloh.canary.model.Domain;
import dev.danieloh.canary.service.InvestigationService;
import dev.danieloh.canary.support.MockOpenAi;
import dev.danieloh.canary.service.RehearsalEvidence;

import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AgentWorkflowTest.LiveProtocolProfile.class)
@QuarkusTestResource(value=MockOpenAi.class,restrictToAnnotatedClass=true)
class AgentWorkflowTest {
    public static class LiveProtocolProfile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.of("demo.ai-mode","live");}
    }
    @Inject InvestigationService investigations;
    @Test void fourRealAgentsUseTheirToolsAndSynthesize() throws Exception {
        var e=investigations.start(RehearsalEvidence.forScene("retry"),"development");
        for(int i=0;i<400 && e.status.equals("RUNNING");i++)Thread.sleep(50);
        assertEquals("COMPLETE",e.status);
        assertEquals(3,e.findings.size(),"Specialists must all return findings");
        assertTrue(e.findings.get("dependency").summary().startsWith("Dependency"),"Dependency role must use its own prompt and finding");
        assertTrue(e.findings.get("release").summary().startsWith("Release"),"Release role must use its own prompt and finding");
        assertTrue(e.toolReads.containsAll(Set.of("metrics-tool","trace-tool","log-tool","release-tool","runbook-tool")));
        assertEquals(Domain.Verdict.NO_GO,e.decision.effective());
        assertTrue(MockOpenAi.requests.get()>=7,"Real model protocol must run, including tool result turns");
        var second=investigations.start(RehearsalEvidence.forScene("retry"),"development");
        for(int i=0;i<400 && second.status.equals("RUNNING");i++)Thread.sleep(50);
        assertEquals(Domain.Verdict.NO_GO,second.decision.effective(),"New investigation must have isolated model memory and evidence");
        assertTrue(second.toolReads.containsAll(Set.of("metrics-tool","trace-tool","log-tool","release-tool","runbook-tool")));
    }
    @Test void numericalMistakesAreCorrectedOnceWithoutAnotherPresenterClick() throws Exception {
        for(var scenario:List.of("latency","sample","persistent")) {
            MockOpenAi.numericalScenario=scenario;
            try {
                var v=RehearsalEvidence.forScene("healthy");
                var evidence=new Domain.Evidence(v.id(),v.capturedAt(),v.source(),"v2","v1",5,
                    new Domain.Signal(898,0,2.227856,1),new Domain.Signal(58,0,7.797314,1),v.traces(),v.logs(),List.of(),v.releaseNotes());
                var e=investigations.start(evidence,"development");
                for(int i=0;i<600 && e.status.equals("RUNNING");i++)Thread.sleep(50);
                assertEquals("COMPLETE",e.status);
                assertEquals(2,e.numericalReviews.size(),"Only one correction pass is permitted");
                assertFalse(e.numericalReviews.getFirst().issues().isEmpty());
                assertTrue(e.toolCalls.get()<=12,"Correction cannot expand the tool budget");
                if(scenario.equals("persistent")) {
                    assertEquals(Domain.Verdict.HOLD,e.decision.effective());
                    assertTrue(e.recommendation.summary().contains("Numerical contradictions remain"));
                } else {
                    assertTrue(e.numericalReviews.getLast().issues().isEmpty());
                    assertEquals(Domain.Verdict.GO,e.decision.effective());
                }
            } finally {MockOpenAi.numericalScenario="";}
        }
    }

}

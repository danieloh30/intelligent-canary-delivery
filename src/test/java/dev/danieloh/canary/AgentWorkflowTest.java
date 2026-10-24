package dev.danieloh.canary;

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
        assertTrue(e.toolReads.containsAll(Set.of("metrics-tool","trace-tool","log-tool","release-tool","runbook-tool")));
        assertEquals(Domain.Verdict.NO_GO,e.decision.effective());
        assertTrue(MockOpenAi.requests.get()>=7,"Real model protocol must run, including tool result turns");
        var second=investigations.start(RehearsalEvidence.forScene("retry"),"development");
        for(int i=0;i<400 && second.status.equals("RUNNING");i++)Thread.sleep(50);
        assertEquals(Domain.Verdict.NO_GO,second.decision.effective(),"New investigation must have isolated model memory and evidence");
        assertTrue(second.toolReads.containsAll(Set.of("metrics-tool","trace-tool","log-tool","release-tool","runbook-tool")));
    }
}

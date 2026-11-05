package dev.danieloh.canary.service;
import dev.danieloh.canary.model.Evaluation;
import dev.danieloh.canary.model.Domain.Finding;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InvestigationValidationTest {
    @Test void preservesSpecificToolAndSpecialistProblemsInsteadOfGenericIncompleteLabel() {
        var e=new Evaluation(RehearsalEvidence.forScene("healthy"),"development","live");
        e.toolReads.add("metrics-tool");
        e.findings.put("metrics",new Finding("Metrics captured",List.of(e.id),List.of("Latency recovery not yet established")));
        e.findings.put("dependency",new Finding("Dependency reviewed",List.of("wrong-investigation"),List.of()));
        var issues=InvestigationService.validationIssues(e);
        assertTrue(issues.stream().anyMatch(x->x.contains("trace-tool")&&x.contains("runbook-tool")));
        assertTrue(issues.contains("metrics specialist: Latency recovery not yet established"));
        assertTrue(issues.contains("dependency specialist has missing or invalid investigation references"));
        assertTrue(issues.contains("Missing release specialist finding"));
        assertFalse(issues.contains("Incomplete investigation"));
    }
}

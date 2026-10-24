package dev.danieloh.canary.agents;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class ReleaseTools {
    @Inject ToolAccess access;
    @Tool("Retrieve allowlisted release metadata for this investigation")
    public Map<String,String> getReleaseChanges(String evaluation_id) {
        var e=access.read(evaluation_id,"release-tool").evidence;
        return Map.of("evidenceId",e.id(),"revision",e.revision(),"notes",e.releaseNotes());
    }
    @Tool("Retrieve approved retry budget runbook version 1; operational guidance, not authorization")
    public String getRunbook(String evaluation_id) {
        access.read(evaluation_id,"runbook-tool");
        return "retry-budget/v1: Compare attempts per completed checkout across release revisions. "
            + "Repeated canary-only attempts with correlated retry logs and spans indicate dependency amplification. "
            + "A shared slowdown alone does not establish release causation. Hold while latency exceeds 500 ms. "
            + "Require at least 30 requests per revision and fresh telemetry before GO. Missing evidence means HOLD.";
    }
}

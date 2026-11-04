package dev.danieloh.canary.agentic;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class MetricsTools {
    @Inject ToolAccess access;
    @Tool("Compare stable and canary metrics in the investigation's frozen time window")
    public Map<String,Object> compareRevisionMetrics(String evaluation_id) {
        var evaluation = access.read(evaluation_id, "metrics-tool");
        var e = evaluation.evidence;
        return Map.of("evidenceId", e.id(), "source", e.source(), "capturedAt", e.capturedAt().toString(),
                "stable", e.stable(), "canary", e.canary(), "canaryRevision", e.revision(), "missing", e.missing(), "computedChecks", NumericalReview.facts(evaluation));
    }
}

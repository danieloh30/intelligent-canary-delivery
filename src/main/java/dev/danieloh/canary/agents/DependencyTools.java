package dev.danieloh.canary.agents;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class DependencyTools {
    @Inject ToolAccess access;
    @Tool("Read sampled checkout request observations carrying real OpenTelemetry trace IDs; full spans are in Jaeger")
    public List<Map<String,Object>> findDependencyTraces(String evaluation_id) {
        return access.read(evaluation_id,"trace-tool").evidence.traces();
    }
    @Tool("Read bounded checkout retry log records correlated by trace ID in the same observation window")
    public List<String> findRetryLogs(String evaluation_id) {
        return access.read(evaluation_id,"log-tool").evidence.logs();
    }
}

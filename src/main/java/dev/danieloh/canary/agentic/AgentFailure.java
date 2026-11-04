package dev.danieloh.canary.agentic;

import java.util.*;

/** Safe diagnostics: never publish exception messages containing provider bodies or credentials. */
public record AgentFailure(int attempt, String category, String message, List<String> causes, boolean retryScheduled) {
    public static AgentFailure describe(Throwable error,int attempt,boolean allowRetry) {
        List<String> causes=new ArrayList<>();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable t=error;t!=null && seen.add(t) && causes.size()<12;t=t.getCause())causes.add(t.getClass().getSimpleName());
        String names=String.join(" → ",causes);
        boolean parsing=causes.stream().anyMatch(n->n.contains("Parsing")||n.contains("JsonParse")||n.contains("JsonMapping")||n.contains("MismatchedInput"));
        boolean timeout=causes.stream().anyMatch(n->n.contains("Timeout"));
        boolean transientFailure=causes.stream().anyMatch(n->n.contains("ConnectException")||n.contains("RateLimit")||n.contains("InternalServer"));
        String category=parsing?"INVALID_MODEL_OUTPUT":timeout?"TIMEOUT":transientFailure?"MODEL_SERVICE_UNAVAILABLE":"WORKFLOW_EXECUTION_FAILED";
        String message=parsing?"An agent response could not be parsed into the required finding or recommendation":timeout?"Agent execution exceeded a time limit":transientFailure?"The model service was temporarily unavailable":"The agent workflow failed before completing its findings";
        return new AgentFailure(attempt,category,message+" ("+names+")",List.copyOf(causes),allowRetry&&(parsing||timeout||transientFailure));
    }
}

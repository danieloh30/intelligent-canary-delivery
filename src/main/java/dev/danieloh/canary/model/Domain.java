package dev.danieloh.canary.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {}
    public enum Verdict { GO, HOLD, NO_GO }
    public record HumanReview(String action, Instant at, String actor) {}
    public record Signal(long requests, double errorRate, double p95Ms, double attemptsPerCheckout) {}
    public record Evidence(String id, Instant capturedAt, String source, String revision,
                           String stableRevision, int rolloutStep, Signal stable, Signal canary,
                           List<Map<String,Object>> traces, List<String> logs, List<String> missing,
                           String releaseNotes, String canaryPodHash) {
        public Evidence(String id,Instant capturedAt,String source,String revision,String stableRevision,int rolloutStep,
                        Signal stable,Signal canary,List<Map<String,Object>> traces,List<String> logs,List<String> missing,String releaseNotes) {
            this(id,capturedAt,source,revision,stableRevision,rolloutStep,stable,canary,traces,logs,missing,releaseNotes,"");
        }
        public boolean matchesPodHash(String hash) {
            if(hash==null||hash.isBlank())return false;
            if(canaryPodHash!=null&&!canaryPodHash.isBlank())return hash.equals(canaryPodHash);
            return traces.stream().anyMatch(x->hash.equals(x.get("podHash"))&&!"stable".equals(x.get("cohort")));
        }
        public Evidence withoutTraceEvidence(String reason) {
            var gaps=new java.util.ArrayList<>(missing);gaps.add(reason);
            return new Evidence(id,capturedAt,source,revision,stableRevision,rolloutStep,stable,canary,List.of(),logs,List.copyOf(gaps),releaseNotes,canaryPodHash);
        }
    }
    public record Finding(String summary, List<String> evidenceIds, List<String> limitations) {}
    public record Recommendation(Verdict verdict, String summary, List<String> evidenceIds, List<String> limitations) {}
    public record Decision(Verdict recommendation, Verdict effective, String reason, boolean approvalRequired,
                           Instant expiresAt, String policy) {}
    public record Event(long sequence, Instant at, String actor, String message) {}
}

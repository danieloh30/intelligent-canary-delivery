package dev.danieloh.canary.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {}
    public enum Verdict { GO, HOLD, NO_GO }
    public record Signal(long requests, double errorRate, double p95Ms, double attemptsPerCheckout) {}
    public record Evidence(String id, Instant capturedAt, String source, String revision,
                           String stableRevision, int rolloutStep, Signal stable, Signal canary,
                           List<Map<String,Object>> traces, List<String> logs, List<String> missing,
                           String releaseNotes) {}
    public record Finding(String summary, List<String> evidenceIds, List<String> limitations) {}
    public record Recommendation(Verdict verdict, String summary, List<String> evidenceIds, List<String> limitations) {}
    public record Decision(Verdict recommendation, Verdict effective, String reason, boolean approvalRequired,
                           Instant expiresAt, String policy) {}
    public record Event(long sequence, Instant at, String actor, String message) {}
}

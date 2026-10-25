package dev.danieloh.canary.model;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.danieloh.canary.model.Domain.*;

public class Evaluation {
    public final String id;
    public final Instant startedAt = Instant.now();
    public final Evidence evidence;
    public final String profile;
    public final String mode;
    public volatile String status = "RUNNING";
    public final Map<String, Finding> findings = new ConcurrentHashMap<>();
    public final Map<String, String> agents = new ConcurrentHashMap<>();
    public final Set<String> toolReads = ConcurrentHashMap.newKeySet();
    public final AtomicInteger toolCalls = new AtomicInteger();
    public volatile Recommendation recommendation;
    public volatile Decision decision;
    public Evaluation(Evidence evidence, String profile, String mode) {
        this.id = evidence.id(); this.evidence = evidence; this.profile = profile; this.mode = mode;
        for (var name : List.of("metrics", "dependency", "release", "decision")) agents.put(name, "QUEUED");
    }
}

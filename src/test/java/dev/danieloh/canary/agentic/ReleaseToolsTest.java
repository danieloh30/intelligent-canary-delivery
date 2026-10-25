package dev.danieloh.canary.agentic;

import dev.danieloh.canary.model.Domain.Evidence;
import dev.danieloh.canary.model.Domain.Signal;
import dev.danieloh.canary.model.Evaluation;
import dev.danieloh.canary.service.DemoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the actual evidence supplied to the release agent, independently of model prose. */
class ReleaseToolsTest {
    private DemoStore store;
    private ReleaseTools tools;

    @BeforeEach void wireToolBoundary() {
        store = new DemoStore();
        ToolAccess access = new ToolAccess();
        access.store = store;
        tools = new ReleaseTools();
        tools.access = access;
    }

    private Evaluation snapshot(String id, int canaryRequests, List<String> missing) {
        Evidence evidence = new Evidence(id, Instant.parse("2026-10-24T12:34:56Z"),
            "LIVE_KUBERNETES_REQUESTS", "v2", "stable-hash", 2,
            new Signal(941, 0, 8.5, 1), new Signal(canaryRequests, 0, 9.25, 1),
            List.of(Map.of("traceId", "trace-distinct-from-evaluation", "cohort", "canary")),
            List.of("correlated request log"), missing, "v2: serialization optimization");
        Evaluation evaluation = new Evaluation(evidence, "development", "live");
        store.add(evaluation);
        return evaluation;
    }

    @Test void healthyReleaseReceivesFrozenFactsWithoutInventedRunbookLimitations() {
        Evaluation healthy = snapshot("healthy-evaluation", 162, List.of());
        // A newer investigation must not replace the explicitly requested snapshot.
        snapshot("newer-missing-evaluation", 7, List.of("trace evidence unavailable"));

        Map<String,Object> result = tools.getReleaseChanges(healthy.id);

        assertEquals(healthy.id, result.get("evidenceId"));
        assertEquals("v2", result.get("revision"));
        assertEquals("2026-10-24T12:34:56Z", result.get("capturedAt"));
        assertEquals("v2: serialization optimization", result.get("notes"));
        assertEquals(healthy.evidence.stable(), result.get("stable"));
        assertEquals(new Signal(162, 0, 9.25, 1), result.get("canary"));
        assertEquals(List.of(), result.get("missingEvidence"),
            "Generic runbook cautions must not be reported as missing evidence for a healthy snapshot");
        assertAuditedRead(healthy);
    }

    @Test void actualMissingEvidenceRemainsVisibleAlongsideObservedMetrics() {
        List<String> missing = List.of("trace evidence unavailable", "one canary pod could not be read");
        Evaluation incomplete = snapshot("incomplete-evaluation", 17, missing);

        Map<String,Object> result = tools.getReleaseChanges(incomplete.id);

        assertEquals(incomplete.id, result.get("evidenceId"));
        assertEquals(incomplete.evidence.capturedAt().toString(), result.get("capturedAt"));
        assertEquals(missing, result.get("missingEvidence"),
            "The agent must receive concrete evidence defects without silently clearing them");
        assertEquals(incomplete.evidence.stable(), result.get("stable"));
        assertEquals(new Signal(17, 0, 9.25, 1), result.get("canary"));
        assertAuditedRead(incomplete);
    }

    private void assertAuditedRead(Evaluation evaluation) {
        assertEquals(1, evaluation.toolCalls.get());
        assertTrue(evaluation.toolReads.contains("release-tool"));
        assertTrue(store.events().stream().anyMatch(event -> event.actor().equals("release-tool")
            && event.message().equals("Read frozen evidence " + evaluation.id)));
    }
}

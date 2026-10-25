package dev.danieloh.canary.service;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.model.Domain.*;

/** Explicit offline fixtures; never eligible for an Argo analysis result. */
public final class RehearsalEvidence {
    private RehearsalEvidence() {}
    public static Evidence forScene(String scene) {
        boolean slow=scene.equals("slowdown"), retry=scene.equals("retry"), missing=scene.equals("missing");
        String id=UUID.randomUUID().toString();
        Signal stable=new Signal(920,0,slow?740:85,1);
        Signal canary=new Signal(110,0,slow?760:retry?130:80,retry?5:1);
        return new Evidence(id,Instant.now(),"REHEARSAL_FIXTURE",retry?"v3":"v2","v1",2,stable,canary,
            missing?List.of():List.of(Map.of("traceId","fixture-stable","revision","v1","attempts",1),
                 Map.of("traceId","fixture-canary","revision",retry?"v3":"v2","attempts",retry?5:1)),
            List.of(retry?"fixture-canary inventory retry attempts=5":"fixture-stable and fixture-canary share inventory timing"),
            missing?List.of("traces unavailable"):List.of(),
            retry?"v3: retry policy changed to five attempts":"v2: response serialization optimization");
    }
}

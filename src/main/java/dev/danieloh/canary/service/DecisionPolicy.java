package dev.danieloh.canary.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.*;
import static dev.danieloh.canary.model.Domain.*;

@ApplicationScoped
public class DecisionPolicy {
    public Decision evaluate(Evidence e, Recommendation r, String profile, boolean approved, Instant now) {
        Instant expiry = e.capturedAt().plusSeconds(120);
        if (now.isAfter(expiry) || e.capturedAt().isAfter(now.plusSeconds(5)))
            return result(r, Verdict.HOLD, "Evidence is stale or clock skew is invalid", false, expiry, profile);
        if (!e.missing().isEmpty() || e.stable().requests() < 30 || e.canary().requests() < 30)
            return result(r, Verdict.HOLD, "Required evidence or minimum 30 requests per revision is missing", false, expiry, profile);
        if (e.canary().errorRate() >= 0.10)
            return result(r, Verdict.NO_GO, "Hard safety cap: canary errors reached 10%", false, expiry, profile);
        if (r == null || r.verdict() == null || r.summary() == null || r.summary().isBlank()
                || r.evidenceIds() == null || r.evidenceIds().isEmpty()
                || r.evidenceIds().stream().anyMatch(id -> !id.equals(e.id())))
            return result(r, Verdict.HOLD, "Recommendation lacks verifiable evidence references", false, expiry, profile);
        if (r.limitations() == null || !r.limitations().isEmpty())
            return result(r, Verdict.HOLD, "Recommendation reports unresolved limitations", false, expiry, profile);
        if (r.verdict() == Verdict.GO && (e.canary().p95Ms() > 500 || e.canary().errorRate() > 0.01))
            return result(r, Verdict.HOLD, "Customer health has not recovered; collect another window", false, expiry, profile);
        if (r.verdict() == Verdict.GO && profile.equals("production") && !approved)
            return result(r, Verdict.HOLD, "Production promotion requires presenter approval", true, expiry, profile);
        return result(r, r.verdict(), "Evidence and platform policy accepted", false, expiry, profile);
    }
    private Decision result(Recommendation r, Verdict v, String reason, boolean approval, Instant expiry, String profile) {
        return new Decision(r == null ? Verdict.HOLD : r.verdict(), v, reason, approval, expiry, profile);
    }
}

<div class="chapter-meta">05 / PRODUCTION DESIGN / AUTOMATION WITH BOUNDED AUTHORITY</div>

# Plan production automation

The conference demo uses **manual investigation** at 10% and 50%. An automatic implementation can start the same four-agent investigation from a server-side trigger. The authority boundary stays the same: evidence supports a recommendation, Decision Policy determines the effective result, and Argo controls the rollout.

**This chapter describes proposed production changes. Automatic investigations are not enabled in this repository.** Open **Production tips** in the SPA's left navigation for the conference discussion version.

## 1. Separate investigation from promotion

There are two independent questions: **who starts the investigation**, and **who authorizes an eligible promotion**.

### Manual investigation — current conference flow

The presenter waits for readiness, optionally injects a controlled fault, and starts the agents at each gate. Development permits an eligible GO; Production waits for a human review.

**Benefits:** deliberate timing, an easy-to-follow evidence trail, and model calls only when the presenter chooses to investigate. This is useful when explaining why a green HTTP dashboard can conceal retry amplification.

**Tradeoffs:** the operator must be available, repeated recovery investigations require clicks, and the gate can exhaust its analysis window while waiting. Keeping the browser open does not itself start the agents.

### Automatic investigation with human review — suggested adoption starting point

A coordinator starts reasoning once an active gate has complete, fresh observations. A Production GO still waits for **Approve promotion** or **Decline promotion**.

**Benefits:** no investigation click to miss, consistent stage handling, and a person still owns the production authorization decision.

**Tradeoffs:** automatic triggers require durable work tracking, duplicate suppression, bounded retries, and observable failures. Human review still adds waiting time. The demo’s 120-second expiry starts at evidence capture, so agent execution already consumes part of the review window. If the evidence expires during that wait, collect new evidence and obtain a new review; an earlier approval cannot transfer to a changed investigation.

### Automatic investigation with policy-authorized promotion

For changes an organization has classified as suitable for unattended promotion, a versioned policy can accept an eligible GO automatically.

**Benefits:** less operator delay and consistent handling across releases and stages.

**Tradeoffs:** evidence quality, policy coverage, model evaluation, and recovery controls carry more responsibility. Automatic operation can increase model usage and propagate a faulty policy repeatedly. Missing evidence or an execution error must never silently become GO.

This needs an explicit production risk policy. The demo's **Production / approval** profile continues to require human review; **Development / no approval** is not a production-readiness certification.

## 2. Add an automatic trigger outside the browser

My recommended starting design is a **server-side coordinator with the existing Web status gate**. A Kubernetes watch or reconciliation loop discovers active AnalysisRuns. An asynchronous worker performs the investigation; Argo keeps reading its saved effective decision.

1. **Reconcile the live gate.** Require the intended namespace, Rollout identity, current candidate, stable baseline, current step, and an active AnalysisRun. Watch events may be duplicated or missed across reconnections, so re-read current state and periodically reconcile.
2. **Check inexpensive prerequisites before calling the model.** Require complete telemetry, sufficient observations, and any explicitly authorized experiment precondition. Collect observations for the current stage. This demo uses a rolling 60-second window and at least 30 requests per cohort; production sample sizes and minimum observation durations need workload-specific validation.
3. **Atomically claim an attempt in durable storage.** Repeated events and multiple replicas must find existing work instead of starting more agents. Enqueue bounded work after the claim; design recovery for a crash between claiming and dispatching.
4. **Capture one evidence snapshot.** Recheck the gate identity, then freeze metrics, trace-linked observations, logs, release context, and runbook versions. All four specialists must reason over that snapshot.
5. **Run the bounded workflow and final policy.** Reuse the agent workflow and deterministic Decision Policy. Persist the findings, references, tool reads, limitations, and effective result. Human review remains a separate step when policy requires it.
6. **Serve cached status quickly.** Argo reads GO, HOLD, or NO_GO; it does not need to wait inside an HTTP request for LLM execution. Reject expired results and results belonging to another gate.
7. **Create fresh work at 50%.** A successful 10% investigation does not authorize the next stage, even when the visible release version remains v2.

The existing [Web provider](https://argoproj.github.io/argo-rollouts/analysis/web/) obtains a measurement from an HTTP response and applies success/failure conditions. In this repo, `deploy/gitops/analysis.yaml` polls `GET /api/analysis` every five seconds, up to 180 measurements. **Those are status checks, not 180 LLM investigations.**

### Use a complete gate identity

A proposed durable key should include:

```text
cluster identity / namespace / Rollout UID /
candidate pod-template hash / stable hash /
rollout step index / AnalysisRun UID
```

A release label such as **v2** is insufficient: a new v2 release creates a different rollout instance. Track a bounded capture-attempt number within a gate for justified re-investigation. Record model, prompt, tool-schema, runbook, and policy versions with each attempt. Changes to authorization inputs must invalidate any pending review that depended on the old values.

### Handle HOLD without cherry-picking

An evidence-based HOLD should wait for changed evidence: recovery after shared degradation, enough new traffic, or restored telemetry. Apply a cooldown, maximum attempts, a total time budget, and escalation when the gate cannot resolve. An execution failure can receive a bounded retry against the same frozen evidence, with both attempts retained.

**Do not reroll the model on unchanged evidence until it happens to return GO.** A human decline or an effective NO_GO stops that candidate; another attempt after a rejection needs an explicitly authorized new release or retry flow.

### Illustrative coordinator algorithm

This is design pseudocode, not an implemented API or drop-in Java class:

```text
on gate event or reconciliation tick:
    gate = read the current Rollout and active AnalysisRun
    if gate is finished, superseded, or already rejected: stop
    if prerequisites are not ready: expose HOLD and wait
    attempt = atomically claim bounded work for this gate
    if work already exists: return its saved status
    enqueue attempt with a deadline

worker:
    recheck gate identity and collect current-stage evidence
    if required evidence is missing: persist HOLD without authorizing promotion
    run the four-agent workflow within its budgets
    evaluate Decision Policy, including any human-review requirement
    save results only for the still-current attempt and gate

status request:
    return the current saved effective decision
    return HOLD if absent, expired, incomplete, or bound to another gate
```

## 3. Choose an execution mechanism

### Coordinator plus Web status endpoint

The repo already uses **Quarkus Kubernetes Client** and **Scheduler**. A prototype can watch AnalysisRuns or periodically reconcile and dispatch `InvestigationService` after readiness. Preserve the cheap status-read role of `GET /api/analysis`.

`@Scheduled(concurrentExecution = SKIP)` prevents overlapping execution **within one application instance**. It does not coordinate replicas. Production needs durable atomic claims and a strategy for leases, crash recovery, cancellation, and dispatch. [Quarkus Scheduler documents this scope](https://quarkus.io/guides/scheduler-reference#concurrent_execution). [Kubernetes Client](https://quarkus.io/guides/kubernetes-client) provides watch APIs and Kubernetes access patterns. [Quartz](https://quarkus.io/guides/quartz) with a database is another scheduling option when clustered scheduling is appropriate; idempotency is still needed.

**Tradeoff:** this avoids launching a new pod for every gate, but the shared coordinator owns queueing, availability, and work recovery.

### Argo Job analysis provider

An [Argo Job provider](https://argoproj.github.io/argo-rollouts/analysis/job/) can launch a bounded Kubernetes Job for a gate. Its client would submit an idempotent investigation request to the Quarkus service and wait for the saved effective result.

**Tradeoff:** a Job gives an explicit execution object, separate resources, and a useful operations trail, with pod startup and lifecycle overhead. The provider considers a successfully completed zero-exit Job successful. HOLD must keep waiting rather than exit successfully. A failed or timed-out Job is a failure outcome, which differs from the Web gate's continuing inconclusive HOLD measurements; decide and test that behavior explicitly.

The current `POST /api/evaluate` is a manual start endpoint and is not a durable idempotent submission API. Add that contract before using it from retrying jobs.

### Idempotent Web POST adapter

Argo's [Web provider also supports POST](https://argoproj.github.io/argo-rollouts/analysis/web/#optional-web-methods). A future ensure-investigation endpoint could atomically enqueue once and immediately return HOLD or cached status. Subsequent measurements would return the same attempt instead of dispatching new agents.

**Tradeoff:** fewer moving pieces in the AnalysisTemplate, but duplicate-request handling and quick responses become essential. This adapter does not exist in the demo. Changing GET to POST alone would not implement safe automation.

## 4. Coordinate the slowdown experiment

An automatic healthy investigation could return GO before a presenter injects the slowdown. Use an explicit experiment precondition in a controlled demo or staging workflow:

1. Arm the authorized scenario before the automatic investigation can start at 10%.
2. Enter the 10% gate and confirm normal sampling readiness.
3. Inject the fault with separately scoped authority.
4. Wait for the intended affected evidence in both cohorts; then release the experiment precondition and start the investigation.
5. After HOLD, clear or let the fault expire. Capture a fresh recovery window after the affected observations have drained, rather than immediately retrying on the same slowdown data.
6. Re-investigate recovery within a bounded attempt budget. At 50%, create a new gate-bound investigation.

For the present manual demo, keep the existing sequence: **10% ready → Inject 25s shared slowdown → both cohorts affected → Investigate**. Automation described here is a proposed alternative.

Normal production automation observes real conditions. It does not inject faults on every deployment. Production resilience experiments need separate authorization, isolation, a defined scope, and recovery controls.

## 5. Adopt autonomy in stages

**First, evaluate in shadow mode.** Compare recommendations with operators and actual release outcomes while independent safety checks remain blocking. Argo's [dry-run analysis metrics](https://argoproj.github.io/argo-rollouts/features/analysis/#dry-run-mode) do not influence rollout success. This demo has one governed analysis metric: preserve or add blocking checks before making that metric non-gating.

**Next, automate investigations with human review.** Measure queue latency, model/tool failures, cost per gate, repeated HOLDs, stale approvals, and false GO/NO_GO outcomes. Add dashboards and alerts for exhausted budgets rather than promoting by default.

**Then, consider unattended promotion for a defined risk class.** Evaluate candidate-specific regressions and shared incidents, replay historical evidence, test restart and duplicate-event behavior, and expand gradually. The thirty-request minimum, 120-second evidence expiry, 85-second orchestration deadline, twelve-tool budget, and fifteen-minute gate window are this demo's settings, not universal production defaults.

### Replace the demo's operational shortcuts

- **Durability:** DemoStore is in memory. A control-pod restart clears its investigation history. Persist attempts, evidence, decisions, approvals, and audit events outside the pod before automating production work.
- **Concurrency:** the demo permits one running investigation in the control service. Production needs explicit per-rollout and global worker/model limits, plus duplicate suppression across replicas.
- **Identity:** use workload identity for automatic submissions and per-user SSO/RBAC for review. The demo's generic presenter actor and token are not a production approval identity system.
- **Authority:** keep agents' tools read-only. Separate release-writing credentials from investigators; Argo remains the rollout controller. Approve or Decline must stay bound to fresh evidence and the same gate.
- **Availability:** test model outages, missing telemetry, worker crashes, late approvals, and cancellations. Preserve HOLD/failure outcomes and escalation; an unavailable model is not evidence of a safe release.

## Conference talk track

> Manual investigation gives this demonstration deliberate timing. A production coordinator can start the same investigation automatically. The agents still recommend, Decision Policy authorizes, and Argo controls traffic. Human review is a separate policy choice.

The design recommendation is to automate the repetitive trigger first, validate the evidence contract, and expand promotion authority only when the organization's risk policy and operational controls support it.

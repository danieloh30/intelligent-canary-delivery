<div class="chapter-meta">04 / PLATFORM GOVERNANCE / BOUNDED AUTHORITY</div>

# Govern the decision

The agent team recommends an outcome. **Decision Policy** determines whether that recommendation is eligible to influence Argo Rollouts. It is implemented in **Java/Quarkus**, primarily in [DecisionPolicy.java](https://github.com/danieloh30/intelligent-canary-delivery/blob/main/src/main/java/dev/danieloh/canary/service/DecisionPolicy.java), with orchestration and release-identity checks at the service/API boundary. It is a deterministic set of application rules, evaluated independently of the LLM’s recommendation.

The policy covers four practical concerns:

- **Traffic and freshness:** at least 30 requests per revision, with decisions expiring 120 seconds after evidence capture.
- **Evidence quality:** HOLD for missing required evidence, invalid references, unsuccessful mandatory tool reads, or unresolved limitations.
- **Customer safety:** enforce error and latency limits; an LLM GO cannot override them.
- **Human approval:** production promotion requires an explicit presenter approval when the recommendation is otherwise eligible.

The SPA’s expandable **Decision Policy** explanation summarizes these rules. Evidence explorer shows their resulting effective decision.

## 1. Demonstrate human-in-the-loop (HITL) approval

This HITL capability already exists. Use it as an **Extended** demonstration after the core shared-slowdown/retry story, so the audience first understands investigation and then sees the authority boundary.

1. At an active analysis gate, wait for the green readiness cue and fresh observations for both revisions.
2. Select **Production / approval** and run the four-agent investigation explicitly.
3. In Evidence explorer, compare the agent’s **GO recommendation** with the policy’s **HOLD effective decision** and approval requirement.
4. Select **Approve promotion** while the evidence is still fresh. The policy rechecks eligibility; a valid approval changes the effective decision to GO.
5. Watch the progress panel advance. A later gate requires a fresh investigation and, when using Production policy, a fresh approval.

When the model recommends GO and all safety requirements pass, the effective result remains **HOLD**. The UI exposes **Approve promotion**. Approving the pending decision runs the policy checks again; it does not bypass them.

<figure markdown>

[![Local rehearsal showing a production GO recommendation awaiting approval](../images/screenshots/06-production-approval.png)](../images/screenshots/06-production-approval.png)

<figcaption>Local rehearsal fixture: Production policy holds an otherwise healthy recommendation pending approval. This SPA capture uses deterministic fixtures, not live LLM output. Approval still rechecks evidence and policy. Select the image for full resolution.</figcaption>
</figure>

The presenter token authorizes this demonstration action. Production identity, role separation, and approval audit storage need their own implementation before using this design outside a demo.

## 2. Withhold required evidence

Switch **Demo mode** to **Extended** and choose **Missing evidence**.

In cluster mode, this scene deliberately withholds trace observations and records a presenter-injected trace evidence outage. Metrics remain observations from real requests. The policy requires HOLD because the mandatory evidence is incomplete.

This is an explicit fault injection, not an unnoticed failure of the trace backend.

## 3. Inspect the deterministic checks

The platform validates:

- Evidence freshness: decisions expire 120 seconds after capture.
- Minimum traffic: at least 30 observed requests per revision.
- Required evidence and successful mandatory tool reads.
- Valid specialist findings and final evidence references.
- The hard 10% canary error cap.
- Customer health before GO: p95 at most 500 ms and errors at most 1%.
- Production approval when applicable.
- The current release, pod-template hash, and rollout step at analysis time.

Model execution has an 85-second orchestration deadline, bounded per-call timeouts, and a 12-tool investigation budget. Failures and unfinished investigations produce HOLD. Local fixture decisions cannot authorize a live rollout.

## 4. Follow the Argo contract

The shared AnalysisTemplate polls the authenticated decision endpoint every five seconds:

- `GO` satisfies the success condition.
- `NO_GO` satisfies the failure condition.
- `HOLD` satisfies neither and remains inconclusive while the measurement budget lasts.

The template allows 180 HTTP checks at five-second intervals—roughly 15 minutes plus execution overhead. These polls read the saved decision; they do not invoke the LLM. A completed inconclusive run does not automatically restart. Fix the cause, then re-enter the same release through GitOps with a fresh pod-template identity or release a new candidate:

```bash
./demo.sh retry-analysis
```

This helper uses the Git release path and requires a clean working tree. It creates a fresh pod-template identity and does not force promotion. The SPA’s release/reset controls provide the corresponding fresh-run path without changing consoles.

## 5. Export the decision record

Use **Export decision record** in the UI. It includes source evidence, findings, recommendation, policy result, and investigation identity.

!!! warning "Demo retention"
    Recent decisions and observations are bounded in-memory data. Prometheus and Jaeger use ephemeral storage. Export records before restarting. Production audit retention, encryption, and access review are separate concerns.

<div class="checkpoint" markdown>

### Checkpoint

You can demonstrate a model recommending GO while policy withholds authority, and explain why approval cannot override stale or missing evidence.

</div>

Review the [presenter playbook](../demo-design.md) to choose the 10-minute or 15-minute flow.

# Presenter playbook

Keep asking the same question: **would you give this release more traffic?** The 10-minute version tells a complete story. The 15-minute version adds depth to the same decisions.

## Before the audience arrives

- Confirm the actual cluster baseline is healthy and traffic is flowing.
- Verify a real GPT-4o mini investigation and all required tools.
- Check the current operator's controller labels and log access.
- Rehearse clearing the fault and draining the 60-second observation window.
- Prepare the explicitly labeled local rehearsal as a fallback.
- Open the dashboard and Argo CD with `./demo.sh present --observability`. Complete OpenShift SSO before going on stage; select `canary-checkout` and leave its resource tree open in the second tab.
- Start the SPA timer when the demonstration begins, not during setup.

## The 10-minute core

| Time | Scene | Audience question |
| --- | --- | --- |
| 0:00–1:00 | SPA baseline → Argo CD Git revision/resource tree → start v2 | What evidence would justify more traffic? |
| 1:00–3:30 | Shared slowdown, HOLD, recovery, GO → Argo CD successful analysis/promotion | Did this release cause the latency increase? |
| 3:30–7:30 | v3 retry regression, NO-GO → Argo CD failed analysis/abort | Can successful requests conceal extra dependency work? |
| 7:30–9:00 | Evidence and policy record | Why should the platform trust this recommendation? |
| 9:00–10:00 | Git change and shared template | What does an application team need to adopt this? |

Model calls, controller reconciliation, and observation windows consume real time. Measure these on your cluster. If the timing cannot fit, shorten optional narration or enter at the prepared retry scenario; do not weaken the evidence or bypass the gate.

## Add five minutes

### Two minutes: follow the investigation

Expand the agent workflow diagram. Use about 30 seconds for the [Prometheus comparisons](reference/observability.md), then 90 seconds to open one request observation, follow its trace ID in Jaeger, and match it to the retry log record. Show the relevant `@Agent` and `@ToolBox` definitions.

### Three minutes: govern autonomy

Apply Production policy to a healthy candidate. Show a GO recommendation held pending approval. Demonstrate that missing evidence still blocks promotion, even when someone wants the rollout to proceed.

## Tell each audience what changed

**Platform engineers:** the organization provides a shared investigation service, central policy, scoped access, and a reusable AnalysisTemplate.

**AI engineers:** the system composes bounded specialists, preserves evidence identity and memory isolation, validates tool use, and records uncertainty.

**Developers:** a small Git release change produces a decision they can inspect, with an explanation of the observed application behavior.

## Rehearse failure as deliberately as success

Verify healthy candidates, shared latency, canary retries, missing traces, sparse traffic, expired evidence, repeated releases, wrong-stage decisions, model outages, and unavailable controller APIs.

Measure recommendation quality, evidence-reference validity, investigation latency, and tool count separately. The current records do not expose a complete token-cost accounting system, so do not present an invented ROI or confidence score.

## Keep the scope honest

This is a bounded decision loop in one cluster. It demonstrates a platform contract; it does not establish multi-cluster scalability, universal causal diagnosis, durable audit retention, or production reliability guarantees.

Shared tools and policies explain how teams can adopt the approach. Add full trace-tree queries, an external log adapter, a versioned evidence archive, and policy distribution when those additions strengthen the decision or developer experience.

## Argo CD UI checkpoints

Keep Argo CD open in a second tab. In the core demo, spend about 20 seconds identifying the Git revision and Rollout at baseline, then 20–30 seconds on the AnalysisRun and stable ReplicaSet after NO-GO. While the recovered observation window is warming, use Argo CD to show that a pending/inconclusive analysis has not authorized promotion. After GO, show a Successful AnalysisRun and progression to the next step. After NO-GO, show the Failed AnalysisRun and aborted/degraded Rollout while the stable ReplicaSet remains available. Synced means desired configuration matches Git; it does not mean the canary passed health analysis.

In the 15-minute version, add one minute to inspect the reasoning-gate AnalysisTemplate, its web-provider URL, and the AnalysisRun result. Take that minute from the extended investigation walkthrough; the production approval and missing-evidence scene remains three minutes. This makes the separation visible: Argo CD reconciles Git, Argo Rollouts executes progressive delivery, agents recommend, and Java policy authorizes the gate.

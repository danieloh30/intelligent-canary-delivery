---
hide:
  - toc
---

<div class="hero" markdown>

<span class="eyebrow">KUBERNETES DELIVERY / JAVA 25 / AGENTIC SYSTEMS</span>

# Give every release<br>an evidence-backed decision.

Build a reasoning layer for canary delivery. Four Java agents investigate the same deployment window, explain what changed, and submit a recommendation to a governed rollout gate.

<div class="hero-actions">
<a class="button button-primary" href="tutorials/01-local/">Start building <span aria-hidden="true">↗</span></a>
<a class="button button-secondary" href="architecture/">Explore the architecture</a>
</div>

<div class="hero-foot"><span>4 specialist agents</span><span>3 rollout outcomes</span><span>10 or 15 minute demo</span></div>

</div>

<div class="intro-line" markdown>

**The question:** should this release receive more traffic?

A brief dependency slowdown can block a healthy candidate. A retry regression can pass HTTP checks while increasing downstream work. This tutorial makes both cases observable, repeatable, and explainable.

</div>

## Follow the investigation

<div class="scenario-grid" markdown>

<article class="scenario-card" markdown>
<span class="step-label">01 / SHARED SLOWDOWN</span>

### A red signal needs context.

Stable and canary both slow down. Compare the revisions, identify the shared dependency, and hold until recovery.

<span class="outcome outcome-hold">HOLD, then GO after recovery</span>
</article>

<article class="scenario-card" markdown>
<span class="step-label">02 / HIDDEN RETRIES</span>

### A green signal needs scrutiny.

Checkout succeeds, but the canary makes repeated inventory calls. Correlate request observations, retry logs, and release guidance.

<span class="outcome outcome-stop">NO-GO for the regression</span>
</article>

<article class="scenario-card" markdown>
<span class="step-label">03 / GOVERNED AUTONOMY</span>

### A recommendation needs authority.

Apply production approval requirements. Withhold promotion when evidence is missing, stale, or belongs to another rollout stage.

<span class="outcome outcome-hold">HOLD until policy is satisfied</span>
</article>

</div>

## Build it in four steps

<div class="learning-path" markdown>

<a href="tutorials/01-local/"><span class="path-number">01</span><span><strong>Run the decision room</strong><small>Start locally, choose live AI or explicit fixtures, and explore the presenter UI.</small></span><span aria-hidden="true">↗</span></a>
<a href="tutorials/02-cluster/"><span class="path-number">02</span><span><strong>Connect delivery to Kubernetes</strong><small>Install GitOps, Argo Rollouts, workloads, and observability on OpenShift.</small></span><span aria-hidden="true">↗</span></a>
<a href="tutorials/03-investigate/"><span class="path-number">03</span><span><strong>Investigate the candidate</strong><small>Run both failure scenarios and follow the four-agent workflow.</small></span><span aria-hidden="true">↗</span></a>
<a href="tutorials/04-govern/"><span class="path-number">04</span><span><strong>Govern the outcome</strong><small>Require fresh evidence, enforce safety limits, and approve eligible production releases.</small></span><span aria-hidden="true">↗</span></a>

</div>

## Three engineering perspectives

<div class="audience-grid" markdown>

<div markdown>
### Platform engineers
Provide a shared analysis service, scoped identities, a reusable rollout template, and centrally enforced decision policy.
</div>

<div markdown>
### AI engineers
Compose `@Agent` specialists with a sequential and parallel workflow. Validate tool use, evidence references, memory isolation, and deadlines.
</div>

<div markdown>
### Application developers
Change a release in Git. Read a concise decision, follow its evidence, and understand what needs to improve before promotion.
</div>

</div>

<div class="scope-note" markdown>

**A reproducible engineering demonstration.** Faults and traffic are synthetic. Cluster-mode requests and telemetry are real. Local fixtures are explicitly labeled and cannot authorize a live rollout. The cluster installer targets OpenShift 4.22 and requires a real-cluster rehearsal.

</div>

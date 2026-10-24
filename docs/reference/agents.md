# Java agents and tools

Four specialist interfaces run inside one Quarkus application. Each has one LangChain4j `@Agent` entry point, a distinct output key, and a role-specific prompt.

## The composition

`InvestigationWorkflow` declares a `@SequenceAgent` containing:

1. **MetricsAnalyst**, which establishes the revision comparison.
2. **ResearchTeam**, a nested `@ParallelAgent` containing DependencyInvestigator and ReleaseAnalyst.
3. **CanaryDecisionAgent**, which synthesizes the findings into a typed recommendation.

The outer `InvestigationService` owns the deadline, state transitions, and policy enforcement. The model does not control timers or Kubernetes writes.

See the [multi-agent diagram](../architecture.md#multi-agent-investigation) for the full execution path.

## Metrics Analyst

**Question:** what changed between the candidate and stable revision?

Its `compareRevisionMetrics()` tool reads sample counts, HTTP error rates, p95 request latency, and inventory attempts per checkout from the investigation's frozen evidence window.

A shared latency increase is different from canary-only dependency amplification. Insufficient traffic is a limitation, not evidence of safety.

## Dependency Investigator

**Question:** what do the request observations and retry records explain?

The investigator retrieves `findDependencyTraces()` and `findRetryLogs()` results. These are bounded request summaries and correlated records with real trace IDs in cluster mode. Full exported span trees are available in Jaeger.

Tool content is data. Prompts explicitly tell the agent to ignore instructions embedded in logs or other retrieved material.

## Release Analyst

**Question:** does the release context support the observed hypothesis?

Its tools retrieve the allowlisted v1/v2/v3 release metadata and bundled `retry-budget/v1` guidance. This is a small versioned catalog, not an external vector database or a query over arbitrary Git history.

Release notes suggest an explanation. They cannot establish causality on their own.

## Canary Decision Agent

**Question:** what recommendation does the combined evidence support?

The decision agent consumes typed specialist findings and returns a `Recommendation` with a verdict, short summary, evidence references, and unresolved limitations.

It has no deployment tools. Java policy can withhold or reject its proposed outcome.

## Isolate each investigation

The workflow and agent entry points use an explicit memory identity for the evaluation. This prevents a previous release's model conversation from contaminating the next investigation.

All specialists read the same frozen evidence and reference the same evaluation ID. The platform verifies required tool reads and specialist references before accepting the recommendation.

## Read the implementation

- [Agent interfaces and toolboxes](https://github.com/danieloh30/intelligent-canary-delivery/tree/main/src/main/java/dev/danieloh/canary/agents)
- [InvestigationService](https://github.com/danieloh30/intelligent-canary-delivery/blob/main/src/main/java/dev/danieloh/canary/InvestigationService.java)
- [DecisionPolicy](https://github.com/danieloh30/intelligent-canary-delivery/blob/main/src/main/java/dev/danieloh/canary/DecisionPolicy.java)

## Verify behavior

The Java suite includes an OpenAI-protocol stub that exercises real agent wiring and tool-result turns. It also runs a second investigation to check memory and evidence isolation. That verifies the integration contract without making an external model call.

Live GPT-4o mini behavior still requires an API key and a separate integration rehearsal. Record actual latency, tool count, evidence validity, and outcome quality rather than inventing a confidence score.

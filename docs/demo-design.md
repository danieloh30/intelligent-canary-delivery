# Presenter design notes

The audience should repeatedly answer: **Would you give this release more traffic?**

The demonstration teaches three distinct skills:

- Platform engineers provide a shared investigation service with central policies and scoped identities.
- AI engineers compose bounded specialists, preserve evidence identity, represent uncertainty, and keep authority outside model output.
- Developers change a release in Git and receive a short explanation tied to the behavior of their application.

## Improve through rehearsal

Measure actual time spent on model calls, observation windows, Git reconciliation, and controller transitions. Tune synthetic request rate and presentation pacing from measurements. Never shorten the evidence window or relax policy just to make the stage demonstration pass.

Evaluate these cases before presenting: healthy candidate, shared slowdown, canary-only retries, missing traces, sparse traffic, expired evidence, incorrect revision, repeated releases of the same version, production approval, model outage, and controller API outage.

Record recommendation accuracy, evidence-reference validity, investigation latency, tool count, and model usage separately. Do not invent model confidence scores or claim milliseconds without measurements.

## Scope boundaries

This project demonstrates a bounded decision loop in one cluster. It does not establish multi-cluster scalability, production SLO guarantees, prompt-injection immunity, durable audit retention, or a universal causal diagnosis engine. Shared tools and policy contracts are the scaling mechanism to explain to the audience.

A future iteration can add revision-specific Prometheus queries, a Loki query adapter, complete trace-tree retrieval, a versioned evidence archive, and policy-as-code distribution. Add each only when it strengthens the on-stage decision or the developer experience.

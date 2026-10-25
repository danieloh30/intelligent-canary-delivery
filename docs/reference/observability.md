<div class="chapter-meta">OBSERVABILITY / LIVE METRICS / TRACE FOLLOW-UP</div>

# Inspect metrics and traces

Use Prometheus and Jaeger as independent evidence views during the demo. OpenShift exposes each through its own SSO-protected Route; no presenter token is required in either UI.

## Prepare the browser tabs

```bash
./demo.sh present --observability
```

This opens the decision room, Argo CD, Prometheus, and Jaeger. Complete any first-use OpenShift login or consent before starting the timer. You can also open each observability view independently:

```bash
./demo.sh prometheus
./demo.sh jaeger
oc -n canary-demo get routes
```

The installer grants the selected presenter permission to get exactly the three UI Services through `canary-demo-presenters`. Separate OAuth clients and cookie keys protect the dashboard and observability views. Internal metric and OTLP ports remain available inside the cluster.

## Prometheus: compare release signals

Wait for fresh scrapes after installation or a Prometheus restart. Under **Query**, run the following expressions. Grouping by both release and pod-template revision distinguishes repeated deployments of the same version.

**HTTP success percentage:**

```promql
100 * sum by (release, revision) (rate(checkout_requests_total{outcome="success"}[1m]))
  / clamp_min(sum by (release, revision) (rate(checkout_requests_total[1m])), 1e-9)
```

**Mean checkout latency in milliseconds:**

```promql
1000 * sum by (release, revision) (rate(checkout_duration_seconds_sum[1m]))
  / clamp_min(sum by (release, revision) (rate(checkout_duration_seconds_count[1m])), 1e-9)
```

**Inventory attempts per checkout request:**

```promql
sum by (release, revision) (rate(checkout_dependency_attempts_total[1m]))
  / clamp_min(sum by (release, revision) (rate(checkout_requests_total[1m])), 1e-9)
```

With healthy traffic, the attempts ratio should be near one. The v3 retry regression raises the canary ratio toward five while HTTP success can remain high. During the shared slowdown, compare the latency trend for both cohorts before attributing the increase to the release.

Prometheus latency here is a **mean**, not p95. The SPA computes p95 from its frozen request observation window; the current timer metrics do not publish histogram buckets for a Prometheus p95 calculation. Rate windows and scrape timing also mean these panels need not match the SPA exactly. If a rate query has no data, check **Status → Targets** and allow enough fresh scrapes for the one-minute range.

## Jaeger: follow one request

1. In the decision room, open **Evidence explorer** and copy a real request `traceId` from a live investigation.
2. Open Jaeger and inspect the `checkout` or `inventory` service, or navigate directly to `https://YOUR_JAEGER_ROUTE/trace/TRACE_ID`.
3. Inspect the checkout request and `inventory.reserve` client spans. Compare `release` and `retry.attempt` attributes for the stable and canary requests.
4. For v3, explain how repeated inventory attempts still produce a successful checkout response.
5. Return to Argo CD to show the Failed AnalysisRun and stable recovery after NO-GO.

Use a recent trace ID. Jaeger retains at most 10,000 traces in memory, evicts older traces as new ones arrive, and loses history on restart. A missing old trace does not prove the request was never traced; collect a fresh investigation if necessary.

## Fit both session lengths

For the **10-minute core**, use a brief Prometheus comparison while the affected observation window drains, then inspect one representative retry trace during the gray-failure explanation. Keep Argo CD open for the controller outcome.

For the **15-minute version**, spend the two-minute investigation extension on approximately 30 seconds of metrics and 90 seconds of trace/log correlation. Preserve the separate three-minute governance scene.

The current agents read frozen request observations with real trace IDs and correlated retry records. They do not directly query Prometheus or complete Jaeger trace trees. These UIs let the audience verify the signals independently and understand what a future external telemetry adapter would add.

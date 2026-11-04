# API and configuration

All presenter and internal evidence endpoints require `Authorization: Bearer TOKEN`. The static SPA shell is public. The OpenAI API key remains in server configuration.

## Presenter API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/state` | Execution mode, release availability, latest evaluation, and recent activity |
| GET | `/api/progress` | Read live stage, analysis check budget, per-cohort counts, and investigation readiness without starting agents |
| GET | `/api/cluster` | Rollout and Argo CD Application status |
| POST | `/api/reset` | Clear faults and current presentation; restore v1 through Git when needed, preserving gates |
| POST | `/api/release/{version}` | Commit v1/v2/v3 to GitHub, request immediate Argo CD refresh; return version, SHA, commit URL, release identity, and refresh status |
| POST | `/api/evaluate` | Start an investigation |
| GET | `/api/evaluations/{id}` | Inspect one investigation |
| POST | `/api/evaluations/{id}/approve` | Approve an eligible pending production decision |
| GET | `/api/logs/{source}` | Read a bounded Kubernetes log tail |
| POST | `/api/fault/{seconds}` | Inject or clear the shared inventory slowdown |
| GET | `/api/analysis?revision=v2` | Return the effective verdict for Argo analysis |

Reset and release actions reject concurrent investigations. A reset on an already Healthy stable v1 does not create a new rollout. Offline reset does not touch Git or Kubernetes. `/api/state` includes `resetEnabled` and `releaseIntent`; `/api/cluster` includes actual `stableVersion` and `candidateVersion` labels. The empty initial decision is not a GO: `/api/analysis` remains HOLD until eligible fresh evidence exists.

Dashboard release responses include `refreshRequested` as the string `"true"` or `"false"`. The server requests a hard refresh of the named `canary-checkout` Application only after Git accepts the commit. A failed refresh does not roll back or invalidate that commit; the response reports the automatic-sync fallback. The control service needs its existing Application read permission plus the named-Application patch Role installed by `scripts/configure-release-refresh.sh`. This presenter action is not an agent tool, does not patch the Rollout, and does not bypass analysis or approval gates.

Cluster evidence includes `canaryPodHash`, captured from Rollout status independently of trace observations. Withholding traces preserves this metadata and records the missing prerequisite; it does not turn a current HOLD into an unrelated historical investigation. The SPA, progress guidance, and analysis endpoint still reject mismatched rollout stages, pod-template identities, and expired authorization. Required-evidence failures identify the actual gap separately from insufficient request counts.

## Start an investigation

The endpoint accepts query parameters and does not require a JSON body:

```bash
curl -X POST \
  -H "Authorization: Bearer $DEMO_API_TOKEN" \
  'http://localhost:8095/api/evaluate?scene=retry&profile=development&revision=v3'
```

Valid scenes are `healthy`, `slowdown`, `retry`, and `missing`. Valid profiles are `development` and `production`. Candidate versions are `v1`, `v2`, and `v3`.

In cluster mode, a scene selection does not fabricate normal telemetry. The collector reads actual running stable and canary pods. The Missing evidence scene deliberately withholds trace observations and labels that injected outage.

## Log sources

`agents` is a UI view of investigation events. Kubernetes log endpoints accept:

- `checkout`
- `inventory`
- `quarkus`
- `rollouts`
- `argocd`

The SPA polls every four seconds, with up to three matching pods and bounded line counts. This is a live console for the demo, not a centralized logging backend.

## Environment

| Variable | Default or requirement | Effect |
| --- | --- | --- |
| `OPENAI_API_KEY` | Required for live model calls | Server-side GPT-4o mini credential |
| `DEMO_AI_MODE` | `live` | Use `fixture` for explicit deterministic rehearsal |
| `DEMO_API_TOKEN` | Required in packaged deployment | Authenticate presenter and internal requests |
| `DEMO_PORT` | `8095` in the local launcher | Local development HTTP port |
| `DEMO_CLUSTER_ENABLED` | `false` | Enable cluster evidence collection |
| `DEMO_NAMESPACE` | `canary-demo` | Workload namespace; installer currently supports this fixed name |
| `DEMO_GITOPS_NAMESPACE` | `openshift-gitops` | Runtime Argo CD namespace |
| `DEMO_ROLE` | `control` | Run the image as control, checkout, inventory, or traffic |
| `DEMO_RELEASE_ENABLED` | `false` (cluster control manifest enables it) | Allow presenter Git release actions when a credential is configured and mode is live |
| `DEMO_GITHUB_TOKEN` | Optional server-side Secret | Repository contents write credential; never sent to the SPA |
| `DEMO_RELEASE_REPOSITORY` | `danieloh30/intelligent-canary-delivery` | GitHub repository; must match Argo CD source |
| `DEMO_RELEASE_BRANCH` | `main` | Release branch; must match Argo CD target revision |
| `DEMO_RELEASE` | `v1` | Select the workload behavior |
| `OTEL_ENDPOINT` | `http://jaeger:4317` | OTLP gRPC collector endpoint |
| `REPO_URL` | This GitHub repository | Installer's Argo CD source repository |
| `GITOPS_CHANNEL` | Operator catalog default | Select a GitOps channel when installing a new operator |

Local development defaults the presenter token to `demo-local`. Production manifests inject a generated token from a Secret.

## Outcome contract

The application distinguishes three things:

1. **Recommendation:** the model's proposed verdict and explanation.
2. **Effective decision:** the result after Decision Policy checks.
3. **Rollout outcome:** the controller's actual status after consuming the analysis result.

The decision API rechecks release and stage identity. A pending, expired, fixture, or mismatched result returns HOLD. Never infer that a rollout advanced merely because an agent said GO; inspect the controller state.


## Progress and investigation timing

`GET /api/progress` returns `state`, `ready`, `revision`, `podHash`, `stepIndex`, `targetWeight`, `stages`, `stableRequests`, `canaryRequests`, `stableP95Ms`, `canaryP95Ms`, `samplesAvailable`, `capturedAt`, `missing`, and AnalysisRun details (`analysisName`, `analysisPhase`, `checksCompleted`, `checksTotal`, `interval`). It is authenticated, briefly cached, and read-only. Its counts precede investigation and never replace the frozen evidence used for a decision. The browser withholds readiness for stale snapshots, mismatched stages/candidates, and unavailable telemetry. The investigation endpoint collects fresh evidence again when explicitly invoked.

The configured 15-second pause occurs before the inline analysis step. During analysis, Argo polls the saved verdict up to 180 times at five-second intervals. This is roughly a 15-minute analysis budget, not a 15-second click window or 180 LLM calls. A completed Inconclusive run cannot be restarted by polling progress or starting another investigation; request a fresh Git release/reset.

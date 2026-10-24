# API and configuration

All presenter and internal evidence endpoints require `Authorization: Bearer TOKEN`. The static SPA shell is public. The OpenAI API key remains in server configuration.

## Presenter API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/state` | Execution mode, latest evaluation, and recent activity |
| GET | `/api/cluster` | Rollout and Argo CD Application status |
| POST | `/api/evaluate` | Start an investigation |
| GET | `/api/evaluations/{id}` | Inspect one investigation |
| POST | `/api/evaluations/{id}/approve` | Approve an eligible pending production decision |
| GET | `/api/logs/{source}` | Read a bounded Kubernetes log tail |
| POST | `/api/fault/{seconds}` | Inject or clear the shared inventory slowdown |
| GET | `/api/analysis?revision=v2` | Return the effective verdict for Argo analysis |

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
| `DEMO_RELEASE` | `v1` | Select the workload behavior |
| `OTEL_ENDPOINT` | `http://jaeger:4317` | OTLP gRPC collector endpoint |
| `REPO_URL` | This GitHub repository | Installer's Argo CD source repository |
| `GITOPS_CHANNEL` | Operator catalog default | Select a GitOps channel when installing a new operator |

Local development defaults the presenter token to `demo-local`. Production manifests inject a generated token from a Secret.

## Outcome contract

The application distinguishes three things:

1. **Recommendation:** the model's proposed verdict and explanation.
2. **Effective decision:** the result after Java policy checks.
3. **Rollout outcome:** the controller's actual status after consuming the analysis result.

The decision API rechecks release and stage identity. A pending, expired, fixture, or mismatched result returns HOLD. Never infer that a rollout advanced merely because an agent said GO; inspect the controller state.

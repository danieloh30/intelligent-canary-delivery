# Troubleshooting

Start with the failing boundary: application, model, evidence, policy, or controller. Keep those failures separate so a model explanation does not hide an infrastructure problem.

## Java does not compile

The project targets **Java 25**. Check both the active runtime and `JAVA_HOME`:

```bash
java -version
printf '%s\n' "$JAVA_HOME"
```

The launcher uses Homebrew's Java 25 location on macOS only when `JAVA_HOME` is unset. An explicitly configured older JDK takes precedence and must be corrected.

## The local port is occupied

Choose another port before starting:

```bash
DEMO_PORT=8098 ./demo.sh rehearsal
```

Open the corresponding localhost URL. Stop the process you started rather than terminating an unrelated Java application.

## The model is unavailable

Confirm the server has `OPENAI_API_KEY`, outbound API access, and access to `gpt-4o-mini`. Inspect the Quarkus logs. Never place the model credential in the browser's presenter-token field.

Timeouts or incomplete investigations should produce HOLD. Use explicit offline rehearsal if you need to practice the UI without API access; the product labels fixture responses.

## The installer stops at an operator or controller

```bash
oc get subscription,csv -n openshift-operators
oc get rolloutmanager -A
./demo.sh doctor
```

Check the operator channel, RolloutManager scope, and controller availability. A CRD alone does not prove that a running controller watches `canary-demo`.

Existing namespace-scoped managers in unrelated namespaces require deliberate configuration. The installer will stop rather than silently changing the operator's global scope.

## Cluster image builds fail

Check the internal registry's management state and storage, build logs, and outbound access to base-image registries and Maven Central:

```bash
oc -n canary-demo get builds
oc -n canary-demo logs -f bc/canary-demo
```

The build context includes only Dockerfile, pom.xml, and src. Credentials should not be added to those files to fix a network or registry issue.

## The rollout stays on HOLD

Check these in order:

1. Is the current analysis step running?
2. Are stable and canary distinct revisions with at least 30 observations each?
3. Does the selected candidate match the actual release?
4. Is the evidence still within its 120-second lifetime?
5. Did every mandatory specialist tool run successfully?
6. Are there missing or unresolved findings?
7. Does production policy require approval?

A stage change requires another investigation. A completed inconclusive analysis does not resume itself; address the cause and use `./demo.sh retry-analysis` or release a fresh candidate.

## The slowdown ended, but latency still fails

The collector uses a rolling 60-second request window. Earlier slow requests remain part of p95 until that window drains. Clearing the fault changes new requests; it does not erase earlier observations.

## Live controller logs are empty

Controller labels vary across operator versions. Use `./demo.sh doctor` to inspect labels and verify the observer role can read the controller namespace. The console reports unmatched sources rather than inventing log output.

## Trace IDs appear, but Jaeger has no trace

The evidence contains real request trace IDs, while exporting complete spans is a separate path. Check OTLP endpoint reachability and protocol. The default is gRPC on port 4317.

```bash
oc -n canary-demo logs -l app=checkout --tail=40
oc -n canary-demo logs deployment/jaeger --tail=40
```

## Documentation diagrams fail

The site serves its Mermaid bundle locally. Rebuild with the documentation hook so the renderer and solid-fill adapter reach the output directory. Check the browser console, then run:

```bash
python3 scripts/check-diagrams.py
mkdocs build --strict
```

Do not switch to a default Mermaid theme to work around a loading problem; all diagrams share the selected workshop style.

## AnalysisRun cannot resolve the reasoning service

The Rollouts controller runs in `openshift-gitops`, while the service runs in `canary-demo`. Use `http://canary-control.canary-demo.svc.cluster.local:8080` in the AnalysisTemplate. After correcting a failed template, re-enter the release through GitOps with `./demo.sh retry-analysis`; an already failed AnalysisRun is immutable history.

## Healthy telemetry still produces HOLD

Inspect the decision reason, specialist limitations, and evidence references. The platform requires all five tool reads and the current investigation UUID in each finding. Request trace IDs are separate identifiers and cannot authorize promotion. A concrete model or evidence validation failure remains HOLD; collect another window and investigate again. After clearing a slowdown, allow the full 60-second observation window to drain before expecting recovered p95 latency.

## Argo CD shows no applications after OpenShift login

First check `oc -n openshift-gitops get applications`: an existing `canary-checkout` can be healthy even when your UI list is empty. Argo CD has its own RBAC; Kubernetes cluster-admin access does not automatically grant Argo CD UI access. The installer adds the current human OpenShift user (or `DEMO_PRESENTER_USER`) to `canary-demo-viewers` and grants that group read access to `default/canary-checkout` and its logs. It preserves existing Argo policies and does not grant sync or deployment writes.

For an existing installation, run this as a cluster administrator, replacing `admin` with the OpenShift username used to log into Argo CD:

```bash
bash scripts/configure-argocd-access.sh admin
```

Then **sign out of Argo CD and log in via OpenShift again**. Refreshing the browser alone retains old group claims. In Argo CD User Info, verify that your SSO groups include `canary-demo-viewers`. If installing through a service account, set `DEMO_PRESENTER_USER` to the intended human username first.

## Dashboard direct URL asks for a presenter token

The OpenShift installation must use the `deploy/openshift` overlay, which adds the OAuth proxy, ServiceAccount redirect reference, presenter RBAC, and Route target `oauth` (4180). Applying only `deploy/base` exposes the plain token-protected backend and does not enable SSO. Re-run the installer after updating the repository. Direct visits should redirect to OpenShift login/consent and return to the dashboard without the token dialog. The backend token remains in the pod, not in browser storage. Argo analysis continues to use internal Service port 8080. Check the `oauth-proxy` container logs and membership of `canary-demo-presenters` if login is denied.

## OTLP export says connection refused on jaeger:4317

Check Jaeger before changing the exporter address:

```bash
oc -n canary-demo get pods -l app=jaeger
oc -n canary-demo get pod YOUR_JAEGER_POD -o jsonpath='{.status.containerStatuses[0].lastState}'
oc -n canary-demo logs deployment/jaeger --tail=50
```

A receiver restart temporarily refuses connections even when DNS and Service ports are correct. During the live rehearsal, Jaeger had been OOMKilled after an unbounded in-memory trace store reached its 512 MiB container limit. The manifest now caps retention with `--memory.max-traces=10000`, sets `GOMEMLIMIT=384MiB`, and probes startup/admin health and OTLP readiness. The Go setting is a soft memory budget; the container limit remains 512 MiB. These bounds suit the demo's recent observation window, not durable trace archival. Old traces are evicted as the count fills, and all in-memory traces disappear on restart.

After applying the updated observability manifest through the installer, confirm a fresh trace is searchable in Jaeger and watch restart counts and exporter logs. A brief export warning can still occur while a receiver starts or restarts; repeated warnings need investigation. Request observations used by the agents and the complete spans exported to Jaeger are separate evidence paths.

## Fabric8 reports unstable v1alpha1 for Argo resources

The installed Argo Rollouts and Argo CD CRDs serve and store `v1alpha1`. Fabric8 emits a version-name warning for these resources even when requests succeed. This is not a connection, authorization, or rollout failure. Confirm the actual versions before attempting to change the client:

```bash
oc get crd rollouts.argoproj.io applications.argoproj.io \
  -o jsonpath='{range .items[*]}{.metadata.name}{": "}{range .spec.versions[*]}{.name}{" served="}{.served}{" storage="}{.storage}{"; "}{end}{"\n"}{end}'
```

Use the version served by the installed CRD; changing this demo to a nonexistent `v1` API would break resource reads.

## Old Java types remain after updating the package layout

The source now uses `agentic`, `model`, `resource`, and `service` packages. An existing checkout can still contain old compiled classes under `target/classes/dev/danieloh/canary` after a package move. LangChain4j may then report two different Finding types for the same workflow key. Stop Quarkus dev mode before cleaning stale build output, then start it again. A fresh checkout or clean CI build does not contain these obsolete classes. Never clean build outputs while dev mode is running.

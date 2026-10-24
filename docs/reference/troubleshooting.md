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

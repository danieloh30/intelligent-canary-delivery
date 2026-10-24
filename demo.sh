#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
NS=${DEMO_NAMESPACE:-canary-demo}
case ${1:-help} in
  install) exec ./scripts/install-openshift.sh ;;
  doctor) exec ./scripts/doctor.sh ;;
  dev)
    if [[ -z ${JAVA_HOME:-} && -x /opt/homebrew/opt/openjdk@25/bin/java ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@25; export PATH="$JAVA_HOME/bin:$PATH"; fi
    exec ./mvnw quarkus:dev -Dquarkus.http.port="${DEMO_PORT:-8095}" ;;
  rehearsal) export DEMO_AI_MODE=fixture; exec "$0" dev ;;
  release)
    version=${2:?Usage: ./demo.sh release v1|v2|v3}
    [[ "$version" =~ ^v[123]$ ]] || { echo 'Expected v1, v2, or v3' >&2; exit 1; }
    [[ -z $(git status --porcelain) ]] || { echo 'Commit or stash local changes before releasing.' >&2; exit 1; }
    VERSION="$version" python3 - <<'PY'
import json, os
from pathlib import Path
p=Path('deploy/gitops/rollout.yaml'); data=json.loads(p.read_text()); v=os.environ['VERSION']
for e in data['spec']['template']['spec']['containers'][0]['env']:
    if e['name']=='DEMO_RELEASE': e['value']=v
# Re-entering the same release gets a fresh pod-template identity.
from datetime import datetime, timezone
data['spec']['template']['metadata']['annotations']={'demo.canary/released-at':datetime.now(timezone.utc).isoformat()}
for step in data['spec']['strategy']['canary']['steps']:
    for arg in step.get('analysis',{}).get('args',[]):
        if arg['name']=='release':arg['value']=v
p.write_text(json.dumps(data,indent=2)+'\n')
PY
    git add deploy/gitops/rollout.yaml
    git commit -m "Release checkout $version"
    git push origin HEAD:main
    echo 'Argo CD will reconcile the release. Wait for canary traffic, then run an investigation in the dashboard.' ;;
  retry-analysis)
    # Argo does not automatically resume a completed inconclusive AnalysisRun.
    command -v kubectl-argo-rollouts >/dev/null || { echo 'Install the Argo Rollouts kubectl plugin first.' >&2; exit 1; }
    kubectl-argo-rollouts -n "$NS" retry rollout checkout ;;
  status) oc -n "$NS" get rollout,analysisrun,pods ;;
  logs) oc -n "$NS" logs -l "app=${2:-canary-control}" --tail=80 --follow ;;
  *) echo 'Usage: ./demo.sh install|doctor|dev|rehearsal|release v1|v2|v3|retry-analysis|status|logs [app]' ;;
esac

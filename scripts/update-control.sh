#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
NS=${DEMO_NAMESPACE:-canary-demo}
for tool in oc python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
oc -n "$NS" get deployment canary-control >/dev/null
oc -n "$NS" get buildconfig canary-demo >/dev/null
bash scripts/configure-release-refresh.sh
work=$(mktemp -d)
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
python3 scripts/package-source.py "$work/source.tar.gz"
oc -n "$NS" start-build canary-demo --from-archive="$work/source.tar.gz" --follow --wait
digest=$(oc -n "$NS" get imagestreamtag canary-demo:latest -o jsonpath='{.image.metadata.name}')
[[ "$digest" == sha256:* ]] || { echo 'Build did not produce an immutable image digest.' >&2; exit 1; }
if [[ -n ${DEMO_GITHUB_TOKEN:-} ]]; then
  printf '%s' "$DEMO_GITHUB_TOKEN" > "$work/token"
  oc -n "$NS" create secret generic canary-github --from-file="token=$work/token" --dry-run=client -o yaml | oc apply -f -
fi
IMAGE_REF="image-registry.openshift-image-registry.svc:5000/$NS/canary-demo@$digest" python3 - "$work/patch.json" <<'PY'
import json,os,sys
from pathlib import Path
env=[{'name':'DEMO_RELEASE_ENABLED','value':'true'},
     {'name':'DEMO_GITHUB_TOKEN','valueFrom':{'secretKeyRef':{'name':'canary-github','key':'token','optional':True}}}]
for name in ('DEMO_RELEASE_REPOSITORY','DEMO_RELEASE_BRANCH'):
    if os.environ.get(name):env.append({'name':name,'value':os.environ[name]})
patch={'spec':{'template':{'spec':{'containers':[{'name':'canary-control','image':os.environ['IMAGE_REF'],'env':env}]}}}}
Path(sys.argv[1]).write_text(json.dumps(patch))
PY
# Strategic merge preserves the OAuth sidecar, model credentials, and existing settings.
oc -n "$NS" patch deployment canary-control --type=strategic --patch-file="$work/patch.json"
oc -n "$NS" rollout status deployment/canary-control --timeout=300s
echo 'Control service updated. Refresh the dashboard. Checkout and dependency images were not changed.'

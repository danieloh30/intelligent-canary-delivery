#!/usr/bin/env bash
set -euo pipefail
GITOPS_NS=${GITOPS_NAMESPACE:-openshift-gitops}
presenter=${1:-${DEMO_PRESENTER_USER:-$(oc whoami)}}
[[ -n "$presenter" && "$presenter" != system:* ]] || { echo 'Set DEMO_PRESENTER_USER to the human OpenShift username used for Argo CD login.' >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
# Patch the operator-owned CR, preserving existing policy and default permissions.
oc -n "$GITOPS_NS" get argocd openshift-gitops -o json > "$work/argocd.json"
python3 - "$work/argocd.json" "$work/patch.json" <<'PY'
import json, sys
from pathlib import Path
rbac = json.loads(Path(sys.argv[1]).read_text()).get('spec', {}).get('rbac', {})
scopes = rbac.get('scopes', '[groups]')
if 'groups' not in scopes:
    raise SystemExit('Argo CD spec.rbac.scopes must include groups for OpenShift group access.')
lines = rbac.get('policy', '').splitlines()
for line in (
    'p, role:canary-demo-viewer, applications, get, default/canary-checkout, allow',
    'p, role:canary-demo-viewer, logs, get, default/canary-checkout, allow',
    'g, canary-demo-viewers, role:canary-demo-viewer',
):
    if line not in lines:
        lines.append(line)
Path(sys.argv[2]).write_text(json.dumps({'spec': {'rbac': {'policy': '\n'.join(lines) + '\n'}}}))
PY
if ! oc get group canary-demo-viewers >/dev/null 2>&1; then
    oc adm groups new canary-demo-viewers
fi
oc adm groups add-users canary-demo-viewers "$presenter"
oc -n "$GITOPS_NS" patch argocd openshift-gitops --type=merge --patch-file="$work/patch.json"
echo "Argo CD demo read access configured for $presenter. Sign out and log back in with OpenShift to refresh group claims."

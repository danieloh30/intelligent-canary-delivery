#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
NS=${DEMO_NAMESPACE:-canary-demo}
REPO_URL=${REPO_URL:-https://github.com/danieloh30/intelligent-canary-delivery.git}
GITOPS_NS=${GITOPS_NAMESPACE:-openshift-gitops}
for tool in oc python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
[[ "$NS" == canary-demo && "$GITOPS_NS" == openshift-gitops ]] || { echo 'This installer currently supports the dedicated canary-demo namespace and default openshift-gitops instance.' >&2; exit 1; }
: "${OPENAI_API_KEY:?Export OPENAI_API_KEY before installation}"
oc whoami >/dev/null
[[ $(oc auth can-i create subscriptions.operators.coreos.com -n openshift-operators) == yes ]] || { echo 'Cluster administrator privileges are required for operator installation.' >&2; exit 1; }
echo "Target cluster: $(oc whoami --show-server)"
echo "Cluster version: $(oc get clusterversion version -o jsonpath='{.status.desired.version}')"
# Reuse the cluster's installed GitOps operator without modifying its channel/config.
if ! oc get crd argocds.argoproj.io >/dev/null 2>&1; then
  channel=${GITOPS_CHANNEL:-$(oc get packagemanifest openshift-gitops-operator -n openshift-marketplace -o jsonpath='{.status.defaultChannel}')}
  [[ -n "$channel" ]] || { echo 'GitOps package is unavailable in OperatorHub.' >&2; exit 1; }
  cat <<YAML | oc apply -f -
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: openshift-gitops-operator
  namespace: openshift-operators
spec:
  channel: $channel
  installPlanApproval: Automatic
  name: openshift-gitops-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
  config:
    env:
      - name: NAMESPACE_SCOPED_ARGO_ROLLOUTS
        value: 'false'
      - name: CLUSTER_SCOPED_ARGO_ROLLOUTS_NAMESPACES
        value: openshift-gitops,canary-demo
YAML
fi
wait_crd() {
  local name=$1
  for ((i=0;i<90;i++)); do
    if oc get crd "$name" >/dev/null 2>&1; then oc wait --for=condition=Established "crd/$name" --timeout=60s; return; fi
    echo "Waiting for $name ($i/90)…"; sleep 10
  done
  echo "Timed out installing $name. Inspect oc get csv,subscription -n openshift-operators" >&2; exit 1
}
wait_crd argocds.argoproj.io
wait_crd rolloutmanagers.argoproj.io
oc create namespace "$NS" --dry-run=client -o yaml | oc apply -f -
oc label namespace "$NS" argocd.argoproj.io/managed-by="$GITOPS_NS" --overwrite
# A cluster-scoped RolloutManager is the supported default. Reuse one if installed.
manager=$(oc get rolloutmanagers.argoproj.io -A -o json | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; print(next((x["metadata"]["namespace"] for x in items if not x.get("spec",{}).get("namespaceScoped",False)), ""))')
if [[ -z "$manager" ]]; then
  existing=$(oc get rolloutmanagers.argoproj.io -A -o name)
  [[ -z "$existing" ]] || { echo 'Existing namespace-scoped RolloutManagers found. Configure one for canary-demo before proceeding; no operator-wide settings were changed.' >&2; exit 1; }
cat <<YAML | oc apply -f -
apiVersion: argoproj.io/v1alpha1
kind: RolloutManager
metadata:
  name: canary-rollouts
  namespace: $GITOPS_NS
spec:
  namespaceScoped: false
YAML
fi
wait_crd rollouts.argoproj.io
if [[ -z "$manager" ]]; then manager=$GITOPS_NS; fi
# The controller must exist and become available, not merely install its CRDs.
for ((i=0;i<60;i++)); do
  controllers=$(oc -n "$manager" get deployments -l app.kubernetes.io/name=argo-rollouts -o name)
  if [[ -n "$controllers" ]]; then
    while IFS= read -r controller; do oc -n "$manager" rollout status "$controller" --timeout=300s; done <<< "$controllers"
    break
  fi
  [[ $i -lt 59 ]] || { echo 'Rollouts controller not available; inspect RolloutManager status and operator scope.' >&2; exit 1; }
  sleep 5
done
oc -n "$GITOPS_NS" rollout status deployment/openshift-gitops-server --timeout=600s
bash scripts/configure-argocd-access.sh
# Secrets pass through private temporary files, never command arguments or Git.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
printf '%s' "$OPENAI_API_KEY" > "$work/openai-api-key"
if [[ -z ${DEMO_API_TOKEN:-} ]]; then
  if oc -n "$NS" get secret canary-secrets >/dev/null 2>&1; then
    DEMO_API_TOKEN=$(oc -n "$NS" get secret canary-secrets -o jsonpath='{.data.api-token}' | base64 --decode)
  else
    DEMO_API_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
  fi
fi
printf '%s' "$DEMO_API_TOKEN" > "$work/api-token"
oc -n "$NS" create secret generic canary-secrets --from-file="$work/openai-api-key" --from-file="$work/api-token" --dry-run=client -o yaml | oc apply -f -
# Build in the cluster. Only explicitly allowlisted source paths enter the build context.
if ! oc -n "$NS" get buildconfig canary-demo >/dev/null 2>&1; then
  oc -n "$NS" new-build --name=canary-demo --binary --strategy=docker
fi
python3 scripts/package-source.py "$work/source.tar.gz"
oc -n "$NS" start-build canary-demo --from-archive="$work/source.tar.gz" --follow --wait
digest=$(oc -n "$NS" get imagestreamtag canary-demo:latest -o jsonpath='{.image.metadata.name}')
[[ "$digest" == sha256:* ]] || { echo 'Build did not produce an immutable image digest.' >&2; exit 1; }
image="image-registry.openshift-image-registry.svc:5000/$NS/canary-demo@$digest"
# OpenShift SSO keeps the presenter credential entirely inside the pod.
if ! oc -n "$NS" get secret canary-oauth >/dev/null 2>&1; then
  python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode(),end="")' > "$work/cookie-secret"
  oc -n "$NS" create secret generic canary-oauth --from-file="$work/cookie-secret" --dry-run=client -o yaml | oc apply -f -
fi
if ! oc -n "$NS" get secret observability-oauth >/dev/null 2>&1; then
  python3 - "$work" <<'PYCOOKIE'
import base64,secrets,sys
from pathlib import Path
for name in ('prometheus','jaeger'):
    (Path(sys.argv[1])/(name+'-cookie-secret')).write_text(base64.b64encode(secrets.token_bytes(32)).decode())
PYCOOKIE
  oc -n "$NS" create secret generic observability-oauth --from-file="$work/prometheus-cookie-secret" --from-file="$work/jaeger-cookie-secret" --dry-run=client -o yaml | oc apply -f -
fi
presenter=${DEMO_PRESENTER_USER:-$(oc whoami)}
if ! oc get group canary-demo-presenters >/dev/null 2>&1; then oc adm groups new canary-demo-presenters; fi
oc adm groups add-users canary-demo-presenters "$presenter"
oauth_image=${OAUTH_PROXY_IMAGE:-$(oc -n openshift get imagestream oauth-proxy -o jsonpath='{.status.tags[0].items[0].dockerImageReference}')}
[[ -n "$oauth_image" ]] || { echo 'Set OAUTH_PROXY_IMAGE to the cluster-compatible OpenShift OAuth proxy image.' >&2; exit 1; }
mkdir "$work/base" "$work/openshift"
cp deploy/openshift/*.yaml "$work/openshift/"
OAUTH_IMAGE="$oauth_image" SSO_DIR="$work/openshift" python3 - <<'PYSSO'
import json,os
from pathlib import Path
for p in Path(os.environ['SSO_DIR']).glob('sso-*.yaml'):
    documents=[json.loads(part) for part in p.read_text().split('\n---\n')]
    for d in documents:
        for container in d.get('spec',{}).get('template',{}).get('spec',{}).get('containers',[]):
            if container['name']=='oauth-proxy':container['image']=os.environ['OAUTH_IMAGE']
    p.write_text('\n---\n'.join(json.dumps(d,indent=2) for d in documents)+'\n')
PYSSO
cp deploy/base/*.yaml "$work/base/"
IMAGE_REF="$image" PLATFORM_FILE="$work/base/platform.yaml" python3 - <<'PYCODE'
import os
from pathlib import Path
p=Path(os.environ['PLATFORM_FILE'])
p.write_text(p.read_text().replace('image-registry.openshift-image-registry.svc:5000/canary-demo/canary-demo:latest',os.environ['IMAGE_REF']))
PYCODE
oc -n "$NS" apply -k "$work/openshift"
# Permit bounded Argo CD status/log reads; this does not grant deployment writes.
cat <<YAML | oc apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: canary-gitops-observer
  namespace: $GITOPS_NS
rules:
  - apiGroups: [argoproj.io]
    resources: [applications]
    verbs: [get]
    resourceNames: [canary-checkout]
  - apiGroups: ['']
    resources: [pods, pods/log]
    verbs: [get, list]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: canary-gitops-observer
  namespace: $GITOPS_NS
subjects:
  - kind: ServiceAccount
    name: canary-control
    namespace: $NS
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: canary-gitops-observer
---
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: canary-checkout
  namespace: $GITOPS_NS
spec:
  project: default
  source:
    repoURL: $REPO_URL
    targetRevision: main
    path: deploy/gitops
    kustomize:
      images:
        - $image
  destination:
    server: https://kubernetes.default.svc
    namespace: $NS
  syncPolicy:
    automated:
      prune: false
      selfHeal: true
YAML
oc -n "$NS" rollout status deployment/canary-control --timeout=600s
oc -n "$NS" rollout status deployment/inventory --timeout=300s
oc -n "$NS" rollout status deployment/jaeger --timeout=300s
oc -n "$NS" rollout status deployment/prometheus --timeout=300s
echo "Dashboard: https://$(oc -n "$NS" get route canary-control -o jsonpath='{.spec.host}')"
echo "Prometheus: https://$(oc -n "$NS" get route prometheus -o jsonpath='{.spec.host}')"
echo "Jaeger: https://$(oc -n "$NS" get route jaeger -o jsonpath='{.spec.host}')"
echo 'Get the presenter token locally: oc -n canary-demo get secret canary-secrets -o jsonpath="{.data.api-token}" | base64 --decode'
echo 'Verify Argo sync and traffic before rehearsal: ./demo.sh doctor'
echo 'Open dashboard, Argo CD, Prometheus, and Jaeger: ./demo.sh present --observability'
echo 'Operator and OpenShift 4.22 compatibility still requires a real-cluster rehearsal.'

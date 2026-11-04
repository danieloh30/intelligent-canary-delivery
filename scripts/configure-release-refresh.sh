#!/usr/bin/env bash
set -euo pipefail
NS=${DEMO_NAMESPACE:-canary-demo}
GITOPS_NS=${GITOPS_NAMESPACE:-openshift-gitops}
# The presenter can request discovery of a committed release in this Application only.
cat <<YAML | oc apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: canary-release-refresh
  namespace: $GITOPS_NS
rules:
  - apiGroups: [argoproj.io]
    resources: [applications]
    resourceNames: [canary-checkout]
    verbs: [patch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: canary-release-refresh
  namespace: $GITOPS_NS
subjects:
  - kind: ServiceAccount
    name: canary-control
    namespace: $NS
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: canary-release-refresh
YAML

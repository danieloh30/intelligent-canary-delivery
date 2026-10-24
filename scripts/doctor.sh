#!/usr/bin/env bash
set -euo pipefail
NS=${DEMO_NAMESPACE:-canary-demo}
oc whoami
oc get clusterversion version
oc get csv -A | awk 'NR==1 || /gitops/'
oc -n "$NS" get rollout,analysisrun,pods,svc
oc -n openshift-gitops get application canary-checkout
oc -n openshift-gitops get pods -l app.kubernetes.io/name=argo-rollouts
oc -n openshift-gitops get pods --show-labels | awk 'NR==1 || /application-controller/'
oc -n "$NS" auth can-i get pods/log --as="system:serviceaccount:$NS:canary-control"
oc -n "$NS" get route canary-control

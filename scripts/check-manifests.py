#!/usr/bin/env python3
"""Structural checks without a cluster. Does not claim Kubernetes API validation."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for p in (root/'deploy').rglob('*.yaml'):
    if p.name=='kustomization.yaml':continue
    for document in p.read_text().split('\n---\n'):
        resource=json.loads(document)
        assert resource['apiVersion'] and resource['kind'] and resource['metadata']['name'],p
rollout=json.loads((root/'deploy/gitops/rollout.yaml').read_text())
assert rollout['spec']['strategy']['canary']['steps'][0]['setWeight']==10
assert rollout['spec']['template']['spec']['automountServiceAccountToken'] is False
analysis=json.loads((root/'deploy/gitops/analysis.yaml').read_text())
metric=analysis['spec']['metrics'][0]
assert metric['successCondition']=='result == "GO"'
assert metric['failureCondition']=='result == "NO_GO"'
assert metric['consecutiveSuccessLimit']==1
print('Manifest structural checks passed (live API validation still required).')

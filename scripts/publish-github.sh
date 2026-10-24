#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
repo=danieloh30/intelligent-canary-delivery
gh auth status
git rev-parse --git-dir >/dev/null 2>&1 || git init -b main
git add .
if ! git diff --cached --quiet; then git commit -m 'Build governed multi-agent canary delivery demo'; fi
if ! gh repo view "$repo" >/dev/null 2>&1; then
  gh repo create "$repo" --public --description 'Kubernetes canary delivery with Quarkus LangChain4j agents, evidence, and governed rollout decisions'
fi
if ! git remote get-url origin >/dev/null 2>&1; then git remote add origin "https://github.com/$repo.git"; fi
git push -u origin main
# Require passing CI and an up-to-date branch before automatic merging.
python3 - <<'PY' > /private/tmp/canary-branch-protection.json
import json
print(json.dumps({'required_status_checks':{'strict':True,'contexts':['verify']},'enforce_admins':False,'required_pull_request_reviews':None,'restrictions':None}))
PY
gh api -X PUT "repos/$repo/branches/main/protection" --input /private/tmp/canary-branch-protection.json >/dev/null
gh api -X PATCH "repos/$repo" -F allow_auto_merge=true -F delete_branch_on_merge=true >/dev/null
echo "Published https://github.com/$repo with required CI and Dependabot auto-merge."

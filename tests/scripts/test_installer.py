"""Installer control-flow tests using an oc protocol stub; never contacts a cluster."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[2]
OC = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]; root=Path(os.environ['OC_TEST_DIR'])
with (root/'calls').open('a') as f:f.write(json.dumps(args)+'\n')
if args[:3]==['get','crd','argocds.argoproj.io'] and not (root/'operator').exists():sys.exit(1)
if args[:3]==['get','packagemanifest','openshift-gitops-operator']: print('gitops-1.21')
elif args[:3]==['get','rolloutmanagers.argoproj.io','-A']:
 if '-o' in args and args[-1]=='json':
  items=[] if os.environ.get('OC_TEST_EXISTING')!='namespace' else [{'metadata':{'namespace':'other'},'spec':{'namespaceScoped':True}}]
  print(json.dumps({'items':items}))
 elif os.environ.get('OC_TEST_EXISTING')=='namespace':print('rolloutmanager/unrelated')
elif 'can-i' in args:print('yes')
elif '--show-server' in args:print('https://cluster.example.test:6443')
elif 'clusterversion' in args:print('4.22.0')
elif 'create' in args and 'namespace' in args:print('apiVersion: v1\nkind: Namespace\nmetadata:\n  name: canary-demo')
elif 'apply' in args:
 if '-f' in args and args[args.index('-f')+1]=='-':
  body=sys.stdin.read()
  with (root/'applied').open('a') as f:f.write(body+'\n')
  if 'kind: Subscription' in body:(root/'operator').touch()
 if '-k' in args:
  base=Path(args[args.index('-k')+1]);(root/'platform').write_text((base/'platform.yaml').read_text())
elif 'imagestreamtag' in args:print('sha256:abc123')
elif 'deployments' in args:print('deployment/argo-rollouts')
elif 'get' in args and 'secret' in args:sys.exit(1)
elif 'create' in args and 'secret' in args:print('apiVersion: v1\nkind: Secret\nmetadata:\n  name: canary-secrets')
elif 'new-build' in args:pass
elif 'route' in args:print('canary.example.test')
'''

class InstallerTest(unittest.TestCase):
    def run_installer(self, existing=''):
        temp=tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        root=Path(temp.name);oc=root/'oc';oc.write_text(OC);oc.chmod(0o755)
        env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],OC_TEST_DIR=str(root),
                 OC_TEST_EXISTING=existing,OPENAI_API_KEY='installer-test-secret',DEMO_API_TOKEN='presenter-test-secret')
        result=subprocess.run(['bash','scripts/install-openshift.sh'],cwd=ROOT,env=env,text=True,capture_output=True,timeout=30)
        return root,result
    def test_fresh_install_pins_digest_and_does_not_print_secrets(self):
        root,result=self.run_installer()
        self.assertEqual(0,result.returncode,result.stderr)
        applied=(root/'applied').read_text()
        self.assertIn('NAMESPACE_SCOPED_ARGO_ROLLOUTS',applied)
        self.assertIn('canary-demo@sha256:abc123',applied)
        self.assertIn('canary-demo@sha256:abc123',(root/'platform').read_text())
        self.assertNotIn('canary-demo:latest',(root/'platform').read_text())
        combined=result.stdout+result.stderr+(root/'calls').read_text()
        self.assertNotIn('installer-test-secret',combined)
        self.assertNotIn('presenter-test-secret',combined)
    def test_incompatible_existing_namespace_controller_stops_install(self):
        root,result=self.run_installer('namespace')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('namespace-scoped RolloutManagers',result.stderr)
        self.assertNotIn('start-build',(root/'calls').read_text())

if __name__=='__main__':unittest.main()

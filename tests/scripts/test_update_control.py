"""Verify control-only updates without contacting a cluster or exposing credentials."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
OC=r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:];root=Path(os.environ['OC_TEST_DIR'])
with (root/'calls').open('a') as f:f.write(json.dumps(args)+'\n')
if 'imagestreamtag' in args:print('sha256:abc123')
elif 'patch' in args:
    patch=next(x.split('=',1)[1] for x in args if x.startswith('--patch-file='))
    (root/'patch').write_text(Path(patch).read_text())
elif 'create' in args and 'secret' in args:
    token=next(x.split('=',2)[2] for x in args if x.startswith('--from-file=token='))
    assert Path(token).read_text()=='github-private-test-token'
    print('apiVersion: v1\nkind: Secret\nmetadata:\n  name: canary-github')
elif 'apply' in args:sys.stdin.read()
'''

class UpdateControlTest(unittest.TestCase):
    def test_update_only_patches_control_and_keeps_secrets_out_of_commands(self):
        for credential in ('','github-private-test-token'):
            with self.subTest(credential=bool(credential)),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);oc=root/'oc';oc.write_text(OC);oc.chmod(0o755)
                env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],OC_TEST_DIR=temp,DEMO_GITHUB_TOKEN=credential)
                result=subprocess.run(['bash','scripts/update-control.sh'],cwd=ROOT,env=env,text=True,capture_output=True,timeout=30)
                self.assertEqual(0,result.returncode,result.stderr)
                calls=[json.loads(line) for line in (root/'calls').read_text().splitlines()]
                patches=[c for c in calls if 'patch' in c];self.assertEqual(1,len(patches));self.assertIn('canary-control',patches[0])
                self.assertNotIn('github-private-test-token',result.stdout+result.stderr+(root/'calls').read_text()+(root/'patch').read_text())
                containers=json.loads((root/'patch').read_text())['spec']['template']['spec']['containers']
                self.assertEqual(['canary-control'],[c['name'] for c in containers])
                self.assertTrue(containers[0]['image'].endswith('@sha256:abc123'))
                self.assertEqual(bool(credential),any('create' in c and 'secret' in c for c in calls))

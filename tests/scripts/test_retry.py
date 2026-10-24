import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class RetryTest(unittest.TestCase):
    def test_retry_reenters_same_release_through_gitops(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'deploy/gitops').mkdir(parents=True)
            (root / 'bin').mkdir()
            shutil.copy(ROOT / 'demo.sh', root / 'demo.sh')
            data = json.loads((ROOT / 'deploy/gitops/rollout.yaml').read_text())
            before = data['spec']['template']['metadata']['annotations']
            (root / 'deploy/gitops/rollout.yaml').write_text(json.dumps(data))
            log = root / 'git-calls'
            git = root / 'bin/git'
            git.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$GIT_CALL_LOG"\n')
            git.chmod(0o755)
            environment = dict(os.environ, PATH=str(root / 'bin') + ':' + os.environ['PATH'], GIT_CALL_LOG=str(log))
            result = subprocess.run(['bash', str(root / 'demo.sh'), 'retry-analysis'], env=environment, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            after = json.loads((root / 'deploy/gitops/rollout.yaml').read_text())
            self.assertNotEqual(before, after['spec']['template']['metadata']['annotations'])
            self.assertEqual(data['spec']['template']['spec'], after['spec']['template']['spec'])
            self.assertEqual(data['spec']['strategy'], after['spec']['strategy'])
            self.assertIn('push origin HEAD:main', log.read_text())

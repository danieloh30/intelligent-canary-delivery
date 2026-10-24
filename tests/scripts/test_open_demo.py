"""Exercise presenter launching without a browser, cluster, or credential files."""
import base64
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('open_demo_launcher', ROOT / 'scripts/open-demo.py')
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)


class OpenDemoTest(unittest.TestCase):
    token = 'presenter-private-token+/='

    def cluster_response(self, command, **kwargs):
        self.assertEqual({'check': True, 'capture_output': True, 'text': True}, kwargs)
        self.assertNotIn(self.token, ' '.join(command))
        if 'secret' in command:
            return SimpleNamespace(stdout=base64.b64encode(self.token.encode()).decode() + '\n')
        if 'canary-control' in command:
            return SimpleNamespace(stdout='decision-room.example.test\n')
        if 'openshift-gitops-server' in command:
            return SimpleNamespace(stdout='argocd.example.test\n')
        self.fail(f'Unexpected oc command: {command!r}')

    def assert_private_output(self, output):
        self.assertNotIn(self.token, output)
        self.assertNotIn(base64.b64encode(self.token.encode()).decode(), output)

    def test_cluster_open_reads_exact_route_and_secret_without_printing_token(self):
        output = io.StringIO()
        with patch.dict(os.environ, {'DEMO_NAMESPACE': 'presenter-demo'}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run', side_effect=self.cluster_response) as command, \
             patch.object(LAUNCHER.webbrowser, 'open', return_value=True) as browser, \
             contextlib.redirect_stdout(output):
            LAUNCHER.main(['open'])
        self.assertEqual([
            ['oc', '-n', 'presenter-demo', 'get', 'route', 'canary-control', '-o', 'jsonpath={.spec.host}'],
            ['oc', '-n', 'presenter-demo', 'get', 'secret', 'canary-secrets', '-o', 'jsonpath={.data.api-token}'],
        ], [call.args[0] for call in command.call_args_list])
        browser.assert_called_once()
        url = urlsplit(browser.call_args.args[0])
        self.assertEqual(('https', 'decision-room.example.test', '/', ''),
                         (url.scheme, url.netloc, url.path, url.query))
        self.assertEqual({'access': [self.token]}, parse_qs(url.fragment))
        self.assertEqual({'new': 2}, browser.call_args.kwargs)
        self.assert_private_output(output.getvalue())

    def test_local_open_uses_local_defaults_without_oc(self):
        with patch.dict(os.environ, {}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run') as command, \
             patch.object(LAUNCHER.webbrowser, 'open', return_value=True) as browser, \
             contextlib.redirect_stdout(io.StringIO()):
            LAUNCHER.main(['open', '--local'])
        command.assert_not_called()
        browser.assert_called_once_with('http://localhost:8095/#access=demo-local', new=2)

    def test_local_open_honors_port_and_private_token_without_oc(self):
        output = io.StringIO()
        with patch.dict(os.environ, {'DEMO_PORT': '8109', 'DEMO_API_TOKEN': self.token}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run') as command, \
             patch.object(LAUNCHER.webbrowser, 'open', return_value=True) as browser, \
             contextlib.redirect_stdout(output):
            LAUNCHER.main(['open', '--local'])
        command.assert_not_called()
        url = urlsplit(browser.call_args.args[0])
        self.assertEqual('localhost:8109', url.netloc)
        self.assertEqual({'access': [self.token]}, parse_qs(url.fragment))
        self.assert_private_output(output.getvalue())

    def test_present_opens_distinct_argocd_sso_tab_without_presenter_credential(self):
        output = io.StringIO()
        with patch.dict(os.environ, {'GITOPS_NAMESPACE': 'team-gitops'}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run', side_effect=self.cluster_response) as command, \
             patch.object(LAUNCHER.webbrowser, 'open', return_value=True) as browser, \
             contextlib.redirect_stdout(output):
            LAUNCHER.main(['present'])
        self.assertEqual(2, browser.call_count)
        decision, argo = [urlsplit(call.args[0]) for call in browser.call_args_list]
        self.assertEqual({'access': [self.token]}, parse_qs(decision.fragment))
        self.assertEqual(('https', 'argocd.example.test', '/applications', '', ''),
                         (argo.scheme, argo.netloc, argo.path, argo.query, argo.fragment))
        self.assertEqual(['oc', '-n', 'team-gitops', 'get', 'route',
                          'openshift-gitops-server', '-o', 'jsonpath={.spec.host}'],
                         command.call_args_list[-1].args[0])
        self.assertTrue(all(call.kwargs == {'new': 2} for call in browser.call_args_list))
        self.assertIn('Use your OpenShift SSO session', output.getvalue())
        self.assert_private_output(output.getvalue())

    def test_argo_only_does_not_read_presenter_secret(self):
        with patch.dict(os.environ, {}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run', side_effect=self.cluster_response) as command, \
             patch.object(LAUNCHER.webbrowser, 'open', return_value=True) as browser, \
             contextlib.redirect_stdout(io.StringIO()):
            LAUNCHER.main(['argo'])
        self.assertEqual(1, command.call_count)
        self.assertNotIn('secret', command.call_args.args[0])
        browser.assert_called_once_with('https://argocd.example.test/applications', new=2)

    def test_oc_failure_does_not_disclose_captured_credentials(self):
        failure = subprocess.CalledProcessError(1, ['oc'], output=self.token, stderr=self.token)
        output, errors = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, {}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run', side_effect=failure), \
             patch.object(LAUNCHER.webbrowser, 'open') as browser, \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors), \
             self.assertRaises(SystemExit) as raised:
            LAUNCHER.main(['open'])
        browser.assert_not_called()
        self.assert_private_output(output.getvalue() + errors.getvalue() + str(raised.exception))
        self.assertIn('Could not open presenter tabs', str(raised.exception))

    def test_browser_failure_does_not_disclose_local_credential(self):
        output = io.StringIO()
        with patch.dict(os.environ, {'DEMO_API_TOKEN': self.token}, clear=True), \
             patch.object(LAUNCHER.subprocess, 'run') as command, \
             patch.object(LAUNCHER.webbrowser, 'open', return_value=False), \
             contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as raised:
            LAUNCHER.main(['open', '--local'])
        command.assert_not_called()
        self.assert_private_output(output.getvalue() + str(raised.exception))

    def test_invalid_local_port_never_opens_a_browser(self):
        for port in ('not-a-port', '0', '65536'):
            with self.subTest(port=port), patch.dict(os.environ, {'DEMO_PORT': port}, clear=True), \
                 patch.object(LAUNCHER.subprocess, 'run') as command, \
                 patch.object(LAUNCHER.webbrowser, 'open') as browser, \
                 self.assertRaises(SystemExit):
                LAUNCHER.main(['open', '--local'])
            command.assert_not_called()
            browser.assert_not_called()


if __name__ == '__main__':
    unittest.main()

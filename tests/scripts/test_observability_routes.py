"""Check the OpenShift observability exposure contract without contacting a cluster."""
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


def documents(relative):
    return [json.loads(document) for document in (ROOT / relative).read_text().split('\n---\n')]


def resource(objects, kind, name):
    return next(item for item in objects if item['kind'] == kind and item['metadata']['name'] == name)


class ObservabilityRoutesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.routes = documents('deploy/openshift/observability-routes.yaml')
        cls.patches = documents('deploy/openshift/sso-observability.yaml')
        cls.base = documents('deploy/base/observability.yaml')
        cls.rbac = documents('deploy/openshift/sso-rbac.yaml')

    def pod_patch(self, name):
        return resource(self.patches, 'Deployment', name)['spec']['template']['spec']

    def test_external_routes_expose_only_the_https_oauth_entry_point(self):
        routes = [item for item in self.routes if item['kind'] == 'Route']
        self.assertEqual({'prometheus', 'jaeger'}, {item['metadata']['name'] for item in routes})
        for route in routes:
            with self.subTest(route=route['metadata']['name']):
                self.assertEqual({'kind': 'Service', 'name': route['metadata']['name']}, route['spec']['to'])
                self.assertEqual({'targetPort': 'oauth'}, route['spec']['port'])
                self.assertEqual({'termination': 'edge', 'insecureEdgeTerminationPolicy': 'Redirect'},
                                 route['spec']['tls'])

    def test_each_proxy_checks_only_its_own_service_and_forwards_no_credentials(self):
        for name, port in [('prometheus', 9090), ('jaeger', 16686)]:
            with self.subTest(service=name):
                pod = self.pod_patch(name)
                self.assertEqual(name, pod['serviceAccountName'])
                self.assertTrue(pod['automountServiceAccountToken'])
                proxy = next(item for item in pod['containers'] if item['name'] == 'oauth-proxy')
                args = proxy['args']
                self.assertIn('--provider=openshift', args)
                self.assertIn(f'--openshift-service-account={name}', args)
                self.assertIn(f'--upstream=http://127.0.0.1:{port}/', args)
                self.assertIn('--pass-access-token=false', args)
                self.assertIn('--pass-basic-auth=false', args)
                self.assertFalse(any(arg.startswith('--basic-auth-password=') for arg in args))
                self.assertFalse(any('DEMO_API_TOKEN' in json.dumps(entry) for entry in proxy.get('env', [])))
                sar = next(arg.split('=', 1)[1] for arg in args if arg.startswith('--openshift-sar='))
                self.assertEqual({'namespace': 'canary-demo', 'resource': 'services',
                                  'resourceName': name, 'verb': 'get'}, json.loads(sar))

    def test_independent_cookie_keys_and_redirect_accounts_match_each_route(self):
        keys = []
        for name in ('prometheus', 'jaeger'):
            pod = self.pod_patch(name)
            cookie = next(item for item in pod['volumes'] if item['name'] == 'oauth-cookie')['secret']
            self.assertEqual('observability-oauth', cookie['secretName'])
            self.assertEqual([{'key': f'{name}-cookie-secret', 'path': 'cookie-secret'}], cookie['items'])
            keys.append(cookie['items'][0]['key'])
            proxy = next(item for item in pod['containers'] if item['name'] == 'oauth-proxy')
            self.assertIn('--cookie-secret-file=/etc/oauth/cookie-secret', proxy['args'])
            self.assertIn({'name': 'oauth-cookie', 'mountPath': '/etc/oauth', 'readOnly': True},
                          proxy['volumeMounts'])
            account = resource(self.routes + self.patches, 'ServiceAccount', name)
            redirect = account['metadata']['annotations'][f'serviceaccounts.openshift.io/oauth-redirectreference.{name}']
            self.assertEqual({'kind': 'Route', 'name': name}, json.loads(redirect)['reference'])
        self.assertEqual(2, len(set(keys)))

    def test_overlay_adds_oauth_without_replacing_internal_ports_or_jaeger_probes(self):
        for name, internal_ports in [('prometheus', {9090}), ('jaeger', {16686, 4317})]:
            base = resource(self.base, 'Service', name)
            self.assertEqual(internal_ports, {item['port'] for item in base['spec']['ports']})
            patch = resource(self.patches, 'Service', name)
            self.assertEqual([{'name': 'oauth', 'port': 4180, 'targetPort': 'oauth'}], patch['spec']['ports'])
            self.assertNotIn('$patch', patch['spec'])
        # The sidecar patch must not replace the Jaeger container's OTLP readiness and admin probes.
        jaeger = resource(self.base, 'Deployment', 'jaeger')['spec']['template']['spec']['containers'][0]
        self.assertEqual('otlp', jaeger['readinessProbe']['tcpSocket']['port'])
        self.assertEqual('admin', jaeger['startupProbe']['httpGet']['port'])
        self.assertEqual('admin', jaeger['livenessProbe']['httpGet']['port'])
        self.assertEqual(['oauth-proxy'], [item['name'] for item in self.pod_patch('jaeger')['containers']])
        prometheus = next(item for item in self.pod_patch('prometheus')['containers'] if item['name'] == 'prometheus')
        self.assertEqual({'path': '/-/ready', 'port': 9090}, prometheus['readinessProbe']['httpGet'])
        self.assertEqual({'path': '/-/healthy', 'port': 9090}, prometheus['livenessProbe']['httpGet'])

    def test_presenter_access_is_scoped_to_getting_the_three_named_services(self):
        role = resource(self.rbac, 'Role', 'canary-presenter')
        self.assertEqual(1, len(role['rules']))
        rule = role['rules'][0]
        self.assertEqual([''], rule['apiGroups'])
        self.assertEqual(['services'], rule['resources'])
        self.assertEqual(['get'], rule['verbs'])
        self.assertEqual({'canary-control', 'prometheus', 'jaeger'}, set(rule['resourceNames']))


if __name__ == '__main__':
    unittest.main()

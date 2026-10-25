#!/usr/bin/env python3
"""Open presenter tabs without printing credentials or writing them to disk."""
import argparse
import os
import subprocess
import webbrowser
from urllib.parse import urlencode


def oc(*arguments):
    return subprocess.run(['oc', *arguments], check=True, capture_output=True, text=True).stdout.strip()


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=('open', 'argo', 'prometheus', 'jaeger', 'present'))
    parser.add_argument('--local', action='store_true')
    parser.add_argument('--observability', action='store_true', help='Also open Prometheus and Jaeger with present')
    args = parser.parse_args(arguments)
    if args.local and args.target != 'open':
        parser.error('--local applies to open; Argo CD requires the cluster')
    if args.observability and args.target != 'present':
        parser.error('--observability applies to present only')
    try:
        if args.target in ('open', 'present'):
            if args.local:
                port = int(os.environ.get('DEMO_PORT', '8095'))
                if not 1 <= port <= 65535:
                    raise ValueError('Invalid port')
                origin = f'http://localhost:{port}'
                token = os.environ.get('DEMO_API_TOKEN', 'demo-local')
            else:
                namespace = os.environ.get('DEMO_NAMESPACE', 'canary-demo')
                host = oc('-n', namespace, 'get', 'route', 'canary-control', '-o', 'jsonpath={.spec.host}')
                if not host:
                    raise ValueError('Route missing')
                token = ''
                origin = f'https://{host}'
            # Fragments are not sent in HTTP requests. The SPA consumes this once,
            # removes it from the address bar, and uses tab-scoped sessionStorage.
            if not webbrowser.open(origin + ('/#' + urlencode({'access': token}) if token else '/'), new=2):
                raise RuntimeError('Browser unavailable')
            print('Opened the decision room. OpenShift uses SSO; local mode uses presenter access.')
        if args.target in ('argo', 'present'):
            namespace = os.environ.get('GITOPS_NAMESPACE', 'openshift-gitops')
            host = oc('-n', namespace, 'get', 'route', 'openshift-gitops-server', '-o', 'jsonpath={.spec.host}')
            if not host or not webbrowser.open(f'https://{host}/applications', new=2):
                raise RuntimeError('Argo CD browser unavailable')
            print('Opened Argo CD. Use your OpenShift SSO session.')
        observability = ['prometheus', 'jaeger'] if args.target == 'present' and args.observability else [args.target] if args.target in ('prometheus', 'jaeger') else []
        for name in observability:
            namespace = os.environ.get('DEMO_NAMESPACE', 'canary-demo')
            host = oc('-n', namespace, 'get', 'route', name, '-o', 'jsonpath={.spec.host}')
            if not host or not webbrowser.open(f'https://{host}/', new=2):
                raise RuntimeError('Observability browser unavailable')
            print(f'Opened {name}. Use your OpenShift SSO session.')
    except (subprocess.CalledProcessError, OSError, ValueError, RuntimeError):
        raise SystemExit('Could not open presenter tabs. Check oc login, namespace access, routes, and the default browser. Credentials were not printed.')


if __name__ == '__main__':
    main()

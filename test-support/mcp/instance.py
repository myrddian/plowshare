#!/usr/bin/env python3
"""Isolated local acceptance server. Requires an explicit new private state directory.

Uses the current built server, a dedicated Docker PostgreSQL container and loopback
synthetic model/MCP services. Never reads installed Plowshare/deployment settings.
Stop retains all data; restart preserves accounts, keys and receipts. No DB test suite.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DATABASE_IMAGE = 'pgvector/pgvector:0.8.7-pg16-bookworm@sha256:7b822b0aac60967beb1ea5e576b8602c94c300a157d187f385ae3e0da199b90a'


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout.strip()


def private(path, value):
    with path.open('x') as out:
        out.write(value)
    path.chmod(0o600)


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def stop_process(state, name):
    path = state / (name + '.pid')
    if path.exists():
        pid = int(path.read_text())
        if alive(pid):
            # A retained PID is only a hint. Never signal a reused, unrelated process.
            command = run('ps', '-p', str(pid), '-o', 'command=')
            if str(state) not in command:
                raise RuntimeError('Refusing to signal an unrelated process; inspect the retained PID')
            os.kill(pid, signal.SIGTERM)
            for _ in range(100):
                if not alive(pid):
                    break
                time.sleep(0.1)
            if alive(pid):
                raise RuntimeError('Process has not stopped; inspect it before restarting')
        path.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'status'])
    parser.add_argument('state', type=Path)
    args = parser.parse_args()
    if not args.state.is_absolute() or args.state.is_symlink():
        parser.error('Use an absolute private directory without symlinks')
    state = args.state.resolve()
    if state == ROOT or ROOT in state.parents or state == Path('/'):
        parser.error('Keep test state outside the checkout in a dedicated directory')
    os.umask(0o077)
    config_path = state / 'instance.json'
    if not config_path.exists():
        if args.action != 'start':
            parser.error('No existing instance at this path')
        state.mkdir(mode=0o700, parents=True, exist_ok=False)
        settings = {'id': secrets.token_hex(8), 'password': secrets.token_urlsafe(24),
                    'databasePassword': secrets.token_urlsafe(24),
                    'key': base64.b64encode(secrets.token_bytes(32)).decode(),
                    'upstream': {name: secrets.token_urlsafe(24) for name in ['alice', 'bob', 'service']}}
        private(config_path, json.dumps(settings))
        for name in ['data', 'workspaces', 'applications', 'runtime']:
            (state / name).mkdir(mode=0o700)
        private(state / 'filestore.js', 'export default ' + json.dumps({'version': 1, 'defaultStore': 'test',
            'fileStores': {'test': {'root': str(state / 'applications'), 'access': {'accounts': [{'handle': 'test-admin', 'role': 'MANAGER'}]}}}}) + ';\n')
        private(state / 'database.env', 'POSTGRES_PASSWORD=' + settings['databasePassword'] + '\n')
    settings = json.loads(config_path.read_text())
    for key in ['serverPort', 'mcpPort', 'modelPort']:
        if key not in settings:
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                settings[key] = listener.getsockname()[1]
    config_path.write_text(json.dumps(settings))
    name = 'plowshare-mcp-test-' + settings['id']
    if args.action in ['stop', 'status']:
        container = json.loads(run('docker', 'inspect', name))[0]
        if container['Config']['Labels'].get('plowshare.mcp-test.state') != str(state):
            raise RuntimeError('Foreign container; refusing to control it')
    if args.action == 'stop':
        stop_process(state, 'server')
        stop_process(state, 'fixture')
        run('docker', 'stop', name)
        print('Stopped; private state and the dedicated database volume are retained at ' + str(state))
        return
    if args.action == 'status':
        for process in ['server', 'fixture']:
            path = state / (process + '.pid')
            print(process + ': ' + ('running' if path.exists() and alive(int(path.read_text())) else 'stopped'))
        print(run('docker', 'inspect', '--format', '{{.State.Status}}', name))
        return
    jars = [path for path in (ROOT / 'plowshare-server/build/libs').glob('*.jar') if not path.name.endswith('-plain.jar')]
    if len(jars) != 1:
        parser.error('Build :plowshare-server:bootJar first')
    for process in ['server', 'fixture']:
        path = state / (process + '.pid')
        if path.exists() and alive(int(path.read_text())):
            parser.error('Instance process already exists; stop before starting again')
    existing = subprocess.run(['docker', 'inspect', name], capture_output=True, text=True)
    if existing.returncode == 0:
        container = json.loads(existing.stdout)[0]
        if container['Config']['Labels'].get('plowshare.mcp-test.state') != str(state):
            raise RuntimeError('Foreign container; refusing to adopt it')
        run('docker', 'start', name)
    else:
        run('docker', 'run', '-d', '--name', name, '--label', 'plowshare.mcp-test.state=' + str(state),
            '--env-file', str(state / 'database.env'), '-e', 'POSTGRES_USER=plowshare', '-e', 'POSTGRES_DB=plowshare',
            '-p', '127.0.0.1::5432', '-v', name + ':/var/lib/postgresql/data', '--memory', '1g', DATABASE_IMAGE)
    for _ in range(120):
        ready = subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'plowshare'], capture_output=True)
        if ready.returncode == 0:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('Test database not ready')
    database_port = run('docker', 'port', name, '5432').rsplit(':', 1)[1]
    env = {key: os.environ[key] for key in ['PATH', 'JAVA_HOME', 'TMPDIR'] if key in os.environ}
    env['NODE_EXTRA_CA_CERTS'] = str(ROOT / 'integrations/mcp/src/fixtures/mcp-test-cert.pem')
    (state / 'fixture.json').unlink(missing_ok=True)
    with (state / 'fixture.log').open('ab') as log:
        fixture = subprocess.Popen(['node', str(ROOT / 'test-support/mcp/instance-fixture.mjs'), str(state)],
            env=env, stdout=log, stderr=log, start_new_session=True)
    (state / 'fixture.pid').write_text(str(fixture.pid))
    for _ in range(100):
        if (state / 'fixture.json').exists():
            break
        if fixture.poll() is not None:
            raise RuntimeError('Fixture failed; inspect private fixture.log')
        time.sleep(0.1)
    else:
        raise RuntimeError('Fixture startup deadline exceeded')
    fixture_config = json.loads((state / 'fixture.json').read_text())
    # Explicit loopback fixture values. No inference credentials or installed configuration.
    port = settings['serverPort']
    overlay = state / 'application.json'
    if not overlay.exists():
        private(overlay, json.dumps({'plowshare': {'providers': {
            'credentials': {'enabled': True, 'active-key': 'test', 'keys': {'test': settings['key']},
                'allowed-origins': [fixture_config['endpoint'].rsplit('/', 1)[0]],
                'callback': 'https://fixture.invalid/provider-credentials/oauth/callback'},
            'hosting': {'executors': []}}}}))
    else:
        current = json.loads(overlay.read_text())
        current['plowshare']['providers']['credentials']['allowed-origins'] = [fixture_config['endpoint'].rsplit('/', 1)[0]]
        overlay.write_text(json.dumps(current))
    current = json.loads(overlay.read_text())
    current['plowshare']['llm'] = {'pools': [{'name': 'test-fixture', 'base-url': fixture_config['model'],
        'provider': 'openai', 'models': ['mcp-test-chat', 'mcp-test-embedding'],
        'vision': ['mcp-test-chat'], 'chat': 2, 'embedding': 1, 'swarm': 0}]}
    overlay.write_text(json.dumps(current))
    snapshot = state / 'runtime/server.jar'
    shutil.copyfile(jars[0], snapshot)
    env.update({'PLOWSHARE_DB_URL': 'jdbc:postgresql://127.0.0.1:' + database_port + '/plowshare',
        'PLOWSHARE_DB_USER': 'plowshare', 'PLOWSHARE_DB_PASSWORD': settings['databasePassword'],
        'PLOWSHARE_DATA_DIR': str(state / 'data'), 'PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY': str(state / 'workspaces'),
        'PLOWSHARE_FILESTORES_CONFIG_FILE': str(state / 'filestore.js'),
        'PLOWSHARE_PORT': str(port), 'PLOWSHARE_BIND': '127.0.0.1', 'PLOWSHARE_AUTH_ENABLED': 'true',
        'PLOWSHARE_ADMIN_HANDLE': 'test-admin', 'PLOWSHARE_ADMIN_PASSWORD': settings['password'],
        'LLM_BASE_URL': fixture_config['model'], 'LLM_CHAT_MODEL': 'mcp-test-chat',
        'LLM_EMBEDDING_MODEL': 'mcp-test-embedding', 'DJL_CACHE_DIR': str(state / 'runtime/tokenizers'),
        'OPT_OUT_TRACKING': 'true', 'SPRING_APPLICATION_JSON': overlay.read_text()})
    with (state / 'server.log').open('ab') as log:
        server = subprocess.Popen(['java', '-Xmx1g', '-Duser.home=' + str(state / 'runtime'), '-jar', str(snapshot)],
            env=env, cwd=state / 'runtime', stdout=log, stderr=log, start_new_session=True)
    (state / 'server.pid').write_text(str(server.pid))
    origin = 'http://127.0.0.1:' + str(port)
    (state / 'origin.txt').write_text(origin + '\n')
    for _ in range(240):
        try:
            with urllib.request.urlopen(origin + '/ready', timeout=1) as response:
                if response.status in (200, 204):
                    print('Test instance ready at ' + origin)
                    print('Private login/configuration: ' + str(config_path))
                    return
        except (OSError, urllib.error.URLError):
            pass
        if server.poll() is not None:
            raise RuntimeError('Server failed; inspect private server.log')
        time.sleep(0.5)
    raise RuntimeError('Server startup deadline exceeded; inspect private server.log')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None

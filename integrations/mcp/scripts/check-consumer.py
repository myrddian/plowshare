#!/usr/bin/env python3
"""Compile and exercise the adapter against packed public SDKs in an isolated consumer."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
ADAPTER = ROOT / 'integrations/mcp'


def run(*command, cwd):
    subprocess.run(command, cwd=cwd, check=True)


with tempfile.TemporaryDirectory(prefix='plowshare-mcp-consumer-') as temporary:
    consumer = Path(temporary)
    for sdk in ['typescript', 'node']:
        run('npm', 'pack', '--ignore-scripts', '--pack-destination', str(consumer),
            cwd=ROOT / 'sdk' / sdk)
    package = json.loads((ADAPTER / 'package.json').read_text())
    for name in ['plowshare-client-ts', 'plowshare-client-node']:
        package['dependencies'][name] = 'file:' + str(consumer / (name + '-0.1.0.tgz'))
    (consumer / 'package.json').write_text(json.dumps(package))
    shutil.copytree(ADAPTER / 'src', consumer / 'src')
    shutil.copytree(ADAPTER / 'scripts', consumer / 'scripts')
    shutil.copy(ROOT / 'tsconfig.strict.json', consumer / 'tsconfig.strict.json')
    config = json.loads((ADAPTER / 'tsconfig.json').read_text())
    config['extends'] = './tsconfig.strict.json'
    (consumer / 'tsconfig.json').write_text(json.dumps(config))
    run('npm', 'install', '--ignore-scripts', '--no-audit', '--no-fund', cwd=consumer)
    run('node', 'node_modules/typescript/bin/tsc', '-p', 'tsconfig.json', cwd=consumer)
    run('node', '--input-type=module', '-e',
        "const adapter = await import('./build/mcp/index.js'); "
        "if (typeof adapter.McpProvider.discover !== 'function') throw new Error('Missing public adapter');",
        cwd=consumer)
    tests = sorted(str(file.relative_to(consumer)) for file in (consumer / 'build/mcp').glob('*.test.js'))
    if not tests:
        raise RuntimeError('No installed-consumer MCP tests')
    run('node', 'scripts/test.mjs', cwd=consumer)
    print('MCP adapter compiled and passed fixtures using installed public SDK artifacts.')

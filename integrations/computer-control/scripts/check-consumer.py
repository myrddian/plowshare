#!/usr/bin/env python3
"""Verify reusable provider imports and tests against packed public SDK artifacts."""
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
ADAPTER = ROOT / 'integrations/computer-control'
budget = int(os.environ.get('PLOWSHARE_COMPUTER_CONSUMER_TIMEOUT_SECONDS', '300'))
if budget < 1 or budget > 1800:
    raise ValueError('Consumer check budget must be 1..1800 seconds')
deadline = time.monotonic() + budget


def run(*command, cwd):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('Consumer check exhausted its shared deadline')
    process = subprocess.Popen(command, cwd=cwd, start_new_session=True)
    try:
        status = process.wait(timeout=remaining)
    except subprocess.TimeoutExpired:
        # Own the whole spawned command group; a caller timeout must not leave npm/test children.
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
        raise TimeoutError('Consumer check deadline expired; command group terminated') from None
    if status != 0:
        raise subprocess.CalledProcessError(status, command)


with tempfile.TemporaryDirectory(prefix='plowshare-computer-consumer-') as temporary:
    consumer = Path(temporary)
    for sdk in ['typescript', 'node']:
        run('npm', 'pack', '--quiet', '--ignore-scripts', '--pack-destination', str(consumer),
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
        "const provider = await import('plowshare-computer-control'); "
        "if (typeof provider.createComputerProvider !== 'function') "
        "throw new Error('Missing reusable SDK provider'); "
        "if (typeof provider.createBrowserControlBackend !== 'function' || "
        "typeof provider.decodeBrowserControlPolicy !== 'function') "
        "throw new Error('Missing reusable browser control capability'); "
        "if (typeof provider.createMacOSControlBackend !== 'function' || "
        "typeof provider.createCoordinatedMacOSBackend !== 'function' || "
        "typeof provider.decodeNativeControlPolicy !== 'function') "
        "throw new Error('Missing reusable native control capability'); "
        "if (provider.computerDeclarations().length !== 4 || "
        "provider.computerDeclarations('computer_', "
        "{controlBudgetMs:2000,observeBudgetMs:10000}, ['windows','type']).length !== 6) "
        "throw new Error('Explicit browser catalogue selection failed'); "
        "if (provider.computerDeclarations('computer_', "
        "{controlBudgetMs:2000,observeBudgetMs:10000}, [], ['windows','type']).length !== 6 || "
        "provider.computerDeclarations('computer_', "
        "{controlBudgetMs:2000,observeBudgetMs:10000}, ['navigate','type'], ['windows','type']).length !== 7) "
        "throw new Error('Explicit native/mixed catalogue selection failed');",
        cwd=consumer)
    run('node', 'scripts/test.mjs', cwd=consumer)
    print('Computer provider compiled and passed fixtures with installed public SDKs.')

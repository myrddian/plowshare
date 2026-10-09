#!/usr/bin/env python3
"""Explicit local SDK build; never installs toolchains or publishes packages."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/distributions'
ENV = dict(os.environ, DOTNET_CLI_TELEMETRY_OPTOUT='1', DOTNET_GENERATE_ASPNET_CERTIFICATE='false', DOTNET_CLI_HOME=str(ROOT / 'build/dotnet-home'), NUGET_PACKAGES=str(ROOT / 'build/nuget'), GOCACHE=os.environ.get('GOCACHE',str(ROOT / 'build/go-cache')), GOMODCACHE=os.environ.get('GOMODCACHE',str(ROOT / 'build/go-mod-cache')), PLOWSHARE_SDK_DTO_FIXTURES=str(ROOT / 'test-support/contracts/sdk-dto-conformance.json'), PYTHONPATH=str(ROOT / 'sdk/python/src'))

def tool(variable, name, local=None):
    value = os.environ.get(variable) or shutil.which(name)
    if not value and local and local.is_file():
        value = str(local)
    if not value:
        raise SystemExit(f'{name} is required; install it or set {variable}. See docs/sdks.md.')
    return value

def run(*args, cwd=ROOT):
    subprocess.run(args, cwd=cwd, env=ENV, check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'distributions'])
    action = parser.parse_args().action
    python = tool('PLOWSHARE_SDK_PYTHON', 'python3', ROOT / 'build/sdk-python-env/bin/python')
    # The documented venv takes precedence over a bare Python installation.
    if not os.environ.get('PLOWSHARE_SDK_PYTHON') and (ROOT / 'build/sdk-python-env/bin/python').is_file():
        python = str(ROOT / 'build/sdk-python-env/bin/python')
    dotnet = tool('PLOWSHARE_SDK_DOTNET', 'dotnet', ROOT / 'build/dotnet/dotnet')
    go = tool('PLOWSHARE_SDK_GO', 'go')
    run('node', '--test', 'sdk/node/test/tools.test.mjs')
    run(python, '-c', 'import websockets; import build; import setuptools; import mypy; import ruff')
    run(python, '-m', 'ruff', 'check', '.', cwd=ROOT / 'sdk/python')
    run(python, '-m', 'ruff', 'format', '--check', '.', cwd=ROOT / 'sdk/python')
    run(python, '-m', 'mypy', 'src', 'tests', cwd=ROOT / 'sdk/python')
    run(python, '-m', 'unittest', 'discover', '-s', 'sdk/python/tests', '-p', 'test_*.py')
    run(dotnet, 'build', 'sdk/dotnet/Conformance', '--configuration', 'Debug', '--nologo')
    run(dotnet, 'run', '--project', 'sdk/dotnet/ContractTests')
    run(go, 'vet', './...', cwd=ROOT / 'sdk/go')
    run(go, 'test', '-race', './...', cwd=ROOT / 'sdk/go')
    if action == 'distributions':
        OUT.mkdir(parents=True, exist_ok=True)
        pnpm = tool('PLOWSHARE_SDK_PNPM', 'pnpm')
        for package, directory in {'plowshare-client-ts': 'sdk/typescript', 'plowshare-client-node': 'sdk/node'}.items():
            # Gradle has built and checked these outputs. Prepack would rewrite
            # shared JS while parallel application checks are importing it.
            run(pnpm, '--config.ignore-scripts=true', 'pack', '--out', str(OUT / (package + '-0.1.0.tgz')), cwd=ROOT / directory)
        run(python, '-m', 'build', 'sdk/python', '--no-isolation', '--outdir', str(OUT))
        run(dotnet, 'pack', 'sdk/dotnet/Plowshare.Sdk', '--configuration', 'Release', '--output', str(OUT), '--nologo')
        with tarfile.open(OUT / 'plowshare-sdk-go-0.1.0.tar.gz', 'w:gz') as archive:
            for name in ['README.md', 'go.mod', 'go.sum', 'client.go', 'packets.go', 'tools.go', 'protocol_generated.go', 'contracts_generated.go', 'codec.go', 'validation.go', 'schemas.json']:
                path = ROOT / 'sdk/go' / name
                info = archive.gettarinfo(str(path), 'plowshare-sdk-go/' + name)
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ''
                with path.open('rb') as content:
                    archive.addfile(info, content)
        print(f'Local SDK packages: {OUT}')

if __name__ == '__main__':
    main()

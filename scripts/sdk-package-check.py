#!/usr/bin/env python3
"""Verify local SDK distributions through fresh consumers, without publishing."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import runpy
import tarfile
config = runpy.run_path(str(Path(__file__).with_name('sdk-build.py')))
ENV, OUT, ROOT, tool = (config[key] for key in ['ENV', 'OUT', 'ROOT', 'tool'])


def main():
    base = Path(tempfile.mkdtemp(prefix='sdk-consumer-', dir=ROOT / 'build'))
    env = dict(ENV)
    # The build's source PYTHONPATH must not make pip mistake egg-info for an
    # installed wheel, or let the fresh consumer import the checkout.
    env.pop('PYTHONPATH', None)
    def run(*args, cwd=ROOT):
        subprocess.run(args, cwd=cwd, env=env, check=True)

    node = base / 'node'
    node.mkdir()
    (node / 'package.json').write_text(json.dumps({'name': 'sdk-consumer-fixture', 'private': True, 'type': 'module'}))
    run('npm', 'install', '--ignore-scripts', '--no-audit', '--no-fund', str(OUT / 'plowshare-client-ts-0.1.0.tgz'), str(OUT / 'plowshare-client-node-0.1.0.tgz'), cwd=node)
    (node / 'entry.mjs').write_text("export * from 'plowshare-client-node'\n")
    (node / 'types.ts').write_text("import { connectPlowshare, type Reply, type Payloads } from 'plowshare-client-node'\nconst payload: Payloads['project.list'] = {}\nasync function exercise(origin: string, token: string): Promise<Reply> { const client = await connectPlowshare({ origin, token }); try { return await client.request('project.list', payload) } finally { client.close() } }\n")
    run('node', str(ROOT / 'sdk/node/node_modules/typescript/bin/tsc'), '--noEmit', '--strict', '--target', 'ES2022', '--lib', 'ES2022', '--module', 'NodeNext', '--moduleResolution', 'NodeNext', 'types.ts', cwd=node)
    with (node / 'types.ts').open('a') as checks:
        checks.write("import { ToolProvider, nodeToolHost, type ToolJournal, type ToolBinding } from 'plowshare-client-node/tools'\nimport type { ToolDeclaration } from 'plowshare-client-ts'\nconst scope: ToolDeclaration = { name: 'network_scope', description: 'Inspect scope', parameters: [], timeoutSeconds: 30 }\nasync function tools(client: Parameters<typeof ToolProvider.create>[0], binding: ToolBinding, journal: ToolJournal) { return ToolProvider.create(client, binding, [{ declaration: scope, handler: async () => ({ state: 'COMPLETED', text: 'scope' }) }], journal, nodeToolHost) }\n")
    run('node', str(ROOT / 'sdk/node/node_modules/typescript/bin/tsc'), '--noEmit', '--strict', '--target', 'ES2022', '--lib', 'ES2022', '--module', 'NodeNext', '--moduleResolution', 'NodeNext', 'types.ts', cwd=node)
    env['PLOWSHARE_SDK_NODE_ENTRY'] = (node / 'entry.mjs').as_uri()

    python = tool('PLOWSHARE_SDK_PYTHON', 'python3', ROOT / 'build/sdk-python-env/bin/python')
    run(python, '-m', 'venv', str(base / 'python'))
    python = str(base / 'python/bin/python')
    run(python, '-m', 'pip', 'install', str(OUT / 'plowshare_sdk-0.1.0-py3-none-any.whl'))
    run(python, '-I', '-c', 'import plowshare; from plowshare import ToolProvider, ToolDeclaration, SqliteToolJournal; from pathlib import Path; import sys; assert Path(plowshare.__file__).is_relative_to(Path(sys.prefix))')
    env['PLOWSHARE_SDK_PYTHON'] = python
    env['PLOWSHARE_SDK_PYTHONPATH'] = ''

    dotnet = base / 'dotnet'
    dotnet.mkdir()
    shutil.copy(ROOT / 'sdk/dotnet/Conformance/Program.cs', dotnet / 'Program.cs')
    (dotnet / 'Consumer.csproj').write_text('<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework><RollForward>Major</RollForward><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable><TreatWarningsAsErrors>true</TreatWarningsAsErrors></PropertyGroup><ItemGroup><PackageReference Include="Plowshare.Sdk" Version="0.1.0" /></ItemGroup></Project>')
    (dotnet / 'ToolsConsumer.cs').write_text("using Plowshare.Sdk;\ninternal static class ToolsConsumer { internal static ToolProvider Create(Client client, ToolBinding binding, IToolJournal journal) => new(new SdkToolRelayPorts(client), binding, [new RegisteredTool(new ToolDeclaration(\"network_scope\", \"Inspect scope\", [], 30), (call, token) => Task.FromResult(new ToolResult(\"COMPLETED\", \"scope\")))], journal); }\n")
    dotnet_tool = tool('PLOWSHARE_SDK_DOTNET', 'dotnet', ROOT / 'build/dotnet/dotnet')
    env['NUGET_PACKAGES'] = str(base / 'nuget')
    run(dotnet_tool, 'build', str(dotnet), '--source', str(OUT), '--source', 'https://api.nuget.org/v3/index.json', '--nologo')
    env['PLOWSHARE_SDK_DOTNET'] = dotnet_tool
    env['PLOWSHARE_SDK_DOTNET_PROJECT'] = str(dotnet)

    go = base / 'go'
    go.mkdir()
    with tarfile.open(OUT / 'plowshare-sdk-go-0.1.0.tar.gz') as archive:
        archive.extractall(go, filter='data')
    consumer = go / 'consumer'
    (consumer / 'cmd/conformance').mkdir(parents=True)
    shutil.copy(ROOT / 'sdk/go/cmd/conformance/main.go', consumer / 'cmd/conformance/main.go')
    (consumer / 'go.mod').write_text('module sdk-consumer-fixture\n\ngo 1.23.0\nrequire io.aeyer/plowshare/sdk v0.1.0\nreplace io.aeyer/plowshare/sdk => ../plowshare-sdk-go\n')
    (consumer / 'cmd/conformance/tools.go').write_text('package main\nimport ("context"; sdk "io.aeyer/plowshare/sdk")\nfunc tools(client *sdk.Client, binding sdk.ToolBinding, journal sdk.ToolJournal) (*sdk.ToolProvider, error) { return sdk.NewToolProvider(client,binding,[]sdk.RegisteredTool{{Declaration:sdk.ToolDeclaration{Name:"network_scope",Description:"Inspect scope",TimeoutSeconds:30},Handler:func(context.Context,sdk.ToolCall)(sdk.ToolResult,error){return sdk.ToolResult{State:"COMPLETED",Text:"scope"},nil}}},journal) }\n')
    go_tool = tool('PLOWSHARE_SDK_GO', 'go')
    run(go_tool, 'mod', 'tidy', cwd=consumer)
    env['PLOWSHARE_SDK_GO_ROOT'] = str(consumer)
    env['PLOWSHARE_SDK_GO'] = go_tool
    run('node', 'scripts/sdk-conformance.mjs')
    print('Fresh distribution consumers passed: ' + str(base))

if __name__ == '__main__':
    main()

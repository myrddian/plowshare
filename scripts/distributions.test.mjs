import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { chmod, copyFile, mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';
import { protocolFixture } from '../plowshare-desktop/scripts/protocol-fixture.mjs';

function launch(command, args, options) {
  const child = spawn(command, args, { ...options, stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '', error = '';
  child.stdout.on('data', bytes => { output += bytes; });
  child.stderr.on('data', bytes => { error += bytes; });
  const finished = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve({ code, output, error })); });
  return { child, finished, output: () => output, error: () => error };
}
async function execute(command, args, options, expectedCode = 0) {
  const held = launch(command, args, options); held.child.stdin.end();
  const result = await held.finished;
  assert.equal(result.code, expectedCode, result.error + '\n' + result.output);
  return result;
}
async function unpack(name, destination) {
  const archive = resolve('build/distributions', `${name}.tar.gz`);
  const digest = createHash('sha256').update(await readFile(archive)).digest('hex');
  assert.equal(await readFile(archive + '.sha256', 'utf8'), `${digest}  ${name}.tar.gz\n`);
  execFileSync('tar', ['-xzf', archive, '-C', destination]);
  return join(destination, name);
}

test('single executable with no runtime on PATH: shared login, CLI/MCP/TUI operations on WS', { timeout: 30000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare fresh install '));
  const fixture = await protocolFixture();
  let mcp, tui;
  try {
    const installed = await unpack('plowshare-clients-0.1.0-darwin-arm64', directory);
    assert.deepEqual((await readdir(installed)).sort(), ['INSTALL.md', 'NODE-LICENSE.txt', 'THIRD-PARTY-NOTICES.txt', 'bin', 'build-info.json']);
    const emptyPath = join(directory, 'no installed runtimes'); await mkdir(emptyPath);
    const working = join(directory, 'user project'); await mkdir(join(working, '.plowshare'), { recursive: true });
    await writeFile(join(working, '.plowshare/project'), 'Empty workspace\n');
    const env = { ...process.env, PATH: emptyPath, PLOWSHARE_CONFIG_DIR: join(directory, 'user config'), PLOWSHARE_URL: fixture.base, HOME: directory, XDG_CONFIG_HOME: join(directory, 'user config') };
    for (const name of ['PLOWSHARE_HANDLE', 'PLOWSHARE_PASSWORD', 'PLOWSHARE_PROJECT', 'PLOWSHARE_AGENT', 'PLOWSHARE_HERE', 'NODE_PATH', 'NODE_OPTIONS', 'DEV']) delete env[name];
    const options = { cwd: working, env };
    // Copy only the executable, away from every sibling file and its original name.
    const cli = join(directory, 'one standalone executable');
    await copyFile(join(installed, 'bin/plowshare'), cli); await chmod(cli, 0o555);
    assert.match((await execute(cli, ['--version'], options)).output, /Plowshare 0\.1\.0 .*bundled Node 26\.7\.0/);
    const licenses = (await execute(cli, ['--licenses'], options)).output;
    for (const library of ['Node.js', 'ink@7.1.1', 'yoga-layout@3.2.1']) assert.ok(licenses.includes(library), library);
    assert.match((await execute(cli, ['--help'], options)).output, /plowshare talk/);
    assert.match((await execute(cli, ['talk', '--help'], options)).output, /Open the Plowshare terminal/);
    assert.equal((await execute(cli, ['mcp', '--help'], options)).output, '', 'MCP help must not contaminate stdout');
    for (const name of ['plowshare-cli', 'plowshare-mcp', 'plowshare-talk']) await execute(join(installed, 'bin', name), ['--help'], options);
    assert.equal(fixture.httpPaths.length, 0, 'help must be offline');
    await execute(cli, ['login'], { ...options, env: { ...env, PLOWSHARE_HANDLE: 'fixture', PLOWSHARE_PASSWORD: 'fixture-password' } });
    const agents = await execute(cli, ['agent', 'list', '--json'], options);
    assert.equal(JSON.parse(agents.output).status, 'completed');

    mcp = launch(cli, ['mcp'], options);
    let sequence = 0;
    async function rpc(method, params = {}) {
      const id = ++sequence;
      mcp.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
      for (let attempt = 0; attempt < 300; attempt++) {
        const lines = mcp.output().trim().split('\n').filter(Boolean).map(line => JSON.parse(line));
        const result = lines.find(line => line.id === id);
        if (result) return result;
        await pause(10);
      }
      throw new Error('No MCP response: ' + mcp.error());
    }
    assert.equal((await rpc('initialize')).result.serverInfo.name, 'plowshare');
    const listed = (await rpc('tools/list')).result.tools;
    assert.equal(listed.length, 35);
    assert.ok(listed.some(tool => tool.name === 'memory_index'));
    const called = await rpc('tools/call', { name: 'memory_index', arguments: {} });
    assert.notEqual(called.result.isError, true, JSON.stringify(called));
    assert.ok(called.result.content[0].text.length);
    mcp.child.stdin.end(); assert.equal((await mcp.finished).code, 0);

    const retrieved = await execute(cli, ['talk', 'memory', 'navigate', '--json', 'remember the evidence'], options, 3);
    assert.equal(JSON.parse(retrieved.output).complete, false, 'fixture deliberately returns an incomplete navigation');
    // Loads the bundled Ink/Yoga renderer even when a pipe selects the plain surface.
    tui = launch(cli, ['talk', '--agent', 'fixture-bot'], options);
    tui.child.stdin.write('Packaged terminal test\n');
    for (let attempt = 0; attempt < 500 && !fixture.frames.some(frame => frame.type === 'conversation.open'); attempt++) await pause(10);
    assert.ok(fixture.frames.some(frame => frame.type === 'conversation.open'), tui.error() + tui.output());
    for (let attempt = 0; attempt < 500 && !fixture.frames.some(frame => frame.type === 'agent.run'); attempt++) await pause(10);
    assert.ok(fixture.frames.some(frame => frame.type === 'agent.run'), tui.error() + tui.output());
    fixture.completeLatest('Packaged terminal finished.');
    for (let attempt = 0; attempt < 500 && !tui.output().includes('Packaged terminal finished.'); attempt++) await pause(10);
    assert.ok(tui.output().includes('Packaged terminal finished.'), tui.error() + tui.output());
    tui.child.stdin.end();
    assert.equal((await tui.finished).code, 0, tui.error() + tui.output());
    const before = fixture.frames.length;
    const python = execFileSync('python3', ['-c', 'import sys; print(sys.executable)'], { encoding: 'utf8' }).trim();
    tui = launch(python, [resolve('scripts/distribution-pty.py'), cli, 'talk', '--agent', 'fixture-bot'], { ...options, env: { ...env, TERM: 'xterm-256color' } });
    for (let attempt = 0; attempt < 500 && !fixture.frames.slice(before).some(frame => frame.type === 'conversation.latest'); attempt++) await pause(10);
    assert.ok(fixture.frames.slice(before).some(frame => frame.type === 'conversation.latest'), tui.error() + tui.output());
    await pause(100);
    tui.child.stdin.write('Packaged interactive test\r');
    for (let attempt = 0; attempt < 500 && !fixture.frames.slice(before).some(frame => frame.type === 'agent.run'); attempt++) await pause(10);
    assert.ok(fixture.frames.slice(before).some(frame => frame.type === 'agent.run'), tui.error() + tui.output());
    fixture.completeLatest('Packaged interactive finished.');
    const plain = () => tui.output().replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '');
    for (let attempt = 0; attempt < 500 && !plain().includes('Packaged interactive finished.'); attempt++) await pause(10);
    assert.ok(plain().includes('Packaged interactive finished.'), tui.error() + plain());
    tui.child.stdin.end();
    assert.equal((await tui.finished).code, 0, tui.error() + plain());
    for (const operation of ['agent.list', 'memory.index', 'memory.navigate', 'conversation.open']) assert.ok(fixture.frames.some(frame => frame.type === operation), operation);
    assert.deepEqual([...new Set(fixture.httpPaths)].sort(), ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket']);
    assert.equal(fixture.httpPaths.filter(path => path === '/v1/auth/login').length, 1, 'all subsequent clients must reuse the saved token');
    assert.equal(fixture.frames.filter(frame => frame.type === 'agent.run').length, 2, 'each TUI entry starts exactly one agent run');
    assert.ok(fixture.frames.some(frame => frame.type === 'agent.list' && frame.payload?.project === 'Empty workspace'), 'project discovery must use the caller directory, not the installed executable directory');
    assert.deepEqual((await readdir(join(installed, 'bin'))).sort(), ['plowshare', 'plowshare-cli', 'plowshare-mcp', 'plowshare-talk']);
  } finally { mcp?.child.kill(); tui?.child.kill(); await fixture.close(); await rm(directory, { recursive: true, force: true }); }
});

test('headless archive rebuild has stable metadata and bytes', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare reproducibility '));
  try {
    const name = 'plowshare-clients-0.1.0-darwin-arm64';
    const installed = await unpack(name, directory);
    const rebuilt = join(directory, 'rebuilt.tar.gz');
    execFileSync('python3', ['scripts/distribution-archive.py', installed, rebuilt]);
    assert.deepEqual(await readFile(rebuilt), await readFile(resolve('build/distributions', name + '.tar.gz')));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('native executable is signed and links only macOS system libraries', async () => {
  const executable = resolve('build/distributions/plowshare-0.1.0-darwin-arm64');
  const bytes = await readFile(executable);
  const digest = createHash('sha256').update(bytes).digest('hex');
  assert.equal(await readFile(executable + '.sha256', 'utf8'), `${digest}  plowshare-0.1.0-darwin-arm64\n`);
  execFileSync('codesign', ['--verify', '--strict', executable]);
  // Read Mach-O load commands directly; otool can require an accepted Xcode license.
  assert.equal(bytes.readUInt32LE(0), 0xfeedfacf, 'Expected a 64-bit Mach-O executable');
  let offset = 32;
  const libraries = [];
  for (let i = 0; i < bytes.readUInt32LE(16); i++) {
    const command = bytes.readUInt32LE(offset), size = bytes.readUInt32LE(offset + 4);
    if ([0xc, 0x80000018, 0x8000001f, 0x20, 0x80000023].includes(command)) {
      const name = offset + bytes.readUInt32LE(offset + 8);
      libraries.push(bytes.toString('utf8', name, bytes.indexOf(0, name)));
    }
    offset += size;
  }
  assert.ok(libraries.length > 0);
  for (const library of libraries) assert.match(library, /^\/(usr\/lib|System\/Library)\//, `External installation required: ${library}`);
});

test('server archive contains an executable jar; installed launcher uses external config/data and immutable jar', { timeout: 15000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare server install '));
  try {
    const installed = await unpack('plowshare-server-0.1.0', directory);
    const java = join(directory, 'runtime bin'); await mkdir(java);
    // Inspect executable payload without starting or changing the user's database.
    const entries = execFileSync('unzip', ['-Z1', join(installed, 'lib/plowshare-server.jar')], { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
    for (const entry of ['org/springframework/boot/loader/launch/JarLauncher.class', 'BOOT-INF/classes/io/aeyer/plowshare/server/ws/OrchestrationFrames$Definitions.class', 'BOOT-INF/classes/io/aeyer/plowshare/server/ws/BoardInspectionFrames$ListBody.class']) assert.ok(entries.includes(entry), entry);
    await writeFile(join(java, 'java'), '#!/bin/sh\nprintf "%s\\n" "$PWD" "$@"\n', { mode: 0o755 });
    const env = { ...process.env, PATH: java + ':' + process.env.PATH, HOME: directory, PLOWSHARE_SERVER_CONFIG: join(directory, 'external config'), PLOWSHARE_SERVER_DATA: join(directory, 'external data'), PLOWSHARE_RUNTIME_DIR: join(directory, 'runtime cache') };
    const options = { cwd: directory, env }, launcher = join(installed, 'bin/plowshare-server');
    await execute(launcher, ['--help'], options);
    await execute(launcher, [], { ...options, env: { ...env, PLOWSHARE_AUTH_ENABLED: 'false', PLOWSHARE_BIND: '0.0.0.0' } }, 1);
    const first = await execute(launcher, ['--server.port=0'], options);
    const lines = first.output.trim().split('\n');
    assert.equal(lines[0], env.PLOWSHARE_SERVER_DATA);
    assert.equal(lines[1], '-jar');
    assert.match(lines[2], /runtime cache\/[a-f0-9]{64}\.jar$/);
    assert.equal(lines[3], `--spring.config.additional-location=optional:file:${env.PLOWSHARE_SERVER_CONFIG}/`);
    assert.equal(lines[4], '--server.port=0');
    assert.deepEqual(await readFile(lines[2]), await readFile(join(installed, 'lib/plowshare-server.jar')));
    assert.equal((await execute(launcher, ['--server.port=0'], options)).output, first.output);
    assert.equal((await readdir(env.PLOWSHARE_RUNTIME_DIR)).length, 1);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

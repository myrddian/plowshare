import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, copyFile, chmod, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { McpWire } from '../integrations/mcp/build/mcp/wire.test-support.js';
import { nativeFixture } from '../integrations/mcp/build/mcp/native.test-support.js';

for (const transport of ['http', 'stdio']) {
  test(`fresh standalone executable: native ${transport} NDJSON calls work without a runtime on PATH or a Plowshare server`, { timeout: 15000 }, async () => {
    const fixture = await nativeFixture(), directory = await mkdtemp(join(tmpdir(), 'plowshare native installed '));
    const wire = transport === 'http' ? new McpWire('2026-07-28') : null;
    await wire?.start();
    const executable = join(directory, 'plowshare-cli');
    await copyFile(resolve('build/distributions/plowshare-0.1.0-darwin-arm64'), executable);
    await chmod(executable, 0o555);
    const emptyPath = join(directory, 'empty PATH'); await mkdir(emptyPath);
    if (wire !== null) await writeFile(fixture.file, JSON.stringify({ ...fixture.config, transport: { kind: 'http', endpoint: wire.endpoint, allowHttp: true, allowPrivateNetwork: true } }), { mode: 0o600 });
    const child = spawn(executable, ['mcp', 'local', 'start', fixture.file], { cwd: directory, env: { PATH: emptyPath, NATIVE_SELECTED: 'selected-fixture' }, stdio: ['pipe', 'pipe', 'pipe'] });
    let errors = '', status = false, count = 0;
    child.stderr.on('data', bytes => { errors += bytes; });
    const rows = [];
    const input = createInterface({ input: child.stdout, terminal: false });
    const completed = new Promise((accept, reject) => { child.once('error', reject); child.once('close', code => code === 0 ? accept() : reject(new Error(`Native packaged host failed (${code}): ${errors}`))); });
    const timer = setTimeout(() => { child.kill('SIGTERM'); }, 10000);
    try {
      for await (const line of input) {
        const row = JSON.parse(line); rows.push(row);
        if (!status) {
          assert.equal(row.status, 'serving');
          assert.equal(row.host.active, true);
          const tool = row.host.tools[0]; assert.ok(tool);
          const call = JSON.stringify({ invocationId: randomUUID(), tool: tool.name, arguments: { values: [{ name: 'packaged' }] }, deadline: new Date(Date.now() + 3000).toISOString() });
          child.stdin.write(call + '\n' + call + '\n'); status = true;
        } else {
          assert.equal(row.state, 'COMPLETED');
          assert.deepEqual(JSON.parse(row.text).structuredContent, { values: [{ name: 'packaged' }] });
          if (++count === 2) child.stdin.end();
        }
      }
      await completed;
      assert.equal(rows.length, 3); assert.deepEqual(rows[1], rows[2]);
      assert.equal(errors.includes('fixture-secret'), false);
      assert.equal(transport === 'http' ? wire.seen.filter(row => row.method === 'tools/call').length : (await fixture.seen()).filter(row => row.method === 'tools/call').length, 1);
    } finally {
      clearTimeout(timer); input.close(); child.kill('SIGTERM');
      await wire?.close(); await fixture.close(); await rm(directory, { recursive: true, force: true });
    }
  });
}

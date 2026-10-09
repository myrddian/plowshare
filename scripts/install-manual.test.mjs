import {fixtureSocket} from './sdk-packet-fixture.mjs';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { loadManual, newJournal, options, publishManual } from './install-manual.mjs';

const ROOT = new URL('../', import.meta.url);

test('live installation requires an explicit origin; offline preview never invents one', () => {
  assert.throws(() => options([], {}), /Set --url or PLOWSHARE_URL/);
  assert.equal(options(['--dry-run'], {}).url, undefined);
  assert.equal(options(['--help'], {}).url, undefined);
  assert.equal(options(['--url', 'https://server.invalid/'], {}).url, 'https://server.invalid');
  assert.equal(options([], { PLOWSHARE_URL: 'https://server.invalid/' }).url, 'https://server.invalid');
});
const digest = text => createHash('sha256').update(text).digest('hex');
const plan = text => ({ edition: '1', chapters: [{ id: 'intro', title: 'Introduction',
  name: 'Plowshare manual / intro.md', text, hash: digest(text) }] });

/** Separate admission, readiness, audience and retained text. Receipt reuse models
 * the server contract across connections; it does not pretend to be PostgreSQL. */
function catalogue() {
  const calls = [], rows = new Map(), receipts = new Map();
  let drop = false, derivation = 'ready';
  async function call(operation, payload, scope = 'personal') {
    calls.push({ operation, payload, scope });
    if (payload.requestId && receipts.has(payload.requestId)) return receipts.get(payload.requestId);
    let result;
    if (operation === 'upload') {
      const revision = randomUUID();
      rows.set(revision, { name: payload.name, text: payload.text, tags: [], shared: false, excluded: false });
      result = { revision, resource: randomUUID(), created: true };
    } else {
      const row = rows.get(payload.revision); assert.ok(row, 'existing revision required');
      if (operation === 'tags') { row.tags = payload.tags; result = { changed: true }; }
      else if (operation === 'status') result = { id: payload.revision, generation: 1,
        availability: 'active', excluded: row.excluded, tags: row.tags,
        steps: [{ stage: 'extract', state: 'ready', generation: 1, attempt: 1 },
          { stage: 'derive', state: derivation, generation: 1, attempt: 1 },
          { stage: 'embed', state: 'failed', generation: 1, attempt: 1 }] };
      else if (operation === 'read') {
        if (scope === 'shared' && !row.shared) throw new Error('unavailable in Shared');
        const start = payload.offset ?? 0, text = row.text.slice(start, start + payload.limit);
        result = { revision: payload.revision, start, end: start + text.length, total: row.text.length, text };
      } else if (operation === 'share') { row.shared = true; result = { changed: true }; }
      else if (operation === 'exclude') { row.excluded = true; result = { availability: 'excluded' }; }
      else throw new Error(`unexpected operation ${operation}`);
    }
    if (payload.requestId) receipts.set(payload.requestId, result);
    if (operation === 'upload' && drop) throw new Error('reply lost after admission');
    return result;
  }
  return { calls, rows, call, client: { call }, sharedClient: { call: (op, payload) => call(op, payload, 'shared') },
    drop(value) { drop = value; }, stage(value) { derivation = value; } };
}

function installation(server, extra = {}) {
  const snapshots = [];
  return { journal: newJournal('http://server.invalid', 'alice'),
    persist: async journal => { snapshots.push(structuredClone(journal)); },
    sharedClient: server.sharedClient, snapshots, ...extra };
}

test('actual chapters are bounded and deterministic; dry run is offline', async () => {
  const first = await loadManual();
  assert.ok(first.chapters.every(chapter => chapter.name === `Plowshare manual / ${chapter.id}.md`));
  assert.equal(first.chapters.length, 37); assert.deepEqual(first, await loadManual());
  const dry = await execute(['--dry-run'], { PLOWSHARE_URL: 'http://unreachable.invalid' });
  assert.equal(dry.code, 0, dry.stderr); assert.match(dry.stdout, /37 chapters validated; no requests/);
});

test('manifest refuses escaping symlinks/duplicates, and Library links preserve fenced examples', async () => {
  const root = await mkdtemp(join(tmpdir(), 'manual-manifest-'));
  const outside = await mkdtemp(join(tmpdir(), 'manual-outside-'));
  try {
    await mkdir(join(root, 'docs/manual'), { recursive: true });
    await writeFile(join(outside, 'private.md'), '# Not a chapter\n');
    await symlink(join(outside, 'private.md'), join(root, 'docs/manual/linked.md'));
    const manifest = { formatVersion: 1, edition: '1', chapters: [{ id: 'one', title: 'One', path: 'docs/manual/linked.md' }] };
    const save = () => writeFile(join(root, 'docs/manual/manifest.json'), JSON.stringify(manifest));
    await save(); await assert.rejects(loadManual(root), /inside the checkout/);
    await writeFile(join(root, 'docs/manual/one.md'), '# One\n[Two](two.md)\n```md\n[Example](two.md)\n```\n');
    await writeFile(join(root, 'docs/manual/two.md'), '# Two\n');
    manifest.chapters = [{ id: 'one', title: 'One', path: 'docs/manual/one.md' }, { id: 'two', title: 'Two', path: 'docs/manual/two.md' }];
    await save(); const valid = await loadManual(root);
    assert.match(valid.chapters[0].text, /\[Two\]\(plowshare-manual:two\)/);
    assert.match(valid.chapters[0].text, /```md\n\[Example\]\(two.md\)\n```/);
    manifest.chapters[1].id = 'one'; await save(); await assert.rejects(loadManual(root), /duplicate/);
  } finally { await rm(root, { recursive: true, force: true }); await rm(outside, { recursive: true, force: true }); }
});

test('personal installation never shares; a later shared installation verifies the audience without reupload', async () => {
  const server = catalogue(), opts = installation(server);
  await publishManual(plan('# First\n'), server.client, opts);
  assert.equal(server.calls.some(row => row.operation === 'share'), false);
  assert.equal(Object.values(opts.snapshots[0].operations)[0].state, 'pending');
  await publishManual(plan('# First\n'), server.client, { ...opts, share: true });
  assert.equal(server.rows.size, 1); assert.equal(server.calls.filter(row => row.operation === 'share').length, 1);
  assert.ok(server.calls.some(row => row.operation === 'read' && row.scope === 'shared'));
});

test('replacement becomes shared/readable before old discovery is excluded; old evidence keeps its text', async () => {
  const server = catalogue(), opts = installation(server, { share: true });
  await publishManual(plan('# Before\n'), server.client, opts);
  const old = opts.journal.chapters.intro.revision;
  await publishManual(plan('# After\n'), server.client, opts);
  assert.equal(server.rows.size, 2); assert.equal(server.rows.get(old).excluded, true);
  assert.equal(server.rows.get(old).text, '# Before\n');
  const index = server.calls.findIndex(row => row.operation === 'exclude');
  assert.equal(server.calls[index - 1].scope, 'shared');
  const count = server.calls.length;
  await publishManual(plan('# After\n'), server.client, opts);
  assert.ok(server.calls.slice(count).every(row => ['status', 'read'].includes(row.operation)));
});

test('failed derivation does not share a replacement or exclude the working old edition', async () => {
  const server = catalogue(), opts = installation(server, { share: true });
  await publishManual(plan('# Before\n'), server.client, opts);
  const old = opts.journal.chapters.intro.revision; server.stage('failed');
  await assert.rejects(publishManual(plan('# After\n'), server.client, opts), /could not become readable/);
  assert.equal(server.rows.get(old).excluded, false);
  assert.equal([...server.rows.values()].filter(row => row.shared).length, 1);
});

test('lost admission never replays automatically; explicit resume uses the saved UUID', async () => {
  const server = catalogue(), opts = installation(server); server.drop(true);
  await assert.rejects(publishManual(plan('# Hello\n'), server.client, opts), /reply lost/);
  const request = server.calls[0].payload.requestId;
  await assert.rejects(publishManual(plan('# Hello\n'), server.client, opts), /uncertain delivery/);
  assert.equal(server.calls.length, 1);
  await assert.rejects(publishManual(plan('# Changed\n'), server.client, { ...opts, resume: true }), /same manual contents/);
  server.drop(false); await publishManual(plan('# Hello\n'), server.client, { ...opts, resume: true });
  assert.equal(server.rows.size, 1); assert.equal(server.calls[1].payload.requestId, request);
});

test('failed journal persistence prevents any unrecorded mutation', async () => {
  const server = catalogue(), opts = installation(server, { persist: async () => { throw new Error('disk full'); } });
  await assert.rejects(publishManual(plan('# Hello\n'), server.client, opts), /disk full/);
  assert.equal(server.calls.length, 0);
});

test('uncertain sharing cannot be skipped by resuming without the share flag', async () => {
  const server = catalogue(), opts = installation(server, { share: true });
  const client = { call: async (operation, payload) => {
    const result = await server.client.call(operation, payload);
    if (operation === 'share') throw new Error('sharing reply lost');
    return result;
  } };
  await assert.rejects(publishManual(plan('# Hello\n'), client, opts), /sharing reply lost/);
  const count = server.calls.length;
  await assert.rejects(publishManual(plan('# Hello\n'), server.client,
    { ...opts, share: false, resume: true }), /uncertain delivery/);
  assert.equal(server.calls.length, count);
  await publishManual(plan('# Hello\n'), server.client, { ...opts, resume: true });
  assert.equal(server.rows.size, 1);
});

test('readiness timeout reuses the confirmed revision on an ordinary rerun', async () => {
  const server = catalogue(); let clock = 0;
  const opts = installation(server, { readinessMs: 10, now: () => clock, sleep: async ms => { clock += ms; } });
  server.stage('pending'); await assert.rejects(publishManual(plan('# Hello\n'), server.client, opts), /still processing/);
  server.stage('ready'); await publishManual(plan('# Hello\n'), server.client, opts);
  assert.equal(server.calls.filter(row => row.operation === 'upload').length, 1);
});

test('completed receipts cannot restore an excluded chapter', async () => {
  const server = catalogue(), opts = installation(server);
  await publishManual(plan('# Hello\n'), server.client, opts);
  server.rows.get(opts.journal.chapters.intro.revision).excluded = true;
  await assert.rejects(publishManual(plan('# Hello\n'), server.client, opts), /unavailable\/excluded/);
});

test('options reject credentials in URLs, relative journals and unbounded waits', () => {
  assert.throws(() => options(['--url', 'http://alice:secret@server.invalid']), /without credentials/);
  assert.throws(() => options(['--state', 'tracked-state.json']), /absolute private/);
  assert.throws(() => options(['--readiness-ms', 'Infinity']), /milliseconds/);
  assert.throws(() => options(['--unknown']), /Unknown option/);
});

function execute(args, env = {}) {
  const child = spawn(process.execPath, [new URL('./install-manual.mjs', import.meta.url).pathname, ...args],
    { cwd: ROOT, env: { ...process.env, PLOWSHARE_HANDLE: '', PLOWSHARE_PASSWORD: '', ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '', stderr = '';
  child.stdout.on('data', bytes => { stdout += bytes; }); child.stderr.on('data', bytes => { stderr += bytes; });
  return new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve({ code, stdout, stderr })); });
}

test('real Node SDK publishes all chapters over WS and resumes a dropped admission without duplication', async () => {
  const { WebSocketServer } = createRequire(new URL('../plowshare-cli/package.json', import.meta.url))('ws');
  const server = catalogue(), paths = [], frames = []; let dropped = false;
  const http = createServer(async (req, res) => {
    paths.push(req.url);
    if (req.url === '/v1/auth/login') {
      const chunks = []; for await (const bytes of req) chunks.push(bytes);
      assert.deepEqual(JSON.parse(Buffer.concat(chunks)), { handle: 'alice', password: 'fixture-password' });
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ access: 'access', refresh: 'refresh', mustChangePassword: false }));
    } else if (req.url === '/v1/auth/refresh') res.writeHead(204, { 'Set-Cookie': ['ps_access=renewed; Path=/', 'ps_refresh=refresh; Path=/'] }).end();
    else if (req.url === '/v1/auth/ticket') res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ ticket: 'ticket' }));
    else res.writeHead(404).end();
  });
  const sockets = new WebSocketServer({ server: http });
  sockets.on('connection', (physical, req) => {
    const socket = fixtureSocket(physical);
    assert.equal(new URL(req.url, 'http://fixture').pathname, '/v1/events');
    socket.on('message', async bytes => {
      const frame = JSON.parse(bytes); frames.push(frame); assert.equal(frame.protocol_version, 'plowshare-v1');
      assert.match(frame.type, /^information\./);
      const operation = frame.type.slice('information.'.length);
      const payload = await server.call(operation, frame.payload, frame.payload.scope.kind);
      if (operation === 'upload' && !dropped) { dropped = true; socket.close(); return; }
      socket.send(JSON.stringify({ id: frame.id, type: frame.type, protocol_version: 'plowshare-v1',
        payload: { code: operation === 'upload' ? 'ACCEPTED' : 'OK', payload } }));
    });
  });
  await new Promise(resolve => http.listen(0, '127.0.0.1', resolve));
  const root = await mkdtemp(join(tmpdir(), 'manual-socket-'));
  const state = join(root, 'journal.json'), url = `http://127.0.0.1:${http.address().port}`;
  const env = { PLOWSHARE_HANDLE: 'alice', PLOWSHARE_PASSWORD: 'fixture-password' };
  const args = ['--url', url, '--share', '--state', state, '--timeout-ms', '5000'];
  try {
    const failed = await execute(args, env); assert.equal(failed.code, 1); assert.equal(server.rows.size, 1);
    const denied = await execute(args, env); assert.equal(denied.code, 1); assert.match(denied.stderr, /uncertain delivery/);
    const done = await execute([...args, '--resume'], env);
    assert.equal(done.code, 0, done.stderr); assert.match(done.stdout, /Installed 37 chapters/);
    assert.equal(server.rows.size, 37);
    assert.ok([...server.rows.values()].every(row => row.shared && row.tags.includes('plowshare-manual') && row.tags.includes(`manual-chapter-${row.name.split('/ ')[1].slice(0, -3)}`)));
    assert.ok(frames.some(frame => frame.type === 'information.read' && frame.payload.scope.kind === 'shared'));
    assert.ok(paths.every(path => ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(path)));
    const journal = await readFile(state, 'utf8');
    assert.ok(!journal.includes('fixture-password') && !journal.includes('refresh') && !journal.includes('access'));
    assert.ok(!done.stdout.includes('fixture-password') && !done.stderr.includes('fixture-password'));
  } finally {
    for (const socket of sockets.clients) socket.terminate();
    await new Promise(resolve => sockets.close(resolve)); http.closeAllConnections();
    await new Promise(resolve => http.close(resolve)); await rm(root, { recursive: true, force: true });
  }
});

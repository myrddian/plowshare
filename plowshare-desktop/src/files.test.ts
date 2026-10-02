import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, mkdir, symlink, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FilePresence } from './files.ts';
import type { FilePresenceState } from './files-shared.ts';
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection';
import type { FileRequest, FileReply } from 'plowshare-client-ts/binding/files';
import { enforcing } from 'plowshare-client-node/enforcer';

class Channel implements Socket {
  closed = false;
  listeners = new Map<string, (event: any) => void>();
  replies = new Map<string, (reply: FileReply) => void>();
  addEventListener(type: string, listener: (event: any) => void) { this.listeners.set(type, listener); }
  send(value: string) { const reply = JSON.parse(value); this.replies.get(reply.id)?.(reply); }
  close() { this.closed = true; queueMicrotask(() => this.listeners.get('close')?.({ code: 1000 })); }
  lose() { this.closed = true; this.listeners.get('close')?.({ code: 1012, reason: 'Server restarting' }); }
  request(request: FileRequest): Promise<FileReply> {
    return new Promise(resolve => { this.replies.set(request.id, resolve); this.listeners.get('message')?.({ data: JSON.stringify(request) } satisfies Arrival); });
  }
}
async function temporary(run: (root: string) => Promise<void>) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'plowshare-files-test-')));
  try { await run(root); } finally { await rm(root, { recursive: true, force: true }); }
}

test('presence serves real files, fences paths and withdraws without losing the marker', async () => temporary(async directory => {
  const root = join(directory, 'project'); await mkdir(root);
  await writeFile(join(root, 'notes.md'), 'Original notes\n');
  await writeFile(join(directory, 'outside.txt'), 'private');
  await symlink(join(directory, 'outside.txt'), join(root, 'escape.txt'));
  let state: FilePresenceState = { status: 'off' };
  let channel!: Channel;
  const presence = new FilePresence(async claim => {
    channel = new Channel(); setTimeout(() => channel.listeners.get('message')?.({ data: JSON.stringify({ ready: true, project: claim.project }) }), 1); return channel;
  }, value => { state = value; });
  try {
    assert.equal(await presence.choose(root, 'Research'), 'Research');
    assert.equal(state.status, 'ready');
    assert.equal(await readFile(join(root, '.plowshare/project'), 'utf8'), 'Research\n');
    assert.deepEqual((await channel.request({ id: 'read', op: 'read', path: 'notes.md' })).span?.lines, ['Original notes']);
    assert.equal((await channel.request({ id: 'edit', op: 'edit', path: 'notes.md', replacing: 'Original', content: 'Updated' })).result?.kind, 'edited');
    assert.equal(await readFile(join(root, 'notes.md'), 'utf8'), 'Updated notes\n');
    for (const path of ['../outside.txt', 'escape.txt', '.plowshare/project']) assert.equal((await channel.request({ id: path, op: 'read', path })).outcome, 'refused');
    await assert.rejects(presence.choose(root, 'Wrong project'), /belongs to Research/);
    assert.equal(state.status, 'ready');
    channel.lose(); assert.equal(state.status, 'lost');
    await presence.choose(root, 'Research'); assert.equal(state.status, 'ready');
    await presence.withdraw(); assert.equal(state.status, 'off'); assert.equal(channel.closed, true);
  } finally { await presence.close(); }
}));

test('refused claims create no marker and an empty ready frame is never displayed as connected', async () => temporary(async root => {
  for (const empty of [false, true]) {
    let state: FilePresenceState = { status: 'off' };
    const presence = new FilePresence(async () => {
      const channel = new Channel();
      setTimeout(() => empty ? channel.listeners.get('message')?.({ data: '{"ready":true}' }) : channel.listeners.get('close')?.({ code: 1003, reason: 'Already rooted by another session' }), 1);
      return channel;
    }, value => { state = value; });
    try {
      await assert.rejects(presence.choose(root, 'Research'), /rooted|confirm/);
      assert.equal(state.status, 'off');
      await assert.rejects(readFile(join(root, '.plowshare/project')), { code: 'ENOENT' });
    } finally { await presence.close(); }
  }
}));

test('disconnect during file authentication closes the arriving socket and creates no marker', async () => temporary(async root => {
  let deliver!: (socket: Socket) => void;
  let opening!: () => void;
  const started = new Promise<void>(resolve => { opening = resolve; });
  const presence = new FilePresence(() => { opening(); return new Promise(resolve => { deliver = resolve; }); }, () => {});
  const selected = presence.choose(root, 'Research');
  await started;
  const closing = presence.close();
  const socket = new Channel(); deliver(socket);
  await assert.rejects(selected, /connection changed/); await closing;
  assert.equal(socket.closed, true);
  await assert.rejects(readFile(join(root, '.plowshare/project')), { code: 'ENOENT' });
}));

test('a refused folder move restores the accepted root and leaves the new folder unmarked', async () => temporary(async directory => {
  const first = join(directory, 'First'); const blocked = join(directory, 'Blocked');
  await mkdir(first); await mkdir(blocked);
  let state: FilePresenceState = { status: 'off' };
  const claims: string[] = [];
  const presence = new FilePresence(async claim => {
    claims.push(claim.project);
    const channel = new Channel();
    setTimeout(() => claim.project === 'Blocked' ? channel.listeners.get('close')?.({ code: 1003, reason: 'Already rooted elsewhere' }) : channel.listeners.get('message')?.({ data: JSON.stringify({ ready: true, project: claim.project }) }), 1);
    return channel;
  }, value => { state = value; });
  try {
    await presence.choose(first, 'First');
    await assert.rejects(presence.choose(blocked, 'Blocked'), /Already rooted/);
    assert.deepEqual(claims, ['First', 'Blocked', 'First']);
    assert.equal(state.status, 'ready'); assert.equal(state.root, first);
    await assert.rejects(readFile(join(blocked, '.plowshare/project')), { code: 'ENOENT' });
  } finally { await presence.close(); }
}));

test('a symlinked marker cannot read or write a project outside the chosen folder', async () => temporary(async directory => {
  const root = join(directory, 'Project'); const elsewhere = join(directory, 'elsewhere');
  await mkdir(root); await mkdir(elsewhere);
  await writeFile(join(elsewhere, 'project'), 'Private\n');
  await symlink(elsewhere, join(root, '.plowshare'));
  const presence = new FilePresence(async () => { throw new Error('Must not open'); }, () => {});
  try {
    await assert.rejects(presence.choose(root), /regular file and directory/);
    assert.equal(await readFile(join(elsewhere, 'project'), 'utf8'), 'Private\n');
  } finally { await presence.close(); }
}));

test('failed restoration closes the unconfirmed channel and reports the previous root as lost', async () => temporary(async directory => {
  const first = join(directory, 'First'); const blocked = join(directory, 'Blocked');
  await mkdir(first); await mkdir(blocked);
  let state: FilePresenceState = { status: 'off' };
  const channels: Channel[] = [];
  const presence = new FilePresence(async claim => {
    const channel = new Channel(); channels.push(channel);
    const attempt = channels.length;
    setTimeout(() => {
      if (attempt === 2) channel.listeners.get('close')?.({ code: 1003, reason: 'Already rooted elsewhere' });
      else channel.listeners.get('message')?.({ data: JSON.stringify({ ready: true, ...(attempt === 1 ? { project: claim.project } : {}) }) });
    }, 1);
    return channel;
  }, value => { state = value; });
  try {
    await presence.choose(first, 'First');
    await assert.rejects(presence.choose(blocked, 'Blocked'), /Already rooted/);
    assert.equal(state.status, 'lost'); assert.equal(state.root, first);
    assert.equal(channels[2]?.closed, true);
  } finally { await presence.close(); }
}));

test('withdrawal waits for a command that ignores SIGTERM to be killed', async () => temporary(async root => {
  let channel!: Channel;
  const presence = new FilePresence(async claim => {
    channel = new Channel();
    setTimeout(() => channel.listeners.get('message')?.({ data: JSON.stringify({ ready: true, project: claim.project }) }), 1);
    return channel;
  }, () => {});
  try {
    await presence.choose(root, 'Research');
    void channel.request({ id: 'stubborn', op: 'run', path: root, argv: [process.execPath, '-e', 'process.on("SIGTERM",()=>{});require("fs").writeFileSync("pid",String(process.pid));setInterval(()=>{},1000)'], timeoutMillis: 15000 });
    let pid = 0;
    for (let attempt = 0; ; attempt++) {
      try { pid = Number(await readFile(join(root, 'pid'), 'utf8')); break; }
      catch { assert.ok(attempt < 200, 'Command failed to start'); await new Promise(resolve => setTimeout(resolve, 10)); }
    }
    await presence.withdraw();
    assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
  } finally { await presence.close(); }
}));

test('withdrawing presence aborts local commands and refuses subsequent requests', async () => temporary(async root => {
  const lifetime = new AbortController();
  const answer = enforcing(root, lifetime.signal);
  const command = answer({ id: 'command', op: 'run', path: root, argv: [process.execPath, '-e', 'require("fs").writeFileSync("started", "yes");setInterval(()=>{},1000)'], timeoutMillis: 10000 });
  // Wait for a real child process before withdrawing its presence.
  for (let attempt = 0; ; attempt++) {
    try { await readFile(join(root, 'started')); break; }
    catch { assert.ok(attempt < 200, 'Command failed to start'); await new Promise(resolve => setTimeout(resolve, 10)); }
  }
  lifetime.abort();
  const outcome = await command;
  assert.equal(outcome.outcome, 'ok'); assert.equal(outcome.exitCode, null);
  assert.equal((await answer({ id: 'after', op: 'roots' })).outcome, 'refused');
}));

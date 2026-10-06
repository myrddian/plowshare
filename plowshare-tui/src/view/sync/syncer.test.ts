import { execFileSync } from 'node:child_process';
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Outcome as Answer } from 'plowshare-client-ts/binding/envelope';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { syncer, noticingWrites, type SyncerOptions } from './syncer.ts';
import { enforcing } from '../files/enforcer.ts';

let top = '';
let hub = '';
let root = '';

class FakeServer {
  enabled = false;
  state = 'OFFLINE';
  /** The first `union.ready` answers stale, as a hub that moved under it would. */
  staleOnce = false;
  readonly sent: string[] = [];
  readonly conflicts: {
    n: number;
    path: string;
    theirsAuthor: string;
    theirsBlob?: string;
  }[] = [];

  async ask(type: string, payload?: unknown): Promise<Answer> {
    this.sent.push(type);
    const body = payload as Record<string, unknown>;
    switch (type) {
      case 'union.status':
        return {
          code: 'OK',
          payload: {
            eligible: true,
            enabled: this.enabled,
            state: this.state,
            syncHidden: [],
            maxFileBytes: 1_000_000,
            openConflicts: this.conflicts.length,
            url: '/v1/sync/ledger.git',
          },
        };
      case 'union.enable':
      case 'union.begin':
        this.state = 'SYNCING';
        return { code: 'OK', payload: { url: '/v1/sync/ledger.git' } };
      case 'union.ready': {
        if (this.staleOnce) {
          this.staleOnce = false;
          return { code: 'CONFLICT', said: 'stale' };
        }
        const main = execFileSync('git', [
          '--git-dir',
          hub,
          'rev-parse',
          'main',
        ])
          .toString()
          .trim();
        if (main !== body['commit']) {
          return { code: 'CONFLICT', said: 'stale' };
        }
        this.enabled = true;
        this.state = 'LIVE';
        return { code: 'OK', payload: {} };
      }
      case 'union.conflict.open': {
        const n = this.conflicts.length + 1;
        this.conflicts.push({
          n,
          path: String(body['path']),
          theirsAuthor: String(body['theirsAuthor']),
          theirsBlob: body['theirsBlob'] as string,
        });
        return { code: 'OK', payload: { n } };
      }
      case 'union.abort':
        if (this.state === 'SYNCING') {
          this.state = 'OFFLINE';
        }
        return { code: 'OK', payload: {} };
      case 'union.hidden':
        return {
          code: 'OK',
          payload: { syncHidden: body['paths'], replaced: true },
        };
      case 'union.conflict.list':
        return {
          code: 'OK',
          payload: {
            conflicts: this.conflicts.map((row) => ({
              baseBlob: null,
              oursBlob: null,
              theirsBlob: null,
              runId: null,
              openedAt: '2026-10-01T00:00:00Z',
              ...row,
            })),
          },
        };
      case 'union.conflict.resolve':
        this.conflicts.splice(
          this.conflicts.findIndex((c) => c.n === body['n']),
          1,
        );
        return { code: 'OK', payload: {} };
      default:
        return { code: 'BAD_REQUEST', said: `unexpected ${type}` };
    }
  }
}

function make(
  server: FakeServer,
  version: readonly [number, number] = [2, 50],
  extra: Partial<SyncerOptions> = {},
) {
  const told: string[] = [];
  const notices: (string | undefined)[] = [];
  const sync = syncer({
    claim: { project: 'ledger', machine: 'laptop', root },
    handle: 'enzo',
    base: 'http://unused',
    asker: checkedTransport(server),
    bearer: async () => 'token',
    tell: (_trouble, lines) => {
      told.push(...lines);
    },
    standing: (notice) => {
      notices.push(notice);
    },
    every: 60_000,
    gitVersion: async () => version,
    remoteUrl: () => `file://${hub}`,
    ...extra,
  });
  return { sync, told, notices };
}

beforeEach(async () => {
  top = await realpath(await mkdtemp(join(tmpdir(), 'syncer-')));
  hub = join(top, 'hub.git');
  root = join(top, 'ledger');
  await mkdir(root);
  await writeFile(join(root, 'a.txt'), 'one\n');
  execFileSync('git', ['init', '-q', '--bare', '-b', 'main', hub]);
});
afterEach(async () => {
  await rm(top, { recursive: true, force: true });
});

describe('syncer', () => {
  it('two independent runtimes serialize reconciliation of one checkout and preserve edits', async () => {
    const server = new FakeServer();
    const first = make(server, [2, 50], { strict: true });
    const second = make(server, [2, 50], { strict: true });
    try {
      await first.sync.run({ kind: 'on' });
      await Promise.all([first.sync.connect(), second.sync.connect()]);
      expect(server.sent.filter((type) => type === 'union.ready')).toHaveLength(
        3,
      );
      await writeFile(join(root, 'a.txt'), 'shared edit\n');
      await Promise.all([first.sync.connect(), second.sync.connect()]);
      expect(
        execFileSync('git', [
          '--git-dir',
          hub,
          'show',
          'main:a.txt',
        ]).toString(),
      ).toBe('shared edit\n');
      expect(await readFile(join(root, 'a.txt'), 'utf8')).toBe('shared edit\n');
      await expect(
        readFile(join(root, '.plowshare', 'sync.lock')),
      ).rejects.toMatchObject({ code: 'ENOENT' });
    } finally {
      first.sync.stop();
      second.sync.stop();
    }
  });

  it('a stopped client waiting for another checkout writer never sends a queued operation', async () => {
    const server = new FakeServer();
    const lifetime = new AbortController();
    const { sync } = make(server, [2, 50], {
      signal: lifetime.signal,
      strict: true,
    });
    const lock = join(root, '.plowshare', 'sync.lock');
    await mkdir(lock, { recursive: true });
    const pending = sync.run({ kind: 'on' });
    lifetime.abort();
    sync.stop();
    await expect(pending).rejects.toThrow();
    expect(server.sent).toHaveLength(0);
    // Cancellation does not remove the other writer's lock.
    await expect(mkdir(lock)).rejects.toMatchObject({ code: 'EEXIST' });
  });

  it('/sync on pushes a snapshot and goes live', async () => {
    const server = new FakeServer();
    const { sync } = make(server);
    await sync.run({ kind: 'on' });
    expect(server.sent).toContain('union.enable');
    expect(server.state).toBe('LIVE');
    expect(
      execFileSync('git', ['--git-dir', hub, 'show', 'main:a.txt']).toString(),
    ).toBe('one\n');
    await sync.leave();
  });

  it('an old git never begins, so the mirror stays authoritative', async () => {
    const server = new FakeServer();
    server.enabled = true;
    const { sync, notices } = make(server, [2, 42]);
    await sync.connect();
    expect(server.sent).not.toContain('union.begin');
    expect(notices.at(-1)).toBe('sync unavailable: git 2.50+ required');
  });

  it('a failed connect retries on the next tick', async () => {
    const server = new FakeServer();
    const first = make(server);
    await first.sync.run({ kind: 'on' });
    await first.sync.leave();
    server.state = 'OFFLINE';
    server.staleOnce = true;

    const second = make(server);
    await second.sync.connect();
    expect(second.notices.at(-1)).toBe('sync failed — retrying');
    expect(server.state).not.toBe('LIVE');
    const begun = server.sent.lastIndexOf('union.begin');
    expect(server.sent.indexOf('union.abort', begun)).toBeGreaterThan(begun);
    expect(server.state).toBe('OFFLINE');

    await second.sync.tick();
    expect(server.state).toBe('LIVE');
    expect(second.notices.at(-1)).toBeUndefined();
    await second.sync.leave();
  });

  it('/sync hidden says the list replaced the previous one', async () => {
    const server = new FakeServer();
    const { sync, told } = make(server);
    await sync.run({ kind: 'hidden', paths: ['.github/'] });
    expect(told).toContain(
      'hidden paths synced (replaces the previous list): .github/',
    );
  });

  it('a conflict on connect is recorded, shown, and resolved as theirs', async () => {
    const server = new FakeServer();
    const first = make(server);
    await first.sync.run({ kind: 'on' });
    await first.sync.leave();

    const work = join(top, 'bot');
    execFileSync('git', ['clone', '-q', hub, work]);
    execFileSync('git', [
      '-C',
      work,
      'checkout',
      '-q',
      '-B',
      'main',
      'origin/main',
    ]);
    await writeFile(join(work, 'a.txt'), 'bot\n');
    execFileSync('git', [
      '-C',
      work,
      '-c',
      'user.name=nightly-bot',
      '-c',
      'user.email=b@b',
      'commit',
      '-qam',
      'run run_1',
    ]);
    execFileSync('git', ['-C', work, 'push', '-q', 'origin', 'main']);
    server.state = 'OFFLINE';
    await writeFile(join(root, 'a.txt'), 'mine\n');

    const second = make(server);
    await second.sync.connect();
    expect(server.conflicts.map((c) => c.path)).toEqual(['a.txt']);
    expect(second.notices.at(-1)).toBe('⚠ 1 sync conflict');
    expect(await readFile(join(root, 'a.txt'), 'utf8')).toBe('mine\n');

    await second.sync.run({ kind: 'resolve', path: 'a.txt', how: 'theirs' });
    expect(await readFile(join(root, 'a.txt'), 'utf8')).toBe('bot\n');
    expect(server.conflicts).toEqual([]);
    expect(second.notices.at(-1)).toBeUndefined();
    await second.sync.leave();
  });
});

describe('noticingWrites', () => {
  it('withdrawal still stops an actual command allowed by the explicit local policy', async () => {
    await mkdir(join(root, '.plowshare'), { recursive: true });
    await writeFile(
      join(root, '.plowshare', 'environment.yml'),
      'local:\n  mode: open\n  timeout: 30s\n',
    );
    const marker = join(root, 'started');
    const answer = noticingWrites(enforcing(root, false), () => undefined);
    const pending = answer({
      id: 'running',
      op: 'run',
      path: root,
      argv: [
        process.execPath,
        '-e',
        `require('node:fs').writeFileSync(${JSON.stringify(marker)}, 'started'); setTimeout(() => {}, 30000)`,
      ],
    });
    try {
      let started = false;
      for (let tries = 0; tries < 200 && !started; tries++) {
        started = await readFile(marker).then(
          () => true,
          () => false,
        );
        if (!started) await new Promise((done) => setTimeout(done, 10));
      }
      expect(started).toBe(true);
    } finally {
      answer.close?.();
    }
    const reply = await pending;
    expect(reply.outcome).toBe('ok');
    expect(reply.exitCode).toBeNull();
    expect(reply.timedOut).toBe(false);
  });
  it('is told only about writes that succeeded', async () => {
    let wrote = 0;
    const answer = noticingWrites(
      async (request) => ({
        id: request.id,
        outcome: request.path === 'bad' ? 'refused' : 'ok',
      }),
      () => {
        wrote++;
      },
    );
    await answer({ id: '1', op: 'read', path: 'a' });
    await answer({ id: '2', op: 'write', path: 'a', content: 'x' });
    await answer({ id: '3', op: 'write', path: 'bad', content: 'x' });
    expect(wrote).toBe(1);
  });

  it('is told about edits, deletes and moves too, and not about their refusals', async () => {
    let wrote = 0;
    const answer = noticingWrites(
      async (request) => ({
        id: request.id,
        outcome: request.path === 'bad' ? 'refused' : 'ok',
      }),
      () => {
        wrote++;
      },
    );
    await answer({
      id: '1',
      op: 'edit',
      path: 'a',
      replacing: 'x',
      content: 'y',
    });
    await answer({ id: '2', op: 'delete', path: 'a' });
    await answer({ id: '3', op: 'move', path: 'a', to: 'b' });
    await answer({ id: '4', op: 'stat', path: 'a' });
    await answer({ id: '5', op: 'glob', pattern: '*' });
    await answer({ id: '6', op: 'grep', needle: 'x' });
    await answer({
      id: '7',
      op: 'edit',
      path: 'bad',
      replacing: 'x',
      content: 'y',
    });
    await answer({ id: '8', op: 'delete', path: 'bad' });
    await answer({ id: '9', op: 'move', path: 'bad', to: 'b' });
    expect(wrote).toBe(3);
  });
});

describe('headless sync failure and stop boundaries', () => {
  it('aborts a begun enable when ready is refused and strict mode throws', async () => {
    const server = new FakeServer();
    server.staleOnce = true;
    const { sync } = make(server, [2, 50], { strict: true });
    await expect(sync.run({ kind: 'on' })).rejects.toThrow('stale');
    expect(server.sent).toContain('union.abort');
    expect(server.enabled).toBe(false);
    sync.stop();
    const count = server.sent.length;
    sync.changed();
    await sync.tick();
    expect(server.sent).toHaveLength(count);
    await expect(sync.run({ kind: 'on' })).rejects.toThrow('stopped');
  });

  it('strict mode rejects malformed status instead of calling it an unsynced project', async () => {
    const server = new FakeServer();
    server.ask = async () => ({ code: 'OK', payload: {} });
    const { sync } = make(server, [2, 50], { strict: true });
    await expect(sync.run({ kind: 'status' })).rejects.toThrow(
      'Unreadable operation reply',
    );
    sync.stop();
  });
});

import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { decodeRequest } from 'plowshare-client-ts/operations/schema';
import type { Payloads } from 'plowshare-client-ts/operations/direct';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  mkdtemp,
  mkdir,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { syncer, type Syncer } from 'plowshare-client-node/sync/syncer';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import { DesktopSync } from './sync.ts';
import { previewIdentity, syncIdentity } from './sync-shared.ts';

const git = (...args: string[]) => execFileSync('git', args).toString().trim();
class HubFixture {
  enabled = false;
  state = 'OFFLINE';
  conflicts: ({ n: number } & Omit<
    Payloads['union.conflict.open'],
    'project'
  >)[] = [];
  sent: string[] = [];
  malformed = false;
  refuseResolve = false;
  readonly hub: string;
  constructor(hub: string) {
    this.hub = hub;
  }
  ask = async (type: string, value: unknown): Promise<Outcome> => {
    this.sent.push(type);
    const asked = decodeRequest(type, value);
    switch (asked.type) {
      case 'union.status':
        return {
          code: 'OK',
          payload: this.malformed
            ? { eligible: true, enabled: this.enabled }
            : {
                eligible: true,
                enabled: this.enabled,
                state: this.state,
                syncHidden: [],
                maxFileBytes: 5_242_880,
                openConflicts: this.conflicts.length,
                url: '/v1/sync/Research.git',
              },
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
              openedAt: '2026-10-02T00:00:00Z',
              ...row,
            })),
          },
        };
      case 'union.enable':
      case 'union.begin':
        this.state = 'SYNCING';
        return { code: 'OK', payload: { url: '/v1/sync/Research.git' } };
      case 'union.ready':
        assert.equal(
          asked.payload.commit,
          git('--git-dir', this.hub, 'rev-parse', 'main'),
        );
        this.enabled = true;
        this.state = 'LIVE';
        return { code: 'OK', payload: {} };
      case 'union.abort':
        this.state = 'OFFLINE';
        return { code: 'OK', payload: {} };
      case 'union.disable':
        this.enabled = false;
        this.state = 'OFFLINE';
        return { code: 'OK', payload: {} };
      case 'union.conflict.open': {
        const n = this.conflicts.length + 1;
        this.conflicts.push({ n, ...asked.payload });
        return { code: 'OK', payload: { n } };
      }
      case 'union.conflict.resolve':
        if (this.refuseResolve) throw new Error('Reply lost after resolution');
        this.conflicts = this.conflicts.filter(
          (row) => row.n !== asked.payload.n,
        );
        return { code: 'OK', payload: {} };
      default:
        throw new Error(type);
    }
  };
}
async function fixture(
  work: (f: {
    top: string;
    root: string;
    hub: HubFixture;
    desktop: DesktopSync;
    runtime: () => Syncer;
  }) => Promise<void>,
) {
  const top = await realpath(
    await mkdtemp(join(tmpdir(), 'plowshare-desktop-sync-')),
  );
  const root = join(top, 'root'),
    hub = new HubFixture(join(top, 'hub.git'));
  await mkdir(root);
  await writeFile(join(root, 'notes.txt'), 'base\n');
  git('init', '-q', '--bare', '-b', 'main', hub.hub);
  let runtime!: Syncer;
  const desktop = new DesktopSync(
    checkedTransport({ ask: hub.ask }).ask,
    () => {},
    (options) => {
      runtime = syncer({
        ...options,
        remoteUrl: () => `file://${hub.hub}`,
        gitVersion: async () => [2, 50],
      });
      return runtime;
    },
  );
  desktop.attach(
    { project: 'Research', machine: 'fixture', root },
    'http://unused',
    'fixture',
    async () => 'token',
  );
  try {
    await desktop.refresh();
    await work({ top, root, hub, desktop, runtime: () => runtime });
  } finally {
    await desktop.close();
    await rm(top, { recursive: true, force: true });
  }
}
async function enable(f: { desktop: DesktopSync }) {
  await f.desktop.run('on', syncIdentity(f.desktop.value!));
}
async function remote(f: { top: string; hub: HubFixture }, text: string) {
  const bot = join(f.top, 'bot');
  git('clone', '-q', f.hub.hub, bot);
  await writeFile(join(bot, 'notes.txt'), text);
  git(
    '-C',
    bot,
    '-c',
    'user.name=fixture-agent',
    '-c',
    'user.email=fixture@local',
    'commit',
    '-qam',
    'server edit',
  );
  git('-C', bot, 'push', '-q', 'origin', 'main');
  f.hub.state = 'OFFLINE';
}
async function conflict(
  f: Parameters<typeof remote>[0] & { root: string; desktop: DesktopSync },
) {
  await enable(f);
  await remote(f, 'server\n');
  await writeFile(join(f.root, 'notes.txt'), 'mine\n');
  await f.desktop.refresh();
  await f.desktop.run('now', syncIdentity(f.desktop.value!));
  assert.equal(f.hub.conflicts.length, 1);
  await f.desktop.inspect('notes.txt');
}

await test('server copy lifecycle uses the rooted adapter and idle ticks pull server edits', async () =>
  fixture(async (f) => {
    await enable(f);
    assert.equal(git('--git-dir', f.hub.hub, 'show', 'main:notes.txt'), 'base');
    await remote(f, 'server-only change\n');
    await f.runtime().tick();
    await f.desktop.refresh();
    assert.equal(
      await readFile(join(f.root, 'notes.txt'), 'utf8'),
      'server-only change\n',
    );
    await f.desktop.run('off', syncIdentity(f.desktop.value!));
    assert.equal(f.desktop.value?.status?.enabled, false);
    assert.equal(
      await readFile(join(f.root, 'notes.txt'), 'utf8'),
      'server-only change\n',
    );
  }));
for (const how of ['mine', 'theirs', 'done'] as const)
  await test(`review and resolve as ${how} keeps exact file bytes`, async () =>
    fixture(async (f) => {
      await conflict(f);
      const preview = f.desktop.value!.preview!;
      assert.equal(preview.mine.text, 'mine\n');
      assert.equal(preview.server.text, 'server\n');
      assert.equal(preview.base.text, 'base\n');
      assert.match(preview.merged!, /<<<<<<< mine/);
      await f.desktop.resolve(
        how,
        previewIdentity(preview),
        how === 'done' ? 'reviewed merge\n' : undefined,
      );
      assert.equal(
        await readFile(join(f.root, 'notes.txt'), 'utf8'),
        how === 'mine'
          ? 'mine\n'
          : how === 'theirs'
            ? 'server\n'
            : 'reviewed merge\n',
      );
      assert.equal(f.hub.conflicts.length, 0);
      assert.equal(f.desktop.value!.preview, undefined);
    }));
await test('a changed local file and an unresolved merge cannot be applied as reviewed', async () =>
  fixture(async (f) => {
    await conflict(f);
    const preview = f.desktop.value!.preview!;
    await assert.rejects(
      f.desktop.resolve('done', previewIdentity(preview), preview.merged),
      /merge markers/,
    );
    assert.equal(await readFile(join(f.root, 'notes.txt'), 'utf8'), 'mine\n');
    await writeFile(join(f.root, 'notes.txt'), 'new local edit\n');
    await assert.rejects(
      f.desktop.resolve('theirs', previewIdentity(preview)),
      /file changed/,
    );
    assert.equal(
      await readFile(join(f.root, 'notes.txt'), 'utf8'),
      'new local edit\n',
    );
    assert.equal(f.hub.sent.includes('union.conflict.resolve'), false);
  }));
await test('a changed conflict is rejected before a resolution or file overwrite', async () =>
  fixture(async (f) => {
    await conflict(f);
    const identity = previewIdentity(f.desktop.value!.preview!);
    const changed = f.hub.conflicts[0];
    if (!changed) throw new Error('Missing conflict fixture.');
    changed.n++;
    await assert.rejects(
      f.desktop.resolve('theirs', identity),
      /conflict changed/,
    );
    assert.equal(await readFile(join(f.root, 'notes.txt'), 'utf8'), 'mine\n');
    assert.equal(f.hub.sent.includes('union.conflict.resolve'), false);
  }));
await test('incomplete reads preserve status and lost resolution replies are never replayed', async () =>
  fixture(async (f) => {
    await conflict(f);
    const status = f.desktop.value!.status;
    f.hub.malformed = true;
    await assert.rejects(f.desktop.refresh(), /incomplete|Unreadable/);
    assert.equal(f.desktop.value!.status, status);
    f.hub.malformed = false;
    f.hub.refuseResolve = true;
    await assert.rejects(
      f.desktop.resolve('mine', previewIdentity(f.desktop.value!.preview!)),
      /Reply lost/,
    );
    assert.equal(f.desktop.value!.uncertain, true);
    await f.desktop.refresh();
    assert.equal(
      f.hub.sent.filter((type) => type === 'union.conflict.resolve').length,
      1,
    );
    f.desktop.stop();
    const count = f.hub.sent.length;
    await f.runtime().tick();
    assert.equal(f.hub.sent.length, count);
  }));
await test('preview refuses a symlinked working file and scratch tree', async () =>
  fixture(async (f) => {
    await conflict(f);
    await rm(join(f.root, 'notes.txt'));
    await writeFile(join(f.top, 'outside.txt'), 'outside\n');
    await symlink(join(f.top, 'outside.txt'), join(f.root, 'notes.txt'));
    await assert.rejects(f.desktop.inspect('notes.txt'), /symbolic link/);
    await rm(join(f.root, 'notes.txt'));
    await writeFile(join(f.root, 'notes.txt'), 'mine\n');
    await rm(join(f.root, '.plowshare', 'conflicts'), {
      recursive: true,
      force: true,
    });
    await symlink(f.top, join(f.root, '.plowshare', 'conflicts'));
    await assert.rejects(f.desktop.inspect('notes.txt'), /symbolic link/);
    assert.equal(
      await readFile(join(f.top, 'outside.txt'), 'utf8'),
      'outside\n',
    );
  }));

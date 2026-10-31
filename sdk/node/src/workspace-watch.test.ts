import assert from 'node:assert/strict';
import { test, type TestContext } from 'node:test';
import { resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises';
import { enforcing } from 'plowshare-client-node/enforcer';
import {
  watchWorkspace,
  type WorkspaceWatchEvents,
} from './workspace-watch.ts';
import type { FileReply, FileRequest } from 'plowshare-client-ts/binding/files';

function fixture(t: TestContext) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const root = resolve(tmpdir(), 'workspace-watch-fixture');
  let event: (path: string | null) => void = () => {};
  let lost: () => void = () => {};
  let closed = false;
  let hints = 0;
  let hash = 'a'.repeat(64);
  let missing = false;
  const reads: FileRequest[] = [];
  const events: WorkspaceWatchEvents = {
    subscribe(_root, changed, unavailable) {
      event = changed;
      lost = unavailable;
      return () => {
        closed = true;
      };
    },
  };
  const reply = (id: string): FileReply =>
    missing
      ? { id, outcome: 'refused' }
      : { id, outcome: 'ok', source: { size: 10, sha256: hash } };
  const watcher = watchWorkspace(
    root,
    (request) => {
      reads.push(request);
      return Promise.resolve(reply(request.id));
    },
    () => {
      hints++;
    },
    events,
  );
  t.after(() => watcher.close());
  const path = resolve(root, 'A.java');
  watcher.observed({ id: 'baseline', op: 'source', path }, reply('baseline'));
  return {
    event: (path: string | null) => event(path),
    lost: () => lost(),
    change: () => {
      hash = 'b'.repeat(64);
    },
    remove: () => {
      missing = true;
    },
    hints: () => hints,
    closed: () => closed,
    watcher,
    reads,
    async flush() {
      t.mock.timers.tick(250);
      for (let turn = 0; turn < 10; turn++) await Promise.resolve();
    },
  };
}

await test('duplicate filesystem events hash once and unchanged bytes send no server hint', async (t) => {
  const f = fixture(t);
  for (let event = 0; event < 100; event++) f.event('A.java');
  await f.flush();
  assert.equal(f.reads.length, 1);
  assert.equal(f.hints(), 0);
  f.change();
  for (let event = 0; event < 100; event++) f.event('A.java');
  await f.flush();
  assert.equal(f.reads.length, 2);
  assert.equal(f.hints(), 1);
  f.event('A.java');
  await f.flush();
  assert.equal(f.hints(), 1);
});

await test('new files and deletions request reconciliation, closed watchers stop notifying', async (t) => {
  const f = fixture(t);
  f.event('New.java');
  await f.flush();
  assert.equal(f.hints(), 1);
  f.remove();
  f.event('A.java');
  await f.flush();
  assert.equal(f.hints(), 2);
  f.watcher.close();
  f.event('New.java');
  await f.flush();
  assert.equal(f.hints(), 2);
  assert.equal(f.closed(), true);
});

await test('overflow and missing names become one bounded reconciliation hint', async (t) => {
  const f = fixture(t);
  for (let event = 0; event < 1002; event++) f.event(`New${event}.java`);
  f.event(null);
  await f.flush();
  assert.equal(f.hints(), 1);
  assert.equal(f.reads.length, 0);
  f.lost();
  await f.flush();
  assert.equal(f.hints(), 2);
});

await test('an event cannot make the watcher read outside the workspace fence', async (t) => {
  const f = fixture(t);
  f.event('../outside.java');
  await f.flush();
  assert.equal(f.reads.length, 0);
  assert.equal(f.hints(), 1);
});

await test('a native filesystem change is hashed before it notifies the server', async (t) => {
  const directory = await mkdtemp(resolve(tmpdir(), 'plowshare-native-watch-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const root = await realpath(directory);
  const path = resolve(root, 'A.java');
  await writeFile(path, 'class Before {}');
  const answer = enforcing(root);
  t.after(() => answer.close());
  let reads = 0;
  let changed: () => void = () => {};
  const notification = new Promise<void>((done) => {
    changed = done;
  });
  const watcher = watchWorkspace(
    root,
    async (request) => {
      reads++;
      return answer(request);
    },
    changed,
  );
  t.after(() => watcher.close());
  const request = { id: 'baseline', op: 'source', path };
  watcher.observed(request, await answer(request));
  await writeFile(path, 'class After {}');
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      notification,
      new Promise<void>((_done, reject) => {
        timeout = setTimeout(
          () => reject(new Error('native watcher did not report the change')),
          5000,
        );
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
  assert.ok(reads >= 1, 'notification must follow a fenced source hash read');
});

import { connectionDirectory } from 'plowshare-client-node/connections';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { JobJournal } from './job-store.ts';
await test('job receipts survive restart, isolate server/account and preserve unresolved submissions without prompts or tokens', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-jobs-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const journal = new JobJournal(directory),
    known = {
      id: 'job-one',
      handle: 'job-one',
      conversation: 'c',
      agent: 'a',
      project: 'p',
    },
    unknown = { id: 'pending-one', conversation: 'uncertain', agent: 'a' };
  await Promise.all([
    journal.save('http://localhost:8091', 'one', [known, unknown]),
    journal.save('http://localhost:8091', 'two', []),
  ]);
  const restarted = new JobJournal(directory);
  assert.deepEqual(await restarted.load('http://localhost:8091', 'one'), [
    known,
    unknown,
  ]);
  assert.deepEqual(await restarted.load('http://localhost:8091', 'two'), []);
  assert.deepEqual(await restarted.load('http://other:8091', 'one'), []);
  assert.equal(
    (
      await stat(
        join(
          connectionDirectory('http://localhost:8091', 'one', directory),
          'desktop-jobs.json',
        ),
      )
    ).mode & 0o777,
    0o600,
  );
  assert.equal(
    /token|password|task|text/.test(
      await readFile(
        join(
          connectionDirectory('http://localhost:8091', 'one', directory),
          'desktop-jobs.json',
        ),
        'utf8',
      ),
    ),
    false,
  );
});
await test('corrupt recovery data is surfaced and not silently overwritten', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-jobs-bad-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const journal = new JobJournal(directory);
  await writeFile(
    journal.path,
    '{"version":1,"accounts":{"bad":[{"id":"job"}]}}',
  );
  await assert.rejects(journal.load('http://server', 'account'), /invalid/);
  await assert.rejects(journal.save('http://server', 'account', []), /invalid/);
});

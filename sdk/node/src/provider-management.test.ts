import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ProviderManagementIntents } from 'plowshare-client-node';
import type { ManagementIntent } from 'plowshare-client-ts/operations/provider-management';

const id = '11111111-1111-1111-1111-111111111111';
const next = '22222222-2222-2222-2222-222222222222';
const intent: ManagementIntent = {
  operation: 'provider.credential.put',
  project: 'fixture',
  connection: id,
  subject: null,
  expectedGeneration: null,
  requestId: id,
};
await test('native bookmarks atomically fence concurrent clients and isolate origin/actor including service principals', async () => {
  const root = await mkdtemp(join(tmpdir(), 'provider-intents-'));
  try {
    const first = new ProviderManagementIntents(
      'https://server.example',
      'owner',
      root,
    );
    const second = new ProviderManagementIntents(
      'https://server.example/',
      'owner',
      root,
    );
    const results = await Promise.allSettled([
      first.reserve(intent),
      second.reserve({ ...intent, requestId: next }),
    ]);
    assert.equal(
      results.filter((result) => result.status === 'fulfilled').length,
      1,
    );
    const retained = await first.list();
    assert.equal(retained.length, 1);
    assert.equal((await stat(first.file)).mode & 0o777, 0o600);
    assert.equal(
      (await stat(join(root, 'provider-management'))).mode & 0o777,
      0o700,
    );
    for (const [server, actor] of [
      ['https://other.example', 'owner'],
      ['https://server.example', 'other'],
      ['https://server.example', '@service/' + id],
    ]) {
      const partition = new ProviderManagementIntents(server!, actor!, root);
      assert.deepEqual(await partition.list(), []);
    }
    const row = retained[0];
    assert.ok(row);
    await second.remove(row.requestId);
    assert.deepEqual(await first.list(), []);
    await first.reserve(intent);
    const text = await readFile(first.file, 'utf8');
    assert.equal(text.includes('secret'), false);
    await writeFile(
      first.file,
      text.replace('"operation":', '"secret":"injected","operation":'),
    );
    await assert.rejects(first.list(), /Unexpected management bookmark fields/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

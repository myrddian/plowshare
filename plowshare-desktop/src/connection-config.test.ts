import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { ConnectionConfig, desktopConfigDirectory } from './connection-config.ts';

test('desktop details survive a new store without persisting credentials or extra fields', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-connection-'));
  try {
    const store = new ConnectionConfig(directory);
    assert.equal(await store.load(), undefined);
    await store.save({ server: 'http://localhost:8091', account: 'alice', reconnect: true, password: 'private-password', tokens: 'private-token' } as any);
    const restarted = new ConnectionConfig(directory);
    assert.deepEqual(await restarted.load(), { server: 'http://localhost:8091', account: 'alice', reconnect: true });
    assert.equal((await stat(store.path)).mode & 0o777, 0o600);
    const saved = await readFile(store.path, 'utf8');
    assert.ok(!saved.includes('private-') && !saved.includes('password') && !saved.includes('tokens'));
    await restarted.save({ server: 'http://localhost:8091', account: 'alice', reconnect: false });
    assert.equal((await store.load())?.reconnect, false);
    await writeFile(store.path, '{broken');
    await assert.rejects(store.load());
    await assert.rejects(store.save({ server: 'http://alice:secret@localhost:8091', account: 'alice', reconnect: true }));
    assert.equal(await readFile(store.path, 'utf8'), '{broken');
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('desktop bookmarks follow the shared config override unless explicitly overridden', () => {
  assert.equal(desktopConfigDirectory({ PLOWSHARE_CONFIG_DIR: '/shared' }), '/shared');
  assert.equal(desktopConfigDirectory({ PLOWSHARE_CONFIG_DIR: '/shared', PLOWSHARE_DESKTOP_CONFIG: '/desktop' }), '/desktop');
  assert.equal(desktopConfigDirectory({ XDG_CONFIG_HOME: '/xdg' }), '/xdg/plowshare');
});

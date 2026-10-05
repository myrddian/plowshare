import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { ProjectConfig } from './project-config.ts';

await test('project bookmarks persist atomically and remain scoped to server and account', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-project-config-'));
  try {
    const config = new ProjectConfig(directory);
    const mapping = {
      server: 'http://localhost:8080',
      account: 'alice',
      name: 'Research',
      path: '/projects/research',
      machine: 'laptop',
      enabled: true,
    };
    await Promise.all([
      config.put(mapping),
      config.put({ ...mapping, account: 'bob', path: '/projects/bob' }),
      config.put({
        ...mapping,
        server: 'http://localhost:8081',
        path: '/projects/other-server',
      }),
    ]);
    const restarted = new ProjectConfig(directory);
    assert.deepEqual(await restarted.list(mapping.server, 'alice'), [mapping]);
    assert.equal(
      (await restarted.list(mapping.server, 'bob'))[0]?.path,
      '/projects/bob',
    );
    assert.equal(
      (await restarted.list('http://localhost:8081', 'alice'))[0]?.path,
      '/projects/other-server',
    );
    assert.equal((await stat(config.path)).mode & 0o777, 0o600);
    await restarted.put({ ...mapping, enabled: false });
    assert.equal(
      (await restarted.list(mapping.server, 'alice'))[0]?.enabled,
      false,
    );
    await restarted.remove(mapping.server, 'alice', mapping.name);
    assert.deepEqual(await restarted.list(mapping.server, 'alice'), []);
    assert.equal((await restarted.list(mapping.server, 'bob')).length, 1);
    assert.equal(
      (await readFile(config.path, 'utf8')).includes('password'),
      false,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test('corrupt configuration is reported and never overwritten or silently reset', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-project-config-'));
  try {
    const config = new ProjectConfig(directory);
    const broken = '{unfinished configuration';
    await writeFile(config.path, broken);
    await assert.rejects(config.list('http://localhost:8080', 'alice'));
    await assert.rejects(
      config.put({
        server: 'http://localhost:8080',
        account: 'alice',
        name: 'Research',
        path: '/projects/research',
        machine: 'laptop',
        enabled: true,
      }),
    );
    assert.equal(await readFile(config.path, 'utf8'), broken);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

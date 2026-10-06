import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  personalDirectory,
  readPersonal,
} from 'plowshare-client-node/personal';

await test('personal mount is idempotent and isolates accounts and servers', async () => {
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  try {
    const root = await personalDirectory(
      'http://server',
      'alice',
      'personal:alice',
      home,
    );
    assert.equal(
      await personalDirectory(
        'http://server/',
        'alice',
        'personal:alice',
        home,
      ),
      root,
    );
    const bob = await personalDirectory(
      'http://server',
      'bob',
      'personal:bob',
      home,
    );
    const other = await personalDirectory(
      'http://other',
      'alice',
      'personal:alice',
      home,
    );
    assert.notEqual(bob, root);
    assert.notEqual(other, root);
    assert.notEqual(other, bob);
    await mkdir(join(root, 'In'));
    await writeFile(join(root, 'In', 'alice.txt'), 'Alice');
    assert.equal(
      await personalDirectory('http://server', 'alice', 'personal:alice', home),
      root,
    );
    await assert.rejects(readPersonal(bob, 'In', 'In/alice.txt'));
    await assert.rejects(
      personalDirectory('http://server', 'alice', 'wrong-project', home),
      /another server or account/,
    );
  } finally {
    await rm(home, { recursive: true, force: true });
  }
});
await test('personal browser reads notes and refuses section traversal and escaping symlinks', async () => {
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  try {
    const root = await personalDirectory(
      'http://server',
      'alice',
      'personal:alice',
      home,
    );
    await mkdir(join(root, 'Planning'));
    await writeFile(join(root, 'Planning', 'plan.md'), 'A useful plan');
    const rows = await readPersonal(root, 'Planning');
    assert.equal(rows.entries[0]?.name, 'plan.md');
    assert.equal(
      (await readPersonal(root, 'Planning', 'Planning/plan.md')).text,
      'A useful plan',
    );
    await writeFile(join(home, 'private'), 'private');
    await symlink(join(home, 'private'), join(root, 'Planning', 'escape'));
    await assert.rejects(
      readPersonal(root, 'Planning', 'Planning/escape'),
      /leaves personal/,
    );
    await assert.rejects(
      readPersonal(root, 'Planning', 'Bots/default'),
      /section/,
    );
    await mkdir(join(root, 'Bots'));
    await writeFile(join(root, 'Bots', 'default'), 'bot');
    await assert.rejects(
      readPersonal(root, 'Planning', 'Planning/../Bots/default'),
      /section/,
    );
    await symlink(join(root, 'Bots', 'default'), join(root, 'Planning', 'bot'));
    await assert.rejects(
      readPersonal(root, 'Planning', 'Planning/bot'),
      /section/,
    );
  } finally {
    await rm(home, { recursive: true, force: true });
  }
});

await test('personal ownership cannot be redirected through metadata symlinks', async () => {
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  try {
    const root = await personalDirectory(
      'http://server',
      'alice',
      'personal:alice',
      home,
    );
    await mkdir(join(home, 'elsewhere'));
    await rm(join(root, '.plowshare'), { recursive: true });
    await symlink(join(home, 'elsewhere'), join(root, '.plowshare'));
    await assert.rejects(
      personalDirectory('http://server', 'alice', 'personal:alice', home),
      /metadata.*symbolic link/,
    );
  } finally {
    await rm(home, { recursive: true, force: true });
  }
});

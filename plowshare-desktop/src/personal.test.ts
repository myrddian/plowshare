import test from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  writeFile,
  readFile,
  symlink,
  rm,
  lstat,
  realpath,
  chmod,
} from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  personalDirectory,
  readPersonal,
} from 'plowshare-client-node/personal';
import { connectionDirectory } from 'plowshare-client-node/connections';

await test('unreadable legacy ownership does not prevent initializing the connection store', async (t) => {
  if (process.platform === 'win32' || process.getuid?.() === 0) {
    t.skip('This fixture requires enforced POSIX directory permissions.');
    return;
  }
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  const metadata = join(home, '.plowshare', 'personal', '.plowshare');
  t.after(async () => {
    await chmod(metadata, 0o700);
    await rm(home, { recursive: true, force: true });
  });
  await mkdir(metadata, { recursive: true });
  const owner = JSON.stringify({
    server: 'http://server',
    account: 'alice',
    project: 'personal:alice',
  });
  await writeFile(join(metadata, 'personal.json'), owner);
  await chmod(metadata, 0o000);
  const root = await personalDirectory(
    'http://server',
    'alice',
    'personal:alice',
    home,
  );
  assert.equal(
    root,
    await realpath(
      join(
        connectionDirectory('http://server', 'alice', join(home, '.plowshare')),
        'personal',
      ),
    ),
  );
  await chmod(metadata, 0o700);
  assert.equal(await readFile(join(metadata, 'personal.json'), 'utf8'), owner);
});

await test('unclaimed legacy sync storage does not block the default connection mount', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const legacy = join(home, '.plowshare', 'personal');
  await mkdir(join(legacy, '.plowshare', 'sync.git'), { recursive: true });
  await writeFile(join(legacy, 'old-notes.txt'), 'Preserve unclaimed files');
  const root = await personalDirectory(
    'http://server',
    'alice',
    'personal:alice',
    home,
  );
  assert.equal(
    root,
    await realpath(
      join(
        connectionDirectory('http://server', 'alice', join(home, '.plowshare')),
        'personal',
      ),
    ),
  );
  assert.equal(
    await readFile(join(legacy, 'old-notes.txt'), 'utf8'),
    'Preserve unclaimed files',
  );
  assert.ok(
    (await lstat(join(legacy, '.plowshare', 'sync.git'))).isDirectory(),
  );
  await assert.rejects(readFile(join(root, 'old-notes.txt')), {
    code: 'ENOENT',
  });
  assert.equal(
    await personalDirectory('http://server', 'alice', 'personal:alice', home),
    root,
  );
});

await test('unproven legacy ownership never imports files into a connection', async (t) => {
  for (const owner of [
    '{invalid json',
    JSON.stringify({
      server: 'not an origin',
      account: 'alice',
      project: 'personal:alice',
    }),
    JSON.stringify({
      server: 'http://server',
      account: 'bob',
      project: 'personal:bob',
    }),
    JSON.stringify({
      server: 'http://other',
      account: 'alice',
      project: 'personal:alice',
    }),
    JSON.stringify({
      server: 'http://server',
      account: 'alice',
      project: 'another-project',
    }),
  ]) {
    const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
    t.after(() => rm(home, { recursive: true, force: true }));
    const legacy = join(home, '.plowshare', 'personal');
    await mkdir(join(legacy, '.plowshare'), { recursive: true });
    await writeFile(join(legacy, '.plowshare', 'personal.json'), owner);
    await writeFile(join(legacy, 'old-notes.txt'), 'Do not import');
    const root = await personalDirectory(
      'http://server',
      'alice',
      'personal:alice',
      home,
    );
    assert.equal(
      await readFile(join(legacy, 'old-notes.txt'), 'utf8'),
      'Do not import',
    );
    assert.equal(
      await readFile(join(legacy, '.plowshare', 'personal.json'), 'utf8'),
      owner,
    );
    await assert.rejects(readFile(join(root, 'old-notes.txt')), {
      code: 'ENOENT',
    });
  }
});

await test('existing scoped Personal wins over a duplicate proven legacy checkout', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const root = await personalDirectory(
    'http://server',
    'alice',
    'personal:alice',
    home,
  );
  await writeFile(join(root, 'current.txt'), 'Current checkout');
  const legacy = join(home, '.plowshare', 'personal');
  await mkdir(join(legacy, '.plowshare'), { recursive: true });
  await writeFile(
    join(legacy, '.plowshare', 'personal.json'),
    JSON.stringify({
      server: 'http://server',
      account: 'alice',
      project: 'personal:alice',
    }),
  );
  await writeFile(join(legacy, 'old.txt'), 'Preserve duplicate');
  assert.equal(
    await personalDirectory('http://server', 'alice', 'personal:alice', home),
    root,
  );
  assert.equal(
    await readFile(join(root, 'current.txt'), 'utf8'),
    'Current checkout',
  );
  assert.equal(
    await readFile(join(legacy, 'old.txt'), 'utf8'),
    'Preserve duplicate',
  );
  await assert.rejects(readFile(join(root, 'old.txt')), { code: 'ENOENT' });
});

await test('symlinked legacy storage is preserved without redirecting the default mount', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'plowshare-personal-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const outside = join(home, 'outside');
  await mkdir(join(outside, '.plowshare'), { recursive: true });
  await writeFile(
    join(outside, '.plowshare', 'personal.json'),
    JSON.stringify({
      server: 'http://server',
      account: 'alice',
      project: 'personal:alice',
    }),
  );
  await mkdir(join(home, '.plowshare'));
  await symlink(outside, join(home, '.plowshare', 'personal'));
  const root = await personalDirectory(
    'http://server',
    'alice',
    'personal:alice',
    home,
  );
  assert.notEqual(root, outside);
  assert.ok(
    (await lstat(join(home, '.plowshare', 'personal'))).isSymbolicLink(),
  );
});

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

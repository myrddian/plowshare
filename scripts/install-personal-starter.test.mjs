import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { installPersonalStarter } from './install-personal-starter.mjs';

async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'personal-starter-'));
  try {
    await mkdir(join(root, '.plowshare/sync.git'), { recursive: true });
    await writeFile(join(root, '.plowshare/personal.json'), JSON.stringify({ account: 'alice', server: 'http://localhost', project: 'personal:616c696365' }));
    await run(root);
  } finally { await rm(root, { recursive: true, force: true }); }
}
test('starter installs into the claimed union and a repeat preserves edits', async () => fixture(async root => {
  const first = await installPersonalStarter(root);
  assert.equal(first.created.length, 25);
  assert.equal(first.preserved.length, 0);
  await writeFile(join(root, 'Resources/AGENTS.md'), 'My instructions');
  const second = await installPersonalStarter(root);
  assert.equal(second.created.length, 0);
  assert.equal(second.preserved.length, 25);
  assert.equal(await readFile(join(root, 'Resources/AGENTS.md'), 'utf8'), 'My instructions');
}));
test('starter rejects a mismatched ownership claim', async () => fixture(async root => {
  await writeFile(join(root, '.plowshare/personal.json'), JSON.stringify({ account: 'bob', server: 'http://localhost', project: 'personal:616c696365' }));
  await assert.rejects(installPersonalStarter(root), /not claimed/);
}));
test('starter refuses an escaping directory before installing files', async () => fixture(async root => {
  const outside = await mkdtemp(join(tmpdir(), 'personal-outside-'));
  try {
    await symlink(outside, join(root, 'Resources'));
    await assert.rejects(installPersonalStarter(root), /symlinks/);
    await assert.rejects(readFile(join(outside, 'AGENTS.md')), { code: 'ENOENT' });
  } finally { await rm(outside, { recursive: true, force: true }); }
}));

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, realpath, rm, symlink, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readCapsFile, saveCapsFile } from './caps.ts';
test('reviewed caps preserve local policy and refuse a file changed after review', async t => {
  const root = await realpath(await mkdtemp(join(tmpdir(),'plowshare-caps-')));t.after(()=>rm(root,{recursive:true,force:true}));
  await mkdir(join(root,'.plowshare'));const file=join(root,'.plowshare/environment.yml');await writeFile(file,'local:\n  mode: ask\n');
  const before=await readCapsFile(root); await saveCapsFile(root,before,'budget',30);
  assert.match(await readFile(file,'utf8'),/mode: ask/);assert.match(await readFile(file,'utf8'),/budget: 30/);
  await assert.rejects(saveCapsFile(root,before,'budget',60),/changed/);
});
test('caps refuse linked configuration instead of writing outside the project', async t => {
  const root=await realpath(await mkdtemp(join(tmpdir(),'plowshare-caps-link-')));t.after(()=>rm(root,{recursive:true,force:true}));
  await mkdir(join(root,'elsewhere'));await symlink(join(root,'elsewhere'),join(root,'.plowshare'));
  await assert.rejects(readCapsFile(root),/real directory/);
});

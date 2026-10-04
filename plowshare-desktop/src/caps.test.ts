import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, realpath, rm, symlink, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readCapsFile, saveCapsFile } from './caps.ts';
import { localModePreview, saveSettingsFile } from 'plowshare-client-node/settings';
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

test('automatic increases use reviewed boolean configuration and preserve command policy', async t => {
  const root=await realpath(await mkdtemp(join(tmpdir(),'plowshare-caps-auto-')));t.after(()=>rm(root,{recursive:true,force:true}));
  await mkdir(join(root,'.plowshare'));const file=join(root,'.plowshare/environment.yml');await writeFile(file,'local:\n  mode: ask\ncaps:\n  budget: 20\n');
  await saveCapsFile(root,await readCapsFile(root),'auto-increase',1);
  assert.match(await readFile(file,'utf8'),/auto-increase: true/);assert.match(await readFile(file,'utf8'),/mode: ask/);
  await saveCapsFile(root,await readCapsFile(root),'auto-increase',0);
  assert.match(await readFile(file,'utf8'),/auto-increase: false/);
  await assert.rejects(saveCapsFile(root,await readCapsFile(root),'auto-increase',2));
});

test('JSON projects update caps and command policy in place without creating .plowshare', async t => {
  const root=await realpath(await mkdtemp(join(tmpdir(),'plowshare-json-caps-')));t.after(()=>rm(root,{recursive:true,force:true}));
  const file=join(root,'plowshare'), original={version:1,name:'house',routing:{sendTo:['alerts']},homeAssistant:{entities:['light.office']},skills:{research:{agentVisible:true}},defaultBot:'interlocutor'};
  await writeFile(file,JSON.stringify(original));
  const before=await readCapsFile(root);assert.equal(before.file,file);assert.equal(before.format,'json');
  await saveCapsFile(root,before,'auto-increase',1);
  const next=await readCapsFile(root);await saveSettingsFile(root,next,localModePreview(next,'ask'));
  assert.deepEqual(JSON.parse(await readFile(file,'utf8')),{...original,caps:{autoIncrease:true},commands:{local:{mode:'ask'}}});
  await assert.rejects(readFile(join(root,'.plowshare/environment.yml')),{code:'ENOENT'});
  await assert.rejects(saveCapsFile(root,before,'budget',50),/changed/);
});

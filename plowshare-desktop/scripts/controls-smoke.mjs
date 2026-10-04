import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, realpath, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
const temporary=await realpath(await mkdtemp(join(tmpdir(),'plowshare-controls-'))),root=join(temporary,'Research');await mkdir(root);
const fixture=await protocolFixture({controls:true});
const env={...process.env,PLOWSHARE_CONFIG_DIR:join(temporary,'credentials'),PLOWSHARE_DESKTOP_CONFIG:join(temporary,'config'),PLOWSHARE_DESKTOP_PROFILE:join(temporary,'profile')};delete env.ELECTRON_RUN_AS_NODE;
let app;
try{
 app=await electron.launch({executablePath,args:[resolve('.')],env});const main=await app.firstWindow(),errors=[];main.on('pageerror',e=>errors.push(e.message));
 await main.evaluate(base=>window.plowshare.request({action:'connect',base,handle:'fixture',password:'fixture-password'}),fixture.base);
 await app.evaluate(({dialog},root)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[root]});},root);
 await main.evaluate(()=>window.plowshare.request({action:'files-choose'}));
 await main.locator('#controls-button').click();const panel=main.locator('.operator-dialog');await expect(panel).toBeVisible();await expect(panel.locator('[data-change]')).toBeVisible();await expect(panel.locator('[data-task-description]')).toContainText('Save something agents should remember');await mkdir('build/smoke',{recursive:true});await main.screenshot({path:'build/smoke/manage-work.png'});
 async function choose(kind,project=''){
  await panel.locator('[name="kind"]').selectOption(kind);
  if(kind!=='approval-grant')await panel.locator('[name="project"]').selectOption(project);
  await expect(panel.locator('[data-change]')).toBeVisible();
 }
 async function apply(){await panel.locator('[data-change] button[type="submit"]').click();await expect(panel.locator('[data-confirm]')).toBeVisible();await expect(panel.locator('[data-apply]')).toBeDisabled();await panel.locator('[data-confirm]').check();await panel.locator('[data-apply]').click();await expect(panel.locator('[data-notice]')).toContainText(/confirmed|accepted|saved/i);await expect(panel.locator('[data-confirm]')).not.toBeVisible();}
 await choose('memory-write','Research');await panel.locator('[name="summary"]').fill('A remembered build');await panel.locator('[name="scope"]').fill('Project builds');await panel.locator('[name="body"]').fill('Use the reviewed command. <img src=x onerror="window.fixtureInjection=true">');
 await panel.locator('[data-change] button[type="submit"]').click();await expect(panel.locator('[data-content]')).toContainText('reviewed command');assert.equal(fixture.frames.some(f=>f.type==='memory.write'),false);await panel.locator('[data-confirm]').check();await panel.locator('[data-apply]').click();await expect(panel.locator('[data-notice]')).toContainText('confirmed');assert.equal(await main.evaluate(()=>window.fixtureInjection),undefined);
 await choose('memory-digest','Research');await apply();await expect(panel.locator('[data-jobs]')).toContainText('finished');
 await choose('agent-curate','Research');await apply();await expect(panel.locator('[data-jobs]')).toContainText('Improve project agents');
 await choose('conversation-lifecycle');await panel.locator('[name="id"]').selectOption('fixture-first');await apply();assert.equal(fixture.frames.find(f=>f.type==='conversation.lifecycle').payload.lifecycle,'archived');assert.equal(await main.evaluate(async()=>(await window.plowshare.request({action:'bootstrap'})).state.conversations.some(row=>row.id==='fixture-first')),false);
 await choose('conversation-lifecycle');await panel.locator('[name="id"]').selectOption('fixture-first');await panel.locator('[name="lifecycle"]').selectOption('active');await apply();
 await choose('conversation-resume');await panel.locator('[name="id"]').selectOption('fixture-first');await apply();
 await expect.poll(async()=>main.evaluate(async()=>(await window.plowshare.request({action:'bootstrap'})).state.jobs.filter(j=>['starting','running','cancelling','unknown'].includes(j.status)&&j.conversation==='fixture-first').length)).toBe(0);
 await main.evaluate(()=>window.plowshare.request({action:'run',conversation:'fixture-first',agent:'fixture-bot',text:'Adjust this active job'}));
 await choose('job-limits');await apply();assert.equal(fixture.frames.some(f=>f.type==='job.limits'),true);fixture.completeLatest('Active job limits adjusted.');
 await choose('approval-grant');await panel.locator('[name="decision"]').selectOption('project');await panel.locator('[name="prefix"]').fill('echo\nfixture approval');await apply();assert.deepEqual(fixture.frames.find(f=>f.type==='approval.answer').payload.prefix,['echo','fixture approval']);
 await choose('approval-revoke','Research');await apply();await expect(panel.locator('[data-content]')).toContainText('Grant revoked');
 await choose('board-topup');await panel.locator('[name="maxModelCalls"]').fill('140');await apply();assert.equal(fixture.frames.find(f=>f.type==='board.topup').payload.maxModelCalls,140);
 await choose('message-open','Research');await panel.locator('[name="agent"]').selectOption('fixture-bot');await panel.locator('[name="makeDefault"]').selectOption('true');await apply();
 const opened=fixture.frames.find(f=>f.type==='message.instance.open');assert.equal(opened.payload.project,'Research');assert.match(opened.payload.requestId,/^[0-9a-f-]{36}$/);assert.equal(opened.payload.makeDefault,true);
 await choose('message-default','Research');await panel.locator('[name="id"]').selectOption('ins_fixture');await apply();assert.equal(fixture.frames.find(f=>f.type==='message.instance.default').payload.instance,'ins_fixture');
 await choose('message-deliveries','Research');await panel.locator('[name="instance"]').selectOption('ins_fixture');await panel.locator('[data-message-read]').click();await expect(panel.locator('[data-content]')).toContainText('Please review this.');assert.equal(await main.evaluate(()=>window.fixtureInjection),undefined);
 await panel.locator('[data-change] button[type="submit"]').click();await expect(panel.locator('[data-content] pre')).toContainText('Please review this.');assert.equal(fixture.frames.some(f=>f.type==='message.cancel'),false);await panel.locator('[data-confirm]').check();await panel.locator('[data-apply]').click();await expect(panel.locator('[data-notice]')).toContainText('confirmed');assert.equal(fixture.frames.find(f=>f.type==='message.cancel').payload.message,'bdm_fixture');
 await panel.locator('[data-message-read]').click();await expect(panel.locator('[data-content]')).toContainText('cancelled');await main.screenshot({path:'build/smoke/message-deliveries.png'});
 await choose('message-stop','Research');await panel.locator('[name="id"]').selectOption('ins_fixture_1');await apply();await expect(panel.locator('[data-content]')).toContainText('stopped');
 await choose('message-archive','Research');await panel.locator('[name="id"]').selectOption('ins_fixture_1');await apply();await expect(panel.locator('[name="id"] option[value="ins_fixture_1"]')).toHaveCount(0);
 await main.screenshot({path:'build/smoke/message-instances.png'});
 await choose('caps','Research');await panel.locator('[name="key"]').selectOption('budget');await panel.locator('[name="value"]').fill('50');await panel.locator('[data-change] button[type="submit"]').click();await expect(panel.locator('[data-content] pre')).toContainText('budget: 50');await panel.locator('[data-confirm]').check();await panel.locator('[data-apply]').click();await expect(panel.locator('[data-notice]')).toContainText('saved');assert.match(await readFile(join(root,'.plowshare/environment.yml'),'utf8'),/budget: 50/);
 await panel.locator('[name="key"]').selectOption('budget');await panel.locator('[name="value"]').fill('80');await panel.locator('[data-change] button[type="submit"]').click();await expect(panel.locator('[data-confirm]')).toBeVisible();
 await mkdir('build/smoke',{recursive:true});await main.screenshot({path:'build/smoke/controls.png'});await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(900,640));await panel.locator('[data-confirm]').scrollIntoViewIfNeeded();await expect(panel.locator('[data-confirm]')).toBeVisible();await main.screenshot({path:'build/smoke/controls-compact.png'});assert.deepEqual(errors,[]);
 console.log('PASS: reviewed native memory creation/digest/curation, archive/restore/resume, job limits, scoped command grant/revocation, Board top-up, persistent message creation/default/stop/archive, project caps and escaped source text.');
}finally{if(app)await app.close();await fixture.close();await rm(temporary,{recursive:true,force:true});}

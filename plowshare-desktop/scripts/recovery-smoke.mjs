import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
import { scopedStateFile } from 'plowshare-client-node/connections';
const directory=await mkdtemp(join(tmpdir(),'plowshare-recovery-'));
const fixture=await protocolFixture({rotateTokens:true});
const env={...process.env,PLOWSHARE_CONFIG_DIR:directory,PLOWSHARE_DESKTOP_PROFILE:join(directory,'profile')};delete env.ELECTRON_RUN_AS_NODE;delete env.PLOWSHARE_DESKTOP_CONFIG;
let app;
const launch=async()=>{app=await electron.launch({executablePath,args:[resolve('.')],env});const page=await app.firstWindow();await page.locator('#connection-button').waitFor();await page.evaluate(()=>window.plowshare.request({action:'bootstrap'}));return page;};
const state=page=>page.evaluate(async()=>(await window.plowshare.request({action:'bootstrap'})).state);
const close=async()=>{await app.close();app=undefined;};
const journal=async()=>JSON.parse(await readFile(await scopedStateFile(fixture.base,'fixture',directory,'desktop-jobs.json'),'utf8'));
try{
 let page=await launch();
 await page.evaluate(base=>window.plowshare.request({action:'connect',base,handle:'fixture',password:'fixture-password'}),fixture.base);
 // Resolve the fixture's unrelated command request through its public prompt
 // before starting the work whose restart/unknown-delivery lifecycle is tested.
 const approval=page.locator('#approval-bar [data-approval-prompt="fixture-approval"]');
 await approval.getByRole('button',{name:'Deny',exact:true}).click();
 await expect(approval).toHaveCount(0);
 fixture.completeLatest('Fixture command request denied before the recovery scenario.');
 await expect.poll(async()=>(await state(page)).jobs.filter(row=>row.conversation==='fixture-first'&&['starting','running','cancelling','unknown'].includes(row.status)).length).toBe(0);
 await page.evaluate(()=>window.plowshare.request({action:'run',agent:'fixture-bot',text:'Work that survives closing the desktop',conversation:'fixture-first'}));
 const id=fixture.latestJob;
 await expect.poll(async()=>JSON.stringify(await journal())).toContain(id);
 await close();
 assert.ok(Object.values((await journal()).accounts).flat().some(row=>row.handle===id));
 fixture.completeLatest('Completed while the desktop was closed.');
 page=await launch();
 await expect.poll(async()=>(await state(page)).jobs.find(row=>row.id===id)?.status).toBe('finished');
 assert.equal(fixture.frames.filter(frame=>frame.type==='agent.run').length,1);
 assert.equal(fixture.loginCount(),1);
 assert.ok(fixture.frames.some(frame=>frame.type==='job.status'&&frame.payload.job===id));

 await page.evaluate(()=>window.plowshare.request({
   action:'run',agent:'fixture-bot',text:'Interrupted work',conversation:'fixture-first',
 }));
 const interrupted = fixture.latestJob;
 await expect.poll(async()=>JSON.stringify(await journal())).toContain(interrupted);
 await close();
 fixture.forgetJobStatus(interrupted);
 fixture.failLatest('The retained continuation stopped.');
 fixture.appendTurn('fixture-first','A newer turn answered.');
 page=await launch();
 await expect.poll(async()=>(await state(page)).jobs.find(row=>row.id===interrupted)?.ending).toBe('UNAVAILABLE');
 await page.keyboard.press('Control+k');
 await page.locator('#navigation-query').fill('Fixture conversation');
 await page.keyboard.press('Enter');
 await expect(page.locator('#chat-title')).toHaveText('Fixture conversation');
 const alerts = page.locator('#conversation-alerts');
 await expect(alerts).toContainText('Run stopped · unavailable');
 await expect(alerts).not.toContainText('Outcome uncertain');
 await expect(page.locator('#retry-conversation')).toHaveCount(0);
 await alerts.getByRole('button',{name:'Dismiss job banner'}).click();
 await expect(alerts).not.toContainText('Run stopped · unavailable');
 await page.evaluate(()=>window.plowshare.request({action:'refresh'}));
 await expect(alerts).not.toContainText('Run stopped · unavailable');
 await expect(page.locator('#transcript')).toContainText('UNAVAILABLE: The retained continuation stopped.');
 assert.equal(fixture.frames.filter(frame=>frame.type==='agent.run').length,2);
 assert.equal(fixture.frames.filter(frame=>['conversation.resume','job.cancel'].includes(frame.type)).length,0);

 fixture.loseRunReply();
 await assert.rejects(page.evaluate(()=>window.plowshare.request({action:'run',agent:'fixture-bot',text:'Receipt lost before job acceptance',conversation:'fixture-first'})));
 await expect.poll(async()=>(await state(page)).jobs.some(row=>row.status==='unknown')).toBe(true);
 await expect(alerts).toContainText('Outcome uncertain');
 await alerts.getByRole('button',{name:'Dismiss job banner'}).click();
 await expect(alerts).not.toContainText('Outcome uncertain');
 await expect.poll(async()=>Object.values((await journal()).accounts).flat().some(row=>!row.handle)).toBe(true);
 await close();page=await launch();
 await expect.poll(async()=>(await state(page)).jobs.some(row=>row.status==='unknown')).toBe(true);
 await assert.rejects(page.evaluate(()=>window.plowshare.request({action:'run',agent:'fixture-bot',text:'Must stay withheld',conversation:'fixture-first'})),/active|unknown|uncertain|running/i);
 assert.equal(fixture.frames.filter(frame=>frame.type==='agent.run').length,3);
 const serialized=JSON.stringify(await journal());assert.ok(!serialized.includes('fixture-access')&&!serialized.includes('fixture-refresh')&&!serialized.includes('Work that survives'));
 console.log('PASS: fresh desktop process restores saved job handles and completion without a new login or resubmission; missing acceptance survives restart and blocks duplicate work.');
}finally{if(app)await close();await fixture.close();await rm(directory,{recursive:true,force:true});}

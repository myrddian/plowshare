import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
const revision='00000000-0000-0000-0000-000000000099';
const profile=await mkdtemp(join(tmpdir(),'plowshare-handoff-smoke-'));
const fixture=await protocolFixture({retainedReport:revision,agentRoster:async()=>[{name:'friendly_bot',displayName:'Friendly Bot',origin:'classpath bots/friendly_bot.md',bot:true,served:true,preferred:true,description:'Your research companion',tools:['information_read'],scopes:['workspace:read'],withheld:['file_edit withheld'],model:'fixture-model'}]});
fixture.addServerProject({name:'Research'});fixture.addServerProject({name:'Other project'});
const env={...process.env,PLOWSHARE_CONFIG_DIR:join(profile,'credentials'),PLOWSHARE_DESKTOP_CONFIG:join(profile,'config'),PLOWSHARE_DESKTOP_PROFILE:profile};delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
 app=await electron.launch({executablePath,args:[resolve('.')],env});
 const main=await app.firstWindow(),errors=[];main.on('pageerror',error=>errors.push(error.message));
 await main.locator('#connect-sidebar').click();await main.locator('#server-url').fill(fixture.base);await main.locator('#handle').fill('fixture');await main.locator('#password').fill('fixture-password');await main.locator('#submit-connection').click();await expect(main.locator('#connection-label')).toHaveText('Connected');
 await main.locator('[data-project="Research"]').click();
 await expect(main.locator('#agent')).toContainText('Friendly Bot');
 await main.locator('#agent-identity > summary').click();await expect(main.locator('#agent-identity')).toContainText('classpath bots/friendly_bot.md');await expect(main.locator('#agent-identity')).toContainText('workspace:read');await expect(main.locator('#agent-identity')).toContainText('file_edit withheld');
 await mkdir('build/smoke',{recursive:true});await main.screenshot({path:'build/smoke/bot-identity.png'});
 const opening=app.waitForEvent('window');await main.evaluate(()=>window.plowshare.request({action:'activity',view:'runs'}));const runs=await opening;runs.on('pageerror',error=>errors.push(error.message));
 await runs.locator('[data-item="fixture-old-root"]').click();
 await expect(runs.locator('[data-report-revision]')).toHaveAttribute('data-report-revision',revision);
 const reading=app.waitForEvent('window');await runs.locator('[data-report-revision]').click();const library=await reading;library.on('pageerror',error=>errors.push(error.message));
 await expect(library.locator('#library-source-panel [data-passage]')).toHaveValue(/A retained claim\./);
 await expect(library.locator('#library-source-panel [data-detail]')).toContainText('draft report');
 const read=fixture.frames.find(frame=>frame.type==='information.read'&&frame.payload.revision===revision);
 assert.ok(read);assert.deepEqual(read.payload.scope,{kind:'project',project:'Other project',includeShared:true});
 assert.equal(fixture.frames.filter(frame=>['information.finalise','information.share','information.ask','information.retry'].includes(frame.type)).length,0);
 await library.screenshot({path:'build/smoke/report-handoff.png'});
 // Reusing the existing Library window must reopen the exact revision.
 await library.locator('#library-documents').click();await runs.locator('[data-report-revision]').click();await expect(library.locator('#library-source-panel [data-passage]')).toHaveValue(/A retained claim\./);
 await assert.rejects(runs.evaluate(()=>window.plowshare.request({action:'library',view:'sources',revision:'00000000-0000-0000-0000-000000000098',project:'Other project'})),/displayed run/);
 assert.deepEqual(errors,[]);console.log('PASS: friendly bot identity, resolved definition origin/access, retained report handoff, exact revision/project, draft preservation and reused reader window.');
}catch(error){if(app){const main=await app.firstWindow();console.error('Visible errors:',await main.locator('[role=alert]').allTextContents());}throw error;}finally{if(app)await app.close();await fixture.close();await rm(profile,{recursive:true,force:true});}

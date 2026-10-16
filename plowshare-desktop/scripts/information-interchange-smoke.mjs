import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm, readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

// The Electron/SDK/IPC journey uses an isolated protocol fixture; PostgreSQL is checked separately.
const revision='aaaaaaaa-0000-0000-0000-000000000001',resource='cccccccc-0000-0000-0000-000000000001';
const localRevision='eeeeeeee-0000-0000-0000-000000000001',localResource='dddddddd-0000-0000-0000-000000000001';
const body=Buffer.from('\uFEFF# A retained claim 🔎\r\nKeep exact bytes.\r\n');
const hash=createHash('sha256').update(body).digest('hex');
const descriptor={resource,revision,ordinal:1,sourceName:'source.md',title:'Source',path:'documents/source.md',mediaType:'text/markdown',contentHash:hash,tags:['billing'],provenance:{owner:'foreign-owner',kind:'source',createdAt:'2026-10-16T00:00:00Z',sourceHash:hash,dependencies:[],evidence:[]}};
const bundle={format:'plowshare-information-bundle/1',id:resource,documents:[{descriptor,bytesBase64:body.toString('base64')}],relationships:[],relationshipCoverage:'included-endpoints-only'};
const plan={bundle,destinations:[{resource,name:'imported/source.md',mode:'create'}],types:[],reuse:[],restoreRelationships:false};
const calls=[];let refuse=true;let recoveryPath;
const fixture=await protocolFixture({information(operation,payload){
  if(!operation.startsWith('information.interchange.'))return undefined;
  calls.push({operation,payload});
  if(operation==='information.interchange.capabilities')return {code:'OK',payload:{format:bundle.format,maxDocuments:16,maxBytes:2097152,maxJsonBytes:8388608}};
  if(operation==='information.interchange.export')return {code:'OK',payload:bundle};
  if(operation==='information.interchange.readable')return {code:'OK',payload:{format:bundle.format,path:descriptor.path,representation:'original_markdown',markdown:body.toString('utf8'),textHash:hash,sidecar:descriptor}};
  if(operation==='information.interchange.import') {
    assert.deepEqual(payload.plan,plan);
    const saved=JSON.parse(readFileSync(recoveryPath,'utf8'));
    assert.equal(saved.requestId,payload.requestId);assert.deepEqual(saved.plan,payload.plan);
    if(refuse){refuse=false;return {code:'BAD_REQUEST',said:'Import is in progress; retain the same request identity for recovery.'};}
    return {code:'OK',payload:{bundle:bundle.id,mappings:[{foreignResource:resource,foreignRevision:revision,resource:localResource,revision:localRevision}]}};
  }
  return undefined;
}});
const profile=await mkdtemp(join(tmpdir(),'plowshare-interchange-smoke-'));
const env={...process.env,PLOWSHARE_CONFIG_DIR:join(profile,'credentials'),PLOWSHARE_DESKTOP_CONFIG:join(profile,'config'),PLOWSHARE_DESKTOP_PROFILE:profile};
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app=await electron.launch({executablePath,args:[resolve('.')],env});
  recoveryPath=join(profile,'import.recovery.json');
  const main=await app.firstWindow(),errors=[];
  main.on('pageerror',e=>errors.push(e.message));
  await main.locator('#connect-sidebar').click();await main.locator('#server-url').fill(fixture.base);await main.locator('#handle').fill('fixture');await main.locator('#password').fill('fixture-password');await main.locator('#submit-connection').click();
  await expect(main.locator('#connection-label')).toContainText('· Connected');await main.locator('#library-open').click();
  await expect.poll(()=>app.windows().some(p=>p.url()==='plowshare://app/library.html'),{timeout:30000}).toBe(true);
  let library=app.windows().find(p=>p.url()==='plowshare://app/library.html');assert.ok(library);
  library.on('pageerror',e=>errors.push(e.message));
  await library.locator('#library-documents').click();await library.locator('#document-sources > summary').click();
  let sources=library.locator('#document-source-panel'),panel=sources.locator('.information-interchange');
  await expect(panel).toBeVisible();await panel.locator('summary').click();
  await sources.locator(`[data-list] [data-id="${revision}"]`).click();
  await library.evaluate(()=>{
    window.portableDownloads=[];
    URL.createObjectURL=blob=>{window.portableDownloads.push(blob);return 'blob:fixture';};
    HTMLAnchorElement.prototype.click=function(){};
  });
  await panel.locator('[data-portable-bundle]').click();await expect(panel.locator('[data-portable-status]')).toContainText('Exported 1 versions');
  await panel.locator('[data-portable-readable]').click();await expect(panel.locator('[data-portable-status]')).toContainText('original Markdown');
  const downloads=await library.evaluate(()=>Promise.all(window.portableDownloads.map(async blob=>Array.from(new Uint8Array(await blob.arrayBuffer())))));
  assert.equal(downloads.length,3);assert.deepEqual(JSON.parse(Buffer.from(downloads[0]).toString('utf8')),bundle);assert.deepEqual(Buffer.from(downloads[1]),body);assert.equal(JSON.parse(Buffer.from(downloads[2]).toString('utf8')).representation,'original_markdown');
  await panel.locator('input[name=plan]').setInputFiles({name:'plan.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(plan))});
  await panel.locator('[data-portable-review] button').click();await expect(panel.locator('[data-portable-preview]')).toContainText('imported/source.md');
  await expect(panel.locator('[data-portable-preview]')).toContainText('Omit relationship restoration');
  assert.equal(calls.filter(c=>c.operation==='information.interchange.import').length,0);
  await app.evaluate(({dialog})=>{dialog.showSaveDialog=async()=>({canceled:true});});
  await panel.locator('[data-portable-import]').click();await expect(panel.locator('[data-portable-status]')).toContainText('Import was not submitted');
  assert.equal(calls.filter(c=>c.operation==='information.interchange.import').length,0);
  await app.evaluate(({dialog},filePath)=>{dialog.showSaveDialog=async()=>({canceled:false,filePath});},join(profile,'missing-directory','import.json'));
  await panel.locator('[data-portable-import]').click();await expect(panel.locator('[data-portable-status]')).toContainText('ENOENT');
  assert.equal(calls.filter(c=>c.operation==='information.interchange.import').length,0);
  await app.evaluate(({dialog},filePath)=>{dialog.showSaveDialog=async()=>({canceled:false,filePath});},recoveryPath);
  await panel.locator('[data-portable-import]').click();await expect(panel.locator('[data-portable-status]')).toContainText('retain the same request identity');
  const recovery=JSON.parse(await readFile(recoveryPath,'utf8'));
  assert.equal(recovery.format,'plowshare-information-import-recovery/1');
  assert.equal(recovery.requestId,calls.find(c=>c.operation==='information.interchange.import').payload.requestId);
  assert.deepEqual(recovery.plan,plan);
  // Destroy the renderer's in-memory draft, then restore only the saved caller-owned recovery file.
  await library.close();
  await main.locator('#library-open').click();
  await expect.poll(()=>app.windows().some(p=>p.url()==='plowshare://app/library.html'),{timeout:30000}).toBe(true);
  library=app.windows().find(p=>p.url()==='plowshare://app/library.html');assert.ok(library);
  library.on('pageerror',e=>errors.push(e.message));
  await library.locator('#library-documents').click();await library.locator('#document-sources > summary').click();
  sources=library.locator('#document-source-panel');panel=sources.locator('.information-interchange');
  await expect(panel).toBeVisible();await panel.locator('summary').click();
  await library.evaluate(()=>{
    window.portableDownloads=[];
    URL.createObjectURL=blob=>{window.portableDownloads.push(blob);return 'blob:fixture';};
    HTMLAnchorElement.prototype.click=function(){};
  });
  await panel.locator('input[name=plan]').setInputFiles({name:'foreign.recovery.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({...recovery,context:'foreign-account-scope'}))});
  await panel.locator('[data-portable-review] button').click();await expect(panel.locator('[data-portable-status]')).toContainText('original connection, account and scope');
  assert.equal(calls.filter(c=>c.operation==='information.interchange.import').length,1);
  await panel.locator('input[name=plan]').setInputFiles({name:'import.recovery.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(recovery))});
  await panel.locator('[data-portable-review] button').click();await expect(panel.locator('[data-portable-preview]')).toContainText('imported/source.md');
  assert.equal(calls.filter(c=>c.operation==='information.interchange.import').length,1);
  await panel.locator('[data-portable-import]').click();await expect(panel.locator('[data-portable-status]')).toContainText('Import retained');
  const imports=calls.filter(c=>c.operation==='information.interchange.import');assert.equal(imports.length,2);assert.equal(imports[0].payload.requestId,imports[1].payload.requestId);
  await panel.locator('input[name=plan]').setInputFiles({name:'unsafe.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({...plan,bundle:{...bundle,documents:[{...bundle.documents[0],descriptor:{...descriptor,path:'../escape.md'}}]}}))});
  await panel.locator('[data-portable-review] button').click();await expect(panel.locator('[data-portable-status]')).toContainText('Invalid');await expect(panel.locator('[data-portable-import]')).toBeHidden();
  assert.equal(calls.filter(c=>c.operation==='information.interchange.import').length,2);
  assert.equal(fixture.frames.filter(f=>f.type==='agent.run'||f.type==='information.retry'||f.type==='information.ask').length,0);
  await mkdir('build/smoke',{recursive:true});await panel.screenshot({path:'build/smoke/information-interchange.png'});
  assert.deepEqual(errors,[]);
  console.log('Portable Library smoke passed: exact downloads, reviewed destinations, receipt recovery after closing/reopening Library, scope fencing and unsafe-plan refusal.');
} catch(error) {
  const library=app?.windows().find(p=>p.url()==='plowshare://app/library.html');
  if(library) {
    await mkdir('build/smoke',{recursive:true});await library.screenshot({path:'build/smoke/information-interchange-failed.png'});
    console.error('Exchange geometry',await library.evaluate(()=>['#document-source-panel','.information-workspace','.information-list-pane','.information-interchange'].map(selector=>{
      const e=document.querySelector(selector);if(!e)return {selector};const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {selector,x:r.x,y:r.y,w:r.width,h:r.height,overflow:s.overflow,flex:s.flex};
    })));
  }
  throw error;
} finally {if(app)await app.close();await fixture.close();await rm(profile,{recursive:true,force:true});}

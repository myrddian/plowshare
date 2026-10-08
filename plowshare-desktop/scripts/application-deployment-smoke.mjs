import {_electron as electron, expect} from 'playwright/test';
import executablePath from 'electron';
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import assert from 'node:assert/strict';
import {protocolFixture} from './protocol-fixture.mjs';
const profile=await mkdtemp(join(tmpdir(),'plowshare-deploy-ui-'));
const source=join(profile,'source'); await mkdir(source);
await writeFile(join(source,'plowshare.json'),'{"version":1,"name":"demo","access":{"accounts":[]}}');
await mkdir(join(source,'agents'));
await writeFile(join(source,'agents','worker.md'),'Application-root source');
const env={...process.env,PLOWSHARE_DESKTOP_CONFIG:join(profile,'config'),PLOWSHARE_CONFIG_DIR:join(profile,'credentials'),PLOWSHARE_DESKTOP_PROFILE:profile}; delete env.ELECTRON_RUN_AS_NODE;
const revision='22222222-2222-2222-2222-222222222222'; let receipt, refuseStatus=false, storesMode="unavailable";
const fixture=await protocolFixture({serverAdmin:true,fileStores() {
  if(storesMode==='unavailable') return {code:'BAD_REQUEST',said:'FileStores temporarily unavailable'};
  return {code:'OK',payload:{stores: storesMode==='empty' ? [] : [
    {alias:'applications',role:'MANAGER'}, {alias:'reports',role:'CONTRIBUTOR'}, {alias:'archive',role:'VIEWER'}
  ]}};
},applicationDeployment(type,payload) {
  if(type==='application.deployment.status') return refuseStatus ? {code:'BAD_REQUEST',said:'Status temporarily unavailable'} : {code:'OK',payload:{project:payload.project,activeRevision:receipt?.release.revision??null,releases:receipt?[receipt.release]:[]}};
  if(type==='application.deploy') {receipt={project:payload.project,requestId:payload.requestId,release:{revision,digest:'a'.repeat(64),fileCount:payload.files.length}};return {code:'OK',payload:{}};}
  if(type==='application.deployment.receipt') return {code:'OK',payload:receipt};
  if(type==='application.activate') return {code:'OK',payload:{...receipt,requestId:payload.requestId}};
}});
let app;
try {
  app=await electron.launch({executablePath,args:[resolve('.')],env});
  await app.evaluate(({dialog},folder)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[folder]});},source);
  const page=await app.firstWindow(),errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.locator('#connect-sidebar').click();await page.locator('#server-url').fill(fixture.base);await page.locator('#handle').fill('fixture');await page.locator('#password').fill('fixture-password');await page.locator('#submit-connection').click();
  await expect(page.locator('#connection-label')).toHaveText(/ · Connected$/);
  await page.locator('#application-deploy-open').click();
  const dialog=page.locator('#application-deployment-dialog'); await expect(dialog).toBeVisible();
  await expect(dialog.locator('[data-stores-status]')).toContainText('could not be loaded');
  await expect(dialog.locator('[data-store]')).toBeDisabled();
  assert.equal(fixture.frames.filter(frame=>frame.type==='application.deploy').length,0);
  storesMode='empty';await dialog.locator('[data-reload-stores]').click();
  await expect(dialog.locator('[data-stores-status]')).toContainText('No FileStores grant');
  await expect(dialog.locator('[data-store]')).toBeDisabled();
  storesMode='granted';await dialog.locator('[data-reload-stores]').click();
  await expect(dialog.locator('[data-store] option')).toHaveText(['Choose a FileStore','applications']);
  await dialog.locator('[data-add-area]').click();
  await dialog.locator('[data-area-store]').selectOption('reports');
  await dialog.locator('[data-area-path]').fill('demo/reports');
  await dialog.locator('[data-project]').fill('demo');await dialog.locator('[data-store]').selectOption('applications');await dialog.locator('[data-path]').fill('demo');
  await dialog.locator('[data-status]').click();await expect(dialog.locator('[data-active]')).toContainText('No active deployment');
  refuseStatus=true;await dialog.locator('[data-status]').click();await expect(dialog.locator('[data-error]')).toContainText('temporarily unavailable');refuseStatus=false;
  await dialog.locator('[data-choose]').click();await expect(dialog.locator('[data-package]')).toContainText('2 source files');
  await dialog.locator('button[type=submit]').click();await expect(dialog.locator('[data-error]')).toContainText('unreadable');
  storesMode='unavailable';
  await page.reload();await expect(page.locator('#connection-label')).toHaveText(/ · Connected$/);await page.locator('#application-deploy-open').click();
  await expect(dialog.locator('[data-request]')).toContainText(receipt.requestId);
  await expect(dialog.locator('[data-stores-status]')).toContainText('could not be loaded');
  await dialog.locator('[data-choose]').click();await expect(dialog.locator('[data-error]')).toContainText('pending receipt');
  await dialog.locator('[data-recover]').click();await expect(dialog.locator('[data-result]')).toContainText(`Committed revision: ${revision}`);await expect(dialog.locator('[data-revision] option')).toHaveCount(1);
  await dialog.locator('[data-activate]').click();await expect(dialog.locator('[data-result]')).toContainText(`Activated revision: ${revision}`);
  storesMode='granted';await dialog.locator('[data-reload-stores]').click();
  await dialog.locator('[data-path]').fill('demo');
  await dialog.locator('[data-add-area]').click();await dialog.locator('[data-area-store]').selectOption('reports');await dialog.locator('[data-area-path]').fill('demo/reports');
  await page.setViewportSize({width:920,height:680});
  await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  assert.equal(await dialog.evaluate(el=>el.getBoundingClientRect().height<=innerHeight),true);
  await mkdir('build/smoke',{recursive:true});await page.screenshot({path:'build/smoke/application-deployment.png'});
  assert.equal(fixture.frames.filter(frame=>frame.type==='application.deploy').length,1);
  const submitted=fixture.frames.find(frame=>frame.type==='application.deploy');
  assert.deepEqual(submitted.payload.destination,{store:'applications',path:'demo'});
  assert.deepEqual(submitted.payload.writableAreas,[{store:'reports',path:'demo/reports'}]);
  assert.deepEqual(submitted.payload.files.map(file=>file.path),['agents/worker.md','plowshare.json']);
  assert.equal(fixture.frames.filter(frame=>frame.type==='application.activate').length,1);
  assert.deepEqual(errors,[]);
  console.log('Application deployment Desktop smoke passed: FileStore failure/reload/no-grant states, permission-filtered selectors, folder snapshot, status-error states, retained receipt recovery after reload despite failed catalogue discovery, no replay, activation and compact layout.');
} finally {await app?.close();await fixture.close();await rm(profile,{recursive:true,force:true});}

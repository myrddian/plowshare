import {_electron as electron,expect} from 'playwright/test';
import executablePath from 'electron';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import assert from 'node:assert/strict';
import {protocolFixture} from './protocol-fixture.mjs';
const directory=await mkdtemp(join(tmpdir(),'plowshare-project-access-'));
const fixture=await protocolFixture({serverAdmin:false});
fixture.addServerProject({name:'Integration',role:'MANAGER',workspace:'/fixture/integration',type:'DISJOINT',members:['fixture']});
fixture.addServerProject({name:'Read only work',role:'VIEWER',workspace:'/fixture/read',members:['fixture']});
const env={...process.env,PLOWSHARE_CONFIG_DIR:directory,PLOWSHARE_DESKTOP_PROFILE:join(directory,'profile')};delete env.ELECTRON_RUN_AS_NODE;delete env.PLOWSHARE_DESKTOP_CONFIG;
let app;
try {
  app=await electron.launch({executablePath,args:[resolve('.')],env});const page=await app.firstWindow();
  await page.locator('#connection-button').click();await page.locator('#server-url').fill(fixture.base);await page.locator('#handle').fill('fixture');await page.locator('#password').fill('fixture-password');await page.locator('#submit-connection').click();
  await expect(page.locator('#connection-label')).toHaveText('Connected');await expect(page.locator('#server-admin-open')).toBeHidden();
  await page.locator('[data-project-access="Integration"]').click();await expect(page.locator('#project-access-dialog [data-role]')).toContainText('manager');
  await page.locator('#project-access-dialog input[name="handle"]').fill('worker');await page.locator('#project-access-dialog select[name="role"]').selectOption('VIEWER');await page.locator('#project-access-dialog [data-add] button').click();
  await expect(page.locator('#project-access-dialog [data-members]')).toContainText('worker');assert.equal(fixture.frames.filter(row=>row.type==='project.member.add').length,1);
  await page.screenshot({path:'/tmp/plowshare-regular-manager-access.png'});await page.locator('#project-access-dialog [data-close]').click();
  await page.locator('[data-project="Read only work"]').click();await expect(page.locator('#new-conversation')).toBeDisabled();await expect(page.locator('#send')).toBeDisabled();
  await page.locator('[data-project-access="Read only work"]').click();await expect(page.locator('#project-access-dialog [data-role]')).toContainText('viewer');await expect(page.locator('#project-access-dialog [data-add]')).toBeHidden();assert.equal(await page.locator('#project-access-dialog [data-handle]').count(),0);
  console.log('Regular-user project access smoke passed: Manager grants without server admin, Viewer inspection and disabled write controls.');
} finally {if(app)await app.close();await fixture.close();await rm(directory,{recursive:true,force:true});}

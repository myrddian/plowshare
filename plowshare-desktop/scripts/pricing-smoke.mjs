import {_electron as electron,expect} from 'playwright/test';
import executablePath from 'electron';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import assert from 'node:assert/strict';
import {protocolFixture} from './protocol-fixture.mjs';
const directory=await mkdtemp(join(tmpdir(),'plowshare-pricing-'));
const fixture=await protocolFixture({serverAdmin:true});
const env={...process.env,PLOWSHARE_CONFIG_DIR:directory,PLOWSHARE_DESKTOP_PROFILE:join(directory,'profile')};delete env.ELECTRON_RUN_AS_NODE;delete env.PLOWSHARE_DESKTOP_CONFIG;
let app;
try {
  app=await electron.launch({executablePath,args:[resolve('.')],env});const page=await app.firstWindow();
  await page.locator('#connection-button').click();await page.locator('#server-url').fill(fixture.base);await page.locator('#handle').fill('fixture');await page.locator('#password').fill('fixture-password');await page.locator('#submit-connection').click();
  await expect(page.locator('#connection-label')).toHaveText('Connected');await page.locator('#server-admin-open').click();await page.locator('#server-admin-dialog [data-pricing]').click();
  const dialog=page.locator('#pricing-admin-dialog');await expect(dialog.locator('[data-target]')).toHaveText('hosted / deployment');
  await expect(dialog.locator('[name="mode"]')).toHaveValue('UNPRICED');
  await dialog.locator('[name="mode"]').selectOption('TOKEN');await dialog.locator('[name="currency"]').fill('USD');await dialog.locator('[name="input"]').fill('0.40');await dialog.locator('[name="output"]').fill('1.60');await dialog.locator('[name="cacheRead"]').fill('0.10');await dialog.locator('[data-edit] button').click();
  await expect(dialog.locator('[data-saved]')).toContainText('Pricing saved');await expect(dialog.locator('[data-origin]')).toContainText('override');
  assert.deepEqual(fixture.frames.find(frame=>frame.type==='admin.pricing.set').payload.rates,{input:'0.40',output:'1.60',cacheRead:'0.10'});
  await page.screenshot({path:'/tmp/plowshare-pricing-admin.png'});
  const prior=fixture.frames.filter(frame=>frame.type==='admin.pricing.set').length;
  await dialog.locator('[name="input"]').fill('-1');await dialog.locator('[data-edit] button').click();await expect(dialog.locator('[data-error]')).toContainText('decimal strings');assert.equal(fixture.frames.filter(frame=>frame.type==='admin.pricing.set').length,prior);
  await dialog.locator('[data-usage]').click();await expect(dialog).toBeHidden();await expect(page.locator('#title-caption')).toHaveText('Usage');
  console.log('Pricing smoke passed: view configured model, save decimal input/output/cache rates, reject invalid input, and navigate to usage statistics.');
} finally {if(app)await app.close();await fixture.close();await rm(directory,{recursive:true,force:true});}

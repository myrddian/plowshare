import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';

const directory = await mkdtemp(join(tmpdir(), 'plowshare-filestores-ui-'));
const config = join(directory, 'config'), root = join(directory, 'applications');
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: config, PLOWSHARE_DESKTOP_PROFILE: join(directory, 'profile') };
for (const key of ['ELECTRON_RUN_AS_NODE', 'PLOWSHARE_DESKTOP_CONFIG', 'PLOWSHARE_CONNECTION', 'PLOWSHARE_HANDLE', 'PLOWSHARE_PASSWORD', 'PLOWSHARE_TOKEN', 'PLOWSHARE_URL']) delete env[key];
await mkdir('build/smoke', { recursive: true });
let app;
const launch = async () => {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const page = await app.firstWindow();
  await page.evaluate(() => window.plowshare.request({ action: 'bootstrap' }));
  return page;
};
try {
  let page = await launch();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await expect(page.locator('#filestore-notice')).toBeVisible();
  await page.locator('#filestore-open').click();
  await page.locator('#filestore-alias').fill('applications');
  await page.locator('#filestore-root').fill(root);
  await page.locator('#filestore-create').click();
  await expect(page.locator('#filestore-dialog')).toBeHidden();
  await expect(page.locator('#filestore-notice')).toBeHidden();
  const saved = JSON.parse(await readFile(join(config, 'config.json'), 'utf8'));
  assert.deepEqual(saved.connections, []);
  assert.deepEqual(saved.fileStoreDefault, { alias: 'applications', root });
  const source = await readFile(join(config, 'filestore.js'), 'utf8');
  // A host definition alone never logs in, claims files, or creates connection state.
  const state = await page.evaluate(async () => (await window.plowshare.request({ action: 'bootstrap' })).state);
  assert.equal(state.connected, false);
  assert.equal(state.files.status, 'off');
  await writeFile(join(config, 'filestore.js'), 'export default {invalid:true};');
  await page.evaluate(() => window.plowshare.request({ action: 'filestore-load' }));
  await expect(page.locator('#filestore-notice')).toBeVisible();
  await page.locator('#filestore-open').click();
  await expect(page.locator('#filestore-status')).toContainText('could not load');
  await expect(page.locator('#filestore-form')).toBeHidden();
  assert.equal(await readFile(join(config, 'filestore.js'), 'utf8'), 'export default {invalid:true};');
  assert.equal(await readFile(join(config, 'config.json'), 'utf8'), JSON.stringify(saved) + '\n');
  await writeFile(join(config, 'filestore.js'), source);
  await rm(root, { recursive: true });
  await page.locator('#filestore-reload').click();
  await expect(page.locator('#filestore-list')).toContainText(root);
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(760, 660));
  await page.locator('#sidebar-toggle').click({ force: true });
  const box = await page.locator('#filestore-dialog').boundingBox();
  const bounds = await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].getContentBounds());
  assert.ok(box && box.x >= 0 && box.x + box.width <= bounds.width + 1, 'FileStore dialog fits native resized window');
  await page.screenshot({ path: 'build/smoke/filestores-narrow.png' });
  assert.deepEqual(errors, []);
  await app.close(); app = undefined;
  await rm(join(config, 'filestore.js'));
  page = await launch();
  await expect(page.locator('#filestore-notice')).toBeHidden();
  const restarted = await page.evaluate(async () => (await window.plowshare.request({ action: 'bootstrap' })).state);
  assert.equal(restarted.localFileStores.status, 'loaded');
  assert.equal(restarted.localFileStores.stores[0].alias, 'applications');
  console.log('PASS: FileStore setup, bootstrap restart, missing-directory recreation, invalid-config preservation, no remote grants, and native window/sidebar resizing.');
} finally {
  if (app) await app.close();
  await rm(directory, { recursive: true, force: true });
}

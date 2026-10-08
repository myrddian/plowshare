import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const profile = await mkdtemp(join(tmpdir(), 'plowshare-storage-'));
const fixture = await protocolFixture({ serverAdmin: true });
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'), PLOWSHARE_DESKTOP_PROFILE: profile, PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config') };
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const page = await app.firstWindow();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  await page.locator('#server-project-add').click();
  await page.locator('#server-project-filestore').check();
  await page.locator('#server-project-name').fill('Chatbot');
  const placement = page.locator('#server-project-placement');
  await placement.locator('[data-store]').selectOption('applications');
  await expect(placement.locator('[data-store] option')).toHaveText(['Choose a FileStore', 'applications']);
  await placement.locator('[data-path]').fill('chatbot');
  await placement.locator('[data-add-area]').click();
  await placement.locator('[data-area-store]').selectOption('reports');
  await placement.locator('[data-area-path]').fill('reports');
  await page.locator('#server-project-type').selectOption('DISJOINT');
  await expect(placement.locator('[data-area-store]')).toHaveValue('reports');
  await expect(page.locator('#server-project-path-note')).toContainText('Choose a server FileStore');
  await placement.locator('[data-area-path]').fill('../private');
  await page.locator('#server-project-form button[type="submit"]').click();
  await expect(page.locator('#server-project-error')).toBeVisible();
  assert.equal(fixture.frames.filter(frame => frame.type === 'application.create').length, 0);
  await placement.locator('[data-area-path]').fill('reports');
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(900, 700));
  await expect(page.locator('#server-project-form button[type="submit"]')).toBeVisible();
  await page.locator('#server-project-form button[type="submit"]').click();
  await expect(page.locator('#server-project-dialog')).toBeHidden();
  assert.deepEqual(fixture.frames.find(frame => frame.type === 'application.create').payload, {
    name: 'Chatbot', type: 'DISJOINT', applicationRoot: { store: 'applications', path: 'chatbot' }, writableAreas: [{ store: 'reports', path: 'reports' }],
  });
  assert.equal(fixture.frames.filter(frame => frame.type === 'project.create').length, 0);
  await expect(page.locator('#application-conversations [data-project="Chatbot"]')).toBeVisible();
  await page.locator('#server-project-add').click();
  await expect(page.locator('#server-project-filestore')).not.toBeChecked();
  await expect(page.locator('#server-project-writes')).toHaveValue('.');
  await page.locator('#server-project-filestore').check();
  await expect(placement.locator('[data-store]')).toHaveValue('applications');
  await page.locator('#server-project-name').fill('Stale');
  await page.evaluate(() => window.plowshare.request({action:'disconnect'}));
  await page.locator('#server-project-form button[type=submit]').click();
  await expect(page.locator('#server-project-error')).toContainText('connection changed');
  assert.equal(fixture.frames.filter(frame => frame.type === 'application.create').length,1);
  await page.locator('#server-project-close').click();
  await page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  await page.locator('#server-project-add').click();
  await expect(page.locator('#server-project-filestore')).not.toBeChecked();
  assert.deepEqual(errors, []);
  console.log('Application storage creation smoke passed: permission-filtered FileStore dropdowns, invalid relative-path refusal, mode changes, connection-change refusal, cancellation reset and native resize.');
} finally {
  await app?.close(); await fixture.close(); await rm(profile, { recursive: true, force: true });
}

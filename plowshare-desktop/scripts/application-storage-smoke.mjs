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
  await page.locator('#server-project-workspace').fill('applications/chatbot');
  await page.locator('#server-project-writes').fill('outputs/reports');
  await page.locator('#server-project-type').selectOption('DISJOINT');
  await expect(page.locator('#server-project-writes')).toHaveValue('outputs/reports');
  await expect(page.locator('#server-project-path-note')).toContainText('server FileStore alias');
  await page.locator('#server-project-writes').fill('outputs/../private');
  await page.locator('#server-project-form button[type="submit"]').click();
  await expect(page.locator('#server-project-error')).toBeVisible();
  assert.equal(fixture.frames.filter(frame => frame.type === 'application.create').length, 0);
  await page.locator('#server-project-writes').fill('outputs/reports');
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(900, 700));
  await expect(page.locator('#server-project-form button[type="submit"]')).toBeVisible();
  await page.locator('#server-project-form button[type="submit"]').click();
  await expect(page.locator('#server-project-dialog')).toBeHidden();
  assert.deepEqual(fixture.frames.find(frame => frame.type === 'application.create').payload, {
    name: 'Chatbot', type: 'DISJOINT', applicationRoot: { store: 'applications', path: 'chatbot' }, writableAreas: [{ store: 'outputs', path: 'reports' }],
  });
  assert.equal(fixture.frames.filter(frame => frame.type === 'project.create').length, 0);
  await expect(page.locator('#application-conversations [data-project="Chatbot"]')).toBeVisible();
  await page.locator('#server-project-add').click();
  await expect(page.locator('#server-project-filestore')).not.toBeChecked();
  await expect(page.locator('#server-project-writes')).toHaveValue('.');
  await page.locator('#server-project-filestore').check();
  await page.locator('#server-project-close').click();
  await page.locator('#server-project-add').click();
  await expect(page.locator('#server-project-filestore')).not.toBeChecked();
  assert.deepEqual(errors, []);
  console.log('Application storage creation smoke passed: strict aliases, no legacy fallback, mode changes, cancellation reset and native resize.');
} finally {
  await app?.close(); await fixture.close(); await rm(profile, { recursive: true, force: true });
}

import { _electron as electron, expect } from 'playwright/test';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';

const profile = await mkdtemp(join(tmpdir(), 'plowshare-origins-'));
const env = { ...process.env, PLOWSHARE_DESKTOP_PROFILE: profile,
  PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'),
  PLOWSHARE_CONFIG_DIR: join(profile, 'credentials') };
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  await mkdir('build/smoke', { recursive: true });
  app = await electron.launch({ executablePath: (await import('electron')).default,
    args: [resolve('.')], env });
  const page = await app.firstWindow();
  await expect(page.locator('#chat-title')).toHaveText('The peaceful atom');
  const opened = app.waitForEvent('window');
  await page.locator('#trajectory-open').click();
  const trajectory = await opened;
  const errors = [];
  trajectory.on('pageerror', error => errors.push(error.message));
  await trajectory.locator('.trajectory-row.person').first().click();
  for (const width of [1440, 900]) {
    await app.evaluate(({ BrowserWindow }, size) => BrowserWindow.getAllWindows()[0].setSize(size, 700), width);
    await expect(trajectory.locator('#trajectory-detail-content')).toContainText('person · demo');
    await expect(trajectory.locator('#trajectory-detail-content')).toContainText('demo-job');
    const handle = trajectory.locator('.pane-resizer[data-pane="trajectory"]:visible');
    await handle.press('Home');
    await handle.press('ArrowRight');
    await expect(trajectory.locator('#trajectory-detail-content')).toContainText('person · demo');
    assert.equal(await trajectory.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  }
  const sidebar = page.locator('.pane-resizer[data-pane="sidebar"]:visible');
  await sidebar.press('Home');
  await sidebar.press('ArrowRight');
  await expect(trajectory.locator('#trajectory-detail-content')).toContainText('person · demo');
  await trajectory.screenshot({ path: 'build/smoke/trajectory-origins.png' });
  assert.deepEqual(errors, []);
  console.log('Origin detail passed in native trajectory windows and resized panes at 1440 and 900 pixels.');
} finally {
  await app?.close();
  await rm(profile, { recursive: true, force: true });
}

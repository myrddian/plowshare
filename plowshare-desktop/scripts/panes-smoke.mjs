import { _electron as electron, expect } from 'playwright/test';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';

const profile = await mkdtemp(join(tmpdir(), 'plowshare-panes-'));
const packaged = process.env.PLOWSHARE_PACKAGED_EXECUTABLE;
const executablePath = packaged ?? (await import('electron')).default;
const env = { ...process.env, PLOWSHARE_DESKTOP_PROFILE: profile, PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'), PLOWSHARE_CONFIG_DIR: join(profile, 'credentials') };
delete env.ELECTRON_RUN_AS_NODE;
await mkdir('build/smoke', { recursive: true });
let app;
const errors = [];
const launch = () => electron.launch({ executablePath, args: packaged ? [] : [resolve('.')], env });
const handleOf = (page, key) => page.locator(`.pane-resizer[data-pane="${key}"]:visible`);
const width = (page, selector) => page.locator(selector).evaluate(node => Math.round(node.getBoundingClientRect().width));
async function drag(page, key, pixels) {
  const handle = handleOf(page, key);
  await expect(handle).toBeVisible();
  const box = await handle.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + pixels, box.y + box.height / 2, { steps: 8 });
  await page.mouse.up();
}
try {
  app = await launch();
  let page = await app.firstWindow();
  page.on('pageerror', error => errors.push(error.message));
  await expect(page.locator('#chat-title')).toHaveText('The peaceful atom');
  const sidebarDefault = await width(page, '#sidebar');
  const contextDefault = await width(page, '#inspector');
  await drag(page, 'sidebar', 80);
  await expect.poll(() => width(page, '#sidebar')).toBe(sidebarDefault + 80);
  await drag(page, 'context', -80);
  await expect.poll(() => width(page, '#inspector')).toBe(contextDefault + 80);
  await page.screenshot({ path: 'build/smoke/resized-panes.png' });
  // Closing the native app verifies durable widths, rather than only renderer state.
  await app.close();
  app = await launch(); page = await app.firstWindow();
  page.on('pageerror', error => errors.push(error.message));
  await expect(page.locator('#chat-title')).toHaveText('The peaceful atom');
  await expect.poll(() => width(page, '#sidebar')).toBe(sidebarDefault + 80);
  await expect.poll(() => width(page, '#inspector')).toBe(contextDefault + 80);
  const sidebarHandle = handleOf(page, 'sidebar');
  await sidebarHandle.press('ArrowRight');
  await expect.poll(() => width(page, '#sidebar')).toBe(sidebarDefault + 96);
  await handleOf(page, 'context').press('ArrowRight');
  await expect.poll(() => width(page, '#inspector')).toBe(contextDefault + 64);
  await sidebarHandle.press('End');
  await expect.poll(() => width(page, '#sidebar')).toBe(Number(await sidebarHandle.getAttribute('aria-valuemax')));
  await page.locator('#sidebar-toggle').click();
  await expect(sidebarHandle).toBeHidden();
  await page.locator('#sidebar-toggle').click();
  await expect(sidebarHandle).toBeVisible();
  await page.locator('#expand').click();
  await expect(handleOf(page, 'context')).toBeHidden();
  await page.locator('#expand').click();
  await expect(handleOf(page, 'context')).toBeVisible();
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(900, 700));
  await expect(page.locator('#inspector')).toBeHidden();
  await page.locator('#inspector-toggle').click();
  await expect(handleOf(page, 'context')).toBeVisible();
  const chatWidth = await width(page, '#chat');
  const drawerWidth = await width(page, '#inspector');
  await drag(page, 'context', -60);
  await expect.poll(() => width(page, '#inspector')).toBe(drawerWidth + 60);
  assert.equal(await width(page, '#chat'), chatWidth, 'Drawer resizing must not squeeze the chat.');
  await handleOf(page, 'context').press('End');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'Pane bounds prevent horizontal overflow.');
  await page.screenshot({ path: 'build/smoke/resized-drawer.png' });
  await page.locator('#inspector-close').click();
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(1440, 960));
  await expect.poll(() => width(page, '#sidebar')).toBe(480);
  await sidebarHandle.dblclick();
  await expect.poll(() => width(page, '#sidebar')).toBe(sidebarDefault);
  await page.locator('#inspector-toggle').click();
  await handleOf(page, 'context').dblclick();
  await expect.poll(() => width(page, '#inspector')).toBe(contextDefault);
  for (const [button, key, pane] of [
    ['#runs-open', 'activity', '.activity-list-column'],
    ['#trajectory-open', 'trajectory', '.trajectory-log'],
    ['#board-open', 'board', '#board-browser'],
    ['#library-open', 'library', '#library-results'],
  ]) {
    const opened = app.waitForEvent('window');
    await page.locator(button).click();
    const child = await opened;
    child.on('pageerror', error => errors.push(error.message));
    if (key === 'library') await child.locator('#library-documents').click();
    const handle = handleOf(child, key);
    await expect(handle).toBeVisible();
    const before = await width(child, pane);
    await drag(child, key, 60);
    await expect.poll(() => width(child, pane)).toBe(before + 60);
    await child.reload();
    await expect.poll(() => width(child, pane)).toBe(before + 60);
    await handle.press('Home');
    await expect.poll(() => width(child, pane)).toBe(Number(await handle.getAttribute('aria-valuemin')));
    await handle.dblclick();
    await expect.poll(() => width(child, pane)).toBe(before);
    if (key === 'library') {
      await child.locator('#library-sources').click();
      await expect(handle).toBeHidden();
      const sourcePane = '#library-source-panel .information-list-pane';
      await expect(handleOf(child, 'sources')).toBeVisible();
      const sourceWidth = await width(child, sourcePane);
      await drag(child, 'sources', 60);
      await expect.poll(() => width(child, sourcePane)).toBe(sourceWidth + 60);
      await handleOf(child, 'sources').dblclick();
      await expect.poll(() => width(child, sourcePane)).toBe(sourceWidth);
    }
    await child.close();
  }
  assert.deepEqual(errors, []);
  console.log('Pane resizing passed: native drag, keyboard bounds, restart persistence, reset, collapsed/expanded panes, compact drawer and all inspection pages.');
} finally {
  await app?.close();
  await rm(profile, { recursive: true, force: true });
}

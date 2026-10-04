import { _electron as electron, expect } from 'playwright/test';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const profile = await mkdtemp(join(tmpdir(), 'plowshare-navigation-'));
const packaged = process.env.PLOWSHARE_PACKAGED_EXECUTABLE;
const executablePath = packaged ?? (await import('electron')).default;
const env = { ...process.env, PLOWSHARE_DESKTOP_PROFILE: profile, PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'), PLOWSHARE_CONFIG_DIR: join(profile, 'credentials') };
delete env.ELECTRON_RUN_AS_NODE;
const fixture = await protocolFixture({ controls: true, conversationProject: 'Navigation test' });
fixture.addServerProject({ name: 'Navigation test' });
await mkdir('build/smoke', { recursive: true });
let app;
const errors = [];
try {
  app = await electron.launch({ executablePath, args: packaged ? [] : [resolve('.')], env });
  const main = await app.firstWindow();
  main.on('pageerror', error => errors.push(error.message));
  const request = value => main.evaluate(value => window.plowshare.request(value), value);
  const nativeWindows = () => app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length);
  await expect(main.locator('#chat-title')).toHaveText('The peaceful atom');
  await main.locator('#draft').fill('A draft that stays with this conversation');
  const opening = app.waitForEvent('window');
  await main.locator('#trajectory-open').click();
  const trajectory = await opening;
  trajectory.on('pageerror', error => errors.push(error.message));
  await expect(trajectory.locator('#trajectory-rows')).toContainText('document_search');
  await expect(main.locator('.breadcrumb')).toHaveText(/Research\s*\/\s*The peaceful atom\s*\/\s*Trajectory/);
  await expect(main.locator('#chat')).toBeHidden();
  await expect(main.locator('#sidebar')).toBeVisible();
  assert.equal(await nativeWindows(), 1, 'Trajectory must not open a second native window.');
  const viewBounds = () => app.evaluate(({ BrowserWindow }, url) => {
    const view = BrowserWindow.getAllWindows()[0].contentView.children.find(view => view.webContents?.getURL() === url);
    return { bounds: view.getBounds(), visible: view.getVisible() };
  }, 'plowshare://app/trajectory.html');
  await expect.poll(async () => (await viewBounds()).visible).toBe(true);
  await trajectory.screenshot({ path: 'build/smoke/embedded-trajectory.png' });
  const hostBounds = await main.locator('#workspace-view-host').boundingBox();
  assert.deepEqual((await viewBounds()).bounds, Object.fromEntries(Object.entries(hostBounds).map(([key, value]) => [key, Math.round(value)])), 'The native view occupies the main content region.');
  const divider = main.locator('.pane-resizer[data-pane="sidebar"]');
  await divider.press('ArrowRight');
  await expect.poll(async () => (await viewBounds()).bounds.x).toBe(Math.round(hostBounds.x + 16));
  await main.locator('#connection-button').click();
  await expect(main.locator('#connection-dialog')).toBeVisible();
  await expect.poll(async () => (await viewBounds()).visible).toBe(false);
  await main.locator('#close-dialog').click();
  await expect.poll(async () => (await viewBounds()).visible).toBe(true);
  await trajectory.locator('#trajectory-search').focus();
  await app.evaluate(({ BrowserWindow }) => {
    const contents = BrowserWindow.getAllWindows()[0].contentView.children.find(view => view.webContents?.getURL() === 'plowshare://app/trajectory.html').webContents;
    contents.sendInputEvent({ type: 'keyDown', keyCode: 'K', modifiers: ['control'] });
    contents.sendInputEvent({ type: 'keyUp', keyCode: 'K', modifiers: ['control'] });
  });
  await expect(main.locator('#navigation-dialog')).toBeVisible();
  await expect.poll(async () => (await viewBounds()).visible).toBe(false);
  await main.keyboard.press('Escape');
  await expect(main.locator('#navigation-dialog')).toBeHidden();
  await expect.poll(async () => (await viewBounds()).visible).toBe(true);
  await assert.rejects(trajectory.evaluate(() => window.plowshare.request({ action: 'run', conversation: 'demo-research', agent: 'plowshare', text: 'Forbidden' })), /only read their own conversation/);
  await assert.rejects(trajectory.evaluate(() => window.plowshare.request({ action: 'workspace-chat' })), /main window/);
  await main.locator('#title-caption').click();
  await expect(main.locator('#chat')).toBeVisible();
  await expect(main.locator('#draft')).toHaveValue('A draft that stays with this conversation');
  await expect(main.locator('#view-caption')).toBeHidden();
  // A late page load must not steal navigation from a more recent selection.
  const lateOpening = app.waitForEvent('window');
  await request({ action: 'activity', view: 'inbox' });
  await request({ action: 'workspace-chat' });
  await expect(main.locator('#chat')).toBeVisible();
  const latePage = await lateOpening;
  await expect(latePage.locator('#activity-title')).toBeVisible();
  await expect(main.locator('#chat')).toBeVisible();
  await request({ action: 'connect', base: fixture.base, handle: 'fixture', password: 'fixture-password' });
  const opened = new Map();
  for (const [button, label, title] of [
    ['#inbox-open', 'Mailbox', '#activity-title'],
    ['#library-open', 'Library', 'h1'],
    ['#board-open', 'Board', '#board-title'],
  ]) {
    const next = app.waitForEvent('window'); await main.locator(button).click();
    const pane = await next; pane.on('pageerror', error => errors.push(error.message));
    opened.set(label, pane);
    await expect(pane.locator(title)).toBeVisible();
    await expect(main.locator('#title-caption')).toHaveText(label);
    await expect(main.locator(button)).toHaveAttribute('aria-current', 'page');
    assert.equal(await nativeWindows(), 1, `${label} stays in the main window.`);
  }
  for (const [button,label,title] of [['#runs-open','Runs','#activity-title'],['#schedules-open','Scheduled work','#activity-title'],['#studio-open','Orchestration builder','#activity-title'],['#swarm-open','Swarm','#board-title'],['#memories-open','Memories','#library-title']]) {
    const next=app.waitForEvent('window');await main.locator(button).click();const pane=await next;opened.set(label,pane);pane.on('pageerror',error=>errors.push(error.message));
    await expect(pane.locator(title)).toBeVisible();await expect(main.locator(button)).toHaveAttribute('aria-current','page');assert.equal(await nativeWindows(),1);
    if(['Runs','Scheduled work','Swarm'].includes(label))await expect(pane.locator('.activity-tabs, #board-tabs')).toBeHidden();
  }
  await expect(opened.get('Mailbox').locator('#activity-title')).toHaveText('Mailbox');
  await expect(opened.get('Board').locator('#board-title')).toHaveText('Project board');
  await expect(opened.get('Library').locator('#library-memories')).toBeHidden();
  await expect(opened.get('Memories').locator('#library-sources')).toBeHidden();
  const studio=opened.get('Orchestration builder');await main.locator('#studio-open').click();await expect(studio.locator('.activity-tabs button:visible')).toHaveCount(2);
  await studio.locator('#activity-definitions').click();await expect(studio.locator('#activity-title')).toHaveText('Definitions');
  await assert.rejects(opened.get('Mailbox').evaluate(()=>window.plowshare.request({action:'activity-view',view:'runs'})),/workspace sidebar/);
  await assert.rejects(opened.get('Board').evaluate(()=>window.plowshare.request({action:'board-view',view:'swarm'})),/workspace sidebar/);
  await main.locator('#projects-toggle').click();await expect(main.locator('#sidebar-projects')).toBeHidden();await expect(main.locator('#projects-toggle')).toHaveAttribute('aria-expanded','false');
  await main.reload();await expect(main.locator('#sidebar-projects')).toBeHidden();await main.locator('#projects-toggle').click();await expect(main.locator('#sidebar-projects')).toBeVisible();
  await main.locator('#controls-button').click();
  await expect(main.locator('#workspace-manage .operator-dialog')).toBeVisible();
  await expect(main.locator('#title-caption')).toHaveText('Manage work');
  assert.equal(await main.locator('.operator-dialog').evaluate(node => node.matches(':modal')), false, 'Management is a workspace page.');
  await expect(main.locator('.operator-dialog [data-change]')).toBeVisible();
  await main.keyboard.press('Control+k');
  await expect(main.locator('#navigation-dialog')).toBeVisible();
  await main.keyboard.press('Escape');
  await main.locator('.operator-dialog [data-close]').click();
  await expect(main.locator('#chat')).toBeVisible();
  await main.locator('[data-project="Navigation test"]').click();
  await main.locator('#conversations [data-conversation="fixture-first"]').click();
  await expect(main.locator('#chat-title')).toHaveText('Fixture conversation');
  await main.locator('#inbox-open').click();
  const activity = opened.get('Mailbox');
  assert.equal(fixture.frames.some(frame => frame.type === 'inbox.read'), false, 'Opening embedded Mailbox never marks a message read.');
  await assert.rejects(activity.evaluate(() => window.plowshare.request({ action: 'run', conversation: 'fixture-first', agent: 'fixture-bot', text: 'Forbidden' })), /Activity windows can only/);
  await request({ action: 'trajectory', conversation: 'fixture-first' });
  await expect(main.locator('.breadcrumb')).toHaveText(/Navigation test\s*\/\s*Fixture conversation\s*\/\s*Trajectory/);
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(900, 700));
  await expect.poll(() => app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].contentView.children.find(view => view.webContents && view.getVisible()).getBounds().width)).toBeLessThan(900);
  assert.equal(await main.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await main.locator('#scope-caption').click();
  await expect(main.locator('#chat')).toBeVisible();
  assert.equal(await nativeWindows(), 1);
  assert.equal(fixture.frames.some(frame => ['agent.run', 'information.ask', 'memory.write'].includes(frame.type)), false, 'Navigation never starts paid work or writes content.');
  assert.deepEqual(errors, []);
  console.log('Workspace navigation passed: one native window, embedded sidebar sections, clickable trajectory breadcrumbs, preserved drafts, pane resizing, modal/shortcut layering, and guarded IPC.');
} finally {
  await app?.close(); await fixture.close(); await rm(profile, { recursive: true, force: true });
}

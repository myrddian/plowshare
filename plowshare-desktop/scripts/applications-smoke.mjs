import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const profile = await mkdtemp(join(tmpdir(), 'plowshare-applications-'));
const fixture = await protocolFixture({ serverAdmin: false });
fixture.addServerProject({ name: 'Chatbot', kind: 'application', type: 'DISJOINT', role: 'VIEWER', readOnly: true, workspace: '/fixture/chatbot' });
const outputArea = { store: 'outputs', path: 'reports' };
fixture.addServerProject({ name: 'Managed app', kind: 'application', type: 'MANAGED', role: 'CONTRIBUTOR', applicationRoot: {store:'applications',path:'chatbot'}, writableAreas:[{store:'applications',path:'chatbot'},outputArea] });
fixture.addServerProject({ name: 'Remote files', kind: 'project', role: 'CONTRIBUTOR' });
fixture.setApplicationFile('Chatbot', 'notes.md', 'Viewer source');
fixture.setApplicationFile('Chatbot', 'agents/guide.md', 'Agent guide');
fixture.setApplicationFile('Managed app', 'notes.md', 'Editable source', true);
fixture.setApplicationFile('Managed app', 'notes.md', 'Generated report', true, outputArea);
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'), PLOWSHARE_DESKTOP_PROFILE: profile, PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config') };
delete env.ELECTRON_RUN_AS_NODE;
await mkdir('build/smoke', { recursive: true });
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const page = await app.firstWindow();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  await expect(page.locator('#application-conversations [data-project="Chatbot"]')).toBeVisible();
  const chatbotLocation = page.locator('#application-conversations [data-project="Chatbot"] .project-location');
  await expect(chatbotLocation).toContainText('Active on server');
  await expect(chatbotLocation).not.toContainText('DISJOINT · no sync');
  await page.locator('#application-conversations [data-project-access="Chatbot"]').click();
  // An Application's membership opens in its hub, not a dialog.
  await expect(page.locator('#project-access-dialog')).toBeHidden();
  await expect(page.locator('#application-panel nav [data-hub="access"]')).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#application-panel [data-access] [data-project]')).toHaveText('Chatbot');
  await expect(page.locator('#conversations [data-project="Remote files"]')).toBeVisible();
  await expect(page.locator('#conversations [data-project="Chatbot"]')).toHaveCount(0);
  await expect(page.locator('#application-conversations [data-project="Managed app"]')).toBeVisible();
  await expect(page.locator('#conversations [data-project="Managed app"]')).toHaveCount(0);
  await expect(page.locator('#application-conversations [data-project="Remote files"]')).toHaveCount(0);
  await expect(page.locator('#applications-toggle')).toContainText('Applications');
  await expect(page.locator('#projects-toggle')).toContainText('Projects');
  await expect(page.locator('#projects-toggle')).not.toContainText('Externals');
  await page.locator('[data-project="Chatbot"]').click();
  await expect(page.locator('#application-panel')).toBeVisible();
  const hubNav = page.locator('#application-panel nav[aria-label="Application views"]');
  // The hub lands on Activity; there is no Overview.
  await expect(hubNav.locator('[data-hub="activity"]')).toHaveAttribute('aria-current', 'page');
  await expect(hubNav.getByRole('button', { name: 'Overview', exact: true })).toHaveCount(0);
  for (const name of ['Activity', 'Schedules', 'Relay', 'Files', 'Runtime', 'Memory', 'Boards', 'Access'])
    await expect(hubNav.getByRole('button', { name, exact: true })).toBeVisible();
  await expect(hubNav.getByRole('button', { name: 'Conversations', exact: true })).toHaveCount(0);
  await expect(hubNav.getByRole('button', { name: 'Runs', exact: true })).toHaveCount(0);
  await page.locator('#application-panel nav [data-hub="activity"]').click();
  await expect(page.locator('#application-panel [data-activity]')).toBeVisible();
  await expect(page.locator('#application-panel [data-activity-tree]')).toBeVisible();
  await expect(page.locator('#application-panel [data-activity-tree]')).toContainText('No LLM activity for this Application yet.');
  await expect(page.locator('#application-panel [data-activity-hide-documents]')).not.toBeChecked();
  await expect(page.locator('#application-panel [data-activity-order]')).toHaveValue('newest');
  // Runtime, Memory and Boards are hub views hosting their pages in a slot.
  for (const name of ['runtime', 'memory', 'boards']) {
    await page.locator(`#application-panel nav [data-hub="${name}"]`).click();
    await expect(page.locator('#application-panel')).toBeVisible();
    await expect(page.locator(`#application-panel [data-workspace-slot="${name}"]`)).toBeVisible();
    await expect(page.locator('#workspace-view-host')).toBeHidden();
  }
  await page.locator('#application-panel nav [data-hub="activity"]').click();
  await expect(page.locator('#application-panel [data-source]')).toBeHidden();
  await page.locator('#application-panel [data-files]').click();
  await expect(page.locator('#application-panel [data-file-browser] .file-browser-tree')).toBeVisible();
  await expect(page.locator('#application-panel [data-application-path="agents"]')).toBeVisible();
  await page.locator('#application-panel [data-application-path="agents"]').click();
  await expect(page.locator('#application-panel [data-application-path="agents/guide.md"]')).toBeVisible();
  const treeBox = await page.locator('#application-panel .file-browser-tree').boundingBox();
  await page.locator('[data-application-path="notes.md"]').click();
  await expect(page.locator('#application-panel [data-text]')).toHaveValue('Viewer source');
  await expect(page.locator('#application-panel [data-text]')).toHaveAttribute('readonly', '');
  await expect(page.locator('#application-panel [data-save]')).toBeDisabled();
  const editorBoxAfterOpen = await page.locator('#application-panel [data-text]').boundingBox();
  assert.ok(treeBox && editorBoxAfterOpen && treeBox.x + treeBox.width <= editorBoxAfterOpen.x + 1, 'Tree stays left of the editor');
  await expect(page.locator('#send')).toBeDisabled();
  await expect(page.locator('#new-conversation')).toBeDisabled();
  await page.locator('#applications-toggle').click();
  await expect(page.locator('#sidebar-applications')).toBeHidden();
  await page.reload();
  await expect(page.locator('#applications-toggle')).toHaveAttribute('aria-expanded', 'false');
  await page.locator('#applications-toggle').click();
  await expect(page.locator('#sidebar-applications')).toBeVisible();
  await page.locator('#projects-toggle').click();
  await expect(page.locator('#sidebar-projects')).toBeHidden();
  await expect(page.locator('#application-conversations [data-project="Chatbot"]')).toBeVisible();
  await page.locator('#projects-toggle').click();
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(900, 700));
  const divider = page.locator('.pane-resizer[data-pane="sidebar"]');
  await divider.press('ArrowLeft');
  await divider.press('ArrowRight');
  for (const selector of ['#applications-toggle', '#projects-toggle', '#application-conversations [data-project="Chatbot"]', '#conversations [data-project="Remote files"]']) {
    await expect(page.locator(selector)).toBeVisible();
    const box = await page.locator(selector).boundingBox();
    const sidebar = await page.locator('#sidebar').boundingBox();
    assert.ok(box.x >= sidebar.x && box.x + box.width <= sidebar.x + sidebar.width + 1, `${selector} fits the resized sidebar`);
  }
  await page.locator('[data-project="Managed app"]').click();
  await page.locator('#application-panel [data-files]').click();
  await page.locator('[data-application-path="notes.md"]').click();
  const text = page.locator('#application-panel [data-text]');
  await expect(text).toHaveValue('Editable source');
  await text.fill('Source draft');
  await page.locator('#application-panel [data-area]').selectOption({index:1});
  await page.locator('[data-application-path="notes.md"]').click();
  await expect(text).toHaveValue('Generated report');
  await text.fill('Output draft');
  await page.locator('#application-panel [data-save]').click();
  assert.deepEqual(fixture.frames.filter(frame => frame.type === 'application.file.save').at(-1).payload.location, outputArea);
  await page.locator('#application-panel [data-area]').selectOption({index:0});
  await page.locator('[data-application-path="notes.md"]').click();
  await expect(text).toHaveValue('Source draft');
  await text.fill('Draft saved on the server');
  await page.locator('#application-panel [data-save]').click();
  await expect(page.locator('#application-panel [data-save]')).toBeDisabled();
  assert.equal(fixture.frames.filter(frame => frame.type === 'application.file.save').length, 2);
  await text.fill('Keep my draft');
  fixture.setApplicationFile('Managed app', 'notes.md', 'Changed externally', true);
  await page.locator('#application-panel [data-save]').click();
  await expect(page.locator('#application-panel [data-error]')).toContainText('will not be replayed');
  await expect(text).toHaveValue('Keep my draft');
  await page.locator('#application-panel [data-read-current]').click();
  await expect(page.locator('#application-panel [data-use-current]')).toBeVisible();
  await expect(text).toHaveValue('Keep my draft');
  await page.locator('#application-panel [data-use-current]').click();
  await expect(text).toHaveValue('Changed externally');
  assert.equal(fixture.liveFileClaims.length, 0, 'Server source does not request a local file claim');
  const editorBox = await text.boundingBox(), panelBox = await page.locator('#application-panel').boundingBox();
  assert.ok(editorBox.x >= panelBox.x && editorBox.x + editorBox.width <= panelBox.x + panelBox.width + 1, 'Editor fits native resized Application pane');
  await page.screenshot({ path: 'build/smoke/applications-narrow.png' });
  fixture.setRefuseProjects(true);
  await page.locator('#refresh').click();
  await expect(page.locator('#application-conversations [role="alert"]')).toContainText('listing unavailable');
  fixture.setRefuseProjects(false);
  await page.locator('#refresh').click();
  await expect(page.locator('#application-conversations [data-project="Chatbot"]')).toBeVisible();
  for (const [view, operation] of [['runtime', 'orchestration.definitions'], ['memory', 'memory.index'], ['boards', 'board.topics']]) {
    await page.locator('[data-project="Managed app"]').click();
    await page.locator(`#application-panel nav [data-hub="${view}"]`).click();
    await expect(page.locator(`#application-panel [data-workspace-slot="${view}"]`)).toBeVisible();
    await expect.poll(() => fixture.frames.some(frame => frame.type === operation && frame.payload.project === 'Managed app')).toBe(true);
  }
  await page.locator('[data-project="Managed app"]').click();
  await expect(page.locator('#application-panel')).toBeVisible();
  const documentWith = async (urlPart, ready) => {
    let found;
    await expect.poll(async () => {
      for (const candidate of app.windows()) {
        if (!candidate.url().includes(urlPart)) continue;
        if (await ready(candidate).catch(() => false)) {
          found = candidate;
          return true;
        }
      }
      return false;
    }, { timeout: 15000 }).toBe(true);
    return found;
  };
  await page.locator('#application-panel nav [data-hub="schedules"]').click();
  const schedules = await documentWith('activity.html', async candidate => (await candidate.locator('#activity-title').textContent()) === 'Scheduled work');
  await expect(schedules.locator('#activity-list-note')).toContainText('No schedules for Managed app');
  // Scheduled work is hosted inside the hub: header and tabs stay in place.
  await expect(page.locator('#application-panel')).toBeVisible();
  await expect(page.locator('#application-panel nav [data-hub="schedules"]')).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#application-panel [data-workspace-slot="schedules"]')).toBeVisible();
  await expect(page.locator('#workspace-view-host')).toBeHidden();
  await page.locator('[data-project="Managed app"]').click();
  await expect(page.locator('#application-panel')).toBeVisible();
  await page.locator('#application-panel nav [data-hub="relay"]').click();
  const relay = await documentWith('relay.html', async candidate => (await candidate.locator('#relay-scope').inputValue()) === 'Managed app');
  await expect(relay.locator('#relay-scope')).toHaveValue('Managed app');
  await expect(page.locator('#application-panel')).toBeVisible();
  await expect(page.locator('#application-panel [data-workspace-slot="relay"]')).toBeVisible();
  await expect(page.locator('#workspace-view-host')).toBeHidden();
  const hubFiles = page.locator('#application-panel nav [data-hub="files"]');
  fixture.setPauseApplicationFiles(true);
  try {
    await page.locator('[data-project="Chatbot"]').click();
    await expect(page.locator('#application-panel')).toBeVisible();
    await page.locator('#application-panel nav [data-hub="files"]').click();
    await expect(hubFiles).toBeDisabled();
    await page.locator('#application-panel nav [data-hub="activity"]').click();
    await expect(page.locator('#application-panel')).toBeVisible();
    await expect(page.locator('#application-panel [data-activity]')).toBeVisible();
    await expect(page.locator('#application-panel [data-activity-tree]')).toBeVisible();
    await expect(page.locator('#application-panel [data-activity-tree]')).toContainText('No LLM activity for this Application yet.');
    await page.locator('#application-panel [data-close]').click();
    await expect(page.locator('#application-panel')).toBeHidden();
    await expect(page.locator('#stage #chat')).toBeVisible();
    await expect(page.locator('#chat')).not.toHaveClass(/application-nested/);
    await expect(page.locator('#scope-caption')).toHaveText('Chatbot');
    await expect(page.locator('#application-conversations [data-project="Chatbot"]')).toHaveAttribute('aria-current', 'true');
    // Opening an Application does not switch Info onto App files.
    await expect(page.locator('#info-files-tab')).toHaveAttribute('aria-selected', 'false');
    // Hub closed, Application still selected: Info offers App files, not Project sync.
    await page.locator('#files-open').click();
    await expect(page.locator('#info-files-tab')).toHaveText('App files');
    await expect(page.locator('#app-files')).toBeVisible();
    await expect(page.locator('#files-choose')).toBeHidden();
    await expect(page.locator('#project-sync')).toBeHidden();
    await expect(page.locator('#file-access-prompt')).toBeHidden();
    await expect(page.locator('#files-open')).not.toHaveAttribute('title', 'Connect a local project folder');
    await page.locator('#app-files-open').click();
    // Held application.files keeps the nav Files button disabled. Opening must still show Files.
    await expect(hubFiles).toHaveAttribute('aria-current', 'page');
    await expect(hubFiles).toBeDisabled();
    await expect(page.locator('#application-panel [data-activity]')).toBeHidden();
    await expect(page.locator('#application-panel [data-source]')).toBeVisible();
  } finally {
    fixture.setPauseApplicationFiles(false);
  }
  await page.locator('[data-project="Remote files"]').click();
  await expect(page.locator('#application-panel')).toBeHidden();
  await page.locator('#inspector-close').click();
  await page.locator('#files-open').click();
  await expect(page.locator('#info-files-tab')).toHaveText('Files');
  await expect(page.locator('#app-files')).toBeHidden();
  await expect(page.locator('#files-choose')).toBeVisible();
  await expect(page.locator('#project-sync')).toBeVisible();
  await page.locator('[data-project="Managed app"]').click();
  fixture.removeServerProject('Managed app');
  await page.locator('#refresh').click();
  await expect(page.locator('#application-panel')).toBeHidden();
  await expect(page.locator('#application-conversations [data-project="Managed app"]')).toHaveCount(0);
  assert.deepEqual(errors, []);
  console.log('Application workspace smoke passed: separate groups, server-active sidebar rows, workflow hub, App files vs Project files, application access, read-only and writable server files, stale revisions and retained drafts, scoped view shortcuts, revoked access, no local file claims and native window/sidebar resize.');
} finally {
  await app?.close();
  await fixture.close();
  await rm(profile, { recursive: true, force: true });
}

import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, readFile, writeFile, realpath, rm, rename } from 'node:fs/promises';
import { tmpdir, hostname } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const temporary = await realpath(await mkdtemp(join(tmpdir(), 'plowshare-projects-smoke-')));
const folders = ['Research', 'Writing'].map(name => join(temporary, name));
for (const folder of folders) { await mkdir(folder); await writeFile(join(folder, 'notes.md'), folder.endsWith('Research') ? 'Research notes' : 'Writing notes'); }
const config = join(temporary, 'config'); const profile = join(temporary, 'profile');
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'), PLOWSHARE_DESKTOP_PROFILE: profile, PLOWSHARE_DESKTOP_CONFIG: config };
delete env.ELECTRON_RUN_AS_NODE;
await mkdir('build/smoke', { recursive: true });
const fixture = await protocolFixture({ rotateTokens: true });
let app;
const launch = async () => { app = await electron.launch({ executablePath, args: [resolve('.')], env }); return app.firstWindow(); };
const connect = page => page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
const state = page => page.evaluate(async () => (await window.plowshare.request({ action: 'bootstrap' })).state);
try {
  let page = await launch(); await connect(page);
  // Exercise the native add flow, then add another folder without withdrawing the first.
  await app.evaluate(({ dialog }, folder) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [folder] }); }, folders[0]);
  await page.locator('#project-add').click(); await page.locator('#files-choose').click();
  await expect(page.locator('[data-project="Research"]')).toHaveAttribute('aria-current', 'true');
  await page.locator('#new-conversation').click();
  const research = (await state(page)).conversations.find(row => row.project === 'Research').id;
  await page.locator('#draft').fill('Research independently'); await page.locator('#send').click();
  await app.evaluate(({ dialog }, folder) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [folder] }); }, folders[1]);
  await page.locator('#project-add').click(); await page.locator('#files-choose').click();
  await expect(page.locator('[data-project="Writing"]')).toHaveAttribute('aria-current', 'true');
  await page.locator('#new-conversation').click();
  const writing = (await state(page)).conversations.find(row => row.project === 'Writing').id;
  await page.locator('#draft').fill('Write independently'); await page.locator('#send').click();
  await expect.poll(() => fixture.liveFileClaims.length).toBe(2);
  const claims = fixture.liveFileClaims;
  assert.equal(new Set(claims.map(row => row.session)).size, 2);
  const jobs = (await state(page)).jobs;
  assert.equal(jobs.filter(row => row.status === 'running').length, 2);
  for (const [conversation, project] of [[research, 'Research'], [writing, 'Writing']]) {
    const frame = fixture.frames.find(frame => frame.type === 'agent.run' && frame.payload.conversation === conversation);
    assert.equal(frame.payload.session, claims.find(row => row.project === project).session);
  }
  assert.deepEqual((await fixture.file({ op: 'read', path: 'notes.md' }, 'Research')).span.lines, ['Research notes']);
  assert.deepEqual((await fixture.file({ op: 'read', path: 'notes.md' }, 'Writing')).span.lines, ['Writing notes']);
  assert.equal((await fixture.file({ op: 'read', path: join(folders[1], 'notes.md') }, 'Research')).outcome, 'refused');
  await fixture.file({ op: 'write', path: 'draft.md', content: 'Research draft' }, 'Research');
  await fixture.file({ op: 'write', path: 'draft.md', content: 'Writing draft' }, 'Writing');
  assert.equal(await readFile(join(folders[0], 'draft.md'), 'utf8'), 'Research draft');
  assert.equal(await readFile(join(folders[1], 'draft.md'), 'utf8'), 'Writing draft');
  await page.locator('[data-project="Research"]').click();
  await expect(page.locator(`#conversations [data-conversation="${research}"]`)).toBeVisible();
  await expect(page.locator('#scope')).toHaveCount(0);
  await expect(page.locator('[data-project="Research"] .project-location')).toHaveText(`${claims.find(row => row.project === 'Research').machine} · ${folders[0]}`);
  await page.locator('[data-project-toggle="Research"]').click();
  await expect(page.locator('[data-project-toggle="Research"]')).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator(`#conversations [data-conversation="${research}"]`)).toBeHidden();
  await page.locator('#refresh').click();
  await expect(page.locator('[data-project-toggle="Research"]')).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('[data-project="Research"]')).toHaveAttribute('aria-current', 'true');
  await page.locator('#conversation-filter').fill('Research');
  await expect(page.locator('[data-project-toggle="Research"]')).toHaveAttribute('aria-expanded', 'true');
  await page.locator('#conversation-filter').fill('');
  await expect(page.locator('[data-project-toggle="Research"]')).toHaveAttribute('aria-expanded', 'false');
  await page.locator('[data-project-toggle="Research"]').click();
  assert.equal(fixture.liveFileClaims.length, 2, 'Navigation does not change either file claim');
  await page.screenshot({ path: 'build/smoke/projects.png' });
  await page.locator('[data-project-toggle="Writing"]').click();
  await expect(page.locator('[data-project-toggle="Writing"]')).toHaveAttribute('aria-expanded', 'false');
  const document = JSON.parse(await readFile(join(config, 'desktop-projects.json'), 'utf8'));
  assert.equal(document.projects.length, 2);
  assert.ok(document.projects.every(row => row.server === fixture.base && row.account === 'fixture' && row.enabled));
  assert.ok(!JSON.stringify(document).includes('fixture-password') && !JSON.stringify(document).includes('fixture-refresh'));
  // Losing one project's event channel must leave the other client and root working.
  fixture.disconnectProject('Research');
  await expect.poll(async () => (await state(page)).projectFolders.find(row => row.name === 'Research').connected).toBe(false);
  assert.equal((await state(page)).connected, true);
  assert.deepEqual((await fixture.file({ op: 'read', path: 'notes.md' }, 'Writing')).span.lines, ['Writing notes']);
  const runCalls = fixture.frames.filter(frame => frame.type === 'agent.run').length;
  await page.evaluate(() => window.plowshare.request({ action: 'project-open', project: 'Research' }));
  assert.equal(fixture.frames.filter(frame => frame.type === 'agent.run').length, runCalls, 'Project reconnect never resubmits an agent turn');
  assert.ok(fixture.rotations >= 6, 'Concurrent project sessions use the shared rotating refresh chain');
  await app.close(); app = undefined;
  await expect.poll(() => fixture.liveFileClaims.length).toBe(0);
  // A fresh process restores the saved token and project clients without another login.
  page = await launch(); await expect.poll(async () => (await state(page)).connected).toBe(true);
  await expect.poll(() => fixture.liveFileClaims.length).toBe(2);
  await expect(page.locator('[data-project="Research"]')).toBeVisible();
  await expect(page.locator('[data-project="Writing"]')).toBeVisible();
  await expect(page.locator('[data-project-toggle="Writing"]')).toHaveAttribute('aria-expanded', 'false');
  assert.equal((await state(page)).projectFolders.filter(row => row.files.status === 'ready').length, 2);
  // Saved mappings still restore when the server's project list is unavailable.
  await app.close(); app = undefined; fixture.setRefuseProjects(true);
  page = await launch(); await expect.poll(async () => (await state(page)).connected).toBe(true);
  assert.equal(fixture.liveFileClaims.length, 2);
  assert.match((await state(page)).projectListError, /saved folders/);
  fixture.setRefuseProjects(false);
  // Pausing persists; the other project remains active and is restored alone next time.
  await page.evaluate(() => window.plowshare.request({ action: 'files-withdraw', project: 'Research' }));
  await expect.poll(() => fixture.liveFileClaims.length).toBe(1);
  await app.close(); app = undefined;
  page = await launch(); await expect.poll(async () => (await state(page)).connected).toBe(true);
  assert.deepEqual(fixture.liveFileClaims.map(row => row.project), ['Writing']);
  // A saved missing folder is an explicit failure, never replaced by a server path.
  await app.close(); app = undefined; await rename(folders[1], folders[1] + '-moved');
  fixture.addServerProject({ name: 'Writing', machine: hostname(), workspace: folders[0] });
  page = await launch(); await expect.poll(async () => (await state(page)).connected).toBe(true);
  assert.equal(fixture.liveFileClaims.length, 0);
  assert.match((await state(page)).projectFolders.find(row => row.name === 'Writing').error, /ENOENT/);
  assert.equal((await state(page)).projectFolders.find(row => row.name === 'Writing').path, folders[1]);
  // A lost local configuration can be recovered from the server's machine/path pair.
  await page.locator('[data-project=""]').click();
  await app.close(); app = undefined;
  await rm(join(config, 'desktop-projects.json'));
  fixture.addServerProject({ name: 'Research', machine: claims.find(row => row.project === 'Research').machine, workspace: folders[0] });
  fixture.addServerProject({ name: 'Remote project', machine: 'another-computer', workspace: folders[0] });
  fixture.addServerProject({ name: 'Server project', workspace: folders[0] });
  page = await launch(); await expect.poll(async () => (await state(page)).connected).toBe(true);
  assert.equal(fixture.liveFileClaims.length, 0, 'Recorded locations are offered, not silently rooted');
  await page.locator('[data-project="Research"]').click();
  assert.equal(fixture.liveFileClaims.length, 0, 'Navigation never approves an unsaved recorded folder');
  await expect(page.locator('#file-access-description')).toContainText(folders[0]);
  await page.locator('#file-access-deny').click();
  assert.equal(fixture.liveFileClaims.length, 0);
  await page.locator('#files-open').click(); await page.locator('#inspector-close').click();
  await page.locator('#file-access-allow').click();
  await expect.poll(() => fixture.liveFileClaims.length).toBe(1);
  await page.locator('#files-open').click();
  await expect(page.locator('#files-root')).toContainText(`${claims.find(row => row.project === 'Research').machine} · ${folders[0]}`);
  await expect(page.locator('#files-reopen')).toBeHidden();
  assert.equal((await state(page)).projectFolders.find(row => row.name === 'Research').path, folders[0]);
  await page.locator('#files-forget').click();
  await expect(page.locator('#files-reopen')).toHaveText('Connect recorded folder');
  await page.locator('#files-reopen').click();
  await expect.poll(() => fixture.liveFileClaims.length).toBe(1);
  await page.locator('#inspector-close').click();
  for (const project of ['Remote project', 'Server project']) {
    await page.locator(`[data-project="${project}"]`).click();
    await expect(page.locator('#files-label')).toHaveText('Connect files');
    await expect(page.locator('#file-access-prompt')).toBeVisible();
    await expect(page.locator('#file-access-description')).toContainText(`Choose a local folder for ${project}`);
    await page.locator('#files-open').click();
    await expect(page.locator('#files-root')).toContainText(folders[0]);
    await expect(page.locator('#files-reopen')).toBeHidden();
    const refusal = await page.evaluate(async project => {
      try { await window.plowshare.request({ action: 'project-open', project }); return ''; }
      catch (error) { return String(error); }
    }, project);
    assert.match(refusal, /another machine or the server/);
    await page.locator('#inspector-close').click();
  }
  assert.deepEqual(fixture.liveFileClaims.map(row => row.project), ['Research']);
  const recovered = JSON.parse(await readFile(join(config, 'desktop-projects.json'), 'utf8'));
  assert.deepEqual(recovered.projects.map(row => row.name), ['Research']);
  assert.equal(fixture.loginCount(), 1, 'All fresh-process project restorations reuse the original login.');
  console.log('Projects smoke passed: independent clients/jobs/files, collapsible navigation, recorded machine/path recovery, refresh rotation, restart restoration, pause, isolated loss and missing-folder handling.');
} finally {
  await app?.close(); await fixture.close(); await rm(temporary, { recursive: true, force: true });
}

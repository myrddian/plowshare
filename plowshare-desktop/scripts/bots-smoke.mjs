import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, readFile, writeFile, realpath, rm } from 'node:fs/promises';
import { tmpdir, hostname } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { thisMachine } from 'plowshare-client-node/marker';
import { protocolFixture } from './protocol-fixture.mjs';

const temporary = await realpath(await mkdtemp(join(tmpdir(), 'plowshare-bots-smoke-')));
const root = join(temporary, 'second-brain-folder');
const bots = join(root, '.plowshare', 'bots');
await mkdir(bots, { recursive: true });
await writeFile(join(root, '.plowshare', 'project'), '2ndbrain\n');
const definition = name => `---\nname: ${name}\ndescription: A project-local research bot\nmodel: fixture-model\nbot: true\nexported: true\ntools: []\n---\nHelp with this project.\n`;
for (const name of ['cathy', 'sophie']) await writeFile(join(bots, `${name}.md`), definition(name));
await writeFile(join(bots, 'default'), 'cathy\n');
const config = join(temporary, 'config');
const profile = join(temporary, 'profile');
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'), PLOWSHARE_DESKTOP_PROFILE: profile, PLOWSHARE_DESKTOP_CONFIG: config };
delete env.ELECTRON_RUN_AS_NODE;
const definitionReads = [];
// The fixture supplies agent.list rows, while definition bytes and the default
// are read from real files through the desktop's own session and file service.
// This exercises desktop discovery ordering, not the Java definition parser.
const fixture = await protocolFixture({ agentRoster: async ({ project, session, files }) => {
  const fallback = [{ name: 'fixture-bot', bot: true, served: true, preferred: !files }];
  if (!files) return fallback;
  const preferred = await files({ op: 'read', path: '.plowshare/bots/default', purpose: 'definitions' });
  const listed = await files({ op: 'glob', pattern: '.plowshare/bots/*.md', purpose: 'definitions' });
  assert.equal(preferred.outcome, 'ok');
  assert.equal(listed.outcome, 'ok');
  const rows = [];
  for (const path of listed.paths) {
    const result = await files({ op: 'read', path, purpose: 'definitions' });
    assert.equal(result.outcome, 'ok');
    const text = result.span.lines.join('\n');
    const name = /^name: (.+)$/m.exec(text)?.[1];
    assert.ok(name && /^bot: true$/m.test(text));
    definitionReads.push({ project, session, name });
    rows.push({ name, bot: true, served: true, preferred: preferred.span.lines[0] === name });
  }
  return [...fallback, ...rows];
} });
fixture.addServerProject({ name: '2ndbrain', machine: thisMachine(env, hostname()), workspace: root });
let app;
const launch = async () => { app = await electron.launch({ executablePath, args: [resolve('.')], env }); return app.firstWindow(); };
try {
  let page = await launch();
  await page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  assert.equal(fixture.liveFileClaims.length, 0);
  await page.locator('[data-project="2ndbrain"]').click();
  await expect(page.locator('#files-label')).toHaveText('Files connected');
  await expect(page.locator('#agent')).toHaveValue('cathy');
  await expect(page.locator('#draft')).toHaveAttribute('placeholder', 'Message cathy…');
  assert.equal(fixture.liveFileClaims[0].project, '2ndbrain');
  assert.ok(definitionReads.some(row => row.name === 'cathy'));
  assert.ok(definitionReads.every(row => row.project === '2ndbrain' && row.session === fixture.liveFileClaims[0].session));
  const saved = JSON.parse(await readFile(join(config, 'desktop-projects.json'), 'utf8'));
  assert.equal(saved.projects[0].name, '2ndbrain'); assert.equal(saved.projects[0].path, root);
  await page.locator('#new-conversation').click();
  await page.locator('#draft').fill('Use my project-local bot'); await page.locator('#send').click();
  await expect.poll(() => fixture.frames.filter(frame => frame.type === 'agent.run').length).toBe(1);
  const run = fixture.frames.find(frame => frame.type === 'agent.run');
  assert.equal(run.payload.agent, 'cathy'); assert.equal(run.payload.session, fixture.liveFileClaims[0].session);
  fixture.completeLatest();
  await expect(page.locator('#run-indicator')).toBeHidden();
  await writeFile(join(bots, 'default'), 'sophie\n');
  await page.locator('#refresh').click();
  await expect(page.locator('#agent')).toHaveValue('sophie');
  await page.locator('[data-project=""]').click();
  await expect(page.locator('#agent option')).toHaveCount(1);
  await expect(page.locator('#agent')).toHaveValue('fixture-bot');
  await page.locator('[data-project="2ndbrain"]').click();
  await page.locator('#files-open').click(); await page.locator('#files-withdraw').click(); await page.locator('#files-close').click();
  await page.locator('[data-project=""]').click(); await page.locator('[data-project="2ndbrain"]').click();
  assert.equal(fixture.liveFileClaims.length, 0, 'Navigation must not undo an explicit pause');
  await page.locator('#files-open').click(); await page.locator('#files-reopen').click(); await page.locator('#files-close').click();
  await expect(page.locator('#agent')).toHaveValue('sophie');
  await app.close(); app = undefined;
  // No saved folder mapping, but a remembered project selection on restart.
  await rm(join(config, 'desktop-projects.json'));
  page = await launch();
  await expect(page.locator('#files-label')).toHaveText('Files connected');
  await expect(page.locator('#agent')).toHaveValue('sophie');
  assert.equal(fixture.loginCount(), 1);
  console.log('Bots smoke passed: real project-local definitions over the same session, automatic attachment, Cathy default, bot refresh, global isolation, explicit pause and restart recovery.');
} finally {
  await app?.close(); await fixture.close(); await rm(temporary, { recursive: true, force: true });
}

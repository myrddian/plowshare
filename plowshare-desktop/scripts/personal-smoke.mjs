import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
import { SyncFixture, git } from './sync-fixture.mjs';
import { connectionDirectory } from '../../sdk/node/build/connections.js';

const temporary = await realpath(await mkdtemp(join(tmpdir(), 'plowshare-personal-smoke-')));
const project = 'personal:' + Buffer.from('fixture').toString('hex');
const union = new SyncFixture(join(temporary, 'hubs'));
await union.ask('union.enable', { project }, true);
const seed = join(temporary, 'seed');
git('clone', '-q', union.row(project).hub, seed);
for (const section of ['In','Out','Resources','Archive','Planning','Bots']) {
  await mkdir(join(seed, section)); await writeFile(join(seed, section, '.keep'), '');
}
await writeFile(join(seed, 'Planning', 'plan.md'), 'A useful personal plan.');
await writeFile(join(seed, 'Bots', 'default'), 'close_reader');
git('-C', seed, 'add', '.');
git('-C', seed, '-c', 'user.name=fixture', '-c', 'user.email=fixture@local', 'commit', '-qm', 'Personal skeleton');
git('-C', seed, 'push', '-q', 'origin', 'main');
union.row(project).enabled = true;
const fixture = await protocolFixture({ union, agentRoster: () => [
  { name:'fixture-bot', displayName:'Personal Assistant', bot:true, served:true, preferred:true, model:'fixture-model', tools:[], description:'First assistant', withheld:[] },
  { name:'planning-bot', displayName:'Planning Assistant', bot:true, served:true, preferred:false, model:'fixture-model', tools:[], description:'Planning assistant', withheld:[] },
] });
fixture.addServerProject({ name: project, kind: 'personal', workspace: '/personal' });
fixture.addConversation({ id:'personal-existing', project, title:'Saved knowledge chat' }, 'fixture-bot');
fixture.addConversation({ id:'personal-planning', project, title:'Saved planning chat' }, 'planning-bot');
const env = { ...process.env, HOME: temporary, PLOWSHARE_CONFIG_DIR: join(temporary,'credentials'),
  PLOWSHARE_DESKTOP_CONFIG: join(temporary,'config'), PLOWSHARE_DESKTOP_PROFILE: join(temporary,'profile') };
delete env.ELECTRON_RUN_AS_NODE;
await mkdir('build/smoke', {recursive:true});
let app;
try {
  app = await electron.launch({executablePath,args:[resolve('.')],env});
  const page = await app.firstWindow(); const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.evaluate(base => window.plowshare.request({action:'connect',base,handle:'fixture',password:'fixture-password'}),fixture.base);
  const state = async () => (await page.evaluate(() => window.plowshare.request({action:'bootstrap'}))).state;
  const personal = join(connectionDirectory(fixture.base, 'fixture', env.PLOWSHARE_CONFIG_DIR), 'personal');
  assert.equal((await state()).personal.root, personal);
  assert.equal(await readFile(join(personal,'Planning','plan.md'),'utf8'), 'A useful personal plan.');
  await expect(page.locator('#personal-navigation')).toBeVisible();
  const personalHeading = page.locator('#personal-toggle');
  const projectsHeading = page.locator('#projects-toggle');
  const personalBounds = await personalHeading.boundingBox();
  const projectsBounds = await projectsHeading.boundingBox();
  assert.equal(personalBounds.x, projectsBounds.x);
  assert.equal(await personalHeading.locator('[data-icon="user"]').count(), 1);
  await personalHeading.click();
  await expect(personalHeading).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#personal-contents')).toBeHidden();
  await expect(page.locator('#sidebar-projects')).toBeVisible();
  await projectsHeading.click();
  await page.reload();
  await expect(page.locator('#personal-navigation')).toBeVisible();
  await expect(personalHeading).toHaveAttribute('aria-expanded', 'false');
  await expect(projectsHeading).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#personal-contents')).toBeHidden();
  await personalHeading.click();
  await expect(page.locator('#personal-contents')).toBeVisible();
  await expect(page.locator('#sidebar-projects')).toBeHidden();
  await projectsHeading.click();
  assert.equal(await page.locator('#conversations [data-project="'+project+'"]').count(),0);
  assert.equal(await page.locator('#conversations button[data-project=""]').count(),0);
  await expect(page.locator('#personal-conversations-toggle')).toHaveText('Conversations');
  await expect(page.locator('#inbox-open')).toContainText('Mailbox');
  for (const section of ['In','Out','Resources','Archive','Planning']) {
    await page.locator('[data-personal-section="'+section+'"]').click();
    await expect(page.locator('#personal-panel')).toBeVisible();
    await expect(page.locator('#personal-title')).toHaveText(section === 'In' ? 'Inbox' : section);
    assert.equal(await page.locator('[data-personal-inbox]').count(), 0);
  }
  await page.locator('#controls-button').click();
  await expect(page.locator('#personal-panel')).toBeHidden();
  await expect(page.locator('#workspace-manage')).toBeVisible();
  await page.locator('[data-personal-section="Planning"]').click();
  await expect(page.locator('#workspace-manage')).toBeHidden();
  await page.locator('[data-personal-path="Planning/plan.md"]').click();
  await expect(page.locator('.personal-preview')).toHaveText('A useful personal plan.');
  await page.locator('#personal-bots-files').click();
  await expect(page.locator('#personal-title')).toHaveText('Bots');
  await page.locator('#personal-bots-toggle').click();
  await page.locator('[data-personal-bot-toggle="fixture-bot"]').click();
  const firstBot = page.locator('[data-personal-bot-group="fixture-bot"]');
  await expect(firstBot.locator('[data-conversation="personal-existing"]')).toBeVisible();
  assert.equal(await firstBot.locator('[data-conversation="personal-planning"]').count(), 0);
  const openedBefore = fixture.frames.filter(row => row.type === 'conversation.open').length;
  await page.locator('[data-personal-bot-open="fixture-bot"]').click();
  await expect(page.locator('#agent')).toHaveValue('fixture-bot');
  assert.equal(fixture.frames.filter(row => row.type === 'conversation.open').length, openedBefore, 'Continuing a bot chat opens no duplicate conversation');
  await page.locator('[data-personal-bot-new="planning-bot"]').click();
  const created = (await state()).conversations.at(-1);
  assert.equal(created.project, project);
  await expect(page.locator('#agent')).toHaveValue('planning-bot');
  await page.locator('#personal-conversations-toggle').click();
  await expect(page.locator('#personal-conversations')).toBeHidden();
  await expect(page.locator('#personal-bots')).toBeVisible();
  await page.reload();
  await expect(page.locator('#personal-navigation')).toBeVisible();
  await expect(page.locator('#personal-conversations')).toBeHidden();
  await expect(page.locator('#personal-bots')).toBeVisible();
  await expect(page.locator('#agent')).toHaveValue('planning-bot');
  await expect(page.locator('[data-personal-bot-group="planning-bot"] [data-conversation="'+created.id+'"]')).toBeVisible();
  await page.locator('#personal-conversations-toggle').click();
  await expect(page.locator('#personal-conversations [data-conversation="'+created.id+'"]')).toBeVisible();
  await page.locator('#sidebar').evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({path:'build/smoke/personal.png'});
  assert.equal(fixture.frames.filter(row => row.type === 'agent.run').length,0);
  assert.equal(fixture.frames.filter(row => row.type === 'union.enable').length,0);
  assert.deepEqual(errors,[]);
  console.log('Personal smoke passed: Inbox/Mailbox separation, conversation and bot trees, server chat continuation without duplicate creation, persisted bot choices, automatic union mount and previews.');
} finally { await app?.close(); union.close(); await fixture.close(); await rm(temporary,{recursive:true,force:true}); }

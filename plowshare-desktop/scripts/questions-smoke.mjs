import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
const profile = await mkdtemp(join(tmpdir(), 'plowshare-questions-'));
const fixture = await protocolFixture({ emptyApprovals: true });
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'), PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'), PLOWSHARE_DESKTOP_PROFILE: profile }; delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const main = await app.firstWindow(), errors = [];
  main.on('pageerror', error => errors.push(error.message));
  await main.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  const modal = main.locator('#question-dialog');
  const open = () => main.locator('[data-open-question="fixture-old-root"]').click();
  const answers = () => fixture.frames.filter(frame => frame.type === 'orchestration.answer');
  await expect(main.locator('#question-waiting')).toContainText('1 question waiting');
  await expect(modal).not.toBeVisible(); // Another workspace is queued without interrupting chat.
  assert.equal((await app.windows()).length, 1);
  await open(); await expect(modal).toBeVisible();
  await expect(modal).toContainText('Other project'); await expect(modal).toContainText('Choose the next research step.');
  await expect(modal.locator('input:checked')).toHaveCount(0);
  await modal.locator('input[value="Archives"]').check(); await modal.locator('[data-note]').fill('Keep this draft.');
  fixture.publish({ kind: 'orchestration.recorded', root: 'fixture-old-root', through: 125 });
  await expect(modal.locator('[data-note]')).toHaveValue('Keep this draft.');
  await modal.locator('[data-question-defer]').click(); await expect(modal).not.toBeVisible();
  assert.equal(answers().length, 0);
  await main.evaluate(() => window.plowshare.request({ action: 'question-refresh' })); await expect(modal).not.toBeVisible();
  await open(); await expect(modal.locator('input[value="Archives"]')).toBeChecked(); await expect(modal.locator('[data-note]')).toHaveValue('Keep this draft.');
  await main.keyboard.press('Escape'); await expect(modal).not.toBeVisible(); await open();
  fixture.replaceQuestion(); await modal.locator('#run-answer-send').click();
  await expect(modal).toContainText('changed'); assert.equal(answers().length, 0);
  await expect(modal.locator('input:checked')).toHaveCount(0);
  fixture.setQuestion({ id: 'multi-question', text: 'Pick scope.', structure: { lead: 'Choose all relevant areas.', questions: [{ header: 'Areas', question: 'Which areas?', multi: true, options: [{ label: 'Server', description: 'Runtime behavior' }, { label: 'GUI', description: 'User questions' }] }, { header: 'Mode', question: 'How should it run?', multi: false, options: [{ label: 'Delegated', description: 'Use an agent' }, { label: 'Direct', description: 'Use the current agent' }] }] } });
  await expect(modal).toContainText('Which areas?');
  await modal.locator('[data-choice="0"] input[value="Server"]').check(); await modal.locator('[data-choice="0"] input[value="GUI"]').check();
  await modal.locator('[data-choice="1"] [data-other]').fill('Decide after reviewing the context.'); await modal.locator('[data-choice="1"] [data-note]').fill('Ask before changing the mode.');
  await main.setViewportSize({ width: 860, height: 640 });
  await expect(modal.locator('.dialog-header')).toBeInViewport();
  await mkdir('build/smoke', { recursive: true }); await main.screenshot({ path: 'build/smoke/question-popup-compact.png' });
  const bounds = await modal.boundingBox(); assert.ok(bounds && bounds.y >= 0 && bounds.y + bounds.height <= 640);
  await modal.locator('#run-answer-send').click(); await expect(modal).not.toBeVisible();
  assert.equal(answers().length, 1); assert.deepEqual(answers()[0].payload.choices, [{ header: 'Areas', chosen: ['Server', 'GUI'] }, { header: 'Mode', chosen: [], other: 'Decide after reviewing the context.', note: 'Ask before changing the mode.' }]);
  fixture.setRunCaller('fixture-old-root', 'fixture-first');
  fixture.changeRun('fixture-old-root', 'asking'); fixture.setQuestion({ id: 'plain-question', text: 'Describe the desired result.', structure: null });
  await expect(main.locator('#question-waiting')).toContainText('1 question waiting'); await expect(modal).toBeVisible();
  await modal.locator('#run-answer-text').fill('Preserve ordinary chat while the agent works.');
  await modal.locator('[data-question-close]').click(); await open();
  await expect(modal.locator('#run-answer-text')).toHaveValue('Preserve ordinary chat while the agent works.');
  await modal.locator('#run-answer-send').click(); await expect(modal).not.toBeVisible();
  assert.equal(answers().length, 2); assert.equal(answers()[1].payload.answer, 'Preserve ordinary chat while the agent works.');
  fixture.changeRun('fixture-old-root', 'asking'); fixture.setQuestion({ id: 'remote-question', text: 'Another client can answer.', structure: null });
  await expect(main.locator('#question-waiting')).toBeVisible(); await expect(modal).toBeVisible();
  fixture.changeRun('fixture-old-root', 'running'); await expect(modal).not.toBeVisible(); await expect(main.locator('#question-waiting')).not.toBeVisible();
  assert.equal(answers().length, 2); assert.equal(fixture.frames.some(frame => frame.type === 'agent.run'), false);
  await assert.rejects(main.evaluate(() => window.plowshare.request({ action: 'run-answer', id: 'foreign', question: 'invented', answer: 'yes' })), /current pending/);
  await assert.rejects(main.evaluate(() => window.plowshare.request({ action: 'run-cancel', id: 'fixture-old-root' })), /Open Activity/);

  const prior = await main.evaluate(() => window.plowshare.request({
    action: 'run', conversation: 'fixture-first', agent: 'fixture-bot', text: 'Pre-restart chat job',
  }));
  const job = prior.state.jobs.find(row => row.task === 'Pre-restart chat job');
  assert.ok(job);
  await expect(main.locator('#transcript')).toContainText('Fixture streaming: Pre-restart chat job');
  fixture.disconnect();
  await expect(main.locator('#conversation-alerts')).toContainText('Outcome uncertain');
  fixture.completeJob(job.id);
  fixture.forgetJobStatus(job.id);
  fixture.changeRun('fixture-old-root', 'asking');
  fixture.setQuestion({
    id: 'objective-review',
    text: 'Approve the objectives?',
    structure: { lead: 'Review the objectives.', questions: [
      { header: 'Objectives', question: 'Approve the objectives?', multi: false,
        options: [{ label: 'Approve', description: 'Continue the run.' }] },
    ] },
  });
  await main.evaluate(base => window.plowshare.request({
    action: 'connect', base, handle: 'fixture', password: '',
  }), fixture.base);
  await expect(modal).toContainText('Approve the objectives?');
  await modal.locator('input[value="Approve"]').check();
  await modal.locator('#run-answer-send').click();
  await expect(modal).not.toBeVisible();
  await expect(main.locator('#conversation-alerts')).toContainText('Job status unavailable');
  await expect(main.locator('#conversation-alerts')).not.toContainText('Outcome uncertain');
  await expect(main.locator('#composer-status-text')).toHaveText('Ready');
  await expect(main.locator('#transcript')).toContainText('Fixture answer: Pre-restart chat job');
  assert.deepEqual(answers()[2]?.payload.choices, [{ header: 'Objectives', chosen: ['Approve'] }]);
  assert.equal(answers().length, 3);
  assert.equal(fixture.frames.filter(frame => frame.type === 'agent.run').length, 1);
  assert.deepEqual(errors, []);
  console.log('PASS: chat question popup, scoped queue, drafts, stale questions, structured decisions, remote settlement and post-restart approval without replay.');
} finally { if (app) await app.close(); await fixture.close(); await rm(profile, { recursive: true, force: true }); }

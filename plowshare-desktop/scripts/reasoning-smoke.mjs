// Exercise the actual native renderer and IPC client against an isolated WS fixture.
import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { protocolFixture } from './protocol-fixture.mjs';

const temporary = await mkdtemp(join(tmpdir(), 'plowshare-reasoning-'));
const fixture = await protocolFixture({ conversationProject: 'Empty workspace', emptyApprovals: true });
fixture.addConversation({ id: 'fixture-second', title: 'Another conversation', project: 'Empty workspace' });
const env = {
  ...process.env,
  PLOWSHARE_CONFIG_DIR: join(temporary, 'credentials'),
  PLOWSHARE_DESKTOP_CONFIG: join(temporary, 'config'),
  PLOWSHARE_DESKTOP_PROFILE: join(temporary, 'profile'),
};
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  const packaged = process.env.PLOWSHARE_PACKAGED_EXECUTABLE;
  app = await electron.launch({ executablePath: packaged ?? executablePath, args: packaged ? [] : [resolve('.')], env });
  const page = await app.firstWindow();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const request = value => page.evaluate(value => window.plowshare.request(value), value);
  await request({ action: 'connect', base: fixture.base, handle: 'fixture', password: 'fixture-password' });
  await page.locator('.project-row[data-project="Empty workspace"]').click();
  await page.locator('.conversation-row[data-conversation="fixture-second"]').click();
  await expect(page.locator('.conversation-row[data-conversation="fixture-second"]')).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#chat-title')).toHaveText('Another conversation');
  await expect(page.locator('#approval-bar')).toBeHidden();
  await request({ action: 'run', conversation: 'fixture-second', agent: 'fixture-bot', text: 'Reasoning display acceptance' });
  const id = fixture.latestJob;
  assert.equal((await request({ action: 'bootstrap' })).state.jobs.find(job => job.id === id).conversation, 'fixture-second');
  const preview = page.locator(`[data-thinking-job="${id}"]`);
  await expect(preview).toBeVisible();
  await expect(preview.locator('pre')).toContainText('Fixture reasoning: Reasoning display acceptance');
  await expect(preview.locator('summary')).toHaveCount(0);
  await expect(preview.locator('pre')).toHaveCSS('font-style', 'italic');
  await expect(preview).toHaveCSS('border-top-width', '0px');
  const answer = page.locator('.message.answer.live .message-body');
  await expect(answer).toContainText('Fixture streaming: Reasoning display acceptance');
  assert.notEqual(await preview.evaluate(node => getComputedStyle(node).color), await answer.evaluate(node => getComputedStyle(node).color));
  // Repeated thought-only deltas keep the same phase. Each must still repaint.
  fixture.publish({ job: id, part: 'THINKING', text: ' first update' });
  await expect(preview.locator('pre')).toContainText('first update');
  fixture.publish({ job: id, part: 'THINKING', text: ' second update' });
  await expect(preview.locator('pre')).toContainText('second update');
  fixture.publish({ job: id, part: 'ANSWER', text: ' More answer text.' });
  await expect(answer).toContainText('More answer text.');
  fixture.publish({ job: id, part: 'THINKING', text: '\nReasoning continues alongside the answer.' });
  await expect(preview).toContainText('Reasoning continues alongside the answer.');
  await expect(answer).toContainText('More answer text.');
  const toolStatus = page.locator('.live-tool-status');
  fixture.publish({ job: id, kind: 'tool_called', tool: 'run' });
  await expect(toolStatus).toHaveText('Waiting for tool replies… (run)');
  await expect(answer).toContainText('More answer text.');
  await mkdir('build/smoke', { recursive: true });
  await page.screenshot({ path: 'build/smoke/published-reasoning.png' });
  fixture.publish({ job: id, kind: 'model_call' });
  await expect(toolStatus).toHaveCount(0);
  fixture.publish({ job: id, kind: 'tool_called', tool: '<img src=x>' });
  await expect(toolStatus).toContainText('<img src=x>');
  await expect(toolStatus.locator('img')).toHaveCount(0);
  fixture.publish({ job: id, part: 'ANSWER', text: ' Answer resumes.' });
  await expect(toolStatus).toHaveCount(0);
  const markup = '<img src=x onerror="window.reasoningInjection=true">';
  fixture.publish({ job: id, part: 'THINKING', text: markup });
  await expect(preview.locator('pre')).toContainText(markup);
  await expect(preview.locator('img')).toHaveCount(0);
  assert.equal(await page.evaluate(() => window.reasoningInjection), undefined);
  fixture.publish({ job: 'another-job', part: 'THINKING', text: 'wrong job text' });
  await expect(preview).not.toContainText('wrong job text');
  fixture.publish({ job: id, part: 'THINKING', text: 'x'.repeat(20000) + 'visible tail' });
  await expect(preview.locator('pre')).toContainText('visible tail');
  assert.equal((await preview.locator('pre').textContent()).length, 16384);
  await expect.poll(() => preview.locator('pre').evaluate(node => node.scrollTop)).toBeGreaterThan(0);
  await expect(preview).toContainText('Earlier text is omitted');
  await preview.locator('pre').evaluate(node => { node.scrollTop = 0; });
  fixture.publish({ job: id, part: 'THINKING', text: ' reader holds position' });
  await expect(preview.locator('pre')).toContainText('reader holds position');
  assert.equal(await preview.locator('pre').evaluate(node => node.scrollTop), 0, 'Reading older text does not jump to the latest delta.');
  await page.locator('.conversation-row[data-conversation="fixture-first"]').click();
  await expect(page.locator('.conversation-row[data-conversation="fixture-first"]')).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#chat-title')).toHaveText('Fixture conversation');
  await expect(page.locator('[data-thinking-job]')).toHaveCount(0);
  await page.locator('.conversation-row[data-conversation="fixture-second"]').click();
  await expect(page.locator('.conversation-row[data-conversation="fixture-second"]')).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('#chat-title')).toHaveText('Another conversation');
  await expect(preview.locator('pre')).toContainText('visible tail');
  fixture.completeLatest('Authoritative final answer.');
  await expect(page.locator('[data-thinking-job]')).toHaveCount(0);
  await expect(page.locator('#transcript')).toContainText('Authoritative final answer.');
  fixture.publish({ job: id, part: 'THINKING', text: 'late reasoning' });
  await expect(page.locator('[data-thinking-job]')).toHaveCount(0);
  assert.equal(fixture.frames.filter(frame => frame.type === 'agent.run').length, 1);
  assert.deepEqual(errors, []);
  console.log('Published reasoning passed: subtle italic stream alongside answers, tool waiting status, escaping, bounded tail, scroll preservation, scope switching and authoritative final answer.');
} finally {
  await app?.close();
  await fixture.close();
  await rm(temporary, { recursive: true, force: true });
}

import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const temporary = await mkdtemp(join(tmpdir(), 'plowshare-approvals-'));
const fixture = await protocolFixture({ controls: true, foregroundApprovals: true });
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(temporary, 'credentials'), PLOWSHARE_DESKTOP_CONFIG: join(temporary, 'config'), PLOWSHARE_DESKTOP_PROFILE: join(temporary, 'profile') };
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  await mkdir('build/smoke', { recursive: true });
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const page = await app.firstWindow();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  await page.locator('#approval-policy-open').click();
  const policy = page.getByRole('dialog', { name: 'Personal approval policy' });
  await expect(policy.getByRole('status')).toHaveText('Saved revision 0.');
  await policy.locator('[name="mode"]').selectOption('SMART');
  await policy.locator('[name="rules"]').fill(JSON.stringify([{ id: 'no-identity-change', effect: 'REJECT', kind: 'COMMAND', project: null, provider: null, cwd: null, inputDigest: null, argv: ['git', 'config', '--local', 'user.email', 'example@example.invalid'], paths: [], guidance: 'Ask me before changing Git identity.' }]));
  await policy.getByRole('button', { name: 'Save policy', exact: true }).click();
  await expect(policy.getByRole('status')).toHaveText('Saved revision 1.');
  const policySave = fixture.frames.find(frame => frame.type === 'approval.policy.save');
  assert.equal(policySave.payload.policy.mode, 'SMART');
  assert.equal(policySave.payload.policy.revision, 0);
  assert.equal(policySave.payload.policy.rules[0].effect, 'REJECT');
  assert.equal(Object.hasOwn(policySave.payload, 'project'), false);
  await page.screenshot({ path: 'build/smoke/approval-policy.png' });
  const policyDraft = await policy.locator('[name="rules"]').inputValue();
  fixture.losePolicyReply();
  await policy.locator('[name="mode"]').selectOption('MANUAL');
  await policy.getByRole('button', { name: 'Save policy', exact: true }).click();
  await expect(policy.getByRole('status')).toContainText('Draft preserved.');
  assert.equal(await policy.locator('[name="rules"]').inputValue(), policyDraft);
  await policy.getByRole('button', { name: 'Reload saved policy', exact: true }).click();
  await expect(policy.getByRole('status')).toContainText('Saved revision 2 loaded. Your draft is preserved');
  assert.equal(await policy.locator('[name="rules"]').inputValue(), policyDraft);
  assert.equal(fixture.frames.filter(frame => frame.type === 'approval.policy.save').length, 2, 'Reconciliation does not resend an uncertain policy change');

  await policy.getByRole('button', { name: 'Close', exact: true }).click();
  const decisions = () => fixture.frames.filter(frame => frame.type === 'approval.answer');
  const prompt = (surface, id) => surface.locator(`[data-approval-prompt="${id}"]`);
  // A state push between pointer-down and pointer-up must not detach the button:
  // Chromium otherwise sends no button click, so the user's decision disappears.
  const clickAcrossRefresh = async (window, button) => {
    const target = await button.elementHandle();
    assert.ok(target, 'Approval button exists before the press');
    await button.hover();
    await window.mouse.down();
    try {
      await window.evaluate(() => {
        delete document.documentElement.dataset.approvalRefreshSeen;
        const unsubscribe = window.plowshare.subscribe(() => {
          unsubscribe();
          document.documentElement.dataset.approvalRefreshSeen = 'true';
        });
      });
      // The workspace owns refresh; inspection windows receive its state push.
      await page.evaluate(() => window.plowshare.request({ action: 'approvals-refresh' }));
      await expect(window.locator('html')).toHaveAttribute('data-approval-refresh-seen', 'true');
      assert.equal(await target.evaluate(element => element.isConnected), true, 'An unchanged approval button survives unrelated state updates');
    } finally {
      await window.mouse.up();
      await target.dispose();
    }
  };
  await expect(prompt(page.locator('#approval-bar'), 'fixture-approval')).toBeVisible();
  // Foreground questions have no pinned account handle: account-wide reads are
  // empty, so opening the conversation must discover its own pending questions.
  assert.ok(fixture.frames.some(frame => frame.type === 'approval.list' && frame.payload.mine === true));
  assert.ok(fixture.frames.some(frame => frame.type === 'approval.list' && frame.payload.conversation === 'fixture-first'));
  await clickAcrossRefresh(page, prompt(page.locator('#approval-bar'), 'fixture-approval').getByRole('button', { name: 'Approve', exact: true }));
  await expect(prompt(page.locator('#approval-bar'), 'fixture-approval')).toHaveCount(0);
  await expect(page.locator('#approval-bar')).toBeHidden();
  assert.equal(decisions()[0].payload.decision, 'once');

  await expect(page.locator('#composer-status-text')).toHaveText('Ready');
  await page.evaluate(() => window.plowshare.request({ action: 'run', conversation: 'fixture-first', agent: 'fixture-bot', text: 'Request a command approval' }));
  fixture.awaitApproval('awaiting-without-reason');
  await expect(prompt(page.locator('#approval-bar'), 'awaiting-without-reason')).toBeVisible();
  await page.screenshot({ path: 'build/smoke/awaiting-approval.png' });
  await clickAcrossRefresh(page, prompt(page.locator('#approval-bar'), 'awaiting-without-reason').getByRole('button', { name: 'Deny', exact: true }));
  await expect(prompt(page.locator('#approval-bar'), 'awaiting-without-reason')).toHaveCount(0);
  await expect(page.locator('#approval-bar')).toBeHidden();
  assert.deepEqual(decisions().at(-1).payload, { id: 'awaiting-without-reason', decision: 'deny' });

  // New requests discovered from an Inbox push appear in their chat prompt and remain actionable.
  fixture.queueApproval('inline-request');
  await expect(prompt(page.locator('#transcript'), 'inline-request')).toBeVisible();
  await prompt(page.locator('#transcript'), 'inline-request').getByRole('button', { name: 'Deny', exact: true }).click();
  await expect(prompt(page.locator('#transcript'), 'inline-request')).toHaveCount(0);
  assert.deepEqual(decisions().at(-1).payload, { id: 'inline-request', decision: 'deny' });

  const file = { kind: 'FILE_WRITE', provider: 'remote:fixture-session', paths: ['/fixture/config'], inputDigest: 'a'.repeat(64), inputPreview: '<reviewed new configuration>' };
  fixture.queueApproval('typed-file-request', 'fixture-first', false, true, file);
  const filePrompt = prompt(page.locator('#approval-bar'), 'typed-file-request');
  await expect(filePrompt).toContainText('FILE_WRITE /fixture/config');
  await expect(filePrompt).toContainText('<reviewed new configuration>');
  await filePrompt.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(filePrompt).toHaveCount(0);
  assert.deepEqual(decisions().at(-1).payload, { id: 'typed-file-request', decision: 'once', fileDigest: file.inputDigest });

  // Work not submitted by this window needs neither a tracked job nor an Inbox wake.
  fixture.queueApproval('log-only-request', 'fixture-first', false, false);
  await expect(prompt(page.locator('#approval-bar'), 'log-only-request')).toBeVisible();
  await page.getByRole('button', { name: 'Check command approvals', exact: true }).click();
  await expect(prompt(page.locator('#approval-bar'), 'log-only-request')).toBeVisible();
  const beforeYes = decisions().length;
  const blocked = await page.evaluate(async () => {
    try { await window.plowshare.request({ action: 'run', conversation: 'fixture-first', agent: 'fixture-bot', text: 'Yes' }); }
    catch (error) { return error.message; }
  });
  assert.match(blocked, /Use Approve or Deny/);
  assert.equal(decisions().length, beforeYes);
  await prompt(page.locator('#approval-bar'), 'log-only-request').getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(prompt(page.locator('#approval-bar'), 'log-only-request')).toHaveCount(0);
  await expect(page.locator('#approval-bar')).toBeHidden();
  assert.deepEqual(decisions().at(-1).payload, { id: 'log-only-request', decision: 'once' });

  const newWindow = app.waitForEvent('window'); await page.locator('#inbox-open').click();
  const inbox = await newWindow; inbox.on('pageerror', error => errors.push(error.message));
  fixture.queueApproval('inbox-request');
  await expect(inbox.locator('[data-item="inbox-inbox-request"]')).toBeVisible();
  await inbox.locator('[data-item="inbox-inbox-request"]').click();
  await expect(prompt(inbox, 'inbox-request')).toBeVisible();
  await inbox.screenshot({ path: 'build/smoke/inbox-approval.png' });
  await prompt(inbox, 'inbox-request').getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(prompt(inbox, 'inbox-request')).toHaveCount(0);
  await expect(prompt(page.locator('#approval-bar'), 'inbox-request')).toHaveCount(0);
  assert.deepEqual(decisions().at(-1).payload, { id: 'inbox-request', decision: 'once' });
  assert.equal(fixture.frames.some(frame => frame.type === 'inbox.read'), false, 'Answering and marking a notice read are different actions');

  fixture.queueApproval('legacy-inbox-request', 'fixture-first', true);
  await expect(inbox.locator('[data-item="inbox-legacy-inbox-request"]')).toBeVisible();
  await inbox.locator('[data-item="inbox-legacy-inbox-request"]').click();
  await prompt(inbox, 'legacy-inbox-request').getByRole('button', { name: 'Deny', exact: true }).click();
  await expect(prompt(inbox, 'legacy-inbox-request')).toHaveCount(0);
  assert.deepEqual(decisions().at(-1).payload, { id: 'legacy-inbox-request', decision: 'deny' });
  await inbox.close();

  const trajectoryWindow = app.waitForEvent('window'); await page.locator('#trajectory-open').click();
  const trajectory = await trajectoryWindow; trajectory.on('pageerror', error => errors.push(error.message));
  fixture.queueApproval('trajectory-request');
  await expect(prompt(trajectory, 'trajectory-request')).toBeVisible();
  await clickAcrossRefresh(trajectory, prompt(trajectory, 'trajectory-request').getByRole('button', { name: 'Approve', exact: true }));
  await expect(prompt(trajectory, 'trajectory-request')).toHaveCount(0);
  await expect(page.locator('#approval-bar')).toBeHidden();
  assert.deepEqual(decisions().at(-1).payload, { id: 'trajectory-request', decision: 'once' });
  assert.equal(fixture.frames.filter(frame => frame.type === 'agent.run').length, 1, 'Decisions continue their original request, never submit another agent turn');
  assert.equal(decisions().length, 8, 'Each explicit click answers exactly one approval');
  assert.deepEqual(errors, []);
  console.log('Approval smoke passed: direct Approve/Deny in command prompts, chat, Inbox and trajectory; live discovery, legacy notices and no replay.');
} finally {
  await app?.close(); await fixture.close(); await rm(temporary, { recursive: true, force: true });
}

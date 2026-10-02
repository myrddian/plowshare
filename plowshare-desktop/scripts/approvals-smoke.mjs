import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

const temporary = await mkdtemp(join(tmpdir(), 'plowshare-approvals-'));
const fixture = await protocolFixture({ controls: true });
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(temporary, 'credentials'), PLOWSHARE_DESKTOP_CONFIG: join(temporary, 'config'), PLOWSHARE_DESKTOP_PROFILE: join(temporary, 'profile') };
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  await mkdir('build/smoke', { recursive: true });
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const page = await app.firstWindow();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.evaluate(base => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }), fixture.base);
  const decisions = () => fixture.frames.filter(frame => frame.type === 'approval.answer');
  const prompt = (surface, id) => surface.locator(`[data-approval-prompt="${id}"]`);
  await expect(prompt(page.locator('#approval-bar'), 'fixture-approval')).toBeVisible();
  await prompt(page.locator('#approval-bar'), 'fixture-approval').getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(prompt(page.locator('#approval-bar'), 'fixture-approval')).toHaveCount(0);
  assert.equal(decisions()[0].payload.decision, 'once');

  // New requests discovered from an Inbox push appear in their chat prompt and remain actionable.
  fixture.queueApproval('inline-request');
  await expect(prompt(page.locator('#transcript'), 'inline-request')).toBeVisible();
  await prompt(page.locator('#transcript'), 'inline-request').getByRole('button', { name: 'Deny', exact: true }).click();
  await expect(prompt(page.locator('#transcript'), 'inline-request')).toHaveCount(0);
  assert.deepEqual(decisions().at(-1).payload, { id: 'inline-request', decision: 'deny' });

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
  await prompt(trajectory, 'trajectory-request').getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(prompt(trajectory, 'trajectory-request')).toHaveCount(0);
  assert.deepEqual(decisions().at(-1).payload, { id: 'trajectory-request', decision: 'once' });
  assert.equal(fixture.frames.some(frame => frame.type === 'agent.run'), false, 'Decisions continue their original request, never submit another agent turn');
  assert.equal(decisions().length, 5, 'Each click answers exactly one approval');
  assert.deepEqual(errors, []);
  console.log('Approval smoke passed: direct Approve/Deny in command prompts, chat, Inbox and trajectory; live discovery, legacy notices and no replay.');
} finally {
  await app?.close(); await fixture.close(); await rm(temporary, { recursive: true, force: true });
}

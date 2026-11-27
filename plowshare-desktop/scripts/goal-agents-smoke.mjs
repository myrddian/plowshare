import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import executablePath from 'electron';
import { _electron as electron, expect } from 'playwright/test';
import { protocolFixture } from './protocol-fixture.mjs';

const goal = {
  goal: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  project: 'Empty workspace',
  conversation: 'cnv_GoalA',
  instance: 'ins_GoalA',
  agent: 'goal_worker',
  objective: 'Check <em>the retained report</em>',
  lifecycle: 'STANDING',
  state: 'IDLE',
  version: 4,
  readable: true,
  reason: null,
  detail: null,
  latestResult: { job: 'job_GoalA', turn: 8, summary: 'Collected the report', evidence: ['A retained claim'] },
  nextScheduleAt: null,
  schedules: [],
  relayListeners: [],
  moreSchedules: false,
  moreRelayListeners: false,
};
const restricted = {
  ...goal,
  goal: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  conversation: 'cnv_Restricted',
  instance: 'ins_Restricted',
  agent: null,
  objective: null,
  latestResult: null,
  readable: false,
};
const profile = await mkdtemp(join(tmpdir(), 'plowshare-goals-'));
const fixture = await protocolFixture({ goals: [goal, restricted] });
const env = {
  ...process.env,
  PLOWSHARE_DESKTOP_PROFILE: profile,
  PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'),
  PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'),
};
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const main = await app.firstWindow();
  const errors = [];
  main.on('pageerror', (error) => errors.push(error.message));
  await main.evaluate(
    (base) => window.plowshare.request({ action: 'connect', base, handle: 'fixture', password: 'fixture-password' }),
    fixture.base,
  );
  const opening = app.waitForEvent('window');
  await main.locator('#goals-open').click();
  const screen = await opening;
  screen.on('pageerror', (error) => errors.push(error.message));
  await expect(screen.locator('#activity-title')).toHaveText('Goal Agents');
  await screen.locator('#goals-project').selectOption(goal.project);
  const list = screen.locator('#activity-list');
  const detail = screen.locator('#activity-detail');
  const select = (id) => list.locator(`[data-goal="${id}"]`).click();
  const pause = detail.getByRole('button', { name: 'Pause', exact: true });
  const resume = detail.getByRole('button', { name: 'Resume', exact: true });

  await select(goal.goal);
  await expect(detail).toContainText(goal.objective);
  await expect(detail).toContainText(goal.latestResult.evidence[0]);
  await expect(detail.locator('em')).toHaveCount(0);
  await expect(pause).toBeEnabled();
  await assert.rejects(
    screen.evaluate((id) => window.plowshare.request({ action: 'goal-control', goal: id, version: 3, control: 'pause' }), goal.goal),
    /refresh its status/,
  );
  await assert.rejects(
    main.evaluate((id) => window.plowshare.request({ action: 'goal-control', goal: id, version: 4, control: 'pause' }), goal.goal),
    /Open Activity/,
  );

  const historyOpening = app.waitForEvent('window');
  await detail.getByRole('button', { name: 'Open conversation history' }).click();
  const history = await historyOpening;
  await expect(history.locator('#trajectory-conversation')).toHaveText(goal.conversation);
  await expect.poll(() => fixture.frames.some((frame) =>
    frame.type === 'conversation.trajectory' && frame.payload.conversation === goal.conversation,
  )).toBe(true);
  // Selecting another goal leaves the exact previously authorized history window navigable.
  await screen.locator('[data-goal-page="next"]').click();
  await select(restricted.goal);
  await expect(detail).toContainText('Conversation content is restricted.');
  await expect(detail.locator('button')).toHaveCount(0);
  await screen.locator('[data-goal-page="previous"]').click();
  await select(goal.goal);

  await pause.click();
  await expect(resume).toBeEnabled();
  const pauseFrames = fixture.frames.filter(({ type }) => type === 'conversation.goal.pause');
  assert.deepEqual(pauseFrames.map(({ payload }) => payload.version), [4]);
  const [paused] = pauseFrames;
  assert.deepEqual(
    fixture.frames.filter(({ type }) => type === 'conversation.goal.get').map(({ payload }) => payload),
    [{ conversation: goal.conversation, requestId: paused.payload.requestId }],
  );
  await detail.locator('#goal-model-calls').fill('40');
  await detail.locator('#goal-activations').fill('24');
  await resume.click();
  await expect(pause).toBeEnabled();
  const [resumed] = fixture.frames.filter(({ type }) => type === 'conversation.goal.resume');
  assert.deepEqual(resumed.payload, {
    goal: goal.goal, conversation: goal.conversation, version: 5,
    requestId: resumed.payload.requestId, maxActivations: 24, maxModelCalls: 40,
  });
  await detail.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(detail.locator('.goal-facts')).toContainText('cancelled');
  await expect(detail.locator('[data-goal-control]')).toHaveCount(0);
  assert.equal(fixture.frames.some(({ type }) => type === 'agent.run'), false);
  assert.deepEqual(errors, []);
  console.log('PASS: Desktop lists and inspects goals, opens exact history, withholds restricted controls, and pauses, resumes and cancels through versioned operations.');
} finally {
  await app?.close();
  await fixture.close();
  await rm(profile, { recursive: true, force: true });
}

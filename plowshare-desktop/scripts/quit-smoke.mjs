import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { protocolFixture } from './protocol-fixture.mjs';

const profile = await mkdtemp(join(tmpdir(), 'plowshare-quit-smoke-'));
const fixture = await protocolFixture();
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'),
  PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'), PLOWSHARE_DESKTOP_PROFILE: profile };
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const diagnostics = [];
  app.process().stderr.on('data', bytes => diagnostics.push(bytes.toString()));
  // Observe a modal attempt without allowing a broken implementation to leave
  // the VM blocked. The assertion below fails on every attempted error dialog.
  await app.evaluate(({ dialog }) => {
    dialog.showErrorBox = (title, text) => process.stderr.write(`UNEXPECTED_QUIT_MODAL:${title}:${text}\n`);
  });
  const main = await app.firstWindow();
  const request = value => main.evaluate(value => window.plowshare.request(value), value);
  await request({ action: 'connect', base: fixture.base, handle: 'fixture', password: 'fixture-password' });
  async function open(value) {
    const opening = app.waitForEvent('window');
    await request(value);
    return opening;
  }
  await open({ action: 'trajectory', conversation: 'fixture-first' });
  const activity = await open({ action: 'activity', view: 'runs' });
  await expect(activity.locator('#activity-title')).toHaveText('Runs');
  await open({ action: 'board-inspection', view: 'board', project: 'Research' });
  const usage = await open({ action: 'usage' });
  await expect(usage.locator('[data-usage-report]')).toContainText('Input tokens');
  await mkdir('build/smoke', { recursive: true });
  await usage.screenshot({ path: 'build/smoke/quit-with-inspectors.png' });
  let expired;
  try {
    await Promise.race([app.close(), new Promise((_, reject) => {
      expired = setTimeout(() => reject(new Error('Desktop quit did not drain within ten seconds')), 10_000);
    })]);
  } finally { clearTimeout(expired); }
  app = undefined;
  assert.equal(diagnostics.join('').includes('UNEXPECTED_QUIT_MODAL:'), false, 'Quit must not attempt a blocking native error dialog');
  assert.equal(diagnostics.join('').includes('The desktop is closing.'), false, 'Closed views must not reenter stopped command admission');
  console.log('PASS: Usage, Board, Activity and trajectory views quit together without reentering commands or opening a native error dialog.');
} finally {
  if (app) app.process().kill('SIGKILL');
  await fixture.close();
  await rm(profile, { recursive: true, force: true });
}

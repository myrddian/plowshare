import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

// Exercise the installed reader with the real desktop and WS binding. This fixture
// proves navigation and read behavior, not publication into a production database.
const profile = await mkdtemp(join(tmpdir(), 'plowshare-manual-smoke-'));
const fixture = await protocolFixture();
const env = { ...process.env, PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'),
  PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'), PLOWSHARE_DESKTOP_PROFILE: profile };
delete env.ELECTRON_RUN_AS_NODE;
const chapter = (id, slug, text) => ({ id, source_name: `Plowshare manual / ${slug}.md`,
  title: slug === '00-index' ? 'Plowshare manual' : 'Hooks', kind: 'source',
  tags: ['plowshare-manual', `manual-chapter-${slug}`], availability: 'active', ordinal: 1, text });
const index = chapter('aaaaaaaa-0000-0000-0000-000000000001', '00-index', '# Plowshare manual\n\n[Read hooks](plowshare-manual:12-hooks)');
const hooks = chapter('bbbbbbbb-0000-0000-0000-000000000002', '12-hooks', '# Hooks\n\n' + 'A retained hook contract.\n\n'.repeat(1400) + 'Final hook placement.');
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const main = await app.firstWindow(), errors = [];
  main.on('pageerror', error => errors.push(error.message));
  await main.locator('#connect-sidebar').click();
  await main.locator('#server-url').fill(fixture.base);
  await main.locator('#handle').fill('fixture');
  await main.locator('#password').fill('fixture-password');
  await main.locator('#submit-connection').click();
  await expect(main.locator('#connection-label')).toHaveText('Connected');
  await main.locator('#manual-open').click();
  await expect.poll(() => app.windows().some(page => page.url() === 'plowshare://app/library.html')).toBe(true);
  const library = app.windows().find(page => page.url() === 'plowshare://app/library.html');
  library.on('pageerror', error => errors.push(error.message));
  const panel = library.locator('#library-manual-panel');
  await expect(panel.locator('[data-detail]')).toContainText('Manual unavailable');
  fixture.setManualChapters([index, hooks]);
  await main.locator('#manual-open').click();
  await expect(panel.locator('[data-rendered-passage]')).toContainText('Plowshare manual');
  await panel.locator('[data-manual-chapter="12-hooks"]').click();
  await expect(panel.locator('[data-rendered-passage]')).toContainText('Final hook placement.');
  await expect(panel.locator('.information-tag-metadata')).not.toHaveAttribute('open', '');
  assert.equal(await panel.evaluate(node => {
    const text = node.querySelector('[data-source]').getBoundingClientRect();
    const tags = node.querySelector('.information-tag-metadata').getBoundingClientRect();
    return tags.top >= text.bottom;
  }), true, 'Tag metadata belongs below the document text.');
  assert.ok(fixture.frames.filter(frame => frame.type === 'information.read' && frame.payload.revision === hooks.id).length >= 2);
  await expect(panel.locator('[data-scope]')).toHaveValue('shared');
  await expect(panel.locator('[data-scope]')).toBeDisabled();
  await library.screenshot({ path: join(tmpdir(), 'plowshare-manual-reader.png') });
  const updated = { ...index, id: 'cccccccc-0000-0000-0000-000000000003', text: '# Updated manual\n\n[Hooks](plowshare-manual:12-hooks)' };
  fixture.setManualChapters([updated, hooks]);
  await main.locator('#manual-open').click();
  await expect(panel.locator('[data-rendered-passage]')).toContainText('Updated manual');
  fixture.setManualChapters([updated, { ...index, id: 'dddddddd-0000-0000-0000-000000000004' }, hooks]);
  await main.locator('#manual-open').click();
  await expect(panel.locator('[data-error]')).toContainText('More than one shared manual');
  await expect(panel.locator('[data-rendered-passage]')).toHaveCount(0);
  await library.locator('#library-documents').click();
  await expect(library.locator('#library-manual-panel')).toBeHidden();
  await main.evaluate(() => window.plowshare.request({ action: 'disconnect' }));
  await main.locator('#manual-open').click();
  await expect(panel.locator('[data-list]')).toContainText('Connect to your server');
  await expect(panel.locator('[data-rendered-passage]')).toHaveCount(0);
  assert.equal(fixture.frames.some(frame => ['information.ask', 'information.upload', 'information.retry', 'information.tags'].includes(frame.type)), false);
  assert.deepEqual(errors, []);
  console.log('Manual Help, current revision links, full chapter reads and ambiguity checks passed.');
} finally {
  if (app) await app.close();
  await fixture.close();
  await rm(profile, { recursive: true, force: true });
}

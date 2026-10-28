import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium, expect } from 'playwright/test';

const built = await build({
  entryPoints: ['src/renderer/information-reader.dom-fixture.ts'],
  bundle: true,
  write: false,
  platform: 'browser',
  format: 'iife',
});
const browser = await chromium.launch({
  headless: true,
  ...(process.env.PLOWSHARE_TEST_BROWSER
    ? { executablePath: process.env.PLOWSHARE_TEST_BROWSER }
    : {}),
});
try {
  for (const kind of ['manual', 'report']) {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.clock.install();
    await page.setContent('<main id="reader"></main>');
    await page.addScriptTag({ content: built.outputFiles[0].text });
    await page.evaluate(
      (kind) => window.informationReaderFixture.open(kind),
      kind,
    );
    await expect
      .poll(() =>
        page.evaluate(() => window.informationReaderFixture.pendingCount()),
      )
      .toBeGreaterThan(0);
    await page.evaluate(() => {
      window.informationReaderFixture.hint();
      window.informationReaderFixture.hint();
      window.informationReaderFixture.hint();
    });
    await expect(page.locator('[data-detail]')).toHaveCSS(
      'visibility',
      'hidden',
    );
    await expect(page.locator('[data-read-status]')).toBeVisible();
    await page.evaluate(() => window.informationReaderFixture.release());
    await page.clock.runFor(300);
    const rendered = page.locator('[data-rendered-passage]');
    await expect(rendered).toContainText('Final retained paragraph.');
    await expect(page.locator('[data-detail]')).toHaveCSS(
      'visibility',
      'visible',
    );
    await expect(page.locator('[data-read-status]')).toBeHidden();
    // Reconciliation without a new hint must preserve both the DOM node and its
    // visibility. A timer alone is not an authority invalidation.
    await page.evaluate(() => {
      window.retainedReader = document.querySelector('[data-rendered-passage]');
      window.readerHidden = false;
      const detail = document.querySelector('[data-detail]');
      new MutationObserver(() => {
        if (detail.style.visibility === 'hidden') window.readerHidden = true;
      }).observe(detail, { attributes: true, attributeFilter: ['style'] });
    });
    await page.locator('[data-ask] input').fill('Keep this draft question');
    await page.clock.runFor(60_000);
    await expect(rendered).toContainText('Final retained paragraph.');
    assert.equal(
      await page.evaluate(
        () =>
          window.retainedReader ===
          document.querySelector('[data-rendered-passage]'),
      ),
      true,
    );
    assert.equal(await page.evaluate(() => window.readerHidden), false);
    await expect(page.locator('[data-ask] input')).toHaveValue(
      'Keep this draft question',
    );
    // A continuous content storm must permit a slow read to complete and retain its authorized
    // text/selection while the next soft refresh validates. This exercises the installed reader.
    await page.evaluate(() => {
      window.informationReaderFixture.hold();
      window.informationReaderFixture.refresh();
    });
    await expect.poll(() => page.evaluate(() => window.informationReaderFixture.pendingCount())).toBeGreaterThan(0);
    for (let tick=0; tick<90; tick++) {
      await page.evaluate(() => window.informationReaderFixture.contentHint());
      await page.clock.runFor(100);
    }
    await page.evaluate(() => window.informationReaderFixture.release());
    await expect(rendered).toContainText('Final retained paragraph.');
    await expect(page.locator('[data-detail]')).toHaveCSS('visibility','visible');
    await page.evaluate(() => {
      window.retainedReader = document.querySelector('[data-rendered-passage]');
      window.readerHidden = false;
    });
    for (let tick=0; tick<30; tick++) {
      await page.evaluate(() => window.informationReaderFixture.contentHint());
      await page.clock.runFor(100);
    }
    assert.equal(await page.evaluate(() => window.retainedReader === document.querySelector('[data-rendered-passage]')),true);
    assert.equal(await page.evaluate(() => window.readerHidden),false);
    await expect(page.locator('[data-ask] input')).toHaveValue('Keep this draft question');
    // A failed read must not be retained as a successful text snapshot, otherwise
    // unchanged metadata can make the Refresh control permanently ineffective.
    await page.evaluate(() => {
      window.informationReaderFixture.failNextRead();
      window.informationReaderFixture.refresh();
    });
    await expect(page.locator('[data-source]')).toContainText(
      'Temporary text read failure',
    );
    await page.evaluate(() => window.informationReaderFixture.refresh());
    await expect(rendered).toContainText('Final retained paragraph.');
    // Revocation during an outstanding text read must reject its late success.
    await page.evaluate(() => {
      window.informationReaderFixture.hold();
      window.informationReaderFixture.refresh();
    });
    await expect
      .poll(() =>
        page.evaluate(() => window.informationReaderFixture.pendingCount()),
      )
      .toBeGreaterThan(0);
    await page.evaluate(() => window.informationReaderFixture.revoke());
    await expect(page.locator('[data-detail]')).toHaveCSS(
      'visibility',
      'hidden',
    );
    await page.evaluate(() => window.informationReaderFixture.release());
    await page.clock.runFor(300);
    await expect(page.locator('[data-error]')).toContainText(
      'permission revoked',
    );
    await expect(rendered).toHaveCount(0);
    await expect(page.locator('[data-read-status]')).toBeHidden();
    assert.deepEqual(errors, []);
    await page.evaluate(() => window.informationReaderFixture.destroy());
    await page.close();
    console.log(
      `PASS: ${kind} recovers interrupted full-text reads, reconciles without flicker, preserves drafts and clears revoked content.`,
    );
  }
} finally {
  await browser.close();
}

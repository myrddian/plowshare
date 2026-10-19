import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium, expect } from 'playwright/test';

// Explicit DOM check; normal node:test exercises the same async owner without a browser install.
const built = await build({
  entryPoints: ['src/renderer/information-collection.dom-fixture.ts'],
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
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.setContent('<main id="collection"></main>');
  await page.addScriptTag({ content: built.outputFiles[0].text });
  await page.evaluate(() => window.informationCollectionFixture.open());
  await expect(page.locator('.information-row')).toContainText(
    'fixture/personal',
  );
  await expect(page.locator('[data-facet-status]')).toContainText(
    'Loading filters',
  );
  await page.locator('[data-detail]').evaluate((detail) => {
    detail.innerHTML =
      '<textarea aria-label="Retained draft">keep this draft</textarea>';
  });
  await page.evaluate(() => {
    window.informationCollectionFixture.refresh();
    window.informationCollectionFixture.refresh();
  });
  assert.equal(
    await page.evaluate(
      () =>
        window.informationCollectionFixture.requests.filter(
          (operation) => operation === 'facets',
        ).length,
    ),
    1,
  );
  await page.evaluate(() => window.informationCollectionFixture.reject(0));
  await expect(page.locator('[data-facet-status]')).toHaveAttribute(
    'role',
    'alert',
  );
  await expect(page.locator('.information-row')).toContainText(
    'fixture/personal',
  );
  await expect(page.getByLabel('Retained draft')).toHaveValue(
    'keep this draft',
  );
  await page.evaluate(() => window.informationCollectionFixture.refresh());
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.informationCollectionFixture.requests.filter(
            (operation) => operation === 'facets',
          ).length,
      ),
    )
    .toBe(2);
  await page.locator('[data-scope]').selectOption('project:Research');
  await expect(page.locator('.information-row')).toContainText(
    'fixture/Research',
  );
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.informationCollectionFixture.requests.filter(
            (operation) => operation === 'facets',
          ).length,
      ),
    )
    .toBe(3);
  await page.evaluate(() => {
    window.informationCollectionFixture.resolve(2, 2);
    window.informationCollectionFixture.resolve(1, 99);
  });
  await expect(page.locator('[data-facet-total]')).toHaveText('· 2 versions');
  await page.evaluate(() => window.informationCollectionFixture.refresh());
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.informationCollectionFixture.requests.filter(
            (operation) => operation === 'facets',
          ).length,
      ),
    )
    .toBe(4);
  await page.locator('[data-detail]').evaluate((detail) => {
    detail.innerHTML = '<textarea aria-label="Retained draft">keep this draft</textarea>';
  });
  await page.evaluate(() => {
    window.informationCollectionFixture.hint();
    window.informationCollectionFixture.hint();
    window.informationCollectionFixture.hint();
  });
  await expect(page.locator('[data-detail]')).toHaveCSS('visibility', 'hidden');
  await expect.poll(() => page.evaluate(() =>
    window.informationCollectionFixture.pendingCount())).toBe(5);
  await expect(page.getByLabel('Retained draft')).toHaveValue('keep this draft');
  await expect(page.locator('[data-detail]')).toHaveCSS('visibility', 'visible');
  await page.evaluate(() => {
    window.informationCollectionFixture.resolve(3, 88);
    window.informationCollectionFixture.resolve(4, 3);
  });
  await expect(page.locator('[data-facet-total]')).toHaveText('· 3 versions');
  await page.evaluate(() => window.informationCollectionFixture.revoke());
  await expect(page.locator('[data-list]')).toHaveText('This collection is unavailable.');
  await expect(page.locator('[data-detail]')).toBeEmpty();
  await expect(page.locator('[data-facet-total]')).toBeEmpty();
  await expect(page.locator('[data-error]')).toContainText('permission revoked');
  await page.evaluate(() => {
    window.informationCollectionFixture.logout();
  });
  await expect(page.locator('#collection')).toBeHidden();
  await expect(page.locator('[data-facet-total]')).toBeEmpty();
  assert.deepEqual(errors, []);
  await page.evaluate(() => window.informationCollectionFixture.destroy());
  console.log(
    'PASS: list renders while facets wait; facet failure preserves list/detail draft; pending reads coalesce; scope/logout reject stale facets; coalesced hints hide and reauthorize without losing drafts; permission loss clears protected data; no unhandled browser rejection.',
  );
} finally {
  await browser.close();
}

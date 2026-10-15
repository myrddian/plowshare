import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';
// This fixture verifies the real SDK/IPC/UI boundary; SQL access is tested separately.
const id = (n) => `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`;
const scope = { kind: 'personal', includeShared: true };
const service = {
  resource: id(1),
  revision: id(11),
  title: 'Billing service',
  sourceName: 'service.md',
  tags: ['billing'],
  scope,
  owner: 'fixture',
};
let runbook = {
  resource: id(2),
  revision: id(12),
  title: 'Refund recovery',
  sourceName: 'refund.md',
  tags: ['billing'],
  scope,
  owner: 'fixture',
};
const initialRunbook = { ...runbook };
let denied = false,
  manager = true,
  version = 1;
const saved = new Map(),
  calls = [];
let added;
const relation = {
  id: id(4),
  source: service.resource,
  target: runbook.resource,
  relation: 'runbook',
  version: 1,
  removed: false,
};
const policy = {
  defaults: { ownerOnly: false, referenceHidden: false, propagate: false },
  mandatory: { ownerOnly: false, referenceHidden: false, propagate: false },
  allowOverrides: true,
  referenceLabel: 'Approved locked reference',
};
const fixture = await protocolFixture({
  information(operation, payload) {
    calls.push({ operation, payload });
    const ok = (payload) => ({ code: 'OK', payload });
    if (operation === 'information.navigation.capabilities')
      return ok({
        capability: 'document-navigation-v1',
        writesEnabled: true,
        maxSteps: 32,
        maxClosure: 1024,
      });
    if (operation === 'information.documents.list')
      return ok({
        documents: payload.relatedTo ? [runbook] : [service, runbook],
        limit: payload.limit ?? 50,
        offset: payload.offset ?? 0,
      });
    if (operation === 'information.documents.facets')
      return ok({
        total: 2,
        facets: {
          kind: [],
          author: [],
          documentAuthor: [],
          tagGroup: [],
          subtype: [],
          when: [],
          tags: [{ value: 'billing', count: 2 }],
          autoTag: [],
          contentType: [],
          contentTypeVersion: [],
        },
        hasMore: {
          kind: false,
          author: false,
          documentAuthor: false,
          tagGroup: false,
          subtype: false,
          when: false,
          tags: false,
          autoTag: false,
          contentType: false,
          contentTypeVersion: false,
        },
        tagGraph: { edges: [], hasMore: false },
      });
    if (operation === 'information.documents.get')
      return ok(
        denied && payload.target.resource === runbook.resource
          ? { kind: 'unavailable' }
          : {
              kind: 'readable',
              document:
                payload.target.resource === service.resource
                  ? service
                  : payload.target.revision === initialRunbook.revision
                    ? initialRunbook
                    : runbook,
            },
      );
    if (operation === 'information.relationships.create') {
      added = {
        id: id(6),
        source: payload.source,
        target: payload.target.resource,
        relation: payload.relation,
        version: 1,
        removed: false,
        ...(payload.target.revision
          ? { pinnedRevision: payload.target.revision }
          : {}),
      };
      return ok(added);
    }
    if (operation === 'information.relationships.remove') {
      added = undefined;
      return ok({
        id: payload.relationship,
        version: 2,
        retainedObligations: false,
      });
    }
    if (operation === 'information.relationships.list')
      return ok({
        connections:
          payload.resource === service.resource &&
          payload.direction === 'outgoing'
            ? [
                ...(added
                  ? [
                      {
                        relationship: added,
                        selection: { kind: 'readable', document: runbook },
                      },
                    ]
                  : []),
                {
                  relationship: relation,
                  selection: denied
                    ? {
                        kind: 'locked',
                        resource: runbook.resource,
                        label: 'Approved locked reference',
                      }
                    : { kind: 'readable', document: runbook },
                },
                {
                  relationship: { ...relation, id: id(5), target: id(3) },
                  selection: {
                    kind: 'locked',
                    resource: id(3),
                    label: 'Approved locked reference',
                  },
                },
              ]
            : [],
        limit: 100,
        offset: 0,
      });
    if (operation === 'information.policy.get')
      return ok({
        target: payload.target,
        version,
        settings: policy,
        effective: policy.defaults,
        canManage: manager,
        retainedObligations: 0,
      });
    if (operation === 'information.policy.set') {
      version++;
      return ok({
        target: payload.target,
        version,
        settings: payload.settings,
        effective: payload.settings.defaults,
        canManage: manager,
        retainedObligations: 0,
      });
    }
    if (operation === 'information.views.list')
      return ok({ views: [...saved.values()] });
    if (operation === 'information.views.get') return ok(saved.get(payload.id));
    if (operation === 'information.views.save') {
      const value = {
        id: payload.id,
        label: payload.label,
        path: payload.path,
        version: 1,
        deleted: false,
      };
      saved.set(value.id, value);
      return ok(value);
    }
    if (operation === 'information.views.delete') {
      saved.delete(payload.id);
      return ok({ id: payload.id, version: 2, retainedObligations: false });
    }
    if (operation === 'information.read')
      return ok({
        revision: payload.revision,
        start: 0,
        end: 21,
        total: 21,
        text: 'Retained runbook text',
      });
    return undefined;
  },
});
const profile = await mkdtemp(join(tmpdir(), 'plowshare-document-navigation-'));
const env = {
  ...process.env,
  PLOWSHARE_CONFIG_DIR: join(profile, 'credentials'),
  PLOWSHARE_DESKTOP_CONFIG: join(profile, 'config'),
  PLOWSHARE_DESKTOP_PROFILE: profile,
};
delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app = await electron.launch({ executablePath, args: [resolve('.')], env });
  const main = await app.firstWindow(),
    errors = [];
  main.on('pageerror', (e) => errors.push(e.message));
  await main.locator('#connect-sidebar').click();
  await main.locator('#server-url').fill(fixture.base);
  await main.locator('#handle').fill('fixture');
  await main.locator('#password').fill('fixture-password');
  await main.locator('#submit-connection').click();
  await expect(main.locator('#connection-label')).toContainText('· Connected');
  await main.locator('#library-open').click();
  await expect
    .poll(() =>
      app.windows().some((p) => p.url() === 'plowshare://app/library.html'),
    )
    .toBe(true);
  const library = app
    .windows()
    .find((p) => p.url() === 'plowshare://app/library.html');
  library.on('pageerror', (e) => errors.push(e.message));
  await library.locator('#library-documents').click();
  await library.locator('#document-sources > summary').click();
  const panel = library.locator(
    '#document-source-panel .information-navigation',
  );
  await expect(panel).toBeVisible();
  await panel.locator(':scope > summary').click();
  await expect(panel.locator('[data-nav-results]')).toContainText(
    'Billing service',
  );
  await panel.locator('[data-nav-open="0"]').click();
  await expect(panel.locator('[data-nav-document]')).toContainText(
    'Approved locked reference',
  );
  await expect(panel.locator('[data-nav-document]')).not.toContainText(
    'Private secret title',
  );
  await panel.locator('details:has([data-nav-link]) > summary').click();
  await panel.locator('[data-nav-link] [name=target]').selectOption('1');
  await panel.locator('[data-nav-link] [name=relation]').fill('related');
  await panel.locator('[data-nav-link] [name=pin]').check();
  await panel.locator('[data-nav-link] button').click();
  await expect.poll(() => Boolean(added)).toBe(true);
  assert.equal(
    calls.find((c) => c.operation === 'information.relationships.create')
      .payload.expectedSourceRevision,
    service.revision,
  );
  assert.equal(
    calls.find((c) => c.operation === 'information.relationships.create')
      .payload.target.revision,
    runbook.revision,
  );
  await panel.locator('[data-nav-remove="0"]').click();
  await expect.poll(() => added).toBeUndefined();
  await panel.locator('[data-nav-traverse] [name=relation]').fill('runbook');
  await panel.locator('[data-nav-traverse] button').click();
  await expect(panel.locator('[data-nav-results]')).toContainText(
    'Refund recovery',
  );
  await panel.locator('[data-nav-open="0"]').click();
  await expect(panel.locator('[data-nav-text]')).toContainText(
    'Retained runbook text',
  );
  await library.screenshot({
    path: resolve('build/document-navigation-smoke.png'),
  });
  await panel.locator('[data-nav-save-form] [name=label]').fill('Refund path');
  await panel.locator('[data-nav-save]').click();
  await expect.poll(() => saved.size).toBe(1);
  runbook = { ...runbook, revision: id(13) };
  await panel.locator('[data-nav-root]').click();
  await panel.locator('[data-nav-views]').selectOption('0');
  await expect
    .poll(() =>
      calls.some(
        (c) =>
          c.operation === 'information.read' && c.payload.revision === id(13),
      ),
    )
    .toBe(true);
  await panel.locator('[data-nav-pin]').click();
  await expect(panel.locator('[data-nav-crumbs]')).toContainText(
    'Refund recovery',
  );
  await panel.locator('[data-nav-policy] summary').click();
  await expect(panel.locator('[data-nav-policy-form]')).toBeVisible();
  manager = false;
  await panel.locator('[data-nav-refresh]').click();
  await panel.locator('[data-nav-policy] summary').click();
  await expect(panel.locator('[data-nav-policy-form]')).toHaveCount(0);
  denied = true;
  await panel.locator('[data-nav-refresh]').click();
  await expect(panel.locator('[data-nav-results]')).toContainText(
    'unavailable',
  );
  await expect(panel.locator('[data-nav-document]')).toBeEmpty();
  assert.equal(
    calls.filter((c) => c.operation === 'information.views.save').length,
    1,
  );
  assert.deepEqual(errors, []);
  console.log(
    'Document navigation smoke passed: paths, live heads, locked references, saved views, pinning and revocation.',
  );
} finally {
  await app?.close();
  await fixture.close();
  await rm(profile, { recursive: true, force: true });
}

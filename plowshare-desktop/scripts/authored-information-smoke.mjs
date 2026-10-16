import { _electron as electron, expect } from 'playwright/test';
import executablePath from 'electron';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { protocolFixture } from './protocol-fixture.mjs';

// A protocol fixture verifies human interactions and exact requests. PostgreSQL tests separately
// verify the server's immutable records, copy transactions, vector slots and spending fences.
const version = '11111111-1111-4111-8111-111111111111',
  type = '22222222-2222-4222-8222-222222222222';
let control = 2,
  lifecycle = 'draft',
  contract,
  latest,
  refuseEdit = false;
const revisions = new Map();
const issue = {
  status: 'INVALID',
  issues: [
    {
      instancePointer: '/severity',
      schemaPointer: '/required',
      keyword: 'required',
      message: 'Required metadata field missing',
    },
  ],
  truncated: false,
};
const fixture = await protocolFixture({
  information(operation, payload) {
    if (operation === 'information.types.capabilities')
      return {
        code: 'OK',
        payload: {
          capability: 'information.contentTypes.v1',
          view: 'content-types-v1',
          profile: 'plowshare-document-metadata/1',
          typedWrites: true,
        },
      };
    if (operation === 'information.types.list')
      return {
        code: 'OK',
        payload: {
          results:
            contract && (payload.drafts || lifecycle !== 'draft')
              ? [
                  {
                    contract,
                    state: lifecycle,
                    controlRevision: control,
                    createdAt: '2026-10-15T00:00:00Z',
                  },
                ]
              : [],
          hasMore: false,
        },
      };
    if (operation === 'information.types.version.create') {
      contract = {
        type,
        version,
        ordinal: 1,
        slug: payload.slug,
        label: payload.label,
        description: payload.description ?? '',
        profile: 'plowshare-document-metadata/1',
        definitionHash: 'a'.repeat(64),
        schema: payload.schema,
        presentation: {},
      };
      return {
        code: 'OK',
        payload: {
          contract,
          state: lifecycle,
          controlRevision: control,
          createdAt: '2026-10-15T00:00:00Z',
        },
      };
    }
    if (operation === 'information.types.activate') {
      assert.equal(payload.expectedControlRevision, control);
      lifecycle = 'active';
      control++;
      return {
        code: 'OK',
        payload: {
          contract,
          state: lifecycle,
          controlRevision: control,
          createdAt: '2026-10-15T00:00:00Z',
        },
      };
    }
    if (operation === 'information.types.validate')
      return {
        code: 'OK',
        payload:
          typeof payload.metadata.severity === 'string'
            ? { status: 'VALID', issues: [], truncated: false }
            : issue,
      };
    if (
      operation === 'information.upload' ||
      operation === 'information.content.revise'
    ) {
      if (operation.endsWith('revise') && refuseEdit) {
        refuseEdit = false;
        return { code: 'CONFLICT', said: 'INFORMATION_REVISION_CONFLICT' };
      }
      if (typeof payload.typing?.metadata?.severity !== 'string')
        return {
          code: 'VALIDATION_FAILED',
          said: 'INFORMATION_METADATA_INVALID',
          payload: issue,
        };
      if (operation.endsWith('revise'))
        assert.equal(payload.expectedRevision, latest);
      latest = operation.endsWith('upload')
        ? 'aaaaaaaa-0000-4000-8000-000000000001'
        : 'bbbbbbbb-0000-4000-8000-000000000002';
      const copied = operation.endsWith('revise');
      revisions.set(latest, {
        metadata: payload.typing.metadata,
        ordinal: copied ? 2 : 1,
        policy: copied ? 'explicit_only' : 'automatic',
      });
      return {
        code: copied ? 'OK' : 'ACCEPTED',
        payload: {
          revision: latest,
          resource: 'cccccccc-0000-4000-8000-000000000003',
          created: true,
        },
      };
    }
    if (
      operation === 'information.list' ||
      operation === 'information.inventory'
    )
      return {
        code: 'OK',
        payload: [...revisions]
          .reverse()
          .map(([id, row]) => ({
            id,
            title: 'Incident',
            source_name: 'incident.md',
            kind: 'source',
            availability: 'active',
            ordinal: row.ordinal,
            contentType: {
              type,
              version,
              ordinal: 1,
              slug: contract.slug,
              label: contract.label,
              definitionHash: contract.definitionHash,
            },
          })),
      };
    if (operation === 'information.status') {
      const row = revisions.get(payload.revision);
      return {
        code: 'OK',
        payload: {
          id: payload.revision,
          title: 'Incident',
          source_name: 'incident.md',
          kind: 'source',
          availability: 'active',
          ordinal: row.ordinal,
          generation: 1,
          can_manage: true,
          inputs: [],
          citations: [],
          events: [],
          allowance_total: row.policy === 'explicit_only' ? 0 : 5,
          allowance_spent: 0,
          steps: [
            'extract',
            'derive',
            'embed',
            'summarise',
            'summary_embed',
            'autoTag',
            'tagGroups',
          ].map((stage, i) => ({
            stage,
            state: i < 5 ? 'ready' : 'pending',
            generation: 1,
            attempt: 0,
            compatible: true,
          })),
        },
      };
    }
    if (operation === 'information.content.get') {
      const row = revisions.get(payload.revision);
      return {
        code: 'OK',
        payload: {
          revision: payload.revision,
          contentType: {
            contract,
            metadataHash: 'b'.repeat(64),
            metadata: row.metadata,
          },
          processingPolicy: row.policy,
          ...(row.policy === 'explicit_only'
            ? {
                startingRevision: 'aaaaaaaa-0000-4000-8000-000000000001',
                revisedBy: 'fixture',
              }
            : {}),
          reused:
            row.policy === 'explicit_only'
              ? [
                  {
                    stage: 'extract',
                    revision: 'aaaaaaaa-0000-4000-8000-000000000001',
                    generation: 1,
                    fingerprint: 'c'.repeat(64),
                  },
                ]
              : [],
          authorisedStages: [],
        },
      };
    }
    if (operation === 'information.read')
      return {
        code: 'OK',
        payload: {
          revision: payload.revision,
          start: 0,
          end: 17,
          total: 17,
          text: 'A retained claim.',
        },
      };
    if (operation === 'information.allowance')
      return { code: 'OK', payload: { changed: true } };
    if (operation === 'information.retry')
      return { code: 'OK', payload: { queued: true } };
    return undefined;
  },
});
const profile = await mkdtemp(join(tmpdir(), 'plowshare-authored-smoke-'));
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
  const main = await app.firstWindow();
  const errors = [];
  main.on('pageerror', (error) => errors.push(error.message));
  await main.locator('#connect-sidebar').click();
  await main.locator('#server-url').fill(fixture.base);
  await main.locator('#handle').fill('fixture');
  await main.locator('#password').fill('fixture-password');
  await main.locator('#submit-connection').click();
  await expect(main.locator('#connection-label')).toContainText('· Connected');
  await main.locator('#library-open').click();
  await expect
    .poll(
      () =>
        app
          .windows()
          .some((page) => page.url() === 'plowshare://app/library.html'),
      { timeout: 30000 },
    )
    .toBe(true);
  const library = app
    .windows()
    .find((page) => page.url() === 'plowshare://app/library.html');
  library.on('pageerror', (error) => errors.push(error.message));
  await library.locator('#library-documents').click();
  await library.locator('#document-sources > summary').click();
  const sources = library.locator('#document-source-panel');
  await sources
    .locator('details:has([data-type-create]) > summary')
    .first()
    .click();
  const create = sources.locator('[data-type-create]');
  await create.locator('[name=slug]').fill('incident');
  await create.locator('[name=label]').fill('Incident');
  await create
    .locator('[name=schema]')
    .fill(
      '{"type":"object","properties":{"severity":{"type":"string"}},"required":["severity"],"additionalProperties":false}',
    );
  await create.locator('button').click();
  await sources.locator('[data-type-transition]').click();
  await expect.poll(() => lifecycle).toBe('active');
  await sources.locator('[data-add-open]').click();
  const add = sources.locator('[data-add]');
  await add.locator('[name=name]').fill('incident.md');
  await add.locator('[name=text]').fill('A retained claim.');
  await add.locator('[name=contentVersion]').selectOption(version);
  await add.locator('button').click();
  await expect(sources.locator('[data-add-error]')).toContainText('/severity');
  assert.equal(revisions.size, 0);
  await add.locator('[name=metadata]').fill('{"severity":"high"}');
  await add.locator('button').click();
  await sources.locator('.information-row').first().click();
  await sources.locator('[data-content] > summary').click();
  await expect(sources.locator('[data-content]')).toContainText(
    'Schema conformance',
  );
  const edit = sources.locator('[data-metadata]');
  await edit.locator('[name=metadata]').fill('{"severity":"low"}');
  await edit.locator('[data-metadata-validate]').click();
  await expect(edit.locator('[data-metadata-validation]')).toContainText(
    'conforms',
  );
  await edit.locator('button[type=submit],button:not([type])').click();
  await expect.poll(() => revisions.size).toBe(2);
  await sources.locator('.information-row').first().click();
  await sources.locator('[data-content] > summary').click();
  await expect(sources.locator('[data-content]')).toContainText(
    'did not schedule',
  );
  await expect(sources.locator('[data-content]')).toContainText('reused from');
  assert.equal(
    fixture.frames.filter(
      (frame) => frame.type === 'agent.run' || frame.type === 'information.ask',
    ).length,
    0,
  );
  refuseEdit = true;
  await sources
    .locator('[data-metadata] [name=metadata]')
    .fill('{"severity":"high"}');
  await sources.locator('[data-metadata] button:not([type])').click();
  await expect(sources.locator('[data-error]')).toContainText('newer revision');
  await expect(sources.locator('[data-reapply-metadata]')).toBeVisible();
  assert.equal(revisions.size, 2);
  await sources.locator('[data-reapply-metadata]').click();
  await expect(sources.locator('[data-metadata] [name=metadata]')).toHaveValue(
    /high/,
  );
  await sources.locator('[data-process] [name=stages]').selectOption('autoTag');
  await sources.locator('[data-process] button').click();
  await expect
    .poll(
      () =>
        fixture.frames.filter((frame) => frame.type === 'information.retry')
          .length,
    )
    .toBe(1);
  assert.deepEqual(
    fixture.frames.find((frame) => frame.type === 'information.retry').payload
      .stages,
    ['autoTag'],
  );
  await mkdir('build/smoke', { recursive: true });
  await library.screenshot({ path: 'build/smoke/authored-information.png' });
  assert.deepEqual(errors, []);
  console.log(
    'Authored Library create/activate/validate/save/revise/conflict/processing smoke passed',
  );
} catch (error) {
  const library = app
    ?.windows()
    .find((page) => page.url() === 'plowshare://app/library.html');
  if (library) {
    console.error(
      'Library errors:',
      await library.locator('[data-error]').allTextContents(),
    );
    await mkdir('build/smoke', { recursive: true });
    await library.screenshot({
      path: 'build/smoke/authored-information-failed.png',
    });
  }
  console.error(
    'Information requests:',
    fixture.frames
      .filter((frame) => frame.type.startsWith('information.'))
      .map((frame) => frame.type)
      .slice(-16),
  );
  throw error;
} finally {
  if (app) await app.close();
  await fixture.close();
  await rm(profile, { recursive: true, force: true });
}

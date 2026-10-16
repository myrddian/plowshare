import assert from 'node:assert/strict';
import test from 'node:test';
import {
  contentHtml,
  metadataObject,
  typeOptions,
} from './authored-information.ts';
import type { InformationTypeContract } from 'plowshare-client-ts/operations/information-content';

const contract: InformationTypeContract = {
  type: 'type',
  version: 'version',
  ordinal: 1,
  slug: 'incident',
  label: '<img src=x onerror=alert(1)>',
  description: '',
  profile: 'plowshare-document-metadata/1',
  definitionHash: 'a'.repeat(64),
  schema: {
    type: 'object',
    properties: { severity: { type: 'string', default: 'high' } },
  },
  presentation: {},
};
await test('metadata reads preserve complete values without materialising defaults or interpreting markup', () => {
  assert.deepEqual(metadataObject('{}'), {});
  assert.throws(
    () => metadataObject('{"severity":"low","severity":"high"}'),
    /Duplicate/,
  );
  const html = contentHtml(
    {
      revision: 'revision',
      contentType: {
        contract,
        metadataHash: 'b'.repeat(64),
        metadata: { severity: '</textarea><script>alert(1)</script>' },
      },
      processingPolicy: 'explicit_only',
      revisedBy: '<script>owner</script>',
      reused: [],
      authorisedStages: [],
    },
    [],
    true,
    true,
  );
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('does not verify'));
  assert.ok(html.includes('data-process'));
  assert.ok(html.includes('data-metadata'));
  assert.ok(html.includes('budget alone does not start processing'));
  assert.equal(
    typeOptions([
      {
        contract,
        state: 'deprecated',
        controlRevision: 2,
        createdAt: '2026-10-15T00:00:00Z',
      },
    ]),
    '',
  );
});

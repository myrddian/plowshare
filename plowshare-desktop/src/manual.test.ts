import test from 'node:test';
import assert from 'node:assert/strict';
import { MANUAL_INDEX, MANUAL_TAG, manualChapterTag, manualSourceName, manualRevision } from './manual.ts';
import type { InformationRow } from 'plowshare-client-ts/operations/information-replies';

const chapter = MANUAL_INDEX;
const row: InformationRow = {
  id: 'aaaaaaaa-0000-0000-0000-000000000001', kind: 'source',
  source_name: manualSourceName(chapter), tags: [MANUAL_TAG, manualChapterTag(chapter)],
};

test('stable chapter identity resolves updated UUIDs and does not depend on titles', () => {
  assert.equal(manualRevision([], chapter), undefined);
  assert.equal(manualRevision([row], chapter), row.id);
  const updated = { ...row, id: 'bbbbbbbb-0000-0000-0000-000000000002', title: 'New title' };
  assert.equal(manualRevision([updated], chapter), updated.id);
});

test('manual lookup rejects competing publishers, foreign chapters and invalid identifiers', () => {
  assert.throws(() => manualRevision([row, row], chapter), /More than one/);
  for (const foreign of [
    { ...row, source_name: 'Another source' }, { ...row, kind: 'report' },
    { ...row, tags: [MANUAL_TAG] }, { ...row, id: 'foreign' },
  ]) assert.throws(() => manualRevision([foreign], chapter), /different manual chapter/);
  for (const invalid of ['', '../index', 'a'.repeat(49), 'Chapter']) {
    assert.throws(() => manualChapterTag(invalid), /valid manual chapter/);
  }
});

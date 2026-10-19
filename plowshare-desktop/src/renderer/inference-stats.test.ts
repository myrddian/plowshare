import { test } from 'node:test';
import assert from 'node:assert/strict';
import { inferenceTokens } from './inference-stats.ts';

await test('inference usage distinguishes provider zero from unrecorded counts', () => {
  const entry = { ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands' };
  assert.equal(
    inferenceTokens(entry),
    'Input tokens: not recorded · Output tokens: not recorded',
  );
  assert.equal(
    inferenceTokens({ ...entry, promptTokens: 0, completionTokens: 0 }),
    'Input tokens: 0 · Output tokens: 0',
  );
  assert.equal(
    inferenceTokens({ ...entry, completionTokens: 12 }),
    'Input tokens: not recorded · Output tokens: 12',
  );
});

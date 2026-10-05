import assert from 'node:assert/strict';
import test from 'node:test';
import { retainedReport } from './retained-report.ts';
const report = '00000000-0000-0000-0000-000000000099',
  audit = '00000000-0000-0000-0000-000000000098';
await test('deep research handoff selects the main retained report rather than another UUID or audit', () => {
  const receipt = `Research completed: 2 objectives and 3 findings. The full report with source links is retained as information revision ${report}. Read it with information_read {"operation":"read","revision":"${report}","offset":0,"limit":8192}; continue from each returned end until total. The separate audit is information revision ${audit}.`;
  assert.equal(retainedReport(receipt), report);
  assert.equal(
    retainedReport(`A source is information revision ${audit}.`),
    undefined,
  );
  assert.equal(retainedReport(undefined), undefined);
  assert.equal(
    retainedReport(receipt.replace(report, 'not-a-revision')),
    undefined,
  );
});

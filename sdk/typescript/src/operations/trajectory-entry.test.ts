import { expect, it } from 'vitest';
import type { EntryView } from './conversation-replies.ts';
import { trajectoryEntry } from './trajectory-entry.ts';
import { stepsOf, callStanding } from './trajectory.ts';
const base: EntryView = {
  ordinal: 1,
  turnOrdinal: 1,
  kind: 'answer',
  excerpt: '',
  length: 0,
  cut: false,
  ejectedAt: null,
  supersededBy: null,
  toolCallId: null,
  toolCalls: [],
  handle: null,
  recordedAt: null,
  tookMillis: null,
  dispatch: null,
  wireModel: null,
  completion: null,
  speaker: null,
  speakerName: null,
  outcome: null,
};
it('pairs retained DTO calls/results without losing provenance or compaction history', () => {
  const rows: EntryView[] = [
    {
      ...base,
      supersededBy: 4,
      job: 'job',
      source: { kind: 'unknown', reference: null },
      toolCalls: [
        {
          id: 'tool',
          name: 'run',
          arguments: '[]',
          length: 2,
          cut: false,
          salient: null,
          opened: { conversation: 'child', agent: 'worker' },
        },
      ],
    },
    {
      ...base,
      ordinal: 2,
      kind: 'tool_result',
      toolCallId: 'tool',
      outcome: 'exit 1',
      excerpt: 'failed',
    },
  ];
  const steps = stepsOf(rows.map(trajectoryEntry));
  expect(steps).toHaveLength(1);
  const call = steps[0]!;
  expect(call.entry.state).toBe('folded');
  expect(call.entry.job).toBe('job');
  expect(call.entry.source).toEqual({ kind: 'unknown', reference: null });
  if (call.kind !== 'call') throw new Error('Expected a paired call');
  expect(callStanding(call)).toBe('fail');
  expect(call.opened?.conversation).toBe('child');
  expect(call.result?.text).toBe('failed');
});
it('keeps unknown legacy metadata absent, preserves orphan results and distinguishes ejection', () => {
  const entry = trajectoryEntry({
    ...base,
    kind: 'tool_result',
    toolCallId: 'earlier-page',
    ejectedAt: 'time',
    excerpt: null,
  });
  expect(entry).not.toHaveProperty('job');
  expect(entry).not.toHaveProperty('speaker');
  expect(entry).not.toHaveProperty('text');
  expect(entry.state).toBe('ejected');
  expect(stepsOf([entry])[0]?.kind).toBe('note');
});

import { describe, expect, it } from 'vitest';
import type { Entry } from './client-views.ts';
import { stepsOf, turnsOf } from './trajectory.ts';

describe('human trajectory after model compaction', () => {
  it('retains folded messages and paired tool results across repeated folds', () => {
    const entries: Entry[] = [
      {
        ordinal: 1,
        turnOrdinal: 1,
        kind: 'utterance',
        state: 'folded',
        supersededBy: 4,
        text: 'Original question',
      },
      {
        ordinal: 2,
        turnOrdinal: 1,
        kind: 'answer',
        state: 'folded',
        supersededBy: 4,
        calls: [
          {
            id: 'lookup',
            name: 'search',
            arguments: '{}',
            cut: false,
            length: 2,
          },
        ],
        tookMillis: 100,
      },
      {
        ordinal: 3,
        turnOrdinal: 1,
        kind: 'tool_result',
        state: 'folded',
        supersededBy: 4,
        toolCallId: 'lookup',
        text: 'Original evidence',
        outcome: 'ok',
        tookMillis: 25,
      },
      {
        ordinal: 4,
        turnOrdinal: 2,
        kind: 'summary',
        state: 'folded',
        supersededBy: 6,
        text: 'First model summary',
      },
      {
        ordinal: 5,
        turnOrdinal: 2,
        kind: 'answer',
        state: 'folded',
        supersededBy: 6,
        text: 'Original answer',
      },
      {
        ordinal: 6,
        turnOrdinal: 3,
        kind: 'turn_summary',
        state: 'stands',
        text: 'Later model summary',
      },
    ];
    const steps = stepsOf([...entries].reverse());
    expect(steps.map((step) => step.kind)).toEqual([
      'person',
      'call',
      'fold',
      'answer',
      'fold',
    ]);
    const call = steps[1];
    expect(call?.kind === 'call' && call.result?.text).toBe(
      'Original evidence',
    );
    expect(call?.kind === 'call' && call.pending).toBe(false);
    expect(steps[0]?.entry.text).toBe('Original question');
    expect(steps[3]?.entry.text).toBe('Original answer');
    expect(turnsOf(steps)[0]).toMatchObject({
      modelCalls: 1,
      modelMillis: 100,
      toolMillis: 25,
      calls: 1,
    });
    expect(entries[0]?.supersededBy).toBe(4);
  });
  it('keeps an orphaned folded result visible when its call is on an earlier page', () => {
    expect(
      stepsOf([
        {
          ordinal: 3,
          turnOrdinal: 1,
          kind: 'tool_result',
          state: 'folded',
          supersededBy: 4,
          toolCallId: 'lookup',
          text: 'Original evidence',
        },
      ])[0],
    ).toMatchObject({ kind: 'note', entry: { text: 'Original evidence' } });
  });
});

describe('per-inference trajectory', () => {
  it('retains a tool-only model response and counts parallel tools as one inference', () => {
    const entries: Entry[] = [
      {
        ordinal: 1,
        turnOrdinal: 1,
        kind: 'answer',
        state: 'stands',
        dispatch: 'primary',
        completion: 'called_tools',
        text: '',
        tookMillis: 250,
        promptTokens: 1200,
        completionTokens: 40,
        calls: ['first', 'second'].map((id) => ({
          id,
          name: 'run',
          arguments: '{}',
          length: 2,
          cut: false,
        })),
      },
      ...['first', 'second'].map((id, index) => ({
        ordinal: index + 2,
        turnOrdinal: 1,
        kind: 'tool_result',
        state: 'stands',
        toolCallId: id,
        tookMillis: 10,
        outcome: 'ok',
      })),
      {
        ordinal: 4,
        turnOrdinal: 1,
        kind: 'answer',
        state: 'stands',
        text: 'Approve running ... [apr_fake]',
      },
    ];
    const steps = stepsOf(entries);
    expect(steps.map((s) => s.kind)).toEqual([
      'answer',
      'call',
      'call',
      'answer',
    ]);
    expect(steps[0]?.entry.promptTokens).toBe(1200);
    expect(turnsOf(steps)[0]).toMatchObject({
      modelCalls: 1,
      modelMillis: 250,
      toolMillis: 20,
      calls: 2,
    });
  });
});

it('does not count an orphaned timed tool result as model inference', () => {
  const steps = stepsOf([
    {
      ordinal: 3,
      turnOrdinal: 1,
      kind: 'tool_result',
      state: 'stands',
      tookMillis: 50,
      toolCallId: 'earlier',
    },
  ]);
  expect(turnsOf(steps)[0]).toMatchObject({ modelCalls: 0, modelMillis: 0 });
});

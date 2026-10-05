import { describe, expect, it } from 'vitest';

import type { Entry } from './session.ts';
import { plainOf } from './tints.ts';
import {
  cutLines,
  failureCut,
  outcomeClass,
  stepsOf,
  stripOf,
  turnsOf,
} from './trajectory.ts';

const row = (
  ordinal: number,
  kind: string,
  extra: Partial<Entry> = {},
): Entry => ({
  ordinal,
  turnOrdinal: 1,
  kind,
  state: 'stands',
  text: '',
  ...extra,
});

const asking = (ordinal: number, ...ids: string[]): Entry =>
  row(ordinal, 'answer', {
    text: 'let me look',
    tookMillis: 3000,
    asked: ids.length,
    calls: ids.map((id) => ({
      id,
      name: 'run',
      arguments: `{"command":"${id}"}`,
      length: 20,
      cut: false,
      salient: id,
    })),
  });

describe('entries turned into steps', () => {
  it("pairs each call with its result, keeps the answer's words, and leaves an unanswered call pending", () => {
    const steps = stepsOf([
      row(1, 'utterance', { text: 'run the tests' }),
      row(2, 'thinking', { text: 'I should run them' }),
      asking(3, 'c1', 'c2'),
      row(4, 'tool_result', {
        toolCallId: 'c1',
        outcome: 'exit 1',
        tookMillis: 4200,
        text: 'FAILED',
      }),
    ]);
    expect(steps.map((step) => step.kind)).toEqual([
      'person',
      'reasoning',
      'answer',
      'call',
      'call',
    ]);
    const [, , , first, second] = steps;
    expect(first).toMatchObject({
      kind: 'call',
      id: 'c1',
      tool: 'run',
      salient: 'c1',
      pending: false,
    });
    expect(first?.kind === 'call' ? first.result?.outcome : undefined).toBe(
      'exit 1',
    );
    expect(second).toMatchObject({
      kind: 'call',
      id: 'c2',
      pending: true,
      unanswered: false,
    });
  });

  it('says a call that its turn outlived was never answered, rather than still going', () => {
    const steps = stepsOf([
      asking(1, 'c1'),
      row(2, 'answer', { text: 'gave up', turnOrdinal: 1 }),
    ]);
    expect(steps[1]).toMatchObject({
      kind: 'call',
      pending: false,
      unanswered: true,
    });
  });

  it('keeps what a model fold superseded, draws the fold, and hangs a hook on the call it followed', () => {
    const steps = stepsOf([
      row(1, 'utterance', { text: 'old', supersededBy: 5 }),
      asking(2, 'c1'),
      row(3, 'tool_result', { toolCallId: 'c1', outcome: 'ok' }),
      row(4, 'hook', { text: 'tool.post: redacted' }),
      row(5, 'summary', { text: 'earlier: the tests were run' }),
    ]);
    expect(steps.map((step) => step.kind)).toEqual([
      'person',
      'answer',
      'call',
      'fold',
    ]);
    expect(
      steps[2]?.kind === 'call'
        ? steps[2].hooks.map((hook) => hook.ordinal)
        : [],
    ).toEqual([4]);
  });

  it('keeps a result whose call is on an earlier page as a row of its own', () => {
    expect(
      stepsOf([row(9, 'tool_result', { toolCallId: 'gone', outcome: 'ok' })])[0]
        ?.kind,
    ).toBe('note');
  });
});

describe('outcomes, turns, cuts and the strip', () => {
  it('classes an outcome word', () => {
    expect(['ok', 'answered', 'exit 0'].map(outcomeClass)).toEqual([
      'ok',
      'ok',
      'ok',
    ]);
    // `ToolLines.RAN`: the display cut took the status line, so the outcome is not known.
    expect(outcomeClass('ran')).toBe('unknown');
    expect(
      ['exit 1', 'refused', 'denied', 'error', 'timed out', 'stuck'].map(
        outcomeClass,
      ),
    ).toEqual(['fail', 'fail', 'fail', 'fail', 'fail', 'fail']);
    expect(['asked', 'cancelled'].map(outcomeClass)).toEqual([
      'waiting',
      'waiting',
    ]);
    expect(outcomeClass(undefined)).toBe('unknown');
  });

  it("sums a turn's model time, tool time, calls and failures, counting a model call once", () => {
    const turns = turnsOf(
      stepsOf([
        asking(1, 'c1', 'c2'),
        row(2, 'tool_result', {
          toolCallId: 'c1',
          outcome: 'ok',
          tookMillis: 100,
        }),
        row(3, 'tool_result', {
          toolCallId: 'c2',
          outcome: 'refused',
          tookMillis: 50,
        }),
        row(4, 'answer', { text: 'done', tookMillis: 1000 }),
      ]),
    );
    expect(turns).toEqual([
      expect.objectContaining({
        ordinal: 1,
        modelCalls: 2,
        modelMillis: 4000,
        toolMillis: 150,
        calls: 2,
        failed: 1,
      }),
    ]);
  });

  it('cuts a result to its head and tail and counts what it hid', () => {
    const text = ['a', 'b', 'c', 'd', 'e', 'f'].join('\n');
    expect(cutLines(text, 2, 2)).toEqual({
      head: ['a', 'b'],
      tail: ['e', 'f'],
      hidden: 2,
    });
    expect(cutLines('a\nb\n', 2, 2)).toEqual({
      head: ['a', 'b'],
      tail: [],
      hidden: 0,
    });
    // Cleaned to draw: a tab is spaces, a carriage return a line break, an escape gone.
    expect(cutLines('\tat Foo\r\n\u001b[31mred\u001b[0m', 2, 2)).toEqual({
      head: ['    at Foo', 'red'],
      tail: [],
      hidden: 0,
    });
  });

  it('cuts a failure around the line that says why, not the status line and banner a run opens with', () => {
    const run = [
      'exit 1',
      '--- stdout ---',
      '> Task :server:test',
      'TokenizerTest > counts_multibyte() FAILED',
      '    expected: <3> but was: <9>',
      '    at TokenizerTest.java:41',
      '    at Method.invoke',
      '41 tests completed, 1 failed',
      'BUILD FAILED in 4s',
    ].join('\n');
    expect(failureCut(run, 2, 2)).toEqual({
      head: [
        'TokenizerTest > counts_multibyte() FAILED',
        '    expected: <3> but was: <9>',
      ],
      tail: ['41 tests completed, 1 failed', 'BUILD FAILED in 4s'],
      hidden: 5,
    });
    expect(failureCut(run, 3, 3).head).toEqual([
      'TokenizerTest > counts_multibyte() FAILED',
      '    expected: <3> but was: <9>',
      '    at TokenizerTest.java:41',
    ]);
    // No line says why: the head and tail, as any other result.
    const quiet = ['exit 2', 'a', 'b', 'c', 'd', 'e'].join('\n');
    expect(failureCut(quiet, 2, 2)).toEqual(cutLines(quiet, 2, 2));
    // The cause is among the last lines: the tail shows it, and the span sits above it rather than repeat it.
    expect(
      failureCut(['a', 'b', 'c', 'd', 'e', 'BUILD FAILED'].join('\n'), 2, 2),
    ).toEqual({ head: ['c', 'd'], tail: ['e', 'BUILD FAILED'], hidden: 2 });
    // Too short to cut: all of it.
    expect(failureCut('Error: no\nmore', 2, 2)).toEqual({
      head: ['Error: no', 'more'],
      tail: [],
      hidden: 0,
    });
  });

  it('draws model and tool time as a two-lane strip, with the cursor on the tool lane', () => {
    const steps = stepsOf([
      asking(1, 'c1'),
      row(2, 'tool_result', {
        toolCallId: 'c1',
        outcome: 'exit 1',
        tookMillis: 3000,
      }),
    ]);
    const strip = stripOf(steps, 6, 1);
    expect(plainOf(strip.model)).toBe('▀▀▀   ');
    expect(plainOf(strip.tools)).toBe('   ▲▀▀');
    expect(strip.tools.find((each) => each.text.includes('▀'))?.role).toBe(
      'fail',
    );
  });
});

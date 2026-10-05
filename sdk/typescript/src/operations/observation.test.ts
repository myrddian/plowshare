import { describe, expect, it } from 'vitest';
import {
  followCommand,
  jobProgress,
  observedConversation,
  observedJob,
} from './observation.ts';

describe('persistent observation', () => {
  it('matches job identity and keeps token parts without confusing progress with outcomes', () => {
    expect(
      observedJob({ job: 'a', kind: 'ended', ending: 'ANSWERED' }, 'a'),
    ).toBe(true);
    expect(observedJob({ job: 'b', kind: 'ended' }, 'a')).toBe(false);
    expect(
      observedJob({ job: 'a', part: 'THINKING', text: 'reason' }, 'a'),
    ).toBe(true);
    expect(observedJob({ job: 'a', part: 'ANSWER', text: 'text' }, 'a')).toBe(
      true,
    );
    expect(observedJob({ job: 'a', part: 'future', text: 'text' }, 'a')).toBe(
      false,
    );
    expect(observedJob({ job: 'a' }, 'a')).toBe(false);
  });
  it('bounds early events, drains only the accepted job, and stops delivery after closing', () => {
    const output: unknown[] = [];
    const progress = jobProgress((push) => output.push(push), 3);
    progress.receive({ job: 'a', kind: 'started' });
    progress.receive({ job: 'a', part: 'THINKING', text: 'one' });
    progress.receive({ job: 'b', kind: 'ended' });
    progress.receive({ job: 'a', part: 'ANSWER', text: 'two' });
    progress.identify('a');
    expect(output).toEqual([
      { job: 'a', part: 'THINKING', text: 'one' },
      { job: 'a', part: 'ANSWER', text: 'two' },
    ]);
    progress.receive({ job: 'b', kind: 'ended' });
    progress.close();
    progress.receive({ job: 'a', kind: 'ended' });
    expect(output).toHaveLength(2);
  });
  it('follows one explicit conversation with valid growth cursors', () => {
    expect(followCommand('conversation follow c')).toEqual({
      type: 'conversation.follow',
      payload: { conversation: 'c' },
    });
    expect(followCommand('conversation chat c')).toBeUndefined();
    for (const line of [
      'conversation follow',
      'conversation follow c d',
      'conversation follow {"conversation":"c"}',
    ])
      expect(() => followCommand(line)).toThrow('conversation id');
    expect(
      observedConversation(
        { kind: 'conversation.appended', conversation: 'c', through: 10 },
        'c',
      ),
    ).toBe(true);
    for (const push of [
      { kind: 'conversation.appended', conversation: 'd', through: 10 },
      { kind: 'conversation.appended', conversation: 'c', through: -1 },
      { kind: 'conversation.appended', conversation: 'c', through: 1.5 },
      { job: 'c', kind: 'ended' },
    ])
      expect(observedConversation(push, 'c')).toBe(false);
  });
});

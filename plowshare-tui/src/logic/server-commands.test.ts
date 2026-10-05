import { describe, expect, it } from 'vitest';
import { completing, typed } from './session.ts';

describe('server commands use conversation transport', () => {
  it('preserves qualified invocation text for server-owned validation and binding', () => {
    for (const text of [
      ' /skill:review --mode=SUMMARISED Review\nthis change',
      '/orchestration:research Investigate this',
      '/skill:unavailable request',
    ]) {
      expect(typed(text)).toEqual({ kind: 'utterance', text });
    }
    expect(typed('/help')).toEqual({ kind: 'help' });
    expect(typed('/unknown')).toEqual({ kind: 'unknown', named: '/unknown' });
  });
  it('completes only the server offers supplied with the selected agent', () => {
    expect(completing('/skill:r', [], ['/skill:review']).matches).toEqual([
      '/skill:review',
    ]);
    expect(completing('/skill:r', [], []).matches).toEqual([]);
    expect(
      completing('/orchestration:r', [], ['/orchestration:research']).matches,
    ).toEqual(['/orchestration:research']);
  });
  it('refreshes the selected agent catalog for command and skill listings in its own project', () => {
    expect(typed('/commands', 'Research')).toEqual({
      kind: 'command-catalog',
      only: 'all',
      ask: { type: 'agent.list', payload: { project: 'Research' } },
    });
    expect(typed('/skills', 'Writing')).toEqual({
      kind: 'command-catalog',
      only: 'skill',
      ask: { type: 'agent.list', payload: { project: 'Writing' } },
    });
    expect(completing('/ski', []).matches).toContain('/skills');
    expect(completing('/comm', []).matches).toContain('/commands');
  });
});

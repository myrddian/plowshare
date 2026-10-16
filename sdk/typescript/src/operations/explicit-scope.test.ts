import { describe, expect, it } from 'vitest';
import { decodeRequest } from './schema.ts';
import { dispatch } from './direct.ts';

/** SDK admission must not infer a runtime destination from human credentials. */
describe('explicit runtime scope', () => {
  const starts = [
    ['conversation.open', {}],
    ['conversation.list', {}],
    ['conversation.latest', { agent: 'worker' }],
    ['agent.run', { agent: 'worker', task: 'work' }],
    ['agent.run', { agent: 'worker', task: 'work', newConversation: true }],
    [
      'orchestration.start',
      {
        agent: 'worker',
        definition: 'workflow',
        request: 'work',
        requestId: '11111111-1111-1111-1111-111111111111',
      },
    ],
  ] as const;
  it.each(starts)(
    'refuses implicit or malformed scope for %s before transport',
    async (operation, payload) => {
      let submissions = 0;
      const transport = {
        async ask() {
          submissions++;
          throw new Error('Unexpected submission');
        },
      };
      for (const project of [undefined, null, '', ' ', ' project ']) {
        if (
          project === null &&
          ['conversation.list', 'conversation.latest'].includes(operation)
        ) {
          expect(
            decodeRequest(operation, { ...payload, project }).payload,
          ).toEqual({ ...payload, project });
          continue;
        }
        const value = project === undefined ? payload : { ...payload, project };
        expect(() => decodeRequest(operation, value)).toThrow();
      }
      // dispatch also checks directly constructed requests before calling an adapter.
      if (operation === 'agent.run') {
        await expect(
          dispatch(transport, {
            type: 'agent.run',
            payload: { agent: 'worker', task: 'work' },
          }),
        ).rejects.toThrow();
      }
      expect(submissions).toBe(0);
    },
  );
  it.each(starts)(
    'accepts an explicitly selected project or Personal scope for %s',
    (operation, payload) => {
      for (const project of ['knowledge-application', 'personal:6f776e6572']) {
        expect(
          decodeRequest(operation, { ...payload, project }).payload,
        ).toEqual({ ...payload, project });
      }
    },
  );
  it('continues a conversation in its own scope and refuses conflicting selectors', () => {
    const payload = {
      agent: 'worker',
      task: 'work',
      conversation: 'cnv_existing',
    };
    expect(decodeRequest('agent.run', payload).payload).toEqual(payload);
    expect(() =>
      decodeRequest('agent.run', { ...payload, project: 'other' }),
    ).toThrow();
    expect(() =>
      decodeRequest('agent.run', { ...payload, newConversation: true }),
    ).toThrow();
  });
});

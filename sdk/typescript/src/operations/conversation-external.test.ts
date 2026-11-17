import { describe, expect, it } from 'vitest';
import { decodeReply, decodeRequest } from './schema.ts';
import { dispatch, request, resultOf } from './direct.ts';

const locator = {
  project: 'house',
  source: 'ha.installation',
  externalConversation: 'opaque/CASE:external',
};

describe('external conversation facade contracts', () => {
  it('requires explicit account-local external selectors without rewriting them', () => {
    expect(decodeRequest('conversation.open.ex', locator).payload).toEqual(
      locator,
    );
    expect(() =>
      decodeRequest('conversation.open.ex', {
        ...locator,
        project: 'p'.repeat(1024),
      }),
    ).not.toThrow();
    const run = {
      ...locator,
      agent: 'worker',
      task: '  Original\ntext  ',
      maxModelCalls: 30,
      images: [],
    };
    expect(decodeRequest('agent.run.ex', run).payload).toEqual(run);
    for (const override of [
      { source: 'UPPER' },
      { source: 'a'.repeat(129) },
      { externalConversation: 'has space' },
      { externalConversation: 'é' },
      { externalConversation: '' },
      { project: null },
      { project: ' house' },
      { project: 'has\ncontrol' },
      { project: 'has\u007fcontrol' },
      { project: 'has\u0085control' },
      { project: 'has\u2028separator' },
      { project: 'has\u2029separator' },
      { project: 'p'.repeat(1025) },
      { owner: 'someone' },
      { conversation: 'cnv_internal' },
      { maxModelCalls: 0 },
      { maxTurns: 1.5 },
      { noBudget: null },
    ])
      expect(() =>
        decodeRequest('conversation.open.ex', { ...locator, ...override }),
      ).toThrow();
    for (const override of [
      { newConversation: false },
      { conversation: null },
      { images: ['img'] },
      { images: null },
      { maxModelCalls: null },
      { noTurnCap: null },
      { maxTurns: 4, noTurnCap: true },
    ])
      expect(() =>
        decodeRequest('agent.run.ex', { ...run, ...override }),
      ).toThrow();
    expect(() =>
      decodeRequest('agent.run.ex', { ...run, session: null }),
    ).not.toThrow();
  });

  it('submits a single request and keeps an uncertain mutation unreplayed', async () => {
    let sends = 0;
    const asked = request('agent.run.ex', {
      ...locator,
      agent: 'worker',
      task: 'work',
    });
    await expect(
      dispatch(
        {
          ask: () => {
            sends += 1;
            return Promise.reject(
              new Error('delivery unknown after submission'),
            );
          },
        },
        asked,
      ),
    ).rejects.toThrow('delivery unknown');
    expect(sends).toBe(1);
  });

  it('requires the ordinary accepted job and its resolved internal conversation receipt', () => {
    const asked = request('agent.run.ex', {
      ...locator,
      agent: 'worker',
      task: 'work',
    });
    const outcome = {
      code: 'ACCEPTED' as const,
      payload: {
        id: 'job_accepted',
        agent: 'worker',
        conversation: 'cnv_resolved',
      },
    };
    expect(resultOf(asked, outcome)).toMatchObject({
      kind: 'accepted',
      job: 'job_accepted',
      conversation: 'cnv_resolved',
    });
    for (const conversation of [null, undefined, 2, 'job_other'])
      expect(
        resultOf(asked, {
          ...outcome,
          payload: { ...outcome.payload, conversation },
        }),
      ).toMatchObject({ kind: 'invalid-response', job: 'job_accepted' });
    expect(decodeReply('agent.run.ex', outcome.payload).conversation).toBe(
      'cnv_resolved',
    );
    for (const conversation of [null, undefined, 2, 'job_other'])
      expect(() =>
        decodeReply('agent.run.ex', { ...outcome.payload, conversation }),
      ).toThrow();
    // Ordinary older started-job replies retain their existing optional metadata contract.
    expect(decodeReply('agent.run', { id: 'job_ordinary' }).id).toBe(
      'job_ordinary',
    );
    expect(resultOf(asked, { code: 'NOT_FOUND', said: 'No alias' }).kind).toBe(
      'refused',
    );
  });

  it('requires OK for Open-Ex while preserving real refusal outcomes', () => {
    const asked = request('conversation.open.ex', locator);
    for (const code of ['CREATED', 'ACCEPTED', 'NO_CONTENT'] as const)
      expect(resultOf(asked, { code, payload: {} }).kind).toBe(
        'invalid-response',
      );
    expect(resultOf(asked, { code: 'NOT_FOUND', said: 'No alias' }).kind).toBe(
      'refused',
    );
  });

  it('checks the complete canonical Open-Ex view without normalizing names or restricting title prose', () => {
    const view = {
      id: ' cnv_external ',
      project: ' renamed-project ',
      maxModelCalls: 0,
      modelCallsSpent: 0,
      maxTurns: null,
      noTurnCap: false,
      noBudget: false,
      title: 'x'.repeat(32769),
    };
    expect(
      decodeReply('conversation.open.ex', { ...view, future: 'ignored' }),
    ).toEqual(view);
    for (const field of [
      'maxModelCalls',
      'modelCallsSpent',
      'maxTurns',
    ] as const) {
      for (const value of [null, 0, 1.0, 2147483648, Number.MAX_SAFE_INTEGER])
        expect(
          decodeReply('conversation.open.ex', { ...view, [field]: value })[
            field
          ],
        ).toBe(value);
      for (const value of [
        -1,
        1.5,
        Number.MAX_SAFE_INTEGER + 1,
        Number.NaN,
        Number.POSITIVE_INFINITY,
      ])
        expect(() =>
          decodeReply('conversation.open.ex', { ...view, [field]: value }),
        ).toThrow();
    }
    for (const field of Object.keys(view)) {
      const absent: Record<string, unknown> = { ...view };
      delete absent[field];
      expect(() => decodeReply('conversation.open.ex', absent)).toThrow();
    }
    for (const field of ['id', 'project'])
      for (const value of ['', ' ', '\ufeff', '\u00a0'])
        expect(() =>
          decodeReply('conversation.open.ex', { ...view, [field]: value }),
        ).toThrow();
    expect(
      decodeReply('conversation.open.ex', {
        ...view,
        project: null,
        title: '\0exact\ntext',
      }),
    ).toMatchObject({ project: null, title: '\0exact\ntext' });
    // The new Ex semantic gate does not retrofit older ordinary decoder policy.
    expect(
      decodeReply('conversation.open', { ...view, maxModelCalls: -1 })
        .maxModelCalls,
    ).toBe(-1);
  });

  it('requires a nonblank Ex run job identity with the existing accepted-job semantics', () => {
    const receipt = {
      id: ' original-id ',
      agent: '',
      conversation: 'cnv_\0preserved',
    };
    expect(decodeReply('agent.run.ex', receipt)).toEqual(receipt);
    for (const id of ['', ' ', '\ufeff', '\u00a0', null, 2])
      expect(() => decodeReply('agent.run.ex', { ...receipt, id })).toThrow();
    expect(decodeReply('agent.run', { id: '' }).id).toBe('');
  });
});

import { describe, expect, it } from 'vitest';
import { asking } from '../binding/envelope.ts';
import { actingOptions } from '../binding/acting.ts';
import { decodeReply, decodePayload } from './schema.ts';

describe('request-scoped acting selection', () => {
  const first = '00000000-0000-0000-0000-000000000001';
  const second = '00000000-0000-0000-0000-000000000002';
  it('keeps interleaved subjects and ordinary requests separate', () => {
    const options = { actingFor: { context: first } };
    const selected = asking(
      '1',
      'conversation.turns',
      { conversation: 'a' },
      options,
    );
    options.actingFor.context = second;
    expect(selected.actingFor?.context).toBe(first);
    expect(
      asking(
        '2',
        'conversation.turns',
        { conversation: 'b' },
        { actingFor: { context: second } },
      ).actingFor?.context,
    ).toBe(second);
    expect(asking('3', 'conversation.turns', {})).not.toHaveProperty(
      'actingFor',
    );
  });
  it('keeps the capacity-wait choice local and boolean', () => {
    expect(actingOptions({ nonBlocking: true })).toEqual({
      nonBlocking: true,
    });
    expect(actingOptions({ nonBlocking: false })).toEqual({
      nonBlocking: false,
    });
    // Coercion here would buy load shedding by accident, and the two forms differ
    // in whether a caller is told the server refused its request.
    for (const invalid of ['yes', 1, null])
      expect(() =>
        actingOptions({ nonBlocking: invalid } as unknown as {
          nonBlocking?: boolean;
        }),
      ).toThrow();
    // A local choice about waiting is nobody else's business: it never serializes.
    expect(
      asking('1', 'project.list', {}, { nonBlocking: true }),
    ).not.toHaveProperty('nonBlocking');
  });
  it('refuses malformed selection and proof exchange before submission', () => {
    expect(() =>
      actingOptions({ actingFor: { context: '1-1-1-1-1' } }),
    ).toThrow();
    expect(() =>
      decodePayload('acting.context.open', {
        application: 'bridge',
        grant: 'users',
        proof: { kind: 'acting-token', credential: 'psa_' + 'a'.repeat(48) },
      }),
    ).not.toThrow();
    expect(() =>
      decodePayload('acting.context.close', { context: 'bad' }),
    ).toThrow();
    expect(() =>
      decodePayload('acting.context.open', {
        application: 'personal:alice',
        grant: 'users',
        proof: { kind: 'acting-token', credential: 'psa_' + 'a'.repeat(48) },
      }),
    ).toThrow();
  });
  it('validates durable turn admission and read-only receipt recovery', () => {
    const request = { requestId: first, agent: 'chat', task: 'Hello' };
    expect(decodePayload('acting.turn.start', request)).toEqual(request);
    for (const invalid of [
      { ...request, session: 'bridge-files' },
      { ...request, task: ' ' },
      { ...request, requestId: 'bad' },
      { ...request, conversation: null },
    ])
      expect(() => decodePayload('acting.turn.start', invalid)).toThrow();
    const receipt = {
      requestId: first,
      agent: 'chat',
      conversation: 'cnv_ABC123',
      state: 'STARTED',
      job: 'job_ABC123',
    };
    expect(decodeReply('acting.turn.receipt', receipt)).toEqual(receipt);
    expect(() =>
      decodeReply('acting.turn.receipt', { ...receipt, job: null }),
    ).toThrow();
  });
});

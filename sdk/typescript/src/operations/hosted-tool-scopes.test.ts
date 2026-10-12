import { describe, it, expect } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import { checkedTransport } from './transport.ts';
import { isMutation } from './discovery.ts';
const id = '11111111-1111-1111-1111-111111111111';
describe('hosted scope contract boundaries', () => {
  it('requires explicit credential purpose and rejects worker-selected owner authority', () => {
    const p = {
      binding: id,
      registration: id,
      purpose: 'DISCOVERY',
      invocation: null,
    };
    expect(decodePayload('tool.scope.worker.credential', p)).toEqual(p);
    for (const changed of [
      { owner: 'someone' },
      { invocation: id },
      { registration: 'bad' },
      { purpose: 'EXECUTION' },
    ])
      expect(() =>
        decodePayload('tool.scope.worker.credential', { ...p, ...changed }),
      ).toThrow();
    expect(() =>
      decodePayload('tool.scope.worker.credential', {
        binding: id,
        registration: id,
        purpose: 'DISCOVERY',
      }),
    ).toThrow();
    expect(isMutation('tool.scope.worker.credential')).toBe(true);
    expect(isMutation('tool.scope.worker.read')).toBe(false);
  });
  it('rejects foreign retained invocation replies without replaying', async () => {
    let calls = 0;
    const p = { binding: id, registration: id, invocation: id };
    const wire = checkedTransport({
      async ask() {
        calls++;
        return {
          code: 'OK',
          payload: {
            ...p,
            invocation: '22222222-2222-2222-2222-222222222222',
            verified: true,
            state: 'COMPLETED',
            text: 'done',
          },
        };
      },
    });
    await expect(wire.ask('tool.scope.worker.read', p)).rejects.toThrow(
      'completion is unknown',
    );
    expect(calls).toBe(1);
  });
  it('checks nested Relay contracts and keeps secrets out of error messages', () => {
    expect(() =>
      decodePayload('tool.scope.worker.consume', {
        binding: id,
        registration: id,
        consumption: {
          project: 'fixture',
          topic: 'tool.fixture.request',
          group: 'tool-provider',
          consumerId: id,
          start: 'OLDEST_RETAINED',
          limit: 0,
          waitMs: 0,
        },
      }),
    ).toThrow();
    let message = '';
    try {
      decodeReply('tool.scope.worker.credential', {
        value: 'private-fixture-token',
      });
    } catch (error) {
      message = String(error);
    }
    expect(message).not.toContain('private-fixture-token');
  });
});

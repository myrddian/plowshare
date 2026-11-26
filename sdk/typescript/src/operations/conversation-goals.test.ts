import { describe, expect, it } from 'vitest';
import { parseCommand } from './commands.ts';
import { decodeReply, decodeRequest } from './schema.ts';
import { discovery } from './discovery.ts';

const conversation = 'cnv_project';
const requestId = '00000000-0000-0000-0000-000000000001';
const goal = '00000000-0000-0000-0000-000000000002';

describe('conversation goal contracts', () => {
  it('exposes the CLI controls without adding a default project or session', () => {
    expect(
      parseCommand('conversation goal get cnv_project', 'different-project'),
    ).toEqual({
      kind: 'request',
      request: { type: 'conversation.goal.get', payload: { conversation } },
    });
    const payload = {
      conversation,
      requestId,
      agent: 'agent',
      objective: 'Check the report',
    };
    expect(
      parseCommand('conversation goal start ' + JSON.stringify(payload)),
    ).toEqual({
      kind: 'request',
      request: { type: 'conversation.goal.start', payload },
    });
    expect(discovery('conversation goal').commands).toHaveLength(5);
  });

  it('rejects malformed controls and decodes a reconnectable receipt', () => {
    expect(() =>
      decodeRequest('conversation.goal.pause', {
        conversation,
        requestId,
        goal,
        version: 0,
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('conversation.goal.resume', {
        conversation,
        requestId,
        goal,
        version: 1,
        maxActivations: 1.2,
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('conversation.goal.get', {
        conversation,
        requestId: 'bogus',
      }),
    ).toThrow();
    const status = {
      goal,
      conversation,
      agent: 'agent',
      objective: 'Check the report',
      criteria: null,
      state: 'BLOCKED',
      version: 3,
      activations: 1,
      maxActivations: 8,
      lastJob: 'job_1',
      lastTurn: null,
      reason: 'UNCONFIRMED_TURN',
      detail: 'Inspect the original job',
      modelCallsSpent: 1,
      modelCallLimit: 8,
    };
    expect(decodeReply('conversation.goal.get', status)).toEqual(status);
    expect(() =>
      decodeReply('conversation.goal.get', {
        ...status,
        state: 'UNRECOGNIZED',
      }),
    ).toThrow();
  });
});

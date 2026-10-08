import { describe, expect, it } from 'vitest';
import { decodePayload } from './schema.ts';
import { parseCommand } from './commands.ts';
import { request, resultOf } from './direct.ts';

const scopeRequest = {
  project: 'fixture',
  scope: 'linear',
  provider: 'linear',
  prefix: 'linear_',
  grants: ['*'],
  agents: ['ticketer'],
  leaseSeconds: 30,
};
describe('provider scope boundary', () => {
  it('exposes the same typed operation through client command surfaces', () => {
    expect(decodePayload('tool.scope.connect', scopeRequest)).toEqual(
      scopeRequest,
    );
    expect(
      parseCommand(`tool scope connect ${JSON.stringify(scopeRequest)}`),
    ).toMatchObject({ kind: 'request' });
  });
  it('uses the selected project when a command omits it', () => {
    expect(parseCommand('tool scope list', 'fixture')).toMatchObject({
      kind: 'request',
      request: { type: 'tool.scope.list', payload: { project: 'fixture' } },
    });
  });
  it('checks correlated scope replies and exposes refusals without replay', () => {
    const asked = request('tool.scope.connect', scopeRequest);
    const reply = {
      ...scopeRequest,
      sourceProvider: 'linear',
      provider: 'p-abc',
      account: 'alice',
    };
    expect(resultOf(asked, { code: 'OK', payload: reply }).kind).toBe(
      'completed',
    );
    expect(
      resultOf(asked, { code: 'OK', payload: { ...reply, project: 'foreign' } })
        .kind,
    ).toBe('invalid-response');
    expect(
      resultOf(asked, { code: 'BAD_REQUEST', payload: 'Denied' }).kind,
    ).toBe('refused');
  });
  it('rejects ambiguous wildcard grants, impersonation and invalid bounds', () => {
    for (const replacement of [
      { grants: ['*', 'linear_read'] },
      { grants: [] },
      { grants: ['x', 'x'] },
      { agents: [] },
      { leaseSeconds: 301 },
      { leaseSeconds: 1.5 },
      { provider: 'Invalid' },
      { account: 'intruder' },
    ]) {
      expect(() =>
        decodePayload('tool.scope.connect', {
          ...scopeRequest,
          ...replacement,
        }),
      ).toThrow();
    }
  });
});

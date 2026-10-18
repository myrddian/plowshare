import { ApplicationIdentityClient } from './application-identity-client.ts';
import { describe, it, expect } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import { dispatch, request } from './direct.ts';
import { isMutation } from './discovery.ts';

const requestId = '11111111-1111-1111-1111-111111111111';
const tokenId = '22222222-2222-2222-2222-222222222222';
const receipt = {
  project: 'app',
  requestId,
  tokenId,
  principal: `@service/${tokenId}`,
  expiresAt: '2026-11-01T00:00:00Z',
};
describe('Application-owned credential lifecycle', () => {
  it('requires bounded explicit issue/rotate policy and retained UUID', () => {
    const payload = {
      project: 'app',
      requestId,
      expiresInDays: 30,
      rotate: false,
    };
    expect(decodePayload('application.token.issue', payload)).toEqual(payload);
    for (const change of [
      { requestId: 'bad' },
      { expiresInDays: 0 },
      { expiresInDays: 366 },
      { rotate: null },
      { project: 'personal:owner' },
      { extra: true },
    ])
      expect(() =>
        decodePayload('application.token.issue', { ...payload, ...change }),
      ).toThrow();
    expect(isMutation('application.token.issue')).toBe(true);
    expect(isMutation('application.token.receipt')).toBe(false);
  });
  it('keeps manual Applications unowned and refuses foreign principal metadata', () => {
    expect(
      decodeReply('application.identity.status', {
        project: 'app',
        identity: null,
      }),
    ).toEqual({ project: 'app', identity: null });
    expect(() =>
      decodeReply('application.token.receipt', {
        ...receipt,
        principal: '@service/foreign',
      }),
    ).toThrow();
  });
  it('does not accept a credential receipt from another request or project', async () => {
    const asked = request('application.token.issue', {
      project: 'app',
      requestId,
      expiresInDays: 30,
      rotate: false,
    });
    const payload = {
      receipt: { ...receipt, project: 'other' },
      credential: 'pss_' + '0'.repeat(48),
    };
    expect(
      (
        await dispatch(
          {
            async ask() {
              return { code: 'OK', payload };
            },
          },
          asked,
        )
      ).kind,
    ).toBe('invalid-response');
  });
  it('scoped reads accept project selection without null or extra scope fields', () => {
    expect(decodePayload('schedule.list', { project: 'app' })).toEqual({
      project: 'app',
    });
    expect(decodePayload('schedule.files', {})).toEqual({});
    expect(() => decodePayload('schedule.list', { project: null })).toThrow();
    const selected = {
      document: requestId,
      scope: { kind: 'project', project: 'app', includeGlobal: false },
    };
    expect(decodePayload('document.detail', selected)).toEqual(selected);
    expect(() =>
      decodePayload('document.detail', {
        ...selected,
        scope: { ...selected.scope, extra: true },
      }),
    ).toThrow();
  });
});

describe('shared credential controls', () => {
  it('retains the original UUID after unknown delivery and recovers without issuing again', async () => {
    let pending: unknown;
    let issued = 0;
    const client = new ApplicationIdentityClient(
      {
        async ask(type) {
          if (type === 'application.token.issue') {
            issued++;
            throw new Error('lost reply');
          }
          return { code: 'OK', payload: receipt };
        },
      },
      {
        load: () => pending,
        save: (value) => {
          pending = value;
        },
      },
    );
    const input = {
      project: 'app',
      requestId,
      expiresInDays: 30,
      rotate: false,
    };
    await expect(client.issue(input)).rejects.toThrow('lost reply');
    expect(client.pending()).toEqual(input);
    await expect(client.issue({ ...input, rotate: true })).rejects.toThrow(
      'pending',
    );
    expect(await client.recover()).toEqual(receipt);
    expect(pending).toBeUndefined();
    expect(issued).toBe(1);
  });
  it('keeps internal failures fenced but clears a checked domain refusal', async () => {
    for (const code of ['INTERNAL_ERROR', 'BAD_REQUEST'] as const) {
      let pending: unknown;
      const client = new ApplicationIdentityClient(
        {
          async ask() {
            return { code, said: 'Failure' };
          },
        },
        {
          load: () => pending,
          save: (value) => {
            pending = value;
          },
        },
      );
      await expect(
        client.issue({
          project: 'app',
          requestId,
          expiresInDays: 30,
          rotate: false,
        }),
      ).rejects.toThrow('Failure');
      expect(pending === undefined).toBe(code === 'BAD_REQUEST');
    }
  });
  it('does not clear uncertainty on a foreign receipt or malformed saved intent', async () => {
    let pending: unknown = {
      project: 'app',
      requestId,
      expiresInDays: 30,
      rotate: false,
    };
    let calls = 0;
    const client = new ApplicationIdentityClient(
      {
        async ask() {
          calls++;
          return { code: 'OK', payload: { ...receipt, project: 'other' } };
        },
      },
      {
        load: () => pending,
        save: (value) => {
          pending = value;
        },
      },
    );
    await expect(client.recover()).rejects.toThrow();
    expect(pending).toBeDefined();
    pending = {
      project: 'app',
      requestId: 'invalid',
      expiresInDays: 30,
      rotate: false,
    };
    await expect(
      client.issue({
        project: 'app',
        requestId,
        expiresInDays: 30,
        rotate: false,
      }),
    ).rejects.toThrow();
    expect(calls).toBe(1);
  });
});

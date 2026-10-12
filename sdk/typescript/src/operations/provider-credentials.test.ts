import { describe, expect, it } from 'vitest';
import { decodePayload, decodeReply } from './schema.ts';
import { checkedTransport } from './transport.ts';
import { isMutation } from './discovery.ts';
import type { ProviderConnection } from './provider-credentials.ts';

const id = '11111111-1111-1111-1111-111111111111';
const definition = {
  name: 'Fixture',
  endpoint: 'https://provider.example/mcp',
  authentication: 'API_KEY' as const,
  apiKeyHeader: 'X-API-Key' as const,
  oauth: null,
};
const connection: ProviderConnection = {
  connection: id,
  project: 'fixture',
  revision: id,
  enabled: true,
  deleted: false,
  definition,
};
const put = {
  project: 'fixture',
  connection: id,
  subject: null,
  requestId: id,
  expectedGeneration: null,
  secret: 'private-key',
};
const grant = {
  connection: id,
  project: 'fixture',
  subject: 'owner',
  generation: id,
  connectionRevision: id,
  state: 'READY' as const,
  expiresAt: null,
  keyId: 'fixture-key',
};
const receipt = {
  requestId: id,
  operation: 'provider.credential.put' as const,
  connection,
  credential: grant,
};

describe('server-held provider credentials', () => {
  it('requires explicit nulls and rejects nested configuration coercion and hidden input fields', () => {
    expect(decodePayload('provider.credential.put', put)).toEqual(put);
    for (const changed of [
      { subject: 'someone' },
      { subject: '@service/bad' },
      { secret: 'bad\nkey' },
      { expectedGeneration: '' },
      { secret: `pss_${'a'.repeat(48)}` },
    ])
      expect(() =>
        decodePayload('provider.credential.put', { ...put, ...changed }),
      ).toThrow();
    const { subject: _subject, ...missing } = put;
    expect(() => decodePayload('provider.credential.put', missing)).toThrow();
    for (const changed of [
      { endpoint: 'http://provider.example/mcp' },
      { endpoint: 'https://provider.example:99999/mcp' },
      { name: 1 },
      { hiddenSecret: 'private-key' },
      { oauth: { issuer: 'https://issuer.example' } },
    ])
      expect(() =>
        decodePayload('provider.connection.save', {
          project: 'fixture',
          connection: id,
          requestId: id,
          expectedRevision: null,
          definition: { ...definition, ...changed },
        }),
      ).toThrow();
  });
  it('projects only redacted metadata and checks nested receipt relationships', () => {
    expect(
      decodeReply('provider.credential.put', {
        ...receipt,
        accessToken: 'private-access',
        credential: { ...grant, refreshToken: 'private-refresh' },
      }),
    ).toEqual(receipt);
    expect(() =>
      decodeReply('provider.credential.put', {
        ...receipt,
        credential: { ...grant, project: 'foreign' },
      }),
    ).toThrow();
    expect(() =>
      decodeReply('provider.credential.status', { ...grant, state: 'NONE' }),
    ).toThrow();
    expect(() =>
      decodeReply('provider.connection.status', {
        ...connection,
        deleted: true,
      }),
    ).toThrow();
  });
  it('refuses foreign receipts and does not retry after unreadable or unknown delivery', async () => {
    let sends = 0;
    const transport = checkedTransport({
      async ask() {
        sends++;
        return {
          code: 'OK' as const,
          payload: {
            ...receipt,
            requestId: '22222222-2222-2222-2222-222222222222',
          },
        };
      },
    });
    await expect(transport.ask('provider.credential.put', put)).rejects.toThrow(
      'unknown',
    );
    expect(sends).toBe(1);
    const lost = checkedTransport({
      async ask() {
        sends++;
        throw new Error('connection lost');
      },
    });
    await expect(lost.ask('provider.credential.put', put)).rejects.toThrow(
      'connection lost',
    );
    expect(sends).toBe(2);
  });
  it('classifies credential changes and authorization as mutations, with receipt/status recovery reads', () => {
    for (const op of [
      'provider.connection.save',
      'provider.connection.change',
      'provider.credential.put',
      'provider.credential.revoke',
      'provider.credential.rekey',
      'provider.credential.authorize',
      'provider.credential.refresh',
    ])
      expect(isMutation(op)).toBe(true);
    for (const op of [
      'provider.connection.list',
      'provider.connection.status',
      'provider.connection.receipt',
      'provider.credential.status',
    ])
      expect(isMutation(op)).toBe(false);
  });
});

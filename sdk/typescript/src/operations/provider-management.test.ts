import { describe, expect, it } from 'vitest';
import {
  ProviderManagementClient,
  ProviderManagementRefused,
  SessionManagementIntents,
  managementRequest,
  decodeManagementIntent,
  redactedManagementRequest,
  redactedManagementValue,
} from './provider-management.ts';
import { checkedTransport } from './transport.ts';
import { isMutation } from './discovery.ts';
import { decodeReply } from './schema.ts';
import { ConnectionFault } from '../binding/connection.ts';

const id = '11111111-1111-1111-1111-111111111111',
  next = '22222222-2222-2222-2222-222222222222';
const put = {
  project: 'fixture',
  connection: id,
  subject: null,
  requestId: id,
  expectedGeneration: null,
  secret: 'sensitive-fixture',
};
const connection = {
  project: 'fixture',
  connection: id,
  revision: id,
  enabled: true,
  deleted: false,
  definition: {
    name: 'Fixture',
    endpoint: 'https://provider.example/mcp',
    authentication: 'API_KEY',
    apiKeyHeader: 'X-API-Key',
    oauth: null,
  },
};
const receipt = {
  requestId: id,
  operation: 'provider.credential.put',
  connection,
  credential: {
    project: 'fixture',
    connection: id,
    subject: 'owner',
    generation: next,
    connectionRevision: id,
    state: 'READY',
    expiresAt: null,
    keyId: 'fixture',
  },
};
function store() {
  const values = new Map<string, string>();
  return {
    values,
    intents: new SessionManagementIntents(
      {
        getItem: (key) => values.get(key) ?? null,
        setItem: (key, value) => {
          values.set(key, value);
        },
      },
      'fixture/owner',
    ),
  };
}
describe('owner provider management lifecycle', () => {
  it('clears a bookmark only when the connection proves non-submission', async () => {
    const { intents } = store();
    const client = new ProviderManagementClient(
      {
        async ask() {
          throw new ConnectionFault('NOT_SUBMITTED', 'closed');
        },
      },
      intents,
    );
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.toBeInstanceOf(ConnectionFault);
    expect(await client.pending()).toEqual([]);
  });
  it('persists only original coordinates before one send and recovers without resending material', async () => {
    const { values, intents } = store();
    let sends = 0,
      missing = true;
    const client = new ProviderManagementClient(
      checkedTransport({
        async ask(type) {
          sends++;
          expect(JSON.stringify([...values])).not.toContain(put.secret);
          if (type === 'provider.credential.put') {
            expect((await intents.list())[0]?.requestId).toBe(id);
            throw new Error('lost delivery');
          }
          return missing
            ? { code: 'NOT_FOUND', said: 'receipt missing' }
            : { code: 'OK', payload: receipt };
        },
      }),
      intents,
    );
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.toThrow('lost delivery');
    await expect(
      client.request('provider.credential.put', { ...put, requestId: next }),
    ).rejects.toThrow(id);
    expect(sends).toBe(1);
    const original = (await client.pending())[0];
    expect(original).toBeDefined();
    if (!original) throw new Error('Missing bookmark.');
    await expect(client.recover(original)).rejects.toBeInstanceOf(
      ProviderManagementRefused,
    );
    expect(await client.pending()).toHaveLength(1);
    missing = false;
    await expect(
      client.execute(
        managementRequest('provider.connection.receipt', {
          project: 'fixture',
          requestId: id,
        }),
      ),
    ).resolves.toEqual(receipt);
    expect(await client.pending()).toEqual([]);
    expect(sends).toBe(3);
  });
  it('refuses corrupt/foreign successful evidence and keeps unknown mutations fenced', async () => {
    const { intents } = store();
    const client = new ProviderManagementClient(
      checkedTransport({
        async ask() {
          return { code: 'OK', payload: { ...receipt, requestId: next } };
        },
      }),
      intents,
    );
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.toThrow('unknown');
    expect(await client.pending()).toHaveLength(1);
    const pending = (await client.pending())[0];
    if (!pending) throw new Error('Missing bookmark.');
    await expect(client.recover(pending)).rejects.toThrow('unknown');
    expect(await client.pending()).toHaveLength(1);
  });
  it('preserves server role refusals without echoing diagnostics or secret bodies', async () => {
    const { intents } = store();
    const client = new ProviderManagementClient(
      checkedTransport({
        async ask() {
          return {
            code: 'VALIDATION_FAILED',
            said: `forbidden ${put.secret}`,
            payload: { secret: put.secret },
          };
        },
      }),
      intents,
    );
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.toMatchObject({ code: 'VALIDATION_FAILED', unsupported: false });
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.not.toThrow(put.secret);
    expect(await client.pending()).toEqual([]);
  });
  it('identifies the real older-server refusal and performs no fallback or replay', async () => {
    const { intents } = store();
    let sends = 0;
    const client = new ProviderManagementClient(
      checkedTransport({
        async ask(type) {
          sends++;
          return {
            code: 'NOT_FOUND',
            said: `no handler is registered for type "${type}"`,
          };
        },
      }),
      intents,
    );
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.toMatchObject({ unsupported: true, code: 'NOT_FOUND' });
    expect(sends).toBe(1);
    expect(await client.pending()).toEqual([]);
  });
  it('keeps internal failures uncertain and refuses writes when bookmark persistence fails', async () => {
    const { intents } = store();
    const client = new ProviderManagementClient(
      checkedTransport({
        async ask() {
          return { code: 'INTERNAL_ERROR' };
        },
      }),
      intents,
    );
    await expect(
      client.request('provider.credential.put', put),
    ).rejects.toMatchObject({ code: 'INTERNAL_ERROR' });
    expect(await client.pending()).toHaveLength(1);
    let sent = false;
    const unwritable = new ProviderManagementClient(
      checkedTransport({
        async ask() {
          sent = true;
          return { code: 'OK', payload: receipt };
        },
      }),
      {
        list: () => Promise.resolve([]),
        reserve: () => Promise.reject(new Error('storage failed')),
        remove: () => Promise.resolve(),
      },
    );
    await expect(
      unwritable.request('provider.credential.put', put),
    ).rejects.toThrow('storage failed');
    expect(sent).toBe(false);
  });
  it('rejects replayable bookmark bodies, hidden worker authority and nested invalid input', () => {
    const intent = {
      operation: 'provider.credential.put',
      project: 'fixture',
      connection: id,
      requestId: id,
      subject: null,
      expectedGeneration: null,
    };
    expect(decodeManagementIntent(intent)).toEqual(intent);
    for (const value of [
      { ...intent, secret: put.secret },
      { ...intent, subject: 'someone' },
      { ...intent, expectedGeneration: 3 },
      { ...intent, operation: 'tool.scope.worker.credential' },
    ])
      expect(() => decodeManagementIntent(value)).toThrow();
    expect(() =>
      managementRequest('tool.scope.worker.credential', {}),
    ).toThrow();
    expect(() =>
      managementRequest('provider.connection.save', {
        ...put,
        definition: { ...connection.definition, oauth: {} },
      }),
    ).toThrow();
    expect(isMutation('tool.scope.connect')).toBe(true);
    expect(isMutation('tool.scope.disconnect')).toBe(true);
  });
  it('redacts validation and OAuth display values without changing typed transport inputs', () => {
    expect(
      JSON.stringify(
        redactedManagementRequest(
          managementRequest('provider.credential.put', put),
        ),
      ),
    ).not.toContain(put.secret);
    expect(put.secret).toBe('sensitive-fixture');
    const authorization = {
      receipt: {
        ...receipt,
        operation: 'provider.credential.authorize',
        credential: { ...receipt.credential, state: 'PENDING' },
      },
      authorizationUrl: 'https://issuer.example/authorize?state=private-state',
      expiresAt: '2026-10-13T12:00:00Z',
    };
    const value = redactedManagementValue(
      'provider.credential.authorize',
      decodeReply('provider.credential.authorize', authorization),
    );
    expect(JSON.stringify(value)).not.toContain('private-state');
  });
});

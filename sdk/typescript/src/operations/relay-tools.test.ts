import type { HostedToolRegistration } from './hosted-tool-scopes.ts';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { Plowshare } from './sdk.ts';
import { CURRENT_VERSION } from '../binding/envelope.ts';
import type { Arrival, Socket } from '../binding/connection.ts';
import { fieldsOf } from './response.ts';
import { decodeToolSchema, canonicalToolSchema } from './tool-schemas.ts';
import {
  decodeToolCall,
  toolResultRequestId,
  toolDeploymentConfig,
  validateToolArguments,
  ToolProvider,
} from './relay-tools.ts';
import type {
  ToolDeclaration,
  ToolCall,
  ToolHost,
  ToolJournal,
  ToolReceipt,
} from './relay-tools.ts';

const binding = {
  project: 'fixture',
  provider: 'scanner',
  account: 'provider',
};
const declaration: ToolDeclaration = {
  name: 'inspect',
  description: 'Inspect fixture',
  timeoutSeconds: 30,
  parameters: [
    { name: 'value', type: 'NUMBER', description: '', required: true },
  ],
};
const host: ToolHost = {
  uuid: () => '22222222-2222-2222-2222-222222222222',
  sha256: async (source) => createHash('sha256').update(source).digest('hex'),
  now: () => new Date(),
  execute: (handler, call) => handler(call),
};
function fixture(): Readonly<Record<string, unknown>> {
  const value: unknown = JSON.parse(
    readFileSync(
      new URL(
        '../../../../test-support/contracts/relay-tools.json',
        import.meta.url,
      ),
      'utf8',
    ),
  );
  return fieldsOf(value);
}
function source(): string {
  const samples = fixture()['cases'];
  if (!Array.isArray(samples)) throw new Error('Missing tool fixtures');
  const value = fieldsOf(samples[0])['source'];
  if (typeof value !== 'string')
    throw new Error('Missing tool request fixture');
  return value;
}
class Journal implements ToolJournal {
  readonly rows = new Map<string, ToolReceipt>();
  readonly identity: string;
  constructor(tool: ToolDeclaration = declaration) {
    this.identity = createHash('sha256')
      .update(toolDeploymentConfig(binding, [tool]))
      .digest('hex');
  }
  async all(): Promise<readonly ToolReceipt[]> {
    return [...this.rows.values()];
  }
  async save(row: ToolReceipt): Promise<void> {
    this.rows.set(row.invocationId, row);
  }
}
class Wire implements Socket {
  readonly request: string;
  constructor(request = source()) {
    this.request = request;
  }
  listener: ((event: Arrival) => void) | undefined;
  closed: (() => void) | undefined;
  publications = 0;
  readonly seen: {
    type: string;
    payload: Readonly<Record<string, unknown>>;
  }[] = [];
  loseReply = false;
  published: Readonly<Record<string, unknown>> = {};
  readonly id = decodeToolCall(source()).invocationId;
  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') this.listener = listener;
    else this.closed = () => listener({ data: null });
  }
  close(): void {
    this.closed?.();
  }
  send(text: string): void {
    const frame: unknown = JSON.parse(text);
    const envelope = fieldsOf(frame);
    let q = fieldsOf(envelope['payload']);
    this.seen.push({ type: String(envelope['type']), payload: q });
    const portType = String(envelope['type']).startsWith('tool.scope.worker.')
      ? 'relay.' + String(envelope['type']).split('.').at(-1)
      : envelope['type'];
    if (portType === 'relay.publish' && 'publication' in q)
      q = fieldsOf(q['publication']);
    if (portType === 'relay.consume' && 'consumption' in q)
      q = fieldsOf(q['consumption']);
    if (portType === 'relay.ack' && 'acknowledgement' in q)
      q = fieldsOf(q['acknowledgement']);
    const topic = {
      name: q['topic'],
      kind: 'TEXT',
      retentionSeconds: '345600',
      maxRecords: null,
      through: '1',
      expiredThrough: '0',
    };
    const event = (result = false) => ({
      position: '1',
      eventId: result ? this.published['requestId'] : this.id,
      publisher: result
        ? 'sdk:' + createHash('sha256').update(binding.account).digest('hex')
        : 'tool-runtime',
      occurredAt: result
        ? this.published['occurredAt']
        : '2026-10-08T00:00:00Z',
      publishedAt: '2026-10-08T00:00:00Z',
      correlationId: this.id,
      causationId: this.id,
      causation: null,
      payload: {
        kind: 'TEXT',
        text: result ? this.published['text'] : this.request,
        schedule: null,
        emits: null,
        fireAt: null,
      },
    });
    let payload: unknown;
    if (portType === 'relay.consume')
      payload = {
        project: q['project'],
        topic: q['topic'],
        group: q['group'],
        consumerId: q['consumerId'],
        status: 'DATA',
        batchId: this.id,
        fence: '1',
        through: '1',
        expiresAt: '2099-01-01T00:00:00Z',
        expiredThrough: null,
        events: [event()],
      };
    else if (portType === 'relay.ack')
      payload = {
        project: q['project'],
        topic: q['topic'],
        group: q['group'],
        batchId: q['batchId'],
        through: '1',
        gap: false,
      };
    else if (portType === 'relay.publish') {
      this.publications++;
      this.published = q;
      if (this.loseReply) {
        this.close();
        return;
      }
      payload = {
        project: q['project'],
        topic: q['topic'],
        requestId: q['requestId'],
        position: '1',
        publishedAt: '2026-10-08T00:00:00Z',
      };
    } else if (portType === 'relay.log')
      payload = {
        scope: { project: q['project'], system: false },
        topic,
        after: q['after'],
        next: '1',
        gapThrough: null,
        events: [event(true)],
        subscribers: [],
        branches: [],
      };
    else if (envelope['type'] === 'tool.scope.worker.read') {
      const result =
        typeof this.published['text'] === 'string'
          ? fieldsOf(JSON.parse(this.published['text']))
          : {};
      payload = {
        binding: q['binding'],
        registration: q['registration'],
        invocation: q['invocation'],
        verified: 'state' in result,
        state: result['state'] ?? 'UNKNOWN',
        text: result['text'] ?? 'No verified result',
      };
    } else throw new Error('Unexpected operation');
    this.listener?.({
      data: JSON.stringify({
        id: envelope['id'],
        type: envelope['type'],
        protocol_version: CURRENT_VERSION,
        payload: { code: 'OK', payload },
      }),
    });
  }
}
describe('Relay tool façade', () => {
  it('uses hosted worker ports and reconciles the original fence after reconnect without replay', async () => {
    const id = '11111111-1111-1111-1111-111111111111',
      oldFence = '33333333-3333-3333-3333-333333333333';
    const route = {
      project: 'fixture',
      scope: 'scans',
      sourceProvider: 'scanner',
      provider: 'host-' + 'a'.repeat(32),
      account: '@service/' + id,
      prefix: 'network_',
      grants: ['*'],
      agents: ['coordinator'],
      leaseSeconds: 30,
    };
    const registration: HostedToolRegistration = {
      binding: {
        binding: id,
        project: 'fixture',
        owner: 'owner',
        generation: 1,
        enabled: true,
        deleted: false,
        application: false,
        definition: {
          scope: 'scans',
          provider: 'scanner',
          prefix: 'network_',
          grants: ['*'],
          agents: ['coordinator'],
          leaseSeconds: 30,
          credentialProject: 'configuration',
          connection: id,
          subject: null,
          executor: route.account,
        },
        credentialSubject: 'owner',
        route,
      },
      registration: oldFence,
      registeredAt: '2026-10-13T00:00:00Z',
      expiresAt: '2099-01-01T00:00:00Z',
    };
    const tool = { ...declaration, name: 'network_read' };
    const request = JSON.stringify({
      ...fieldsOf(JSON.parse(source())),
      provider: route.provider,
      tool: tool.name,
      deadline: '2099-01-01T00:00:00Z',
    });
    const rows = new Map<string, ToolReceipt>();
    const journal: ToolJournal = {
      identity: await host.sha256(toolDeploymentConfig(route, [tool])),
      async all() {
        return [...rows.values()];
      },
      async save(receipt) {
        rows.set(receipt.invocationId, receipt);
      },
    };
    const wire = new Wire(request),
      client = new Plowshare({ socket: wire });
    let calls = 0;
    const tools = [
      {
        declaration: tool,
        handler: async () => {
          calls++;
          return { state: 'COMPLETED' as const, text: 'hosted result' };
        },
      },
    ];
    const provider = await ToolProvider.create(
      client,
      route,
      tools,
      journal,
      host,
      registration,
    );
    wire.loseReply = true;
    await expect(provider.poll()).rejects.toThrow();
    expect(calls).toBe(1);
    expect(wire.seen.map((v) => v.type)).toEqual([
      'tool.scope.worker.consume',
      'tool.scope.worker.ack',
      'tool.scope.worker.publish',
    ]);
    const reconnect = new Wire(request);
    reconnect.published = wire.published;
    const recovered = await ToolProvider.create(
      new Plowshare({ socket: reconnect }),
      route,
      tools,
      journal,
      host,
      { ...registration, registration: '44444444-4444-4444-4444-444444444444' },
    );
    expect(await recovered.reconcile()).toBe(1);
    expect(calls).toBe(1);
    expect(reconnect.publications).toBe(0);
    expect(reconnect.seen[0]?.type).toBe('tool.scope.worker.read');
    expect(reconnect.seen[0]?.payload['registration']).toBe(oldFence);
    expect((await journal.all())[0]?.phase).toBe('done');
  });
  it('uses the common strict scalar contract and result identity', async () => {
    const samples = fixture()['cases'];
    if (!Array.isArray(samples)) throw new Error('Missing fixtures');
    expect(
      await toolResultRequestId(host, decodeToolCall(source()).invocationId),
    ).toBe(fixture()['resultRequestId']);
    for (const row of samples) {
      const sample = fieldsOf(row);
      const input = sample['source'];
      if (typeof input !== 'string') throw new Error('Invalid fixture');
      let accepted = true;
      try {
        validateToolArguments(declaration, decodeToolCall(input).arguments);
      } catch {
        accepted = false;
      }
      expect(accepted, String(sample['name'])).toBe(sample['valid']);
    }
  });
  it('treats parameter names as own fields rather than JavaScript prototype members', () => {
    const call = decodeToolCall(
      source().replace('"value":0.5', '"__proto__":0.5'),
    );
    const tool = {
      ...declaration,
      parameters: [
        {
          name: '__proto__',
          type: 'NUMBER' as const,
          description: '',
          required: true,
        },
      ],
    };
    expect(Object.hasOwn(call.arguments, '__proto__')).toBe(true);
    expect(() => validateToolArguments(tool, call.arguments)).not.toThrow();
    expect(() =>
      validateToolArguments(
        {
          ...declaration,
          parameters: [
            {
              name: 'constructor',
              type: 'NUMBER',
              description: '',
              required: false,
            },
          ],
        },
        {},
      ),
    ).not.toThrow();
  });
  it('reconciles a lost publication and never repeats handler or result', async () => {
    const wire = new Wire(),
      journal = new Journal();
    let executions = 0;
    const tools = [
      {
        declaration,
        handler: async () => {
          executions++;
          return { state: 'COMPLETED' as const, text: 'observed' };
        },
      },
    ];
    wire.loseReply = true;
    const provider = await ToolProvider.create(
      new Plowshare({ socket: wire }),
      binding,
      tools,
      journal,
      host,
    );
    await expect(provider.poll()).rejects.toThrow();
    expect(journal.rows.get(wire.id)?.phase).toBe('publishing');
    wire.loseReply = false;
    const restarted = await ToolProvider.create(
      new Plowshare({ socket: wire }),
      binding,
      tools,
      journal,
      host,
    );
    await expect(restarted.poll()).rejects.toThrow();
    expect(await restarted.reconcile()).toBe(1);
    await restarted.poll();
    expect(executions).toBe(1);
    expect(wire.publications).toBe(1);
  });
  it('settles interrupted execution as UNKNOWN without invoking its handler', async () => {
    const wire = new Wire(),
      journal = new Journal();
    let executions = 0;
    await journal.save({
      invocationId: wire.id,
      request: source(),
      phase: 'executing',
      result: null,
      occurredAt: null,
    });
    const provider = await ToolProvider.create(
      new Plowshare({ socket: wire }),
      binding,
      [
        {
          declaration,
          handler: async () => {
            executions++;
            return { state: 'COMPLETED', text: 'unexpected' };
          },
        },
      ],
      journal,
      host,
    );
    await provider.poll();
    expect(executions).toBe(0);
    expect(wire.publications).toBe(1);
    expect(journal.rows.get(wire.id)?.result?.state).toBe('UNKNOWN');
  });
});

describe('live tool catalogue', () => {
  it('publishes and withdraws scoped metadata without executing or replaying handlers', async () => {
    const large: ToolDeclaration = {
      ...declaration,
      parameters: Array.from({ length: 24 }, (_, i) => ({
        name: `field_${i}`,
        type: 'STRING',
        description: 'd'.repeat(4096),
        required: false,
      })),
    };
    const wire = new Wire();
    const client = new Plowshare({ socket: wire });
    let calls = 0;
    const provider = await ToolProvider.create(
      client,
      binding,
      [
        {
          declaration: large,
          handler: async () => {
            calls++;
            return { state: 'COMPLETED', text: 'unexpected' };
          },
        },
      ],
      new Journal(large),
      host,
    );
    await provider.publishCatalog('11111111-1111-1111-1111-111111111111');
    expect(wire.published['topic']).toBe('tool.scanner.catalog');
    expect(wire.published['parentTopic']).toBeUndefined();
    expect(Buffer.byteLength(String(wire.published['text']))).toBeGreaterThan(
      65536,
    );
    const first: unknown = JSON.parse(String(wire.published['text']));
    expect(fieldsOf(first)['version']).toBe('plowshare-tool-catalog/1');
    expect(fieldsOf(first)['tools']).toEqual([large]);
    await provider.withdrawCatalog('22222222-2222-2222-2222-222222222222');
    const withdrawn: unknown = JSON.parse(String(wire.published['text']));
    expect(fieldsOf(withdrawn)['tools']).toEqual([]);
    wire.loseReply = true;
    await expect(
      provider.publishCatalog('33333333-3333-3333-3333-333333333333'),
    ).rejects.toThrow();
    expect(wire.publications).toBe(3);
    expect(calls).toBe(0);
  });
});

it('delivers nested values once and refuses a changed schema before execution', async () => {
  const schema = decodeToolSchema(
    JSON.parse(
      '{"type":"object","properties":{"data":{"type":"array","items":{"type":["string","null"]}}},"required":["data"],"additionalProperties":false}',
    ),
  );
  const tool = { ...declaration, parameters: [], inputSchema: schema };
  const request = JSON.stringify({
    ...fieldsOf(JSON.parse(source())),
    schema: 'plowshare-tool/2',
    schemaFingerprint: await host.sha256(canonicalToolSchema(schema)),
    arguments: { data: ['nested', null] },
  });
  const wire = new Wire(request),
    journal = new Journal(tool);
  let executions = 0;
  const registered = {
    declaration: tool,
    handler: async (call: ToolCall) => {
      executions++;
      expect(call.arguments).toEqual({ data: ['nested', null] });
      return { state: 'COMPLETED' as const, text: 'observed' };
    },
  };
  const client = new Plowshare({ socket: wire });
  const provider = await ToolProvider.create(
    client,
    binding,
    [registered],
    journal,
    host,
  );
  await provider.poll();
  await provider.poll();
  expect(executions).toBe(1);
  const changedTool = {
    ...tool,
    inputSchema: { ...schema, description: 'changed' },
  };
  const changed = await ToolProvider.create(
    client,
    binding,
    [{ ...registered, declaration: changedTool }],
    new Journal(changedTool),
    host,
  );
  await expect(changed.poll()).rejects.toThrow();
  expect(executions).toBe(1);
});

it('retains full 256-character declarations and their topic names without aliases', () => {
  const name = 'a'.repeat(256);
  const long = { ...declaration, name };
  expect(toolDeploymentConfig(binding, [long])).toContain(name);
  expect(
    decodeToolCall(JSON.stringify({ ...JSON.parse(source()), tool: name }))
      .tool,
  ).toBe(name);
  expect(() =>
    toolDeploymentConfig(binding, [{ ...long, name: name + 'a' }]),
  ).toThrow();
});

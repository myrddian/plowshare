import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { Plowshare } from './sdk.ts';
import { CURRENT_VERSION } from '../binding/envelope.ts';
import type { Arrival, Socket } from '../binding/connection.ts';
import { fieldsOf } from './response.ts';
import {
  decodeToolCall,
  toolResultRequestId,
  toolDeploymentConfig,
  validateToolArguments,
  ToolProvider,
} from './relay-tools.ts';
import type {
  ToolDeclaration,
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
  readonly identity = createHash('sha256')
    .update(toolDeploymentConfig(binding, [declaration]))
    .digest('hex');
  async all(): Promise<readonly ToolReceipt[]> {
    return [...this.rows.values()];
  }
  async save(row: ToolReceipt): Promise<void> {
    this.rows.set(row.invocationId, row);
  }
}
class Wire implements Socket {
  listener: ((event: Arrival) => void) | undefined;
  closed: (() => void) | undefined;
  publications = 0;
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
    const envelope = fieldsOf(frame),
      q = fieldsOf(envelope['payload']);
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
        text: result ? this.published['text'] : source(),
        schedule: null,
        emits: null,
        fireAt: null,
      },
    });
    let payload: unknown;
    if (envelope['type'] === 'relay.consume')
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
    else if (envelope['type'] === 'relay.ack')
      payload = {
        project: q['project'],
        topic: q['topic'],
        group: q['group'],
        batchId: q['batchId'],
        through: '1',
        gap: false,
      };
    else if (envelope['type'] === 'relay.publish') {
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
    } else if (envelope['type'] === 'relay.log')
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
    else throw new Error('Unexpected operation');
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

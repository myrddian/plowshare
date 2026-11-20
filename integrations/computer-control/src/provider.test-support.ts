import { createHash } from 'node:crypto';
import {
  Plowshare,
  decodeToolCall,
  decodeToolReceipt,
} from 'plowshare-client-ts';
import type {
  ToolBinding,
  ToolCall,
  ToolJournal,
  ToolReceipt,
} from 'plowshare-client-ts';
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection';
import type { CapturedImage, ComputerBackend } from './contracts.ts';
import type { FakeClock } from './contracts.test-support.ts';
import { image, target } from './contracts.test-support.ts';

export const binding: ToolBinding = {
  project: 'fixture-project',
  provider: 'computer',
  account: 'producer',
};
export function record(value: unknown): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Expected fixture object');
  return value as Readonly<Record<string, unknown>>;
}

export class MemoryJournal implements ToolJournal {
  readonly rows = new Map<string, ToolReceipt>();
  constructor(readonly identity: string) {}
  async all(): Promise<readonly ToolReceipt[]> {
    return [...this.rows.values()];
  }
  async save(receipt: ToolReceipt): Promise<void> {
    this.rows.set(receipt.invocationId, decodeToolReceipt(receipt));
  }
}

/** Real public SDK transport with a controlled Relay peer, not a server/database fixture. */
export class RelayWire implements Socket {
  listener: ((event: Arrival) => void) | undefined;
  closed: (() => void) | undefined;
  readonly calls: ToolCall[] = [];
  readonly publications: Readonly<Record<string, unknown>>[];
  losePublication = false;
  constructor(publications: Readonly<Record<string, unknown>>[] = []) {
    this.publications = publications;
  }
  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') this.listener = listener;
    else this.closed = () => listener({ data: null });
  }
  close(): void {
    this.closed?.();
  }
  send(source: string): void {
    const envelope = record(JSON.parse(source));
    const q = record(envelope['payload']);
    const type = envelope['type'];
    let payload: unknown;
    if (type === 'relay.consume') {
      const index = this.calls.findIndex(
        (call) => q['topic'] === `tool.${call.provider}.${call.tool}.request`,
      );
      const call = index < 0 ? undefined : this.calls.splice(index, 1)[0];
      payload = {
        project: q['project'],
        topic: q['topic'],
        group: q['group'],
        consumerId: q['consumerId'],
        status: call === undefined ? 'EMPTY' : 'DATA',
        batchId: call?.invocationId ?? null,
        fence: call === undefined ? null : '1',
        through: call === undefined ? '0' : '1',
        expiresAt: call === undefined ? null : '2099-01-01T00:00:00Z',
        expiredThrough: null,
        events: call === undefined ? [] : [this.event(call)],
      };
    } else if (type === 'relay.ack')
      payload = {
        project: q['project'],
        topic: q['topic'],
        group: q['group'],
        batchId: q['batchId'],
        through: '1',
        gap: false,
      };
    else if (type === 'relay.publish') {
      this.publications.push(q);
      if (this.losePublication) {
        this.close();
        return;
      }
      payload = {
        project: q['project'],
        topic: q['topic'],
        requestId: q['requestId'],
        position: '1',
        publishedAt: '2026-11-16T00:00:00Z',
      };
    } else if (type === 'relay.log') {
      const publication = this.publications.find(
        (row) => row['topic'] === q['topic'],
      );
      payload = {
        scope: { project: q['project'], system: false },
        topic: {
          name: q['topic'],
          kind: 'TEXT',
          retentionSeconds: '345600',
          maxRecords: null,
          through: '1',
          expiredThrough: '0',
        },
        after: q['after'],
        next: '1',
        gapThrough: null,
        events:
          publication === undefined ? [] : [this.resultEvent(publication)],
        subscribers: [],
        branches: [],
      };
    } else throw new Error('Unexpected fixture operation');
    this.listener?.({
      data: JSON.stringify({
        id: envelope['id'],
        type,
        protocol_version: 'plowshare-v1',
        payload: { code: 'OK', payload },
      }),
    });
  }
  private event(call: ToolCall) {
    return {
      position: '1',
      eventId: call.invocationId,
      publisher: 'tool-runtime',
      occurredAt: '2026-11-16T00:00:00Z',
      publishedAt: '2026-11-16T00:00:00Z',
      correlationId: call.invocationId,
      causationId: call.invocationId,
      causation: null,
      payload: {
        kind: 'TEXT',
        text: JSON.stringify(call),
        schedule: null,
        emits: null,
        fireAt: null,
      },
    };
  }
  private resultEvent(publication: Readonly<Record<string, unknown>>) {
    return {
      position: '1',
      eventId: publication['requestId'],
      publisher:
        'sdk:' + createHash('sha256').update(binding.account).digest('hex'),
      occurredAt: publication['occurredAt'],
      publishedAt: '2026-11-16T00:00:00Z',
      correlationId: publication['correlationId'],
      causationId: publication['parentEventId'],
      causation: null,
      payload: {
        kind: 'TEXT',
        text: publication['text'],
        schedule: null,
        emits: null,
        fireAt: null,
      },
    };
  }
}

export function client(wire = new RelayWire()): Plowshare {
  return new Plowshare({
    dispatchMode: 'ordered',
    socket: wire,
    deadline: {
      milliseconds: 2000,
      schedule: (expired, ms) => {
        const timer = setTimeout(expired, ms);
        return () => clearTimeout(timer);
      },
    },
  });
}
let sequence = 1;
export function call(
  clock: FakeClock,
  tool: string,
  args: ToolCall['arguments'] = {},
): ToolCall {
  const suffix = (sequence++).toString().padStart(12, '0');
  return decodeToolCall(
    JSON.stringify({
      schema: 'plowshare-tool/3',
      resultProfile: 'content/1',
      invocationId: `11111111-1111-1111-1111-${suffix}`,
      project: binding.project,
      provider: binding.provider,
      account: 'owner',
      run: 'run',
      call: 'call',
      tool: 'computer_' + tool,
      deadline: new Date(clock.wallNow() + 10_000).toISOString(),
      arguments: args,
    }),
  );
}
export function metadata(result: {
  readonly text: string;
}): Readonly<Record<string, unknown>> {
  return record(JSON.parse(result.text));
}
export class CaptureBackend implements ComputerBackend {
  captures = 0;
  value: CapturedImage;
  constructor(readonly clock: FakeClock) {
    this.value = image(clock);
  }
  async targets() {
    return [target];
  }
  async capture() {
    this.captures++;
    return this.value;
  }
}

export function admissions(
  options: { width?: number; producer?: string } = {},
): typeof fetch {
  return async (_url, init) => {
    if (!(init?.body instanceof FormData))
      throw new Error('Expected invocation image form');
    const invocation = init.body.get('invocation'),
      requestId = init.body.get('requestId'),
      ordinal = init.body.get('ordinal'),
      file = init.body.get('file');
    if (
      typeof invocation !== 'string' ||
      typeof requestId !== 'string' ||
      typeof ordinal !== 'string' ||
      !(file instanceof Blob)
    )
      throw new Error('Invalid invocation form');
    const bytes = new Uint8Array(await file.arrayBuffer());
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    return new Response(
      JSON.stringify({
        request: { invocation, requestId, ordinal: Number(ordinal) },
        project: binding.project,
        owner: 'owner',
        producer: options.producer ?? binding.account,
        imageId: 'img_' + sha256.slice(0, 32),
        mimeType: 'image/png',
        width: options.width ?? 100,
        height: 80,
        byteLength: bytes.byteLength,
        sha256,
        admittedAt: '2026-11-16T00:00:00Z',
      }),
    );
  };
}

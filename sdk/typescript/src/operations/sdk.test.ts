import { checkedTransport } from './transport.ts';
import { connect } from '../binding/connection.ts';
import { describe, expect, expectTypeOf, it } from 'vitest';
import { CURRENT_VERSION } from '../binding/envelope.ts';
import type { Arrival, Socket } from '../binding/connection.ts';
import { Plowshare, Refusal, requirePayload } from './sdk.ts';
import { decodePayload, decodeReply, decodeRequest } from './schema.ts';
import type { Replies } from './replies.ts';

class Wire implements Socket {
  readonly sent: string[] = [];
  private received: ((event: Arrival) => void) | undefined;
  private closed: (() => void) | undefined;
  send(text: string): void {
    this.sent.push(text);
  }
  close(): void {
    this.closed?.();
  }
  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') this.received = listener;
    else this.closed = () => listener({ data: null });
  }
  push(value: unknown): void {
    this.received?.({ data: JSON.stringify(value) });
  }
  answer(code: string, payload?: unknown): void {
    const frame: unknown = JSON.parse(this.sent.at(-1) ?? '{}');
    if (
      frame === null ||
      typeof frame !== 'object' ||
      !('id' in frame) ||
      !('type' in frame)
    )
      throw new Error('test expected a submitted frame');
    this.received?.({
      data: JSON.stringify({
        id: frame.id,
        type: frame.type,
        protocol_version: CURRENT_VERSION,
        payload: { code, ...(payload === undefined ? {} : { payload }) },
        futureEnvelope: true,
      }),
    });
  }
}

describe('validated public SDK contracts', () => {
  it('exposes typed Relay methods without acknowledging reads or replaying foreign replies', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    const id = '11111111-1111-1111-1111-111111111111';
    const published = client.publishRelay({
      requestId: id,
      project: 'fixture',
      topic: 'checks.requests',
      text: 'complete message',
      occurredAt: '2026-10-08T00:00:00Z',
    });
    wire.answer('OK', {
      requestId: id,
      project: 'fixture',
      topic: 'checks.requests',
      position: '1',
      publishedAt: '2026-10-08T00:00:00Z',
    });
    expectTypeOf(requirePayload(await published)).toEqualTypeOf<
      Replies['relay.publish']
    >();
    const consumption = {
      project: 'fixture',
      topic: 'checks.requests',
      group: 'detectors',
      consumerId: id,
      start: 'OLDEST_RETAINED' as const,
    };
    const reading = client.consumeRelay(consumption);
    const empty = {
      ...consumption,
      status: 'EMPTY',
      batchId: null,
      fence: null,
      through: '0',
      expiresAt: null,
      expiredThrough: null,
      events: [],
    };
    wire.answer('OK', empty);
    expectTypeOf(requirePayload(await reading)).toEqualTypeOf<
      Replies['relay.consume']
    >();
    expect(wire.sent).toHaveLength(2);
    const acknowledgement = client.acknowledgeRelay({
      project: 'fixture',
      topic: 'checks.requests',
      group: 'detectors',
      consumerId: id,
      batchId: id,
      fence: '1',
    });
    wire.answer('BAD_REQUEST');
    const refused = await acknowledgement;
    expect(() => requirePayload(refused)).toThrow(Refusal);
    const foreign = client.consumeRelay(consumption);
    wire.answer('OK', {
      ...empty,
      consumerId: '22222222-2222-2222-2222-222222222222',
    });
    await expect(foreign).rejects.toMatchObject({
      delivery: 'INVALID_RESPONSE',
    });
    expect(wire.sent).toHaveLength(4);
    client.close();
  });
  it('pairs typed results with their operation and strips unknown output fields', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    const waiting = client.request('project.list', {});
    wire.answer('OK', []);
    const rows = requirePayload(await waiting);
    expectTypeOf(rows).toEqualTypeOf<Replies['project.list']>();
    expect(rows).toEqual([]);
    expect(
      decodeReply('information.upload', {
        revision: 'revision',
        resource: 'resource',
        created: true,
        future: { private: 'opaque' },
      }),
    ).toEqual({ revision: 'revision', resource: 'resource', created: true });
    client.close();
  });
  it('preserves the explicit no-content success contract', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    const waiting = client.request('project.move', { project: 'p', to: 'new' });
    wire.answer('NO_CONTENT');
    expect(requirePayload(await waiting)).toBeNull();
    expect(wire.sent).toHaveLength(1);
    client.close();
  });
  it('rejects unknown fields and nested type errors before sending', () => {
    expect(() =>
      decodeRequest('memory.write', {
        proposal: {
          summary: 's',
          scope: 's',
          body: 'b',
          formedBy: 'user',
          formedWhere: { bad: true },
        },
      }),
    ).toThrow();
    expect(() => decodeRequest('project.list', { future: true })).toThrow();
    expect(() =>
      decodeRequest('information.list', {
        scope: { kind: 'personal', project: 'wrong' },
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('information.list', {
        scope: { kind: 'project', project: 3 },
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('job.status', { job: '', unexpected: true }),
    ).toThrow();
    expect(() =>
      decodePayload('memory.recall', { question: 'q', limit: 1.5 }),
    ).toThrow();
  });
  it('does not submit invalid in-process values or replay unreadable acknowledgements', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    await expect(client.request('job.status', { job: '' })).rejects.toThrow();
    expect(wire.sent).toHaveLength(0);
    const pending = client.request('project.list', {});
    wire.answer('OK', { wrong: 'shape' });
    await expect(pending).rejects.toMatchObject({
      delivery: 'INVALID_RESPONSE',
    });
    expect(wire.sent).toHaveLength(1);
    client.close();
  });
  it('returns checked refusals without exposing arbitrary refusal payloads', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    const pending = client.request('project.list', {});
    wire.answer('BAD_REQUEST', { opaque: 'server detail' });
    const reply = await pending;
    expect(reply).toEqual({
      kind: 'refused',
      outcome: { code: 'BAD_REQUEST' },
    });
    expect(() => requirePayload(reply)).toThrow(Refusal);
    client.close();
  });
  it('validates report findings and union exclusivity recursively', () => {
    expect(() =>
      decodeRequest('information.record.report', {
        scope: { kind: 'personal' },
        requestId: 'request',
        name: 'report',
        text: 'text',
        findings: [
          {
            id: 'finding',
            objective: 'objective',
            claim: 'claim',
            rationale: 'reason',
            verdict: 'unsupported',
          },
        ],
      }),
    ).toThrow();
    expect(() =>
      decodeRequest('conversation.follow', {
        conversation: 'conversation',
        conversations: ['other'],
      }),
    ).toThrow();
    expect(
      decodePayload('conversation.follow', { conversations: ['one'] }),
    ).toEqual({ conversations: ['one'] });
  });
});

describe('external DTOs and typed notifications', () => {
  it('rejects raw or inconsistent external messages without sending', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    expect(() =>
      decodePayload('outgoing.send', {
        requestId: '00000000-0000-4000-8000-000000000001',
        peer: 'peer',
        message: {
          parts: [{ text: 'text', url: 'https://example.test/file' }],
        },
      }),
    ).toThrow();
    await expect(
      client.request('outgoing.send', {
        requestId: '00000000-0000-4000-8000-000000000001',
        peer: 'peer',
        message: { parts: [{ raw: 'bad base64' }] },
      }),
    ).rejects.toThrow();
    expect(wire.sent).toHaveLength(0);
    client.close();
  });
  it('validates integration variants and report observations', () => {
    expect(() =>
      decodePayload('outgoing.send', {
        requestId: '00000000-0000-4000-8000-000000000001',
        peer: 'peer',
        message: {
          parts: [
            {
              data: {
                schema: 'plowshare-integration/1',
                binding: 'binding',
                operation: 'actions.execute',
                arguments: {
                  action: 'toggle',
                  parameters: { entity: 'lamp', enabled: true },
                },
              },
            },
          ],
        },
      }),
    ).not.toThrow();
    expect(() =>
      decodePayload('outgoing.report', {
        id: 'id',
        revision: 1,
        state: 'made-up',
      }),
    ).toThrow();
    expect(() =>
      decodeReply('outgoing.claim', { work: null, action: 'send' }),
    ).toThrow();
  });
  it('projects valid pushes and reports malformed hints without disturbing requests', async () => {
    const wire = new Wire(),
      pushes: unknown[] = [],
      faults: unknown[] = [];
    const client = new Plowshare({
      socket: wire,
      onPush: (p) => pushes.push(p),
      onPushFault: (p) => faults.push(p),
    });
    const waiting = client.request('project.list', {});
    wire.push({ kind: 'inbox.changed', unread: 2, future: { private: true } });
    wire.push({ kind: 'inbox.changed', unread: -1 });
    const requestId = '00000000-0000-4000-8000-000000000001';
    wire.push({
      kind: 'orchestration.resumed',
      orchestration: 'root',
      requestId,
      future: true,
    });
    wire.push({
      kind: 'orchestration.resumed',
      orchestration: 'root',
      requestId: 'invalid',
    });
    wire.answer('OK', []);
    expect(requirePayload(await waiting)).toEqual([]);
    expect(pushes).toEqual([
      { kind: 'inbox.changed', unread: 2 },
      { kind: 'orchestration.resumed', orchestration: 'root', requestId },
    ]);
    expect(faults).toHaveLength(2);
    expect(wire.sent).toHaveLength(1);
    client.close();
  });
});

describe('existing socket owners use the same typed boundary', () => {
  it('rejects invalid inputs before sending and never replays an unreadable mutation', async () => {
    const wire = new Wire(),
      connection = connect({ socket: wire }),
      transport = checkedTransport(connection);
    await expect(
      transport.ask('project.create', { name: '' }),
    ).rejects.toThrow();
    expect(wire.sent).toHaveLength(0);
    const empty = transport.ask('schedule.pause', {
      schedule: 'schedule',
      paused: true,
    });
    wire.answer('NO_CONTENT');
    expect(await empty).toEqual({ code: 'NO_CONTENT', payload: null });
    const pending = transport.ask('project.create', { name: 'project' });
    wire.answer('OK', { name: 5 });
    await expect(pending).rejects.toThrow('completion is unknown');
    expect(wire.sent).toHaveLength(2);
    connection.close();
  });
  it('rejects inconsistent report evidence and objectives before sending', async () => {
    const wire = new Wire(),
      client = new Plowshare({ socket: wire });
    const report = {
      scope: { kind: 'personal' as const },
      requestId: '00000000-0000-4000-8000-000000000001',
      name: 'report',
      text: 'A retained report.',
      objectives: ['question'],
      findings: [
        {
          id: 'finding',
          objective: 'other',
          claim: 'claim',
          rationale: 'reason',
          verdict: 'holds' as const,
          support: ['not-an-id'],
        },
      ],
    };
    await expect(
      client.request('information.record.report', report),
    ).rejects.toThrow();
    await expect(
      client.request('information.record.report', {
        ...report,
        findings: [{ ...report.findings[0]!, objective: 'question' }],
      }),
    ).rejects.toThrow();
    expect(wire.sent).toHaveLength(0);
    client.close();
  });
});

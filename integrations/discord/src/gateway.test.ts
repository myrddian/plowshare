import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { ConnectionFault, Plowshare, Refusal } from 'plowshare-client-node';
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection';
import { CURRENT_VERSION } from 'plowshare-client-ts/binding/envelope';
import { SdkGateway } from './gateway.ts';
import { configuration, PRINCIPAL, task } from './adapter.test-support.ts';

/** Exercise the shipped SDK encoder/decoder, without internal server fixtures or a database. */
class Wire implements Socket {
  readonly sent: string[] = [];
  private received: ((event: Arrival) => void) | undefined;
  private closed: (() => void) | undefined;
  send(frame: string): void {
    this.sent.push(frame);
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
  answer(code: string, payload: unknown): void {
    const frame: unknown = JSON.parse(this.sent.at(-1) ?? '{}');
    if (
      frame === null ||
      typeof frame !== 'object' ||
      !('id' in frame) ||
      !('type' in frame)
    )
      throw new Error('No submitted public SDK frame');
    this.received?.({
      data: JSON.stringify({
        id: frame.id,
        type: frame.type,
        protocol_version: CURRENT_VERSION,
        payload: { code, payload },
      }),
    });
  }
}
await test('service identity is read from authenticated public SDK status, not a configured label', async () => {
  const wire = new Wire();
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const gateway = new SdkGateway(client);
  try {
    const service = gateway.identity();
    wire.answer('OK', { handle: PRINCIPAL, serverAdmin: false });
    assert.equal(await service, PRINCIPAL);
    const human = gateway.identity();
    wire.answer('OK', { handle: 'alice', serverAdmin: false });
    await assert.rejects(human, /service principal/);
  } finally {
    client.close();
  }
});
await test('incoming receipt and catalog refusal pass through public SDK validation', async () => {
  const wire = new Wire();
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const gateway = new SdkGateway(client);
  try {
    const route = configuration().routes[0];
    assert.ok(route);
    const catalog = gateway.catalog(route);
    wire.answer('BAD_REQUEST', null);
    await assert.rejects(catalog, Refusal);
    const request = {
      project: 'research',
      client: 'discord_fixture',
      agent: 'researcher',
      requestId: randomUUID(),
      context: null,
      body: 'Bounded input',
    };
    const receiving = gateway.receive(request);
    const result = task();
    wire.answer('OK', result);
    assert.deepEqual(await receiving, result);
    const malformed = gateway.status({
      project: 'research',
      client: 'discord_fixture',
      id: result.id,
    });
    wire.answer('OK', { ...result, context: 'not-a-UUID' });
    await assert.rejects(malformed);
  } finally {
    client.close();
  }
});

await test('Relay receipts must belong to the requested subscription and batch', async () => {
  const wire = new Wire();
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const gateway = new SdkGateway(client);
  const request = {
    project: 'research',
    topic: 'research.completed',
    group: 'discord.results',
    consumerId: randomUUID(),
    start: 'LATEST' as const,
    limit: 1,
    waitMs: 0,
  };
  try {
    const foreign = gateway.consume(request);
    wire.answer('OK', {
      project: 'private',
      topic: request.topic,
      group: request.group,
      consumerId: request.consumerId,
      status: 'EMPTY',
      batchId: null,
      fence: null,
      through: '0',
      expiresAt: null,
      expiredThrough: null,
      events: [],
    });
    await assert.rejects(foreign, ConnectionFault);
    const ackRequest = {
      project: request.project,
      topic: request.topic,
      group: request.group,
      consumerId: request.consumerId,
      batchId: randomUUID(),
      fence: '1',
    };
    const ack = gateway.acknowledge(ackRequest);
    wire.answer('OK', {
      project: ackRequest.project,
      topic: ackRequest.topic,
      group: ackRequest.group,
      batchId: randomUUID(),
      through: '1',
      gap: false,
    });
    await assert.rejects(ack, ConnectionFault);
  } finally {
    client.close();
  }
});

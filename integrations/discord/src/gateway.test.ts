import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { ConnectionFault, Plowshare } from 'plowshare-client-node';
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection';
import { CURRENT_VERSION } from 'plowshare-client-ts/binding/envelope';
import { SdkGateway } from './gateway.ts';
import { object } from './config.ts';
import { decodeState } from './state.ts';
import { DiscordAdapter } from './adapter.ts';
import type { Submission } from './contracts.ts';
import type { ReplyChunk } from './messaging-codec.ts';
import type { Replies } from 'plowshare-client-node';
type RelayEvent = Replies['relay.log']['events'][number];
import {
  configuration,
  PRINCIPAL,
  task,
  MemoryState,
  FakeDiscord,
  input,
  BOT,
} from './adapter.test-support.ts';

/** Direct facade tests must establish the same durable admission owner as DiscordAdapter. */
function own(repository: MemoryState, request: Submission): Submission {
  const source = input();
  assert.equal(source.kind, 'guild');
  if (source.kind !== 'guild') throw new Error('Expected guild fixture');
  repository.state.records.push({
    key: 'discord:' + randomUUID(),
    kind: 'ASK',
    userId: source.userId,
    destination: {
      kind: 'guild',
      guildId: source.guildId,
      channelId: source.channelId,
      parentId: source.parentId,
    },
    project: request.project,
    bot: request.agent,
    client: request.client,
    contextKey: randomUUID(),
    requestId: request.requestId,
    nativeHeader: null,
    lastRevision: '0',
    replyCursor: null,
    replyTo: source.messageId,
    notification: null,
    admission: 'UNKNOWN',
    taskId: null,
    contextId: request.context,
    finished: false,
    seenReplies: [],
    outputs: [],
  });
  return request;
}

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

class MessagingWire extends Wire {
  readonly events: RelayEvent[] = [];
  commands = 0;
  lostReceipt = false;
  dropCursor: string | undefined;
  refusal = false;
  foreignNegative = false;
  replyGeneration = randomUUID();
  readonly requestGeneration = randomUUID();
  current = task();
  page:
    | ((cursor: unknown) => {
        replies: readonly ReplyChunk[];
        next: string | null;
        resume: string | null;
      })
    | undefined;
  override send(frame: string): void {
    super.send(frame);
    const row = object(JSON.parse(frame) as unknown, [
      'id',
      'type',
      'protocol_version',
      'payload',
      'acting',
    ]);
    const payload = object(row.payload, [
      'project',
      'topic',
      'after',
      'limit',
      'text',
      'requestId',
      'occurredAt',
    ]);
    queueMicrotask(() => {
      if (row.type === 'agent.list') {
        this.answer('OK', [
          {
            name: 'researcher',
            tools: [],
            calls: [],
            scopes: [],
            served: true,
            withheld: [],
            bot: true,
            description: 'Help',
            preferred: false,
            model: null,
            orchestrations: [],
            commands: [],
          },
        ]);
      } else if (row.type === 'relay.publish') {
        this.commands++;
        const command = object(
          JSON.parse(
            typeof payload.text === 'string' ? payload.text : 'null',
          ) as unknown,
          [
            'schema',
            'requestId',
            'client',
            'context',
            'body',
            'command',
            'task',
            'originalRequest',
            'cursor',
          ],
        );
        if (command.schema === 'plowshare-message-request/1')
          this.current = {
            ...this.current,
            requestId:
              typeof command.requestId === 'string'
                ? command.requestId
                : 'invalid',
          };
        if (
          !this.lostReceipt &&
          !(this.dropCursor !== undefined && command.cursor === this.dropCursor)
        ) {
          const { revision, cursor: _cursor, ...header } = this.current;
          const base = {
            schema: 'plowshare-message-task/1',
            requestId: payload.requestId,
            sourceEvent: payload.requestId,
            client: command.client,
            revision,
            task: {
              ...header,
              replies: header.replies.map((reply) => ({
                ...reply,
                offset: '0',
                complete: true,
              })),
            },
            refusal: null,
            next: null as string | null,
            resume: 'follow-cursor' as string | null,
          };
          const page = this.page?.(command.cursor);
          if (page !== undefined) {
            base.task = { ...base.task, replies: [...page.replies] };
            base.next = page.next;
            base.resume = page.resume;
          }
          if (this.foreignNegative)
            this.event({
              ...base,
              requestId: randomUUID(),
              sourceEvent: randomUUID(),
              task: null,
              revision: null,
              resume: null,
              refusal: 'NOT_ACCESSIBLE',
            });
          this.event(
            this.refusal
              ? {
                  ...base,
                  task: null,
                  revision: null,
                  resume: null,
                  refusal: 'NOT_ACCESSIBLE',
                }
              : base,
          );
        }
        this.answer('OK', {
          requestId: payload.requestId,
          project: payload.project,
          topic: payload.topic,
          position: String(this.commands),
          publishedAt: '2026-01-01T00:00:00Z',
        });
      } else if (row.type === 'relay.log') {
        const topic = payload.topic;
        const after = typeof payload.after === 'string' ? payload.after : '0';
        const replies = topic === 'discord.replies';
        const events = replies
          ? this.events
              .filter((event) => BigInt(event.position) > BigInt(after))
              .slice(0, typeof payload.limit === 'number' ? payload.limit : 100)
          : [];
        this.answer('OK', {
          scope: { project: payload.project, system: false },
          topic: {
            name: topic,
            generation: replies ? this.replyGeneration : this.requestGeneration,
            kind: 'TEXT',
            retentionSeconds: '3600',
            maxRecords: null,
            through: String(replies ? this.events.length : this.commands),
            expiredThrough: '0',
          },
          after,
          next: events.at(-1)?.position ?? after,
          gapThrough: null,
          events,
          subscribers: [],
          branches: [],
        });
      } else throw new Error('Unexpected legacy or unsupported operation');
    });
  }
  private event(body: unknown): void {
    this.events.push({
      position: String(this.events.length + 1),
      eventId: randomUUID(),
      publisher: 'messaging:ingress',
      occurredAt: '2026-01-01T00:00:00Z',
      publishedAt: '2026-01-01T00:00:00Z',
      correlationId: null,
      causationId: null,
      payload: {
        kind: 'TEXT',
        text: JSON.stringify(body),
        schedule: null,
        emits: null,
        fireAt: null,
      },
    });
  }
}
await test('native intake uses existing Relay operations, filters foreign negatives and pins incarnations', async () => {
  const wire = new MessagingWire();
  wire.foreignNegative = true;
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const config = configuration();
  const repository = new MemoryState();
  const gateway = new SdkGateway(client, config.messaging, repository, 200);
  const request = {
    project: 'research',
    agent: 'researcher',
    client: 'discord_fixture',
    requestId: randomUUID(),
    context: null,
    body: 'Bounded input',
  };
  try {
    await gateway.catalog({ project: 'research', bot: 'researcher' });
    const result = await gateway.receive(own(repository, request));
    assert.equal(result.id, wire.current.id);
    assert.equal(result.requestId, request.requestId);
    const current = await gateway.status({
      project: request.project,
      agent: request.agent,
      client: request.client,
      id: result.id,
      cursor: result.cursor,
    });
    assert.equal(current.id, result.id);
    assert.equal(wire.commands, 1);
    assert.equal(repository.state.messageTraversals.length, 1);
    assert.equal(repository.state.relayGenerations.length, 2);
    wire.replyGeneration = randomUUID();
    await assert.rejects(
      gateway.receive(own(repository, { ...request, requestId: randomUUID() })),
      /incarnation/,
    );
    assert.equal(wire.commands, 1);
    assert.ok(wire.sent.every((frame) => !frame.includes('incoming.')));
  } finally {
    client.close();
  }
});
await test('lost native admission receipt is recovered by original request inspection without mutation replay', async () => {
  const wire = new MessagingWire();
  wire.lostReceipt = true;
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const repository = new MemoryState();
  const gateway = new SdkGateway(
    client,
    configuration().messaging,
    repository,
    20,
  );
  const request = {
    project: 'research',
    agent: 'researcher',
    client: 'discord_fixture',
    requestId: randomUUID(),
    context: null,
    body: 'Work',
  };
  try {
    await assert.rejects(
      gateway.receive(own(repository, request)),
      (error: unknown) =>
        error instanceof ConnectionFault && error.delivery === 'UNKNOWN',
    );
    assert.equal(wire.commands, 1);
    wire.lostReceipt = false;
    const result = await gateway.status({
      project: request.project,
      agent: request.agent,
      client: request.client,
      id: null,
      requestId: request.requestId,
    });
    assert.equal(result.requestId, request.requestId);
    assert.equal(wire.commands, 2);
    const admissions = wire.sent.filter((frame) =>
      frame.includes('plowshare-message-request/1'),
    );
    assert.equal(admissions.length, 1);
  } finally {
    client.close();
  }
});

await test('completed headers wait for Unicode chunks and FOLLOW reads only new replies', async () => {
  const wire = new MessagingWire();
  const replyId = randomUUID();
  const followId = randomUUID();
  const body = '🌻'.repeat(16_384) + '尾';
  const chunk = (
    id: string,
    text: string,
    offset: string,
    complete: boolean,
  ): ReplyChunk => ({
    id,
    body: text,
    offset,
    complete,
    finalReply: true,
    generated: false,
    ending: 'ANSWERED',
    postedAt: '2026-01-01T00:00:00Z',
  });
  wire.current = { ...wire.current, state: 'COMPLETED', ending: 'ANSWERED' };
  const cursors: unknown[] = [];
  wire.page = (cursor) => {
    cursors.push(cursor);
    if (cursor === 'head-tail')
      return {
        replies: [chunk(replyId, '尾', '16384', true)],
        next: null,
        resume: 'follow-1',
      };
    if (cursor === 'follow-1')
      return {
        replies: [chunk(followId, 'new only', '0', true)],
        next: null,
        resume: 'follow-2',
      };
    return {
      replies: [chunk(replyId, '🌻'.repeat(16_384), '0', false)],
      next: 'head-tail',
      resume: null,
    };
  };
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const repository = new MemoryState();
  const gateway = new SdkGateway(
    client,
    configuration().messaging,
    repository,
    200,
  );
  try {
    const admitted = await gateway.receive(
      own(repository, {
        project: 'research',
        agent: 'researcher',
        client: 'discord_fixture',
        requestId: randomUUID(),
        context: null,
        body: 'work',
      }),
    );
    assert.equal(admitted.state, 'COMPLETED');
    assert.equal(admitted.replies[0]?.body, body);
    assert.equal(admitted.cursor, 'follow-2');
    assert.deepEqual(
      admitted.replies.map((reply) => reply.body),
      [body, 'new only'],
    );
    const followed = await gateway.status({
      project: 'research',
      agent: 'researcher',
      client: 'discord_fixture',
      id: admitted.id,
      cursor: admitted.cursor,
    });
    assert.deepEqual(
      followed.replies.map((reply) => reply.body),
      [body, 'new only'],
    );
    assert.equal(followed.cursor, 'follow-2');
    assert.deepEqual(cursors, [undefined, 'head-tail', 'follow-1']);
    assert.equal(wire.commands, 3);
  } finally {
    client.close();
  }
});

await test('Unicode paging survives a timeout and state reload without refetching its prefix', async () => {
  const wire = new MessagingWire();
  const reply = randomUUID();
  const body = '🌻'.repeat(16384) + '尾';
  const cursors: unknown[] = [];
  const chunk = (
    text: string,
    offset: string,
    complete: boolean,
  ): ReplyChunk => ({
    id: reply,
    body: text,
    offset,
    complete,
    finalReply: true,
    generated: false,
    ending: 'ANSWERED',
    postedAt: '2026-01-01T00:00:00Z',
  });
  wire.current = { ...wire.current, state: 'COMPLETED', ending: 'ANSWERED' };
  wire.page = (cursor) => {
    cursors.push(cursor);
    if (cursor === 'tail')
      return {
        replies: [chunk('尾', '16384', true)],
        next: null,
        resume: 'follow',
      };
    if (cursor === 'follow')
      return { replies: [], next: null, resume: 'follow-done' };
    return {
      replies: [chunk('🌻'.repeat(16384), '0', false)],
      next: 'tail',
      resume: null,
    };
  };
  wire.dropCursor = 'tail';
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  const config = configuration();
  const repository = new MemoryState();
  try {
    await new DiscordAdapter(
      config,
      repository,
      new SdkGateway(client, config.messaging, repository, 20),
      new FakeDiscord(),
    ).accept(input());
    const partial = repository.state.messageTraversals[0];
    assert.ok(partial);
    assert.equal(partial.next, 'tail');
    assert.equal(partial.resume, null);
    assert.equal(partial.parts[0]?.reply.body, '🌻'.repeat(16384));
    const retained: unknown = JSON.parse(repository.persisted);
    const restored = new MemoryState(
      decodeState(retained, config, PRINCIPAL, BOT),
    );
    wire.dropCursor = undefined;
    cursors.length = 0;
    const recoveredGateway = new SdkGateway(
      client,
      config.messaging,
      restored,
      200,
    );
    const pending = restored.state.records[0];
    assert.ok(pending);
    const completed = await recoveredGateway.status({
      project: pending.project,
      agent: pending.bot,
      client: pending.client,
      id: null,
      requestId: pending.requestId,
    });
    assert.equal(completed.replies[0]?.body, body);
    assert.equal(restored.state.messageTraversals[0]?.next, null);
    assert.equal(restored.state.records[0]?.replyCursor, null);
    assert.equal(
      decodeState(
        JSON.parse(restored.persisted) as unknown,
        config,
        PRINCIPAL,
        BOT,
      ).messageTraversals.length,
      1,
    );
    await new DiscordAdapter(
      config,
      restored,
      recoveredGateway,
      new FakeDiscord(),
    ).poll();
    assert.deepEqual(cursors, ['tail', 'follow']);
    assert.equal(restored.state.messageTraversals.length, 0);
    const record = restored.state.records[0];
    assert.ok(record);
    assert.equal(record.finished, true);
    assert.equal(record.replyCursor, 'follow-done');
    assert.equal(
      record.outputs
        .filter((output) => output.key.startsWith(reply + ':'))
        .map((output) => output.text)
        .join(''),
      body,
    );
    const admissions = wire.sent.filter((frame) =>
      frame.includes('plowshare-message-request/1'),
    );
    assert.equal(admissions.length, 1);
    assert.equal(
      decodeState(
        JSON.parse(restored.persisted) as unknown,
        config,
        PRINCIPAL,
        BOT,
      ).messageTraversals.length,
      0,
    );
    for (const invalid of [
      { ...partial, client: 'foreign_client' },
      { ...partial, requests: 'other.commands' },
      { ...partial, header: { ...partial.header, agent: 'other' } },
      { ...partial, next: null },
      {
        ...partial,
        parts: [
          {
            ...partial.parts[0],
            reply: { ...partial.parts[0]?.reply, body: '\ud800' },
          },
        ],
      },
      { ...partial, parts: [...partial.parts, ...partial.parts] },
    ])
      assert.throws(() =>
        decodeState(
          { ...repository.state, messageTraversals: [invalid] },
          config,
          PRINCIPAL,
          BOT,
        ),
      );
    const { messageTraversals: _old, ...legacy } = repository.state;
    assert.equal(
      decodeState(legacy, config, PRINCIPAL, BOT).messageTraversals.length,
      0,
    );
  } finally {
    client.close();
  }
});

await test('higher revisions cannot replace native message or creation identity during paging', async () => {
  for (const field of ['message', 'createdAt'] as const) {
    const wire = new MessagingWire();
    wire.page = (cursor) => {
      if (cursor === 'tail')
        return { replies: [], next: null, resume: 'follow' };
      wire.current = {
        ...wire.current,
        revision: '2',
        [field]: field === 'message' ? 'bdm_other' : '2026-01-02T00:00:00Z',
      };
      return { replies: [], next: 'tail', resume: null };
    };
    const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
    const repository = new MemoryState();
    try {
      await assert.rejects(
        new SdkGateway(
          client,
          configuration().messaging,
          repository,
          200,
        ).receive(
          own(repository, {
            project: 'research',
            agent: 'researcher',
            client: 'discord_fixture',
            requestId: randomUUID(),
            context: null,
            body: 'work',
          }),
        ),
        ConnectionFault,
      );
      assert.equal(repository.state.messageTraversals[0]?.header.revision, '1');
    } finally {
      client.close();
    }
  }
});

await test('observed native identity remains pinned after completed traversal retirement', async () => {
  for (const field of ['message', 'createdAt'] as const) {
    const wire = new MessagingWire();
    const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
    const repository = new MemoryState();
    const config = configuration();
    const adapter = new DiscordAdapter(
      config,
      repository,
      new SdkGateway(client, config.messaging, repository, 200),
      new FakeDiscord(),
    );
    try {
      await adapter.accept(input());
      assert.equal(repository.state.messageTraversals.length, 0);
      const native = repository.state.records[0]?.nativeHeader;
      assert.ok(native);
      wire.current = {
        ...wire.current,
        revision: '2',
        [field]: field === 'message' ? 'bdm_changed' : '2026-01-02T00:00:00Z',
      };
      await assert.rejects(adapter.poll(), /retained native identity/);
      assert.deepEqual(repository.state.records[0]?.nativeHeader, native);
      assert.equal(repository.state.messageTraversals.length, 0);
    } finally {
      client.close();
    }
  }
});

await test('facade refuses unowned admission before any public transport side effect', async () => {
  const wire = new MessagingWire();
  const client = new Plowshare({ socket: wire, dispatchMode: 'ordered' });
  try {
    await assert.rejects(
      new SdkGateway(
        client,
        configuration().messaging,
        new MemoryState(),
        200,
      ).receive({
        project: 'research',
        agent: 'researcher',
        client: 'discord_fixture',
        requestId: randomUUID(),
        context: null,
        body: 'unowned',
      }),
      /owned delivery record/,
    );
    assert.equal(wire.sent.length, 0);
  } finally {
    client.close();
  }
});

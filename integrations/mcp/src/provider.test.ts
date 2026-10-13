import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Payloads, Reply } from 'plowshare-client-ts';
import type { Operation } from 'plowshare-client-ts/operations/direct';
import { McpProvider } from './provider.ts';
import type { ProviderConnection } from './provider.ts';
import {
  Credentials,
  Sessions,
  Journal,
  registration,
  options,
  invocation,
  id,
  secondId,
} from './provider.test-support.ts';
import { decodeMcpTool, object } from './codec.ts';
import { rawTool } from './wire.test-support.ts';
import { setTimeout as pause } from 'node:timers/promises';

await test('separate credential subjects and generations create separate sessions and offered identities', async () => {
  const first = new Credentials(),
    second = new Credentials();
  second.credential = {
    ...second.credential,
    subject: '@service/' + id,
    credentialGeneration: id,
  };
  const service = {
    ...registration,
    binding: {
      ...registration.binding,
      credentialSubject: second.credential.subject,
      definition: {
        ...registration.binding.definition,
        subject: second.credential.subject,
      },
    },
  };
  const a = new Sessions(),
    b = new Sessions();
  const one = await McpProvider.discover(registration, first, a, options);
  const two = await McpProvider.discover(service, second, b, options);
  try {
    const firstTool = one.tools[0],
      secondTool = two.tools[0];
    assert.ok(firstTool);
    assert.ok(secondTool);
    assert.notEqual(firstTool.declaration.name, secondTool.declaration.name);
    assert.notEqual(one.journalIdentity, two.journalIdentity);
    assert.equal(
      (await firstTool.handler(invocation(firstTool.declaration.name))).state,
      'COMPLETED',
    );
    assert.deepEqual(first.invocations, [null, id]);
    assert.equal(a.calls, 1);
    assert.equal(b.calls, 0);
    first.credential = { ...first.credential, credentialGeneration: id };
    assert.equal(
      (await firstTool.handler(invocation(firstTool.declaration.name))).state,
      'REJECTED',
    );
    assert.equal(a.calls, 1);
  } finally {
    await one.close();
    await two.close();
  }
});
await test('authorization refusal and expired invocation send no effects; loss returns UNKNOWN and disables context', async () => {
  const credentials = new Credentials(),
    sessions = new Sessions();
  const adapter = await McpProvider.discover(
    registration,
    credentials,
    sessions,
    options,
  );
  const tool = adapter.tools[0];
  assert.ok(tool);
  credentials.denied = true;
  assert.equal(
    (await tool.handler(invocation(tool.declaration.name))).state,
    'REJECTED',
  );
  credentials.denied = false;
  assert.equal(
    (
      await tool.handler({
        ...invocation(tool.declaration.name),
        deadline: '2000-01-01T00:00:00Z',
      })
    ).state,
    'REJECTED',
  );
  assert.equal(sessions.calls, 0);
  sessions.fail = true;
  assert.equal(
    (await tool.handler(invocation(tool.declaration.name))).state,
    'UNKNOWN',
  );
  assert.equal(
    (await tool.handler(invocation(tool.declaration.name))).state,
    'REJECTED',
  );
  assert.equal(sessions.calls, 1);
  await assert.rejects(adapter.refresh());
  await adapter.close();
});
await test('whole-catalogue discovery is bounded and changes stop renewal', async () => {
  for (const mode of ['duplicate', 'cursor', 'empty'] as const) {
    const sessions = new Sessions();
    sessions.tools =
      mode === 'duplicate'
        ? [decodeMcpTool(rawTool), decodeMcpTool(rawTool)]
        : [];
    sessions.nextCursor = mode === 'cursor' ? 'again' : null;
    await assert.rejects(
      McpProvider.discover(registration, new Credentials(), sessions, options),
    );
    assert.ok(sessions.closed);
    assert.ok(sessions.pages <= 2);
  }
  const sessions = new Sessions();
  const adapter = await McpProvider.discover(
    registration,
    new Credentials(),
    sessions,
    options,
  );
  sessions.tools = [
    decodeMcpTool({ ...rawTool, description: 'Changed contract' }),
  ];
  await assert.rejects(adapter.refresh());
  await adapter.close();
});

await test('late credential release cannot dispatch after the invocation deadline or shutdown', async () => {
  const credentials = new Credentials(),
    sessions = new Sessions();
  const stop = new AbortController();
  let blocked = false;
  const adapter = await McpProvider.discover(
    registration,
    {
      async release(invocation) {
        if (blocked) await pause(80);
        return credentials.release(invocation);
      },
    },
    sessions,
    options,
    stop.signal,
  );
  try {
    const tool = adapter.tools[0];
    assert.ok(tool);
    blocked = true;
    const result = await tool.handler({
      ...invocation(tool.declaration.name),
      deadline: new Date(Date.now() + 20).toISOString(),
    });
    assert.equal(result.state, 'REJECTED');
    stop.abort();
    assert.equal(
      (await tool.handler(invocation(tool.declaration.name))).state,
      'REJECTED',
    );
    await pause(100);
    assert.equal(sessions.calls, 0);
    assert.deepEqual(credentials.invocations, [null, id]);
  } finally {
    await adapter.close();
  }
});
await test('existing ToolProvider publishes, polls, journals and reconciles by original worker fence without replay', async () => {
  const sessions = new Sessions();
  const adapter = await McpProvider.discover(
    registration,
    new Credentials(),
    sessions,
    options,
  );
  const journal = new Journal(adapter.journalIdentity);
  const declared = adapter.tools[0];
  assert.ok(declared);
  const call = invocation(declared.declaration.name);
  const operations: string[] = [];
  let publications = 0;
  let resultText = '';
  let dropResult = true;
  const connection: ProviderConnection = {
    async request<K extends Operation>(
      operation: K,
      payload: Payloads[K],
    ): Promise<Reply<K>> {
      operations.push(operation);
      const request = object(payload);
      assert.equal(request.binding, id);
      assert.equal(request.registration, secondId);
      let reply: unknown;
      if (operation === 'tool.scope.worker.publish') {
        const publication = object(request.publication);
        publications++;
        if (publication.correlationId === id) {
          const parsed = object(JSON.parse(String(publication.text)));
          assert.equal(typeof parsed.text, 'string');
          resultText = String(parsed.text);
          if (dropResult) {
            dropResult = false;
            throw new Error('Unknown provider publication');
          }
        }
        reply = {
          project: publication.project,
          topic: publication.topic,
          requestId: publication.requestId,
          position: '1',
          publishedAt: '2026-01-01T00:00:00Z',
        };
      } else if (operation === 'tool.scope.worker.consume') {
        const consumption = object(request.consumption);
        reply = {
          project: consumption.project,
          topic: consumption.topic,
          group: consumption.group,
          consumerId: consumption.consumerId,
          status: 'DATA',
          batchId: id,
          fence: '1',
          through: '1',
          expiresAt: '2099-01-01T00:00:00Z',
          expiredThrough: null,
          events: [
            {
              position: '1',
              eventId: id,
              publisher: 'tool-runtime',
              occurredAt: '2026-01-01T00:00:00Z',
              publishedAt: '2026-01-01T00:00:00Z',
              correlationId: id,
              causationId: id,
              causation: null,
              payload: {
                kind: 'TEXT',
                text: JSON.stringify(call),
                schedule: null,
                emits: null,
                fireAt: null,
              },
            },
          ],
        };
      } else if (operation === 'tool.scope.worker.ack') {
        const ack = object(request.acknowledgement);
        reply = {
          project: ack.project,
          topic: ack.topic,
          group: ack.group,
          batchId: ack.batchId,
          through: '1',
          gap: false,
        };
      } else if (operation === 'tool.scope.worker.read')
        reply = {
          ...request,
          verified: true,
          state: 'COMPLETED',
          text: resultText,
        };
      else throw new Error('Unexpected adapter operation');
      return {
        kind: 'success',
        outcome: { code: 'OK', payload: decodeReply(operation, reply) },
      };
    },
  };
  const provider = await adapter.attach(connection, journal);
  try {
    await provider.publishCatalog(id);
    await assert.rejects(provider.poll());
    assert.equal(journal.rows.get(id)?.phase, 'publishing');
    assert.equal(sessions.calls, 1);
    const restarted = await adapter.attach(connection, journal);
    assert.equal(await restarted.reconcile(), 1);
    assert.equal(journal.rows.get(id)?.phase, 'done');
    await restarted.poll();
    assert.equal(sessions.calls, 1);
    assert.equal(publications, 2);
    await restarted.withdrawCatalog(secondId);
    assert.ok(
      operations.every((operation) =>
        operation.startsWith('tool.scope.worker.'),
      ),
    );
    assert.equal(journal.rows.get(id)?.hostedWorker?.registration, secondId);
  } finally {
    await adapter.close();
  }
});

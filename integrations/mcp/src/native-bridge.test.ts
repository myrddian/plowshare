import { setTimeout as delay } from 'node:timers/promises';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { NativeMcpHost, NativeMcpSessions } from './native-host.ts';
import { NativeProviderBridge } from './native-bridge.ts';
import {
  nativeProviderConnection,
  NativeHostCommands,
} from './native-commands.ts';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { canonicalToolSchema } from 'plowshare-client-ts/operations/tool-schemas';
import { object } from './codec.ts';
import { nativeFixture } from './native.test-support.ts';
import type { ToolScopeConnect } from 'plowshare-client-ts/operations/tool-scopes';
const scope: ToolScopeConnect = {
  project: 'fixture',
  scope: 'laptop',
  provider: 'mcpnative',
  prefix: 'native_',
  agents: ['agent'],
  grants: ['*'],
  leaseSeconds: 30,
};
for (const lost of [false, true]) {
  await test(`optional server bridge ${lost ? 'lost metadata' : 'serving'}: only cross-process calls use scopes/Relay and the effect remains direct`, async () => {
    const fixture = await nativeFixture(),
      host = await NativeMcpHost.start(
        fixture.config,
        fixture.config.owner,
        new NativeMcpSessions({ NATIVE_SELECTED: 'one' }),
        true,
      );
    const operations: string[] = [];
    let invalidations = 0;
    const tool = host.tools[0];
    assert.ok(tool?.inputSchema);
    const id = '11111111-1111-1111-1111-111111111111';
    const source = JSON.stringify({
      schema: 'plowshare-tool/2',
      invocationId: id,
      project: 'fixture',
      provider: 'scanner',
      tool: tool.name,
      account: 'caller',
      run: 'run',
      call: 'call',
      deadline: new Date(Date.now() + 2000).toISOString(),
      schemaFingerprint: createHash('sha256')
        .update(canonicalToolSchema(tool.inputSchema))
        .digest('hex'),
      arguments: { values: [{ name: 'from-server' }] },
    });
    const wire = {
      async ask(type: string, value?: unknown) {
        const request = object(value);
        operations.push(type);
        if (type === 'tool.scope.connect')
          return {
            code: 'OK' as const,
            payload: {
              ...scope,
              sourceProvider: scope.provider,
              provider: 'scanner',
              account: 'fixture',
            },
          };
        if (type === 'tool.scope.disconnect')
          return {
            code: 'OK' as const,
            payload: {
              project: scope.project,
              scope: scope.scope,
              disconnected: true,
            },
          };
        if (type === 'relay.publish') {
          if (lost) throw new Error('fixture lost metadata reply');
          return {
            code: 'OK' as const,
            payload: {
              requestId: request.requestId,
              project: request.project,
              topic: request.topic,
              position: '1',
              publishedAt: new Date().toISOString(),
            },
          };
        }
        if (type === 'relay.ack')
          return {
            code: 'OK' as const,
            payload: {
              project: request.project,
              topic: request.topic,
              group: request.group,
              batchId: request.batchId,
              through: '1',
              gap: false,
            },
          };
        if (type === 'relay.consume')
          return {
            code: 'OK' as const,
            payload: {
              project: request.project,
              topic: request.topic,
              group: request.group,
              consumerId: request.consumerId,
              status: 'DATA',
              batchId: id,
              fence: '1',
              through: '1',
              expiresAt: new Date(Date.now() + 2000).toISOString(),
              expiredThrough: null,
              events: [
                {
                  position: '1',
                  eventId: id,
                  publisher: 'tool-runtime',
                  occurredAt: '2026-10-14T00:00:00Z',
                  publishedAt: '2026-10-14T00:00:00Z',
                  correlationId: id,
                  causationId: 'run',
                  causation: null,
                  payload: {
                    kind: 'TEXT',
                    text: source,
                    schedule: null,
                    emits: null,
                    fireAt: null,
                  },
                },
              ],
            },
          };
        throw new Error('Unexpected fixture operation');
      },
    };
    const connection = nativeProviderConnection(checkedTransport(wire), () => {
      invalidations++;
    });
    try {
      if (lost) {
        await assert.rejects(
          NativeProviderBridge.start(connection, scope, host),
        );
        assert.equal(invalidations, 1);
        assert.deepEqual(operations, ['tool.scope.connect', 'relay.publish']);
        assert.equal(
          (await fixture.seen()).some((row) => row.method === 'tools/call'),
          false,
        );
      } else {
        const bridge = await NativeProviderBridge.start(
          connection,
          scope,
          host,
        );
        try {
          await bridge.poll();
          await bridge.poll();
          assert.equal(
            (await fixture.seen()).filter((row) => row.method === 'tools/call')
              .length,
            1,
          );
        } finally {
          await bridge.close();
          await bridge.close();
        }
        assert.equal(
          operations.filter((type) => type === 'tool.scope.disconnect').length,
          1,
        );
        assert.equal(invalidations, 0);
      }
    } finally {
      await host.close();
      await fixture.close();
    }
  });
}

await test('upstream session loss withdraws the shared scope while the owning client remains connected', async () => {
  const fixture = await nativeFixture(),
    closed = new AbortController();
  let withdrawals = 0;
  const connection = nativeProviderConnection(
    checkedTransport({
      async ask(type: string, value?: unknown) {
        const row = object(value);
        if (type === 'tool.scope.connect')
          return {
            code: 'OK' as const,
            payload: {
              ...scope,
              sourceProvider: scope.provider,
              provider: 'scanner',
              account: 'fixture',
            },
          };
        if (type === 'relay.publish')
          return {
            code: 'OK' as const,
            payload: {
              requestId: row.requestId,
              project: row.project,
              topic: row.topic,
              position: '1',
              publishedAt: new Date().toISOString(),
            },
          };
        if (type === 'relay.consume')
          return {
            code: 'OK' as const,
            payload: {
              project: row.project,
              topic: row.topic,
              group: row.group,
              consumerId: row.consumerId,
              status: 'EMPTY',
              batchId: null,
              fence: null,
              through: '0',
              expiresAt: null,
              expiredThrough: null,
              events: [],
            },
          };
        if (type === 'tool.scope.disconnect') {
          withdrawals++;
          return {
            code: 'OK' as const,
            payload: {
              project: scope.project,
              scope: scope.scope,
              disconnected: true,
            },
          };
        }
        throw new Error('Unexpected fixture operation');
      },
    }),
    () => {
      throw new Error('Verified withdrawal must not close the client socket');
    },
  );
  const commands = new NativeHostCommands(
    fixture.config.owner,
    {
      async open() {
        return {
          closedSignal: closed.signal,
          async page() {
            return {
              cursor: null,
              tools: [
                {
                  name: 'echo',
                  description: 'Echo',
                  input: { type: 'object', properties: {} },
                  output: null,
                  metadata: {
                    name: 'echo',
                    inputSchema: { type: 'object', properties: {} },
                  },
                },
              ],
            };
          },
          async call() {
            throw new Error('No tool call expected');
          },
          async close() {},
        };
      },
    },
    connection,
  );
  try {
    await commands.execute({
      action: 'share',
      file: fixture.file,
      consent: true,
      scope,
      pollMs: 2,
      renewalFraction: 0.5,
    });
    closed.abort();
    for (let i = 0; i < 50 && withdrawals === 0; i++) await delay(5);
    assert.equal(withdrawals, 1);
    await commands.close();
    assert.equal(withdrawals, 1);
  } finally {
    await commands.close();
    await fixture.close();
  }
});

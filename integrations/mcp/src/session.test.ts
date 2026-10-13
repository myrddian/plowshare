import assert from 'node:assert/strict';
import { test } from 'node:test';
import { HttpMcpSessions } from './session.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import { McpWire, rawTool } from './wire.test-support.ts';
import type { McpProtocol } from './contracts.ts';

for (const protocol of ['2026-07-28', '2025-11-25'] satisfies McpProtocol[]) {
  await test(`${protocol}: independent HTTP negotiation, nested arguments and isolated credentials/sessions`, async () => {
    const wire = new McpWire(protocol);
    await wire.start();
    const sessions = new HttpMcpSessions();
    const open = (value: string) =>
      sessions.open(
        {
          protocol,
          policy: {
            endpoint: wire.endpoint,
            allowHttp: true,
            allowPrivateNetwork: true,
            timeoutMs: 2000,
          },
          header: 'Authorization',
          value,
        },
        AbortSignal.timeout(2000),
      );
    const first = await open('Bearer account-a');
    const second = await open('Bearer account-b');
    try {
      const one = await first.page(null, AbortSignal.timeout(2000));
      const two = await second.page(null, AbortSignal.timeout(2000));
      const tool = one.tools[0];
      assert.ok(tool);
      assert.equal(two.tools.length, 1);
      for (const mode of ['json', 'sse', 'error'] as const) {
        wire.mode = mode;
        const reply = await first.call(
          tool,
          { values: [{ name: 'one' }] },
          AbortSignal.timeout(2000),
        );
        assert.deepEqual(reply.structured, { values: [{ name: 'one' }] });
        assert.equal(reply.isError, mode === 'error');
      }
      assert.deepEqual(
        wire.seen.find((entry) => entry.method === 'tools/call')?.arguments,
        { values: [{ name: 'one' }] },
      );
      const lists = wire.seen.filter((entry) => entry.method === 'tools/list');
      assert.deepEqual(
        lists.map((entry) => entry.authorization),
        ['Bearer account-a', 'Bearer account-b'],
      );
      if (protocol === '2025-11-25') {
        assert.ok(lists[0]?.session);
        assert.notEqual(lists[0]?.session, lists[1]?.session);
      } else {
        assert.equal(
          wire.seen.some((entry) => entry.method === 'initialize'),
          false,
        );
        assert.equal(lists[0]?.headers['mcp-protocol-version'], protocol);
      }
      assert.deepEqual(wire.faults, []);
    } finally {
      await first.close();
      await second.close();
      await wire.close();
    }
  });
}
for (const mode of [
  'loss',
  'unauthorized',
  'input',
  'invalid',
  'oversize',
  'redirect',
  'hang',
] as const) {
  await test(`HTTP ${mode}: one attempted effect, no auth/transport/input continuation`, async () => {
    const wire = new McpWire('2026-07-28');
    await wire.start();
    const session = await new HttpMcpSessions().open(
      {
        protocol: wire.protocol,
        policy: {
          endpoint: wire.endpoint,
          allowHttp: true,
          allowPrivateNetwork: true,
          timeoutMs: 1000,
        },
        header: 'X-API-Key',
        value: 'fixture-key',
      },
      AbortSignal.timeout(1000),
    );
    try {
      const page = await session.page(null, AbortSignal.timeout(1000));
      const tool = page.tools[0];
      assert.ok(tool);
      wire.mode = mode;
      await assert.rejects(
        session.call(
          tool,
          { values: [{ name: 'one' }] },
          AbortSignal.timeout(150),
        ),
      );
      assert.equal(
        wire.seen.filter((entry) => entry.method === 'tools/call').length,
        1,
      );
      assert.equal(
        wire.seen.some((entry) => entry.method.includes('auth')),
        false,
      );
    } finally {
      await session.close();
      await wire.close();
    }
  });
}
await test('explicit protocol pin refuses incompatible discovery without legacy fallback', async () => {
  const wire = new McpWire('2026-07-28');
  await wire.start();
  wire.advertisedVersion = '2025-11-25';
  try {
    await assert.rejects(
      new HttpMcpSessions().open(
        {
          protocol: wire.protocol,
          policy: {
            endpoint: wire.endpoint,
            allowHttp: true,
            allowPrivateNetwork: true,
            timeoutMs: 1000,
          },
          header: 'Authorization',
          value: 'Bearer fixture',
        },
        AbortSignal.timeout(1000),
      ),
    );
    assert.equal(
      wire.seen.some((entry) => entry.method === 'initialize'),
      false,
    );
  } finally {
    await wire.close();
  }
});

await test('configured client identity and wire budget apply to real HTTP requests without retries', async () => {
  const wire = new McpWire('2025-11-25');
  await wire.start();
  const session = await new HttpMcpSessions({
    name: 'configured-executor',
    version: '2',
  }).open(
    {
      protocol: wire.protocol,
      policy: {
        endpoint: wire.endpoint,
        allowHttp: true,
        allowPrivateNetwork: true,
        timeoutMs: 1000,
        maxWireBytes: 1024,
      },
      header: 'Authorization',
      value: 'Bearer fixture',
    },
    AbortSignal.timeout(1000),
  );
  try {
    assert.deepEqual(
      wire.seen.find((entry) => entry.method === 'initialize')?.clientInfo,
      { name: 'configured-executor', version: '2' },
    );
    const tool = (await session.page(null, AbortSignal.timeout(1000))).tools[0];
    assert.ok(tool);
    await assert.rejects(
      session.call(
        tool,
        { values: [{ name: 'x'.repeat(2000) }] },
        AbortSignal.timeout(1000),
      ),
    );
    assert.equal(
      wire.seen.filter((entry) => entry.method === 'tools/call').length,
      0,
    );
    wire.tools = [
      {
        name: 'oversized',
        description: 'x'.repeat(2000),
        inputSchema: { type: 'object', properties: {} },
      },
    ];
    await assert.rejects(session.page(null, AbortSignal.timeout(1000)));
    assert.equal(
      wire.seen.filter((entry) => entry.method === 'tools/list').length,
      2,
    );
  } finally {
    await session.close();
    await wire.close();
  }
});

for (const protocol of ['2026-07-28', '2025-11-25'] satisfies McpProtocol[]) {
  await test(`${protocol}: configured long upstream names and cursors survive HTTP decoding`, async () => {
    const wire = new McpWire(protocol);
    wire.tools = [
      { ...rawTool, name: 'a'.repeat(200), description: 'd'.repeat(1500) },
    ];
    wire.cursor = 'c'.repeat(1500);
    await wire.start();
    const session = await new HttpMcpSessions().open(
      {
        protocol,
        policy: {
          endpoint: wire.endpoint,
          allowHttp: true,
          allowPrivateNetwork: true,
          timeoutMs: 2000,
        },
        header: 'Authorization',
        value: 'Bearer fixture',
        limits: {
          ...DEFAULT_LIMITS,
          maxToolNameLength: 200,
          maxDescriptionLength: 1500,
          maxCursorLength: 1500,
        },
      },
      AbortSignal.timeout(2000),
    );
    try {
      const page = await session.page(null, AbortSignal.timeout(2000));
      assert.equal(page.tools[0]?.name.length, 200);
      assert.equal(page.cursor?.length, 1500);
      assert.equal(page.tools[0]?.description.length, 1500);
    } finally {
      await session.close();
      await wire.close();
    }
  });
}

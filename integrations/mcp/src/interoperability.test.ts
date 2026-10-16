import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { HostedToolRegistration, ToolCall } from 'plowshare-client-ts';
import { McpProvider } from './provider.ts';
import { HttpMcpSessions } from './session.ts';
import { McpToolClient } from './tool-client.ts';
import type { McpProtocol } from './contracts.ts';
import {
  Credentials,
  registration,
  options,
  invocation,
  id,
  secondId,
} from './provider.test-support.ts';
import { McpWire, inputSchema, rawTool } from './wire.test-support.ts';

/** Real HTTP and official MCP client, with the public credential authority faked explicitly.
 * These are independent protocol fixtures, not deployed Plowshare/user/OAuth acceptance.
 */
function context(wire: McpWire, owner: string, binding = id) {
  const admitted: HostedToolRegistration = {
    ...registration,
    binding: {
      ...registration.binding,
      binding,
      owner,
      credentialSubject: owner,
      route: {
        ...registration.binding.route,
        provider: 'host-' + binding.replaceAll('-', ''),
      },
    },
  };
  const credentials = new Credentials();
  credentials.credential = {
    ...credentials.credential,
    binding,
    subject: owner,
    value: 'Bearer ' + owner,
    connection: {
      ...credentials.credential.connection,
      definition: {
        ...credentials.credential.connection.definition,
        endpoint: wire.endpoint,
      },
    },
  };
  const discover = () =>
    McpProvider.discover(admitted, credentials, new HttpMcpSessions(), {
      ...options,
      protocol: wire.protocol,
      policy: {
        ...options.policy,
        allowHttp: false,
        allowPrivateNetwork: true,
      },
    });
  const call = (name: string): ToolCall => ({
    ...invocation(name),
    account: owner,
    provider: admitted.binding.route.provider,
  });
  return { admitted, credentials, discover, call };
}

for (const protocol of ['2026-07-28', '2025-11-25'] satisfies McpProtocol[]) {
  await test(`${protocol}: hosted users share an endpoint but retain separate paged catalogues, credentials and HTTP sessions`, async (t) => {
    const wire = new McpWire(protocol, true);
    wire.acceptedAuthorization = new Set(['Bearer alice', 'Bearer bob']);
    wire.onList = (authorization, cursor) => ({
      tools: [
        {
          ...rawTool,
          name: authorization.endsWith('alice')
            ? 'alice/' + (cursor ?? 'first')
            : 'bob/' + (cursor ?? 'first'),
        },
      ],
      ...(cursor === null ? { cursor: 'second' } : {}),
    });
    await wire.start();
    t.after(() => wire.close());
    const alice = context(wire, 'alice');
    const bob = context(wire, 'bob', secondId);
    const one = await alice.discover();
    try {
      const two = await bob.discover();
      try {
        assert.equal(one.tools.length, 2);
        assert.equal(two.tools.length, 2);
        assert.notEqual(one.journalIdentity, two.journalIdentity);
        assert.ok(
          one.tools.every((tool) => tool.declaration.name.includes('alice')),
        );
        assert.ok(
          two.tools.every((tool) => tool.declaration.name.includes('bob')),
        );
        const first = one.tools[0];
        const second = two.tools[0];
        assert.ok(first);
        assert.ok(second);
        // Another caller/name and malformed nested values are refused before credential release.
        for (const call of [
          { ...alice.call(first.declaration.name), account: 'bob' },
          alice.call(second.declaration.name),
          {
            ...alice.call(first.declaration.name),
            arguments: { values: [{ name: 42 }] },
          },
        ])
          assert.equal((await first.handler(call)).state, 'REJECTED');
        assert.deepEqual(alice.credentials.invocations, [null]);
        assert.equal(
          wire.seen.filter((entry) => entry.method === 'tools/call').length,
          0,
        );
        assert.equal(
          (await first.handler(alice.call(first.declaration.name))).state,
          'COMPLETED',
        );
        assert.equal(
          (await second.handler(bob.call(second.declaration.name))).state,
          'COMPLETED',
        );
        const calls = wire.seen.filter(
          (entry) => entry.method === 'tools/call',
        );
        assert.deepEqual(
          calls.map((entry) => entry.authorization),
          ['Bearer alice', 'Bearer bob'],
        );
        assert.deepEqual(
          calls.map((entry) => entry.arguments),
          [{ values: [{ name: 'nested' }] }, { values: [{ name: 'nested' }] }],
        );
        assert.deepEqual(
          wire.seen
            .filter((entry) => entry.method === 'tools/list')
            .map((entry) => entry.cursor),
          [null, 'second', null, 'second'],
        );
        if (protocol === '2025-11-25') {
          assert.ok(calls[0]?.session);
          assert.notEqual(calls[0]?.session, calls[1]?.session);
        }
        assert.deepEqual(wire.faults, []);
      } finally {
        await two.close();
      }
    } finally {
      await one.close();
      await wire.close();
    }
  });

  for (const change of ['credential', 'connection', 'revoked'] as const) {
    await test(`${protocol}: ${change} change refuses old hosted execution before HTTP dispatch; recovery needs a new context`, async (t) => {
      const wire = new McpWire(protocol, true);
      await wire.start();
      t.after(() => wire.close());
      const setup = context(wire, 'alice');
      const provider = await setup.discover();
      try {
        const tool = provider.tools[0];
        assert.ok(tool);
        if (change === 'revoked') setup.credentials.denied = true;
        else if (change === 'credential')
          setup.credentials.credential = {
            ...setup.credentials.credential,
            credentialGeneration: id,
            value: 'Bearer rotated',
          };
        else
          setup.credentials.credential = {
            ...setup.credentials.credential,
            connection: {
              ...setup.credentials.credential.connection,
              revision: id,
            },
          };
        assert.equal(
          (await tool.handler(setup.call(tool.declaration.name))).state,
          'REJECTED',
        );
        assert.equal(
          wire.seen.filter((entry) => entry.method === 'tools/call').length,
          0,
        );
        if (change !== 'revoked') {
          await assert.rejects(provider.refresh());
          const replacement = await setup.discover();
          try {
            const fresh = replacement.tools[0];
            assert.ok(fresh);
            assert.notEqual(fresh.declaration.name, tool.declaration.name);
            assert.equal(
              (await fresh.handler(setup.call(fresh.declaration.name))).state,
              'COMPLETED',
            );
            assert.equal(
              wire.seen.filter((entry) => entry.method === 'tools/call').length,
              1,
            );
          } finally {
            await replacement.close();
          }
        }
      } finally {
        await provider.close();
        await wire.close();
      }
    });
  }

  await test(`${protocol}: successful paged refresh does not admit a changed contract under the old name`, async (t) => {
    const wire = new McpWire(protocol, true);
    await wire.start();
    t.after(() => wire.close());
    const setup = context(wire, 'alice');
    const provider = await setup.discover();
    try {
      const tool = provider.tools[0];
      assert.ok(tool);
      wire.tools = [{ ...rawTool, outputSchema: inputSchema }];
      await assert.rejects(provider.refresh(), /changed/);
      assert.equal(
        (await tool.handler(setup.call(tool.declaration.name))).state,
        'REJECTED',
      );
      assert.equal(
        wire.seen.filter((entry) => entry.method === 'tools/call').length,
        0,
      );
      const replacement = await setup.discover();
      try {
        assert.notEqual(
          replacement.tools[0]?.declaration.name,
          tool.declaration.name,
        );
      } finally {
        await replacement.close();
      }
    } finally {
      await provider.close();
      await wire.close();
    }
  });

  for (const mode of [
    'unauthorized',
    'schema',
    'malformed',
    'input',
  ] as const) {
    await test(`${protocol}: ${mode} after hosted dispatch retains UNKNOWN and cannot trigger authentication/continuation replay`, async (t) => {
      const wire = new McpWire(protocol, true);
      wire.tools = [{ ...rawTool, outputSchema: inputSchema }];
      await wire.start();
      t.after(() => wire.close());
      const setup = context(wire, 'alice');
      const provider = await setup.discover();
      try {
        const tool = provider.tools[0];
        assert.ok(tool);
        wire.mode = mode;
        assert.equal(
          (await tool.handler(setup.call(tool.declaration.name))).state,
          'UNKNOWN',
        );
        wire.mode = 'json';
        assert.equal(
          (await tool.handler(setup.call(tool.declaration.name))).state,
          'REJECTED',
        );
        assert.deepEqual(setup.credentials.invocations, [null, id]);
        assert.equal(
          wire.seen.filter((entry) => entry.method === 'tools/call').length,
          1,
        );
        assert.ok(
          wire.seen.every((entry) => entry.authorization === 'Bearer alice'),
        );
      } finally {
        await provider.close();
        await wire.close();
      }
    });
  }

  await test(`${protocol}: missing tools capability and HTTP authentication refusal fail before tools discovery or effect`, async (t) => {
    const wire = new McpWire(protocol, true);
    await wire.start();
    t.after(() => wire.close());
    try {
      wire.toolsSupported = false;
      await assert.rejects(context(wire, 'alice').discover());
      wire.toolsSupported = true;
      wire.acceptedAuthorization = new Set(['Bearer bob']);
      await assert.rejects(context(wire, 'alice').discover());
      assert.equal(
        wire.seen.filter(
          (entry) =>
            entry.method === 'tools/list' || entry.method === 'tools/call',
        ).length,
        0,
      );
      assert.equal(
        wire.seen.filter(
          (entry) =>
            entry.authorization === 'Bearer alice' &&
            (entry.method === 'initialize' ||
              entry.method === 'server/discover'),
        ).length,
        2,
      );
    } finally {
      await wire.close();
    }
  });

  for (const failure of ['duplicate', 'cursor', 'unsupported'] as const) {
    await test(`${protocol}: ${failure} on a later HTTP discovery page admits no partial catalogue`, async (t) => {
      const wire = new McpWire(protocol, true);
      wire.onList = (_authorization, cursor) =>
        cursor === null
          ? { tools: [rawTool], cursor: 'second' }
          : {
              tools: [
                {
                  ...rawTool,
                  ...(failure === 'duplicate' ? {} : { name: 'another' }),
                  ...(failure === 'unsupported'
                    ? { inputSchema: { ...inputSchema, patternProperties: {} } }
                    : {}),
                },
              ],
              ...(failure === 'cursor' ? { cursor: 'second' } : {}),
            };
      await wire.start();
      t.after(() => wire.close());
      try {
        await assert.rejects(context(wire, 'alice').discover());
        assert.equal(
          wire.seen.filter((entry) => entry.method === 'tools/list').length,
          2,
        );
        assert.equal(
          wire.seen.filter((entry) => entry.method === 'tools/call').length,
          0,
        );
      } finally {
        await wire.close();
      }
    });
  }

  await test(`${protocol}: invalid nested input is rejected by the direct boundary without sending an HTTP effect`, async (t) => {
    const wire = new McpWire(protocol, true);
    await wire.start();
    t.after(() => wire.close());
    const session = await new HttpMcpSessions().open(
      {
        protocol,
        policy: {
          endpoint: wire.endpoint,
          allowHttp: false,
          allowPrivateNetwork: true,
          timeoutMs: 2000,
        },
      },
      AbortSignal.timeout(2000),
    );
    const client = await McpToolClient.discover(
      session,
      AbortSignal.timeout(2000),
    );
    try {
      await assert.rejects(
        client.invoke(
          rawTool.name,
          { values: [{ name: 42 }] },
          AbortSignal.timeout(2000),
        ),
      );
      assert.equal(
        wire.seen.filter((entry) => entry.method === 'tools/call').length,
        0,
      );
    } finally {
      await client.close();
      await wire.close();
    }
  });
}

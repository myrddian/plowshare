import { decodeReply } from 'plowshare-client-ts/operations/schema';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { respond } from './protocol.js';
import { call, tools } from './adapter.js';
import type { Backend } from './adapter.js';
import { ws, currentReply } from './fixtures.test-support.js';
import { record } from './values.js';
import type { Data } from './values.js';

const fixture = JSON.parse(
  await readFile(
    new URL(
      '../../test-support/contracts/mcp-compatibility.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as {
  toolsList: { result: { tools: unknown[] } };
  initialize: unknown;
  ping: unknown;
  notification: unknown;
  unknownTool: unknown;
  cases: {
    id: string;
    backendMode: string;
    request: unknown;
    response: Data;
    backendCalls: {
      method: string;
      arguments: Data;
      returns?: unknown;
      throws?: string;
    }[];
  }[];
};

await test('the 35-tool menu preserves the legacy contract except explicit runtime scope', async () => {
  assert.deepEqual(
    tools.filter(
      (tool) => !['agent_run', 'conversation_list'].includes(tool.name),
    ),
    fixture.toolsList.result.tools.filter(
      (tool) =>
        !['agent_run', 'conversation_list'].includes(
          String(record(tool)['name']),
        ),
    ),
  );
  const run = tools.find((tool) => tool.name === 'agent_run');
  assert.ok(run);
  assert.deepEqual(run.inputSchema.required, ['agent', 'task', 'project']);
  assert.match(run.description, /Select the project explicitly/);
  const listing = tools.find((tool) => tool.name === 'conversation_list');
  assert.ok(listing);
  assert.deepEqual(listing.inputSchema.required, ['project']);
  assert.equal(tools.length, 35);
  const unused: Backend = {
    invoke: async () => {
      throw new Error('unexpected backend call');
    },
    runSession: () => null,
    root: async () => {
      throw new Error('unexpected rooting');
    },
  };
  assert.deepEqual(
    await respond(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'initialize',
        params: {},
      }),
      unused,
      'fixture-version',
    ),
    fixture.initialize,
  );
  assert.deepEqual(
    await respond(
      JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
      unused,
    ),
    { ...record(fixture.toolsList), result: { tools } },
  );
  assert.deepEqual(
    await respond(
      JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'ping' }),
      unused,
    ),
    fixture.ping,
  );
  assert.deepEqual(
    await respond(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'tools/call',
        params: { name: 'missing_tool', arguments: {} },
      }),
      unused,
    ),
    fixture.unknownTool,
  );
  assert.equal(
    await respond(
      JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
      unused,
    ),
    undefined,
  );
});
for (const item of fixture.cases)
  await test(item.id, async () => {
    let cursor = 0;
    const backend: Backend = {
      invoke: async (type, payload) => {
        const expected = item.backendCalls[cursor++];
        assert.ok(expected, 'unexpected WS call');
        assert.deepEqual(
          [type, payload],
          ws(expected.method, expected.arguments),
        );
        if (item.backendMode === 'unreachable')
          throw new Error(
            'the WS connection was lost or unreadable; completion is unknown; no mutation was replayed or cancelled; restart the MCP client',
          );
        if (item.backendMode === 'refused')
          throw new Error(
            'the Plowshare server answered NOT_FOUND: fixture missing',
          );
        return currentReply(type, expected.returns);
      },
      runSession: () => null,
      root: async (_project, path) => {
        throw new Error(
          `'path' names ${path}, which is not a directory on this machine. A project cannot be served from a place that is not there. Nothing was rooted.`,
        );
      },
    };
    const actual = await respond(JSON.stringify(item.request), backend);
    const params = record(record(item.request)['params']);
    const args = record(params['arguments']);
    if (
      ['agent_run', 'conversation_list'].includes(String(params['name'])) &&
      args['project'] == null &&
      item.backendCalls.length > 0
    ) {
      assert.equal(record(actual?.result)['isError'], true);
      assert.match(JSON.stringify(actual?.result), /project/);
      assert.equal(cursor, 0);
      return;
    }
    // Two reviewed transport migrations: WS codes retain their names, and
    // connection failures are redacted/unknown rather than native HTTP errors.
    const expected = structuredClone(item.response);
    if (item.backendMode === 'unreachable' || item.backendMode === 'refused') {
      record(expected['result'])['content'] = [
        {
          type: 'text',
          text:
            item.backendMode === 'refused'
              ? 'the Plowshare server answered NOT_FOUND: fixture missing'
              : 'the WS connection was lost or unreadable; completion is unknown; no mutation was replayed or cancelled; restart the MCP client',
        },
      ];
    }
    assert.deepEqual(actual, expected);
    assert.equal(cursor, item.backendCalls.length);
  });

await test('malformed protocol requests survive, notifications are silent and tool errors remain model-visible', async () => {
  const backend: Backend = {
    invoke: async () => {
      throw new Error('unreachable');
    },
    runSession: () => null,
    root: async () => {
      throw new Error('unavailable');
    },
  };
  assert.equal((await respond('not JSON', backend))?.error?.code, -32700);
  assert.equal((await respond('[]', backend))?.error?.code, -32600);
  assert.equal(await respond(' ', backend), undefined);
  assert.equal(
    await respond('{"jsonrpc":"2.0","method":"unknown"}', backend),
    undefined,
  );
  assert.equal(
    (await respond('{"jsonrpc":"2.0","id":"a","method":"unknown"}', backend))
      ?.error?.code,
    -32601,
  );
});

await test('information preserves scoped receipts and refuses human publication and migration before WS dispatch', async () => {
  const calls: unknown[] = [];
  const backend: Backend = {
    invoke: async (type, payload) => {
      calls.push([type, payload]);
      return decodeReply(type, { revision: 'r', resource: 'q', created: true });
    },
    runSession: () => null,
    root: async () => {
      throw new Error('unexpected root');
    },
  };
  const payload = {
    scope: { kind: 'project', project: 'research', includeGlobal: false },
    requestId: 'retained-receipt',
    name: 'report',
    text: 'Report',
    inputs: ['cited-source', 'uncited-source'],
  };
  await call(backend, 'information', { operation: 'record.report', payload });
  assert.deepEqual(calls, [['information.record.report', payload]]);
  for (const operation of ['share', 'finalise', 'delete', 'migration.adopt'])
    await assert.rejects(
      call(backend, 'information', { operation, payload }),
      /unsupported model information operation/,
    );
  assert.equal(calls.length, 1);
});

await test('information facets forwards the requested project scope and filter exactly once', async () => {
  const calls: unknown[] = [];
  const fixtures: unknown = JSON.parse(
    await readFile(
      new URL(
        '../../test-support/contracts/ws-information-fixtures.json',
        import.meta.url,
      ),
      'utf8',
    ),
  );
  const facets = decodeReply(
    'information.facets',
    record(record(fixtures)['information.facets'])['payload'],
  );
  const backend: Backend = {
    invoke: async (type, payload) => {
      calls.push([type, payload]);
      return decodeReply(type, facets);
    },
    runSession: () => null,
    root: async () => {
      throw new Error('unexpected root');
    },
  };
  const payload = {
    scope: { kind: 'project', project: 'research', includeGlobal: false },
    kind: 'source',
    filter: { tags: ['a2a'] },
  };
  assert.equal(
    await call(backend, 'information', { operation: 'facets', payload }),
    JSON.stringify(facets),
  );
  assert.deepEqual(calls, [['information.facets', payload]]);
});

await test('model type reads force published versions and refuse management or draft discovery', async () => {
  const calls: unknown[] = [];
  const backend: Backend = {
    invoke: async (type, payload) => {
      calls.push([type, payload]);
      throw new Error('fixture stop after dispatch');
    },
    runSession: () => null,
    root: async () => {
      throw new Error('unexpected root');
    },
  };
  const scope = { kind: 'personal' };
  const version = '00000000-0000-4000-8000-000000000001';
  for (const operation of ['types.get', 'types.validate']) {
    const payload = {
      scope,
      view: 'content-types-v1',
      version,
      publishedOnly: false,
      ...(operation === 'types.validate' ? { metadata: {} } : {}),
    };
    await assert.rejects(
      call(backend, 'information', { operation, payload }),
      /fixture stop/,
    );
    assert.deepEqual(calls.at(-1), [
      `information.${operation}`,
      { ...payload, publishedOnly: true },
    ]);
  }
  await assert.rejects(
    call(backend, 'information', {
      operation: 'types.list',
      payload: { scope, view: 'content-types-v1', drafts: true },
    }),
    /published types only/,
  );
  for (const operation of [
    'types.version.create',
    'types.activate',
    'types.deprecate',
  ])
    await assert.rejects(
      call(backend, 'information', { operation, payload: { scope } }),
      /unsupported model information operation/,
    );
  assert.equal(calls.length, 2);
});

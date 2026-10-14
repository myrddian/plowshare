import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { run } from './run.js';

await test('native foreground CLI serves nested NDJSON directly, then EOF withdraws availability without a Plowshare request', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-cli-native-'));
  const calls: string[] = [];
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on('data', (value: Buffer) => chunks.push(value));
    request.on('end', () => {
      const raw: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      assert.ok(
        raw !== null &&
          typeof raw === 'object' &&
          'method' in raw &&
          typeof raw.method === 'string' &&
          'id' in raw,
      );
      calls.push(raw.method);
      const result =
        raw.method === 'server/discover'
          ? {
              resultType: 'complete',
              supportedVersions: ['2026-07-28'],
              capabilities: { tools: {} },
              ttlMs: 0,
              cacheScope: 'public',
              _meta: {
                'io.modelcontextprotocol/serverInfo': {
                  name: 'fixture',
                  version: '1',
                },
              },
            }
          : raw.method === 'tools/list'
            ? {
                resultType: 'complete',
                ttlMs: 0,
                cacheScope: 'public',
                tools: [
                  {
                    name: 'echo',
                    inputSchema: {
                      type: 'object',
                      properties: {
                        value: {
                          type: ['array', 'null'],
                          items: { type: 'integer' },
                        },
                      },
                      required: ['value'],
                      additionalProperties: false,
                    },
                  },
                ],
              }
            : {
                resultType: 'complete',
                content: [{ type: 'text', text: 'observed' }],
              };
      response
        .writeHead(200, { 'content-type': 'application/json' })
        .end(JSON.stringify({ jsonrpc: '2.0', id: raw.id, result }));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address !== null && typeof address !== 'string');
  const file = join(directory, 'profile.json');
  await writeFile(
    file,
    JSON.stringify({
      owner: {
        origin: 'https://unreachable.example.invalid',
        account: 'fixture',
      },
      instance: 'cli',
      profile: 'echo',
      project: 'fixture',
      prefix: 'local_',
      protocol: '2026-07-28',
      journalDirectory: join(directory, 'journals'),
      timeoutMs: 2000,
      timeoutSeconds: 2,
      shutdownGraceMs: 30,
      transport: {
        kind: 'http',
        endpoint: `http://127.0.0.1:${address.port}/mcp`,
        allowHttp: true,
        allowPrivateNetwork: true,
      },
    }),
    { mode: 0o600 },
  );
  const output: string[] = [];
  try {
    const exit = await run(['mcp', 'local', 'start', file], {
      env: {},
      stdout: (line) => output.push(line),
      stderr: (line) => {
        throw new Error(line);
      },
      stdin: async () => '',
      nativeInput: async function* () {
        const raw: unknown = JSON.parse(output[0] ?? '');
        assert.ok(
          raw !== null &&
            typeof raw === 'object' &&
            'host' in raw &&
            raw.host !== null &&
            typeof raw.host === 'object' &&
            'tools' in raw.host &&
            Array.isArray(raw.host.tools),
        );
        const tool: unknown = raw.host.tools[0];
        assert.ok(
          tool !== null &&
            typeof tool === 'object' &&
            'name' in tool &&
            typeof tool.name === 'string',
        );
        const line = JSON.stringify({
          invocationId: randomUUID(),
          tool: tool.name,
          arguments: { value: [1, 2] },
          deadline: new Date(Date.now() + 2000).toISOString(),
        });
        yield line;
        yield line;
      },
    });
    assert.equal(exit, 0);
    assert.equal(calls.filter((method) => method === 'tools/call').length, 1);
    assert.equal(output.length, 3);
    assert.ok(output[1]?.includes('COMPLETED'));
    assert.equal(output[1], output[2]);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

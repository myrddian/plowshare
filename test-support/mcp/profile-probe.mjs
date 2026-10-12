// Manual design probe: independent wire fixtures, no Plowshare server or credentials.
// Install the exact SDK pin in a separate directory; pass that directory as --modules.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const modern = '2026-07-28';
const legacy = '2025-11-25';
const schema = {
  type: 'object',
  properties: { values: { type: 'array', items: { type: 'string' } } },
  required: ['values'],
  additionalProperties: false,
};

function result(message, version) {
  switch (message.method) {
    case 'server/discover':
      if (version === legacy)
        return { error: { code: -32601, message: 'Legacy fixture' } };
      return {
        result: {
          resultType: 'complete',
          supportedVersions: [modern],
          capabilities: { tools: {} },
          ttlMs: 0,
          cacheScope: 'private',
          _meta: {
            'io.modelcontextprotocol/serverInfo': {
              name: 'fixture',
              version: '1',
            },
          },
        },
      };
    case 'initialize':
      assert.equal(
        version,
        legacy,
        'Modern fixture must never receive initialize',
      );
      return {
        result: {
          protocolVersion: legacy,
          capabilities: { tools: {} },
          serverInfo: { name: 'fixture', version: '1' },
        },
      };
    case 'tools/list':
      return {
        result: {
          ...(version === modern
            ? { resultType: 'complete', ttlMs: 0, cacheScope: 'private' }
            : {}),
          tools: [
            {
              name: 'echo',
              description: 'Independent nested echo fixture',
              inputSchema: schema,
            },
          ],
        },
      };
    case 'tools/call':
      assert.equal(message.params.name, 'echo');
      assert.deepEqual(message.params.arguments, { values: ['one', 'two'] });
      return {
        result: {
          ...(version === modern ? { resultType: 'complete' } : {}),
          content: [
            { type: 'text', text: JSON.stringify(message.params.arguments) },
          ],
        },
      };
    case 'ping':
      return { result: {} };
    default:
      throw new Error(`Unexpected fixture method: ${message.method}`);
  }
}

if (process.argv[2] === '--stdio-fixture') {
  const version = process.argv[3];
  assert.ok([modern, legacy].includes(version));
  const lines = createInterface({ input: process.stdin });
  for await (const line of lines) {
    const message = JSON.parse(line);
    if (message.id === undefined) continue;
    process.stdout.write(
      JSON.stringify({
        jsonrpc: '2.0',
        id: message.id,
        ...result(message, version),
      }) + '\n',
    );
  }
} else {
  assert.equal(
    process.argv[2],
    '--modules',
    'Usage: node profile-probe.mjs --modules SDK_INSTALL_DIRECTORY',
  );
  assert.ok(process.argv[3], 'SDK install directory is required');
  const requireSdk = createRequire(resolve(process.argv[3], 'package.json'));
  const { Client, StreamableHTTPClientTransport } = requireSdk(
    '@modelcontextprotocol/client',
  );
  const { StdioClientTransport } = requireSdk(
    '@modelcontextprotocol/client/stdio',
  );
  const version = JSON.parse(
    readFileSync(
      resolve(
        process.argv[3],
        'node_modules/@modelcontextprotocol/client/package.json',
      ),
      'utf8',
    ),
  );
  assert.equal(version.version, '2.3.1');

  function client(era) {
    return new Client(
      { name: 'plowshare-design-probe', version: '1' },
      {
        capabilities: {},
        versionNegotiation: {
          mode: era === modern ? { pin: modern } : 'legacy',
          probe: { timeoutMs: 2000, maxRetries: 0 },
        },
        inputRequired: { autoFulfill: false },
        listMaxPages: 4,
      },
    );
  }

  async function exercise(target) {
    const tools = await target.listTools();
    assert.equal(tools.tools.length, 1);
    const response = await target.callTool(
      { name: 'echo', arguments: { values: ['one', 'two'] } },
      { timeout: 2000 },
    );
    assert.deepEqual(response.content, [
      { type: 'text', text: '{"values":["one","two"]}' },
    ]);
  }

  async function httpProbe(era, mode = 'complete') {
    const messages = [];
    const server = createServer((request, response) => {
      if (request.method !== 'POST') {
        response.writeHead(405).end();
        return;
      }
      let body = '';
      request.setEncoding('utf8');
      request.on('data', (chunk) => {
        body += chunk;
      });
      request.on('end', () => {
        try {
          const message = JSON.parse(body);
          messages.push(message.method);
          if (message.id === undefined) {
            response.writeHead(202).end();
            return;
          }
          if (era === modern) {
            assert.equal(request.headers['mcp-protocol-version'], modern);
            assert.equal(request.headers['mcp-method'], message.method);
            assert.equal(
              message.params._meta['io.modelcontextprotocol/protocolVersion'],
              modern,
            );
          }
          if (message.method === 'tools/call' && mode === 'lost') {
            request.socket.destroy();
            return;
          }
          if (message.method === 'tools/call' && mode === 'unauthorized') {
            response
              .writeHead(401, {
                'WWW-Authenticate': 'Bearer error="invalid_token"',
              })
              .end();
            return;
          }
          const wire =
            message.method === 'tools/call' && mode === 'input-required'
              ? {
                  result: {
                    resultType: 'input_required',
                    requestState: 'opaque',
                  },
                }
              : result(message, era);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(
            JSON.stringify({ jsonrpc: '2.0', id: message.id, ...wire }),
          );
        } catch (error) {
          response.writeHead(500).end(String(error));
        }
      });
    });
    await new Promise((resolveReady) =>
      server.listen(0, '127.0.0.1', resolveReady),
    );
    const address = server.address();
    assert.ok(address && typeof address !== 'string');
    const target = client(era);
    try {
      const transport = new StreamableHTTPClientTransport(
        new URL(`http://127.0.0.1:${address.port}/mcp`),
        {
          requestInit: { redirect: 'error' },
          reconnectionOptions: {
            initialReconnectionDelay: 1,
            maxReconnectionDelay: 1,
            reconnectionDelayGrowFactor: 1,
            maxRetries: 0,
          },
          onInsufficientScope: 'throw',
          authProvider: { token: async () => 'fixture-token' },
        },
      );
      await target.connect(transport, { timeout: 2000 });
      if (mode === 'complete') await exercise(target);
      else {
        await target.listTools();
        await assert.rejects(
          target.callTool(
            { name: 'echo', arguments: { values: ['one', 'two'] } },
            { timeout: 2000 },
          ),
          (error) => {
            assert.notEqual(
              error.code,
              'INVALID_RESULT',
              'Fixture must reach the intended refusal, not fail wire validation',
            );
            if (mode === 'input-required')
              assert.equal(error.code, 'UNSUPPORTED_RESULT_TYPE');
            return true;
          },
        );
      }
      assert.equal(
        messages.filter((method) => method === 'tools/call').length,
        1,
        'Tool call must never be retried',
      );
      assert.equal(messages.includes('initialize'), era === legacy);
      console.log(`PASS HTTP ${era}: ${mode}, one tools/call`);
    } finally {
      await target.close();
      server.closeAllConnections();
      await new Promise((resolveClosed, reject) =>
        server.close((error) => (error ? reject(error) : resolveClosed())),
      );
    }
  }

  for (const era of [modern, legacy]) {
    await httpProbe(era);
    const target = client(era);
    try {
      await target.connect(
        new StdioClientTransport({
          command: process.execPath,
          args: [fileURLToPath(import.meta.url), '--stdio-fixture', era],
          env: {},
          stderr: 'pipe',
          maxBufferSize: 65536,
        }),
        { timeout: 2000 },
      );
      await exercise(target);
      console.log(`PASS STDIO ${era}: nested echo`);
    } finally {
      await target.close();
    }
  }
  for (const mode of ['lost', 'unauthorized', 'input-required'])
    await httpProbe(modern, mode);
}

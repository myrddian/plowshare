// Loopback-only synthetic MCP and model services for the isolated acceptance instance.
// This never forwards requests to another model, MCP service or credential store.
import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { McpWire } from '../../integrations/mcp/build/mcp/wire.test-support.js';
const state = process.argv[2];
if (!state) throw new Error('Supply the private instance directory');
const settings = JSON.parse(await readFile(state + '/instance.json', 'utf8'));
const wire = new McpWire('2026-07-28', true);
wire.acceptedAuthorization = new Set(
  Object.values(settings.upstream).map((value) => 'Bearer ' + value),
);
wire.onList = (authorization) => {
  const owner = Object.entries(settings.upstream).find(
    ([, value]) => authorization === 'Bearer ' + value,
  )?.[0];
  if (!owner) throw new Error('Unknown fixture identity');
  return {
    tools: [
      {
        name: owner + '/echo',
        description: 'Echo a nested fixture value',
        inputSchema: {
          type: 'object',
          properties: {
            values: {
              type: 'array',
              items: {
                type: 'object',
                properties: { name: { type: 'string', minLength: 1 } },
                required: ['name'],
                additionalProperties: false,
              },
            },
          },
          required: ['values'],
          additionalProperties: false,
        },
      },
    ],
  };
};
await wire.start(settings.mcpPort ?? 0);
const server = createServer((request, response) => {
  respond(request, response).catch(() => {
    response.destroy();
  });
});
async function respond(request, response) {
  const url = new URL(request.url, 'http://fixture.invalid');
  if (url.pathname === '/observations' && request.method === 'GET') {
    const counts = Object.fromEntries(
      Object.entries(settings.upstream).map(([owner, value]) => [
        owner,
        wire.seen.filter(
          (entry) =>
            entry.method === 'tools/call' &&
            entry.authorization === 'Bearer ' + value,
        ).length,
      ]),
    );
    response
      .writeHead(200, { 'content-type': 'application/json' })
      .end(JSON.stringify({ counts }));
    return;
  }
  let body = '';
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 2 * 1024 * 1024)
      throw new Error('Fixture input exceeded budget');
  }
  const data = body ? JSON.parse(body) : {};
  let answer;
  if (url.pathname === '/v1/models')
    answer = {
      object: 'list',
      data: ['mcp-test-chat', 'mcp-test-embedding'].map((id) => ({
        id,
        object: 'model',
      })),
    };
  else if (url.pathname === '/v1/embeddings')
    answer = {
      object: 'list',
      model: data.model,
      data: (Array.isArray(data.input) ? data.input : [data.input]).map(
        (_, index) => ({
          index,
          object: 'embedding',
          embedding: Array(768).fill(0.01),
        }),
      ),
      usage: { prompt_tokens: 1, total_tokens: 1 },
    };
  else if (url.pathname === '/v1/chat/completions') {
    const tool = data.tools?.find((entry) =>
      entry.function?.name?.startsWith('mcp_'),
    );
    const received = data.messages?.at(-1)?.role === 'tool';
    const call = tool && !received;
    const message = call
      ? {
          role: 'assistant',
          content: null,
          tool_calls: [
            {
              id: 'fixture-call',
              type: 'function',
              function: {
                name: tool.function.name,
                arguments: JSON.stringify({ values: [{ name: 'acceptance' }] }),
              },
            },
          ],
        }
      : {
          role: 'assistant',
          content: received
            ? 'MCP fixture response received.'
            : 'No MCP tool admitted.',
        };
    answer = {
      id: 'mcp-acceptance-fixture',
      object: 'chat.completion',
      model: data.model,
      choices: [
        { index: 0, message, finish_reason: call ? 'tool_calls' : 'stop' },
      ],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    };
    if (data.stream) {
      const delta = {
        ...message,
        ...(call
          ? {
              tool_calls: message.tool_calls.map((entry, index) => ({
                index,
                ...entry,
              })),
            }
          : {}),
      };
      const chunk = {
        ...answer,
        object: 'chat.completion.chunk',
        choices: [
          { index: 0, delta, finish_reason: call ? 'tool_calls' : 'stop' },
        ],
      };
      response
        .writeHead(200, { 'content-type': 'text/event-stream' })
        .end('data: ' + JSON.stringify(chunk) + '\n\ndata: [DONE]\n\n');
      return;
    }
  } else {
    response.writeHead(404).end();
    return;
  }
  response
    .writeHead(200, { 'content-type': 'application/json' })
    .end(JSON.stringify(answer));
}
await new Promise((resolve) =>
  server.listen(settings.modelPort ?? 0, '127.0.0.1', resolve),
);
const address = server.address();
await writeFile(
  state + '/fixture.json',
  JSON.stringify({
    endpoint: wire.endpoint,
    model: `http://127.0.0.1:${address.port}/v1`,
    observations: `http://127.0.0.1:${address.port}/observations`,
  }),
  { mode: 0o600 },
);
const stop = async () => {
  server.closeAllConnections();
  server.close();
  await wire.close();
};
process.once('SIGTERM', () => {
  stop().catch(() => {
    process.exitCode = 1;
  });
});
process.once('SIGINT', () => {
  stop().catch(() => {
    process.exitCode = 1;
  });
});

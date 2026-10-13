import assert from 'node:assert/strict';
import { test } from 'node:test';
import { declaration, decodeMcpTool, decodeMcpReply, result } from './codec.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import { decodeConfig } from './config.ts';
import { allowedAddress, endpoint } from './http.ts';
import { inputSchema, rawTool } from './wire.test-support.ts';

await test('preserves nested contracts and changes offered names for every semantic coordinate', () => {
  const tool = decodeMcpTool(rawTool);
  const make = (
    context = 'account-a',
    protocol: '2026-07-28' | '2025-11-25' = '2026-07-28',
  ) => declaration(tool, 'mcp_', protocol, context, 30);
  assert.deepEqual(make().inputSchema, inputSchema);
  assert.match(make().name, /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/);
  assert.ok(make().name.length <= 64);
  assert.notEqual(make().name, make('account-b').name);
  assert.notEqual(make().name, make('account-a', '2025-11-25').name);
  assert.notEqual(
    make().name,
    declaration(
      decodeMcpTool({ ...rawTool, annotations: { readOnlyHint: true } }),
      'mcp_',
      '2026-07-28',
      'account-a',
      30,
    ).name,
  );
  assert.equal(
    make().name,
    declaration(
      decodeMcpTool({
        inputSchema,
        description: rawTool.description,
        name: rawTool.name,
      }),
      'mcp_',
      '2026-07-28',
      'account-a',
      30,
    ).name,
  );
  for (const schema of [
    { ...inputSchema, patternProperties: {} },
    {
      ...inputSchema,
      properties: {
        token: { type: 'string', 'x-mcp-header': 'Authorization' },
      },
    },
    { type: 'object', properties: { x: { type: 'string', pattern: '.*' } } },
  ])
    assert.throws(() => decodeMcpTool({ ...rawTool, inputSchema: schema }));
  assert.throws(() =>
    decodeMcpTool({ ...rawTool, execution: { taskSupport: 'required' } }),
  );
});
await test('validates structured output and separates received upstream errors from protocol failure', () => {
  const tool = decodeMcpTool({ ...rawTool, outputSchema: inputSchema });
  const reply = decodeMcpReply({
    content: [{ type: 'text', text: 'error received' }],
    isError: true,
    structuredContent: { values: [{ name: 'nested' }] },
  });
  assert.equal(result(tool, reply).state, 'COMPLETED');
  assert.throws(() =>
    result(
      tool,
      decodeMcpReply({ content: [], structuredContent: { values: [{}] } }),
    ),
  );
  assert.throws(() => result(tool, decodeMcpReply({ content: [] })));
  assert.throws(() =>
    decodeMcpReply({ content: [{ type: 'resource', resource: {} }] }),
  );
  assert.throws(() =>
    result(
      decodeMcpTool(rawTool),
      decodeMcpReply({ content: [{ type: 'text', text: 'x'.repeat(16384) }] }),
    ),
  );
});
await test('configuration and network policy have no implicit origin, subject, transport or private-network defaults', () => {
  assert.throws(() => decodeConfig({}));
  assert.throws(() =>
    decodeConfig({
      origin: 'https://example.invalid',
      tokenEnvironment: 'TOKEN',
      journalDirectory: '/tmp/fixture',
      pollMs: 50,
      workers: [
        {
          binding: '11111111-1111-1111-1111-111111111111',
          generation: 1,
          registrationRequestId: '22222222-2222-2222-2222-222222222222',
          protocol: 'stdio',
        },
      ],
    }),
  );
  assert.throws(() => endpoint('http://example.invalid/mcp', false));
  assert.throws(() => endpoint('https://secret@example.invalid/mcp', false));
  assert.throws(() =>
    endpoint('https://example.invalid/mcp?token=secret', false),
  );
  for (const value of [
    '127.0.0.1',
    '10.0.0.1',
    '169.254.169.254',
    '::1',
    '::ffff:127.0.0.1',
    'fc00::1',
    '2001:db8::1',
  ])
    assert.equal(allowedAddress(value, false), false);
  assert.equal(allowedAddress('8.8.8.8', false), true);
  assert.equal(allowedAddress('127.0.0.1', true), true);
});

await test('configured content and UTF-8 result budgets are enforced without truncating output', () => {
  assert.throws(() =>
    decodeMcpReply(
      {
        content: [
          { type: 'text', text: '' },
          { type: 'text', text: '' },
        ],
      },
      { ...DEFAULT_LIMITS, maxContentBlocks: 1 },
    ),
  );
  const tool = decodeMcpTool(rawTool);
  const reply = decodeMcpReply({
    content: [{ type: 'text', text: 'é'.repeat(20) }],
  });
  const encoded = result(tool, reply).text;
  assert.ok(encoded);
  assert.throws(() => result(tool, reply, encoded.length));
  assert.equal(
    result(tool, reply, Buffer.byteLength(encoded)).state,
    'COMPLETED',
  );
});

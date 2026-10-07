import { record, field, json } from './json.test-support.js';
import { httpHandler, wireText } from './http.test-support.js';
import { displayText } from 'plowshare-client-ts/binding/values';
import { Credentials } from 'plowshare-client-node/credentials';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import {
  mkdtemp,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { WebSocketServer } from 'ws';
import type { WebSocket } from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { FileRequest, FileReply } from 'plowshare-client-ts/binding/files';
import { ws } from './fixtures.test-support.js';
import type { Data } from './values.js';

interface Frame {
  id: string;
  type: string;
  protocol_version: string;
  payload: Data;
}
type Script = (frame: Frame) => Outcome | 'drop' | 'hang';
const main =
  process.env['PLOWSHARE_MCP_TEST_MAIN'] ??
  fileURLToPath(new URL('../build/main.js', import.meta.url));
const secrets = [
  'fixture-password-secret',
  'access-secret',
  'refresh-secret',
  'ticket-secret',
];
function noSecrets(text: string): void {
  for (const secret of secrets) assert.ok(!text.includes(secret), secret);
}
const env = {
  PLOWSHARE_HANDLE: 'fixture',
  PLOWSHARE_PASSWORD: secrets[0]!,
  PLOWSHARE_PROJECT: 'must-not-inherit',
};
const retrieval = (
  JSON.parse(
    await readFile(
      new URL(
        '../../test-support/contracts/ws-retrieval-fixtures.json',
        import.meta.url,
      ),
      'utf8',
    ),
  ) as { replies: Record<string, unknown> }
).replies;

async function fixture(
  script: Script,
  files: 'ready' | 'refuse' | 'silent' = 'ready',
  flagged = false,
) {
  const paths: string[] = [],
    frames: Frame[] = [],
    claims: URL[] = [],
    fileSockets: WebSocket[] = [];
  let refresh = 'initial-refresh-secret',
    generation = 0;
  const server = createServer(
    httpHandler(async (req, res) => {
      paths.push(req.url!);
      if (req.url === '/v1/auth/login') {
        const chunks: Buffer[] = [];
        for await (const chunk of req)
          chunks.push(Buffer.from(chunk as Uint8Array));
        assert.deepEqual(JSON.parse(Buffer.concat(chunks).toString()), {
          handle: 'fixture',
          password: secrets[0],
        });
        assert.equal(req.headers['x-plowshare-token-delivery'], 'body');
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(
          JSON.stringify({
            access: 'initial-access-secret',
            refresh: flagged ? null : refresh,
            mustChangePassword: flagged,
          }),
        );
      } else if (req.url === '/v1/auth/refresh') {
        assert.equal(req.headers.cookie, 'ps_refresh=' + refresh);
        refresh = `refresh-secret-${++generation}`;
        res
          .writeHead(204, {
            'Set-Cookie': [
              `ps_access=access-secret-${generation}; Path=/`,
              `ps_refresh=${refresh}; Path=/`,
            ],
          })
          .end();
      } else if (req.url === '/v1/auth/ticket') {
        assert.equal(
          req.headers.authorization,
          `Bearer access-secret-${generation}`,
        );
        res
          .writeHead(200, { 'Content-Type': 'application/json' })
          .end(JSON.stringify({ ticket: 'ticket-secret' }));
      } else res.writeHead(404).end();
    }),
  );
  const sockets = new WebSocketServer({ server });
  let eventSession = '';
  sockets.on('connection', (socket, req) => {
    const url = new URL(req.url!, 'http://localhost');
    assert.equal(url.searchParams.get('ticket'), 'ticket-secret');
    if (url.pathname === '/v1/files') {
      assert.equal(url.searchParams.get('session'), eventSession);
      claims.push(url);
      fileSockets.push(socket);
      if (files === 'ready')
        socket.send(
          JSON.stringify({
            ready: true,
            project: url.searchParams.get('project'),
          }),
        );
      if (files === 'refuse') socket.close(1003, 'claim refused');
      return;
    }
    assert.equal(url.pathname, '/v1/events');
    eventSession = url.searchParams.get('session')!;
    assert.ok(eventSession);
    assert.equal(url.searchParams.has('root'), false);
    socket.on('message', (bytes) => {
      const frame = JSON.parse(wireText(bytes)) as Frame;
      assert.equal(frame.protocol_version, 'plowshare-v1');
      frames.push(frame);
      const outcome = script(frame);
      if (outcome === 'drop') {
        socket.close();
        return;
      }
      if (outcome === 'hang') return;
      socket.send(JSON.stringify({ job: 'foreign-job', kind: 'ended' }));
      socket.send(
        JSON.stringify({
          id: frame.id,
          type: frame.type,
          protocol_version: frame.protocol_version,
          payload: outcome,
        }),
      );
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    base: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
    paths,
    frames,
    claims,
    fileSockets,
    session: () => eventSession,
    async file(request: FileRequest): Promise<FileReply> {
      const socket = fileSockets.at(-1)!;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error('file response timed out')),
          2000,
        );
        socket.once('message', (bytes) => {
          clearTimeout(timer);
          resolve(JSON.parse(wireText(bytes)) as FileReply);
        });
        socket.send(JSON.stringify(request));
      });
    },
    async close() {
      for (const socket of sockets.clients) socket.terminate();
      await new Promise<void>((resolve) => sockets.close(() => resolve()));
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      assert.deepEqual(
        paths.filter(
          (path) =>
            !['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
              path,
            ),
        ),
        [],
        'operational HTTP fallback',
      );
    },
  };
}
async function sdk(
  base: string,
  timeout = 10000,
  configured: Record<string, string> = env,
) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [main, '--url', base, '--timeout-ms', String(timeout)],
    env: configured,
    stderr: 'pipe',
  });
  let stderr = '';
  transport.stderr?.on('data', (bytes) => {
    stderr += String(bytes);
  });
  const client = new Client({ name: 'independent-sdk-fixture', version: '1' });
  try {
    await client.connect(transport);
  } catch (failure) {
    await transport.close();
    throw failure;
  }
  return {
    client,
    transport,
    stderr: () => stderr,
    call: (name: string, args: Data = {}) =>
      client.callTool({ name, arguments: args }),
  };
}

await test(
  'external SDK reads nonempty retrieval evidence and rejects damaged replies through shared WS validation',
  { timeout: 15000 },
  async () => {
    let expected = '',
      returned: unknown;
    const fake = await fixture((frame) => {
      assert.equal(frame.type, expected);
      return { code: 'OK', payload: returned };
    });
    const peer = await sdk(fake.base);
    const cases: [string, string, Data][] = [
      ['memory_index', 'memory.index', {}],
      ['memory_read', 'memory.read', { ids: ['memory_fixture'] }],
      ['memory_recall', 'memory.recall', { question: 'x' }],
      [
        'memory_write',
        'memory.write',
        { summary: 'x', scope: 'x', body: 'x', formed_by: 'person' },
      ],
      ['memory_navigate', 'memory.navigate', { question: 'x' }],
      ['memory_proposals', 'proposal.list', {}],
      [
        'memory_resolve',
        'proposal.resolve',
        { proposal_id: 'p', decision: 'accept', by: 'person' },
      ],
      ['conversation_search', 'conversation.search', { q: 'x' }],
      ['document_search', 'document.search', { question: 'x' }],
      ['document_citations', 'document.citations', {}],
      ['document_retrieve', 'document.retrieve', { question: 'x' }],
      ['document_list', 'document.list', {}],
      ['document_rank', 'document.rank', { question: 'x' }],
      ['document_outline', 'document.detail', { document: 'd' }],
      ['search', 'web.search', { query: 'x' }],
      ['fetch', 'web.fetch', { url: 'https://example.invalid' }],
    ];
    try {
      for (const [name, type, args] of cases) {
        expected = type;
        returned = retrieval[type];
        const result = await peer.call(name, args);
        assert.equal(result.isError, undefined, name + JSON.stringify(result));
        noSecrets(JSON.stringify(result) + peer.stderr());
        if (type === 'document.citations') {
          const content = (result.content as { text: string }[])[0]!.text;
          assert.equal(content.match(/> whole paragraph/g)?.length, 1);
          assert.match(content, /edited or removed that paragraph/);
          assert.match(content, /document is no longer in the corpus/);
        }
      }
      const damaged: [number, unknown][] = [
        [0, [{ id: 'm' }]],
        [2, { unsearchable: 0 }],
        [3, { kind: 'new', memoryId: 'm' }],
        [6, { promotedId: 'm', demoted: [] }],
        [8, { hits: [], searchable: '0', unsearchable: 0 }],
        [
          9,
          {
            scope: 'document',
            limit: 10,
            citations: [{ standing: 'resolves', paragraphText: null }],
          },
        ],
      ];
      for (const [index, payload] of damaged) {
        const [name, type, args] = cases[index]!;
        expected = type;
        returned = payload;
        const result = await peer.call(name, args);
        assert.equal(result.isError, true);
        assert.match(
          JSON.stringify(result),
          /unreadable operation result; completion is unknown/,
        );
        noSecrets(JSON.stringify(result) + peer.stderr());
      }
      assert.equal(fake.frames.length, cases.length + damaged.length);
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
    } finally {
      await peer.client.close();
      await fake.close();
    }
  },
);

await test(
  'external MCP callers receive uncertainty for malformed or foreign job replies',
  { timeout: 15000 },
  async () => {
    for (const payload of [
      { state: 'DONE', outcome: { ending: 'ANSWERED', answered: true } },
      { id: 'job_1', state: 'DONE', outcome: { ending: 'ANSWERED' } },
      {
        id: 'foreign-job',
        state: 'DONE',
        outcome: { ending: 'ANSWERED', answered: true },
      },
      {
        id: 'job_1',
        state: 'DONE',
        outcome: { ending: 'ANSWERED', answered: false },
      },
    ]) {
      const fake = await fixture(() => ({ code: 'OK', payload }));
      const peer = await sdk(fake.base);
      try {
        for (const name of ['agent_poll', 'agent_result', 'agent_cancel']) {
          const result = await peer.call(name, { job_id: 'job_1' });
          assert.equal(result.isError, true);
          assert.match(
            JSON.stringify(result),
            /unreadable|unknown|unrecognized/i,
          );
          noSecrets(JSON.stringify(result) + peer.stderr());
        }
        assert.deepEqual(
          fake.frames.map((frame) => frame.type),
          ['job.status', 'job.status', 'job.cancel'],
        );
      } finally {
        await peer.client.close();
        await fake.close();
      }
    }
  },
);

await test(
  'external MCP SDK exercises all 34 server tools and explicit options through WS only',
  { timeout: 20000 },
  async () => {
    const legacy = JSON.parse(
      await readFile(
        new URL(
          '../../test-support/contracts/mcp-compatibility.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ) as {
      toolsList: { result: { tools: unknown[] } };
      cases: {
        id: string;
        request: { params: { name: string; arguments: Data } };
        response: { result: Data };
        backendCalls: { method: string; arguments: Data; returns?: unknown }[];
      }[];
    };
    let current: (typeof legacy.cases)[number] | undefined,
      cursor = 0;
    const fake = await fixture((frame) => {
      const call = current?.backendCalls[cursor++];
      assert.ok(call);
      assert.deepEqual(
        [frame.type, frame.payload],
        ws(call.method, call.arguments),
      );
      // The synthetic Java fixture's job id differs from the requested one.
      // Actual WS replies must retain the requested identity.
      let payload =
        frame.type === 'job.status' || frame.type === 'job.cancel'
          ? { ...(call.returns as Data), id: frame.payload['job'] }
          : call.returns;
      // Legacy synthetic Java bodies predate the current WS DTOs. Supply the
      // extra wire metadata here; formatter parity still uses the original cases.
      if (
        [
          'project.define',
          'project.workspace',
          'project.lend',
          'project.unlend',
        ].includes(frame.type)
      )
        payload = { ...(payload as Data), machine: null, members: [] };
      if (
        frame.type === 'conversation.chat' ||
        frame.type === 'conversation.trajectory'
      )
        payload = {
          ...(payload as Data),
          through: 2,
          oldest: null,
          more: null,
        };
      if (frame.type === 'information.list')
        payload = [{ id: 'retained-source', title: 'Retained source' }];
      if (frame.type === 'conversation.context') {
        const row = payload as Data,
          prefix = row['prefix'] as Data;
        const count = {
          tokens: 2,
          basis: 'ESTIMATED',
          how: 'synthetic fixture estimate',
        };
        payload = {
          ...row,
          measuredTurns: [
            { turn: 1, promptTokens: 1, grewBy: null, since: null },
            { turn: 2, promptTokens: 2, grewBy: 1, since: 1 },
          ],
          systemPromptTokens: count,
          toolTokens: count,
          messageTokens: count,
          prefix: {
            ...prefix,
            systemPromptTokens: count,
            toolTokens: count,
            contextLength: null,
          },
        };
      }
      return {
        code: [
          'agent.run',
          'agent.curate',
          'memory.digest',
          'document.ask',
        ].includes(frame.type)
          ? 'ACCEPTED'
          : payload == null
            ? 'NO_CONTENT'
            : 'OK',
        ...(payload == null ? {} : { payload }),
      };
    });
    const peer = await sdk(fake.base);
    try {
      assert.deepEqual(
        (await peer.client.listTools()).tools,
        legacy.toolsList.result.tools,
      );
      const cases = legacy.cases.filter(
        (item) =>
          item.id.endsWith('/minimal') || item.id.endsWith('/explicit-options'),
      );
      assert.equal(cases.length, 68);
      for (const item of cases) {
        current = item;
        cursor = 0;
        const response = await peer.call(
          item.request.params.name,
          item.request.params.arguments,
        );
        const expected = record(
          json(
            JSON.stringify(item.response.result).replaceAll(
              'job_fixture',
              displayText(
                item.request.params.arguments['job_id'] ?? 'job_fixture',
              ),
            ),
          ),
        );
        if (item.request.params.name === 'information')
          expected.content = [
            {
              type: 'text',
              text: JSON.stringify([
                { id: 'retained-source', title: 'Retained source' },
              ]),
            },
          ];
        assert.deepEqual(response, expected, item.id);
        assert.equal(cursor, item.backendCalls.length);
      }
      assert.equal(
        new Set(cases.map((item) => item.request.params.name)).size,
        34,
      );
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
      assert.equal(fake.claims.length, 0);
      assert.equal(peer.stderr(), '');
    } finally {
      await peer.client.close();
      await fake.close();
    }
  },
);

await test(
  'explicit rooting serves fenced files, defaults commands off, carries session and blocks runs after loss',
  { timeout: 15000 },
  async () => {
    const root = await mkdtemp(join(tmpdir(), 'plowshare-mcp-presence-'));
    const outside = await mkdtemp(join(tmpdir(), 'plowshare-mcp-outside-'));
    await writeFile(join(root, 'note.txt'), 'first\nsecond\n');
    const binary = Buffer.concat([
      Buffer.from('%PDF-1.4\n'),
      Buffer.alloc(140000, 255),
    ]);
    await writeFile(join(root, 'bytes.pdf'), binary);
    await writeFile(join(outside, 'secret.txt'), 'outside');
    await symlink(join(outside, 'secret.txt'), join(root, 'escape.txt'));
    const fake = await fixture(() => ({
      code: 'ACCEPTED',
      payload: { id: 'job_real', agent: 'fixture' },
    }));
    const peer = await sdk(fake.base);
    try {
      await peer.call('agent_run', {
        agent: 'fixture',
        task: 'before rooting',
      });
      assert.equal(fake.frames.at(-1)?.payload['session'], null);
      const missing = await peer.call('client_root_project_here', {
        project: 'repo',
        path: join(root, 'missing'),
      });
      assert.equal(missing.isError, true);
      assert.equal(fake.claims.length, 0);
      const rooted = await peer.call('client_root_project_here', {
        project: 'repo',
        path: root,
      });
      assert.equal(rooted.isError, undefined);
      assert.equal(
        fake.claims[0]?.searchParams.get('root'),
        await realpath(root),
      );
      assert.equal(fake.claims[0]?.searchParams.get('source'), '1');
      const metadata = (
        await fake.file({ id: 'source-meta', op: 'source', path: 'bytes.pdf' })
      ).source;
      assert.deepEqual(metadata, {
        size: binary.length,
        sha256: createHash('sha256').update(binary).digest('hex'),
      });
      const parts: Buffer[] = [];
      for (let offset = 0; offset < binary.length; offset += 65536) {
        const part = (
          await fake.file({
            id: 'source-' + offset,
            op: 'source',
            path: 'bytes.pdf',
            offset,
            limit: 65536,
          })
        ).source;
        assert.equal(part?.offset, offset);
        assert.ok(part?.data);
        parts.push(Buffer.from(part.data, 'base64'));
      }
      assert.deepEqual(Buffer.concat(parts), binary);
      assert.equal(
        (
          await fake.file({
            id: 'source-escape',
            op: 'source',
            path: 'escape.txt',
          })
        ).outcome,
        'refused',
      );
      assert.equal(
        (
          await fake.file({
            id: 'source-bound',
            op: 'source',
            path: 'bytes.pdf',
            offset: 0,
            limit: 65537,
          })
        ).outcome,
        'refused',
      );
      const read = await fake.file({
        id: 'read',
        op: 'read',
        path: 'note.txt',
      });
      assert.equal(read.outcome, 'ok');
      assert.deepEqual(read.span?.lines, ['first', 'second']);
      assert.equal(
        (await fake.file({ id: 'escape', op: 'read', path: 'escape.txt' }))
          .outcome,
        'refused',
      );
      assert.equal(
        (await fake.file({ id: 'outside', op: 'read', path: '../secret.txt' }))
          .outcome,
        'refused',
      );
      assert.equal(
        (
          await fake.file({
            id: 'run',
            op: 'run',
            argv: ['node', '-e', 'process.exit(0)'],
          })
        ).outcome,
        'refused',
      );
      const environment = await fake.file({
        id: 'definitions',
        op: 'read',
        path: '.plowshare/environment.yml',
        purpose: 'definitions',
      });
      assert.deepEqual(environment.span?.lines, ['local:', '  mode: off']);
      await peer.call('agent_run', {
        agent: 'fixture',
        task: 'rooted',
        project: 'repo',
      });
      assert.equal(fake.frames.at(-1)?.payload['session'], fake.session());
      const lost = new Promise<void>((resolve) =>
        fake.fileSockets[0]!.once('close', () => resolve()),
      );
      fake.fileSockets[0]!.close(1001, 'fixture restart');
      await lost;
      // Let the native client close callback run before the next stdio message.
      await peer.client.ping();
      const before = fake.frames.length;
      const blocked = await peer.call('agent_run', {
        agent: 'fixture',
        task: 'after loss',
      });
      assert.equal(blocked.isError, true);
      assert.match(JSON.stringify(blocked), /not submitted/);
      assert.equal(fake.frames.length, before);
      await peer.call('client_root_project_here', {
        project: 'repo',
        path: outside,
      });
      assert.equal(fake.claims.length, 2);
      await peer.call('agent_run', { agent: 'fixture', task: 'rooted again' });
      assert.equal(fake.frames.at(-1)?.payload['session'], fake.session());
      noSecrets(peer.stderr());
    } finally {
      await peer.client.close();
      await fake.close();
      await rm(root, { recursive: true, force: true });
      await rm(outside, { recursive: true, force: true });
    }
  },
);

await test(
  'claim refusal and absent readiness never report successful local presence',
  { timeout: 20000 },
  async () => {
    const root = await mkdtemp(join(tmpdir(), 'plowshare-mcp-refused-'));
    try {
      for (const mode of ['refuse', 'silent'] as const) {
        const fake = await fixture(
          () => ({
            code: 'ACCEPTED',
            payload: { id: 'job_real', agent: 'fixture' },
          }),
          mode,
        );
        const peer = await sdk(fake.base);
        try {
          const response = await peer.call('client_root_project_here', {
            project: 'repo',
            path: root,
          });
          assert.equal(response.isError, true);
          assert.match(JSON.stringify(response), /serving no files/);
          const blocked = await peer.call('agent_run', {
            agent: 'fixture',
            task: 'after refusal',
          });
          assert.equal(blocked.isError, true);
          assert.match(JSON.stringify(blocked), /root explicitly again/);
          assert.equal(
            fake.frames.some((frame) => frame.type === 'agent.run'),
            false,
            'a refused local root cannot silently submit against another client or the global scope',
          );
        } finally {
          await peer.client.close();
          await fake.close();
        }
      }
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  },
);

await test(
  'WS loss is model-visible uncertainty, without replay or implicit job cancellation',
  { timeout: 10000 },
  async () => {
    const fake = await fixture((frame) =>
      frame.type === 'agent.run'
        ? { code: 'ACCEPTED', payload: { id: 'job_real', agent: 'fixture' } }
        : 'drop',
    );
    const peer = await sdk(fake.base);
    try {
      assert.match(
        JSON.stringify(
          await peer.call('agent_run', { agent: 'fixture', task: 'submit' }),
        ),
        /job_real/,
      );
      const result = await peer.call('agent_result', { job_id: 'job_real' });
      assert.equal(result.isError, true);
      assert.match(JSON.stringify(result), /completion is unknown/);
      assert.equal(
        (await peer.call('agent_run', { agent: 'fixture', task: 'no replay' }))
          .isError,
        true,
      );
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['agent.run', 'job.status'],
      );
      noSecrets(JSON.stringify(result) + peer.stderr());
    } finally {
      await peer.client.close();
      await fake.close();
    }
  },
);

function raw(base: string, input?: string, timeout = 1000) {
  const child = spawn(
    process.execPath,
    [main, '--url', base, '--timeout-ms', String(timeout)],
    { env: { ...process.env, ...env }, stdio: ['pipe', 'pipe', 'pipe'] },
  );
  let stdout = '',
    stderr = '';
  child.stdout.on('data', (bytes) => {
    stdout += String(bytes);
  });
  child.stderr.on('data', (bytes) => {
    stderr += String(bytes);
  });
  if (input !== undefined) child.stdin.end(input);
  const done = new Promise<{
    code: number | null;
    stdout: string;
    stderr: string;
  }>((resolve, reject) => {
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
  return { child, done };
}
await test(
  'deadline detaches a submitted mutation, EOF flushes one message, and idle SIGTERM exits',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => 'hang');
    try {
      const timed = await raw(
        fake.base,
        JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'tools/call',
          params: { name: 'memory_digest', arguments: {} },
        }) + '\n',
      ).done;
      assert.equal(timed.code, 5);
      assert.equal(field(json(timed.stdout), ['result', 'isError']), true);
      assert.match(timed.stdout, /completion is unknown/);
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['memory.digest'],
      );
      noSecrets(timed.stdout + timed.stderr);
      const eof = await raw(
        fake.base,
        '{"jsonrpc":"2.0","id":2,"method":"ping"}',
      ).done;
      assert.equal(eof.code, 0);
      assert.deepEqual(JSON.parse(eof.stdout), {
        jsonrpc: '2.0',
        id: 2,
        result: {},
      });
      const idle = raw(fake.base);
      const ready = new Promise<void>((resolve) =>
        idle.child.stdout.once('data', () => resolve()),
      );
      idle.child.stdin.write('{"jsonrpc":"2.0","id":3,"method":"ping"}\n');
      await ready;
      idle.child.kill('SIGTERM');
      assert.equal((await idle.done).code, 5);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'mandatory password change exits before MCP startup, refresh or WS',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => ({ code: 'OK' }), 'ready', true);
    try {
      const result = await raw(
        fake.base,
        '{"jsonrpc":"2.0","id":1,"method":"ping"}\n',
      ).done;
      assert.equal(result.code, 2);
      assert.equal(result.stdout, '');
      assert.match(result.stderr, /password change required/);
      assert.deepEqual(fake.paths, ['/v1/auth/login']);
      noSecrets(result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'external MCP SDK uses a saved login without password environment variables',
  { timeout: 10000 },
  async () => {
    const fake = await fixture((frame) => ({
      code: 'OK',
      payload: retrieval[frame.type],
    }));
    const directory = await mkdtemp(join(tmpdir(), 'plowshare-mcp-login-'));
    try {
      const credentials = new Credentials(
        fake.base,
        join(directory, 'credentials'),
      );
      await credentials.login(
        { base: fake.base, fetch },
        'fixture',
        secrets[0]!,
      );
      const peer = await sdk(fake.base, 10000, {
        PLOWSHARE_HANDLE: '',
        PLOWSHARE_PASSWORD: '',
        PLOWSHARE_PROJECT: '',
        PLOWSHARE_CONFIG_DIR: directory,
      });
      try {
        const answer = await peer.call('memory_index');
        assert.notEqual(answer.isError, true);
        assert.deepEqual(
          fake.frames.map((frame) => frame.type),
          ['memory.index'],
        );
        assert.equal(
          fake.paths.filter((path) => path === '/v1/auth/login').length,
          1,
        );
        noSecrets(JSON.stringify(answer) + peer.stderr());
      } finally {
        await peer.client.close();
      }
    } finally {
      await fake.close();
      await rm(directory, { recursive: true, force: true });
    }
  },
);

await test(
  'external MCP SDK reads complete nonempty conversation replies and refuses damaged pages',
  { timeout: 10000 },
  async () => {
    const replies = JSON.parse(
      await readFile(
        new URL(
          '../../test-support/contracts/ws-conversation-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ) as Record<string, Outcome>;
    let damaged = false;
    const fake = await fixture((frame) =>
      damaged
        ? { code: 'OK', payload: { entries: [], total: 100 } }
        : replies[frame.type]!,
    );
    const peer = await sdk(fake.base);
    try {
      const listed = await peer.call('conversation_list', {});
      assert.notEqual(listed.isError, true);
      assert.match(JSON.stringify(listed), /c — allowance unlimited/);
      const trajectory = await peer.call('conversation_trajectory', {
        conversation: 'c',
      });
      assert.notEqual(trajectory.isError, true);
      assert.match(JSON.stringify(trajectory), /future_kind|content ejected/);
      const context = await peer.call('conversation_context', {
        conversation: 'c',
        agent: 'a',
      });
      assert.notEqual(context.isError, true);
      assert.match(JSON.stringify(context), /120 tokens/);
      damaged = true;
      const failed = await peer.call('conversation_trajectory', {
        conversation: 'c',
      });
      assert.equal(failed.isError, true);
      assert.match(JSON.stringify(failed), /unreadable operation result/);
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        [
          'conversation.list',
          'conversation.trajectory',
          'conversation.context',
          'conversation.trajectory',
        ],
      );
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
      noSecrets(JSON.stringify(failed) + peer.stderr());
    } finally {
      await peer.client.close();
      await fake.close();
    }
  },
);

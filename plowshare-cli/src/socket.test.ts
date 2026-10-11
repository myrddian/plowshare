import { nodePacketSocket } from 'plowshare-client-node';
import { run } from './run.js';
import { httpHandler } from './http.test-support.js';
import { record, json, list, field, text } from './json.test-support.js';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import {
  readFile,
  mkdtemp,
  readdir,
  rm,
  mkdir,
  writeFile,
  symlink,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { WebSocketServer } from 'ws';
import type { WebSocket } from 'ws';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';

interface Frame {
  id: string;
  type: string;
  protocol_version: string;
  payload: Record<string, unknown>;
}
type Script = (frame: Frame) => Outcome | 'drop' | 'hang';
const main =
  process.env['PLOWSHARE_CLI_TEST_MAIN'] ??
  fileURLToPath(new URL('../build/main.js', import.meta.url));
const inspections = JSON.parse(
  await readFile(
    new URL(
      '../../test-support/contracts/ws-inspection-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<string, Outcome>;
const conversations = JSON.parse(
  await readFile(
    new URL(
      '../../test-support/contracts/ws-conversation-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<string, Outcome>;
const retrieval = (
  JSON.parse(
    await readFile(
      new URL(
        '../../test-support/contracts/ws-retrieval-fixtures.json',
        import.meta.url,
      ),
      'utf8',
    ),
  ) as { replies: Record<string, Record<string, unknown> | unknown[]> }
).replies;

async function fixture(
  script: Script,
  flagged = false,
  refuseLogin = false,
  machineCredential?: string,
) {
  const paths: string[] = [],
    frames: Frame[] = [],
    sessions: string[] = [];
  let seen: ((frame: Frame) => void) | undefined;
  const server = createServer(
    httpHandler(async (req, res) => {
      paths.push(req.url ?? '');
      if (req.url === '/v1/auth/login') {
        const chunks: Buffer[] = [];
        for await (const chunk of req)
          chunks.push(Buffer.from(chunk as Uint8Array));
        assert.deepEqual(json(Buffer.concat(chunks).toString()), {
          handle: 'fixture',
          password: 'fixture-password-secret',
        });
        assert.equal(req.headers['x-plowshare-token-delivery'], 'body');
        if (refuseLogin) {
          res.writeHead(401).end();
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(
          JSON.stringify({
            access: 'fixture-access-secret',
            refresh: flagged ? null : 'fixture-refresh-secret',
            mustChangePassword: flagged,
          }),
        );
      } else if (req.url === '/v1/auth/refresh') {
        assert.equal(req.headers.cookie, 'ps_refresh=fixture-refresh-secret');
        res
          .writeHead(204, {
            'Set-Cookie': [
              'ps_access=renewed-access-secret; Path=/',
              'ps_refresh=renewed-refresh-secret; Path=/',
            ],
          })
          .end();
      } else if (req.url === '/v1/auth/ticket') {
        assert.equal(
          req.headers.authorization,
          'Bearer ' + (machineCredential ?? 'renewed-access-secret'),
        );
        if (machineCredential === 'pss_revoked-test') {
          res.writeHead(401).end();
          return;
        }
        res
          .writeHead(200, { 'Content-Type': 'application/json' })
          .end(JSON.stringify({ ticket: 'ticket-secret' }));
      } else res.writeHead(404).end();
    }),
  );
  const sockets = new WebSocketServer({ server });
  const eventPeers = new Map<WebSocket, ReturnType<typeof nodePacketSocket>>();
  sockets.on('connection', (socket, req) => {
    const url = new URL(req.url!, 'http://localhost');
    assert.equal(url.pathname, '/v1/events');
    const messages = nodePacketSocket(socket);
    eventPeers.set(socket, messages);
    socket.on('close', () => eventPeers.delete(socket));
    assert.equal(url.searchParams.get('ticket'), 'ticket-secret');
    assert.ok(url.searchParams.get('session'));
    sessions.push(url.searchParams.get('session')!);
    assert.equal(url.searchParams.has('root'), false);
    messages.addEventListener('message', (event) => {
      if (typeof event.data !== 'string')
        throw new Error('Non-text logical fixture message');
      const bytes = event.data;
      const frame = JSON.parse(bytes) as Frame;
      assert.equal(frame.protocol_version, 'plowshare-v1');
      frames.push(frame);
      seen?.(frame);
      const outcome = script(frame);
      if (outcome === 'drop') {
        socket.close();
        return;
      }
      if (outcome === 'hang') return;
      // Bare ended pushes never prove completion, including another job's event.
      messages.send(JSON.stringify({ job: 'foreign-job', kind: 'ended' }));
      messages.send(
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
    sessions,
    onFrame(callback: (frame: Frame) => void) {
      seen = callback;
    },
    push(value: unknown) {
      for (const peer of eventPeers.values()) peer.send(JSON.stringify(value));
    },
    disconnect() {
      for (const socket of sockets.clients) socket.close();
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

function cli(
  base: string,
  args: string[],
  input = '',
  extraEnv: Record<string, string> = {},
) {
  const child = spawn(process.execPath, [main, '--url', base, ...args], {
    env: {
      ...process.env,
      PLOWSHARE_TOKEN: '',
      PLOWSHARE_HANDLE: 'fixture',
      PLOWSHARE_PASSWORD: 'fixture-password-secret',
      PLOWSHARE_PROJECT: '',
      ...extraEnv,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stdout = '',
    stderr = '';
  let output: ((stdout: string) => void) | undefined;
  child.stdout.on('data', (bytes) => {
    stdout += String(bytes);
    output?.(stdout);
  });
  child.stderr.on('data', (bytes) => {
    stderr += String(bytes);
  });
  child.stdin.end(input);
  const done = new Promise<{
    code: number | null;
    stdout: string;
    stderr: string;
  }>((resolve, reject) => {
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
  return {
    child,
    done,
    onOutput(callback: (stdout: string) => void) {
      output = callback;
    },
  };
}

function noSecrets(output: string): void {
  for (const secret of [
    'fixture-password-secret',
    'fixture-access-secret',
    'renewed-access-secret',
    'fixture-refresh-secret',
    'renewed-refresh-secret',
    'ticket-secret',
  ])
    assert.ok(!output.includes(secret), secret);
}

await test(
  'new conversation jobs use one authenticated submission and retain both handles through wait',
  { timeout: 10000 },
  async () => {
    let submitted = 0;
    const fake = await fixture((frame) => {
      if (frame.type === 'agent.run') {
        submitted++;
        assert.equal(frame.payload.newConversation, true);
        return {
          code: 'ACCEPTED',
          payload: {
            id: 'job_new',
            agent: 'a',
            conversation: `cnv_new_${submitted}`,
          },
        };
      }
      return {
        code: 'OK',
        payload: {
          id: 'job_new',
          state: 'FINISHED',
          outcome: { ending: 'ANSWERED', answered: true, text: 'done' },
        },
      };
    });
    try {
      const first = await cli(fake.base, [
        'agent',
        'run',
        '{"agent":"a","task":"work"}',
        '--json',
      ]).done;
      assert.equal(first.code, 3, first.stdout + first.stderr);
      assert.equal(field(json(first.stdout), ['conversation']), 'cnv_new_1');
      assert.equal(fake.frames[0]!.payload.project, undefined);
      const second = await cli(fake.base, [
        'agent',
        'run',
        '{"agent":"a","task":"work"}',
        '--project',
        'repo',
        '--new-conversation',
        '--json',
        '--wait',
      ]).done;
      assert.equal(second.code, 0, second.stdout + second.stderr);
      assert.equal(field(json(second.stdout), ['conversation']), 'cnv_new_2');
      assert.equal(fake.frames[1]!.payload.project, 'repo');
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['agent.run', 'agent.run', 'job.status'],
      );
      noSecrets(first.stdout + second.stdout);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'uncertain new conversation submission is never replayed',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => 'drop');
    try {
      const result = await cli(fake.base, [
        'agent',
        'run',
        '{"agent":"a","task":"work"}',
        '--json',
      ]).done;
      assert.equal(result.code, 5);
      assert.equal(
        field(json(result.stdout), ['code']),
        'SUBMISSION_UNCERTAIN',
      );
      assert.equal(fake.frames.length, 1);
      assert.equal(fake.frames[0]!.payload.newConversation, true);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'unreadable retrieval results fail explicitly with raw replies and no repeated mutations',
  { timeout: 10000 },
  async () => {
    const cases: [string[], Record<string, unknown>, unknown][] = [
      [['memory', 'recall'], { question: 'x' }, { unsearchable: 0 }],
      [
        ['memory', 'write'],
        {
          proposal: {
            summary: 'x',
            scope: 'x',
            body: 'x',
            formedBy: 'person',
            formedWhere: '',
          },
        },
        { kind: 'new', memoryId: 'm' },
      ],
      [
        ['memory', 'resolve'],
        { proposal: 'p', accept: true, by: 'person' },
        { promotedId: 'm', demoted: [] },
      ],
      [
        ['document', 'search'],
        { query: 'x' },
        { ...record(retrieval['document.search']), searchable: '0' },
      ],
      [
        ['document', 'citations'],
        {},
        {
          ...record(retrieval['document.citations']),
          citations: [{ standing: 'resolves', paragraphText: null }],
        },
      ],
      [
        ['web', 'fetch'],
        { url: 'https://example.invalid' },
        { ...record(retrieval['web.fetch']), nextOffset: 0 },
      ],
    ];
    for (const [command, payload, returned] of cases) {
      const fake = await fixture(() => ({ code: 'OK', payload: returned }));
      try {
        const result = await cli(
          fake.base,
          ['--json', '--global', '--payload', '-', ...command],
          JSON.stringify(payload),
        ).done;
        assert.equal(result.code, 5);
        const output = json(result.stdout);
        assert.equal(field(output, ['status']), 'invalid-response');
        assert.deepEqual(field(output, ['outcome']), {
          code: 'OK',
          payload: returned,
        });
        assert.equal(fake.frames.length, 1);
        assert.ok(
          fake.paths.every((path) =>
            ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
              path,
            ),
          ),
        );
        noSecrets(result.stdout + result.stderr);
      } finally {
        await fake.close();
      }
    }
  },
);

await test(
  'headless subprocess covers all twelve memory verbs, scoped search and JSON/stdin without an agent turn',
  { timeout: 15000 },
  async () => {
    const commands: readonly [
      string[],
      string,
      Record<string, unknown>,
      number,
    ][] = [
      [['memory', 'index'], 'memory.index', { project: 'repo' }, 0],
      [['memory', 'read', 'm'], 'memory.read', { memory: 'm' }, 0],
      [
        ['memory', 'recall', 'build instructions'],
        'memory.recall',
        { question: 'build instructions', project: 'repo' },
        0,
      ],
      [
        [
          'memory',
          'write',
          '{"proposal":{"summary":"build","scope":"repo","body":"pnpm build","formedBy":"person","formedWhere":""}}',
        ],
        'memory.write',
        {
          proposal: {
            summary: 'build',
            scope: 'repo',
            body: 'pnpm build',
            formedBy: 'person',
            formedWhere: '',
          },
          project: 'repo',
        },
        0,
      ],
      [
        ['memory', 'navigate', 'build instructions'],
        'memory.navigate',
        { question: 'build instructions', project: 'repo' },
        4,
      ],
      [['memory', 'digest'], 'memory.digest', { project: 'repo' }, 3],
      [['memory', 'curate'], 'agent.curate', { project: 'repo' }, 3],
      [['memory', 'proposals'], 'proposal.list', { project: 'repo' }, 0],
      [
        [
          'memory',
          'resolve',
          '{"proposal":"p","accept":false,"by":"person","reason":"stale"}',
        ],
        'proposal.resolve',
        { proposal: 'p', accept: false, by: 'person', reason: 'stale' },
        0,
      ],
      [['memory', 'reconsider'], 'proposal.reconsider', { project: 'repo' }, 0],
      [
        [
          'memory',
          'invalidate',
          '{"memory":"m","reason":"stale","by":"person"}',
        ],
        'memory.invalidate',
        { memory: 'm', reason: 'stale', by: 'person' },
        0,
      ],
      [['memory', 'reembed'], 'memory.reembed', { project: 'repo' }, 0],
      [
        ['search', '{"q":"build","project":null,"offset":2,"limit":3}'],
        'conversation.search',
        { q: 'build', project: null, offset: 2, limit: 3 },
        0,
      ],
    ];
    const fake = await fixture((frame) =>
      frame.type === 'memory.digest' || frame.type === 'agent.curate'
        ? { code: 'ACCEPTED', payload: { id: 'job_1', agent: 'maintenance' } }
        : { code: 'OK', payload: retrieval[frame.type] },
    );
    try {
      for (const [args, type, payload, code] of commands) {
        const result = await cli(fake.base, [
          '--json',
          '--project',
          'repo',
          ...args,
        ]).done;
        assert.equal(result.code, code, result.stderr + result.stdout);
        assert.equal(result.stderr, '');
        const output = json(result.stdout);
        assert.equal(field(output, ['operation']), type);
        assert.deepEqual(fake.frames.at(-1)?.payload, payload);
        noSecrets(result.stdout);
      }
      const stdin = await cli(
        fake.base,
        ['--json', '--global', '--payload', '-', 'memory', 'recall'],
        '{"question":"from stdin","limit":2}',
      ).done;
      assert.equal(stdin.code, 0);
      assert.deepEqual(fake.frames.at(-1)?.payload, {
        question: 'from stdin',
        limit: 2,
      });
      assert.equal(
        field(json(stdin.stdout), ['outcome', 'payload', 'unsearchable']),
        2,
      );
      const readable = await cli(fake.base, ['--global', 'memory', 'index'])
        .done;
      assert.match(readable.stdout, /memory.index: completed/);
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        [...commands.map(([, type]) => type), 'memory.recall', 'memory.index'],
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'explicit waiting polls only durable WS status, including terminal cancellation',
  { timeout: 10000 },
  async () => {
    let statuses = 0;
    const fake = await fixture((frame) =>
      frame.type === 'memory.digest'
        ? { code: 'ACCEPTED', payload: { id: 'job_1' } }
        : frame.type === 'job.cancel'
          ? {
              code: 'OK',
              payload: { id: 'job_1', state: 'RUNNING', cancelRequested: true },
            }
          : {
              code: 'OK',
              payload: {
                id: 'job_1',
                state: ++statuses % 2 ? 'RUNNING' : 'FINISHED',
                ...(!(statuses % 2)
                  ? {
                      outcome: {
                        ending: statuses > 2 ? 'CANCELLED' : 'ANSWERED',
                        answered: statuses <= 2,
                        text: 'durable result',
                      },
                    }
                  : {}),
              },
            },
    );
    try {
      const completed = await cli(fake.base, [
        '--json',
        '--global',
        '--wait',
        '--poll-ms',
        '1',
        'memory',
        'digest',
      ]).done;
      assert.equal(completed.code, 0, completed.stdout);
      assert.equal(
        field(json(completed.stdout), [
          'outcome',
          'payload',
          'outcome',
          'text',
        ]),
        'durable result',
      );
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['memory.digest', 'job.status', 'job.status'],
      );
      const cancelled = await cli(fake.base, [
        '--json',
        '--global',
        '--wait',
        '--poll-ms',
        '1',
        'job',
        'cancel',
        'job_1',
      ]).done;
      assert.equal(cancelled.code, 4);
      assert.equal(
        field(json(cancelled.stdout), [
          'outcome',
          'payload',
          'outcome',
          'ending',
        ]),
        'CANCELLED',
      );
      assert.equal(
        fake.frames.filter((frame) => frame.type === 'job.cancel').length,
        1,
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'disconnect after acceptance keeps the handle, never replays a mutation, and leaks no credentials',
  { timeout: 10000 },
  async () => {
    const fake = await fixture((frame) =>
      frame.type === 'memory.digest'
        ? { code: 'ACCEPTED', payload: { id: 'job_1' } }
        : 'drop',
    );
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        '--wait',
        'memory',
        'digest',
      ]).done;
      assert.equal(result.code, 5);
      assert.equal(field(json(result.stdout), ['status']), 'unknown');
      assert.equal(field(json(result.stdout), ['job']), 'job_1');
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['memory.digest', 'job.status'],
      );
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'malformed durable status preserves the accepted handle and raw reply without replay',
  { timeout: 10000 },
  async () => {
    for (const payload of [
      { state: 'DONE', outcome: { ending: 'ANSWERED', answered: true } },
      { id: 'job_1', state: 'DONE', outcome: { ending: 'ANSWERED' } },
      {
        id: 'foreign-job',
        state: 'DONE',
        outcome: { ending: 'ANSWERED', answered: true },
      },
      { id: 'job_1', state: 'DONE', outcome: 'broken' },
    ]) {
      const fake = await fixture((frame) =>
        frame.type === 'memory.digest'
          ? { code: 'ACCEPTED', payload: { id: 'job_1' } }
          : { code: 'OK', payload },
      );
      try {
        const result = await cli(fake.base, [
          '--json',
          '--global',
          '--wait',
          'memory',
          'digest',
        ]).done;
        assert.equal(result.code, 5);
        const reply = json(result.stdout);
        assert.deepEqual(
          {
            operation: field(reply, ['operation']),
            status: field(reply, ['status']),
            job: field(reply, ['job']),
            outcome: field(reply, ['outcome']),
          },
          {
            operation: 'memory.digest',
            status: 'invalid-response',
            job: 'job_1',
            outcome: { code: 'OK', payload },
          },
        );
        assert.equal(field(reply, ['code']), 'INVALID_SERVER_RESPONSE');
        assert.equal(field(reply, ['nextActions', 0, 'action']), 'inspect');
        assert.deepEqual(
          fake.frames.map((frame) => frame.type),
          ['memory.digest', 'job.status'],
        );
        noSecrets(result.stdout + result.stderr);
      } finally {
        await fake.close();
      }
    }
  },
);

await test(
  'Ctrl-C detaches a known job without cancellation',
  { timeout: 10000 },
  async () => {
    const fake = await fixture((frame) =>
      frame.type === 'memory.digest'
        ? { code: 'ACCEPTED', payload: { id: 'job_1' } }
        : 'hang',
    );
    try {
      fake.onFrame((frame) => {
        if (frame.type === 'job.status') interrupt?.();
      });
      const process = cli(fake.base, [
        '--json',
        '--global',
        '--wait',
        'memory',
        'digest',
      ]);
      const interrupt = () => {
        process.child.kill('SIGINT');
      };
      const result = await process.done;
      assert.equal(result.code, 5);
      assert.equal(field(json(result.stdout), ['job']), 'job_1');
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['memory.digest', 'job.status'],
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'deadline after submission reports uncertainty without retrying synchronous maintenance',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => 'hang');
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        '--timeout-ms',
        '1000',
        'memory',
        'reembed',
      ]).done;
      assert.equal(result.code, 5);
      assert.equal(field(json(result.stdout), ['status']), 'unknown');
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['memory.reembed'],
      );
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'a refused operation carries the server sentence and does not invoke an agent',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => ({
      code: 'NOT_FOUND',
      said: 'No such memory.',
      payload: { future: 'retained' },
    }));
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        'memory',
        'read',
        'missing',
      ]).done;
      assert.equal(result.code, 1);
      assert.deepEqual(field(json(result.stdout), ['outcome']), {
        code: 'NOT_FOUND',
        said: 'No such memory.',
        payload: { future: 'retained' },
      });
      assert.equal(fake.frames.length, 1);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'mandatory password change fails promptly without refresh, ticket, WS or terminal prompts',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => ({ code: 'OK' }), true);
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        'memory',
        'index',
      ]).done;
      assert.equal(result.code, 2);
      assert.match(
        text(json(result.stdout), ['said']),
        /password change required/,
      );
      assert.deepEqual(fake.paths, ['/v1/auth/login']);
      assert.deepEqual(fake.frames, []);
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'sign-in refusal never distinguishes a wrong account from a wrong password or prints secrets',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => ({ code: 'OK' }), false, true);
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        'memory',
        'index',
      ]).done;
      assert.equal(result.code, 2);
      assert.match(text(json(result.stdout), ['said']), /sign-in refused/);
      assert.deepEqual(fake.paths, ['/v1/auth/login']);
      assert.deepEqual(fake.frames, []);
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'job wait and result expose authoritative partial outcomes without hiding their ending',
  { timeout: 10000 },
  async () => {
    let reads = 0;
    const fake = await fixture(() => ({
      code: 'OK',
      payload:
        ++reads % 2
          ? { id: 'job_1', state: 'RUNNING' }
          : {
              id: 'job_1',
              state: 'FINISHED',
              outcome: {
                ending: 'CALL_BUDGET',
                answered: false,
                text: 'allowance spent',
              },
            },
    }));
    try {
      const running = await cli(fake.base, [
        '--json',
        '--global',
        'job',
        'result',
        'job_1',
      ]).done;
      assert.equal(running.code, 3);
      assert.equal(field(json(running.stdout), ['status']), 'running');
      const partial = await cli(fake.base, [
        '--json',
        '--global',
        '--poll-ms',
        '1',
        'job',
        'wait',
        'job_1',
      ]).done;
      assert.equal(partial.code, 4);
      assert.equal(
        field(json(partial.stdout), [
          'outcome',
          'payload',
          'outcome',
          'ending',
        ]),
        'CALL_BUDGET',
      );
      assert.ok(
        fake.frames.every(
          (frame) =>
            frame.type === 'job.status' && frame.payload['job'] === 'job_1',
        ),
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'broader operator commands use native WS with server fields, scope and full results intact',
  { timeout: 20000 },
  async () => {
    const cases: readonly [string, string, Record<string, unknown>, number][] =
      [
        ['conversation open', 'conversation.open', {}, 0],
        [
          'conversation list',
          'conversation.list',
          { lifecycle: 'archived' },
          0,
        ],
        ['conversation latest', 'conversation.latest', { agent: 'a' }, 0],
        [
          'conversation lifecycle',
          'conversation.lifecycle',
          { conversation: 'c', lifecycle: 'archived' },
          0,
        ],
        ['conversation turns', 'conversation.turns', { conversation: 'c' }, 0],
        [
          'conversation compactions',
          'conversation.compactions',
          { conversation: 'c' },
          0,
        ],
        [
          'conversation chat',
          'conversation.chat',
          { conversation: 'c', offset: 2, limit: 3 },
          0,
        ],
        [
          'conversation trajectory',
          'conversation.trajectory',
          { conversation: 'c', tail: true, kinds: ['answered'], drawn: true },
          0,
        ],
        [
          'conversation context',
          'conversation.context',
          { conversation: 'c' },
          0,
        ],
        [
          'conversation projection',
          'conversation.projection',
          { conversation: 'c', agent: 'a', turn: 1 },
          0,
        ],
        [
          'conversation resume',
          'conversation.resume',
          { conversation: 'c', session: null },
          3,
        ],
        ['agent list', 'agent.list', {}, 0],
        [
          'agent define',
          'agent.define',
          { name: 'a', text: 'exact definition\n', overwrite: false },
          0,
        ],
        [
          'agent run',
          'agent.run',
          {
            agent: 'a',
            task: 'standalone',
            session: null,
            newConversation: false,
          },
          3,
        ],
        ['job list', 'job.list', {}, 0],
        [
          'job limits',
          'job.limits',
          { job: 'j', maxModelCalls: 100, noTurnCap: true },
          0,
        ],
        [
          'document ask',
          'document.ask',
          { document: 'd', question: 'why?' },
          3,
        ],
        [
          'document retrieve',
          'document.retrieve',
          { query: 'x', document: 'd', limit: 2 },
          0,
        ],
        [
          'document list',
          'document.list',
          { q: 'title', offset: 2, limit: 3 },
          0,
        ],
        ['document outline', 'document.detail', { document: 'd' }, 0],
        ['document chunk', 'document.chunk', { chunk: 'ch' }, 0],
        ['document rank', 'document.rank', { query: 'x' }, 0],
        [
          'document stance',
          'document.stance',
          { document: 'd', claim: 'x' },
          0,
        ],
        ['document citations', 'document.citations', { conversation: 'c' }, 0],
        [
          'document search',
          'document.search',
          { query: 'x', mode: 'LEXICAL' },
          0,
        ],
        [
          'web search',
          'web.search',
          { query: 'x', pageSize: 10, max: 20, page: 2 },
          1,
        ],
        [
          'web fetch',
          'web.fetch',
          { url: 'https://example.com', offset: 809 },
          0,
        ],
        ['admin accounts', 'admin.accounts', {}, 0],
        [
          'admin account create',
          'admin.account.create',
          { handle: 'member', serverAdmin: false },
          0,
        ],
        [
          'admin account update',
          'admin.account.update',
          { handle: 'member', enabled: false },
          0,
        ],
        ['admin account reset', 'admin.account.reset', { handle: 'member' }, 0],
        ['admin sessions', 'admin.sessions', { handle: 'member' }, 0],
        [
          'admin session revoke',
          'admin.session.revoke',
          { handle: 'member' },
          0,
        ],
        ['admin audit', 'admin.audit', { limit: 25, before: 0 }, 0],
        [
          'project create',
          'project.create',
          {
            name: 'integration',
            type: 'DISJOINT',
            workspace: '/server/pipeline',
            writePaths: ['generated', 'reports'],
          },
          0,
        ],
        ['project list', 'project.list', {}, 0],
        [
          'project define',
          'project.define',
          {
            name: 'p',
            workspace: '/server/work',
            lent: ['/server/extra'],
            exclusions: ['private'],
          },
          0,
        ],
        [
          'project lend',
          'project.lend',
          { project: 'p', roots: ['/server/extra'] },
          0,
        ],
        [
          'project unlend',
          'project.unlend',
          { project: 'p', roots: ['/server/extra'] },
          0,
        ],
        [
          'project workspace',
          'project.workspace',
          { project: 'p', workspace: '/server/new' },
          0,
        ],
        ['project move', 'project.move', { project: 'p', to: 'renamed' }, 0],
        ['project forget', 'project.forget', { project: 'p' }, 0],
        ['project access', 'project.access', { project: 'p' }, 0],
        [
          'project member-role',
          'project.member.role',
          { project: 'p', handle: 'member', role: 'VIEWER' },
          0,
        ],
        [
          'project member-add',
          'project.member.add',
          { project: 'p', handle: 'member' },
          0,
        ],
        [
          'project member-remove',
          'project.member.remove',
          { project: 'p', handle: 'member' },
          0,
        ],
      ];
    const content = {
      searchable: 7,
      unsearchable: 2,
      nextOffset: 1601,
      hasMore: true,
      hits: [
        { chunkId: 'ch', paragraphId: 'pa', documentId: 'd', text: 'source' },
      ],
      future: { preserved: true },
    };
    const fake = await fixture((frame) =>
      ['agent.run', 'document.ask', 'conversation.resume'].includes(frame.type)
        ? { code: 'ACCEPTED', payload: { id: 'j', agent: 'a' } }
        : conversations[frame.type] !== undefined
          ? conversations[frame.type]!
          : inspections[frame.type] !== undefined
            ? inspections[frame.type]!
            : frame.type === 'project.move' || frame.type === 'project.forget'
              ? { code: 'NO_CONTENT' }
              : frame.type === 'conversation.latest'
                ? { code: 'OK' }
                : frame.type === 'web.search'
                  ? {
                      code: 'OK',
                      payload: {
                        ...content,
                        refusal: 'provider allowance spent',
                      },
                    }
                  : { code: 'OK', payload: retrieval[frame.type] ?? content },
    );
    try {
      for (const [command, type, payload, code] of cases) {
        const result = await cli(
          fake.base,
          ['--json', '--global', '--payload', '-', ...command.split(' ')],
          JSON.stringify(payload),
        ).done;
        assert.equal(
          result.code,
          code,
          command + result.stdout + result.stderr,
        );
        assert.equal(result.stderr, '');
        const output = json(result.stdout);
        assert.equal(field(output, ['operation']), type);
        assert.deepEqual(fake.frames.at(-1)?.payload, payload);
        if (type === 'document.search' || type === 'web.fetch')
          assert.deepEqual(
            field(output, ['outcome', 'payload']),
            retrieval[type],
          );
        if (type === 'conversation.latest')
          assert.equal('payload' in record(field(output, ['outcome'])), false);
        if (type === 'web.search')
          assert.equal(field(output, ['status']), 'refused');
        noSecrets(result.stdout);
      }
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        cases.map(([, type]) => type),
      );
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'agent, resume and document jobs wait through durable status without an implicit conversation or session mismatch',
  { timeout: 10000 },
  async () => {
    const fake = await fixture((frame) =>
      frame.type === 'job.status'
        ? {
            code: 'OK',
            payload: {
              id: 'j',
              state: 'FINISHED',
              outcome: { ending: 'ANSWERED', answered: true, text: 'done' },
            },
          }
        : { code: 'ACCEPTED', payload: { id: 'j' } },
    );
    try {
      for (const [command, payload] of [
        [
          'agent run',
          { agent: 'a', task: 'standalone', newConversation: false },
        ],
        ['agent run', { agent: 'a', task: 'turn', conversation: 'c' }],
        ['conversation resume', { conversation: 'c' }],
        ['document ask', { document: 'd', question: 'why?' }],
      ] as const) {
        const result = await cli(fake.base, [
          '--json',
          '--project',
          'repo',
          '--wait',
          ...command.split(' '),
          JSON.stringify(payload),
        ]).done;
        assert.equal(result.code, 0, result.stdout);
        const submitted = fake.frames.at(-2)!;
        assert.equal(fake.frames.at(-1)?.type, 'job.status');
        if (command !== 'document ask')
          assert.equal(submitted.payload.session, fake.sessions.at(-1));
        if (command === 'agent run' && !('conversation' in payload))
          assert.equal(submitted.payload.project, 'repo');
        else assert.equal('project' in submitted.payload, false);
        assert.equal(
          field(json(result.stdout), ['outcome', 'payload', 'outcome', 'text']),
          'done',
        );
      }
      assert.equal(
        fake.frames.filter((frame) => frame.type === 'conversation.open')
          .length,
        0,
      );
      assert.equal(
        fake.frames.filter((frame) => frame.type === 'job.cancel').length,
        0,
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'invalid new operation payloads fail before authentication or dispatch',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => {
      throw new Error('invalid input reached server');
    });
    try {
      for (const args of [
        ['agent', 'run', '{"agent":"a","task":"x","maxModelCalls":50}'],
        ['conversation', 'open', '{"maxModelCalls":50,"noBudget":true}'],
        ['web', 'search', '{"query":"x"}'],
        [
          'schedule',
          'save',
          '{"name":"../escape","source":"server","definition":{}}',
        ],
        ['schedule', 'sync', '{"source":"workspace"}'],
        ['project', 'lend', '{"project":"p","roots":[]}'],
      ]) {
        const result = await cli(fake.base, ['--json', '--global', ...args])
          .done;
        assert.equal(result.code, 2, result.stdout);
        assert.equal(field(json(result.stdout), ['status']), 'error');
      }
      assert.deepEqual(fake.paths, []);
      assert.deepEqual(fake.frames, []);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'administrative command families remain WS-only, preserve proposals and never invent job submissions',
  { timeout: 20000 },
  async () => {
    const cases: readonly [string, string, Record<string, unknown>, number][] =
      [
        ['approval list', 'approval.list', { mine: true }, 0],
        [
          'approval answer',
          'approval.answer',
          { id: 'a', decision: 'once' },
          3,
        ],
        ['approval revoke', 'approval.revoke', { id: 'a' }, 0],
        [
          'board topup',
          'board.topup',
          { topic: 'topic', maxModelCalls: 100 },
          0,
        ],
        ['buffer purge', 'buffer.purge', {}, 0],
        ['retention sweep', 'retention.sweep', {}, 0],
        ['provider list', 'provider.list', {}, 0],
        ['provider deregister', 'provider.deregister', { provider: 'key' }, 0],
        ['inbox list', 'inbox.list', { unread: true, offset: 2, limit: 4 }, 0],
        ['inbox read', 'inbox.read', { items: ['i1', 'i2'] }, 0],
        ['todos read', 'todos.read', { conversation: 'c' }, 0],
        [
          'orchestration definitions',
          'orchestration.definitions',
          { project: null },
          0,
        ],
        [
          'orchestration list',
          'orchestration.list',
          { project: 'p', state: 'asking', limit: 20 },
          0,
        ],
        ['orchestration status', 'orchestration.status', { id: 'o' }, 0],
        [
          'orchestration answer',
          'orchestration.answer',
          {
            id: 'o',
            choices: [{ header: 'question', chosen: ['first'] }],
            answer: '',
          },
          0,
        ],
        ['orchestration cancel', 'orchestration.cancel', { id: 'o' }, 0],
        [
          'orchestration resume',
          'orchestration.resume',
          { id: 'o', requestId: '00000000-0000-0000-0000-000000000001' },
          0,
        ],
        ['orchestration caps', 'orchestration.caps', { project: 'p' }, 0],
        [
          'orchestration record',
          'orchestration.record',
          { root: 'o', before: 10, limit: 5, kinds: ['cap.answered'] },
          0,
        ],
        ['schedule list', 'schedule.list', {}, 0],
        ['schedule files', 'schedule.files', {}, 0],
        [
          'schedule sync',
          'schedule.sync',
          { project: 'p', source: 'server' },
          0,
        ],
        [
          'schedule save',
          'schedule.save',
          {
            name: 'weekday',
            project: 'p',
            source: 'server',
            definition: {
              version: 1,
              cron: '0 0 9 * * MON-FRI',
              zone: 'UTC',
              paused: false,
              action: {
                kind: 'skill',
                agent: 'reviewer',
                name: 'review',
                input: 'Review retained sources',
                mode: 'NEW',
              },
              target: {
                kind: 'mailbox',
                project: null,
                conversation: null,
                to: null,
                route: null,
              },
              limits: { maxModelCalls: 50, maxTurns: 10, queueCap: 1 },
            },
          },
          0,
        ],
        [
          'schedule define',
          'schedule.define',
          {
            schedule: 'weekday',
            cron: '0 0 9 * * MON-FRI',
            zone: 'Australia/Melbourne',
            emits: 'daily',
          },
          0,
        ],
        [
          'schedule read',
          'schedule.read',
          {
            text: 'every weekday at nine here',
            conversation: 'c',
            project: 'p',
          },
          0,
        ],
        [
          'schedule pause',
          'schedule.pause',
          { schedule: 'weekday', paused: false },
          0,
        ],
        ['schedule resume', 'schedule.pause', { schedule: 'weekday' }, 0],
        ['schedule forget', 'schedule.forget', { schedule: 'weekday' }, 0],
        ['trigger list', 'trigger.list', {}, 0],
        [
          'trigger define',
          'trigger.define',
          {
            trigger: 't',
            event: 'daily',
            agent: 'a',
            task: 'check',
            project: 'p',
            maxModelCalls: 50,
            maxTurns: 10,
            queueCap: 2,
          },
          0,
        ],
        ['trigger pause', 'trigger.pause', { trigger: 't', paused: true }, 0],
        ['trigger forget', 'trigger.forget', { trigger: 't' }, 0],
        [
          'event fire',
          'event.fire',
          { event: 'daily', data: { text: 'Manual observation' } },
          0,
        ],
        [
          'firing list',
          'firing.list',
          { trigger: 't', status: 'queued', offset: 2, limit: 10 },
          0,
        ],
        ['union status', 'union.status', { project: 'p' }, 0],
        ['union conflicts', 'union.conflict.list', { project: 'p' }, 0],
      ];
    const replies = JSON.parse(
      await readFile(
        new URL(
          '../../test-support/contracts/ws-administrative-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ) as Record<string, Outcome>;
    const proposal = replies['schedule.read']!.payload;
    const firings = replies['event.fire']!.payload;
    const fake = await fixture(
      (frame) =>
        replies[frame.type] ??
        inspections[frame.type] ??
        (frame.type === 'orchestration.record'
          ? {
              code: 'OK',
              payload: {
                root: 'o',
                rows: [
                  {
                    ordinal: 9,
                    at: '2026-10-02T00:00:00Z',
                    run: 'o',
                    actor: 'conductor',
                    kind: 'cap_continued',
                    text: 'continued',
                    detail: null,
                    body: 'full\nrecord',
                    future: true,
                  },
                ],
                total: 12,
                limit: 5,
                through: 12,
                oldest: 9,
                more: true,
                future: true,
              },
            }
          : { code: 'OK', payload: { conflicts: [] } }),
    );
    try {
      for (const [command, type, payload, code] of cases) {
        const result = await cli(
          fake.base,
          ['--json', '--global', '--payload', '-', ...command.split(' ')],
          JSON.stringify(payload),
        ).done;
        assert.equal(
          result.code,
          code,
          command + result.stdout + result.stderr,
        );
        assert.deepEqual(
          fake.frames.at(-1)?.payload,
          type === 'schedule.save'
            ? { ...payload, overwrite: false }
            : command === 'schedule resume'
              ? { ...payload, paused: false }
              : payload,
        );
        const output = json(result.stdout);
        assert.equal(field(output, ['operation']), type);
        if (type === 'approval.answer') {
          assert.equal(field(output, ['status']), 'accepted');
          assert.equal(field(output, ['outcome', 'code']), 'OK');
          assert.equal(field(output, ['job']), 'j');
        }
        if (type === 'schedule.read')
          assert.deepEqual(field(output, ['outcome', 'payload']), proposal);
        if (type === 'event.fire')
          assert.deepEqual(field(output, ['outcome', 'payload']), firings);
        noSecrets(result.stdout + result.stderr);
      }
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        cases.map(([, type]) => type),
      );
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'approval wait reads only its returned continuation; busy decisions stand and refusal is preserved',
  { timeout: 10000 },
  async () => {
    let answers = 0;
    const fake = await fixture((frame) =>
      frame.type === 'job.status'
        ? {
            code: 'OK',
            payload: {
              id: 'j',
              state: 'FINISHED',
              outcome: {
                ending: 'ANSWERED',
                answered: true,
                text: 'continued',
              },
            },
          }
        : ++answers === 1
          ? {
              code: 'OK',
              payload: {
                id: 'a',
                state: 'allowed',
                job: 'j',
                busy: false,
                note: null,
              },
            }
          : answers === 2
            ? {
                code: 'OK',
                payload: {
                  id: 'a',
                  state: 'allowed',
                  job: null,
                  busy: true,
                  note: 'decision stands; conversation busy',
                },
              }
            : {
                code: 'CONFLICT',
                said: 'approval superseded; nothing changed',
              },
    );
    try {
      const args = [
        '--json',
        '--global',
        '--wait',
        'approval',
        'answer',
        '{"id":"a","decision":"once"}',
      ];
      const continued = await cli(fake.base, args).done;
      assert.equal(continued.code, 0, continued.stdout);
      assert.equal(
        field(json(continued.stdout), [
          'outcome',
          'payload',
          'outcome',
          'text',
        ]),
        'continued',
      );
      const busy = await cli(fake.base, args).done;
      assert.equal(busy.code, 0, busy.stdout);
      assert.equal(
        field(json(busy.stdout), ['outcome', 'payload', 'busy']),
        true,
      );
      assert.equal(field(json(busy.stdout), ['status']), 'completed');
      const refused = await cli(fake.base, args).done;
      assert.equal(refused.code, 1, refused.stdout);
      assert.equal(
        field(json(refused.stdout), ['outcome', 'said']),
        'approval superseded; nothing changed',
      );
      assert.deepEqual(
        fake.frames.map((f) => f.type),
        ['approval.answer', 'job.status', 'approval.answer', 'approval.answer'],
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'raw subscription and union mutations fail before sign-in, with no false file presence',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => {
      throw new Error('bound operation reached one-shot transport');
    });
    try {
      for (const command of [
        'job stream',
        'union enable',
        'union ready',
        'union conflict-open',
        'union conflict-resolve',
      ]) {
        const result = await cli(fake.base, [
          '--json',
          '--global',
          ...command.split(' '),
        ]).done;
        assert.equal(result.code, 2, result.stdout);
        assert.match(text(json(result.stdout), ['said']), /requires/);
      }
      assert.deepEqual(fake.paths, []);
      assert.deepEqual(fake.frames, []);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'watched submissions subscribe before running, retain early parts and trust durable status after ended pushes',
  { timeout: 10000 },
  async () => {
    let statuses = 0;
    const fake = await fixture((frame) =>
      frame.type === 'job.stream'
        ? { code: 'OK' }
        : frame.type === 'agent.run'
          ? { code: 'ACCEPTED', payload: { id: 'j' } }
          : {
              code: 'OK',
              payload: {
                id: 'j',
                state: ++statuses === 1 ? 'RUNNING' : 'FINISHED',
                ...(statuses === 1
                  ? {}
                  : {
                      outcome: {
                        ending: 'CALL_BUDGET',
                        answered: false,
                        text: 'partial durable answer',
                      },
                    }),
              },
            },
    );
    fake.onFrame((frame) => {
      if (frame.type === 'agent.run') {
        fake.push({ job: 'foreign', part: 'ANSWER', text: 'not this run' });
        fake.push({ job: 'j', part: 'THINKING', text: 'early thinking' });
        fake.push({ job: 'j', part: 'ANSWER', text: 'early answer' });
        fake.push({ job: 'j', kind: 'ended', ending: 'ANSWERED' });
      }
    });
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        '--watch',
        '--poll-ms',
        '1',
        'agent',
        'run',
        '{"agent":"a","task":"work","newConversation":false}',
      ]).done;
      assert.equal(result.code, 4, result.stderr + result.stdout);
      const output = result.stdout
        .trim()
        .split('\n')
        .map((line) => json(line));
      assert.equal(
        field(output.at(-1), ['outcome', 'payload', 'outcome', 'text']),
        'partial durable answer',
      );
      assert.equal(
        field(output.at(-1), ['outcome', 'payload', 'outcome', 'ending']),
        'CALL_BUDGET',
      );
      assert.deepEqual(
        list(output, [])
          .filter((row) => field(row, ['status']) === 'event')
          .map((row) => field(row, ['event'])),
        [
          { job: 'j', part: 'THINKING', text: 'early thinking' },
          { job: 'j', part: 'ANSWER', text: 'early answer' },
          { job: 'j', kind: 'ended', ending: 'ANSWERED' },
        ],
      );
      assert.ok(
        output.some(
          (row) =>
            field(row, ['status']) === 'accepted' &&
            field(row, ['job']) === 'j',
        ),
      );
      assert.ok(
        output.some(
          (row) =>
            field(row, ['status']) === 'running' &&
            field(row, ['operation']) === 'job.status',
        ),
      );
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['job.stream', 'agent.run', 'job.status', 'job.status'],
      );
      assert.deepEqual(fake.frames[0]?.payload, { on: true });
      assert.equal(fake.frames[1]?.payload['session'], fake.sessions[0]);
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'job watch reads an existing durable handle and refuses a subscription before any mutation',
  { timeout: 10000 },
  async () => {
    let statuses = 0,
      subscriptions = 0;
    const fake = await fixture((frame) =>
      frame.type === 'job.stream'
        ? ++subscriptions === 1
          ? { code: 'OK' }
          : { code: 'BAD_REQUEST', said: 'not subscribed' }
        : {
            code: 'OK',
            payload: {
              id: 'j',
              state: ++statuses === 1 ? 'RUNNING' : 'FINISHED',
              ...(statuses === 1
                ? {}
                : {
                    outcome: {
                      ending: 'ANSWERED',
                      answered: true,
                      text: 'whole answer',
                    },
                  }),
            },
          },
    );
    try {
      const result = await cli(fake.base, [
        '--json',
        '--global',
        '--poll-ms',
        '1',
        'job',
        'watch',
        'j',
      ]).done;
      assert.equal(result.code, 0, result.stdout + result.stderr);
      const output = result.stdout
        .trim()
        .split('\n')
        .map((line) => json(line));
      assert.deepEqual(
        list(output, []).map((row) => field(row, ['status'])),
        ['running', 'completed'],
      );
      const refused = await cli(fake.base, [
        '--json',
        '--global',
        '--watch',
        'agent',
        'run',
        '{"agent":"a","task":"work","newConversation":false}',
      ]).done;
      assert.equal(refused.code, 1, refused.stdout);
      assert.equal(
        field(json(refused.stdout), ['outcome', 'said']),
        'not subscribed',
      );
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['job.stream', 'job.status', 'job.status', 'job.stream'],
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'conversation follow retains early growth cursors, excludes foreign pushes and detaches on signals',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => ({ code: 'OK' }));
    fake.onFrame((frame) => {
      if (frame.type === 'conversation.follow') {
        fake.push({
          kind: 'conversation.appended',
          conversation: 'foreign',
          through: 99,
        });
        fake.push({
          kind: 'conversation.appended',
          conversation: 'c',
          through: 10,
        });
        fake.push({
          kind: 'conversation.appended',
          conversation: 'c',
          through: 11,
          future: 'kept',
        });
        fake.push({
          kind: 'conversation.appended',
          conversation: 'c',
          through: -1,
        });
      }
    });
    try {
      const process = cli(fake.base, [
        '--json',
        '--global',
        'conversation',
        'follow',
        'c',
      ]);
      let stopped = false;
      process.onOutput((output) => {
        if (!stopped && output.includes('"through":11')) {
          stopped = true;
          process.child.kill('SIGINT');
        }
      });
      const result = await process.done;
      assert.equal(result.code, 5, result.stdout + result.stderr);
      const output = result.stdout
        .trim()
        .split('\n')
        .map((line) => json(line));
      assert.equal(field(output, [0, 'status']), 'following');
      assert.deepEqual(
        list(output, [])
          .filter((row) => field(row, ['status']) === 'event')
          .map((row) => field(row, ['event', 'through'])),
        [10, 11],
      );
      assert.equal(field(output.at(-2), ['event', 'future']), 'kept');
      assert.equal(field(output.at(-1), ['status']), 'stopped');
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['conversation.follow'],
      );
      assert.deepEqual(fake.frames[0]?.payload, { conversation: 'c' });
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'idle observer loss and deadline end promptly without reconnecting, cancelling or replaying',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(() => ({ code: 'OK' }));
    try {
      const process = cli(fake.base, [
        '--json',
        '--global',
        'conversation',
        'follow',
        'c',
      ]);
      let dropped = false;
      process.onOutput((output) => {
        if (!dropped && output.includes('"following"')) {
          dropped = true;
          fake.disconnect();
        }
      });
      const lost = await process.done;
      assert.equal(lost.code, 5, lost.stdout + lost.stderr);
      assert.equal(
        field(json(lost.stdout.trim().split('\n').at(-1)!), ['status']),
        'unknown',
      );
      const deadline = await cli(fake.base, [
        '--json',
        '--global',
        '--timeout-ms',
        '300',
        'conversation',
        'follow',
        'c',
      ]).done;
      assert.equal(deadline.code, 5, deadline.stdout + deadline.stderr);
      assert.equal(
        field(json(deadline.stdout.trim().split('\n').at(-1)!), ['status']),
        'stopped',
      );
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['conversation.follow', 'conversation.follow'],
      );
      assert.equal(fake.sessions.length, 2);
      noSecrets(lost.stdout + deadline.stdout);
    } finally {
      await fake.close();
    }
  },
);

await test('malformed orchestration records retain the raw reply and fail after one WS read', async () => {
  const payload = {
    root: 'o',
    rows: [{ ordinal: 5, kind: 'tool_call', text: 'missing coordinates' }],
    future: true,
  };
  const fake = await fixture(() => ({ code: 'OK', payload }));
  try {
    const result = await cli(fake.base, [
      '--json',
      '--global',
      'orchestration',
      'record',
      '{"root":"o","after":4}',
    ]).done;
    assert.equal(result.code, 5, result.stdout + result.stderr);
    const output = json(result.stdout);
    assert.equal(field(output, ['status']), 'invalid-response');
    assert.deepEqual(field(output, ['outcome', 'payload']), payload);
    assert.equal(fake.frames.length, 1);
    assert.equal(fake.frames[0]!.type, 'orchestration.record');
    assert.ok(
      fake.paths.every((path) =>
        ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
          path,
        ),
      ),
    );
  } finally {
    await fake.close();
  }
});

await test('incomplete successful response families fail visibly and retain their raw WS reply', async () => {
  const cases: readonly [string[], string, unknown][] = [
    [['inbox', 'list'], 'inbox.list', { items: [{ id: 'i1' }], unread: 0 }],
    [['inbox', 'read', '{"items":["i1"]}'], 'inbox.read', { unread: 0 }],
    [
      ['orchestration', 'status', '{"id":"o"}'],
      'orchestration.status',
      {
        orchestration: { id: 'o', state: 'finished' },
        todos: [],
        messages: [],
        children: [],
      },
    ],
    [
      ['schedule', 'read', '{"text":"at noon"}'],
      'schedule.read',
      { cron: '0 0 12 * * *', destination: 'inbox' },
    ],
    [
      ['conversation', 'trajectory', '{"conversation":"c"}'],
      'conversation.trajectory',
      { entries: [], total: 30 },
    ],
    [
      ['conversation', 'context', '{"conversation":"c"}'],
      'conversation.context',
      { sent: 10, messageTokens: 10 },
    ],
    [['project', 'list'], 'project.list', [{ name: 'p' }]],
    [['job', 'list'], 'job.list', [{ state: 'FINISHED' }]],
    [['board', 'topics'], 'board.topics', { topics: [], more: false }],
    [
      ['union', 'status', '{"project":"p"}'],
      'union.status',
      { eligible: true, enabled: true },
    ],
  ];
  let payload: unknown;
  const fake = await fixture(() => ({ code: 'OK', payload }));
  try {
    for (const [args, type, damaged] of cases) {
      payload = damaged;
      const before = fake.frames.length;
      const result = await cli(fake.base, ['--json', '--global', ...args]).done;
      assert.equal(result.code, 5, result.stdout + result.stderr);
      const output = json(result.stdout);
      assert.equal(field(output, ['status']), 'invalid-response');
      assert.deepEqual(field(output, ['outcome', 'payload']), damaged);
      assert.equal(fake.frames.length, before + 1);
      assert.equal(fake.frames.at(-1)!.type, type);
    }
    assert.ok(
      fake.paths.every((path) =>
        ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
          path,
        ),
      ),
    );
  } finally {
    await fake.close();
  }
});

await test('information keeps scoped admission receipts pending and waits only for the returned answer job', async () => {
  const scope = { kind: 'project', project: 'research', includeShared: false };
  const fake = await fixture((frame) => {
    if (frame.type === 'information.upload')
      return {
        code: 'ACCEPTED',
        payload: { revision: 'r', resource: 'q', created: true },
      };
    if (frame.type === 'information.ask')
      return {
        code: 'ACCEPTED',
        payload: { job: 'answer-job', revision: 'r' },
      };
    if (frame.type === 'job.status')
      return {
        code: 'OK',
        payload: {
          id: frame.payload['job'],
          state: 'FINISHED',
          outcome: {
            ending: 'ANSWERED',
            answered: true,
            text: 'Grounded answer',
          },
        },
      };
    throw new Error('unexpected frame ' + frame.type);
  });
  try {
    const intake = {
      scope,
      requestId: 'retained-receipt',
      name: 'source',
      text: 'Source text',
    };
    const uploaded = await cli(fake.base, [
      '--json',
      '--global',
      'information',
      'upload',
      JSON.stringify(intake),
    ]).done;
    assert.equal(uploaded.code, 3, uploaded.stdout + uploaded.stderr);
    assert.equal(field(json(uploaded.stdout), ['status']), 'accepted');
    assert.equal('job' in record(json(uploaded.stdout)), false);
    assert.deepEqual(fake.frames[0]?.payload, intake);
    const question = {
      scope,
      revision: 'r',
      question: 'What does it establish?',
      maxModelCalls: 20,
    };
    const answered = await cli(fake.base, [
      '--json',
      '--global',
      '--wait',
      'information',
      'ask',
      JSON.stringify(question),
    ]).done;
    assert.equal(answered.code, 0, answered.stdout + answered.stderr);
    assert.equal(field(json(answered.stdout), ['job']), 'answer-job');
    assert.deepEqual(
      fake.frames.map((frame) => [frame.type, frame.payload]),
      [
        ['information.upload', intake],
        ['information.ask', question],
        ['job.status', { job: 'answer-job' }],
      ],
    );
    assert.ok(
      fake.paths.every((path) =>
        ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
          path,
        ),
      ),
    );
  } finally {
    await fake.close();
  }
});

await test(
  'lost orchestration acknowledgment recovers by durable key without replay or a bot turn',
  { timeout: 10000 },
  async () => {
    const key = '00000000-0000-0000-0000-000000000001';
    let commits = 0;
    const fake = await fixture((frame) => {
      if (frame.type === 'orchestration.start') {
        commits++;
        return 'drop';
      }
      if (frame.type === 'orchestration.receipt')
        return {
          code: 'OK',
          payload: { id: 'orc_original', state: 'running', requestId: key },
        };
      throw new Error('unexpected operation ' + frame.type);
    });
    try {
      const lost = await cli(fake.base, [
        'orchestration',
        'start',
        JSON.stringify({
          agent: 'caller',
          definition: 'custom',
          request: 'small task',
          requestId: key,
        }),
        '--json',
        '--global',
      ]).done;
      assert.equal(lost.code, 5);
      const reply = json(lost.stdout);
      assert.equal(field(reply, ['code']), 'SUBMISSION_UNCERTAIN');
      assert.deepEqual(field(reply, ['nextActions']), [
        { action: 'lookup-receipt', command: `orchestration receipt ${key}` },
      ]);
      const recovered = await cli(fake.base, [
        'orchestration',
        'receipt',
        key,
        '--json',
        '--global',
      ]).done;
      assert.equal(recovered.code, 0);
      assert.equal(
        field(json(recovered.stdout), ['orchestration']),
        'orc_original',
      );
      assert.equal(commits, 1);
      assert.deepEqual(
        fake.frames.map((f) => f.type),
        ['orchestration.start', 'orchestration.receipt'],
      );
      noSecrets(lost.stdout + recovered.stdout);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'orchestration wait exposes asking questions and detaches on interruption',
  { timeout: 10000 },
  async () => {
    const admin = JSON.parse(
      await readFile(
        new URL(
          '../../test-support/contracts/ws-administrative-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ) as Record<string, Outcome>;
    const original = admin['orchestration.status']!.payload as {
      orchestration: Record<string, unknown>;
      messages: unknown[];
    };
    for (const state of ['asking', 'finished', 'failed', 'cancelled']) {
      const payload = {
        ...original,
        orchestration: { ...original.orchestration, id: 'orc_one', state },
        messages: [
          {
            id: 'orm_one',
            kind: 'question',
            text: 'Which evidence scope?',
            author: 'conductor',
            createdAt: '2026-10-02T00:00:00Z',
            deliveredAt: null,
            capKind: null,
            structure: null,
          },
        ],
      };
      const fake = await fixture(() => ({ code: 'OK', payload }));
      try {
        const result = await cli(fake.base, [
          'orchestration',
          'wait',
          'orc_one',
          '--json',
          '--global',
          '--poll-ms',
          '1',
        ]).done;
        assert.equal(
          result.code,
          state === 'finished' ? 0 : state === 'failed' ? 1 : 4,
        );
        const reply = json(result.stdout);
        assert.equal(field(reply, ['state']), state);
        assert.deepEqual(field(reply, ['outcome', 'payload']), payload);
        assert.deepEqual(
          fake.frames.map((f) => f.type),
          ['orchestration.status'],
        );
      } finally {
        await fake.close();
      }
    }
    const fake = await fixture(() => ({
      code: 'OK',
      payload: {
        ...original,
        orchestration: {
          ...original.orchestration,
          id: 'orc_one',
          state: 'running',
        },
      },
    }));
    try {
      const process = cli(fake.base, [
        'orchestration',
        'wait',
        'orc_one',
        '--json',
        '--global',
        '--poll-ms',
        '1000',
      ]);
      fake.onFrame(() => setTimeout(() => process.child.kill('SIGINT'), 20));
      const result = await process.done;
      assert.equal(result.code, 5);
      const reply = json(result.stdout);
      assert.equal(field(reply, ['orchestration']), 'orc_one');
      assert.equal(field(reply, ['code']), 'INTERRUPTED');
      assert.ok(fake.frames.every((f) => f.type === 'orchestration.status'));
      assert.equal(field(reply, ['nextActions', 0, 'action']), 'inspect');
    } finally {
      await fake.close();
    }
  },
);

await test(
  'service automation uses its bearer without login, refresh or credential persistence',
  { timeout: 10000 },
  async () => {
    const directory = await mkdtemp(join(tmpdir(), 'plowshare-service-cli-'));
    const fake = await fixture(
      (frame) =>
        frame.type === 'admin.status'
          ? {
              code: 'OK',
              payload: { handle: '@service/fixture-token', serverAdmin: false },
            }
          : conversations['project.list']!,
      false,
      false,
      'pss_machine-test',
    );
    try {
      const result = await cli(fake.base, ['project', 'list', '--json'], '', {
        PLOWSHARE_TOKEN: 'pss_machine-test',
        PLOWSHARE_CONFIG_DIR: directory,
      }).done;
      assert.equal(result.code, 0, result.stdout + result.stderr);
      assert.deepEqual(fake.paths, ['/v1/auth/ticket']);
      assert.deepEqual(
        fake.frames.map((row) => row.type),
        ['admin.status', 'project.list'],
      );
      assert.deepEqual(await readdir(directory), []);
      assert.ok(!(result.stdout + result.stderr).includes('pss_machine-test'));
    } finally {
      await fake.close();
      await rm(directory, { recursive: true, force: true });
    }
  },
);

await test(
  'revoked machine credentials fail without falling back to a saved or password login',
  { timeout: 10000 },
  async () => {
    const fake = await fixture(
      () => {
        throw new Error('No frame should be submitted');
      },
      false,
      false,
      'pss_revoked-test',
    );
    try {
      const result = await cli(fake.base, ['project', 'list', '--json'], '', {
        PLOWSHARE_TOKEN: 'pss_revoked-test',
      }).done;
      assert.notEqual(result.code, 0);
      assert.deepEqual(fake.paths, ['/v1/auth/ticket']);
      assert.equal(fake.frames.length, 0);
      assert.ok(!(result.stdout + result.stderr).includes('pss_revoked-test'));
    } finally {
      await fake.close();
    }
  },
);

await test(
  'pricing list and set run once over authenticated WS and retain stale-edit failures',
  { timeout: 10000 },
  async () => {
    const fake = await fixture((frame) =>
      frame.type === 'admin.pricing.list'
        ? conversations['admin.pricing.list']!
        : {
            code: 'BAD_REQUEST',
            said: 'Pricing changed; refresh before saving',
          },
    );
    try {
      const listed = await cli(fake.base, [
        'admin',
        'pricing',
        'list',
        '--json',
      ]).done;
      assert.equal(listed.code, 0, listed.stdout + listed.stderr);
      assert.equal(
        field(json(listed.stdout), ['outcome', 'payload', 0, 'billingRoute']),
        'hosted',
      );
      const payload = {
        billingRoute: 'hosted',
        model: 'deployment',
        expectedVersion: 'boot:',
        mode: 'TOKEN',
        currency: 'USD',
        rates: { input: '0.40', output: '1.60' },
      };
      const saved = await cli(fake.base, [
        'admin',
        'pricing',
        'set',
        JSON.stringify(payload),
        '--json',
      ]).done;
      assert.equal(saved.code, 1, saved.stdout + saved.stderr);
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['admin.pricing.list', 'admin.pricing.set'],
      );
      assert.deepEqual(fake.frames[1]!.payload, payload);
      noSecrets(listed.stdout + saved.stdout);
    } finally {
      await fake.close();
    }
  },
);

await test(
  'usage statistics can be queried from the CLI over WS',
  { timeout: 10000 },
  async () => {
    const totals = {
      calls: '1',
      attempts: '1',
      active_calls: '0',
      incomplete_attempts: '0',
      unknown_cost_attempts: '0',
      input_tokens: '100',
      output_tokens: '10',
      input_tokens_known: '1',
      output_tokens_known: '1',
      costs: { USD: '0.000056' },
      usage_complete: true,
      cost_complete: true,
      complete: true,
    };
    const fake = await fixture((frame) => ({
      code: 'OK',
      payload: {
        filters: { type: frame.type, filter: frame.payload },
        totals,
        groups: [],
        cursor: null,
        health: {
          watermark: '1',
          as_of: '2026-10-04T00:00:00Z',
          capture_enabled: true,
          historical_usage: 'retained',
        },
      },
    }));
    try {
      for (const command of [
        ['usage', 'models'],
        ['usage', 'pools'],
        ['usage', 'project', '{"project":"automation"}'],
      ]) {
        const result = await cli(fake.base, [...command, '--json']).done;
        assert.equal(result.code, 0, result.stdout + result.stderr);
        assert.equal(
          field(json(result.stdout), [
            'outcome',
            'payload',
            'totals',
            'input_tokens',
          ]),
          '100',
        );
      }
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['usage.models', 'usage.pools', 'usage.project'],
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'schedule file authoring respects project selection and never replays an uncertain save',
  { timeout: 10000 },
  async () => {
    const definition = field(
      json(
        await readFile(
          new URL(
            '../../test-support/contracts/ws-administrative-fixtures.json',
            import.meta.url,
          ),
          'utf8',
        ),
      ),
      ['schedule.save', 'payload', 'definition'],
    );
    const fake = await fixture(() => 'hang');
    try {
      const result = await cli(fake.base, [
        '--json',
        '--project',
        'research',
        '--timeout-ms',
        '1000',
        'schedule',
        'save',
        JSON.stringify({ name: 'weekday', source: 'server', definition }),
      ]).done;
      assert.equal(result.code, 5, result.stdout);
      const output = json(result.stdout);
      assert.equal(field(output, ['status']), 'unknown');
      assert.equal(fake.frames.length, 1);
      assert.equal(fake.frames[0]?.type, 'schedule.save');
      assert.equal(fake.frames[0]?.payload['project'], 'research');
      assert.deepEqual(fake.frames[0]?.payload['definition'], definition);
      assert.equal(fake.frames[0]?.payload['overwrite'], false);
      assert.ok(
        fake.paths.every((path) =>
          ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket'].includes(
            path,
          ),
        ),
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'resume submits once with its durable key then waits on the same orchestration',
  { timeout: 10000 },
  async () => {
    const admin = JSON.parse(
      await readFile(
        new URL(
          '../../test-support/contracts/ws-administrative-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ) as Record<string, Outcome>;
    const original = admin['orchestration.status']!.payload as {
      orchestration: Record<string, unknown>;
    };
    const key = '00000000-0000-0000-0000-000000000123';
    const fake = await fixture((frame) =>
      frame.type === 'orchestration.resume'
        ? { code: 'OK', payload: { id: 'orc_one', state: 'running' } }
        : {
            code: 'OK',
            payload: {
              ...original,
              orchestration: {
                ...original.orchestration,
                id: 'orc_one',
                state: 'finished',
              },
            },
          },
    );
    try {
      const result = await cli(fake.base, [
        'orchestration',
        'resume',
        JSON.stringify({ id: 'orc_one', requestId: key }),
        '--wait',
        '--json',
        '--global',
        '--poll-ms',
        '1',
      ]).done;
      assert.equal(result.code, 0, result.stdout + result.stderr);
      assert.equal(field(json(result.stdout), ['orchestration']), 'orc_one');
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['orchestration.resume', 'orchestration.status'],
      );
      assert.deepEqual(fake.frames[0]!.payload, {
        id: 'orc_one',
        requestId: key,
      });
    } finally {
      await fake.close();
    }
  },
);

await test(
  'project list groups Applications and Projects while JSON retains the protocol reply',
  { timeout: 10000 },
  async () => {
    const rows = [
      { name: 'Chatbot', kind: 'application', type: 'MANAGED' },
      { name: 'Pipeline app', kind: 'application', type: 'DISJOINT' },
      { name: 'Remote files', kind: 'project' },
      { name: 'External checkout', kind: 'project', type: 'DISJOINT' },
      { name: 'personal:fixture', kind: 'personal' },
    ].map((row) => ({
      ...row,
      workspace: '/fixture/source',
      lent: [],
      exclusions: [],
      machine: null,
      members: [],
    }));
    let listed = rows;
    const fake = await fixture((frame) =>
      frame.type === 'project.list'
        ? { code: 'OK', payload: listed }
        : { code: 'OK', payload: {} },
    );
    try {
      const human = await cli(fake.base, ['project', 'list']).done;
      assert.equal(human.code, 0, human.stderr);
      assert.equal(
        human.stdout,
        'project.list: completed\nApplications:\n  Chatbot\n  Pipeline app · DISJOINT · no sync\nProjects:\n  Remote files\n  External checkout · DISJOINT · no sync\nPersonal · personal:fixture\n',
      );
      assert.ok(!human.stdout.includes('/fixture/source'));
      const machine = await cli(fake.base, ['--json', 'project', 'list']).done;
      assert.equal(machine.code, 0, machine.stderr);
      assert.deepEqual(
        field(json(machine.stdout), ['outcome', 'payload']),
        rows,
      );
      listed = [];
      const empty = await cli(fake.base, ['project', 'list']).done;
      assert.equal(empty.code, 0, empty.stderr);
      assert.ok(
        empty.stdout.includes('No Applications available to this account.'),
      );
      assert.ok(
        empty.stdout.includes('No Projects available to this account.'),
      );
    } finally {
      await fake.close();
    }
  },
);

await test(
  'Application files use server source commands without a local root or union',
  { timeout: 10000 },
  async () => {
    const document = {
      project: 'app',
      path: 'a.txt',
      text: 'old',
      revision: 'a'.repeat(64),
      writable: true,
    };
    const fake = await fixture((frame) => ({
      code: 'OK',
      payload:
        frame.type === 'application.files'
          ? { project: 'app', path: '', entries: [], more: false }
          : document,
    }));
    try {
      for (const command of [
        ['files'],
        ['read', '{"path":"a.txt"}'],
        [
          'save',
          JSON.stringify({
            path: 'a.txt',
            text: 'old',
            revision: document.revision,
          }),
        ],
      ]) {
        const result = await cli(fake.base, [
          '--json',
          '--project',
          'app',
          'application',
          ...command,
        ]).done;
        assert.equal(result.code, 0, result.stderr);
      }
      assert.deepEqual(
        fake.frames.map((row) => row.type),
        ['application.files', 'application.file.read', 'application.file.save'],
      );
      assert.equal(fake.frames[2]!.payload['revision'], document.revision);
    } finally {
      await fake.close();
    }
  },
);

await test('FileStore setup and bootstrap are shared offline operations and preserve errors without connecting', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-cli-filestores-'));
  let output = '';
  const io = {
    env: { PLOWSHARE_CONFIG_DIR: join(directory, 'config') },
    stdout: (text: string) => {
      output += text;
    },
    stderr: (text: string) => {
      output += text;
    },
    stdin: async () => '',
  };
  try {
    assert.equal(await run(['--json', 'filestore', 'status'], io), 2);
    assert.match(output, /needs-setup/);
    output = '';
    assert.equal(
      await run(['filestore', 'setup', 'apps', join(directory, 'apps')], io),
      0,
    );
    assert.match(output, /loaded/);
    output = '';
    assert.equal(await run(['filestore', 'resolve', 'apps', ''], io), 0);
    assert.match(output, /apps/);
    assert.equal(
      await run(['filestore', 'setup', 'other', join(directory, 'other')], io),
      2,
    );
    assert.match(output, /already exists/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test(
  'Application folder deployment snapshots source, excludes local state and never replays a lost reply',
  { timeout: 10000 },
  async () => {
    const folder = await mkdtemp(join(tmpdir(), 'plowshare-deployment-'));
    const requestId = '11111111-1111-1111-1111-111111111111';
    const revision = '22222222-2222-2222-2222-222222222222';
    const metadata = {
      project: 'app',
      requestId,
      expectedRevision: null,
      destination: { store: 'applications', path: 'app' },
      writableAreas: [],
    };
    await writeFile(
      join(folder, 'plowshare.json'),
      '{"version":1,"name":"app"}',
    );
    await mkdir(join(folder, 'bots'), { recursive: true });
    await writeFile(join(folder, 'bots', 'worker.md'), 'source');
    await mkdir(join(folder, '.git'));
    await writeFile(join(folder, '.git', 'config'), 'local-state');
    let loseReply = false;
    const fake = await fixture((frame) => {
      if (frame.type !== 'application.deploy') return { code: 'OK' };
      return loseReply
        ? 'drop'
        : {
            code: 'OK',
            payload: {
              project: 'app',
              requestId,
              release: { revision, digest: 'a'.repeat(64), fileCount: 2 },
            },
          };
    });
    try {
      const args = [
        '--json',
        'application',
        'deploy',
        folder,
        JSON.stringify(metadata),
      ];
      const complete = await cli(fake.base, args).done;
      assert.equal(complete.code, 0, complete.stderr);
      const uploaded = fake.frames.filter(
        (frame) => frame.type === 'application.deploy',
      );
      assert.equal(uploaded.length, 1);
      assert.deepEqual(uploaded[0]!.payload, {
        ...metadata,
        files: [
          { path: 'bots/worker.md', text: 'source' },
          { path: 'plowshare.json', text: '{"version":1,"name":"app"}' },
        ],
      });
      loseReply = true;
      const uncertain = await cli(fake.base, args).done;
      assert.notEqual(uncertain.code, 0);
      assert.equal(
        fake.frames.filter((frame) => frame.type === 'application.deploy')
          .length,
        2,
      );
      await mkdir(join(folder, '.plowshare', 'agents'), { recursive: true });
      await writeFile(
        join(folder, '.plowshare', 'agents', 'hidden.md'),
        'hidden source',
      );
      const hidden = await cli(fake.base, args).done;
      assert.notEqual(hidden.code, 0);
      assert.match(
        hidden.stdout + hidden.stderr,
        /resources belong directly in the root/,
      );
      assert.equal(
        fake.frames.filter((frame) => frame.type === 'application.deploy')
          .length,
        2,
      );
      await rm(join(folder, '.plowshare'), { recursive: true });
      await symlink(
        join(folder, 'plowshare.json'),
        join(folder, 'linked.json'),
      );
      const invalid = await cli(fake.base, args).done;
      assert.notEqual(invalid.code, 0);
      assert.equal(
        fake.frames.filter((frame) => frame.type === 'application.deploy')
          .length,
        2,
      );
    } finally {
      await fake.close();
      await rm(folder, { recursive: true, force: true });
    }
  },
);

await test('FileStore list discovers server grants rather than using the local registry', async () => {
  const fake = await fixture(() => ({
    code: 'OK',
    payload: { stores: [{ alias: 'applications', role: 'MANAGER' }] },
  }));
  try {
    const result = await cli(fake.base, ['--json', 'filestore', 'list']).done;
    assert.equal(result.code, 0, result.stderr);
    assert.match(result.stdout, /applications/);
    assert.deepEqual(
      fake.frames.map((frame) => ({
        type: frame.type,
        payload: frame.payload,
      })),
      [{ type: 'filestore.list', payload: {} }],
    );
  } finally {
    await fake.close();
  }
});

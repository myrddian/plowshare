import { nodePacketSocket } from 'plowshare-client-node';
import { field, json } from './json.test-support.js';
import { httpHandler, wireText } from './http.test-support.js';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { WebSocket, WebSocketServer } from 'ws';
import type { FileReply, FileRequest } from 'plowshare-client-ts/binding/files';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';

const main =
  process.env['PLOWSHARE_CLI_TEST_MAIN'] ??
  fileURLToPath(new URL('../build/main.js', import.meta.url));
interface Frame {
  id: string;
  type: string;
  protocol_version: string;
  payload: Record<string, unknown>;
}
async function fixture(
  script: (frame: Frame) => Outcome | Promise<Outcome>,
  ready = true,
  gitRoot?: string,
) {
  const frames: Frame[] = [],
    claims: URL[] = [],
    auth: string[] = [],
    gitRequests: string[] = [];
  let file: WebSocket | undefined,
    fileClosed = false,
    eventSession = '',
    renewals = 0;
  const replies = new Map<string, (reply: FileReply) => void>();
  const server = createServer(
    httpHandler(async (req, res) => {
      const url = new URL(req.url!, 'http://localhost');
      if (url.pathname.startsWith('/v1/auth/')) {
        auth.push(url.pathname);
        if (url.pathname === '/v1/auth/login') {
          renewals = 0;
          res.writeHead(200).end(
            JSON.stringify({
              access: 'access-secret-0',
              refresh: 'refresh-secret-0',
              mustChangePassword: false,
            }),
          );
        } else if (url.pathname === '/v1/auth/refresh') {
          assert.equal(
            req.headers.cookie,
            `ps_refresh=refresh-secret-${renewals}`,
          );
          ++renewals;
          res
            .writeHead(204, {
              'Set-Cookie': [
                `ps_access=access-secret-${renewals}; Path=/`,
                `ps_refresh=refresh-secret-${renewals}; Path=/`,
              ],
            })
            .end();
        } else {
          assert.equal(
            req.headers.authorization,
            `Bearer access-secret-${renewals}`,
          );
          res.writeHead(200).end(JSON.stringify({ ticket: 'ticket-secret' }));
        }
        return;
      }
      if (gitRoot !== undefined && url.pathname.startsWith('/v1/sync/')) {
        gitRequests.push(req.url!);
        assert.equal(
          req.headers.authorization,
          `Bearer access-secret-${renewals}`,
        );
        const input: Buffer[] = [];
        for await (const chunk of req)
          input.push(Buffer.from(chunk as Uint8Array));
        const bytes = Buffer.concat(input);
        const backend = spawn('git', ['http-backend'], {
          env: {
            ...process.env,
            GIT_PROJECT_ROOT: gitRoot,
            GIT_HTTP_EXPORT_ALL: '1',
            PATH_INFO: url.pathname.slice('/v1/sync'.length),
            REQUEST_METHOD: req.method!,
            QUERY_STRING: url.search.slice(1),
            CONTENT_TYPE: req.headers['content-type'] ?? '',
            CONTENT_LENGTH: String(bytes.length),
            REMOTE_USER: 'fixture',
          },
          stdio: ['pipe', 'pipe', 'pipe'],
        });
        const output: Buffer[] = [];
        backend.stdout.on('data', (chunk: Buffer<ArrayBufferLike>) =>
          output.push(chunk),
        );
        backend.stderr.resume();
        backend.on('close', () => {
          const response = Buffer.concat(output),
            end = response.indexOf('\r\n\r\n');
          assert.ok(end >= 0, response.toString());
          for (const line of response
            .subarray(0, end)
            .toString()
            .split('\r\n')) {
            const colon = line.indexOf(':');
            if (line.startsWith('Status:'))
              res.statusCode = Number(
                line
                  .slice(colon + 1)
                  .trim()
                  .split(' ')[0],
              );
            else if (colon > 0)
              res.setHeader(line.slice(0, colon), line.slice(colon + 1).trim());
          }
          res.end(response.subarray(end + 4));
        });
        backend.stdin.end(bytes);
        return;
      }
      res.writeHead(404).end();
    }),
  );
  const sockets = new WebSocketServer({ server });
  sockets.on('connection', (socket, req) => {
    const url = new URL(req.url!, 'http://localhost');
    assert.equal(url.searchParams.get('ticket'), 'ticket-secret');
    if (url.pathname === '/v1/files') {
      file = socket;
      claims.push(url);
      assert.equal(url.searchParams.get('session'), eventSession);
      const claimedProject = url.searchParams.get('project')!;
      assert.equal(
        claimedProject,
        frames.find((row) => row.type === 'project.attach')
          ? 'client:scope:cmVwbw'
          : 'repo',
      );
      assert.equal(url.searchParams.get('ready'), '1');
      assert.equal(url.searchParams.get('source'), '1');
      socket.on('message', (bytes) => {
        const reply = JSON.parse(wireText(bytes)) as FileReply;
        replies.get(reply.id)?.(reply);
        replies.delete(reply.id);
      });
      socket.on('close', () => {
        fileClosed = true;
      });
      if (ready)
        setTimeout(
          () =>
            socket.send(
              JSON.stringify({ ready: true, project: claimedProject }),
            ),
          20,
        );
      else setTimeout(() => socket.close(1003, 'refused'), 20);
    } else {
      assert.equal(url.pathname, '/v1/events');
      const messages = nodePacketSocket(socket);
      eventSession = url.searchParams.get('session')!;
      messages.addEventListener('message', (event) => {
        assert.equal(typeof event.data, 'string');
        const bytes = event.data as string;
        void (async () => {
          const frame = JSON.parse(bytes) as Frame;
          frames.push(frame);
          const outcome = await script(frame);
          if (socket.readyState === WebSocket.OPEN)
            messages.send(
              JSON.stringify({
                id: frame.id,
                type: frame.type,
                protocol_version: frame.protocol_version,
                payload: outcome,
              }),
            );
        })().catch((error: unknown) => {
          socket.close(
            1011,
            error instanceof Error ? error.message : 'Fixture failure',
          );
        });
      });
    }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    base: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
    frames,
    claims,
    auth,
    gitRequests,
    get fileClosed() {
      return fileClosed;
    },
    async ask(request: FileRequest): Promise<FileReply> {
      assert.ok(file);
      const answer = new Promise<FileReply>((resolve) =>
        replies.set(request.id, resolve),
      );
      file.send(JSON.stringify(request));
      return answer;
    },
    loseFiles() {
      file?.close();
    },
    loseEvents() {
      for (const socket of sockets.clients) if (socket !== file) socket.close();
    },
    async close() {
      for (const socket of sockets.clients) socket.terminate();
      await new Promise<void>((resolve) => sockets.close(() => resolve()));
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}
function cli(base: string, root: string, args: string[], infer = false) {
  const child = spawn(
    process.execPath,
    [
      main,
      '--json',
      '--url',
      base,
      ...(infer ? [] : ['--project', 'repo']),
      '--root',
      root,
      ...args,
    ],
    {
      env: {
        ...process.env,
        PLOWSHARE_HANDLE: 'fixture',
        PLOWSHARE_PASSWORD: 'password-secret',
        PLOWSHARE_PROJECT: '',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  let stdout = '',
    stderr = '';
  let observed: ((records: Record<string, unknown>[]) => void) | undefined;
  child.stdout.on('data', (chunk) => {
    stdout += String(chunk);
    if (stdout.endsWith('\n'))
      observed?.(
        stdout
          .trim()
          .split('\n')
          .map((line) => JSON.parse(line) as Record<string, unknown>),
      );
  });
  child.stderr.on('data', (chunk) => {
    stderr += String(chunk);
  });
  child.stdin.end();
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
    serving: () =>
      new Promise<void>((resolve, reject) => {
        // A refused/malformed startup must fail the test instead of keeping
        // the fixture server alive while waiting for a status that cannot arrive.
        done.then(
          (result) => reject(new Error(result.stdout + result.stderr)),
          reject,
        );
        const check = (records: Record<string, unknown>[]): void => {
          if (records.some((row) => row['status'] === 'serving')) resolve();
        };
        observed = check;
        if (stdout.endsWith('\n') && stdout)
          check(
            stdout
              .trim()
              .split('\n')
              .map((line) => JSON.parse(line) as Record<string, unknown>),
          );
      }),
  };
}
async function directory() {
  return realpath(await mkdtemp(join(tmpdir(), 'cli-presence-')));
}
function noSecrets(text: string) {
  assert.doesNotMatch(text, /(?:password|access|refresh|ticket)-secret/);
}

await test(
  'explicit persistent root streams unchanged bytes, fences symlinks and refuses commands; SIGINT withdraws both sockets',
  { timeout: 10000 },
  async () => {
    const root = await directory(),
      outside = await directory();
    const fake = await fixture(() => ({ code: 'OK' }));
    const bytes = Buffer.concat([
      Buffer.from('%PDF-1.7\n'),
      Buffer.alloc(70000, 0x42),
    ]);
    try {
      await writeFile(join(root, 'source.pdf'), bytes);
      await writeFile(join(outside, 'secret.txt'), 'outside');
      await symlink(join(outside, 'secret.txt'), join(root, 'escape'));
      const child = cli(fake.base, root, ['client', 'root']);
      await child.serving();
      assert.equal(fake.frames.length, 0);
      assert.equal(fake.claims[0]?.searchParams.get('root'), root);
      const metadata = await fake.ask({
        id: 'metadata',
        op: 'source',
        path: 'source.pdf',
      });
      assert.equal(
        metadata.source?.sha256,
        createHash('sha256').update(bytes).digest('hex'),
      );
      const first = await fake.ask({
        id: 'first',
        op: 'source',
        path: 'source.pdf',
        offset: 0,
        limit: 65536,
      });
      const last = await fake.ask({
        id: 'last',
        op: 'source',
        path: 'source.pdf',
        offset: 65536,
        limit: 65536,
      });
      assert.deepEqual(
        Buffer.concat([
          Buffer.from(first.source!.data!, 'base64'),
          Buffer.from(last.source!.data!, 'base64'),
        ]),
        bytes,
      );
      assert.equal(
        (await fake.ask({ id: 'escape', op: 'source', path: 'escape' }))
          .outcome,
        'refused',
      );
      assert.equal(
        (
          await fake.ask({
            id: 'run',
            op: 'run',
            path: '.',
            argv: ['touch', 'forbidden'],
          })
        ).outcome,
        'refused',
      );
      child.child.kill('SIGINT');
      const result = await child.done;
      assert.equal(result.code, 5, result.stdout + result.stderr);
      assert.equal(fake.fileClosed, true);
      assert.equal(
        field(json(result.stdout.trim().split('\n').at(-1)!), ['status']),
        'stopped',
      );
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
      await rm(outside, { recursive: true, force: true });
    }
  },
);

await test(
  'rooted job waits for ready and serves bytes until durable completion without a second submission',
  { timeout: 10000 },
  async () => {
    const root = await directory();
    const fake = await fixture(async (frame) => {
      if (frame.type === 'agent.run') {
        assert.equal(fake.claims.length, 1);
        assert.equal(
          frame.payload['session'],
          fake.claims[0]!.searchParams.get('session'),
        );
        assert.equal(
          (await fake.ask({ id: 'during-job', op: 'read', path: 'a.txt' })).span
            ?.lines[0],
          'served',
        );
        return { code: 'ACCEPTED', payload: { id: 'job_1' } };
      }
      return {
        code: 'OK',
        payload: {
          id: 'job_1',
          state: 'FINISHED',
          outcome: { ending: 'ANSWERED', answered: true, text: 'done' },
        },
      };
    });
    try {
      await writeFile(join(root, 'a.txt'), 'served\n');
      const result = await cli(fake.base, root, [
        '--wait',
        'agent',
        'run',
        '{"agent":"a","task":"Read","newConversation":false}',
      ]).done;
      assert.equal(result.code, 0, result.stdout + result.stderr);
      assert.deepEqual(
        fake.frames.map((frame) => frame.type),
        ['agent.run', 'job.status'],
      );
      assert.equal(fake.fileClosed, true);
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);

await test(
  'invalid roots and detached rooted jobs fail before authentication; rejected readiness never submits a job',
  { timeout: 10000 },
  async () => {
    const root = await directory(),
      fake = await fixture(() => ({ code: 'OK' }), false);
    try {
      for (const [path, args] of [
        ['relative', ['client', 'root']],
        [join(root, 'missing'), ['client', 'root']],
        [
          root,
          [
            'agent',
            'run',
            '{"agent":"a","task":"Read","newConversation":false}',
          ],
        ],
      ] as const) {
        assert.equal((await cli(fake.base, path, [...args]).done).code, 2);
        assert.equal(fake.auth.length, 0);
      }
      const result = await cli(fake.base, root, [
        '--wait',
        'agent',
        'run',
        '{"agent":"a","task":"Read","newConversation":false}',
      ]).done;
      assert.equal(result.code, 5);
      assert.equal(fake.frames.length, 0);
      assert.match(result.stdout + result.stderr, /refused/);
      assert.equal(fake.claims.length, 1, 'a rejected root is attempted once');
      assert.doesNotMatch(
        result.stdout,
        /"status":"rooted"|"status":"serving"/,
      );
      noSecrets(result.stdout + result.stderr);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);

await test(
  'file and event connection loss stop persistent roots without reconnect or hidden synchronization',
  { timeout: 10000 },
  async () => {
    for (const files of [true, false]) {
      const root = await directory(),
        fake = await fixture(() => ({ code: 'OK' }));
      try {
        const child = cli(fake.base, root, ['client', 'root']);
        await child.serving();
        if (files) fake.loseFiles();
        else fake.loseEvents();
        const result = await child.done;
        assert.equal(result.code, 5);
        assert.equal(
          field(json(result.stdout.trim().split('\n').at(-1)!), ['status']),
          'unknown',
        );
        assert.equal(fake.claims.length, 1);
        assert.equal(fake.frames.length, 0);
        assert.equal(fake.fileClosed, true);
      } finally {
        await fake.close();
        await rm(root, { recursive: true, force: true });
      }
    }
  },
);

await test(
  'sync on transfers real Git objects with rotated credentials, acknowledges the pushed commit over WS and holds its root',
  { timeout: 20000 },
  async () => {
    const root = await directory(),
      hubs = await directory(),
      hub = join(hubs, 'repo.git');
    execFileSync('git', ['init', '-q', '--bare', '-b', 'main', hub]);
    execFileSync('git', [
      '--git-dir',
      hub,
      'config',
      'http.receivepack',
      'true',
    ]);
    let enabled = false,
      refuseReady = false;
    const fake = await fixture(
      (frame) => {
        assert.equal(frame.payload['project'], 'repo');
        if (frame.type === 'union.status')
          return {
            code: 'OK',
            payload: {
              eligible: true,
              enabled,
              state: enabled ? 'LIVE' : 'OFFLINE',
              syncHidden: [],
              maxFileBytes: 1000000,
              openConflicts: 0,
              url: '/v1/sync/repo.git',
            },
          };
        if (frame.type === 'union.ready') {
          assert.equal(
            frame.payload['commit'],
            execFileSync('git', ['--git-dir', hub, 'rev-parse', 'main'])
              .toString()
              .trim(),
          );
          if (refuseReady)
            return { code: 'CONFLICT', said: 'access-secret-ready-refusal' };
          enabled = true;
        }
        if (frame.type === 'union.disable') enabled = false;
        if (frame.type === 'union.enable' || frame.type === 'union.begin')
          return { code: 'OK', payload: { url: '/v1/sync/repo.git' } };
        return { code: 'OK', payload: {} };
      },
      true,
      hubs,
    );
    try {
      await writeFile(join(root, 'a.txt'), 'union source\n');
      const child = cli(fake.base, root, [
        '--timeout-ms',
        '15000',
        'sync',
        'on',
      ]);
      await child.serving();
      assert.equal(enabled, true);
      assert.equal(
        execFileSync('git', [
          '--git-dir',
          hub,
          'show',
          'main:a.txt',
        ]).toString(),
        'union source\n',
      );
      assert.ok(
        fake.gitRequests.some((url) => url.includes('git-receive-pack')),
      );
      assert.ok(fake.frames.some((frame) => frame.type === 'union.enable'));
      assert.ok(fake.frames.some((frame) => frame.type === 'union.ready'));
      assert.doesNotMatch(
        await readFile(join(root, '.plowshare', 'sync.git', 'config'), 'utf8'),
        /secret|extraHeader/,
      );
      child.child.kill('SIGINT');
      const result = await child.done;
      assert.equal(result.code, 5, result.stdout + result.stderr);
      assert.equal(fake.fileClosed, true);
      noSecrets(result.stdout + result.stderr);
      const off = await cli(fake.base, root, [
        '--timeout-ms',
        '10000',
        'sync',
        'off',
      ]).done;
      assert.equal(off.code, 0, off.stdout + off.stderr);
      assert.equal(enabled, false);
      assert.ok(fake.frames.some((frame) => frame.type === 'union.begin'));
      assert.ok(fake.frames.some((frame) => frame.type === 'union.disable'));
      await assert.rejects(
        readFile(join(root, '.plowshare', 'sync.git', 'config')),
      );
      refuseReady = true;
      const before = fake.frames.length;
      const failed = await cli(fake.base, root, [
        '--timeout-ms',
        '10000',
        'sync',
        'on',
      ]).done;
      assert.equal(failed.code, 5, failed.stdout + failed.stderr);
      assert.ok(
        fake.frames.slice(before).some((frame) => frame.type === 'union.abort'),
      );
      assert.doesNotMatch(
        failed.stdout,
        /"status":"serving"|"status":"completed"/,
      );
      assert.equal(fake.fileClosed, true);
      noSecrets(failed.stdout + failed.stderr);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
      await rm(hubs, { recursive: true, force: true });
    }
  },
);

await test(
  'sync status, hidden and conflicts use WS; malformed status and refusals do not claim completion',
  { timeout: 10000 },
  async () => {
    const root = await directory();
    let malformed = false;
    const fake = await fixture((frame) =>
      frame.type === 'union.status'
        ? malformed
          ? { code: 'OK', payload: {} }
          : {
              code: 'OK',
              payload: {
                eligible: true,
                enabled: false,
                state: 'OFFLINE',
                syncHidden: [],
                maxFileBytes: 1000000,
                openConflicts: 0,
                url: '/v1/sync/repo.git',
              },
            }
        : frame.type === 'union.conflict.list'
          ? { code: 'OK', payload: { conflicts: [] } }
          : { code: 'CONFLICT', said: 'access-secret-do-not-print' },
    );
    try {
      assert.equal(
        (await cli(fake.base, root, ['sync', 'status']).done).code,
        0,
      );
      assert.equal(
        (await cli(fake.base, root, ['sync', 'conflicts']).done).code,
        0,
      );
      const refused = await cli(fake.base, root, ['sync', 'hidden', '.extra'])
        .done;
      assert.equal(refused.code, 5);
      noSecrets(refused.stdout + refused.stderr);
      assert.doesNotMatch(refused.stdout, /"status":"completed"/);
      malformed = true;
      const result = await cli(fake.base, root, ['sync', 'status']).done;
      assert.equal(result.code, 5);
      assert.doesNotMatch(result.stdout, /"status":"completed"/);
      assert.equal(fake.gitRequests.length, 0);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);

await test(
  'a root in a checkout infers the project and nearest marker directory',
  { timeout: 10000 },
  async () => {
    const root = await directory(),
      fake = await fixture(() => ({ code: 'OK' }));
    try {
      await mkdir(join(root, '.plowshare'));
      await writeFile(join(root, '.plowshare/project'), 'repo\n');
      const nested = join(root, 'src/integration');
      await mkdir(nested, { recursive: true });
      const child = cli(fake.base, nested, ['client', 'root'], true);
      await child.serving();
      assert.equal(fake.claims[0]?.searchParams.get('root'), root);
      child.child.kill('SIGINT');
      assert.equal((await child.done).code, 5);
      assert.equal(fake.frames.length, 0);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);

await test(
  'root manifests stay private and never create local metadata or export files',
  { timeout: 10000 },
  async () => {
    const root = await directory(),
      key = 'client:scope:cmVwbw';
    const fake = await fixture((frame) => {
      if (frame.type === 'project.list') return { code: 'OK', payload: [] };
      if (frame.type === 'project.attach')
        return {
          code: 'OK',
          payload: {
            name: key,
            displayName: 'repo',
            workspace: root,
            machine: 'test',
            lent: [],
            exclusions: [],
            members: [],
            type: 'DISJOINT',
          },
        };
      assert.equal(frame.type, 'memory.index');
      assert.equal(frame.payload['project'], key);
      return { code: 'OK', payload: [] };
    });
    try {
      const manifest = JSON.stringify({
        version: 1,
        name: 'repo',
        routing: {
          sendTo: ['notifications'],
          routeFiles: ['routes/internal.json'],
        },
        integration: { enabled: true },
      });
      await writeFile(join(root, 'plowshare'), manifest);
      const result = await cli(fake.base, root, ['memory', 'index'], true).done;
      assert.equal(result.code, 0, result.stdout + result.stderr);
      assert.deepEqual(
        fake.frames.map((row) => row.type),
        ['project.list', 'project.attach', 'memory.index'],
      );
      assert.equal(await readFile(join(root, 'plowshare'), 'utf8'), manifest);
      await assert.rejects(readFile(join(root, '.plowshare/project')), {
        code: 'ENOENT',
      });
      await assert.rejects(readFile(join(root, '.git/config')), {
        code: 'ENOENT',
      });
      assert.equal(fake.gitRequests.length, 0);
    } finally {
      await fake.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);

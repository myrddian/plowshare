import { httpHandler, wireText } from './http.test-support.js';
import { record, text, field, json } from './json.test-support.js';
import assert from 'node:assert/strict';
import test from 'node:test';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import {
  mkdtemp,
  readFile,
  readdir,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import {
  Credentials,
  LoginRequired,
  savedLoginServers,
} from 'plowshare-client-node/credentials';
import { run } from './run.js';

const main =
  process.env['PLOWSHARE_CLI_TEST_MAIN'] ??
  fileURLToPath(new URL('../build/main.js', import.meta.url));
const replies = record(
  field(
    json(
      await readFile(
        new URL(
          '../../test-support/contracts/ws-retrieval-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    ),
    ['replies'],
  ),
);
async function fixture(flagged = false) {
  let generation = 0,
    current = 'refresh-0',
    logins = 0,
    refreshes = 0,
    logout = false,
    failTicket = false,
    failLogout = false;
  const paths: string[] = [];
  let registrations = 0,
    ladderWrites = 0,
    ladder = 'duckduckgo';
  const providerUrls: string[] = [];
  const server = createServer(
    httpHandler(async (req, res) => {
      paths.push(req.url!);
      if (req.url === '/v1/auth/login') {
        logins++;
        logout = false;
        res.writeHead(200).end(
          JSON.stringify({
            access: 'access-' + generation,
            refresh: current,
            mustChangePassword: flagged,
          }),
        );
      } else if (req.url === '/v1/auth/password') {
        flagged = false;
        res.writeHead(204).end();
      } else if (req.url === '/v1/auth/refresh') {
        refreshes++;
        if (logout || req.headers.cookie !== 'ps_refresh=' + current) {
          res.writeHead(401).end();
          return;
        }
        // Yield while holding the client lock to expose competing processes.
        await new Promise((resolve) => setTimeout(resolve, 15));
        current = 'refresh-' + ++generation;
        res
          .writeHead(204, {
            'Set-Cookie': [
              `ps_access=access-${generation}; Path=/`,
              `ps_refresh=${current}; Path=/`,
            ],
          })
          .end();
      } else if (req.url === '/v1/auth/ticket') {
        if (failTicket) {
          res.writeHead(503).end();
          return;
        }
        res.writeHead(200).end(JSON.stringify({ ticket: 'ticket' }));
      } else if (req.url === '/v1/search/providers') {
        let raw = '';
        for await (const chunk of req) raw += chunk;
        providerUrls.push(text(json(raw), ['baseUrl']));
        registrations++;
        res.writeHead(200).end('{}');
      } else if (req.url === '/v1/config') {
        res
          .writeHead(200)
          .end(
            JSON.stringify([{ key: 'plowshare.search.ladder', value: ladder }]),
          );
      } else if (req.url === '/v1/config/plowshare.search.ladder') {
        ladderWrites++;
        ladder = '';
        for await (const chunk of req) ladder += chunk;
        res.writeHead(204).end();
      } else if (req.url === '/v1/auth/logout') {
        if (failLogout) {
          res.writeHead(503).end();
          return;
        }
        logout = true;
        res.writeHead(204).end();
      } else res.writeHead(404).end();
    }),
  );
  const sockets = new WebSocketServer({ server });
  sockets.on('connection', (socket) =>
    socket.on('message', (bytes) => {
      const frame = json(wireText(bytes));
      socket.send(
        JSON.stringify({
          id: field(frame, ['id']),
          type: field(frame, ['type']),
          protocol_version: field(frame, ['protocol_version']),
          payload: { code: 'OK', payload: replies[text(frame, ['type'])] },
        }),
      );
    }),
  );
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return {
    base,
    paths,
    registrations: () => registrations,
    search: () => ({ providerUrls, ladderWrites, ladder }),
    counts: () => ({ logins, refreshes }),
    fail: (value: boolean) => {
      failTicket = value;
    },
    failLogout: (value: boolean) => {
      failLogout = value;
    },
    door: {
      base,
      fetch: (url: string, init: Parameters<typeof fetch>[1]) =>
        fetch(url, init),
    },
    async close() {
      for (const socket of sockets.clients) socket.terminate();
      await new Promise<void>((resolve) => sockets.close(() => resolve()));
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}
function child(
  args: string[],
  directory: string,
): Promise<{ code: number | null; out: string; err: string }> {
  return new Promise((resolve, reject) => {
    const proc = spawn(process.execPath, [main, ...args], {
      env: {
        ...process.env,
        PLOWSHARE_CONFIG_DIR: directory,
        PLOWSHARE_HANDLE: '',
        PLOWSHARE_PASSWORD: '',
        PLOWSHARE_PROJECT: undefined,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '',
      err = '';
    proc.stdout.on('data', (chunk) => {
      out += chunk;
    });
    proc.stderr.on('data', (chunk) => {
      err += chunk;
    });
    proc.once('error', reject);
    proc.once('exit', (code) => resolve({ code, out, err }));
  });
}

await test(
  'one prompted login serves concurrent CLI processes, saves rotated tokens and logout removes them',
  { timeout: 15000 },
  async () => {
    const fake = await fixture(),
      directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'));
    let out = '';
    try {
      const code = await run(['--server', fake.base, 'login'], {
        env: { PLOWSHARE_CONFIG_DIR: directory },
        stdout: (text) => {
          out += text;
        },
        stderr: () => {},
        stdin: async () => '',
        login: async () => ({
          handle: 'operator',
          password: 'never-save-password',
        }),
      });
      assert.equal(code, 0);
      assert.ok(!out.includes('never-save-password'));
      const results = await Promise.all([
        child(['--server', fake.base, '--json', 'memory', 'index'], directory),
        child(['--url', fake.base, '--json', 'memory', 'index'], directory),
      ]);
      for (const result of results) {
        assert.equal(result.code, 0, result.err + result.out);
        assert.equal(field(json(result.out), ['status']), 'completed');
        assert.ok(!result.out.includes('refresh-'));
      }
      assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 });
      const privateDirectory = join(directory, 'credentials'),
        files = await readdir(privateDirectory);
      assert.equal(files.length, 1);
      assert.equal((await stat(privateDirectory)).mode & 0o777, 0o700);
      assert.equal(
        (await stat(join(privateDirectory, files[0]!))).mode & 0o777,
        0o600,
      );
      const saved = await readFile(join(privateDirectory, files[0]!), 'utf8');
      assert.ok(!saved.includes('never-save-password'));
      assert.equal(field(json(saved), ['tokens', 'refresh']), 'refresh-2');
      const signedOut = await child(['--url', fake.base, 'logout'], directory);
      assert.equal(signedOut.code, 0, signedOut.err);
      assert.deepEqual(await readdir(privateDirectory), []);
      const missing = await child(
        ['--url', fake.base, 'memory', 'index'],
        directory,
      );
      assert.equal(missing.code, 2);
      assert.match(missing.err, /Sign in first/);
    } finally {
      await fake.close();
      await rm(directory, { recursive: true, force: true });
    }
  },
);

await test(
  'failed ticket keeps the rotated pair and a later client never repeats login',
  { timeout: 15000 },
  async () => {
    const fake = await fixture(),
      directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'));
    try {
      const store = new Credentials(fake.base, join(directory, 'credentials'));
      await store.login(fake.door, 'operator', 'password');
      fake.fail(true);
      assert.equal(
        (await child(['--url', fake.base, 'memory', 'index'], directory)).code,
        5,
      );
      assert.equal((await store.session()).tokens.refresh, 'refresh-1');
      fake.fail(false);
      assert.equal(
        (await child(['--url', fake.base, 'memory', 'index'], directory)).code,
        0,
      );
      assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 });
      await assert.rejects(
        new Credentials(
          'http://localhost:1',
          join(directory, 'credentials'),
        ).session(),
        LoginRequired,
      );
    } finally {
      await fake.close();
      await rm(directory, { recursive: true, force: true });
    }
  },
);

await test('uncertain renewal is fenced before sending and cannot replay a spent refresh', async () => {
  const fake = await fixture(),
    directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'));
  try {
    const store = new Credentials(fake.base, directory);
    await store.login(fake.door, 'operator', 'password');
    let calls = 0;
    const broken = {
      base: fake.base,
      fetch: async (): Promise<never> => {
        calls++;
        throw new Error('reply lost');
      },
    };
    await assert.rejects(store.renew(broken), /uncertain refresh/);
    await assert.rejects(store.renew(broken), /previous token renewal/);
    await assert.rejects(store.logout(broken), /previous token renewal/);
    assert.equal(calls, 1);
    await store.login(fake.door, 'operator', 'password');
    assert.ok((await store.renew(fake.door)).refresh);
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('interactive first login changes a required initial password before saving tokens', async () => {
  const fake = await fixture(true),
    directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'));
  try {
    const code = await run(['--url', fake.base, 'login'], {
      env: { PLOWSHARE_CONFIG_DIR: directory },
      stdout: () => {},
      stderr: () => {},
      stdin: async () => '',
      login: async () => ({ handle: 'operator', password: 'initial-password' }),
      newPassword: async () => 'replacement-password',
    });
    assert.equal(code, 0);
    assert.deepEqual(fake.paths, [
      '/v1/auth/login',
      '/v1/auth/password',
      '/v1/auth/login',
    ]);
    const saved = await new Credentials(
      fake.base,
      join(directory, 'credentials'),
    ).session();
    assert.equal(saved.handle, 'operator');
    assert.equal(saved.mustChangePassword, false);
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('failed server logout retains the rotated session for a safe retry', async () => {
  const fake = await fixture(),
    directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'));
  try {
    const store = new Credentials(fake.base, directory);
    await store.login(fake.door, 'operator', 'password');
    fake.failLogout(true);
    await assert.rejects(store.logout(fake.door), /credentials were retained/);
    assert.equal((await store.session()).tokens.refresh, 'refresh-1');
    fake.failLogout(false);
    await store.logout(fake.door);
    await assert.rejects(store.session(), LoginRequired);
    assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 });
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('shared-login discovery returns only server/account metadata and skips invalid sessions', async () => {
  const fake = await fixture(),
    directory = await mkdtemp(join(tmpdir(), 'plowshare-login-discovery-'));
  try {
    assert.deepEqual(await savedLoginServers(directory), []);
    const store = new Credentials(fake.base, directory);
    await store.login(fake.door, 'operator', 'private-password');
    await writeFile(join(directory, 'a'.repeat(64) + '.json'), '{broken');
    assert.deepEqual(await savedLoginServers(directory), [
      { server: fake.base, account: 'operator' },
    ]);
    assert.ok(
      !JSON.stringify(await savedLoginServers(directory)).includes('refresh'),
    );
    await new Credentials('http://localhost:12345', directory).login(
      fake.door,
      'other',
      'private-password',
    );
    assert.equal((await savedLoginServers(directory)).length, 2);
    assert.equal(fake.counts().refreshes, 0);
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('setup consumes the temporary login and saves only the first administrator session', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-setup-'));
  const requests: { path: string; body: Record<string, string> }[] = [];
  let completed = false,
    output = '';
  const server = createServer(
    httpHandler(async (req, res) => {
      let raw = '';
      for await (const chunk of req) raw += chunk;
      const body = JSON.parse(raw) as Record<string, string>;
      requests.push({ path: req.url!, body });
      if (req.url === '/v1/auth/login') {
        const bootstrap = body['handle'] === 'admin';
        res
          .writeHead(200, {
            'Content-Type': 'application/json',
            'X-Plowshare-Setup-Required': String(bootstrap),
          })
          .end(
            JSON.stringify({
              access: bootstrap ? 'temporary-access' : 'owner-access',
              refresh: bootstrap ? null : 'owner-refresh',
              mustChangePassword: bootstrap,
            }),
          );
      } else if (
        req.url === '/v1/auth/setup' &&
        req.headers.authorization === 'Bearer temporary-access'
      ) {
        completed = true;
        res.writeHead(204).end();
      } else res.writeHead(404).end();
    }),
  );
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const code = await run(['setup', '--url', base], {
      env: { PLOWSHARE_CONFIG_DIR: directory },
      stdout: (text) => {
        output += text;
      },
      stderr: (text) => {
        output += text;
      },
      stdin: async () => '',
      login: async () => ({ handle: 'admin', password: 'temporary-secret' }),
      setup: async () => ({
        handle: 'owner',
        password: 'chosen-owner-password',
      }),
    });
    assert.equal(code, 0, output);
    assert.equal(completed, true);
    assert.deepEqual(
      requests.map((row) => row.path),
      ['/v1/auth/login', '/v1/auth/setup', '/v1/auth/login'],
    );
    assert.equal(requests[1]!.body['temporaryPassword'], 'temporary-secret');
    const files = await readdir(join(directory, 'credentials'));
    const saved = await readFile(
      join(directory, 'credentials', files[0]!),
      'utf8',
    );
    assert.equal(field(json(saved), ['handle']), 'owner');
    for (const value of [
      'temporary-secret',
      'chosen-owner-password',
      'temporary-access',
    ]) {
      assert.ok(!output.includes(value));
      assert.ok(!saved.includes(value));
    }
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(directory, { recursive: true, force: true });
  }
});

await test('search registration uses the saved administrator session with one operational HTTP mutation', async () => {
  const fake = await fixture(),
    directory = await mkdtemp(join(tmpdir(), 'plowshare-register-'));
  try {
    assert.equal(
      await run(['login', '--url', fake.base], {
        env: {
          PLOWSHARE_CONFIG_DIR: directory,
          PLOWSHARE_HANDLE: 'operator',
          PLOWSHARE_PASSWORD: 'initial-secret',
        },
        stdout: () => {},
        stderr: () => {},
        stdin: async () => '',
      }),
      0,
    );
    const result = await new Promise<{
      code: number | null;
      out: string;
      err: string;
    }>((resolve, reject) => {
      const process = spawn(
        globalThis.process.execPath,
        [
          new URL('../../deploy/docker/register-search.mjs', import.meta.url)
            .pathname,
        ],
        {
          env: {
            ...globalThis.process.env,
            PLOWSHARE_URL: fake.base,
            PLOWSHARE_CONFIG_DIR: directory,
            PLOWSHARE_SEARCH_PROVIDER_URL: fake.base,
            PLOWSHARE_TOKEN: '',
            PLOWSHARE_TOKEN_FILE: '',
          },
        },
      );
      let out = '',
        err = '';
      process.stdout.on('data', (chunk) => {
        out += chunk;
      });
      process.stderr.on('data', (chunk) => {
        err += chunk;
      });
      process.once('error', reject);
      process.once('exit', (code) => resolve({ code, out, err }));
    });
    assert.equal(result.code, 0, result.err);
    assert.equal(fake.registrations(), 1);
    assert.ok(!result.out.includes('access-'));
    assert.ok(!result.out.includes('refresh-'));
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('local search helper preserves existing rungs and does not repeat an existing ladder mutation', async () => {
  const fake = await fixture(),
    directory = await mkdtemp(join(tmpdir(), 'plowshare-local-search-'));
  try {
    await new Credentials(fake.base, join(directory, 'credentials')).login(
      fake.door,
      'owner',
      'private-password',
    );
    const invoke = () =>
      new Promise<{ code: number | null; out: string; err: string }>(
        (resolve, reject) => {
          const proc = spawn(
            process.execPath,
            [
              new URL(
                '../../deploy/docker/register-search.mjs',
                import.meta.url,
              ).pathname,
              '--enable-ladder',
            ],
            {
              env: {
                ...process.env,
                PLOWSHARE_URL: fake.base,
                PLOWSHARE_CONFIG_DIR: directory,
                PLOWSHARE_TOKEN: '',
                PLOWSHARE_TOKEN_FILE: '',
                PLOWSHARE_SEARCH_PROVIDER_URL: 'http://127.0.0.1:8100',
              },
            },
          );
          let out = '',
            err = '';
          proc.stdout.on('data', (chunk) => {
            out += chunk;
          });
          proc.stderr.on('data', (chunk) => {
            err += chunk;
          });
          proc.once('error', reject);
          proc.once('exit', (code) => resolve({ code, out, err }));
        },
      );
    for (let i = 0; i < 2; i++) {
      const result = await invoke();
      assert.equal(result.code, 0, result.err);
      assert.ok(!result.out.includes('access-'));
      assert.ok(!result.out.includes('refresh-'));
    }
    assert.deepEqual(fake.search(), {
      providerUrls: ['http://127.0.0.1:8100', 'http://127.0.0.1:8100'],
      ladderWrites: 1,
      ladder: 'duckduckgo,searxng',
    });
    assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 });
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('search registration uses the selected operator file without logging in', async () => {
  const fake = await fixture();
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-search-operator-'));
  const tokenFile = join(directory, 'operator-token');
  try {
    await writeFile(tokenFile, 'fixture-operator-token\n', { mode: 0o600 });
    const result = await new Promise<{
      code: number | null;
      out: string;
      err: string;
    }>((resolve, reject) => {
      const proc = spawn(
        process.execPath,
        [
          new URL('../../deploy/docker/register-search.mjs', import.meta.url)
            .pathname,
        ],
        {
          env: {
            ...process.env,
            PLOWSHARE_URL: fake.base,
            PLOWSHARE_SEARCH_PROVIDER_URL: fake.base,
            PLOWSHARE_CONFIG_DIR: directory,
            PLOWSHARE_TOKEN_FILE: tokenFile,
            PLOWSHARE_TOKEN: '',
          },
        },
      );
      let out = '',
        err = '';
      proc.stdout.on('data', (chunk) => {
        out += chunk;
      });
      proc.stderr.on('data', (chunk) => {
        err += chunk;
      });
      proc.once('error', reject);
      proc.once('exit', (code) => resolve({ code, out, err }));
    });
    assert.equal(result.code, 0, result.err);
    assert.equal(fake.registrations(), 1);
    assert.deepEqual(fake.counts(), { logins: 0, refreshes: 0 });
    assert.ok(!result.out.includes('fixture-operator-token'));
    assert.ok(!result.err.includes('fixture-operator-token'));
  } finally {
    await fake.close();
    await rm(directory, { recursive: true, force: true });
  }
});

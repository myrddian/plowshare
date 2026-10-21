/** Synthetic native auth fixtures. Never use production origins or credentials. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import {
  mkdtemp,
  readFile,
  writeFile,
  mkdir,
  rm,
  stat,
  access,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const root = resolve(import.meta.dirname, '..');
const { Credentials: NodeCredentials, authenticationFetch } = await import(
  process.env.PLOWSHARE_SDK_NODE_CREDENTIALS ??
    pathToFileURL(join(root, 'sdk/node/build/credentials.js')).href
);
const { connectionKey } = await import(
  process.env.PLOWSHARE_SDK_NODE_CONNECTIONS ??
    pathToFileURL(join(root, 'sdk/node/build/connections.js')).href
);
const python = process.env.PLOWSHARE_SDK_PYTHON;
assert.ok(python, 'Set PLOWSHARE_SDK_PYTHON');
const pythonProgram = `
import asyncio,json,sys
from pathlib import Path
from plowshare import Credentials,LoginRequired,connection_key
async def main():
    i=json.load(sys.stdin)
    try:
        if i['Action']=='key':
            print(json.dumps(dict(key=connection_key(i['Origin'],i['Account'] or 'alice'))))
            return
        c=Credentials.resolve(i['Origin'] or None,account=i['Account'] or None,connection=i['Connection'] or None,directory=Path(i['Root']) if i['Root'] else None) if i['Action']=='resolve' else Credentials(i['Origin'],account=i['Account'] or 'alice',directory=Path(i['Root']) if i['Root'] else None,lock_timeout=.2)
        if i['Action']=='login': await c.login(i['Account'] or 'alice','fixture-password')
        elif i['Action']=='renew': await c.renew(rejected_access=i['Rejected'] or None)
        elif i['Action']=='authenticated': await c.authenticated()
        elif i['Action']=='logout':
            await c.logout()
            print('{}')
            return
        s=await c.session()
        print(json.dumps(dict(access=s.tokens.access,handle=s.handle,key=connection_key(s.origin,s.handle))))
    except (ValueError,OSError) as e: print(json.dumps(dict(failed=True,loginRequired=isinstance(e,LoginRequired))))
asyncio.run(main())
`;
const commands = {
  python: [python, ['-c', pythonProgram]],
  go: [
    process.env.PLOWSHARE_SDK_AUTH_GO ?? join(root, 'build/sdk-auth-go'),
    [],
  ],
  dotnet: [
    process.env.PLOWSHARE_SDK_DOTNET ?? join(root, 'build/dotnet/dotnet'),
    [
      process.env.PLOWSHARE_SDK_AUTH_DOTNET ??
        join(
          root,
          'sdk/dotnet/AuthConformance/bin/Debug/net8.0/AuthConformance.dll',
        ),
    ],
  ],
  java: [
    'java',
    [
      '-cp',
      process.env.PLOWSHARE_SDK_AUTH_JAVA_CP ??
        (
          await readFile(
            join(root, 'sdk/java/build/auth-fixture-classpath.txt'),
            'utf8',
          )
        ).trim(),
      'io.aeyer.plowshare.sdk.AuthConformance',
    ],
  ],
};
const languages = Object.keys(commands);
const isolatedEnv = { ...process.env };
for (const key of [
  'PLOWSHARE_URL',
  'PLOWSHARE_ACCOUNT',
  'PLOWSHARE_HANDLE',
  'PLOWSHARE_CONNECTION',
  'PLOWSHARE_CONFIG_DIR',
  'PLOWSHARE_DESKTOP_CONFIG',
  'PLOWSHARE_TOKEN',
  'PLOWSHARE_SERVICE_TOKEN',
])
  delete isolatedEnv[key];
function invoke(language, input, environment = {}) {
  const defaults = {
    Root: '',
    Origin: '',
    Action: 'session',
    Rejected: '',
    Account: '',
    Connection: '',
  };
  return new Promise((resolve, reject) => {
    const [command, args] = commands[language];
    const child = spawn(command, args, {
      env: { ...isolatedEnv, ...environment },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let output = '',
      diagnostic = '';
    child.stdout.on('data', (data) => {
      output += data;
    });
    child.stderr.on('data', (data) => {
      diagnostic += data;
    });
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`${language} auth fixture timed out`));
    }, 15000);
    child.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code !== 0)
        reject(
          new Error(`${language} fixture failed (${code}): ${diagnostic}`),
        );
      else {
        try {
          resolve(JSON.parse(output));
        } catch {
          reject(
            new Error(`${language} fixture emitted invalid output: ${output}`),
          );
        }
      }
    });
    child.stdin.end(JSON.stringify({ ...defaults, ...input }));
  });
}
const temporary = await mkdtemp(join(tmpdir(), 'sdk-auth-parity-'));
let generation = 0,
  refreshes = 0,
  status = 204,
  refreshMode = 'normal',
  loginMode = 'normal',
  entered,
  release;
const server = createServer(async (request, response) => {
  if (request.url === '/v1/auth/login') {
    assert.equal(request.method, 'POST');
    assert.equal(request.headers['x-plowshare-token-delivery'], 'body');
    let body = '';
    for await (const chunk of request) body += chunk;
    const sent = JSON.parse(body);
    assert.ok(['alice', 'bob'].includes(sent.handle));
    assert.equal(sent.password, 'fixture-password');
    const signed = {
      access: `access-${generation}`,
      refresh: `refresh-${generation}`,
      mustChangePassword: false,
      future: { ignored: true },
    };
    if (loginMode === 'restricted') {
      signed.access = 'restricted';
      delete signed.refresh;
      signed.mustChangePassword = true;
    } else if (loginMode === 'invalid-flag')
      signed.mustChangePassword = 'false';
    else if (loginMode === 'missing-pair') delete signed.refresh;
    else if (loginMode === 'oversize') signed.access = 'x'.repeat(65537);
    response
      .writeHead(200, {
        'Content-Type': 'application/json',
        'X-Plowshare-Setup-Required':
          loginMode === 'restricted' ? 'true' : 'false',
      })
      .end(JSON.stringify(signed));
  } else if (request.url === '/v1/auth/session')
    response
      .writeHead(
        request.headers.authorization === `Bearer access-${generation}`
          ? status
          : 401,
      )
      .end();
  else if (request.url === '/v1/auth/refresh') {
    refreshes++;
    if (request.headers.cookie !== `ps_refresh=refresh-${generation}`) {
      response.writeHead(401).end();
      return;
    }
    // Every implementation must commit its renewing marker before HTTP arrives.
    const files = await Promise.all(
      liveFiles.map(async (file) => {
        try {
          return JSON.parse(await readFile(file, 'utf8'));
        } catch {
          return null;
        }
      }),
    );
    assert.ok(
      files.some((file) => file?.renewing === true),
      'HTTP refresh arrived without a persisted intent',
    );
    entered?.();
    if (release) await release;
    generation++;
    if (refreshMode === 'lost') {
      response.destroy();
      return;
    }
    if (refreshMode === 'redirect') {
      response.writeHead(307, { Location: origin + '/forbidden' }).end();
      return;
    }
    response
      .writeHead(204, {
        'Set-Cookie':
          refreshMode === 'malformed'
            ? ['ps_access=unusable']
            : [
                `ps_access=access-${generation}; Path=/`,
                `ps_refresh=refresh-${generation}; HttpOnly`,
              ],
      })
      .end();
  } else if (request.url === '/v1/auth/logout') response.writeHead(204).end();
  else {
    assert.fail('Unexpected auth request: ' + request.url);
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const filename = createHash('sha256').update(origin).digest('hex') + '.json';
let liveFiles = [];
const door = { base: origin, fetch: authenticationFetch };
function fileAt(directory, account = 'alice') {
  return join(
    directory,
    'connections',
    connectionKey(origin, account),
    'credentials',
    filename,
  );
}
function nodeAt(directory) {
  return new NodeCredentials(
    origin,
    join(directory, 'credentials'),
    undefined,
    'alice',
  );
}
async function call(
  language,
  directory,
  action = 'session',
  rejected = '',
  extra = {},
) {
  return invoke(language, {
    Root: directory,
    Origin: origin,
    Action: action,
    Rejected: rejected,
    ...extra,
  });
}
try {
  for (const candidate of [
    'https://EXAMPLE.com:443/',
    'http://example.com:80',
    'https://[2001:0db8:0:0:0:0:0:1]/',
    'http://[::ffff:192.0.2.1]:8080',
  ]) {
    for (const language of languages)
      assert.equal(
        (
          await invoke(language, {
            Root: temporary,
            Origin: candidate,
            Action: 'key',
          })
        ).key,
        connectionKey(candidate, 'alice'),
        `${language} canonical origin differs`,
      );
  }
  for (const candidate of [
    'http://127.1',
    'http://0x7f000001',
    'https://user@example.com',
    'https://example.com/path',
    'https://example.com/./',
    'https://example.com?',
    'https://é.example',
  ]) {
    for (const language of languages)
      assert.ok(
        (
          await invoke(language, {
            Root: temporary,
            Origin: candidate,
            Action: 'key',
          })
        ).failed,
        `${language} accepted ambiguous origin`,
      );
  }
  for (const language of languages) {
    const directory = join(temporary, language);
    await mkdir(directory);
    const file = fileAt(directory);
    liveFiles = [file];
    const node = nodeAt(directory);
    const signed = await call(language, directory, 'login');
    assert.ok(!signed.failed, `${language} sign-in failed`);
    assert.equal(signed.key, connectionKey(origin, 'alice'));
    assert.equal((await node.session()).tokens.access, signed.access);
    loginMode = 'restricted';
    assert.equal(
      (await call(language, directory, 'login')).access,
      signed.access,
      'restricted login replaced renewable state',
    );
    for (const invalid of ['invalid-flag', 'missing-pair', 'oversize']) {
      loginMode = invalid;
      assert.ok(
        (await call(language, directory, 'login')).failed,
        `${language} accepted malformed sign-in`,
      );
      assert.equal((await node.session()).tokens.access, signed.access);
    }
    loginMode = 'normal';
    const first = await node.renew(door, signed.access);
    assert.equal((await call(language, directory)).access, first.access);
    const next = await call(language, directory, 'renew', first.access);
    assert.equal((await node.session()).tokens.access, next.access);
    if (process.platform !== 'win32') {
      assert.equal((await stat(file)).mode & 0o777, 0o600);
      assert.equal((await stat(join(file, '..'))).mode & 0o777, 0o700);
    }
    // Each native implementation must hold the lock while refresh is in flight.
    let resume;
    release = new Promise((resolve) => {
      resume = resolve;
    });
    const started = new Promise((resolve) => {
      entered = resolve;
    });
    const owner = call(language, directory, 'renew', next.access);
    await Promise.race([
      started,
      owner.then((result) => {
        throw new Error(
          `${language} owner finished before entering refresh: ${JSON.stringify(result)}`,
        );
      }),
    ]);
    const contender = node.renew(door, next.access);
    const oldCount = refreshes;
    resume();
    const [owned, contended] = await Promise.all([owner, contender]);
    assert.equal(owned.access, contended.access);
    assert.equal(refreshes, oldCount);
    release = undefined;
    entered = undefined;
    // Status errors never spend a refresh token. Explicit 401 permits one.
    for (const unavailable of [403, 503, 302]) {
      status = unavailable;
      const before = refreshes;
      assert.ok((await call(language, directory, 'authenticated')).failed);
      assert.equal(refreshes, before);
      assert.equal((await node.session()).tokens.access, owned.access);
    }
    status = 401;
    const before = refreshes;
    assert.ok((await call(language, directory, 'authenticated')).failed);
    assert.equal(refreshes, before + 1);
    status = 204;
    assert.equal(
      (await call(language, directory, 'authenticated')).access,
      `access-${generation}`,
    );
    // Resolve selection from exactly the same native config Node writes.
    await writeFile(
      join(directory, 'config.json'),
      JSON.stringify({
        version: 1,
        connections: [
          {
            key: signed.key,
            name: 'Home',
            server: origin,
            account: 'alice',
            reconnect: true,
          },
        ],
        selected: signed.key,
      }),
    );
    assert.equal(
      (await invoke(language, { Root: directory, Action: 'resolve' })).handle,
      'alice',
    );
    assert.ok(
      (
        await call(language, directory, 'resolve', '', {
          Account: 'bob',
          Connection: 'Home',
        })
      ).failed,
    );
    for (const environment of [
      {
        PLOWSHARE_CONFIG_DIR: directory,
        PLOWSHARE_DESKTOP_CONFIG: join(temporary, 'must-not-use'),
      },
      { PLOWSHARE_DESKTOP_CONFIG: directory },
    ])
      assert.equal(
        (await invoke(language, { Action: 'resolve' }, environment)).handle,
        'alice',
      );
    for (const environment of [
      { PLOWSHARE_URL: '' },
      { PLOWSHARE_ACCOUNT: '' },
      { PLOWSHARE_CONNECTION: '' },
    ])
      assert.ok(
        (
          await invoke(
            language,
            { Root: directory, Action: 'resolve' },
            environment,
          )
        ).failed,
      );
    const config = await readFile(join(directory, 'config.json'), 'utf8');
    await writeFile(
      join(directory, 'config.json'),
      config.replace('"reconnect":true', '"reconnect":null'),
    );
    assert.ok(
      (await invoke(language, { Root: directory, Action: 'resolve' })).failed,
    );
    await writeFile(join(directory, 'config.json'), config);
    await call(language, directory, 'login', '', { Account: 'bob' });
    assert.ok(
      (await call(language, directory, 'resolve')).failed,
      'multiple accounts inferred',
    );
    assert.equal(
      (await call(language, directory, 'resolve', '', { Account: 'alice' }))
        .handle,
      'alice',
    );
    await rm(join(directory, 'connections', connectionKey(origin, 'bob')), {
      recursive: true,
    });
    // Logout markers prevent resurrection from a preserved legacy copy.
    const old = await readFile(file);
    assert.ok(!(await call(language, directory, 'logout')).failed);
    await mkdir(join(directory, 'credentials'));
    await writeFile(join(directory, 'credentials', filename), old);
    assert.ok((await call(language, directory)).failed);
    await assert.rejects(node.session());
    // Migration is a move under both locks, and never copies a refresh token.
    await rm(join(directory, 'connections'), { recursive: true });
    assert.equal((await call(language, directory)).handle, 'alice');
    await assert.rejects(access(join(directory, 'credentials', filename)));
    assert.equal(
      (await node.session()).tokens.access,
      JSON.parse(old).tokens.access,
    );
    // Stale locks remain untouched and cannot authorize another rotation.
    await mkdir(file + '.lock');
    const count = refreshes;
    assert.ok((await call(language, directory, 'renew')).failed);
    assert.equal(refreshes, count);
    await access(file + '.lock');
    await rm(file + '.lock', { recursive: true });
    // Malformed/lost/redirected refresh replies fence every subsequent client.
    for (const mode of ['malformed', 'lost', 'redirect']) {
      await call(language, directory, 'login');
      refreshMode = mode;
      const count = refreshes;
      assert.ok((await call(language, directory, 'renew')).failed);
      assert.equal(refreshes, count + 1);
      assert.equal(JSON.parse(await readFile(file, 'utf8')).renewing, true);
      for (const other of languages)
        assert.ok((await call(other, directory, 'renew', 'older')).failed);
      await assert.rejects(node.session());
      assert.equal(refreshes, count + 1);
      refreshMode = 'normal';
    }
    console.log(
      `${language}: shared keys, locks, migration/logout, status and ambiguous-refresh fences passed`,
    );
  }
  // All languages contest the very same rejected pair. Every one reloads and
  // observes the single replacement rather than rotating a cached refresh.
  const directory = join(temporary, 'all');
  await mkdir(directory);
  liveFiles = [fileAt(directory)];
  const first = await call('python', directory, 'login');
  const before = refreshes;
  const results = await Promise.all([
    ...languages.map((language) =>
      call(language, directory, 'renew', first.access),
    ),
    nodeAt(directory).renew(door, first.access),
  ]);
  assert.ok(
    results.every((result) => result.access === `access-${generation}`),
  );
  assert.equal(refreshes, before + 1);
  console.log(
    'Node/Python/Go/.NET/Java: concurrent renewal consumed one refresh token',
  );
} finally {
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
  await rm(temporary, { recursive: true, force: true });
}

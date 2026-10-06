import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { TestContext } from 'node:test';
import {
  mkdtemp,
  mkdir,
  readFile,
  writeFile,
  rm,
  stat,
  symlink,
  realpath,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { execFile } from 'node:child_process';
import {
  Connections,
  connectionDirectory,
  resolveConnection,
  decodeConnectionConfiguration,
  manageConnections,
} from 'plowshare-client-node/connections';
import {
  Credentials,
  savedLoginServers,
} from 'plowshare-client-node/credentials';
import { personalDirectory } from 'plowshare-client-node/personal';
import { ConnectionConfig } from './connection-config.ts';
import { ProjectConfig } from './project-config.ts';
import { JobJournal } from './job-store.ts';

async function directory(t: TestContext) {
  const root = await mkdtemp(join(tmpdir(), 'plowshare-named-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}
const first = {
  name: 'Home account',
  server: 'https://one.example',
  account: 'alice',
  reconnect: true,
};
const second = { ...first, name: 'Other account', account: 'bob' };

await test('shared registry isolates identities, preserves storage across rename/remove, and rejects rebinding', async (t) => {
  const root = await directory(t),
    registry = new Connections(root);
  const a = await registry.put(first),
    b = await registry.put(second);
  const c = await registry.put({
    ...first,
    name: 'Other server',
    server: 'https://two.example',
  });
  assert.equal(new Set([a.key, b.key, c.key]).size, 3);
  await assert.rejects(
    registry.put({ ...second, name: first.name }),
    /another server\/account/,
  );
  await assert.rejects(
    registry.put({ ...first, name: 'Alias' }),
    /already has a name/,
  );
  await mkdir(connectionDirectory(a.server, a.account, root), {
    recursive: true,
  });
  const data = join(
    connectionDirectory(a.server, a.account, root),
    'retained.txt',
  );
  await writeFile(data, 'Keep this identity');
  await registry.rename(a.name, 'Renamed');
  assert.equal((await registry.select('Renamed')).key, a.key);
  await registry.remove('Renamed');
  assert.equal((await registry.load()).selected, undefined);
  assert.equal(await readFile(data, 'utf8'), 'Keep this identity');
  const restored = await registry.put({ ...first, name: 'Restored' });
  assert.equal(restored.key, a.key);
  assert.equal((await stat(registry.path)).mode & 0o777, 0o600);
  assert.equal((await stat(root)).mode & 0o777, 0o700);
});

await test('selection precedence is explicit and conflicts fail instead of changing identity', async (t) => {
  const root = await directory(t),
    registry = new Connections(root);
  const a = await registry.put(first),
    b = await registry.put(second);
  assert.equal((await resolveConnection(registry, {}, {}))?.key, b.key);
  assert.equal(
    (
      await resolveConnection(
        registry,
        { name: first.name },
        { PLOWSHARE_CONNECTION: second.name },
      )
    )?.key,
    a.key,
  );
  assert.equal(
    (
      await resolveConnection(
        registry,
        {},
        { PLOWSHARE_CONNECTION: first.name },
      )
    )?.key,
    a.key,
  );
  assert.equal(
    await resolveConnection(registry, { server: 'https://three.example' }, {}),
    undefined,
  );
  await assert.rejects(
    resolveConnection(
      registry,
      { name: first.name, account: second.account },
      {},
    ),
    /conflicts/,
  );
  await assert.rejects(
    resolveConnection(registry, { name: 'Missing' }, {}),
    /Unknown/,
  );
  await assert.rejects(
    resolveConnection(
      registry,
      { name: first.name },
      { PLOWSHARE_URL: 'https://two.example' },
    ),
    /conflicts/,
  );
});

await test('configuration rejects unsupported fields, malformed identities, keys, duplicates and selections', async (t) => {
  const registry = new Connections(await directory(t));
  const row = await registry.put(first);
  for (const malformed of [
    { version: 2, connections: [row] },
    { version: 1, connections: [row], password: 'secret' },
    { version: 1, connections: [{ ...row, key: '../escape' }] },
    {
      version: 1,
      connections: [{ ...row, server: 'https://alice:secret@one.example' }],
    },
    { version: 1, connections: [{ ...row, account: '../alice' }] },
    { version: 1, connections: [{ ...row, reconnect: 'true' }] },
    { version: 1, connections: [row, row] },
    { version: 1, selected: 'missing', connections: [row] },
  ])
    assert.throws(() => decodeConnectionConfiguration(malformed));
  await writeFile(registry.path, '{broken');
  await assert.rejects(registry.put(second));
  assert.equal(await readFile(registry.path, 'utf8'), '{broken');
});

await test('independent processes serialize config writes without losing another connection', async (t) => {
  const root = await directory(t);
  const run = promisify(execFile);
  const module = new URL('../../sdk/node/build/connections.js', import.meta.url)
    .href;
  const script = `import { Connections } from ${JSON.stringify(module)}; const [root,name,account] = process.argv.slice(1); await new Connections(root).put({name,account,server:'https://one.example',reconnect:true});`;
  await Promise.all(
    ['alice', 'bob', 'carol'].map((account) =>
      run(process.execPath, [
        '--input-type=module',
        '-e',
        script,
        root,
        account,
        account,
      ]),
    ),
  );
  assert.equal((await new Connections(root).load()).connections.length, 3);
});

await test('account-scoped tokens rotate under one lock, selected logout leaves other accounts intact', async (t) => {
  const root = await directory(t),
    legacy = join(root, 'credentials');
  const stores = ['alice', 'bob'].map(
    (account) => new Credentials(first.server, legacy, undefined, account),
  );
  const current = new Map<string, string>();
  let renewals = 0;
  const door = {
    base: first.server,
    fetch: async (url: string, init?: RequestInit) => {
      if (url.endsWith('/login')) {
        const body: unknown = JSON.parse(
          typeof init?.body === 'string' ? init.body : 'null',
        );
        if (
          !body ||
          typeof body !== 'object' ||
          !('handle' in body) ||
          typeof body.handle !== 'string'
        )
          throw new Error('Invalid fixture login.');
        const token = body.handle + '-0';
        current.set(body.handle, token);
        return new Response(
          JSON.stringify({
            access: token,
            refresh: token,
            mustChangePassword: false,
          }),
          { status: 200 },
        );
      }
      if (url.endsWith('/logout')) return new Response(null, { status: 204 });
      const headers = new Headers(init?.headers),
        token = headers.get('Cookie')?.replace('ps_refresh=', '');
      const owner = [...current].find(([, value]) => value === token)?.[0];
      assert.ok(owner, 'A spent or foreign refresh token must never be used.');
      const next = owner + '-' + ++renewals;
      current.set(owner, next);
      const cookies = new Headers();
      cookies.append('Set-Cookie', `ps_access=${next}; Path=/`);
      cookies.append('Set-Cookie', `ps_refresh=${next}; Path=/`);
      return new Response(null, { status: 204, headers: cookies });
    },
  };
  const alice = stores[0],
    bob = stores[1];
  assert.ok(alice && bob);
  await alice.login(door, 'alice', 'fixture-password');
  await bob.login(door, 'bob', 'fixture-password');
  await Promise.all([
    alice.renew(door),
    new Credentials(first.server, legacy, undefined, 'alice').renew(door),
  ]);
  await assert.rejects(
    new Credentials(first.server, legacy).session(),
    /Multiple accounts/,
  );
  await assert.rejects(
    alice.login(door, 'bob', 'fixture-password'),
    /conflicts/,
  );
  // A stale legacy client must not make the selected account silently reappear after logout.
  await mkdir(legacy, { recursive: true });
  await new Credentials(first.server, legacy).login(
    {
      base: first.server,
      fetch: async () =>
        new Response(
          JSON.stringify({
            access: 'stale-access',
            refresh: 'stale-refresh',
            mustChangePassword: false,
          }),
        ),
    },
    'alice',
    'fixture-password',
  );
  await alice.logout(door);
  await assert.rejects(alice.session(), /Sign in first/);
  assert.equal((await bob.session()).handle, 'bob');
  assert.deepEqual(await savedLoginServers(legacy), [
    { server: first.server, account: 'bob' },
  ]);
});

await test('proven legacy sessions migrate atomically and foreign sessions stay untouched', async (t) => {
  const root = await directory(t),
    legacy = join(root, 'credentials');
  const door = {
    base: first.server,
    fetch: async () =>
      new Response(
        JSON.stringify({
          access: 'fixture-access',
          refresh: 'fixture-refresh',
          mustChangePassword: false,
        }),
      ),
  };
  const original = new Credentials(first.server, legacy);
  await original.login(door, 'alice', 'fixture-password');
  const bob = new Credentials(first.server, legacy, undefined, 'bob');
  await assert.rejects(bob.session(), /Sign in first/);
  assert.equal((await original.session()).handle, 'alice');
  const alice = new Credentials(first.server, legacy, undefined, 'alice');
  assert.equal((await alice.session()).handle, 'alice');
  assert.equal(
    (await new Credentials(first.server, legacy).session()).handle,
    'alice',
  );
});

await test('Personal migration retains content and actual Git history, and ambiguous ownership changes nothing', async (t) => {
  const home = await directory(t),
    legacy = join(home, '.plowshare', 'personal');
  await mkdir(join(legacy, '.plowshare'), { recursive: true });
  await writeFile(
    join(legacy, '.plowshare', 'personal.json'),
    JSON.stringify({
      server: first.server,
      account: first.account,
      project: 'personal:alice',
    }),
  );
  await writeFile(join(legacy, 'notes.txt'), 'Preserved notes');
  const run = promisify(execFile);
  await run('git', ['init', legacy]);
  await run('git', ['-C', legacy, 'add', '.']);
  await run('git', [
    '-C',
    legacy,
    '-c',
    'user.name=Fixture',
    '-c',
    'user.email=fixture@example.test',
    'commit',
    '-m',
    'Fixture content',
  ]);
  const before = (await run('git', ['-C', legacy, 'rev-parse', 'HEAD'])).stdout;
  const moved = await personalDirectory(
    first.server,
    first.account,
    'personal:alice',
    home,
  );
  assert.equal(
    (await run('git', ['-C', moved, 'rev-parse', 'HEAD'])).stdout,
    before,
  );
  assert.equal(
    await readFile(join(moved, 'notes.txt'), 'utf8'),
    'Preserved notes',
  );
  await mkdir(legacy);
  await writeFile(join(legacy, 'unclaimed.txt'), 'Untouched');
  const bob = await personalDirectory(
    first.server,
    'bob',
    'personal:bob',
    home,
  );
  assert.equal(
    bob,
    await realpath(
      join(
        connectionDirectory(first.server, 'bob', join(home, '.plowshare')),
        'personal',
      ),
    ),
  );
  assert.equal(
    await readFile(join(legacy, 'unclaimed.txt'), 'utf8'),
    'Untouched',
  );
});

await test('symlinked configuration boundaries cannot inherit state outside the selected root', async (t) => {
  const root = await directory(t),
    outside = await directory(t);
  await symlink(outside, join(root, 'connections'));
  await assert.rejects(
    new Credentials(
      first.server,
      join(root, 'credentials'),
      undefined,
      'alice',
    ).session(),
    /real directories/,
  );
  await symlink(join(outside, 'config.json'), join(root, 'config.json'));
  await assert.rejects(new Connections(root).load(), /regular file/);
});

await test('desktop imports legacy metadata once and keeps scoped bookmarks, receipts and drafts separate', async (t) => {
  const root = await directory(t);
  await writeFile(
    join(root, 'desktop-connection.json'),
    JSON.stringify({
      version: 1,
      server: first.server,
      account: first.account,
      reconnect: true,
    }),
  );
  const config = new ConnectionConfig(root);
  assert.equal((await config.load())?.account, first.account);
  const named = (await config.list())[0];
  assert.ok(named);
  await config.remove(named.name);
  assert.equal(
    await config.load(),
    undefined,
    'Removed entries must never be resurrected from legacy preferences.',
  );
  const projects = new ProjectConfig(root),
    jobs = new JobJournal(root);
  const bookmark = {
    server: first.server,
    account: 'alice',
    name: 'Project',
    path: '/fixture/project',
    machine: 'fixture',
    enabled: true,
  };
  await Promise.all([
    projects.put(bookmark),
    new ProjectConfig(root).put({ ...bookmark, name: 'Second' }),
    projects.put({ ...bookmark, account: 'bob' }),
  ]);
  assert.equal((await projects.list(first.server, 'alice')).length, 2);
  assert.equal((await projects.list(first.server, 'bob')).length, 1);
  await jobs.save(first.server, 'alice', [
    { id: 'uncertain', conversation: 'c', agent: 'bot' },
  ]);
  assert.deepEqual(await jobs.load(first.server, 'bob'), []);
  const preference = { selected: 'c', scope: '', drafts: { c: 'Alice draft' } };
  await config.savePreferences(first.server, 'alice', preference);
  assert.deepEqual(
    await new ConnectionConfig(root).loadPreferences(first.server, 'alice'),
    preference,
  );
  assert.equal(await config.loadPreferences(first.server, 'bob'), undefined);
  await manageConnections(new Connections(root), [
    'add',
    'Name with spaces',
    first.server,
    'bob',
  ]);
  assert.equal((await config.load())?.name, 'Name with spaces');
});

await test("simultaneous desktop clients retain each other's receipts and retire only observed work", async (t) => {
  const root = await directory(t),
    one = new JobJournal(root),
    two = new JobJournal(root);
  const a = { id: 'a', conversation: 'ca', agent: 'bot' },
    b = { id: 'b', conversation: 'cb', agent: 'bot' };
  await Promise.all([
    one.save(first.server, 'alice', [a]),
    two.save(first.server, 'alice', [b]),
  ]);
  assert.deepEqual(
    (await new JobJournal(root).load(first.server, 'alice'))
      .map((row) => row.id)
      .sort(),
    ['a', 'b'],
  );
  await one.save(first.server, 'alice', []);
  assert.deepEqual(await new JobJournal(root).load(first.server, 'alice'), [b]);
  assert.deepEqual(await one.load(first.server, 'bob'), []);
});

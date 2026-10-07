import test from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  readFile,
  rm,
  writeFile,
  symlink,
  realpath,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FileStores, localStorePath } from 'plowshare-client-node/filestores';
import {
  Connections,
  connectionDirectory,
} from 'plowshare-client-node/connections';

async function fixture(t: test.TestContext) {
  const root = await realpath(
    await mkdtemp(join(tmpdir(), 'plowshare-filestores-')),
  );
  t.after(() => rm(root, { recursive: true, force: true }));
  const registry = new FileStores(join(root, 'config'));
  return { root, registry, connections: new Connections(registry.directory) };
}
await test('missing configuration needs setup without inventing a directory; explicit initialization saves its bootstrap default', async (t) => {
  const { root, registry, connections } = await fixture(t);
  assert.equal((await registry.load()).status, 'needs-setup');
  await assert.rejects(readFile(registry.path), { code: 'ENOENT' });
  const storage = join(root, 'applications');
  const state = await registry.initialize({
    alias: 'applications',
    root: storage,
  });
  assert.equal(state.status, 'loaded');
  assert.equal(state.defaultStore, 'applications');
  assert.deepEqual((await connections.load()).fileStoreDefault, {
    alias: 'applications',
    root: storage,
  });
  assert.equal(
    await localStorePath(registry, 'store:applications'),
    state.stores[0]?.root,
  );
});
await test('configured bootstrap works across simultaneous clients and retains named connection state', async (t) => {
  const { root, registry, connections } = await fixture(t);
  const one = await connections.put({
    name: 'one',
    server: 'https://one.example',
    account: 'alice',
    reconnect: true,
  });
  const two = await connections.put({
    name: 'two',
    server: 'https://two.example',
    account: 'alice',
    reconnect: true,
  });
  await connections.setFileStoreDefault({
    alias: 'apps',
    root: join(root, 'apps'),
  });
  const states = await Promise.all([
    registry.load(),
    new FileStores(registry.directory).load(),
  ]);
  assert.ok(states.every((state) => state.status === 'loaded'));
  await connections.select('one');
  const saved = await connections.load();
  assert.equal(saved.selected, one.key);
  assert.equal(saved.connections.length, 2);
  assert.ok(saved.fileStoreDefault);
  assert.notEqual(
    connectionDirectory(one.server, one.account, registry.directory),
    connectionDirectory(two.server, two.account, registry.directory),
  );
});
await test('existing JavaScript registry supports multiple roots and reload observes edits and recreates missing directories', async (t) => {
  const { root, registry } = await fixture(t);
  await registry.load();
  const apps = join(root, 'apps'),
    data = join(root, 'data');
  await writeFile(
    registry.path,
    `// Host-owned JavaScript definition\nexport default { version: 1, defaultStore: 'apps', fileStores: { apps: { root: ${JSON.stringify(apps)} }, data: { root: ${JSON.stringify(data)} } } };`,
  );
  let state = await registry.load();
  assert.equal(state.status, 'loaded');
  assert.equal(state.stores.length, 2);
  await rm(apps, { recursive: true });
  assert.equal((await registry.load()).status, 'loaded');
  await writeFile(
    registry.path,
    `export default {version:1, defaultStore:'data', fileStores:{data:{root:${JSON.stringify(data)}}}};`,
  );
  state = await registry.load();
  assert.equal(state.defaultStore, 'data');
  assert.equal(state.stores.length, 1);
});
await test('invalid definitions preserve both files and bootstrap policy without partial directory creation or fallback', async (t) => {
  const { root, registry, connections } = await fixture(t);
  await connections.setFileStoreDefault({
    alias: 'fallback',
    root: join(root, 'fallback'),
  });
  await mkdir(registry.directory, { recursive: true });
  const source = `export default {version:1, defaultStore:'valid',fileStores:{valid:{root:${JSON.stringify(join(root, 'valid'))}},bad:{root:'relative'}}};`;
  await writeFile(registry.path, source);
  const before = await readFile(connections.path, 'utf8');
  assert.equal((await registry.load()).status, 'unavailable');
  assert.equal(await readFile(registry.path, 'utf8'), source);
  assert.equal(await readFile(connections.path, 'utf8'), before);
  await assert.rejects(readFile(join(root, 'valid')), { code: 'ENOENT' });
  await assert.rejects(readFile(join(root, 'fallback')), { code: 'ENOENT' });
  await writeFile(registry.path, 'export default ???');
  assert.equal((await registry.load()).status, 'unavailable');
});
await test('setup never replaces an existing registry; invalid field types and unsupported grants fail before writes', async (t) => {
  const { root, registry } = await fixture(t);
  await registry.initialize({ alias: 'apps', root: join(root, 'apps') });
  const source = await readFile(registry.path, 'utf8');
  await assert.rejects(
    registry.initialize({ alias: 'other', root: join(root, 'other') }),
    /already exists/,
  );
  assert.equal(await readFile(registry.path, 'utf8'), source);
  for (const bad of [
    "export default {version:1,defaultStore:'apps',fileStores:{apps:{root:null}}}",
    "export default {version:1,defaultStore:'apps',fileStores:{apps:{root:'/fixture',write:true}}}",
  ]) {
    await writeFile(registry.path, bad);
    assert.equal((await registry.load()).status, 'unavailable');
  }
});
await test('directory failures and symlink configuration remain actionable and preserved', async (t) => {
  const { root, registry } = await fixture(t);
  const blocked = join(root, 'file');
  await writeFile(blocked, 'preserve');
  const state = await registry.initialize({ alias: 'apps', root: blocked });
  assert.equal(state.status, 'unavailable');
  assert.match(state.message, /apps is unavailable/);
  assert.equal(await readFile(blocked, 'utf8'), 'preserve');
  const source = await readFile(registry.path, 'utf8');
  const target = join(root, 'target.js');
  await writeFile(target, source);
  await rm(registry.path);
  await symlink(target, registry.path);
  assert.equal((await registry.load()).status, 'unavailable');
  assert.equal(await readFile(target, 'utf8'), source);
});
await test('alias resolution contains canonical paths and rejects traversal, foreign aliases and symlink escapes', async (t) => {
  const { root, registry } = await fixture(t);
  const storage = join(root, 'apps');
  await registry.initialize({ alias: 'apps', root: storage });
  await mkdir(join(storage, 'chatbot'));
  assert.equal(
    await localStorePath(registry, 'store:apps/chatbot'),
    join(storage, 'chatbot'),
  );
  assert.equal(
    await localStorePath(registry, '/explicit/path'),
    '/explicit/path',
  );
  for (const bad of [
    '../outside',
    '/outside',
    'chatbot/../outside',
    'chatbot\\outside',
    './chatbot',
  ])
    await assert.rejects(registry.resolve('apps', bad), /relative FileStore/);
  await assert.rejects(registry.resolve('foreign', ''), /Unknown local/);
  await symlink(root, join(storage, 'escape'));
  await assert.rejects(registry.resolve('apps', 'escape'), /escapes/);
});

await test('blocking JavaScript is refused within a bounded worker and preserves the definition', async (t) => {
  const { registry } = await fixture(t);
  await registry.load();
  const source = 'while (true) {}';
  await writeFile(registry.path, source);
  const state = await registry.load();
  assert.equal(state.status, 'unavailable');
  assert.match(state.message, /two seconds/);
  assert.equal(await readFile(registry.path, 'utf8'), source);
});

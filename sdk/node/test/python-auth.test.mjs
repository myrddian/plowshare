/** Native Python and Node must coordinate single-use refresh through one store. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { authenticationFetch, Credentials } from '../build/credentials.js';
import { connectionKey } from '../build/connections.js';

const python = process.env.PLOWSHARE_SDK_PYTHON;
assert.ok(python, 'Run through sdkCheck or set PLOWSHARE_SDK_PYTHON');
const program = `
import asyncio, json, sys
from pathlib import Path
from plowshare import Credentials, LoginRequired, connection_key
async def main():
    root, origin, action, rejected = json.load(sys.stdin)
    store = Credentials(origin, account="alice", directory=Path(root))
    if action == "login":
        await store.login("alice", "fixture-password")
    elif action == "renew":
        await store.renew(rejected_access=rejected)
    elif action == "logout":
        await store.logout()
        print(json.dumps({"loggedOut": True}))
        return
    try:
        session = await store.session()
        print(json.dumps({"handle": session.handle, "access": session.tokens.access, "key": connection_key(origin, session.handle)}))
    except LoginRequired:
        print(json.dumps({"loginRequired": True}))
asyncio.run(main())
`;
function invoke(root, origin, action, rejected = null) {
  return new Promise((resolve, reject) => {
    const child = spawn(python, ['-c', program], {
      env: process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let output = '',
      diagnostic = '';
    child.stdout.on('data', (value) => {
      output += value;
    });
    child.stderr.on('data', (value) => {
      diagnostic += value;
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) reject(new Error(`Python fixture failed: ${diagnostic}`));
      else {
        try {
          resolve(JSON.parse(output));
        } catch (error) {
          reject(error);
        }
      }
    });
    child.stdin.end(JSON.stringify([root, origin, action, rejected]));
  });
}

await test(
  'Python/Node share identity, migration fences, locks and rotated pairs',
  { timeout: 20000 },
  async (t) => {
    const root = await mkdtemp(join(tmpdir(), 'sdk-native-auth-'));
    let generation = 0,
      refreshes = 0,
      holdRefresh;
    let started;
    const server = createServer(async (request, response) => {
      request.resume();
      if (request.url === '/v1/auth/login') {
        response.writeHead(200, { 'Content-Type': 'application/json' }).end(
          JSON.stringify({
            access: 'fixture-access-0',
            refresh: 'fixture-refresh-0',
            mustChangePassword: false,
          }),
        );
      } else if (request.url === '/v1/auth/refresh') {
        refreshes++;
        if (
          request.headers.cookie !== `ps_refresh=fixture-refresh-${generation}`
        ) {
          response.writeHead(401).end();
          return;
        }
        started?.();
        if (holdRefresh) await holdRefresh;
        generation++;
        response
          .writeHead(204, {
            'Set-Cookie': [
              `ps_access=fixture-access-${generation}; Path=/`,
              `ps_refresh=fixture-refresh-${generation}; HttpOnly`,
            ],
          })
          .end();
      } else if (request.url === '/v1/auth/logout')
        response.writeHead(204).end();
      else response.writeHead(404).end();
    });
    server.listen(0, '127.0.0.1');
    await new Promise((resolve) => server.once('listening', resolve));
    t.after(async () => {
      await new Promise((resolve) => server.close(resolve));
      await rm(root, { recursive: true, force: true });
    });
    const origin = `http://127.0.0.1:${server.address().port}`;
    const door = { base: origin, fetch: authenticationFetch };
    const node = new Credentials(
      origin,
      join(root, 'credentials'),
      undefined,
      'alice',
    );
    const signed = await invoke(root, origin, 'login');
    assert.equal(signed.key, connectionKey(origin, 'alice'));
    assert.equal((await node.session()).tokens.access, signed.access);
    const first = await node.renew(door, signed.access);
    assert.equal((await invoke(root, origin, 'session')).access, first.access);
    const second = await invoke(root, origin, 'renew', first.access);
    assert.equal((await node.session()).tokens.access, second.access);

    // Python holds the shared lock while the server owns the refresh. Node must
    // reload after waiting, then reuse the pair rather than consume it again.
    let release;
    holdRefresh = new Promise((resolve) => {
      release = resolve;
    });
    const entered = new Promise((resolve) => {
      started = resolve;
    });
    const pending = invoke(root, origin, 'renew', second.access);
    await entered;
    const nodePending = node.renew(door, second.access);
    release();
    const [a, b] = await Promise.all([pending, nodePending]);
    assert.equal(a.access, b.access);
    assert.equal(refreshes, 3);
    holdRefresh = undefined;
    started = undefined;

    // The opposite owner/order must behave identically.
    const [c, d] = await Promise.all([
      node.renew(door, a.access),
      invoke(root, origin, 'renew', a.access),
    ]);
    assert.equal(c.access, d.access);
    assert.equal(refreshes, 4);

    const filename =
      createHash('sha256').update(origin).digest('hex') + '.json';
    const file = join(root, 'connections', signed.key, 'credentials', filename);
    const old = await readFile(file);
    await invoke(root, origin, 'logout');
    await mkdir(join(root, 'credentials'), { recursive: true });
    await writeFile(join(root, 'credentials', filename), old);
    await assert.rejects(node.session());
    assert.equal((await invoke(root, origin, 'session')).loginRequired, true);
  },
);

import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { TestContext } from 'node:test';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openSocket } from 'plowshare-client-ts/binding/auth';
import type { Socket } from 'plowshare-client-ts/binding/connection';
import {
  authenticationFetch,
  Credentials,
} from 'plowshare-client-node/credentials';

/** Real native HTTP proves pre-send connection refusal separately from lost replies. */
async function fixture(t: TestContext) {
  const directory = await mkdtemp(join(tmpdir(), 'credential-reconnect-'));
  let generation = 0;
  let expired = false;
  let loseRefreshReply = false;
  let refreshes = 0;
  const server = createServer((request, response) => {
    if (request.url === '/v1/auth/login') {
      response.writeHead(200).end(
        JSON.stringify({
          access: 'access-' + generation,
          refresh: 'refresh-' + generation,
          mustChangePassword: false,
        }),
      );
    } else if (request.url === '/v1/auth/ticket') {
      if (
        expired ||
        request.headers.authorization !== 'Bearer access-' + generation
      ) {
        response.writeHead(401).end();
      } else {
        response
          .writeHead(200)
          .end(JSON.stringify({ ticket: 'fixture-ticket' }));
      }
    } else if (request.url === '/v1/auth/refresh') {
      refreshes++;
      if (request.headers.cookie !== 'ps_refresh=refresh-' + generation) {
        response.writeHead(401).end();
        return;
      }
      generation++;
      expired = false;
      if (loseRefreshReply) {
        response.destroy();
        return;
      }
      response
        .writeHead(204, {
          'Set-Cookie': [
            'ps_access=access-' + generation + '; Path=/',
            'ps_refresh=refresh-' + generation + '; Path=/',
          ],
        })
        .end();
    } else response.writeHead(404).end();
  });
  const start = (port = 0): Promise<void> =>
    new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, '127.0.0.1', () => {
        server.off('error', reject);
        resolve();
      });
    });
  const stop = async (): Promise<void> => {
    server.closeAllConnections();
    if (server.listening)
      await new Promise<void>((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()));
      });
  };
  await start();
  const port = (server.address() as AddressInfo).port;
  const base = 'http://127.0.0.1:' + port;
  const door = { base, fetch: authenticationFetch };
  const store = new Credentials(base, directory);
  await store.login(door, 'fixture', 'fixture-password');
  t.after(async () => {
    await stop();
    await rm(directory, { recursive: true, force: true });
  });
  const socket: Socket = {
    send: () => {},
    close: () => {},
    addEventListener: () => {},
  };
  return {
    door,
    store,
    stop,
    start: () => start(port),
    expire: () => {
      expired = true;
    },
    loseReply: () => {
      loseRefreshReply = true;
    },
    refreshes: () => refreshes,
    sibling: () => new Credentials(base, directory),
    connect: (credentials = store) =>
      openSocket(
        {
          ...door,
          session: 'fixture-session',
          open: () => Promise.resolve(socket),
          renew: (rejectedAccess?: string) =>
            credentials.renew(door, rejectedAccess),
        },
        { access: 'access-0', refresh: 'refresh-0' },
      ),
  };
}

for (const expired of [false, true]) {
  await test(`server outage preserves ${expired ? 'expired' : 'live'} saved access until reconnect`, async (t) => {
    const f = await fixture(t);
    if (expired) f.expire();
    await f.stop();
    await assert.rejects(f.connect(), /Could not reach/);
    assert.equal((await f.store.session()).tokens.refresh, 'refresh-0');
    assert.equal(f.refreshes(), 0);
    await f.start();
    const opened = await f.connect();
    assert.equal(opened.tokens.refresh, expired ? 'refresh-1' : 'refresh-0');
    assert.equal(f.refreshes(), expired ? 1 : 0);
    opened.connection.close();
  });
}

await test('native connection refusal during refresh retains the pair and permits a later safe retry', async (t) => {
  const f = await fixture(t);
  await f.stop();
  await assert.rejects(f.store.renew(f.door), /saved session was retained/);
  assert.equal((await f.store.session()).tokens.refresh, 'refresh-0');
  await f.start();
  assert.equal((await f.store.renew(f.door)).refresh, 'refresh-1');
  assert.equal(f.refreshes(), 1);
});

await test('concurrent clients reload rotation under the same cross-process lock', async (t) => {
  const f = await fixture(t);
  f.expire();
  const opened = await Promise.all([f.connect(), f.connect(f.sibling())]);
  assert.equal(f.refreshes(), 1);
  assert.equal((await f.store.session()).tokens.refresh, 'refresh-1');
  for (const connection of opened) connection.connection.close();
});

await test('stale rejection reuses a newer saved pair while explicit renewals still rotate', async (t) => {
  const f = await fixture(t);
  const first = await f.store.renew(f.door);
  assert.equal(
    (await f.sibling().renew(f.door, 'access-0')).access,
    first.access,
  );
  assert.equal(f.refreshes(), 1);
  assert.equal((await f.store.renew(f.door)).refresh, 'refresh-2');
  assert.equal(f.refreshes(), 2);
  await assert.rejects(f.store.renew(f.door, ''), /must be nonempty/);
  assert.equal(f.refreshes(), 2);
});

await test('a processed refresh with a lost response remains fenced and is never replayed', async (t) => {
  const f = await fixture(t);
  f.loseReply();
  await assert.rejects(f.store.renew(f.door), /uncertain refresh/);
  await assert.rejects(f.store.renew(f.door), /previous token renewal/);
  await assert.rejects(f.sibling().session(), /previous token renewal/);
  await assert.rejects(
    f.sibling().renew(f.door, 'different-access'),
    /previous token renewal/,
  );
  assert.equal(f.refreshes(), 1);
});

await test('custom fetch errno, abort, timeout, reset and malformed reply cannot clear the renewal fence', async (t) => {
  const f = await fixture(t);
  const failures = [
    new TypeError('custom fetch', {
      cause: Object.assign(new Error(), { code: 'ECONNREFUSED' }),
    }),
    new Error('aborted'),
    new Error('read timeout'),
    new TypeError('fetch failed', {
      cause: Object.assign(new Error(), { code: 'ECONNRESET' }),
    }),
  ];
  for (const error of failures) {
    await f.store.login(f.door, 'fixture', 'fixture-password');
    let submitted = 0;
    const door = {
      base: f.door.base,
      fetch: (): Promise<never> => {
        submitted++;
        return Promise.reject(error);
      },
    };
    await assert.rejects(f.store.renew(door), /uncertain refresh/);
    await assert.rejects(f.store.renew(door), /previous token renewal/);
    assert.equal(submitted, 1);
  }
  await f.store.login(f.door, 'fixture', 'fixture-password');
  await assert.rejects(
    f.store.renew({
      base: f.door.base,
      fetch: () => Promise.resolve(new Response(null, { status: 204 })),
    }),
    /uncertain refresh/,
  );
  await assert.rejects(f.store.renew(f.door), /previous token renewal/);
  assert.equal(f.refreshes(), 0);
});

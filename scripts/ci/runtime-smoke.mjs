import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { signIn, changePassword, openSocket, ticket } from '../../plowshare-client-ts/build/binding/auth.js';
import { connect } from '../../plowshare-client-ts/build/binding/connection.js';

const base = process.env.PLOWSHARE_URL;
assert.ok(base, 'Supply PLOWSHARE_URL');
const deadline = AbortSignal.timeout(60000);
const door = {
  base, session: randomUUID(),
  fetch: (url, init) => fetch(url, { ...init, signal: deadline, redirect: 'error' }),
  open: url => new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.addEventListener('open', () => resolve(socket), { once: true });
    socket.addEventListener('error', () => reject(new Error('WS connection failed')), { once: true });
    deadline.addEventListener('abort', () => { socket.close(); reject(new Error('WS deadline exceeded')); }, { once: true });
  }),
};
const disposable = process.argv.includes('--initial');
const handle = process.env.PLOWSHARE_HANDLE ?? 'admin';
assert.equal((await door.fetch(base + '/v1/auth/session')).status, 401, 'Anonymous access must be rejected');
let connection;
if (process.argv.includes('--operator')) {
  assert.equal(disposable, false, 'Operator verification must not initialize an account');
  const access = (await readFile(process.env.PLOWSHARE_OPERATOR_TOKEN_FILE, 'utf8')).split(/\r?\n/, 1)[0].trim();
  assert.ok(access && !/\s/.test(access), 'Operator credential must be a single nonempty token');
  const pass = await ticket(door, { access });
  const socketUrl = new URL(base + '/v1/events');
  socketUrl.protocol = socketUrl.protocol === 'https:' ? 'wss:' : 'ws:';
  socketUrl.searchParams.set('ticket', pass);
  socketUrl.searchParams.set('session', door.session);
  connection = connect({ socket: await door.open(socketUrl.href) });
} else {
 const password = (await readFile(process.env.PLOWSHARE_PASSWORD_FILE, 'utf8')).trim();
 let signed = await signIn(door, handle, password);
 if (disposable) {
  assert.equal(signed.mustChangePassword, true, 'Fresh admin must change its password');
  assert.equal((await door.fetch(base + '/v1/auth/ticket', {
    method: 'POST', headers: { Authorization: `Bearer ${signed.tokens.access}` },
  })).status, 403, 'Flagged admin must not receive a WS ticket');
  const next = (await readFile(process.env.PLOWSHARE_NEXT_PASSWORD_FILE, 'utf8')).trim();
  await changePassword(door, signed.tokens.access, password, next);
  signed = await signIn(door, handle, next);
 }
 assert.equal(signed.mustChangePassword, false, 'Use an existing account whose first password change is complete');
 ({ connection } = await openSocket(door, signed.tokens));
}
deadline.addEventListener('abort', () => connection.close(), { once: true });
try {
  for (const type of ['project.list', 'agent.list', 'orchestration.definitions']) {
    const result = await connection.ask(type, {});
    assert.equal(result.code, 'OK', `${type} failed`);
    console.log(`${type}: OK`);
  }
  const result = await connection.ask('web.search', { query: 'Docker container documentation', max: 3, pageSize: 3, page: 1 });
  assert.equal(result.code, 'OK', 'web.search failed');
  assert.ok(!result.payload.refusal, 'Search provider refused the request');
  assert.ok(result.payload.hits.length > 0 && result.payload.hits.length <= 3, 'Expected bounded nonempty search results');
  if (process.env.PLOWSHARE_CI_EXPECT_FIXTURES === 'true') {
    assert.equal(result.payload.hits.length, 3);
    assert.ok(result.payload.hits.every(hit => hit.url.startsWith('https://fixture.invalid/')));
  }
  console.log(`web.search: OK (${result.payload.hits.length} hits)`);
} finally { connection.close(); }

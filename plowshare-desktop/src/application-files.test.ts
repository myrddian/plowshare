import { test } from 'node:test';
import assert from 'node:assert/strict';
import { demoState } from './demo.ts';
import { ApplicationFilesClient } from './application-files.ts';
import { checkedSender } from './fixture.test-support.ts';
import type { Request } from 'plowshare-client-ts/operations/direct';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { DesktopState } from './shared.ts';
const source = {
  project: 'app',
  path: 'a.txt',
  text: 'old',
  revision: 'a'.repeat(64),
  writable: true,
};
function fixture() {
  const state: DesktopState = {
    ...demoState(),
    connected: true,
    projects: [{ name: 'app', kind: 'application', role: 'CONTRIBUTOR' }],
  };
  const calls: Request[] = [];
  let reply: (ask: Request) => Promise<Outcome> = async (ask) => ({
    code: 'OK',
    payload:
      ask.type === 'application.files'
        ? {
            project: 'app',
            path: '',
            entries: [{ path: 'a.txt', name: 'a.txt', directory: false }],
            more: false,
          }
        : source,
  });
  const client = new ApplicationFilesClient(
    () => state,
    checkedSender(async (ask) => {
      calls.push(ask);
      return reply(ask);
    }),
    () => {},
  );
  return {
    state,
    calls,
    client,
    respond(value: typeof reply) {
      reply = value;
    },
  };
}
await test('writable-area selection cannot reuse source write authority or accept a reply without its location', async () => {
  const f = fixture();
  const location = { store: 'outputs', path: 'reports' };
  await f.client.read('app', 'a.txt');
  await assert.rejects(
    f.client.save('app', 'a.txt', 'new', source.revision, location),
  );
  assert.equal(
    f.calls.filter((row) => row.type === 'application.file.save').length,
    0,
  );
  await f.client.read('app', 'a.txt', location);
  assert.match(f.state.applicationFiles?.error ?? '', /reply|response/i);
  assert.equal(f.state.applicationFiles?.document, undefined);
  f.respond(async () => ({ code: 'OK', payload: { ...source, location } }));
  await f.client.read('app', 'a.txt', location);
  assert.deepEqual(f.state.applicationFiles?.location, location);
  await f.client.save('app', 'a.txt', 'new', source.revision, location);
  assert.deepEqual(f.calls.at(-1)?.payload, {
    project: 'app',
    path: 'a.txt',
    text: 'new',
    revision: source.revision,
    location,
  });
});
await test('server-only files require an available Application and preserve failed save without replay', async () => {
  const f = fixture();
  await f.client.list('app');
  await f.client.read('app', 'a.txt');
  assert.equal(f.state.applicationFiles?.document?.text, 'old');
  f.respond(async () => {
    throw new Error('socket lost');
  });
  await f.client.save('app', 'a.txt', 'new', source.revision);
  assert.equal(f.state.applicationFiles?.uncertain, true);
  assert.match(f.state.applicationFiles?.error ?? '', /will not be replayed/);
  await assert.rejects(
    f.client.save('app', 'a.txt', 'new', source.revision),
    /Read the writable file/,
  );
  assert.equal(
    f.calls.filter((row) => row.type === 'application.file.save').length,
    1,
  );
  f.state.projects = [];
  await assert.rejects(
    f.client.read('app', 'a.txt'),
    /available server Application/,
  );
});
await test('connection reset discards a late source reply and readonly source cannot be saved', async () => {
  const f = fixture();
  let resolve!: (value: Outcome) => void;
  f.respond(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const read = f.client.read('app', 'a.txt');
  f.client.reset();
  resolve({ code: 'OK', payload: source });
  await read;
  assert.equal(f.state.applicationFiles, undefined);
  f.respond(async () => ({
    code: 'OK',
    payload: { ...source, writable: false },
  }));
  await f.client.read('app', 'a.txt');
  await assert.rejects(f.client.save('app', 'a.txt', 'new', source.revision));
  assert.equal(
    f.calls.filter((row) => row.type === 'application.file.save').length,
    0,
  );
});

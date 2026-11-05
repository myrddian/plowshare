import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decodeDesktopRequest } from './request-boundary.ts';
import {
  decodeRuntimeSetting,
  HttpRuntimeConfiguration,
} from './runtime-configuration.ts';

const setting = {
  key: 'plowshare.documents.ingest-budget',
  value: '40',
  pinned: true,
  updatedBy: null,
  updatedAt: null,
};

await test('configuration HTTP preserves raw values, uses the selected bearer and never follows redirects', async () => {
  const calls: { url: string; init: RequestInit }[] = [];
  const client = new HttpRuntimeConfiguration(
    'https://configured.example',
    async () => 'private-bearer',
    async (url, init) => {
      calls.push({ url, init });
      return Response.json(
        init.method === 'GET' ? [setting] : { ...setting, value: init.body },
      );
    },
  );
  assert.deepEqual(await client.list(), [setting]);
  assert.equal((await client.set(setting.key, ' 42\n')).value, ' 42\n');
  assert.equal(
    calls[1]?.url,
    'https://configured.example/v1/config/plowshare.documents.ingest-budget',
  );
  assert.equal(calls[1]?.init.body, ' 42\n');
  assert.deepEqual(calls[1]?.init.headers, {
    Authorization: 'Bearer private-bearer',
    'Content-Type': 'text/plain; charset=utf-8',
  });
  assert.equal(calls[1]?.init.redirect, 'error');
});

await test('invalid configuration input causes no HTTP or credential side effect', async () => {
  let effects = 0;
  const client = new HttpRuntimeConfiguration(
    'https://configured.example',
    async () => {
      effects++;
      return 'token';
    },
    async () => {
      effects++;
      return Response.json(setting);
    },
  );
  for (const key of ['', '../other', 'x'.repeat(257)])
    await assert.rejects(client.set(key, '40'), /configuration key/);
  await assert.rejects(client.set(setting.key, 'x'.repeat(65537)), /65536/);
  await assert.rejects(client.set(setting.key, 'a\0b'), /NUL/);
  assert.equal(effects, 0);
  assert.throws(() =>
    decodeDesktopRequest({
      action: 'server-configuration',
      operation: 'list',
      value: 'unexpected',
    }),
  );
  assert.throws(() =>
    decodeDesktopRequest({
      action: 'server-configuration',
      operation: 'set',
      key: setting.key,
      value: 42,
    }),
  );
});

await test('malformed HTTP DTOs fail before projection; extra fields do not enter the desktop DTO', async () => {
  for (const value of [
    { ...setting, pinned: 'yes' },
    { ...setting, value: {} },
    { ...setting, updatedBy: 42 },
  ])
    assert.throws(() => decodeRuntimeSetting(value), /Unreadable/);
  assert.deepEqual(
    decodeRuntimeSetting({ ...setting, accessToken: 'never-project' }),
    setting,
  );
  const client = new HttpRuntimeConfiguration(
    'https://configured.example',
    async () => 'token',
    async () => Response.json({ settings: [setting] }),
  );
  await assert.rejects(client.list(), /listing/);
});

await test('a refused or uncertain configuration write is sent once and never replayed', async () => {
  for (const uncertain of [false, true]) {
    let sent = 0;
    const client = new HttpRuntimeConfiguration(
      'https://configured.example',
      async () => 'token',
      async () => {
        sent++;
        if (uncertain) throw new Error('Connection lost after delivery');
        return new Response('', { status: 403 });
      },
    );
    await assert.rejects(client.set(setting.key, '41'), /not retried/);
    assert.equal(sent, 1);
  }
});

await test('a write reply for another key cannot replace the selected setting', async () => {
  const client = new HttpRuntimeConfiguration(
    'https://configured.example',
    async () => 'token',
    async () => Response.json({ ...setting, key: 'another.key' }),
  );
  await assert.rejects(client.set(setting.key, '41'), /different key/);
});

await test('configuration HTTP rejects origins with credentials, paths or unsupported protocols', () => {
  for (const origin of [
    'file:///tmp/settings',
    'https://user:password@configured.example',
    'https://configured.example/path',
    'https://configured.example/?token=secret',
  ])
    assert.throws(
      () =>
        new HttpRuntimeConfiguration(
          origin,
          async () => 'token',
          async () => Response.json([]),
        ),
      /explicit HTTP/,
    );
});

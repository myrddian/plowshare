import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeConfig, snowflake } from './config.ts';
import { chunks } from './render.ts';
import { configuration, CHANNEL } from './adapter.test-support.ts';

await test('requires explicit server, private state, users and unambiguous channel routes', () => {
  const config = configuration();
  for (const invalid of [
    { ...config, origin: 'https://secret@plowshare.example.invalid' },
    { ...config, stateDirectory: 'relative' },
    { ...config, allowedUserIds: [] },
    { ...config, allowedUserIds: [123] },
    { ...config, routes: [...config.routes, ...config.routes] },
    { ...config, routes: [{ ...config.routes[0], requireMention: undefined }] },
    { ...config, routes: [{ ...config.routes[0], unsupported: true }] },
    { ...config, discordTokenEnvironment: config.serviceTokenEnvironment },
    { ...config, maxRecords: null },
    { ...config, pollMs: NaN },
    { ...config, token: 'secret' },
  ])
    assert.throws(() => decodeConfig(invalid));
});
await test('notification destinations must match an explicitly mapped project/channel', () => {
  const config = configuration();
  const notification = {
    project: 'research',
    channelId: CHANNEL,
    topic: 'research.completed',
    group: 'discord.results',
    start: 'LATEST',
  };
  assert.equal(
    decodeConfig({ ...config, notifications: [notification] }).notifications
      .length,
    1,
  );
  assert.throws(() =>
    decodeConfig({
      ...config,
      notifications: [{ ...notification, project: 'private' }],
    }),
  );
  assert.throws(() =>
    decodeConfig({ ...config, notifications: [notification, notification] }),
  );
  assert.throws(() =>
    decodeConfig({
      ...config,
      notifications: [{ ...notification, start: null }],
    }),
  );
});
await test('Discord chunks preserve Unicode and report oversized results explicitly', () => {
  const body = 'a'.repeat(1999) + '😀' + '\n@everyone';
  const parts = chunks(body);
  assert.equal(parts.join(''), body);
  assert.ok(parts.every((part) => part.length <= 2000));
  assert.match(chunks('x'.repeat(40001))[0] ?? '', /larger/);
});

await test('Discord IDs are canonical unsigned 64-bit strings, independent of age', () => {
  assert.equal(snowflake('1'), '1');
  assert.equal(snowflake('18446744073709551615'), '18446744073709551615');
  for (const invalid of ['0', '01', '-1', '18446744073709551616', 123])
    assert.throws(() => snowflake(invalid));
});

await test('DM service mode is explicit, permits DM-only operation and has no implicit project or bot', () => {
  const config = configuration();
  assert.deepEqual(config.dm, { mode: 'disabled' });
  assert.equal(
    decodeConfig({
      ...config,
      routes: [],
      dm: {
        mode: 'service',
        project: 'research',
        bot: 'researcher',
      },
    }).routes.length,
    0,
  );
  for (const dm of [
    null,
    {},
    { mode: 'linked' },
    { mode: 'service', project: 'research' },
    { mode: 'service', project: 'research', bot: '' },
    { mode: 'disabled', project: 'research' },
    {
      mode: 'service',
      project: 'research',
      bot: 'researcher',
      token: 'forbidden',
    },
  ])
    assert.throws(() => decodeConfig({ ...config, dm }));
  assert.throws(() => decodeConfig({ ...config, routes: [] }));
});

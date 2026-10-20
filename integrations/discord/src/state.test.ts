import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, symlink } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { FileState, decodeState } from './state.ts';
import {
  configuration,
  emptyState,
  PRINCIPAL,
  BOT,
  input,
  FakeDiscord,
  FakeGateway,
} from './adapter.test-support.ts';
import { DiscordAdapter } from './adapter.ts';
import { IntakeFull, SerialOwner } from './main.ts';

await test('journal binds server, principal and Discord bot and rejects corrupt state', () => {
  const config = configuration();
  const state = emptyState(config);
  for (const invalid of [
    { ...state, principal: '@service/foreign' },
    { ...state, origin: 'https://other.example.invalid' },
    { ...state, discordBotId: '100000000000000009' },
    { ...state, consumerId: 'bad' },
    { ...state, unknown: true },
    { ...state, contexts: [{ key: 'x', contextId: 'bad' }] },
  ])
    assert.throws(() => decodeState(invalid, config, PRINCIPAL, BOT));
});
await test('private journal persists receipts, refuses a second owner and never follows a state symlink', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-discord-state-'));
  const config = { ...configuration(), stateDirectory: directory };
  let repository: FileState | undefined;
  try {
    repository = await FileState.open(config, PRINCIPAL, BOT);
    await assert.rejects(FileState.open(config, PRINCIPAL, BOT));
    const adapter = new DiscordAdapter(
      config,
      repository,
      new FakeGateway(),
      new FakeDiscord(),
    );
    await adapter.accept(input());
    const raw: unknown = JSON.parse(
      await readFile(join(directory, 'state.json'), 'utf8'),
    );
    assert.equal(decodeState(raw, config, PRINCIPAL, BOT).records.length, 1);
    assert.equal(
      (await stat(join(directory, 'state.json'))).mode & 0o777,
      0o600,
    );
    assert.equal((await stat(directory)).mode & 0o777, 0o700);
    await repository.close();
    repository = undefined;
    await rm(join(directory, 'state.json'));
    await symlink(
      join(directory, 'external.json'),
      join(directory, 'state.json'),
    );
    await assert.rejects(FileState.open(config, PRINCIPAL, BOT));
  } finally {
    await repository?.close();
    await rm(directory, { recursive: true, force: true });
  }
});
await test('serial owner maintains ordering and releases the queue after a failed operation', async () => {
  const owner = new SerialOwner();
  const order: number[] = [];
  const failed = owner.run(() => {
    order.push(1);
    return Promise.reject(new Error('fixture'));
  });
  const next = owner.run(async () => {
    order.push(2);
  });
  await assert.rejects(failed);
  await next;
  await owner.settle();
  assert.deepEqual(order, [1, 2]);
});
await test('interrupted outbound attempt is decoded UNKNOWN and cannot be replayed', async () => {
  const config = configuration();
  const state = emptyState(config);
  const memory = { state, save: async () => {} };
  await new DiscordAdapter(
    config,
    memory,
    new FakeGateway(),
    new FakeDiscord(),
  ).accept(input());
  const record = state.records[0];
  assert.ok(record);
  record.outputs[0] = {
    key: 'accepted:0',
    text: 'Accepted',
    state: 'SENDING',
    messageId: null,
  };
  const decoded = decodeState(state, config, PRINCIPAL, BOT);
  assert.equal(decoded.records[0]?.outputs[0]?.state, 'UNKNOWN');
});

await test('bounded intake refuses overflow without executing it and recovers after draining', async () => {
  const owner = new SerialOwner();
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let executed = 0;
  const pending = Array.from({ length: 32 }, () =>
    owner.run(async () => {
      await gate;
      executed++;
    }),
  );
  await assert.rejects(
    owner.run(async () => {
      executed++;
    }),
    IntakeFull,
  );
  release();
  await Promise.all(pending);
  await owner.run(async () => {
    executed++;
  });
  assert.equal(executed, 33);
});

await test('v1 guild journals migrate without changing conversation, task or receipt identities', async () => {
  const config = configuration();
  const state = emptyState(config);
  await new DiscordAdapter(
    config,
    { state, async save() {} },
    new FakeGateway(),
    new FakeDiscord(),
  ).accept(input());
  const original = state.records[0];
  assert.ok(original && original.destination.kind === 'guild');
  const { kind: _kind, ...oldDestination } = original.destination;
  const legacy = {
    ...state,
    version: 1,
    records: [{ ...original, destination: oldDestination }],
  };
  const migrated = decodeState(legacy, config, PRINCIPAL, BOT);
  assert.equal(migrated.version, 2);
  assert.deepEqual(migrated.contexts, state.contexts);
  assert.deepEqual(migrated.records, state.records);
  const gateway = new FakeGateway();
  await new DiscordAdapter(
    config,
    { state: migrated, async save() {} },
    gateway,
    new FakeDiscord(),
  ).accept(input({ messageId: '200000000000000002' }));
  assert.equal(gateway.submissions[0]?.context, original.contextId);
  const interrupted = decodeState(
    { ...legacy, records: [{ ...legacy.records[0], admission: 'ADMITTING' }] },
    config,
    PRINCIPAL,
    BOT,
  );
  assert.equal(interrupted.records[0]?.admission, 'UNKNOWN');
});
await test('v2 destinations are explicit and DM journals cannot forge recipients or notification audiences', async () => {
  const config = configuration();
  const state = emptyState(config);
  await new DiscordAdapter(
    config,
    { state, async save() {} },
    new FakeGateway(),
    new FakeDiscord(),
  ).accept(input());
  const original = state.records[0];
  assert.ok(original);
  const dm = {
    kind: 'dm',
    channelId: original.destination.channelId,
    recipientId: original.userId,
  };
  const direct = { ...original, destination: dm };
  assert.equal(
    decodeState({ ...state, records: [direct] }, config, PRINCIPAL, BOT)
      .records[0]?.destination.kind,
    'dm',
  );
  for (const record of [
    { ...direct, destination: { ...dm, recipientId: '100000000000000004' } },
    { ...direct, destination: { ...dm, guildId: '100000000000000001' } },
    { ...direct, kind: 'NOTICE' },
    { ...original, destination: { channelId: original.destination.channelId } },
  ])
    assert.throws(() =>
      decodeState({ ...state, records: [record] }, config, PRINCIPAL, BOT),
    );
  assert.throws(() =>
    decodeState(
      { ...state, version: 1, records: [direct] },
      config,
      PRINCIPAL,
      BOT,
    ),
  );
});

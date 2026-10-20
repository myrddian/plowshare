import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { FileSetup, decodeSetupState } from './setup-store.ts';
import { DiscordSetup } from './setup-engine.ts';
import {
  ConfigurationManager,
  decodeConfigurationRevision,
} from './management.ts';
import {
  setupConfiguration,
  setupSource,
  SetupServer,
} from './setup.test-support.ts';
import { FileState } from './state.ts';
import {
  BOT,
  OTHER,
  MemoryState,
  FakeGateway,
  FakeDiscord,
  input,
} from './adapter.test-support.ts';
import { DiscordAdapter } from './adapter.ts';
import type { SetupConfiguration } from './setup-contracts.ts';

async function installation(
  work: (
    store: FileSetup,
    config: SetupConfiguration,
    server: SetupServer,
  ) => Promise<void>,
) {
  const root = await mkdtemp(join(tmpdir(), 'discord-management-'));
  const base = setupConfiguration();
  const config = {
    ...base,
    sourceDirectory: join(root, 'source'),
    outputDirectory: join(root, 'setup'),
    adapter: { ...base.adapter, stateDirectory: join(root, 'runtime') },
  };
  let store: FileSetup | undefined;
  try {
    store = await FileSetup.open(config);
    const server = new SetupServer(config);
    await new DiscordSetup(config, store, server).install(setupSource());
    await store.close();
    store = await FileSetup.open(config, 'configuration');
    await work(store, config, server);
  } finally {
    await store?.close();
    await rm(root, { recursive: true, force: true });
  }
}

await test('configuration changes use revisions and preserve original setup and credential evidence', async () => {
  await installation(async (store, config, server) => {
    const before = await readFile(
      join(config.outputDirectory, 'setup-state.json'),
      'utf8',
    );
    const secret = await store.serviceCredential();
    const initial = await store.configuration();
    let verified = 0;
    const manager = new ConfigurationManager(store, {
      async verify(adapter) {
        verified++;
        assert.equal(adapter.dm.mode, 'service');
      },
    });
    const next = await manager.update(initial.revision, {
      ...config.adapter,
      dm: { mode: 'service', project: config.project, bot: 'researcher' },
    });
    assert.notEqual(next.revision, initial.revision);
    assert.deepEqual(await store.configuration(), next);
    assert.equal(
      await readFile(join(config.outputDirectory, 'setup-state.json'), 'utf8'),
      before,
    );
    assert.deepEqual(await store.serviceCredential(), secret);
    assert.equal(server.issuances.length, 1);
    await assert.rejects(
      manager.update(initial.revision, config.adapter),
      /revision changed/,
    );
    assert.equal(verified, 1);
    await assert.rejects(store.published(), /configuration differs/);
    assert.deepEqual(await store.prepareRuntime(), next.adapter);
    assert.deepEqual(await store.published(), secret);
    await store.close();
    const reopened = await FileSetup.open(config);
    try {
      assert.deepEqual(await reopened.configuration(), next);
    } finally {
      await reopened.close();
    }
  });
});

await test('configuration refuses identity changes, scope widening and foreign revision documents before verification', async () => {
  await installation(async (store, config) => {
    let verified = 0;
    const manager = new ConfigurationManager(store, {
      async verify() {
        verified++;
      },
    });
    const original = await store.configuration();
    for (const adapter of [
      { ...config.adapter, origin: 'https://foreign.example.invalid' },
      {
        ...config.adapter,
        stateDirectory: join(config.outputDirectory, 'foreign'),
      },
      { ...config.adapter, discordTokenEnvironment: 'OTHER_TOKEN' },
      { ...config.adapter, serviceTokenEnvironment: 'OTHER_TOKEN' },
      {
        ...config.adapter,
        dm: { mode: 'service', project: 'foreign', bot: 'researcher' },
      },
      { ...config.adapter, allowedUserIds: [] },
      { ...config.adapter, token: 'never accepted' },
    ])
      await assert.rejects(manager.update(original.revision, adapter));
    assert.equal(verified, 0);
    assert.deepEqual(await store.configuration(), original);
    assert.throws(() =>
      decodeConfigurationRevision(
        { ...original, installation: 'foreign' },
        config,
      ),
    );
    assert.throws(() =>
      decodeConfigurationRevision({ ...original, revision: 'bad' }, config),
    );
  });
});

await test('failed catalogue verification leaves configuration unpublished', async () => {
  await installation(async (store, config) => {
    const original = await store.configuration();
    const manager = new ConfigurationManager(store, {
      async verify() {
        throw new Error('catalogue refused');
      },
    });
    await assert.rejects(
      manager.update(original.revision, { ...config.adapter, pollMs: 5000 }),
    );
    assert.deepEqual(await store.configuration(), original);
  });
});

await test('configuration cannot discard journal evidence by lowering capacity', async () => {
  await installation(async (store, config) => {
    const memory = new MemoryState();
    const adapter = new DiscordAdapter(
      config.adapter,
      memory,
      new FakeGateway(),
      new FakeDiscord(),
    );
    await adapter.accept(input());
    await adapter.accept(
      input({ messageId: '200000000000000002', userId: OTHER }),
    );
    const identity = await store.serviceCredential();
    const journal = JSON.stringify({
      ...memory.state,
      origin: config.adapter.origin,
      principal: identity.receipt.principal,
    });
    const path = join(config.adapter.stateDirectory, 'state.json');
    await writeFile(path, journal, { mode: 0o600 });
    const original = await store.configuration();
    const manager = new ConfigurationManager(store, { async verify() {} });
    await assert.rejects(
      manager.update(original.revision, { ...config.adapter, maxRecords: 1 }),
    );
    assert.deepEqual(await store.configuration(), original);
    assert.equal(await readFile(path, 'utf8'), journal);
  });
});

await test('an active raw runtime prevents configuration ownership and closed stores cannot steal later locks', async () => {
  await installation(async (store, config) => {
    const credential = await store.serviceCredential();
    await store.close();
    const runtime = await FileState.open(
      config.adapter,
      credential.receipt.principal,
      BOT,
    );
    try {
      await assert.rejects(FileSetup.open(config, 'configuration'));
    } finally {
      await runtime.close();
    }
    const owner = await FileSetup.open(config, 'configuration');
    try {
      await store.close();
      await runtime.close();
      await assert.rejects(runtime.save(), /closed/);
      await assert.rejects(store.configuration(), /closed/);
      await assert.rejects(
        FileState.open(config.adapter, credential.receipt.principal, BOT),
      );
      await assert.rejects(FileSetup.open(config, 'configuration'));
    } finally {
      await owner.close();
    }
  });
});

await test('pre-DM setup hashes migrate only with the original disabled DM configuration', async () => {
  await installation(async (store, config) => {
    assert.ok(store.state);
    const { dm: _dm, ...adapter } = config.adapter;
    const configurationHash = createHash('sha256')
      .update(JSON.stringify({ ...config, adapter }))
      .digest('hex');
    const legacy = { ...store.state, configurationHash };
    assert.deepEqual(decodeSetupState(legacy, config), store.state);
    assert.throws(() =>
      decodeSetupState(legacy, {
        ...config,
        adapter: {
          ...config.adapter,
          dm: { mode: 'service', project: config.project, bot: 'researcher' },
        },
      }),
    );
  });
});

await test('the configuration CLI reads private metadata offline without printing service secrets', async () => {
  await installation(async (store, config) => {
    const secret = await store.serviceCredential();
    const expected = await store.configuration();
    await store.close();
    const file = join(config.outputDirectory, '..', 'setup.json');
    await writeFile(file, JSON.stringify(config), { mode: 0o600 });
    const cli = fileURLToPath(new URL('./setup.js', import.meta.url));
    const result = await promisify(execFile)(process.execPath, [
      cli,
      'config',
      file,
    ]);
    const value: unknown = JSON.parse(result.stdout);
    assert.deepEqual(decodeConfigurationRevision(value, config), expected);
    assert.ok(!result.stdout.includes(secret.credential));
  });
});

await test('overlapping edits from the same owner cannot both replace one revision', async () => {
  await installation(async (store, config) => {
    const original = await store.configuration();
    const manager = new ConfigurationManager(store, { async verify() {} });
    const results = await Promise.allSettled([
      manager.update(original.revision, { ...config.adapter, pollMs: 5000 }),
      manager.update(original.revision, { ...config.adapter, pollMs: 6000 }),
    ]);
    assert.equal(
      results.filter((result) => result.status === 'fulfilled').length,
      1,
    );
    assert.equal(
      results.filter((result) => result.status === 'rejected').length,
      1,
    );
    assert.notEqual((await store.configuration()).revision, original.revision);
  });
});

await test('recovery and rotation preserve edited configuration, original principal and uncertain runtime evidence', async () => {
  await installation(async (store, config, server) => {
    const original = await store.configuration();
    const manager = new ConfigurationManager(store, { async verify() {} });
    const next = await manager.update(original.revision, {
      ...config.adapter,
      dm: { mode: 'service', project: config.project, bot: 'researcher' },
    });
    const principal = (await store.serviceCredential()).receipt.principal;
    const memory = new MemoryState();
    const gateway = new FakeGateway();
    gateway.failure = new Error('Lost submission response');
    await new DiscordAdapter(
      next.adapter,
      memory,
      gateway,
      new FakeDiscord(),
    ).accept(input());
    const journal = JSON.stringify({ ...memory.state, principal });
    const runtime = join(config.adapter.stateDirectory, 'state.json');
    await writeFile(runtime, journal, { mode: 0o600 });
    await store.close();
    const reopened = await FileSetup.open(config);
    try {
      const setup = new DiscordSetup(config, reopened, server);
      await setup.recover();
      assert.equal(server.issuances.length, 1);
      assert.deepEqual(await reopened.configuration(), next);
      assert.deepEqual(
        JSON.parse(
          await readFile(join(config.outputDirectory, 'adapter.json'), 'utf8'),
        ),
        next.adapter,
      );
      await setup.rotate();
      assert.equal(server.issuances.length, 2);
      assert.equal((await reopened.published()).receipt.principal, principal);
      assert.deepEqual(await reopened.configuration(), next);
      assert.equal(await readFile(runtime, 'utf8'), journal);
    } finally {
      await reopened.close();
    }
  });
});

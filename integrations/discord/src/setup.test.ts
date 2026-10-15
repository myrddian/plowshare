import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  stat,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { DiscordSetup } from './setup-engine.ts';
import { decodeSetupConfig, setupFiles } from './setup-config.ts';
import { decodeSetupState, FileSetup } from './setup-store.ts';
import {
  MemorySetup,
  SetupServer,
  setupConfiguration,
  setupSource,
  latest,
} from './setup.test-support.ts';

function fixture() {
  const config = setupConfiguration();
  const repository = new MemorySetup();
  const server = new SetupServer(config);
  const engine = new DiscordSetup(config, repository, server);
  return { config, repository, server, engine };
}
await test('setup rejects ambiguous identities, cross-project routes and overlapping state before I/O', () => {
  const config = setupConfiguration();
  for (const invalid of [
    { ...config, account: '@service/foreign' },
    { ...config, serviceHandle: config.account },
    { ...config, sourceDirectory: 'relative' },
    { ...config, outputDirectory: config.sourceDirectory },
    { ...config, outputDirectory: config.adapter.stateDirectory },
    { ...config, project: 'Personal:operator' },
    {
      ...config,
      adapter: {
        ...config.adapter,
        routes: config.adapter.routes.map((r) => ({
          ...r,
          project: 'foreign',
        })),
      },
    },
    { ...config, adminPassword: 'never accepted' },
    { ...config, destination: { store: 'applications', path: '../outside' } },
    {
      ...config,
      adapter: { ...config.adapter, serviceTokenEnvironment: 'NODE_OPTIONS' },
    },
  ])
    assert.throws(() => decodeSetupConfig(invalid));
});
await test('setup prepares owned identity and explicit human grant without altering selected source files', () => {
  const source = setupSource();
  const original = JSON.stringify(source);
  const prepared = setupFiles(setupConfiguration(), source);
  const manifest: unknown = JSON.parse(prepared[0]?.text ?? 'null');
  assert.ok(
    manifest !== null &&
      typeof manifest === 'object' &&
      'service' in manifest &&
      'access' in manifest,
  );
  assert.deepEqual(manifest.service, {
    handle: 'discord-worker-fixture',
    role: 'CONTRIBUTOR',
  });
  assert.deepEqual(manifest.access, {
    accounts: [{ handle: 'operator', role: 'MANAGER' }],
  });
  assert.equal(JSON.stringify(source), original);
  assert.throws(() =>
    setupFiles(setupConfiguration(), [
      { path: 'plowshare.json', text: '{"version":1,"acting":[]}' },
    ]),
  );
});
await test('installation deploys and issues once; re-running reconciles without new work', async () => {
  const f = fixture();
  await f.engine.install(setupSource());
  assert.equal(f.server.deployments.length, 1);
  assert.equal(f.server.issuances.length, 1);
  assert.ok(f.repository.published);
  assert.equal(
    f.repository.published.receipt.principal,
    f.server.identityValue?.principal,
  );
  assert.ok(!f.repository.persisted.includes('pss_'));
  await f.engine.install(setupSource());
  await f.engine.recover();
  assert.equal(f.server.deployments.length, 1);
  assert.equal(f.server.issuances.length, 1);
});
await test('a different/non-administrator cannot submit setup mutations', async () => {
  for (const mode of ['account', 'role']) {
    const f = fixture();
    if (mode === 'account') f.server.handle = 'another-admin';
    else f.server.admin = false;
    await assert.rejects(f.engine.install(setupSource()), /administrator/);
    assert.equal(f.server.deployments.length, 0);
    assert.equal(f.server.issuances.length, 0);
  }
});
await test('recovery and rotation recheck administrator authority before touching an installed credential', async () => {
  for (const operation of ['recover', 'rotate'] as const) {
    for (const change of ['account', 'role']) {
      const f = fixture();
      await f.engine.install(setupSource());
      const persisted = f.repository.persisted;
      const verifications = f.server.verifications.length;
      f.repository.published = null;
      if (change === 'account') f.server.handle = 'another-admin';
      else f.server.admin = false;
      await assert.rejects(f.engine[operation](), /administrator/);
      assert.equal(f.server.deployments.length, 1);
      assert.equal(f.server.issuances.length, 1);
      assert.equal(f.server.verifications.length, verifications);
      assert.equal(f.repository.persisted, persisted);
      assert.equal(f.repository.published, null);
    }
  }
});
await test('failed durable intent prevents deployment, and an existing Application cannot be adopted', async () => {
  const f = fixture();
  f.repository.failSave = true;
  await assert.rejects(f.engine.install(setupSource()));
  assert.equal(f.server.deployments.length, 0);
  const existing = fixture();
  await existing.engine.install(setupSource());
  const fresh = new MemorySetup();
  await assert.rejects(
    new DiscordSetup(existing.config, fresh, existing.server).install(
      setupSource(),
    ),
    /new Application/,
  );
  assert.equal(existing.server.deployments.length, 1);
});
await test('lost deployment reply is recovered by original receipt without resubmission', async () => {
  const f = fixture();
  f.server.failDeploy = true;
  await assert.rejects(f.engine.install(setupSource()));
  const saved: unknown = JSON.parse(f.repository.persisted);
  f.repository.state = decodeSetupState(saved, f.config);
  await f.engine.recover();
  assert.equal(f.server.deployments.length, 1);
  assert.equal(f.server.issuances.length, 1);
});
await test('missing deployment receipt fences all subsequent mutations, including explicit rotation', async () => {
  const f = fixture();
  f.server.failDeploy = true;
  await assert.rejects(f.engine.install(setupSource()));
  f.server.missingReceipt = true;
  await assert.rejects(f.engine.recover());
  await assert.rejects(f.engine.rotate());
  assert.equal(f.server.deployments.length, 1);
  assert.equal(f.server.issuances.length, 0);
});
await test('lost issuance response requires explicit rotation of the same retained token', async () => {
  const f = fixture();
  f.server.failIssue = true;
  await assert.rejects(f.engine.install(setupSource()));
  const token = latest(f.repository).tokenId;
  const oldRequest = latest(f.repository).request.requestId;
  f.server.failIssue = false;
  await assert.rejects(f.engine.recover(), /secret was lost/);
  assert.equal(f.server.issuances.length, 1);
  await f.engine.rotate();
  assert.equal(f.server.issuances.length, 2);
  assert.equal(f.server.issuances[1]?.rotate, true);
  assert.equal(latest(f.repository).tokenId, token);
  assert.notEqual(latest(f.repository).request.requestId, oldRequest);
});
await test('a missing credential receipt cannot authorize another issue or rotation', async () => {
  const f = fixture();
  f.server.failIssue = true;
  await assert.rejects(f.engine.install(setupSource()));
  f.server.missingReceipt = true;
  await assert.rejects(f.engine.recover());
  await assert.rejects(f.engine.rotate());
  assert.equal(f.server.issuances.length, 1);
});
await test('a saved secret survives interruption while publishing runtime configuration', async () => {
  const f = fixture();
  f.repository.failPublish = true;
  await assert.rejects(f.engine.install(setupSource()));
  assert.equal(f.repository.secrets.size, 1);
  f.repository.failPublish = false;
  await f.engine.recover();
  assert.equal(f.server.issuances.length, 1);
  assert.ok(f.repository.published);
});
await test('an unsaved one-time secret cannot be invented during recovery', async () => {
  const f = fixture();
  f.repository.failSecret = true;
  await assert.rejects(f.engine.install(setupSource()));
  f.repository.failSecret = false;
  await assert.rejects(f.engine.recover(), /secret was lost/);
  assert.equal(f.server.issuances.length, 1);
});
await test('a durable secret is recovered when recording its issuance receipt was interrupted', async () => {
  const f = fixture();
  f.repository.failOnSave = 4;
  await assert.rejects(f.engine.install(setupSource()));
  assert.equal(f.repository.secrets.size, 1);
  const saved: unknown = JSON.parse(f.repository.persisted);
  f.repository.state = decodeSetupState(saved, f.config);
  assert.equal(latest(f.repository).receipt, null);
  await f.engine.recover();
  assert.equal(f.server.issuances.length, 1);
  assert.ok(f.repository.published);
});
await test('superseded, expired or revoked credentials are never republished automatically', async () => {
  for (const mode of ['superseded', 'expired', 'revoked']) {
    const f = fixture();
    await f.engine.install(setupSource());
    const identity = f.server.identityValue;
    assert.ok(identity);
    f.server.identityValue = {
      ...identity,
      ...(mode === 'superseded'
        ? { credentialRequestId: '00000000-0000-0000-0000-000000000099' }
        : mode === 'expired'
          ? { expiresAt: '2000-01-01T00:00:00Z' }
          : { revokedAt: new Date().toISOString() }),
    };
    f.repository.published = null;
    await assert.rejects(f.engine.recover(), /superseded, revoked or expired/);
    assert.equal(f.repository.published, null);
    assert.equal(f.server.issuances.length, 1);
  }
});
await test('changed active source or owned service identity prevents credential mutation', async () => {
  const f = fixture();
  await f.engine.install(setupSource());
  assert.ok(f.server.release);
  f.server.release = {
    ...f.server.release,
    release: {
      ...f.server.release.release,
      revision: '00000000-0000-0000-0000-000000000099',
    },
  };
  await assert.rejects(f.engine.rotate(), /no longer active/);
  assert.equal(f.server.issuances.length, 1);
});
await test('failed service identity/catalog verification keeps runtime output unpublished', async () => {
  const f = fixture();
  f.server.failVerify = true;
  await assert.rejects(f.engine.install(setupSource()));
  assert.equal(f.repository.published, null);
  assert.equal(f.server.issuances.length, 1);
});
await test('journal ownership and issuance coordinates are validated before recovery', async () => {
  const f = fixture();
  await f.engine.install(setupSource());
  const state = f.repository.state;
  assert.ok(state);
  assert.throws(() =>
    decodeSetupState(state, { ...f.config, account: 'foreign' }),
  );
  assert.throws(() =>
    decodeSetupState(
      { ...state, credentials: [...state.credentials, ...state.credentials] },
      f.config,
    ),
  );
  assert.throws(() =>
    decodeSetupState(
      { ...state, deployment: { ...state.deployment, project: 'foreign' } },
      f.config,
    ),
  );
});
await test('private setup artifacts are exclusive, request-linked and recover after partial publication', async () => {
  const root = await mkdtemp(join(tmpdir(), 'discord-setup-fixture-'));
  const config = {
    ...setupConfiguration(),
    outputDirectory: join(root, 'installation'),
    sourceDirectory: join(root, 'source'),
    adapter: {
      ...setupConfiguration().adapter,
      stateDirectory: join(root, 'runtime'),
    },
  };
  let repository: FileSetup | undefined;
  try {
    repository = await FileSetup.open(config);
    await assert.rejects(FileSetup.open(config));
    const server = new SetupServer(config);
    await new DiscordSetup(config, repository, server).install(setupSource());
    assert.equal(
      (await stat(join(config.outputDirectory, 'service-credential.json')))
        .mode & 0o777,
      0o600,
    );
    assert.equal((await stat(config.outputDirectory)).mode & 0o777, 0o700);
    const credential = await repository.published();
    assert.equal(credential.receipt.principal, server.identityValue?.principal);
    const foreign = {
      ...credential,
      receipt: {
        ...credential.receipt,
        requestId: '00000000-0000-0000-0000-000000000099',
      },
    };
    await assert.rejects(
      repository.saveCredential(foreign),
      /matching owned issuance intent/,
    );
    await assert.rejects(
      repository.publish(config.adapter, foreign),
      /confirmed installation/,
    );
    assert.deepEqual(await repository.published(), credential);
    assert.ok(
      !(
        await readFile(join(config.outputDirectory, 'setup-state.json'), 'utf8')
      ).includes('pss_'),
    );
    await repository.close();
    repository = undefined;
    repository = await FileSetup.open(config);
    await new DiscordSetup(config, repository, server).recover();
    assert.equal(server.issuances.length, 1);
    await repository.close();
    repository = undefined;
    await rm(join(config.outputDirectory, 'setup-state.json'));
    await writeFile(join(root, 'external.json'), '{}');
    await symlink(
      join(root, 'external.json'),
      join(config.outputDirectory, 'setup-state.json'),
    );
    await assert.rejects(FileSetup.open(config));
  } finally {
    await repository?.close();
    await rm(root, { recursive: true, force: true });
  }
});

await test('setup refuses a symlink ancestor that aliases output into Application source', async () => {
  const root = await mkdtemp(join(tmpdir(), 'discord-setup-alias-'));
  try {
    const sourceDirectory = join(root, 'source');
    await mkdir(sourceDirectory);
    await symlink(sourceDirectory, join(root, 'alias'));
    await assert.rejects(
      FileSetup.open({
        ...setupConfiguration(),
        sourceDirectory,
        outputDirectory: join(root, 'alias', 'new-output'),
      }),
      /disjoint/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

await test('interrupted rotation fences old published credentials across reopen until explicit repair', async () => {
  const root = await mkdtemp(join(tmpdir(), 'discord-rotation-fixture-'));
  const base = setupConfiguration();
  const config = {
    ...base,
    sourceDirectory: join(root, 'source'),
    outputDirectory: join(root, 'installation'),
    adapter: { ...base.adapter, stateDirectory: join(root, 'runtime') },
  };
  let repository: FileSetup | undefined;
  try {
    repository = await FileSetup.open(config);
    const server = new SetupServer(config);
    let engine = new DiscordSetup(config, repository, server);
    await engine.install(setupSource());
    const original = await repository.published();
    server.failIssue = true;
    await assert.rejects(engine.rotate());
    // The previous publication still exists on disk, but the pending intent must fence start.
    assert.equal(
      await readFile(
        join(config.outputDirectory, 'service-credential.json'),
        'utf8',
      ),
      JSON.stringify(original) + '\n',
    );
    await repository.close();
    repository = undefined;
    repository = await FileSetup.open(config);
    await assert.rejects(repository.published(), /incomplete/);
    engine = new DiscordSetup(config, repository, server);
    server.failIssue = false;
    await assert.rejects(engine.recover(), /secret was lost/);
    assert.equal(server.issuances.length, 2);
    await assert.rejects(repository.published(), /stale/);
    await engine.rotate();
    const replacement = await repository.published();
    assert.equal(server.issuances.length, 3);
    assert.equal(replacement.receipt.tokenId, original.receipt.tokenId);
    assert.notEqual(replacement.receipt.requestId, original.receipt.requestId);
  } finally {
    await repository?.close();
    await rm(root, { recursive: true, force: true });
  }
});

await test('setup help is offline and failed CLI usage never echoes credential-shaped input', async () => {
  const execute = promisify(execFile);
  const cli = fileURLToPath(new URL('./setup.js', import.meta.url));
  const help = await execute(process.execPath, [cli, '--help']);
  assert.match(help.stdout, /install\|recover\|rotate\|start/);
  const secret = 'pss_' + '1'.repeat(48);
  await assert.rejects(
    execute(process.execPath, [cli, secret]),
    (error: unknown) => {
      assert.ok(
        error !== null &&
          typeof error === 'object' &&
          'stderr' in error &&
          typeof error.stderr === 'string',
      );
      assert.match(error.stderr, /Usage:/);
      assert.ok(!error.stderr.includes(secret));
      return true;
    },
  );
});

await test('credential issuance cannot run before its own durable intent succeeds', async () => {
  const f = fixture();
  f.repository.failOnSave = 3;
  await assert.rejects(f.engine.install(setupSource()));
  assert.equal(f.server.deployments.length, 1);
  assert.equal(f.server.issuances.length, 0);
  assert.equal(f.repository.secrets.size, 0);
});

await test('a valid-looking credential receipt for another token is never published', async () => {
  const f = fixture();
  const issue = f.server.issue.bind(f.server);
  f.server.issue = async (request) => {
    const value = await issue(request);
    const tokenId = '00000000-0000-0000-0000-000000000099';
    return {
      ...value,
      receipt: { ...value.receipt, tokenId, principal: `@service/${tokenId}` },
    };
  };
  await assert.rejects(
    f.engine.install(setupSource()),
    /Foreign setup credential/,
  );
  assert.equal(f.repository.secrets.size, 0);
  assert.equal(f.repository.published, null);
});

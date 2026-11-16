import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import type { Connection } from 'plowshare-client-ts/binding/connection';
import { connectionDirectory } from '../build/connections.js';
import {
  enableWorkspaceRecovery,
  recoverableClaim,
  releaseWorkspaceRecovery,
  workspaceRecoveryKey,
} from '../build/workspace-recovery.js';

const connection = (): Connection => ({
  ask: async () => ({ code: 'OK' }),
  close() {},
});
const claim = {
  machine: 'fixture-machine',
  root: '/fixture/workspace',
  project: 'project',
};

await test('reopening uses the same private workspace capability, isolated by server account and root', async () => {
  const configuration = await mkdtemp(
    join(tmpdir(), 'plowshare-workspace-recovery-'),
  );
  try {
    const first = connection();
    enableWorkspaceRecovery(
      first,
      'https://fixture.example',
      'alice',
      configuration,
    );
    const key = await workspaceRecoveryKey(first, claim);
    assert.match(key ?? '', /^[a-f0-9]{64}$/);
    const concurrent = connection();
    enableWorkspaceRecovery(
      concurrent,
      'https://fixture.example',
      'alice',
      configuration,
    );
    assert.notEqual(await workspaceRecoveryKey(concurrent, claim), key);
    await releaseWorkspaceRecovery(first);
    const reopened = connection();
    enableWorkspaceRecovery(
      reopened,
      'https://fixture.example',
      'alice',
      configuration,
    );
    assert.equal(await workspaceRecoveryKey(reopened, claim), key);
    assert.notEqual(
      await workspaceRecoveryKey(reopened, {
        ...claim,
        root: '/fixture/other',
      }),
      key,
    );
    const otherAccount = connection();
    enableWorkspaceRecovery(
      otherAccount,
      'https://fixture.example',
      'bob',
      configuration,
    );
    assert.notEqual(await workspaceRecoveryKey(otherAccount, claim), key);
    assert.equal((await recoverableClaim(reopened, claim)).recovery, key);
    assert.equal('recovery' in claim, false);
    const directory = join(
      connectionDirectory('https://fixture.example', 'alice', configuration),
      'workspaces',
    );
    for (const file of await readdir(directory)) {
      assert.equal((await stat(join(directory, file))).mode & 0o777, 0o600);
      assert.ok(
        Array.isArray(
          JSON.parse(await readFile(join(directory, file), 'utf8')),
        ),
      );
    }
  } finally {
    await rm(configuration, { recursive: true, force: true });
  }
});

await test('a connection without durable client configuration keeps legacy isolated attachment semantics', async () => {
  assert.equal(await workspaceRecoveryKey(connection(), claim), undefined);
});

await test('an exited client process leaves a recoverable identity for the reopened client', async () => {
  const configuration = await mkdtemp(
    join(tmpdir(), 'plowshare-workspace-process-'),
  );
  try {
    const module = new URL('../build/workspace-recovery.js', import.meta.url)
      .href;
    const { stdout } = await promisify(execFile)(
      process.execPath,
      [
        '--input-type=module',
        '--eval',
        `import { enableWorkspaceRecovery, workspaceRecoveryKey } from ${JSON.stringify(module)};
       const connection = { ask: async () => ({ code: 'OK' }), close() {} };
       enableWorkspaceRecovery(connection, 'https://fixture.example', 'alice', process.argv[1]);
       process.stdout.write(await workspaceRecoveryKey(connection, ${JSON.stringify(claim)}));`,
        configuration,
      ],
      { timeout: 5000 },
    );
    const reopened = connection();
    enableWorkspaceRecovery(
      reopened,
      'https://fixture.example',
      'alice',
      configuration,
    );
    assert.equal(await workspaceRecoveryKey(reopened, claim), stdout);
    await releaseWorkspaceRecovery(reopened);
  } finally {
    await rm(configuration, { recursive: true, force: true });
  }
});

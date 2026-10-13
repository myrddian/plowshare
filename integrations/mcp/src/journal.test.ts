import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FileToolJournal, CatalogueIntent } from './journal.ts';
import { RegistrationIntent } from './journal.ts';
import { FileRecoveryCatalogues } from './recovery.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import { McpProvider } from './provider.ts';
import {
  Credentials,
  Sessions,
  registration,
  options,
} from './provider.test-support.ts';
import { id, secondId, invocation } from './provider.test-support.ts';

await test('SDK receipts reopen durably without credentials; uncertain catalogue intent blocks restart', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-journal-'));
  try {
    const identity = 'a'.repeat(64);
    const journal = await FileToolJournal.open(directory, identity);
    const request = JSON.stringify(invocation('mcp_echo_fixture'));
    await journal.save({
      invocationId: id,
      request,
      phase: 'executing',
      result: null,
      occurredAt: null,
      hostedWorker: { binding: id, registration: secondId },
    });
    const reopened = await FileToolJournal.open(directory, identity);
    assert.deepEqual(await reopened.all(), await journal.all());
    assert.equal(
      (await readFile(join(directory, identity + '.json'), 'utf8')).includes(
        'fixture-secret',
      ),
      false,
    );
    const intent = await CatalogueIntent.open(directory);
    await intent.begin(secondId, 'publish');
    await assert.rejects(
      CatalogueIntent.open(directory),
      /operator reconciliation/,
    );
    await intent.settled();
    await CatalogueIntent.open(directory);
    const registrationIntent = await RegistrationIntent.open(directory);
    await registrationIntent.begin(secondId);
    await assert.rejects(
      RegistrationIntent.open(directory),
      /operator reconciliation/,
    );
    await registrationIntent.settled();
    await RegistrationIntent.open(directory);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test('recovery retains immutable declarations and original registration across rotation', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-recovery-'));
  const adapter = await McpProvider.discover(
    registration,
    new Credentials(),
    new Sessions(),
    options,
  );
  try {
    const repository = new FileRecoveryCatalogues(directory, id);
    const catalogue = {
      identity: adapter.journalIdentity,
      registration,
      declarations: adapter.tools.map((tool) => tool.declaration),
    };
    await repository.save(catalogue);
    await repository.save({
      ...catalogue,
      registration: { ...registration, registration: id },
    });
    const saved = (await repository.all())[0];
    assert.ok(saved);
    assert.equal(saved.registration.registration, secondId);
    assert.deepEqual(saved.declarations, catalogue.declarations);
    await assert.rejects(
      repository.save({ ...catalogue, identity: 'b'.repeat(64) }),
    );
    await assert.rejects(new FileRecoveryCatalogues(directory, secondId).all());
  } finally {
    await adapter.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('configured recovery archive limit refuses new generations without losing retained catalogues', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-recovery-limit-'));
  const first = await McpProvider.discover(
    registration,
    new Credentials(),
    new Sessions(),
    options,
  );
  const second = await McpProvider.discover(
    registration,
    new Credentials(),
    new Sessions(),
    { ...options, limits: { ...DEFAULT_LIMITS, maxPages: 1 } },
  );
  const catalogue = (adapter: McpProvider) => ({
    identity: adapter.journalIdentity,
    registration,
    declarations: adapter.tools.map((tool) => tool.declaration),
  });
  try {
    const repository = new FileRecoveryCatalogues(directory, id, 1);
    await repository.save(catalogue(first));
    await repository.save(catalogue(first));
    await assert.rejects(
      repository.save(catalogue(second)),
      /configured bound/,
    );
    assert.equal((await repository.all()).length, 1);
    const expanded = new FileRecoveryCatalogues(directory, id, 2);
    await expanded.save(catalogue(second));
    assert.equal((await expanded.all()).length, 2);
    await assert.rejects(repository.all(), /exceeds bound/);
    assert.equal((await expanded.all()).length, 2);
  } finally {
    await first.close();
    await second.close();
    await rm(directory, { recursive: true, force: true });
  }
});

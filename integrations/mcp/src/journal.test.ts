import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FileToolJournal, CatalogueIntent } from './journal.ts';
import { RegistrationIntent } from './journal.ts';
import { FileRecoveryCatalogues, reconcileCatalogues } from './recovery.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import { McpProvider } from './provider.ts';
import {
  Credentials,
  Sessions,
  registration,
  options,
} from './provider.test-support.ts';
import { id, secondId, invocation } from './provider.test-support.ts';
import { decodeToolCall } from 'plowshare-client-node/tools';
import { decodeMcpTool, object } from './codec.ts';
import { rawTool } from './wire.test-support.ts';
import type { ProviderConnection } from './provider.ts';
import type { Operation } from 'plowshare-client-ts/operations/direct';
import type { Payloads, Reply } from 'plowshare-client-ts';
import { decodeReply } from 'plowshare-client-ts/operations/schema';

await test('journal requests preserve valid SDK Unicode envelopes larger in bytes than character units', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-unicode-receipt-'));
  try {
    const request = JSON.stringify({
      ...invocation('mcp_echo_fixture'),
      arguments: {
        values: Array.from({ length: 3 }, () => ({ name: '漢'.repeat(4096) })),
      },
    });
    assert.ok(request.length < 32768);
    assert.ok(Buffer.byteLength(request) > 32768);
    decodeToolCall(request);
    const journal = await FileToolJournal.open(directory, 'a'.repeat(64));
    await journal.save({
      invocationId: id,
      request,
      phase: 'executing',
      result: null,
      occurredAt: null,
    });
    assert.ok(
      (await (await FileToolJournal.open(directory, 'a'.repeat(64))).all())[0]
        ?.request === request,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

await test('recovery reopens descriptions admitted above the default metadata budget', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-long-recovery-'));
  const sessions = new Sessions();
  const limits = { ...DEFAULT_LIMITS, maxDescriptionLength: 4096 };
  sessions.tools = [
    decodeMcpTool({ ...rawTool, description: 'd'.repeat(2048) }, limits),
  ];
  const adapter = await McpProvider.discover(
    registration,
    new Credentials(),
    sessions,
    { ...options, limits },
  );
  try {
    const repository = new FileRecoveryCatalogues(directory, id);
    await repository.save({
      identity: adapter.journalIdentity,
      registration,
      declarations: adapter.tools.map((tool) => tool.declaration),
    });
    assert.equal(
      (await repository.all())[0]?.declarations[0]?.description.length,
      2048,
    );
  } finally {
    await adapter.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('retired uncertain publication settles a late verified result through only its original worker read', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-late-result-'));
  const sessions = new Sessions();
  const adapter = await McpProvider.discover(
    registration,
    new Credentials(),
    sessions,
    options,
  );
  try {
    const repository = new FileRecoveryCatalogues(directory, id);
    await repository.save({
      identity: adapter.journalIdentity,
      registration,
      declarations: adapter.tools.map((tool) => tool.declaration),
    });
    const journal = await FileToolJournal.open(
      directory,
      adapter.journalIdentity,
    );
    const tool = adapter.tools[0];
    assert.ok(tool);
    const request = JSON.stringify(invocation(tool.declaration.name));
    const result = {
      state: 'COMPLETED' as const,
      text: 'Verified late provider result',
    };
    const occurredAt = '2026-10-16T00:00:00Z';
    await journal.save({
      invocationId: id,
      request,
      phase: 'publishing',
      result,
      occurredAt,
      hostedWorker: { binding: id, registration: secondId },
    });
    let verified = false;
    let reads = 0;
    const connection: ProviderConnection = {
      async request<K extends Operation>(
        operation: K,
        payload: Payloads[K],
      ): Promise<Reply<K>> {
        assert.equal(operation, 'tool.scope.worker.read');
        const original = object(payload);
        assert.equal(original.binding, id);
        assert.equal(original.registration, secondId);
        assert.equal(original.invocation, id);
        reads++;
        return {
          kind: 'success',
          outcome: {
            code: 'OK',
            payload: decodeReply(operation, {
              ...original,
              verified,
              state: verified ? result.state : 'UNKNOWN',
              text: verified ? result.text : 'Not verified yet',
            }),
          },
        };
      },
    };
    await adapter.close();
    assert.equal(
      await reconcileCatalogues(connection, repository, directory),
      0,
    );
    assert.equal(
      (
        await (
          await FileToolJournal.open(directory, adapter.journalIdentity)
        ).all()
      )[0]?.phase,
      'publishing',
    );
    verified = true;
    assert.equal(
      await reconcileCatalogues(connection, repository, directory),
      1,
    );
    const settled = (
      await (
        await FileToolJournal.open(directory, adapter.journalIdentity)
      ).all()
    )[0];
    assert.ok(settled);
    assert.equal(settled.phase, 'done');
    assert.deepEqual(settled.result, result);
    assert.equal(settled.occurredAt, occurredAt);
    assert.equal(settled.request, request);
    assert.equal(reads, 2);
    assert.equal(sessions.calls, 0);
    assert.equal(
      await reconcileCatalogues(connection, repository, directory),
      0,
    );
  } finally {
    await adapter.close();
    await rm(directory, { recursive: true, force: true });
  }
});

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

await test('retired interrupted execution is durably UNKNOWN before any new MCP session or request intake', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-interrupted-'));
  const adapter = await McpProvider.discover(
    registration,
    new Credentials(),
    new Sessions(),
    options,
  );
  try {
    const catalogues = new FileRecoveryCatalogues(directory, id);
    await catalogues.save({
      identity: adapter.journalIdentity,
      registration,
      declarations: adapter.tools.map((tool) => tool.declaration),
    });
    const journal = await FileToolJournal.open(
      directory,
      adapter.journalIdentity,
    );
    const tool = adapter.tools[0];
    assert.ok(tool);
    const request = JSON.stringify(invocation(tool.declaration.name));
    await journal.save({
      invocationId: id,
      request,
      phase: 'executing',
      result: null,
      occurredAt: null,
      hostedWorker: { binding: id, registration: secondId },
    });
    const connection = {
      request() {
        throw new Error('Recovery must not consume, publish or dispatch work');
      },
    };
    assert.equal(
      await reconcileCatalogues(connection, catalogues, directory),
      0,
    );
    const reopened = await FileToolJournal.open(
      directory,
      adapter.journalIdentity,
    );
    const receipt = (await reopened.all())[0];
    assert.ok(receipt);
    assert.equal(receipt.phase, 'ready');
    assert.ok(receipt.result);
    assert.equal(receipt.result.state, 'UNKNOWN');
    assert.match(receipt.result.text, /Execution was interrupted/);
    assert.equal(receipt.request, request);
    assert.deepEqual(receipt.hostedWorker, {
      binding: id,
      registration: secondId,
    });
    assert.equal(
      await reconcileCatalogues(connection, catalogues, directory),
      0,
    );
    assert.deepEqual(
      await (
        await FileToolJournal.open(directory, adapter.journalIdentity)
      ).all(),
      await reopened.all(),
    );
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

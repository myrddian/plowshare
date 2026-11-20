import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { toolDeploymentConfig } from 'plowshare-client-node/tools';
import {
  ComputerPasses,
  FileCatalogueIntents,
  runComputerHost,
} from './runner.ts';
import { decodeComputerHostConfig } from './config.ts';
import { computerDeclarations } from './provider.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import { FakeClock } from './contracts.test-support.ts';
import {
  binding,
  CaptureBackend,
  client,
  MemoryJournal,
  RelayWire,
} from './provider.test-support.ts';
import { BackendError } from './contracts.ts';
import { ConnectionFault } from 'plowshare-client-ts/binding/connection';

await test('SDK pass expiry retains ownership until its original promise settles', async () => {
  const clock = new FakeClock(),
    connection = client();
  const passes = new ComputerPasses(connection, 2000),
    deadline = new OperationDeadline(1000, clock);
  let finish: (() => void) | undefined,
    stopped = false;
  const running = passes.run(
    deadline,
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    () => {
      stopped = true;
    },
  );
  await Promise.resolve();
  const expired = assert.rejects(running, DeadlineExceeded);
  clock.advance(1001);
  await expired;
  assert.ok(stopped);
  const next = new OperationDeadline(1000, clock);
  await assert.rejects(
    passes.run(
      next,
      async () => {},
      () => {},
    ),
    /physically active/,
  );
  if (finish === undefined) assert.fail('Pass did not begin');
  finish();
  await passes.drain(next);
  next.dispose();
  connection.close();
});

await test('queued SDK pass rechecks monotonic deadline before starting its operation', async () => {
  const clock = new FakeClock(),
    connection = client();
  const passes = new ComputerPasses(connection, 2000),
    deadline = new OperationDeadline(1000, clock);
  let effects = 0;
  try {
    const running = passes.run(
      deadline,
      async () => {
        effects++;
      },
      () => {},
    );
    clock.monotonic = 1001; // Timer callbacks have not run yet.
    await assert.rejects(running, DeadlineExceeded);
    assert.equal(effects, 0);
  } finally {
    deadline.dispose();
    connection.close();
  }
});

await test('completed local work past a monotonic cutoff cannot become a late successful pass', async () => {
  const clock = new FakeClock(),
    connection = client();
  const passes = new ComputerPasses(connection, 2000),
    deadline = new OperationDeadline(1000, clock);
  let stopped = false;
  try {
    await assert.rejects(
      passes.run(
        deadline,
        async () => {
          clock.monotonic += 1001;
          return 'late';
        },
        () => {
          stopped = true;
        },
      ),
      DeadlineExceeded,
    );
    assert.ok(stopped);
  } finally {
    deadline.dispose();
    connection.close();
  }
});

await test('reply arriving beyond monotonic cutoff remains uncertain and stops the active pass', async () => {
  const clock = new FakeClock(),
    connection = client();
  const late: Pick<typeof connection, 'request'> = {
    request: async (type, payload, options) => {
      const reply = await connection.request(type, payload, options);
      clock.monotonic += 1001;
      return reply;
    },
  };
  const passes = new ComputerPasses(late, 2000),
    deadline = new OperationDeadline(1000, clock);
  let stopped = false,
    failure: unknown;
  try {
    await assert.rejects(
      passes.run(
        deadline,
        async () => {
          try {
            return await passes.connection.request('relay.publish', {
              project: binding.project,
              topic: 'fixture.results',
              requestId: '22222222-2222-2222-2222-222222222222',
              occurredAt: '2026-11-16T00:00:00Z',
              text: 'Controlled publication fixture',
            });
          } catch (error) {
            failure = error;
            throw error;
          }
        },
        () => {
          stopped = true;
        },
      ),
      DeadlineExceeded,
    );
    const drain = new OperationDeadline(1000, clock);
    try {
      await passes.drain(drain);
    } finally {
      drain.dispose();
    }
    assert.ok(stopped);
    assert.ok(failure instanceof ConnectionFault);
    assert.equal(failure.delivery, 'UNKNOWN');
  } finally {
    deadline.dispose();
    connection.close();
  }
});

await test('catalogue intent survives reopen and refuses replacement of uncertain metadata', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-intent-'));
  try {
    const store = new FileCatalogueIntents(directory, 'a'.repeat(64));
    const pending = {
      version: 1 as const,
      identity: 'a'.repeat(64),
      requestId: '11111111-1111-1111-1111-111111111111',
      kind: 'publish' as const,
      state: 'pending' as const,
    };
    await store.save(pending);
    assert.deepEqual(
      await new FileCatalogueIntents(directory, pending.identity).load(),
      pending,
    );
    await assert.rejects(
      store.save({
        ...pending,
        requestId: '22222222-2222-2222-2222-222222222222',
      }),
      /cannot be replaced/,
    );
    await store.save({ ...pending, state: 'confirmed' });
    assert.equal((await store.load())?.state, 'confirmed');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

function configFor(directory: string) {
  return decodeComputerHostConfig({
    version: 1,
    origin: 'https://fixture.invalid',
    binding,
    credential: { kind: 'saved', directory },
    journalDirectory: directory,
    catalogue: { leaseSeconds: 300, renewalMs: 30_000 },
    browser: {
      executablePath: '/fixture/browser',
      headless: true,
      targets: [
        {
          targetId: 'fixture-target',
          label: 'Fixture',
          url: 'https://fixture.invalid',
          viewport: { width: 100, height: 80 },
        },
      ],
    },
  });
}
const identity = createHash('sha256')
  .update(toolDeploymentConfig(binding, computerDeclarations()))
  .digest('hex');

await test('foreground host uses the same SDK provider, retains catalogue intent and closes owned resources', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-host-'));
  const wire = new RelayWire(),
    connection = client(wire),
    clock = new FakeClock(),
    backend = new CaptureBackend(clock);
  let closed = false,
    ready = false;
  try {
    await runComputerHost({
      config: configFor(directory),
      connection,
      currentBearer: async () => 'fixture',
      signal: new AbortController().signal,
      backends: [
        {
          backend,
          close: async () => {
            closed = true;
          },
        },
      ],
      journal: new MemoryJournal(identity),
      clock,
      maxPasses: 1,
      onReady: () => {
        ready = true;
      },
    });
    assert.ok(ready);
    assert.ok(closed);
    assert.equal(wire.publications.length, 2);
    assert.equal(
      (await new FileCatalogueIntents(directory, identity).load())?.kind,
      'withdraw',
    );
    assert.ok(
      !(await readdir(directory)).some(
        (name) => name.endsWith('.active') || name.endsWith('.lock'),
      ),
    );
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('uncertain backend cleanup retains a persistent host fence rather than allowing restart', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-fenced-host-'));
  const wire = new RelayWire(),
    connection = client(wire),
    clock = new FakeClock(),
    backend = new CaptureBackend(clock);
  const options = {
    config: configFor(directory),
    connection,
    currentBearer: async () => 'fixture',
    signal: new AbortController().signal,
    backends: [
      {
        backend,
        close: async () => {
          throw new BackendError('CAPTURE_FAILED', 'uncertain');
        },
      },
    ],
    journal: new MemoryJournal(identity),
    clock,
    maxPasses: 1,
  };
  try {
    await assert.rejects(runComputerHost(options), /cleanup is uncertain/);
    assert.ok(
      (await readdir(directory)).some((name) => name.endsWith('.active')),
    );
    await assert.rejects(runComputerHost(options), /prior computer host/);
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('authentication and setup consume the same earlier startup budget before any catalogue publication', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-startup-budget-'));
  const wire = new RelayWire(),
    connection = client(wire),
    clock = new FakeClock(),
    backend = new CaptureBackend(clock);
  const startup = new OperationDeadline(1000, clock);
  clock.advance(800); // Time already consumed by the host caller's authentication.
  let saved = 0,
    ready = false,
    closed = false;
  try {
    await assert.rejects(
      runComputerHost({
        config: configFor(directory),
        connection,
        currentBearer: async () => 'fixture',
        signal: new AbortController().signal,
        startupDeadline: startup,
        backends: [
          {
            backend,
            close: async () => {
              closed = true;
            },
          },
        ],
        journal: new MemoryJournal(identity),
        clock,
        maxPasses: 1,
        onReady: () => {
          ready = true;
        },
        intents: {
          load: async () => {
            clock.advance(201);
            return null;
          },
          save: async () => {
            saved++;
          },
        },
      }),
      DeadlineExceeded,
    );
    assert.equal(saved, 0);
    assert.equal(wire.publications.length, 0);
    assert.equal(ready, false);
    assert.ok(closed);
  } finally {
    startup.dispose();
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('oversized or corrupt private catalogue metadata stops startup before network publication', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-invalid-intent-'));
  const wire = new RelayWire(),
    connection = client(wire),
    clock = new FakeClock(),
    backend = new CaptureBackend(clock);
  const file = join(directory, identity + '.catalogue.json');
  try {
    for (const source of ['x'.repeat(4097), '{"version":']) {
      await writeFile(file, source, { mode: 0o600 });
      await assert.rejects(
        runComputerHost({
          config: configFor(directory),
          connection,
          currentBearer: async () => 'fixture',
          signal: new AbortController().signal,
          backends: [{ backend, close: async () => {} }],
          journal: new MemoryJournal(identity),
          clock,
          maxPasses: 1,
        }),
      );
      assert.equal(wire.publications.length, 0);
    }
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

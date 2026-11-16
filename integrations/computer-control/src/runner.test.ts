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
import { FakeBrowserControlBackend } from './browser-provider.test-support.ts';
import { FakeNativeControlBackend } from './native-provider.test-support.ts';
import { call, metadata, record } from './provider.test-support.ts';

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

await test('controlled foreground composition publishes matching tools and drains its injected lease', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-controlled-host-'));
  const wire = new RelayWire(),
    connection = client(wire),
    clock = new FakeClock(),
    backend = new FakeBrowserControlBackend(clock);
  const old = configFor(directory);
  const config = decodeComputerHostConfig({
    ...old,
    browser: undefined,
    browserControl: {
      ...old.browser,
      ownershipDirectory: join(directory, 'ownership'),
      control: {
        enabled: true,
        tools: backend.browserControl.allowedTools,
        allowedOrigins: ['https://fixture.invalid'],
      },
    },
    sessions: { idleMs: 90_000, frameFreshMs: 7000 },
    budgets: { ...old.budgets, browserObservationReserveMs: 4000 },
  });
  const declarations = computerDeclarations(
    config.prefix,
    {
      controlBudgetMs: config.budgets.controlMs,
      observeBudgetMs: config.budgets.observeMs,
    },
    backend.browserControl.allowedTools,
  );
  const hash = createHash('sha256')
    .update(toolDeploymentConfig(binding, declarations))
    .digest('hex');
  const journal = new MemoryJournal(hash);
  let closed = 0;
  try {
    await runComputerHost({
      config,
      connection,
      currentBearer: async () => 'fixture',
      signal: new AbortController().signal,
      backends: [
        {
          backend,
          close: async () => {
            closed++;
          },
        },
      ],
      browserControls: [backend],
      journal,
      clock,
      maxPasses: 1,
      onReady: () => {
        wire.calls.push(call(clock, 'open', { targetId: backend.targetId }));
      },
    });
    assert.equal(backend.acquireCalls, 1);
    assert.equal(backend.releaseCalls, 1);
    assert.equal(backend.hasLease, false);
    assert.equal(closed, 1);
    assert.equal(clock.pendingTimers(), 0);
    const published = wire.publications.find(
      (row) =>
        typeof row['topic'] === 'string' && row['topic'].endsWith('.catalog'),
    );
    assert.ok(published);
    const text = published['text'];
    assert.ok(typeof text === 'string');
    const catalog = record(JSON.parse(text)),
      tools = catalog['tools'];
    assert.ok(Array.isArray(tools));
    assert.equal(tools.length, 11);
    assert.equal(
      (await new FileCatalogueIntents(directory, hash).load())?.kind,
      'withdraw',
    );
    assert.equal(
      (await readdir(directory)).some(
        (name) => name.endsWith('.active') || name.endsWith('.lock'),
      ),
      false,
    );
    const receipt = [...journal.rows.values()][0];
    assert.ok(receipt?.result);
    assert.equal(metadata(receipt.result)['controlled'], true);
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('native foreground composition serves the shared SDK provider and drains its exact lease', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-native-host-'));
  const wire = new RelayWire();
  const connection = client(wire);
  const clock = new FakeClock();
  const backend = new FakeNativeControlBackend(clock);
  const old = configFor(directory);
  const config = decodeComputerHostConfig({
    ...old,
    browser: undefined,
    macosControl: {
      helperExecutable: join(directory, 'helper'),
      ownershipDirectory: join(directory, 'ownership'),
      targets: [
        {
          targetId: backend.targetId,
          label: 'Synthetic native',
          surfaceId: backend.surfaceId,
        },
      ],
      control: {
        enabled: true,
        tools: backend.allowedTools,
        allowedBundleIds: ['org.example.NativeFixture'],
      },
    },
  });
  const identity = createHash('sha256')
    .update(
      toolDeploymentConfig(
        binding,
        computerDeclarations(
          config.prefix,
          {
            controlBudgetMs: config.budgets.controlMs,
            observeBudgetMs: config.budgets.observeMs,
          },
          [],
          backend.allowedTools,
        ),
      ),
    )
    .digest('hex');
  const journal = new MemoryJournal(identity);
  let closed = 0;
  try {
    await runComputerHost({
      config,
      connection,
      currentBearer: async () => 'fixture',
      signal: new AbortController().signal,
      backends: [
        {
          backend,
          close: async () => {
            closed++;
          },
        },
      ],
      nativeControls: [backend],
      journal,
      clock,
      maxPasses: 1,
      onReady: () =>
        wire.calls.push(call(clock, 'open', { targetId: backend.targetId })),
    });
    assert.equal(backend.acquireCalls, 1);
    assert.equal(backend.releaseCalls, 1);
    assert.equal(backend.hasLease, false);
    assert.equal(closed, 1);
    assert.equal(clock.pendingTimers(), 0);
    assert.equal(wire.publications.length, 3);
    assert.equal(
      (await new FileCatalogueIntents(directory, identity).load())?.kind,
      'withdraw',
    );
    assert.equal(
      (await readdir(directory)).some(
        (name) => name.endsWith('.active') || name.endsWith('.lock'),
      ),
      false,
    );
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('dual-port native host injection refuses before filesystem, transport or ownership transfer', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'computer-native-dual-port-'));
  const wire = new RelayWire();
  const connection = client(wire);
  const clock = new FakeClock();
  const browser = new FakeBrowserControlBackend(clock);
  const native = new FakeNativeControlBackend(clock);
  let transport = 0;
  const send = wire.send.bind(wire);
  wire.send = (source) => {
    transport++;
    send(source);
  };
  let backendCalls = 0;
  let closed = 0;
  let credentialCalls = 0;
  const dual = {
    desktopIsolation: 'native-broker' as const,
    targets: async () => {
      backendCalls++;
      return [];
    },
    capture: async () => {
      backendCalls++;
      throw new Error('Unexpected capture');
    },
    browserControl: browser.browserControl,
    nativeControl: native.nativeControl,
  };
  const old = configFor(directory);
  const config = decodeComputerHostConfig({
    ...old,
    browser: undefined,
    browserControl: {
      ...old.browser,
      ownershipDirectory: join(directory, 'browser-ownership'),
      control: {
        enabled: true,
        tools: browser.browserControl.allowedTools,
        allowedOrigins: ['https://fixture.invalid'],
      },
    },
    macosControl: {
      helperExecutable: join(directory, 'helper'),
      ownershipDirectory: join(directory, 'native-ownership'),
      targets: [
        {
          targetId: native.targetId,
          label: 'Native',
          surfaceId: native.surfaceId,
        },
      ],
      control: {
        enabled: true,
        tools: native.allowedTools,
        allowedBundleIds: ['org.example.NativeFixture'],
      },
    },
  });
  try {
    await assert.rejects(
      runComputerHost({
        config,
        connection,
        currentBearer: async () => {
          credentialCalls++;
          return 'fixture';
        },
        signal: new AbortController().signal,
        backends: [
          {
            backend: dual,
            close: async () => {
              closed++;
            },
          },
        ],
        browserControls: [dual],
        nativeControls: [dual],
        clock,
        maxPasses: 1,
      }),
      /both native and browser/,
    );
    assert.deepEqual(await readdir(directory), []);
    assert.equal(transport, 0);
    assert.equal(credentialCalls, 0);
    assert.equal(backendCalls, 0);
    assert.equal(closed, 0);
    assert.equal(native.acquireCalls, 0);
    assert.equal(browser.acquireCalls, 0);
  } finally {
    connection.close();
    await rm(directory, { recursive: true, force: true });
  }
});

await test('mismatched controlled host injection fails before local state or catalogue effects', async () => {
  const directory = await mkdtemp(
    join(tmpdir(), 'computer-controlled-config-'),
  );
  const wire = new RelayWire(),
    connection = client(wire),
    clock = new FakeClock(),
    backend = new FakeBrowserControlBackend(clock);
  const old = configFor(directory);
  const config = decodeComputerHostConfig({
    ...old,
    browser: undefined,
    browserControl: {
      ...old.browser,
      ownershipDirectory: join(directory, 'ownership'),
      control: {
        enabled: true,
        tools: backend.browserControl.allowedTools,
        allowedOrigins: ['https://fixture.invalid'],
      },
    },
  });
  let closed = 0;
  const options = {
    config,
    connection,
    currentBearer: async () => 'fixture',
    signal: new AbortController().signal,
    backends: [
      {
        backend,
        close: async () => {
          closed++;
        },
      },
    ],
    clock,
    maxPasses: 1,
  };
  try {
    await assert.rejects(
      runComputerHost(options),
      /exact browser control backend/,
    );
    const other = new FakeBrowserControlBackend(clock);
    await assert.rejects(
      runComputerHost({ ...options, browserControls: [other] }),
      /same selected backend instance/,
    );
    const controlled = config.browserControl;
    assert.ok(controlled);
    const limited = decodeComputerHostConfig({
      ...config,
      browserControl: {
        ...controlled,
        control: { ...controlled.control, tools: ['windows'] },
      },
    });
    await assert.rejects(
      runComputerHost({
        ...options,
        config: limited,
        browserControls: [backend],
      }),
      /match the configured backend capabilities/,
    );
    assert.deepEqual(await readdir(directory), []);
    assert.equal(wire.publications.length, 0);
    assert.equal(backend.acquireCalls, 0);
    assert.equal(other.acquireCalls, 0);
    assert.equal(closed, 0);
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

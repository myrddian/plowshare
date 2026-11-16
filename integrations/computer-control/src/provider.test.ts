import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { toolDeploymentConfig } from 'plowshare-client-node/tools';
import type { ToolCall } from 'plowshare-client-ts';
import { createComputerProvider, computerDeclarations } from './provider.ts';
import { FakeClock } from './contracts.test-support.ts';
import {
  admissions,
  binding,
  call,
  CaptureBackend,
  client,
  MemoryJournal,
  metadata,
  record,
  RelayWire,
} from './provider.test-support.ts';
import { BackendError, decodeCloseArguments } from './contracts.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import { connect } from 'plowshare-client-ts/binding/connection';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { computerProviderConnection } from './provider-connection.ts';

async function fixture(
  options: {
    readonly http?: typeof fetch;
    readonly maxImagesPerRun?: number;
    readonly parentDeadline?: () => OperationDeadline | undefined;
  } = {},
) {
  const clock = new FakeClock(),
    backend = new CaptureBackend(clock),
    wire = new RelayWire(),
    connection = client(wire);
  const identity = createHash('sha256')
    .update(toolDeploymentConfig(binding, computerDeclarations()))
    .digest('hex');
  const journal = new MemoryJournal(identity);
  const provider = await createComputerProvider({
    connection,
    binding,
    journal,
    backends: [backend],
    generation: 'generation',
    origin: 'https://fixture.invalid',
    currentBearer: async () => 'fixture-bearer',
    clock,
    http: options.http ?? admissions(),
    ...(options.maxImagesPerRun === undefined
      ? {}
      : { maxImagesPerRun: options.maxImagesPerRun }),
    ...(options.parentDeadline === undefined
      ? {}
      : { parentDeadline: options.parentDeadline }),
  });
  const invoke = async (
    operation: string,
    args: ToolCall['arguments'] = {},
  ) => {
    const registered = provider.tools.find(
      (tool) => tool.declaration.name === 'computer_' + operation,
    );
    if (registered === undefined) assert.fail('Missing registered tool');
    return registered.handler(call(clock, operation, args));
  };
  const open = async () => {
    const session = record(
      metadata(await invoke('open', { targetId: 'fixture-target' }))['session'],
    );
    return decodeCloseArguments({
      sessionId: session['sessionId'],
      generation: session['generation'],
    });
  };
  return { clock, backend, wire, connection, journal, provider, invoke, open };
}

await test('reusable SDK embedding returns admitted image and exact geometry without opening a connection', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    const result = await f.invoke('observe', {
      ...session,
      surfaceId: 'viewport',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.equal(f.backend.captures, 1);
    assert.equal(result.content?.parts[1]?.kind, 'IMAGE');
    assert.equal(result.content?.parts[1]?.use, 'OBSERVATION');
    assert.equal(result.admissions?.[0]?.width, 100);
    assert.deepEqual(record(metadata(result)['frame'])['transform'], {
      a: 1,
      b: 0,
      c: 0,
      d: 1,
      e: -300,
      f: 50,
    });
    assert.equal(f.provider.journalIdentity, f.journal.identity);
  } finally {
    f.connection.close();
  }
});

await test('existing TUI/GUI Connection transport supplies the SDK provider on its original socket', async () => {
  const wire = new RelayWire();
  const existing = connect({ dispatchMode: 'ordered', socket: wire });
  const connection = computerProviderConnection(checkedTransport(existing));
  const clock = new FakeClock(),
    backend = new CaptureBackend(clock);
  const identity = createHash('sha256')
    .update(toolDeploymentConfig(binding, computerDeclarations()))
    .digest('hex');
  try {
    const provider = await createComputerProvider({
      connection,
      binding,
      journal: new MemoryJournal(identity),
      backends: [backend],
      generation: 'embedded-generation',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'session-owned-token',
      http: admissions(),
      clock,
    });
    wire.calls.push(call(clock, 'targets'));
    assert.equal(await provider.provider.poll(), 1);
    assert.equal(wire.publications.length, 1);
    assert.equal(backend.captures, 0);
  } finally {
    existing.close();
  }
});

await test('denied native discovery cannot disable an available browser or refetch on selected browser work', async () => {
  const clock = new FakeClock(),
    backend = new CaptureBackend(clock),
    connection = client();
  let deniedChecks = 0;
  const denied = {
    targets: async () => {
      deniedChecks++;
      throw new BackendError('PERMISSION_DENIED');
    },
    capture: async () => {
      throw new BackendError('PERMISSION_DENIED');
    },
  };
  const identity = createHash('sha256')
    .update(toolDeploymentConfig(binding, computerDeclarations()))
    .digest('hex');
  try {
    const provider = await createComputerProvider({
      connection,
      binding,
      journal: new MemoryJournal(identity),
      backends: [backend, denied],
      generation: 'generation',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      http: admissions(),
      clock,
    });
    const invoke = async (tool: string, args: ToolCall['arguments'] = {}) => {
      const registered = provider.tools.find(
        (row) => row.declaration.name === 'computer_' + tool,
      );
      if (registered === undefined) assert.fail('Missing tool');
      return registered.handler(call(clock, tool, args));
    };
    const listed = metadata(await invoke('targets'));
    assert.equal(
      Array.isArray(listed['targets']) && listed['targets'].length,
      1,
    );
    assert.deepEqual(listed['unavailable'], [
      { backendIndex: 1, code: 'PERMISSION_DENIED' },
    ]);
    const opened = record(
      metadata(await invoke('open', { targetId: 'fixture-target' }))['session'],
    );
    const session = decodeCloseArguments({
      sessionId: opened['sessionId'],
      generation: opened['generation'],
    });
    assert.equal(
      (await invoke('observe', { ...session, surfaceId: 'viewport' })).state,
      'COMPLETED',
    );
    assert.equal(deniedChecks, 1);
    assert.equal(backend.captures, 1);
  } finally {
    connection.close();
  }
});

await test('malformed, foreign session and unavailable surface reject without capture or upload', async () => {
  let uploads = 0;
  const f = await fixture({
    http: async (...args) => {
      uploads++;
      return admissions()(...args);
    },
  });
  try {
    const session = await f.open();
    for (const args of [
      { ...session, surfaceId: 'other' },
      { ...session, generation: 'old', surfaceId: 'viewport' },
      { ...session, surfaceId: 'viewport', host: 'foreign' },
    ]) {
      assert.equal((await f.invoke('observe', args)).state, 'REJECTED');
    }
    assert.equal(f.backend.captures, 0);
    assert.equal(uploads, 0);
  } finally {
    f.connection.close();
  }
});

await test('provider rejects actual Personal namespace while retaining ordinary named and client scopes', async () => {
  const clock = new FakeClock(),
    backend = new CaptureBackend(clock),
    connection = client();
  try {
    for (const project of [
      'personal:abcdef',
      'PERSONAL:abcdef',
      'global',
      'Personal',
      'client:fixture',
    ]) {
      const selectedBinding = { ...binding, project };
      const identity = createHash('sha256')
        .update(toolDeploymentConfig(selectedBinding, computerDeclarations()))
        .digest('hex');
      const creating = createComputerProvider({
        connection,
        binding: selectedBinding,
        journal: new MemoryJournal(identity),
        backends: [backend],
        generation: 'generation',
        origin: 'https://fixture.invalid',
        currentBearer: async () => 'fixture',
        clock,
      });
      if (project.toLowerCase().startsWith('personal:'))
        await assert.rejects(creating, /ordinary explicit project/);
      else assert.equal((await creating).journalIdentity, identity);
    }
    assert.equal(backend.captures, 0);
  } finally {
    connection.close();
  }
});

await test('admission geometry/producer mismatch withholds pixels and preserves UNKNOWN', async () => {
  for (const http of [
    admissions({ width: 101 }),
    admissions({ producer: 'other' }),
  ]) {
    const f = await fixture({ http });
    try {
      const session = await f.open();
      const result = await f.invoke('observe', {
        ...session,
        surfaceId: 'viewport',
      });
      assert.equal(result.state, 'UNKNOWN');
      assert.equal(
        result.content?.parts.some((part) => part.kind === 'IMAGE'),
        false,
      );
    } finally {
      f.connection.close();
    }
  }
});

await test('capture intent limit survives close/open and upload uncertainty does not replay capture', async () => {
  let uploads = 0;
  const f = await fixture({
    maxImagesPerRun: 1,
    http: async () => {
      uploads++;
      throw new Error('lost receipt');
    },
  });
  try {
    let session = await f.open();
    assert.equal(
      (await f.invoke('observe', { ...session, surfaceId: 'viewport' })).state,
      'UNKNOWN',
    );
    await f.invoke('close', { ...session });
    session = await f.open();
    const refused = await f.invoke('observe', {
      ...session,
      surfaceId: 'viewport',
    });
    assert.equal(refused.state, 'REJECTED');
    assert.equal(metadata(refused)['code'], 'FRAME_LIMIT');
    assert.equal(f.backend.captures, 1);
    assert.equal(uploads, 1);
  } finally {
    f.connection.close();
  }
});

await test('SDK polling deduplicates delivery and journals image receipt before publication', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    const invocation = call(f.clock, 'observe', {
      ...session,
      surfaceId: 'viewport',
    });
    f.wire.calls.push(invocation);
    assert.equal(await f.provider.provider.poll(), 1);
    const receipt = f.journal.rows.get(invocation.invocationId);
    assert.equal(receipt?.phase, 'done');
    assert.equal(receipt.result?.admissions?.length, 1);
    f.wire.calls.push(invocation);
    assert.equal(await f.provider.provider.poll(), 1);
    assert.equal(f.backend.captures, 1);
    assert.equal(f.wire.publications.length, 1);
  } finally {
    f.connection.close();
  }
});

await test('lost publication reply reconciles original typed result without recapture or republish', async () => {
  const f = await fixture();
  let reconnected: ReturnType<typeof client> | undefined;
  try {
    const session = await f.open();
    const invocation = call(f.clock, 'observe', {
      ...session,
      surfaceId: 'viewport',
    });
    f.wire.calls.push(invocation);
    f.wire.losePublication = true;
    await assert.rejects(f.provider.provider.poll());
    assert.equal(
      f.journal.rows.get(invocation.invocationId)?.phase,
      'publishing',
    );
    const wire = new RelayWire(f.wire.publications);
    reconnected = client(wire);
    const restarted = await createComputerProvider({
      connection: reconnected,
      binding,
      journal: f.journal,
      backends: [f.backend],
      generation: 'next-generation',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      http: admissions(),
      clock: f.clock,
    });
    assert.equal(await restarted.provider.reconcile(), 1);
    assert.equal(f.backend.captures, 1);
    assert.equal(wire.publications.length, 1);
    assert.equal(f.journal.rows.get(invocation.invocationId)?.phase, 'done');
  } finally {
    reconnected?.close();
    f.connection.close();
  }
});

await test('late capture consumes shared deadline and no upload follows expiry', async () => {
  let uploads = 0;
  const f = await fixture({
    http: async (...args) => {
      uploads++;
      return admissions()(...args);
    },
  });
  try {
    f.backend.capture = async () => {
      f.backend.captures++;
      f.clock.advance(10_001);
      return f.backend.value;
    };
    const session = await f.open();
    const result = await f.invoke('observe', {
      ...session,
      surfaceId: 'viewport',
    });
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(uploads, 0);
    assert.throws(
      () =>
        f.provider.sessions.open(
          {
            account: 'owner',
            project: binding.project,
            provider: binding.provider,
            run: 'other',
          },
          'fixture-target',
        ),
      /BUSY/,
    );
  } finally {
    f.connection.close();
  }
});

await test('stop disables new work and drain uses an explicit finite budget', async () => {
  const f = await fixture();
  const deadline = new OperationDeadline(1000, f.clock);
  try {
    f.provider.stop();
    assert.equal((await f.invoke('targets')).state, 'REJECTED');
    await f.provider.drain(deadline);
    assert.equal(f.backend.captures, 0);
  } finally {
    deadline.dispose();
    f.connection.close();
  }
});

await test('stop during SDK capture publishes UNKNOWN once while drain retains actual physical work', async () => {
  let uploads = 0;
  const f = await fixture({
    http: async (...args) => {
      uploads++;
      return admissions()(...args);
    },
  });
  let started: (() => void) | undefined,
    finish: ((value: typeof f.backend.value) => void) | undefined;
  const entered = new Promise<void>((resolve) => {
    started = resolve;
  });
  f.backend.capture = () => {
    f.backend.captures++;
    started?.();
    return new Promise((resolve) => {
      finish = resolve;
    });
  };
  const cleanup = new OperationDeadline(25, f.clock);
  try {
    const session = await f.open();
    const invocation = call(f.clock, 'observe', {
      ...session,
      surfaceId: 'viewport',
    });
    f.wire.calls.push(invocation);
    const polling = f.provider.provider.poll();
    await entered;
    f.provider.stop();
    await polling;
    assert.equal(
      f.journal.rows.get(invocation.invocationId)?.result?.state,
      'UNKNOWN',
    );
    assert.equal(f.wire.publications.length, 1);
    const stoppedDrain = assert.rejects(
      f.provider.drain(cleanup),
      DeadlineExceeded,
    );
    f.clock.advance(26);
    await stoppedDrain;
    assert.throws(
      () =>
        f.provider.sessions.open(
          {
            account: 'owner',
            project: binding.project,
            provider: binding.provider,
            run: 'other',
          },
          'fixture-target',
        ),
      /BUSY/,
    );
    if (finish === undefined) assert.fail('Capture never started');
    finish(f.backend.value);
    const settled = new OperationDeadline(1000, f.clock);
    try {
      await f.provider.drain(settled);
    } finally {
      settled.dispose();
    }
    assert.equal(uploads, 0);
    assert.equal(f.backend.captures, 1);
    assert.equal(f.wire.publications.length, 1);
    assert.equal(
      f.journal.rows.get(invocation.invocationId)?.result?.state,
      'UNKNOWN',
    );
  } finally {
    cleanup.dispose();
    f.connection.close();
  }
});

await test('restarted executing observation becomes UNKNOWN without capture and restores conservative intent count', async () => {
  const f = await fixture();
  let restartedConnection: ReturnType<typeof client> | undefined;
  try {
    const original = call(f.clock, 'observe', {
      sessionId: 'old-session',
      generation: 'old-generation',
      surfaceId: 'viewport',
    });
    await f.provider.journal.save({
      invocationId: original.invocationId,
      request: JSON.stringify(original),
      phase: 'executing',
      result: null,
      occurredAt: null,
    });
    const wire = new RelayWire();
    restartedConnection = client(wire);
    const restarted = await createComputerProvider({
      connection: restartedConnection,
      binding,
      journal: f.journal,
      backends: [f.backend],
      generation: 'restarted',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      http: admissions(),
      clock: f.clock,
      maxImagesPerRun: 1,
    });
    await restarted.provider.poll();
    assert.equal(
      f.journal.rows.get(original.invocationId)?.result?.state,
      'UNKNOWN',
    );
    assert.equal(f.backend.captures, 0);
    assert.throws(
      () =>
        restarted.sessions.reserveImage({
          account: 'owner',
          project: binding.project,
          provider: binding.provider,
          run: 'run',
        }),
      /FRAME_LIMIT/,
    );
  } finally {
    restartedConnection?.close();
    f.connection.close();
  }
});

await test('owning pass monotonic expiry prevents image admission even before any timer callback runs', async () => {
  let uploads = 0;
  const f = await fixture({
    parentDeadline: () => parent,
    http: async (...args) => {
      uploads++;
      return admissions()(...args);
    },
  });
  const parent = new OperationDeadline(1000, f.clock);
  try {
    const session = await f.open();
    f.backend.capture = async () => {
      f.backend.captures++;
      f.clock.monotonic += 1001; // Deliberately do not fire the fake scheduler.
      assert.equal(parent?.signal.aborted, false);
      return f.backend.value;
    };
    const result = await f.invoke('observe', {
      ...session,
      surfaceId: 'viewport',
    });
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(uploads, 0);
    assert.equal(f.backend.captures, 1);
  } finally {
    parent.dispose();
    f.connection.close();
  }
});

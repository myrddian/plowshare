import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { toolDeploymentConfig } from 'plowshare-client-node/tools';
import { decodeToolCall } from 'plowshare-client-ts';
import { createComputerProvider } from './provider.ts';
import { BrowserTestGate } from './browser-provider.test-support.ts';
import { browserProviderFixture as fixture } from './browser-provider-fixture.test-support.ts';
import { FakeBrowserControlBackend } from './browser-provider.test-support.ts';
import { FakeClock, image, target } from './contracts.test-support.ts';
import { decodeCloseArguments } from './contracts.ts';
import { OperationDeadline } from './deadline.ts';
import {
  admissions,
  binding,
  call,
  client,
  metadata,
  record,
  RelayWire,
  MemoryJournal,
} from './provider.test-support.ts';

await test('stop during delayed acquire retains late lease only for cleanup', async () => {
  const f = await fixture(),
    gate = new BrowserTestGate();
  let entered = () => {};
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const original = f.backend.browserControl.acquire.bind(
    f.backend.browserControl,
  );
  f.backend.browserControl.acquire = async (request, deadline) => {
    const lease = await original(request, deadline);
    entered();
    await gate.promise;
    return lease;
  };
  try {
    const pending = f.invoke('open', { targetId: f.backend.targetId });
    await started;
    f.provider.stop();
    const result = await pending;
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(f.backend.hasLease, true);
    assert.throws(
      () =>
        f.provider.sessions.open(
          {
            account: 'owner',
            project: binding.project,
            provider: binding.provider,
            run: 'other',
          },
          f.backend.targetId,
        ),
      /BUSY/,
    );
    gate.resolve();
    await new Promise<void>((resolve) => setImmediate(resolve));
    await f.cleanup();
    assert.equal(f.backend.hasLease, false);
    assert.equal(f.backend.releaseCalls, 1);
  } finally {
    gate.resolve();
    f.connection.close();
  }
});

await test('PARTIAL settled input retains its exact extent and is never presented as a refusal', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.backend.delivery = {
      outcome: 'PARTIAL',
      physicalSettlement: 'settled',
      delivered: { kind: 'type', insertedUtf16Units: 2, cleared: false },
      code: 'INPUT_FAILED',
    };
    f.backend.postObservationFailure = 'OBSERVATION_FAILED';
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'longer',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'PARTIAL');
    assert.deepEqual(record(metadata(result)['action'])['delivered'], {
      kind: 'type',
      insertedUtf16Units: 2,
      cleared: false,
    });
    assert.equal(f.backend.inputs, 1);
  } finally {
    await f.cleanup();
  }
});

await test('a second SDK provider cannot adopt or release another binding’s controlled lease', async () => {
  const f = await fixture(),
    wire = new RelayWire(),
    connection = client(wire);
  const otherBinding = { ...binding, provider: 'other-computer' };
  const identity = createHash('sha256')
    .update(toolDeploymentConfig(otherBinding, f.provider.declarations))
    .digest('hex');
  const journal = new MemoryJournal(identity);
  const other = await createComputerProvider({
    connection,
    binding: otherBinding,
    journal,
    backends: [f.backend],
    browserControls: [f.backend],
    browserTools: f.tools,
    generation: 'other-generation',
    origin: 'https://fixture.invalid',
    currentBearer: async () => 'fixture',
    clock: f.clock,
    http: admissions(),
  });
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    const request = decodeToolCall(
      JSON.stringify({
        ...call(f.clock, 'open', { targetId: f.backend.targetId }),
        provider: otherBinding.provider,
      }),
    );
    wire.calls.push(request);
    await other.provider.poll();
    assert.notEqual(
      journal.rows.get(request.invocationId)?.result?.state,
      'COMPLETED',
    );
    other.stop();
    const deadline = new OperationDeadline(2000, f.clock);
    try {
      await assert.rejects(other.drain(deadline));
    } finally {
      deadline.dispose();
    }
    assert.equal(f.backend.releaseCalls, 0);
    assert.equal(f.backend.leaseOwner?.provider, binding.provider);
    assert.equal(
      (
        await f.invoke('type', {
          ...session,
          target: observed.target,
          mode: 'insert',
          text: 'owned',
        })
      ).state,
      'COMPLETED',
    );
    assert.equal(f.backend.inputs, 1);
  } finally {
    connection.close();
    await f.cleanup();
  }
});

await test('close after an SDK input timeout waits for the original physical operation', async () => {
  const f = await fixture();
  const gate = f.backend.deferInput();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    let entered = () => {};
    const started = new Promise<void>((resolve) => {
      entered = resolve;
    });
    f.backend.beforeInput = entered;
    const pending = f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    await started;
    f.clock.advance(10_001);
    assert.equal((await pending).state, 'UNKNOWN');
    let releasing = () => {};
    const releaseStarted = new Promise<void>((resolve) => {
      releasing = resolve;
    });
    f.backend.beforeRelease = releasing;
    const close = f.invoke('close', { ...session });
    await releaseStarted;
    assert.equal(f.backend.hasLease, true);
    assert.equal(f.backend.inputActive, true);
    gate.resolve();
    assert.equal((await close).state, 'COMPLETED');
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.hasLease, false);
    await f.cleanup();
  } finally {
    gate.resolve();
    f.connection.close();
  }
});

await test('ignored upload abort cannot publish late action pixels or release occupied refs early', async () => {
  const gate = new BrowserTestGate();
  let uploads = 0,
    entered = () => {},
    uploadSignal: AbortSignal | null | undefined;
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const f = await fixture({
    http: async (...args) => {
      uploads++;
      if (uploads === 2) {
        uploadSignal = args[1]?.signal;
        entered();
        await gate.promise;
      }
      return admissions()(...args);
    },
  });
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    const request = f.request('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    const pending = f.dispatch(request);
    await started;
    f.clock.advance(10_001);
    const result = await pending;
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(uploadSignal?.aborted, true);
    assert.equal(
      result.content?.parts.some((part) => part.kind === 'IMAGE'),
      false,
    );
    assert.throws(
      () =>
        f.provider.sessions.open(
          {
            account: 'owner',
            project: binding.project,
            provider: binding.provider,
            run: 'other',
          },
          f.backend.targetId,
        ),
      /BUSY/,
    );
    gate.resolve();
    await new Promise<void>((resolve) => setImmediate(resolve));
    await f.cleanup();
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 2);
    assert.equal(uploads, 2);
    assert.equal(
      f.journal.rows.get(request.invocationId)?.result?.state,
      'UNKNOWN',
    );
    assert.equal(f.wire.publications.length, 3);
  } finally {
    gate.resolve();
    f.connection.close();
  }
});

await test('lost publication reply reconciles retained action without a second input or upload', async () => {
  const f = await fixture();
  let restartedConnection: ReturnType<typeof client> | undefined;
  try {
    const session = await f.open(),
      observed = await f.observe(session),
      request = f.request('type', {
        ...session,
        target: observed.target,
        mode: 'insert',
        text: 'once',
      });
    f.wire.losePublication = true;
    await assert.rejects(f.dispatch(request));
    assert.equal(f.journal.rows.get(request.invocationId)?.phase, 'publishing');
    await f.cleanup();
    const wire = new RelayWire(f.wire.publications);
    restartedConnection = client(wire);
    const backend = new FakeBrowserControlBackend(f.clock);
    const restarted = await createComputerProvider({
      connection: restartedConnection,
      binding,
      journal: f.journal,
      backends: [backend],
      browserControls: [backend],
      browserTools: f.tools,
      generation: 'restarted',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      clock: f.clock,
      http: admissions(),
    });
    await restarted.provider.reconcile();
    assert.equal(f.journal.rows.get(request.invocationId)?.phase, 'done');
    assert.equal(f.backend.inputs, 1);
    assert.equal(backend.inputs, 0);
    assert.equal(backend.captureCalls, 0);
  } finally {
    restartedConnection?.close();
    f.connection.close();
  }
});

await test('executing mutation after restart becomes UNKNOWN without action replay', async () => {
  const f = await fixture();
  let restartedConnection: ReturnType<typeof client> | undefined;
  try {
    const request = f.request('type', {
      sessionId: 'old',
      generation: 'old',
      target: { kind: 'element', snapshotId: 'old', elementId: 'old' },
      mode: 'insert',
      text: 'once',
    });
    await f.provider.journal.save({
      invocationId: request.invocationId,
      request: JSON.stringify(request),
      phase: 'executing',
      result: null,
      occurredAt: null,
    });
    restartedConnection = client();
    const backend = new FakeBrowserControlBackend(f.clock);
    const restarted = await createComputerProvider({
      connection: restartedConnection,
      binding,
      journal: f.journal,
      backends: [backend],
      browserControls: [backend],
      browserTools: f.tools,
      generation: 'restarted',
      origin: 'https://fixture.invalid',
      currentBearer: async () => 'fixture',
      clock: f.clock,
      http: admissions(),
      maxImagesPerRun: 1,
    });
    await restarted.provider.poll();
    assert.equal(
      f.journal.rows.get(request.invocationId)?.result?.state,
      'UNKNOWN',
    );
    assert.equal(backend.inputs, 0);
    assert.equal(backend.acquireCalls, 0);
    assert.throws(
      () =>
        restarted.sessions.reserveImage({
          account: request.account,
          project: request.project,
          provider: request.provider,
          run: request.run,
        }),
      /FRAME_LIMIT/,
    );
  } finally {
    restartedConnection?.close();
    await f.cleanup();
  }
});

await test('mixed capture-only targets accept structured:false and expose exact browser tools', async () => {
  const clock = new FakeClock();
  let captures = 0;
  const legacy = {
    targets: async () => [{ ...target, targetId: 'legacy' }],
    capture: async () => {
      captures++;
      return image(clock);
    },
  };
  const f = await fixture({ clock, additionalBackends: [legacy] });
  try {
    const discovery = metadata(await f.invoke('targets')),
      rows = discovery['targets'];
    assert.ok(Array.isArray(rows));
    const controlled = rows
        .map(record)
        .find((row) => row['targetId'] === f.backend.targetId),
      old = rows.map(record).find((row) => row['targetId'] === 'legacy');
    assert.ok(controlled);
    assert.ok(old);
    assert.equal(Array.isArray(controlled['browserTools']), true);
    assert.equal(old['browserTools'], undefined);
    const opened = record(
      metadata(await f.invoke('open', { targetId: 'legacy' }))['session'],
    );
    const session = decodeCloseArguments({
      sessionId: opened['sessionId'],
      generation: opened['generation'],
    });
    assert.equal(
      (
        await f.invoke('observe', {
          ...session,
          surfaceId: 'viewport',
          structured: false,
        })
      ).state,
      'COMPLETED',
    );
    assert.equal(
      (
        await f.invoke('observe', {
          ...session,
          surfaceId: 'viewport',
          structured: true,
        })
      ).state,
      'REJECTED',
    );
    assert.equal(captures, 1);
  } finally {
    await f.cleanup();
  }
});

await test('post-transform invalid points are known refusals before input', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    f.backend.imageTransform = { a: 1, b: 0, c: 0, d: 1, e: -500, f: 0 };
    const observed = await f.observe(session);
    const result = await f.invoke('pointer', {
      ...session,
      action: 'click',
      target: { kind: 'point', frameId: observed.frameId, x: 10, y: 10 },
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('windows focus and quarantined navigation project exact guarded backend DTOs', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    await f.observe(session);
    f.backend.windowsValue = {
      windowsId: 'pending',
      capturedAt: new Date(f.clock.wallNow()).toISOString(),
      tabs: [
        {
          state: {
            ...f.backend.state,
            surfaceId: 'popup',
            readiness: 'quarantined',
            focusedElementId: null,
            url: 'about:blank',
            origin: null,
            requestedUrl: 'https://fixture.invalid/land',
          },
          title: 'Popup',
        },
      ],
      truncated: false,
    };
    const windows = record(
      metadata(await f.invoke('windows', { ...session }))['windows'],
    );
    const windowsId = windows['windowsId'];
    assert.ok(typeof windowsId === 'string');
    const focused = await f.invoke('focus', {
      ...session,
      windowsId,
      surfaceId: 'popup',
    });
    assert.equal(focused.state, 'COMPLETED');
    assert.deepEqual(Object.keys(f.backend.focused[0] ?? {}).sort(), [
      'observationReserveMs',
      'surfaceId',
      'windowsId',
    ]);
    const current = record(
      metadata(await f.invoke('windows', { ...session }))['windows'],
    );
    const currentWindowsId = current['windowsId'];
    assert.ok(typeof currentWindowsId === 'string');
    const navigated = await f.invoke('navigate', {
      ...session,
      observation: {
        kind: 'window',
        windowsId: currentWindowsId,
        surfaceId: 'popup',
      },
      url: 'https://fixture.invalid/land',
    });
    assert.equal(navigated.state, 'COMPLETED');
    assert.equal(metadata(navigated)['observationOutcome'], 'CAPTURED');
    assert.deepEqual(
      Object.keys(f.backend.navigated[0]?.expected ?? {}).sort(),
      ['documentId', 'focusGeneration', 'layoutGeneration', 'surfaceId'],
    );
  } finally {
    await f.cleanup();
  }
});

await test('escaped subtree metadata stays bounded and omitted elements never gain references', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      original = f.backend.browserControl.observe.bind(
        f.backend.browserControl,
      );
    f.backend.browserControl.observe = async (lease, request, deadline) => {
      const observed = await original(lease, request, deadline),
        snapshot = observed.snapshot;
      assert.ok(snapshot);
      const first = snapshot.nodes[0];
      assert.ok(first);
      return {
        ...observed,
        snapshot: {
          ...snapshot,
          rootElementId: first.elementId,
          nodes: [
            first,
            ...Array.from({ length: 199 }, (_, index) => ({
              ...first,
              elementId: 'large-' + index,
              parentElementId: first.elementId,
              focused: false,
              name: '"\\\n'.repeat(80),
            })),
          ],
        },
      };
    };
    const result = await f.invoke('observe', {
      ...session,
      surfaceId: 'viewport',
    });
    assert.equal(result.state, 'COMPLETED');
    assert.ok(result.text.length <= 12000);
    assert.ok(JSON.stringify({ content: result.content }).length <= 32768);
    const snapshot = record(metadata(result)['snapshot']),
      nodes = snapshot['nodes'];
    assert.ok(Array.isArray(nodes));
    assert.ok(nodes.length < 200);
    assert.equal(snapshot['truncated'], true);
    assert.ok(
      nodes
        .map(record)
        .some((node) => node['elementId'] === snapshot['rootElementId']),
    );
    const snapshotId = snapshot['snapshotId'];
    assert.ok(typeof snapshotId === 'string');
    const rejected = await f.invoke('type', {
      ...session,
      target: {
        kind: 'element',
        snapshotId,
        elementId: 'large-198',
      },
      mode: 'insert',
      text: 'x',
    });
    assert.equal(rejected.state, 'REJECTED');
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('idle expiry releases without another invocation and retains ownership while cleanup is pending', async () => {
  const f = await fixture({ idleMs: 20 }),
    gate = f.backend.deferRelease();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    assert.equal(f.wire.publications.length, 2);
    f.clock.advance(21);
    assert.equal(f.backend.releaseCalls, 1);
    assert.equal(f.backend.hasLease, true);
    assert.equal(f.wire.publications.length, 2);
    assert.throws(
      () =>
        f.provider.sessions.open(
          {
            account: 'owner',
            project: binding.project,
            provider: binding.provider,
            run: 'other',
          },
          f.backend.targetId,
        ),
      /BUSY/,
    );
    assert.equal(
      (
        await f.invoke('type', {
          ...session,
          target: observed.target,
          mode: 'insert',
          text: 'late',
        })
      ).state,
      'REJECTED',
    );
    assert.equal(f.backend.inputs, 0);
    assert.equal(f.backend.releaseCalls, 1);
    gate.resolve();
    await new Promise<void>((resolve) => setImmediate(resolve));
    await f.cleanup();
    assert.equal(f.backend.hasLease, false);
    assert.equal(f.clock.pendingTimers(), 0);
  } finally {
    gate.resolve();
    f.connection.close();
  }
});

await test('failed idle cleanup stays fenced and never starts a periodic cleanup retry', async () => {
  const f = await fixture({ idleMs: 20 });
  try {
    await f.open();
    f.backend.releaseFailure = new Error('fixture blocked release');
    f.clock.advance(21);
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(f.backend.releaseCalls, 1);
    assert.equal(f.backend.hasLease, true);
    assert.equal(f.clock.pendingTimers(), 0);
    f.clock.advance(100_000);
    assert.equal(f.backend.releaseCalls, 1);
    await assert.rejects(f.cleanup());
    assert.equal(f.backend.releaseCalls, 1);
  } finally {
    f.connection.close();
  }
});

await test('typed uncertain delivery keeps its code and handled-dialog evidence through fencing', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.backend.delivery = {
      outcome: 'UNCERTAIN',
      physicalSettlement: 'settled',
      code: 'INPUT_FAILED',
    };
    f.backend.dialogEvent = {
      type: 'confirm',
      decision: 'dismiss',
      message: 'Save?',
      messageTruncated: false,
    };
    const result = await f.invoke('type', {
      ...session,
      target: observed.target,
      mode: 'insert',
      text: 'once',
    });
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(metadata(result)['code'], 'INPUT_FAILED');
    assert.deepEqual(metadata(result)['dialog'], f.backend.dialogEvent);
    assert.equal(
      result.content?.parts.some((part) => part.kind === 'IMAGE'),
      false,
    );
    assert.equal(f.backend.inputs, 1);
  } finally {
    await f.cleanup();
  }
});

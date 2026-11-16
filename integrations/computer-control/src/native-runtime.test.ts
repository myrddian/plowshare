import assert from 'node:assert/strict';
import test from 'node:test';
import type { NativeAcquisition } from './native-control.ts';
import { NativeToolRuntime } from './native-runtime.ts';
import { ComputerSessions } from './sessions.ts';
import { FakeNativeControlBackend } from './native-provider.test-support.ts';
import { nativeProviderFixture as fixture } from './native-provider-fixture.test-support.ts';
import { FakeClock } from './contracts.test-support.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import { createComputerProvider } from './provider.ts';
import { decodeNativeObserveArguments } from './native-tool-arguments.ts';
import { decodeCloseArguments } from './contracts.ts';
import {
  admissions,
  binding,
  call,
  client,
  metadata,
  record,
  RelayWire,
} from './provider.test-support.ts';

function gate() {
  let done: (() => void) | undefined;
  const promise = new Promise<void>((resolve) => {
    done = resolve;
  });
  return {
    promise,
    resolve() {
      if (done === undefined) throw new Error('Uninitialized gate');
      done();
    },
  };
}
async function tick(count = 10) {
  for (let index = 0; index < count; index++) await Promise.resolve();
}

await test('proven BUSY acquisition frees only empty local reservation and never releases foreign lease', async () => {
  const clock = new FakeClock(),
    backend = new FakeNativeControlBackend(clock),
    first = await fixture({ clock, backend }),
    second = await fixture({ clock, backend });
  try {
    await first.open();
    for (let index = 0; index < 2; index++) {
      const result = await second.invoke('open', {
        targetId: backend.targetId,
      });
      assert.equal(result.state, 'REJECTED');
      assert.equal(metadata(result)['code'], 'BUSY');
    }
    assert.equal(backend.acquireCalls, 3);
    assert.equal(backend.releaseCalls, 0);
    assert.equal(backend.hasLease, true);
    await second.cleanup();
    assert.equal(backend.hasLease, true);
    assert.equal(backend.releaseCalls, 0);
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
  assert.equal(backend.releaseCalls, 1);
});

await test('uncertain and mismatched acquisition never produce input or a foreign cleanup capability', async () => {
  for (const malformed of [false, true]) {
    const clock = new FakeClock(),
      backend = new FakeNativeControlBackend(clock);
    backend.acquire = async (): Promise<NativeAcquisition> =>
      malformed
        ? {
            outcome: 'ACQUIRED',
            lease: {
              leaseId: 'foreign',
              targetId: 'foreign',
              sessionId: 'foreign',
              generation: 'foreign',
            },
          }
        : { outcome: 'UNCERTAIN', lease: null, code: 'CLEANUP_UNCERTAIN' };
    const f = await fixture({ clock, backend });
    try {
      assert.equal(
        (await f.invoke('open', { targetId: backend.targetId })).state,
        'UNKNOWN',
      );
      assert.equal(
        (await f.invoke('open', { targetId: backend.targetId })).state,
        'REJECTED',
      );
      assert.equal(backend.releaseCalls, 0);
      assert.equal(backend.inputs, 0);
    } finally {
      await assert.rejects(f.cleanup(), /CLEANUP_UNCERTAIN/);
    }
    assert.equal(clock.pendingTimers(), 0);
  }
});

await test('late acquired lease is cleanup-only after stop, without a second acquisition or receipt rewrite', async () => {
  const f = await fixture(),
    original = f.backend.acquire.bind(f.backend),
    held = gate(),
    entered = gate();
  f.backend.acquire = async (...args) => {
    const result = await original(...args);
    entered.resolve();
    await held.promise;
    return result;
  };
  const request = f.request('open', { targetId: f.backend.targetId });
  try {
    const pending = f.dispatch(request);
    await entered.promise;
    f.provider.stop();
    const unknown = await pending;
    assert.equal(unknown.state, 'UNKNOWN');
    assert.equal(f.backend.hasLease, true);
    held.resolve();
    await tick();
    await f.cleanup();
    assert.equal(f.backend.hasLease, false);
    assert.equal(f.backend.acquireCalls, 1);
    assert.equal(f.backend.releaseCalls, 1);
    assert.deepEqual(f.journal.rows.get(request.invocationId)?.result, unknown);
  } finally {
    held.resolve();
    await f.cleanup();
  }
});

await test('all native input variants cross the SDK boundary with fresh exact refs and native units', async () => {
  const f = await fixture();
  try {
    const session = await f.open();
    const observed = await f.observe(session);
    const update = (result: { readonly text: string }) => {
      const body = metadata(result),
        frame = record(body['frame']),
        snapshot = record(body['snapshot']);
      const frameId = frame['frameId'],
        snapshotId = snapshot['snapshotId'];
      assert.ok(typeof frameId === 'string' && typeof snapshotId === 'string');
      return {
        frameId,
        target: { kind: 'element', snapshotId, elementId: 'native-input' },
      };
    };
    let current = { frameId: observed.frameId, target: observed.target };
    for (const action of ['move', 'double_click']) {
      const result = await f.invoke('pointer', {
        ...session,
        action,
        target: { kind: 'point', frameId: current.frameId, x: 10, y: 10 },
      });
      assert.equal(result.state, 'COMPLETED');
      current = update(result);
    }
    const drag = await f.invoke('pointer', {
      ...session,
      action: 'drag',
      frameId: current.frameId,
      points: [
        { x: 10, y: 10, atMs: 0 },
        { x: 20, y: 20, atMs: 1000 },
      ],
    });
    assert.equal(drag.state, 'COMPLETED');
    current = update(drag);
    const key = await f.invoke('key', {
      ...session,
      frameId: current.frameId,
      expectedFocus: current.target,
      key: 'Enter',
    });
    assert.equal(key.state, 'COMPLETED');
    current = update(key);
    f.backend.delivery = {
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism: 'cg-scroll',
      scroll: { dx: 1, dy: -1, units: 'lines' },
    };
    const scroll = await f.invoke('scroll', {
      ...session,
      target: current.target,
      dx: 1,
      dy: -1,
      units: 'lines',
    });
    assert.equal(scroll.state, 'COMPLETED');
    current = update(scroll);
    const listed = metadata(await f.invoke('windows', { ...session })),
      windows = record(listed['windows']);
    const windowsId = windows['windowsId'];
    assert.ok(typeof windowsId === 'string');
    const focused = await f.invoke('focus', {
      ...session,
      windowsId,
      surfaceId: 'native-window-surface',
    });
    assert.equal(focused.state, 'COMPLETED');
    current = update(focused);
    f.backend.delivery = {
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism: 'ax-selected-text',
    };
    assert.equal(
      (
        await f.invoke('type', {
          ...session,
          target: current.target,
          mode: 'insert',
          text: 'Native🙂',
        })
      ).state,
      'COMPLETED',
    );
    assert.equal(f.backend.inputs, 7);
    assert.equal(f.backend.bareCaptureCalls, 0);
    assert.deepEqual(
      f.backend.performed.map((request) => request.action.kind),
      ['move', 'double_click', 'drag', 'key', 'scroll', 'type'],
    );
  } finally {
    await f.cleanup();
  }
});

await test('secure/disabled AX targets and unknown focus are rejected before native input', async () => {
  for (const mode of ['secure', 'disabled'] as const) {
    const clock = new FakeClock(),
      backend = new FakeNativeControlBackend(clock),
      original = backend.observation.bind(backend);
    backend.observation = (structured = true) => {
      const value = original(structured);
      assert.ok(value.snapshot);
      return {
        ...value,
        snapshot: {
          ...value.snapshot,
          nodes: value.snapshot.nodes.map((node) => ({
            ...node,
            secure: mode === 'secure',
            enabled: mode !== 'disabled',
          })),
        },
      };
    };
    const f = await fixture({ clock, backend });
    try {
      const session = await f.open(),
        observed = await f.observe(session);
      const result = await f.invoke('type', {
        ...session,
        target: observed.target,
        mode: 'replace',
        text: 'no secret injection',
      });
      assert.equal(result.state, 'REJECTED');
      assert.equal(
        metadata(result)['code'],
        mode === 'secure' ? 'SENSITIVE_TARGET' : 'NOT_EDITABLE',
      );
      assert.equal(backend.inputs, 0);
    } finally {
      await f.cleanup();
    }
  }
});

await test('true partial native dispatch keeps exact extent and known observation failure without replay', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    f.backend.perform = async () => {
      f.backend.inputs++;
      return {
        delivery: {
          outcome: 'PARTIAL',
          physicalSettlement: 'settled',
          delivered: { kind: 'type', dispatchedUtf16Units: 2, cleared: false },
          code: 'INPUT_FAILED',
        },
        observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
      };
    };
    const request = f.request('type', {
        ...session,
        target: observed.target,
        mode: 'insert',
        text: 'abc',
      }),
      result = await f.dispatch(request);
    assert.equal(result.state, 'COMPLETED');
    assert.equal(metadata(result)['actionOutcome'], 'PARTIAL');
    assert.equal(metadata(result)['observationOutcome'], 'FAILED');
    const delivered = record(record(metadata(result)['action'])['delivered']);
    assert.equal(delivered['dispatchedUtf16Units'], 2);
    assert.equal(
      result.content?.parts.some((part) => part.kind === 'IMAGE'),
      false,
    );
    assert.deepEqual(await f.dispatch(request), result);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 1);
  } finally {
    await f.cleanup();
  }
});

await test('stop retains busy physical input and ignores late capture/upload after immutable UNKNOWN', async () => {
  let uploads = 0;
  const f = await fixture({
      http: async (...args) => {
        uploads++;
        return admissions()(...args);
      },
    }),
    original = f.backend.perform.bind(f.backend),
    entered = gate(),
    ack = gate();
  f.backend.perform = async (...args) => {
    const result = await original(...args);
    entered.resolve();
    await ack.promise;
    return result;
  };
  try {
    const session = await f.open(),
      observed = await f.observe(session),
      request = f.request('type', {
        ...session,
        target: observed.target,
        mode: 'insert',
        text: 'once',
      });
    const pending = f.dispatch(request);
    await entered.promise;
    f.provider.stop();
    const unknown = await pending;
    assert.equal(unknown.state, 'UNKNOWN');
    assert.throws(
      () =>
        f.provider.sessions.releaseAfterQuiescence(
          {
            account: request.account,
            project: request.project,
            run: request.run,
            provider: request.provider,
          },
          session,
        ),
      /BUSY/,
    );
    ack.resolve();
    await tick();
    await f.cleanup();
    assert.deepEqual(f.journal.rows.get(request.invocationId)?.result, unknown);
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.backend.captureCalls, 2);
    assert.equal(f.backend.releaseCalls, 1);
    assert.equal(uploads, 1);
  } finally {
    ack.resolve();
    await f.cleanup();
  }
});

await test('unproved native cleanup is memoized, retains occupancy and never retries release', async () => {
  const f = await fixture();
  f.backend.release = async () => {
    f.backend.releaseCalls++;
    return {
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
      helperStopped: true,
    };
  };
  try {
    const session = await f.open();
    assert.equal((await f.invoke('close', { ...session })).state, 'UNKNOWN');
    assert.equal(f.backend.hasLease, true);
    assert.equal(
      (await f.invoke('open', { targetId: f.backend.targetId })).state,
      'REJECTED',
    );
    assert.equal(f.backend.releaseCalls, 1);
  } finally {
    await assert.rejects(f.cleanup(), /CLEANUP_UNCERTAIN/);
  }
  assert.equal(f.backend.releaseCalls, 1);
  assert.equal(f.clock.pendingTimers(), 0);
});

await test('native idle expiry cleans without another invocation and never needs fabricated input readiness', async () => {
  const f = await fixture({ idleMs: 100 });
  try {
    await f.open();
    f.clock.advance(101);
    await tick();
    assert.equal(f.backend.releaseCalls, 1);
    assert.equal(f.backend.hasLease, false);
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
  assert.equal(f.clock.pendingTimers(), 0);
});

await test('metadata truncation never makes omitted native AX handles actionable', async () => {
  const clock = new FakeClock(),
    backend = new FakeNativeControlBackend(clock),
    original = backend.observation.bind(backend);
  backend.observation = (structured = true) => {
    const value = original(structured);
    assert.ok(value.snapshot);
    const first = value.snapshot.nodes[0];
    assert.ok(first);
    return {
      ...value,
      snapshot: {
        ...value.snapshot,
        nodes: [
          first,
          ...Array.from({ length: 149 }, (_, index) => ({
            ...first,
            elementId: 'native-extra-' + index,
            name: '"\\'.repeat(128),
            editable: false,
            focused: false,
          })),
        ],
      },
    };
  };
  const f = await fixture({ clock, backend });
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    assert.equal(metadata(observed.result)['truncated'], true);
    const result = await f.invoke('pointer', {
      ...session,
      action: 'click',
      target: {
        kind: 'element',
        snapshotId: observed.target.snapshotId,
        elementId: 'native-extra-148',
      },
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(metadata(result)['code'], 'STALE_OBSERVATION');
    assert.equal(backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('crash before READY restarts as UNKNOWN and original invocation cannot dispatch again', async () => {
  const f = await fixture();
  const save = f.journal.save.bind(f.journal);
  let actionId = '';
  try {
    const session = await f.open(),
      observed = await f.observe(session),
      request = f.request('type', {
        ...session,
        target: observed.target,
        mode: 'insert',
        text: 'once',
      });
    actionId = request.invocationId;
    f.journal.save = async (row) => {
      if (row.invocationId === actionId && row.phase === 'ready')
        throw new Error('Synthetic crash before READY');
      await save(row);
    };
    await assert.rejects(f.dispatch(request));
    assert.equal(f.backend.inputs, 1);
    assert.equal(f.journal.rows.get(actionId)?.phase, 'executing');
    f.journal.save = save;
    await f.cleanup();
    const wire = new RelayWire(),
      connection = client(wire),
      provider = await createComputerProvider({
        connection,
        binding,
        journal: f.journal,
        backends: [f.backend],
        nativeControls: [f.backend],
        nativeTools: f.tools,
        generation: 'restart-generation',
        origin: 'https://fixture.invalid',
        currentBearer: async () => 'fixture',
        http: admissions(),
        clock: f.clock,
      });
    try {
      wire.calls.push(request);
      assert.equal(await provider.provider.poll(), 1);
      const recovered = f.journal.rows.get(actionId)?.result;
      assert.equal(recovered?.state, 'UNKNOWN');
      assert.equal(
        recovered?.content?.parts.some((part) => part.kind === 'IMAGE'),
        false,
      );
      assert.equal(f.backend.inputs, 1);
      wire.calls.push(request);
      assert.equal(await provider.provider.poll(), 1);
      assert.deepEqual(f.journal.rows.get(actionId)?.result, recovered);
      assert.equal(f.backend.inputs, 1);
    } finally {
      provider.stop();
      const deadline = new OperationDeadline(2000, f.clock);
      try {
        await provider.drain(deadline);
      } finally {
        deadline.dispose();
        connection.close();
      }
    }
  } finally {
    f.journal.save = save;
    await f.cleanup();
  }
});

await test('known native dispatch survives outer expiry as text evidence, without late success or image upload', async () => {
  const clock = new FakeClock(),
    backend = new FakeNativeControlBackend(clock);
  let next = 0,
    uploads = 0;
  const sessions = new ComputerSessions({
    generation: 'direct-generation',
    id: () => 'direct-' + next++,
    clock,
  });
  const runtime = new NativeToolRuntime({
    binding,
    sessions,
    controls: [backend],
    tools: backend.allowedTools,
    clock,
    origin: 'https://fixture.invalid',
    currentBearer: async () => 'fixture',
    http: async (...args) => {
      uploads++;
      return admissions()(...args);
    },
    captureLimits: { maxBytes: 1048576, maxDimension: 1600 },
    observationReserveMs: 500,
    cleanupBudgetMs: 2000,
    idleCheckMs: 1000,
  });
  const budget = async <T>(
    work: (deadline: OperationDeadline) => Promise<T>,
  ) => {
    const deadline = new OperationDeadline(10000, clock);
    try {
      return await work(deadline);
    } finally {
      deadline.dispose();
    }
  };
  try {
    const opened = await budget((deadline) =>
      runtime.open(
        call(clock, 'open', { targetId: backend.targetId }),
        backend.targetId,
        backend,
        deadline,
      ),
    );
    const raw = record(metadata(opened)['session']);
    const session = decodeCloseArguments({
      sessionId: raw['sessionId'],
      generation: raw['generation'],
    });
    const observed = await budget((deadline) =>
      runtime.observe(
        call(clock, 'observe', { ...session }),
        decodeNativeObserveArguments({
          ...session,
          surfaceId: backend.surfaceId,
        }),
        deadline,
      ),
    );
    const snapshot = record(metadata(observed)['snapshot']);
    const snapshotId = snapshot['snapshotId'];
    assert.ok(typeof snapshotId === 'string');
    const original = backend.perform.bind(backend);
    backend.perform = async (...args) => {
      const result = await original(...args);
      clock.monotonic += 10001;
      return result;
    };
    const result = await budget((deadline) =>
      runtime.action(
        call(clock, 'type', {
          ...session,
          target: {
            kind: 'element',
            snapshotId,
            elementId: 'native-input',
          },
          mode: 'insert',
          text: 'once',
        }),
        'type',
        deadline,
      ),
    );
    assert.equal(result.state, 'UNKNOWN');
    assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
    assert.equal(metadata(result)['observationOutcome'], 'FAILED');
    assert.equal(record(metadata(result)['action'])['outcome'], 'DISPATCHED');
    assert.equal(uploads, 1);
    assert.equal(backend.inputs, 1);
  } finally {
    runtime.stop();
    const deadline = new OperationDeadline(2000, clock);
    try {
      await runtime.drain(deadline);
    } finally {
      deadline.dispose();
    }
  }
});

await test('null key focus does not wildcard the actual native focused editable element', async () => {
  const f = await fixture();
  try {
    const session = await f.open(),
      observed = await f.observe(session);
    const result = await f.invoke('key', {
      ...session,
      frameId: observed.frameId,
      expectedFocus: null,
      key: 'Enter',
    });
    assert.equal(result.state, 'REJECTED');
    assert.equal(metadata(result)['code'], 'FOCUS_MISMATCH');
    assert.equal(f.backend.inputs, 0);
  } finally {
    await f.cleanup();
  }
});

await test('deferred native release bounds waiters and never drops a late cleanup fence or retries release', async () => {
  const f = await fixture(),
    entered = gate(),
    stopped = gate();
  f.backend.release = async () => {
    f.backend.releaseCalls++;
    entered.resolve();
    await stopped.promise;
    f.backend.hasLease = false;
    return {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
    };
  };
  try {
    const session = await f.open(),
      request = f.request('close', { ...session }),
      pending = f.dispatch(request);
    await entered.promise;
    f.clock.advance(2001);
    const unknown = await pending;
    assert.equal(unknown.state, 'UNKNOWN');
    assert.equal(f.backend.hasLease, true);
    assert.equal(f.backend.releaseCalls, 1);
    f.provider.stop();
    const deadline = new OperationDeadline(2000, f.clock);
    const drained = assert.rejects(
      f.provider.drain(deadline),
      DeadlineExceeded,
    );
    f.clock.advance(2001);
    await drained;
    deadline.dispose();
    stopped.resolve();
    await tick();
    assert.equal(f.backend.hasLease, false);
    assert.deepEqual(f.journal.rows.get(request.invocationId)?.result, unknown);
    assert.equal(f.backend.releaseCalls, 1);
  } finally {
    stopped.resolve();
    await assert.rejects(f.cleanup(), DeadlineExceeded);
  }
  assert.equal(f.backend.releaseCalls, 1);
  assert.equal(f.clock.pendingTimers(), 0);
});

await test('deferred upload keeps physical handler ownership and late response cannot rewrite UNKNOWN or recapture', async () => {
  const entered = gate(),
    reply = gate();
  let uploads = 0;
  const f = await fixture({
    http: async (...args) => {
      uploads++;
      if (uploads === 2) {
        entered.resolve();
        await reply.promise;
      }
      return admissions()(...args);
    },
  });
  try {
    const session = await f.open(),
      observed = await f.observe(session),
      request = f.request('type', {
        ...session,
        target: observed.target,
        mode: 'insert',
        text: 'once',
      }),
      pending = f.dispatch(request);
    await entered.promise;
    f.provider.stop();
    const unknown = await pending;
    assert.equal(unknown.state, 'UNKNOWN');
    assert.equal(f.backend.inputs, 1);
    assert.equal(uploads, 2);
    assert.throws(
      () =>
        f.provider.sessions.releaseAfterQuiescence(
          {
            account: request.account,
            project: request.project,
            provider: request.provider,
            run: request.run,
          },
          session,
        ),
      /BUSY/,
    );
    const deadline = new OperationDeadline(1000, f.clock);
    const draining = assert.rejects(
      f.provider.drain(deadline),
      DeadlineExceeded,
    );
    f.clock.advance(1001);
    await draining;
    deadline.dispose();
    reply.resolve();
    await tick();
    assert.deepEqual(await f.dispatch(request), unknown);
    assert.equal(uploads, 2);
    assert.equal(f.backend.captureCalls, 2);
    assert.equal(f.backend.inputs, 1);
  } finally {
    reply.resolve();
    await f.cleanup();
  }
  assert.equal(f.clock.pendingTimers(), 0);
});

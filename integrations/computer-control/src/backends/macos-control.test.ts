import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { mkdtemp, readdir, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { FakeClock, owner } from '../contracts.test-support.ts';
import { OperationDeadline } from '../deadline.ts';
import { FakeNativeControlBackend } from '../native-provider.test-support.ts';
import type {
  NativeCleanupResult,
  NativeLease,
  NativePerformRequest,
} from '../native-control.ts';
import { decodeNativeStateGuard } from '../native-control.ts';
import type { NativeControlProcess } from './native-control-process.ts';
import { NativeProcessError } from './native-control-process.ts';
import {
  createMacOSControlBackend,
  decodeMacOSControlBackendConfig,
} from './macos-control.ts';
import type { MacOSControlBackendConfig } from './macos-control.ts';
import {
  decodeNativeBrokerRequest,
  NATIVE_BROKER_VERSION,
} from './native-broker-protocol.ts';
import type {
  NativeBrokerReply,
  NativeBrokerRequest,
} from './native-broker-protocol.ts';

const session = { sessionId: 'native-session', generation: 'generation' };
const replyHeader = {
  version: NATIVE_BROKER_VERSION,
  requestId: '11111111-1111-1111-1111-111111111111',
} as const;
class Broker implements NativeControlProcess {
  readonly commands: NativeBrokerRequest[] = [];
  readonly fake = new FakeNativeControlBackend(new FakeClock());
  closeCalls = 0;
  revocations = 0;
  rejectPreflight = false;
  preflightDelay?: Promise<void>;
  preflightSeen?: () => void;
  cleanup: NativeCleanupResult = {
    physicalSettlement: 'settled',
    heldInputsReleased: true,
    helperStopped: true,
  };
  respond:
    ((request: NativeBrokerRequest) => Promise<NativeBrokerReply>) | undefined;
  request(
    value: NativeBrokerRequest,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply> {
    const request = decodeNativeBrokerRequest(value);
    deadline.requireActive();
    this.commands.push(request);
    if (request.operation === 'preflight' && this.rejectPreflight)
      return Promise.reject(
        new NativeProcessError('INVALID_PROTOCOL', 'uncertain'),
      );
    if (request.operation === 'preflight') {
      this.preflightSeen?.();
      return (this.preflightDelay ?? Promise.resolve()).then(() => ({
        ...replyHeader,
        operation: 'preflight',
        result: {
          permissions: {
            screenCapture: false,
            accessibility: false,
            eventPosting: false,
            eventListening: false,
          },
          availability: 'unknown',
          secureInput: 'unknown',
          globalFence: 'clear',
        },
      }));
    }
    if (this.respond !== undefined) return this.respond(request);
    if (request.operation === 'arm_request')
      return Promise.resolve({
        ...replyHeader,
        operation: request.operation,
        result: {
          state: 'PENDING',
          binding: {
            lease: request.lease,
            helperGeneration: replyHeader.requestId,
          },
          requestId: request.request.requestId,
          challengeId: 'aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb',
          requestedAt: '2026-11-17T00:00:00Z',
          expiresAt: '2026-11-17T00:00:30Z',
        },
      });
    if (request.operation === 'arm_status')
      return Promise.resolve({
        ...replyHeader,
        operation: request.operation,
        result: {
          state: 'UNARMED',
          binding: {
            lease: request.lease,
            helperGeneration: replyHeader.requestId,
          },
        },
      });
    if (request.operation === 'acquire')
      return Promise.resolve({
        ...replyHeader,
        operation: 'acquire',
        result: { outcome: 'ACQUIRED', lease: request.lease },
      });
    if (request.operation === 'observe')
      return Promise.resolve({
        ...replyHeader,
        operation: 'observe',
        result: this.fake.observation(request.request.structured),
      });
    if (request.operation === 'windows')
      return Promise.resolve({
        ...replyHeader,
        operation: 'windows',
        result: {
          windowsId: 'windows',
          capturedAt: new Date(this.fake.clock.wall).toISOString(),
          windows: [],
          truncated: false,
        },
      });
    if (request.operation === 'perform' || request.operation === 'focus')
      return Promise.resolve({
        ...replyHeader,
        operation: request.operation,
        result: {
          delivery: {
            outcome: 'DISPATCHED',
            physicalSettlement: 'settled',
            mechanism: 'cg-event',
          },
          observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
        },
      });
    throw new Error('Unexpected fake broker operation');
  }
  close(deadline: OperationDeadline): Promise<NativeCleanupResult> {
    deadline.requireActive();
    this.closeCalls++;
    return Promise.resolve(this.cleanup);
  }
  revoke(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeBrokerReply> {
    deadline.requireActive();
    this.revocations++;
    return Promise.resolve({
      ...replyHeader,
      operation: 'arm_revoke',
      result: {
        state: 'REVOKED',
        binding: { lease, helperGeneration: replyHeader.requestId },
        requestId: null,
        armGeneration: null,
        revokedAt: new Date(this.fake.clock.wall).toISOString(),
        reason: 'HOST_REVOKED',
      },
    });
  }
}
function config(directory: string): MacOSControlBackendConfig {
  return {
    helperExecutable: '/fixture/helper',
    ownershipDirectory: directory,
    targets: [{ targetId: 'native', label: 'Native', surfaceId: 'display:1' }],
    control: {
      enabled: true,
      tools: ['windows', 'focus', 'pointer', 'type', 'key', 'scroll'],
      allowedBundleIds: ['org.example.NativeFixture'],
    },
    cleanupBudgetMs: 100,
  };
}
async function fixture(broker = new Broker()) {
  const directory = await mkdtemp(join(tmpdir(), 'native-controller-'));
  const deadline = new OperationDeadline(2000);
  let launches = 0;
  const handle = await createMacOSControlBackend(
    config(directory),
    deadline,
    () => {
      launches++;
      assert.ok(existsSync(join(directory, 'capture.lock', 'lease.json')));
      return broker;
    },
  );
  return {
    directory,
    deadline,
    handle,
    broker,
    launches: () => launches,
    control: handle.backend.nativeControl,
    dispose: async () => {
      deadline.dispose();
      await rm(directory, { recursive: true, force: true });
    },
  };
}
async function acquire(
  f: Awaited<ReturnType<typeof fixture>>,
): Promise<NativeLease> {
  const result = await f.control.acquire(
    { owner, session, targetId: 'native' },
    f.deadline,
  );
  assert.equal(result.outcome, 'ACQUIRED');
  if (result.outcome !== 'ACQUIRED') throw new Error('Expected fixture lease');
  return result.lease;
}

await test('host arming uses acquire-time owner, exact lease, and dedicated revoke capability', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f),
      arming = f.handle.backend.nativeArming;
    const request = {
      requestId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      durationMs: 30_000,
    };
    assert.equal(
      (await arming.request(lease, request, f.deadline)).state,
      'PENDING',
    );
    const sent = f.broker.commands.at(-1);
    assert.ok(sent?.operation === 'arm_request');
    assert.deepEqual(sent.owner, owner);
    assert.deepEqual(sent.lease, lease);
    assert.deepEqual(sent.request, request);
    assert.equal((await arming.status(lease, f.deadline)).state, 'UNARMED');
    const before = f.broker.commands.length;
    await assert.rejects(
      arming.status({ ...lease, leaseId: 'foreign' }, f.deadline),
    );
    await assert.rejects(
      arming.request(lease, { ...request, durationMs: 30_001 }, f.deadline),
    );
    assert.equal(f.broker.commands.length, before);
    assert.equal((await arming.revoke(lease, f.deadline)).state, 'REVOKED');
    assert.equal(f.broker.revocations, 1);
    assert.equal(f.broker.commands.length, before);
    await f.control.release(lease, f.deadline);
  } finally {
    await f.dispose();
  }
});

await test('foreign arming status binding fences the original lease without a retry', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f);
    f.broker.respond = async (request) => {
      assert.equal(request.operation, 'arm_status');
      return {
        ...replyHeader,
        operation: 'arm_status',
        result: {
          state: 'UNARMED',
          binding: {
            lease: { ...lease, leaseId: 'foreign' },
            helperGeneration: replyHeader.requestId,
          },
        },
      };
    };
    await assert.rejects(
      f.handle.backend.nativeArming.status(lease, f.deadline),
    );
    const before = f.broker.commands.length;
    await assert.rejects(
      f.handle.backend.nativeArming.status(lease, f.deadline),
    );
    assert.equal(f.broker.commands.length, before);
    assert.equal(
      (await f.control.release(lease, f.deadline)).physicalSettlement,
      'uncertain',
    );
    assert.ok(existsSync(join(f.directory, 'capture.lock')));
  } finally {
    await f.dispose();
  }
});

await test('same-lease old arming intent and non-revoked control reply fence without rearming', async () => {
  for (const operation of ['request', 'revoke'] as const) {
    const f = await fixture();
    try {
      const lease = await acquire(f),
        arming = f.handle.backend.nativeArming;
      const status = {
        state: 'PENDING' as const,
        binding: { lease, helperGeneration: replyHeader.requestId },
        requestId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        challengeId: 'aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb',
        requestedAt: '2026-11-17T00:00:00Z',
        expiresAt: '2026-11-17T00:00:30Z',
      };
      if (operation === 'request') {
        f.broker.respond = async () => ({
          ...replyHeader,
          operation: 'arm_request',
          result: status,
        });
        await assert.rejects(
          arming.request(
            lease,
            {
              requestId: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff',
              durationMs: 30_000,
            },
            f.deadline,
          ),
        );
      } else {
        f.broker.revoke = async () => ({
          ...replyHeader,
          operation: 'arm_revoke',
          result: status,
        });
        await assert.rejects(arming.revoke(lease, f.deadline));
      }
      const count = f.broker.commands.length;
      await assert.rejects(arming.status(lease, f.deadline));
      assert.equal(f.broker.commands.length, count);
      assert.equal(
        (await f.control.release(lease, f.deadline)).physicalSettlement,
        'uncertain',
      );
    } finally {
      await f.dispose();
    }
  }
});
function click(broker: Broker): NativePerformRequest {
  const state = broker.fake.state();
  return {
    invocationId: replyHeader.requestId,
    expected: decodeNativeStateGuard({
      surfaceId: state.surfaceId,
      applicationId: state.applicationId,
      windowId: state.windowId,
      layoutGeneration: state.layoutGeneration,
      focusGeneration: state.focusGeneration,
      displayGeneration: state.displayGeneration,
      interactionGeneration: state.interactionGeneration,
    }),
    action: {
      kind: 'click',
      target: { kind: 'point', x: -290, y: 60 },
      button: 'left',
      modifiers: [],
    },
    observationReserveMs: 100,
  };
}

await test('native config and expired factory reject before helper or filesystem effects', async () => {
  const good = config('/fixture/native');
  for (const invalid of [
    { ...good, helperExecutable: 'helper' },
    { ...good, helperExecutable: '/' + 'x'.repeat(4096) },
    { ...good, ownershipDirectory: '/' + 'x'.repeat(4096) },
    {
      ...good,
      targets: [{ targetId: 'native', label: 'Native', surfaceId: 'window:1' }],
    },
    { ...good, control: { ...good.control, tools: ['navigate'] } },
    { ...good, maxVisitedNodes: 100 },
  ])
    assert.throws(() => decodeMacOSControlBackendConfig(invalid));
  const deadline = new OperationDeadline(1000);
  deadline.cancel();
  let launches = 0;
  try {
    assert.throws(() =>
      createMacOSControlBackend(good, deadline, () => {
        launches++;
        return new Broker();
      }),
    );
    assert.equal(launches, 0);
  } finally {
    deadline.dispose();
  }
});

await test('old private protocol is refused before acquire and only proved child exit frees the empty reservation', async () => {
  for (const reaped of [true, false]) {
    const broker = new Broker();
    broker.rejectPreflight = true;
    broker.cleanup = {
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
      helperStopped: reaped,
    };
    const f = await fixture(broker);
    try {
      const result = await f.control.acquire(
        { owner, session, targetId: 'native' },
        f.deadline,
      );
      assert.equal(result.outcome, reaped ? 'NOT_ACQUIRED' : 'UNCERTAIN');
      assert.deepEqual(
        broker.commands.map((command) => command.operation),
        ['preflight'],
      );
      assert.equal(broker.revocations, 0);
      assert.equal(broker.closeCalls, 1);
      assert.equal(existsSync(join(f.directory, 'capture.lock')), !reaped);
      if (reaped) {
        await f.handle.close(f.deadline);
        assert.deepEqual(await readdir(f.directory), []);
      } else {
        await assert.rejects(f.handle.close(f.deadline));
        assert.equal(broker.closeCalls, 1);
      }
    } finally {
      await f.dispose();
    }
  }
});

await test('late valid preflight cannot acquire after monotonic expiry or host stop', async () => {
  for (const stopped of [false, true]) {
    const broker = new Broker(),
      clock = new FakeClock(),
      deadline = new OperationDeadline(10, clock);
    let release = () => {},
      entered = () => {};
    broker.preflightDelay = new Promise<void>((resolve) => {
      release = resolve;
    });
    const seen = new Promise<void>((resolve) => {
      entered = resolve;
    });
    broker.preflightSeen = entered;
    const f = await fixture(broker);
    try {
      const pending = f.control.acquire(
        { owner, session, targetId: 'native' },
        deadline,
      );
      await seen;
      assert.ok(existsSync(join(f.directory, 'capture.lock')));
      assert.equal(broker.closeCalls, 0);
      let closing: Promise<void> | undefined;
      if (stopped) closing = f.handle.close(f.deadline);
      else clock.monotonic += 11; // Deliberately do not fire the deadline timer.
      assert.deepEqual(
        broker.commands.map((command) => command.operation),
        ['preflight'],
      );
      release();
      assert.equal((await pending).outcome, 'NOT_ACQUIRED');
      await closing;
      assert.deepEqual(
        broker.commands.map((command) => command.operation),
        ['preflight'],
      );
      assert.equal(broker.revocations, 0);
      assert.equal(broker.closeCalls, 1);
      assert.deepEqual(await readdir(f.directory), []);
      await f.handle.close(f.deadline);
    } finally {
      release();
      deadline.dispose();
      await f.dispose();
    }
    assert.equal(clock.pendingTimers(), 0);
  }
});

await test('native lease acquires durable exclusion before helper and bare capture cannot bypass it', async () => {
  const f = await fixture();
  try {
    assert.equal(f.handle.backend.desktopIsolation, 'native-broker');
    await assert.rejects(
      f.handle.backend.capture(
        { targetId: 'native', surfaceId: 'display:1' },
        f.deadline,
      ),
    );
    assert.equal(f.launches(), 0);
    const lease = await acquire(f);
    assert.equal(f.launches(), 1);
    assert.deepEqual(
      await f.control.acquire(
        {
          owner,
          session: { ...session, sessionId: 'other' },
          targetId: 'native',
        },
        f.deadline,
      ),
      { outcome: 'NOT_ACQUIRED', code: 'BUSY' },
    );
    await assert.rejects(
      f.control.observe(
        { ...lease, generation: 'forged' },
        { surfaceId: 'display:1', structured: false },
        f.deadline,
      ),
    );
    assert.deepEqual(
      f.broker.commands.map((command) => command.operation),
      ['preflight', 'acquire'],
    );
    await f.control.release(lease, f.deadline);
    await f.handle.close(f.deadline);
    assert.deepEqual(await readdir(f.directory), []);
  } finally {
    await f.dispose();
  }
});

await test('foreign capture marker is an ordinary busy refusal with no helper or foreign cleanup', async () => {
  const f = await fixture();
  try {
    const marker = join(f.directory, 'capture.lock');
    await mkdir(marker, { mode: 0o700 });
    assert.deepEqual(
      await f.control.acquire(
        { owner, session, targetId: 'native' },
        f.deadline,
      ),
      { outcome: 'NOT_ACQUIRED', code: 'BUSY' },
    );
    assert.equal(f.launches(), 0);
    assert.equal(f.broker.closeCalls, 0);
    assert.deepEqual(await readdir(f.directory), ['capture.lock']);
    await rm(marker, { recursive: true });
    const lease = await acquire(f);
    await f.control.release(lease, f.deadline);
    await f.handle.close(f.deadline);
  } finally {
    await f.dispose();
  }
});

await test('native acquire helper refusal removes only its own empty reservation and may open later', async () => {
  const broker = new Broker();
  broker.respond = async (request) => {
    assert.equal(request.operation, 'acquire');
    return {
      ...replyHeader,
      operation: 'acquire',
      result: { outcome: 'NOT_ACQUIRED', code: 'BUSY' },
    };
  };
  const f = await fixture(broker);
  try {
    assert.deepEqual(
      await f.control.acquire(
        { owner, session, targetId: 'native' },
        f.deadline,
      ),
      { outcome: 'NOT_ACQUIRED', code: 'BUSY' },
    );
    assert.equal(broker.closeCalls, 1);
    assert.deepEqual(await readdir(f.directory), []);
    broker.respond = undefined;
    const lease = await acquire(f);
    await f.control.release(lease, f.deadline);
    await f.handle.close(f.deadline);
  } finally {
    await f.dispose();
  }
});

await test('typed uncertain helper failure fences subsequent commands and does not replay', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f);
    f.broker.respond = async () => ({
      ...replyHeader,
      operation: 'failure',
      result: { code: 'INPUT_FAILED', physicalSettlement: 'uncertain' },
    });
    await assert.rejects(f.control.windows(lease, f.deadline));
    const count = f.broker.commands.length;
    await assert.rejects(
      f.control.observe(
        lease,
        { surfaceId: 'display:1', structured: false },
        f.deadline,
      ),
    );
    assert.equal(f.broker.commands.length, count);
    assert.equal(
      (await f.control.release(lease, f.deadline)).physicalSettlement,
      'uncertain',
    );
    await assert.rejects(f.handle.close(f.deadline));
    assert.deepEqual(await readdir(f.directory), ['capture.lock']);
  } finally {
    await f.dispose();
  }
});

await test('concurrent native release and host close join one cleanup proof and marker removal', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f);
    const [one, two] = await Promise.all([
      f.control.release(lease, f.deadline),
      f.control.release(lease, f.deadline),
      f.handle.close(f.deadline),
    ]);
    assert.equal(one.physicalSettlement, 'settled');
    assert.deepEqual(two, one);
    assert.equal(f.broker.closeCalls, 1);
    assert.deepEqual(await readdir(f.directory), []);
  } finally {
    await f.dispose();
  }
});

await test('failed native held-input cleanup retains durable occupancy and is never retried', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f);
    f.broker.cleanup = {
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
      helperStopped: true,
    };
    assert.equal(
      (await f.control.release(lease, f.deadline)).physicalSettlement,
      'uncertain',
    );
    assert.equal(
      (await f.control.release(lease, f.deadline)).physicalSettlement,
      'uncertain',
    );
    assert.equal(f.broker.closeCalls, 1);
    assert.deepEqual(await readdir(f.directory), ['capture.lock']);
    await assert.rejects(f.handle.close(f.deadline));
  } finally {
    await f.dispose();
  }
});

await test('native known delivery remains distinct from failed post-observation', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f);
    const result = await f.control.perform(lease, click(f.broker), f.deadline);
    assert.equal(result.delivery.outcome, 'DISPATCHED');
    assert.equal(result.observation.state, 'FAILED');
    assert.equal(
      f.broker.commands.filter((command) => command.operation === 'perform')
        .length,
      1,
    );
    await f.control.release(lease, f.deadline);
    await f.handle.close(f.deadline);
  } finally {
    await f.dispose();
  }
});

await test('native concurrent action is a pre-dispatch busy refusal without poisoning an earlier read', async () => {
  const f = await fixture();
  let settle: ((reply: NativeBrokerReply) => void) | undefined;
  const pending = new Promise<NativeBrokerReply>((resolve) => {
    settle = resolve;
  });
  try {
    const lease = await acquire(f);
    f.broker.respond = () => pending;
    const reading = f.control.windows(lease, f.deadline);
    await Promise.resolve();
    const result = await f.control.perform(lease, click(f.broker), f.deadline);
    assert.deepEqual(result.delivery, {
      outcome: 'NOT_DISPATCHED',
      physicalSettlement: 'settled',
      code: 'BUSY',
    });
    assert.equal(f.broker.commands.length, 3);
    assert.ok(settle);
    settle({
      ...replyHeader,
      operation: 'windows',
      result: {
        windowsId: 'windows',
        capturedAt: new Date(f.broker.fake.clock.wall).toISOString(),
        windows: [],
        truncated: false,
      },
    });
    await reading;
    f.broker.respond = undefined;
    await f.control.observe(
      lease,
      { surfaceId: 'display:1', structured: false },
      f.deadline,
    );
    await f.control.release(lease, f.deadline);
    await f.handle.close(f.deadline);
  } finally {
    settle?.({
      ...replyHeader,
      operation: 'windows',
      result: {
        windowsId: 'windows',
        capturedAt: new Date(f.broker.fake.clock.wall).toISOString(),
        windows: [],
        truncated: false,
      },
    });
    await f.dispose();
  }
});

await test('native queued monotonic expiry submits no helper command and preserves the idle lease', async () => {
  const f = await fixture();
  const clock = new FakeClock();
  const deadline = new OperationDeadline(10, clock);
  try {
    const lease = await acquire(f);
    const rejected = assert.rejects(f.control.windows(lease, deadline));
    clock.monotonic += 11;
    await rejected;
    assert.equal(f.broker.commands.length, 2);
    await f.control.observe(
      lease,
      { surfaceId: 'display:1', structured: false },
      f.deadline,
    );
    assert.equal(
      (await f.control.release(lease, f.deadline)).physicalSettlement,
      'settled',
    );
    await f.handle.close(f.deadline);
  } finally {
    deadline.dispose();
    await f.dispose();
  }
});

await test('foreign acquisition identity keeps only the caller cleanup capability and never opens a second helper', async () => {
  const broker = new Broker();
  broker.respond = async (request) => {
    if (request.operation !== 'acquire')
      throw new Error('Unexpected fake command');
    return {
      ...replyHeader,
      operation: 'acquire',
      result: {
        outcome: 'ACQUIRED',
        lease: { ...request.lease, generation: 'foreign' },
      },
    };
  };
  const f = await fixture(broker);
  try {
    const result = await f.control.acquire(
      { owner, session, targetId: 'native' },
      f.deadline,
    );
    assert.equal(result.outcome, 'UNCERTAIN');
    if (result.outcome !== 'UNCERTAIN' || result.lease === null)
      throw new Error('Expected own uncertain lease');
    assert.equal(result.lease.generation, session.generation);
    assert.deepEqual(
      await f.control.acquire(
        { owner, session, targetId: 'native' },
        f.deadline,
      ),
      { outcome: 'NOT_ACQUIRED', code: 'BUSY' },
    );
    assert.equal(f.launches(), 1);
    assert.equal(
      (await f.control.release(result.lease, f.deadline)).physicalSettlement,
      'uncertain',
    );
    assert.equal(broker.closeCalls, 1);
    await assert.rejects(f.handle.close(f.deadline));
  } finally {
    await f.dispose();
  }
});

await test('settled helper failure after possible action is not a proven no-dispatch refusal', async () => {
  const f = await fixture();
  try {
    const lease = await acquire(f);
    f.broker.respond = async () => ({
      ...replyHeader,
      operation: 'failure',
      result: { code: 'INPUT_FAILED', physicalSettlement: 'settled' },
    });
    const result = await f.control.perform(lease, click(f.broker), f.deadline);
    assert.equal(result.delivery.outcome, 'UNCERTAIN');
    const count = f.broker.commands.length;
    await assert.rejects(
      f.control.observe(
        lease,
        { surfaceId: 'display:1', structured: false },
        f.deadline,
      ),
    );
    assert.equal(f.broker.commands.length, count);
    await f.control.release(lease, f.deadline);
    await assert.rejects(f.handle.close(f.deadline));
  } finally {
    await f.dispose();
  }
});

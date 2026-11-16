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
    if (this.respond !== undefined) return this.respond(request);
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
    assert.equal(f.broker.commands.length, 1);
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
    assert.equal(f.broker.commands.length, 2);
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
    assert.equal(f.broker.commands.length, 1);
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

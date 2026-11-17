import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough, Writable } from 'node:stream';
import test from 'node:test';
import { OperationDeadline } from '../deadline.ts';
import type { NativeChild } from './macos.ts';
import {
  NATIVE_BROKER_VERSION,
  NATIVE_BROKER_MAX_REPLY_BYTES,
} from './native-broker-protocol.ts';
import type {
  NativeBrokerReply,
  NativeBrokerRequest,
} from './native-broker-protocol.ts';
import { FakeClock } from '../contracts.test-support.ts';
import { decodeNativeLease } from '../native-control.ts';
import type { NativeLease } from '../native-control.ts';
import { decodeNativeArmStatus } from '../native-arming.ts';
import {
  NativeProcessError,
  SpawnNativeControlProcess,
  parseNativeReplyJson,
} from './native-control-process.ts';

interface Command {
  readonly operation: string;
  readonly requestId: string;
  readonly targetRequestId?: string;
  readonly lease?: NativeLease;
}
function object(value: unknown): Readonly<Record<string, unknown>> {
  assert.ok(
    value !== null && typeof value === 'object' && !Array.isArray(value),
  );
  return value as Readonly<Record<string, unknown>>;
}
function command(source: string): Command {
  const row = object(JSON.parse(source));
  assert.equal(row['version'], NATIVE_BROKER_VERSION);
  assert.equal(typeof row['operation'], 'string');
  assert.equal(typeof row['requestId'], 'string');
  const operation = row['operation'];
  const requestId = row['requestId'];
  assert.ok(typeof operation === 'string' && typeof requestId === 'string');
  const targetRequestId = row['targetRequestId'];
  if (targetRequestId !== undefined)
    assert.equal(typeof targetRequestId, 'string');
  return {
    operation,
    requestId,
    ...(typeof targetRequestId === 'string' ? { targetRequestId } : {}),
    ...(row['lease'] === undefined
      ? {}
      : { lease: decodeNativeLease(row['lease']) }),
  };
}
function decode(value: unknown): NativeBrokerReply {
  const row = object(value);
  assert.equal(row['version'], NATIVE_BROKER_VERSION);
  const requestId = row['requestId'];
  assert.equal(typeof requestId, 'string');
  if (typeof requestId !== 'string')
    throw new Error('Missing test correlation');
  if (row['operation'] === 'arm_revoke')
    return {
      version: NATIVE_BROKER_VERSION,
      requestId,
      operation: 'arm_revoke',
      result: decodeNativeArmStatus(row['result']),
    };
  if (row['operation'] === 'preflight')
    return {
      version: NATIVE_BROKER_VERSION,
      requestId,
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
    };
  if (row['operation'] === 'stop') {
    const result = object(row['result']);
    assert.ok(
      result['physicalSettlement'] === 'settled' ||
        result['physicalSettlement'] === 'uncertain',
    );
    assert.equal(typeof result['heldInputsReleased'], 'boolean');
    const physicalSettlement = result['physicalSettlement'];
    const heldInputsReleased = result['heldInputsReleased'];
    if (typeof heldInputsReleased !== 'boolean')
      throw new Error('Invalid test proof');
    return {
      version: NATIVE_BROKER_VERSION,
      requestId,
      operation: 'stop',
      result: { physicalSettlement, heldInputsReleased },
    };
  }
  throw new Error('Unsupported test reply');
}
function decodeRequest(value: unknown): NativeBrokerRequest {
  const row = object(value);
  if (row['operation'] === 'arm_revoke') {
    assert.deepEqual(Object.keys(row).sort(), ['lease', 'operation']);
    return { operation: 'arm_revoke', lease: decodeNativeLease(row['lease']) };
  }
  assert.deepEqual(Object.keys(row), ['operation']);
  const operation = row['operation'];
  assert.ok(operation === 'preflight' || operation === 'stop');
  return { operation };
}
class Child extends EventEmitter implements NativeChild {
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  readonly writes: Command[] = [];
  kills = 0;
  autoCloseOnKill = true;
  onCommand: ((command: Command) => void) | undefined;
  readonly stdin = new Writable({
    write: (bytes: Buffer, _encoding, done) => {
      const parsed = command(bytes.toString('utf8'));
      this.writes.push(parsed);
      this.onCommand?.(parsed);
      done();
    },
  });
  kill(_signal: 'SIGKILL'): boolean {
    this.kills++;
    if (this.autoCloseOnKill) queueMicrotask(() => this.emit('close', null));
    return true;
  }
  reply(
    command: Command,
    result: unknown = {},
    requestId = command.requestId,
  ): void {
    this.stdout.write(
      JSON.stringify({
        version: NATIVE_BROKER_VERSION,
        requestId,
        operation: command.operation,
        result,
      }) + '\n',
    );
  }
  cleanStop(): void {
    this.onCommand = (command) => {
      if (command.operation === 'stop') {
        this.reply(command, {
          physicalSettlement: 'settled',
          heldInputsReleased: true,
        });
        queueMicrotask(() => this.emit('close', 0));
      }
    };
  }
}
function fixture(cleanup = 20) {
  const child = new Child();
  const startup = new OperationDeadline(1000);
  const process = new SpawnNativeControlProcess(
    '/fixture/helper',
    { decodeRequest, decodeReply: decode },
    startup,
    cleanup,
    () => child,
  );
  startup.dispose();
  return { child, process };
}
function uncertain(error: unknown): boolean {
  return (
    error instanceof NativeProcessError &&
    error.physicalSettlement === 'uncertain'
  );
}

const lease = decodeNativeLease({
  leaseId: 'lease',
  sessionId: 'session',
  generation: 'provider-generation',
  targetId: 'screen',
});
const revoked = {
  state: 'REVOKED',
  binding: { lease, helperGeneration: '11111111-2222-3333-4444-555555555555' },
  requestId: null,
  armGeneration: null,
  revokedAt: '2026-11-17T00:00:00Z',
  reason: 'HOST_REVOKED',
};

await test('revoke bypasses pending input, rejects duplicate and normal work, and correlates both reply orders', async () => {
  for (const reverse of [false, true]) {
    const { child, process } = fixture();
    const deadline = new OperationDeadline(1000);
    try {
      const normal = process.request({ operation: 'preflight' }, deadline);
      const control = process.revoke(lease, deadline);
      assert.equal(child.writes.length, 2);
      assert.deepEqual(child.writes[1]?.lease, lease);
      await assert.rejects(
        process.revoke(lease, deadline),
        (error) => error instanceof NativeProcessError && error.code === 'BUSY',
      );
      await assert.rejects(
        process.request({ operation: 'preflight' }, deadline),
        (error) => error instanceof NativeProcessError && error.code === 'BUSY',
      );
      const replies = child.writes.map((command) => ({
        version: NATIVE_BROKER_VERSION,
        requestId: command.requestId,
        operation: command.operation,
        result: command.operation === 'arm_revoke' ? revoked : {},
      }));
      if (reverse) replies.reverse();
      child.stdout.write(
        replies.map((reply) => JSON.stringify(reply) + '\n').join(''),
      );
      assert.equal((await control).operation, 'arm_revoke');
      assert.equal((await normal).operation, 'preflight');
      child.cleanStop();
      assert.equal(
        (await process.close(deadline)).physicalSettlement,
        'settled',
      );
    } finally {
      deadline.dispose();
    }
  }
});

await test('pre-write expired revoke leaves the ordinary request healthy', async () => {
  const { child, process } = fixture();
  const deadline = new OperationDeadline(1000),
    expired = new OperationDeadline(1);
  expired.cancel();
  try {
    const normal = process.request({ operation: 'preflight' }, deadline);
    await assert.rejects(process.revoke(lease, expired));
    await assert.rejects(
      process.request({ operation: 'arm_revoke', lease }, deadline),
    );
    assert.equal(child.writes.length, 1);
    const command = child.writes[0];
    assert.ok(command);
    child.reply(command);
    assert.equal((await normal).operation, 'preflight');
    child.cleanStop();
    assert.equal((await process.close(deadline)).physicalSettlement, 'settled');
  } finally {
    deadline.dispose();
    expired.dispose();
  }
});

await test('revoke timeout fences both pending lanes, sends one cancellation and never replays', async () => {
  const { child, process } = fixture();
  const deadline = new OperationDeadline(1000),
    controlDeadline = new OperationDeadline(1000);
  try {
    const normal = assert.rejects(
      process.request({ operation: 'preflight' }, deadline),
      uncertain,
    );
    const control = assert.rejects(
      process.revoke(lease, controlDeadline),
      uncertain,
    );
    controlDeadline.cancel();
    await Promise.all([normal, control]);
    assert.equal(child.writes.length, 3);
    assert.equal(child.writes[2]?.operation, 'cancel');
    assert.equal(child.writes[2]?.targetRequestId, child.writes[0]?.requestId);
    assert.equal(
      (await process.close(deadline)).physicalSettlement,
      'uncertain',
    );
  } finally {
    deadline.dispose();
    controlDeadline.dispose();
  }
});

await test('bad revoke correlation fences both pending requests without cleanup proof', async () => {
  const { child, process } = fixture();
  const deadline = new OperationDeadline(1000);
  try {
    const normal = assert.rejects(
      process.request({ operation: 'preflight' }, deadline),
      uncertain,
    );
    const control = assert.rejects(process.revoke(lease, deadline), uncertain);
    const command = child.writes[1];
    assert.ok(command);
    child.reply(command, revoked, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
    await Promise.all([normal, control]);
    assert.equal(child.writes.length, 2);
    assert.equal(
      (await process.close(deadline)).physicalSettlement,
      'uncertain',
    );
  } finally {
    deadline.dispose();
  }
});

await test('native already-expired and queued monotonic startup performs zero launches', () => {
  const clock = new FakeClock();
  for (const monotonicOnly of [false, true]) {
    const deadline = new OperationDeadline(10, clock);
    let launches = 0;
    if (monotonicOnly) clock.monotonic += 11;
    else deadline.cancel();
    assert.throws(
      () =>
        new SpawnNativeControlProcess(
          '/fixture/helper',
          { decodeRequest, decodeReply: decode },
          deadline,
          20,
          () => {
            launches++;
            return new Child();
          },
        ),
    );
    assert.equal(launches, 0);
    deadline.dispose();
  }
});

await test('invalid outgoing command is rejected by owning codec before stdin', async () => {
  const { child, process } = fixture();
  const deadline = new OperationDeadline(1000);
  try {
    const extra = { operation: 'preflight' as const, owner: 'forged' };
    await assert.rejects(
      process.request(extra, deadline),
      (error) =>
        error instanceof NativeProcessError &&
        error.code === 'INVALID_REQUEST' &&
        error.physicalSettlement === 'settled',
    );
    assert.equal(child.writes.length, 0);
    child.cleanStop();
    await process.close(deadline);
  } finally {
    deadline.dispose();
  }
});

await test('native JSON boundary rejects duplicate escaped/nested aliases and malformed Unicode', () => {
  for (const source of [
    '{"a":1,"a":2}',
    '{"nested":{"a":1,"\\u0061":2}}',
    '{"array":[{"x":1,"x":2}]}',
  ])
    assert.throws(() => parseNativeReplyJson(Buffer.from(source)));
  assert.throws(() => parseNativeReplyJson(Uint8Array.from([0xff])));
  assert.deepEqual(
    parseNativeReplyJson(Buffer.from('{"a":{"x":1},"b":{"x":2}}')),
    { a: { x: 1 }, b: { x: 2 } },
  );
});

await test('native process correlates bounded split frames and cleanup requires actual exit', async () => {
  const { child, process } = fixture();
  const deadline = new OperationDeadline(1000);
  try {
    child.onCommand = (command) => {
      const wire = Buffer.from(
        JSON.stringify({
          version: NATIVE_BROKER_VERSION,
          requestId: command.requestId,
          operation: 'preflight',
          result: {},
        }) + '\n',
      );
      child.stdout.write(wire.subarray(0, 5));
      child.stdout.write(wire.subarray(5));
    };
    const reply = await process.request({ operation: 'preflight' }, deadline);
    assert.equal(reply.operation, 'preflight');
    child.cleanStop();
    assert.deepEqual(await process.close(deadline), {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
    });
    assert.equal(
      child.writes.filter((row) => row.operation === 'preflight').length,
      1,
    );
    assert.equal(child.kills, 0);
  } finally {
    deadline.dispose();
  }
});

await test('native pre-dispatch expiry and busy refusal perform no extra writes', async () => {
  const { child, process } = fixture();
  const expired = new OperationDeadline(1000);
  expired.cancel();
  const deadline = new OperationDeadline(1000);
  try {
    await assert.rejects(
      process.request({ operation: 'preflight' }, expired),
      (error) =>
        error instanceof NativeProcessError &&
        error.physicalSettlement === 'settled',
    );
    assert.equal(child.writes.length, 0);
    const first = process.request({ operation: 'preflight' }, deadline);
    await assert.rejects(
      process.request({ operation: 'preflight' }, deadline),
      (error) => error instanceof NativeProcessError && error.code === 'BUSY',
    );
    assert.equal(child.writes.length, 1);
    const sent = child.writes[0];
    assert.ok(sent);
    child.reply(sent);
    await first;
    child.cleanStop();
    await process.close(deadline);
  } finally {
    expired.dispose();
    deadline.dispose();
  }
});

await test('native deadline sends one cancellation and child death never proves held-input cleanup', async () => {
  const { child, process } = fixture();
  const deadline = new OperationDeadline(1000);
  const cleanup = new OperationDeadline(1000);
  try {
    const rejected = assert.rejects(
      process.request({ operation: 'preflight' }, deadline),
      uncertain,
    );
    deadline.cancel();
    await rejected;
    assert.deepEqual(
      child.writes.map((row) => row.operation),
      ['preflight', 'cancel'],
    );
    assert.equal(child.writes[1]?.targetRequestId, child.writes[0]?.requestId);
    assert.deepEqual(await process.close(cleanup), {
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
      helperStopped: true,
    });
    await assert.rejects(
      process.request({ operation: 'preflight' }, cleanup),
      uncertain,
    );
  } finally {
    deadline.dispose();
    cleanup.dispose();
  }
});

await test('lost reap stays bounded and fenced even after late physical exit', async () => {
  const { child, process } = fixture(12);
  child.autoCloseOnKill = false;
  const deadline = new OperationDeadline(1000);
  const cleanup = new OperationDeadline(1000);
  try {
    const failed = assert.rejects(
      process.request({ operation: 'preflight' }, deadline),
      uncertain,
    );
    deadline.cancel();
    await failed;
    const result = await process.close(cleanup);
    assert.equal(result.physicalSettlement, 'uncertain');
    assert.equal(result.helperStopped, false);
    child.emit('close', null);
    await assert.rejects(
      process.request({ operation: 'preflight' }, cleanup),
      uncertain,
    );
    assert.deepEqual(await process.close(cleanup), result);
  } finally {
    deadline.dispose();
    cleanup.dispose();
  }
});

await test('wrong correlation, duplicate keys and oversized output fence without replay', async () => {
  for (const fault of ['correlation', 'duplicate', 'oversize']) {
    const { child, process } = fixture();
    const deadline = new OperationDeadline(1000);
    try {
      const failed = assert.rejects(
        process.request({ operation: 'preflight' }, deadline),
        uncertain,
      );
      const sent = child.writes[0];
      assert.ok(sent);
      if (fault === 'correlation') child.reply(sent, {}, 'foreign');
      else if (fault === 'duplicate')
        child.stdout.write(
          '{"version":"' +
            NATIVE_BROKER_VERSION +
            '","version":"' +
            NATIVE_BROKER_VERSION +
            '"}\n',
        );
      else
        child.stdout.write(Buffer.alloc(NATIVE_BROKER_MAX_REPLY_BYTES + 1, 32));
      await failed;
      assert.equal(child.writes.length, 1);
      assert.ok(child.kills >= 1);
      assert.equal(
        (await process.close(deadline)).physicalSettlement,
        'uncertain',
      );
    } finally {
      deadline.dispose();
    }
  }
});

await test('native pipe read errors are owned and fence the process without replay', async () => {
  for (const stream of ['stdout', 'stderr'] as const) {
    const { child, process } = fixture();
    const deadline = new OperationDeadline(1000);
    try {
      const failed = assert.rejects(
        process.request({ operation: 'preflight' }, deadline),
        uncertain,
      );
      child[stream].emit('error', new Error('Synthetic broken pipe'));
      await failed;
      assert.equal(child.writes.length, 1);
      assert.equal(
        (await process.close(deadline)).physicalSettlement,
        'uncertain',
      );
    } finally {
      deadline.dispose();
    }
  }
});

await test('helper cleanup reply without process exit is uncertain and memoized', async () => {
  const { child, process } = fixture(12);
  child.autoCloseOnKill = false;
  child.onCommand = (command) =>
    child.reply(command, {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
    });
  const deadline = new OperationDeadline(1000);
  try {
    const first = await process.close(deadline);
    assert.equal(first.physicalSettlement, 'uncertain');
    assert.equal(first.helperStopped, false);
    child.emit('close', 0);
    assert.deepEqual(await process.close(deadline), first);
    assert.equal(child.writes.length, 1);
  } finally {
    deadline.dispose();
  }
});

await test('native cleanup exit beyond monotonic cutoff cannot publish success before timer runs', async () => {
  const { child, process } = fixture(20);
  const clock = new FakeClock();
  const deadline = new OperationDeadline(1000, clock);
  child.onCommand = (command) =>
    child.reply(command, {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
    });
  try {
    const stopping = process.close(deadline);
    await Promise.resolve();
    clock.monotonic += 21;
    child.emit('close', 0);
    assert.deepEqual(await stopping, {
      physicalSettlement: 'uncertain',
      heldInputsReleased: true,
      helperStopped: true,
    });
  } finally {
    deadline.dispose();
  }
});

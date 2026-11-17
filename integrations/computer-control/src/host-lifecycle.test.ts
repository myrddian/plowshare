import assert from 'node:assert/strict';
import test from 'node:test';
import { FakeClock } from './contracts.test-support.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import {
  CatalogueLeaseExpired,
  HostCatalogueLease,
  waitForHostPoll,
  withHostAdmission,
} from './host-lifecycle.ts';
import { ComputerCleanup } from './runner.ts';

await test('catalogue availability starts at publication beginning and requires confirmation', () => {
  const clock = new FakeClock();
  const lease = new HostCatalogueLease(1000, 400, clock, () => {});
  const pending = lease.begin();
  assert.throws(() => lease.pass(500), /not been confirmed/);
  clock.advance(100);
  lease.confirm(pending);
  const pass = lease.pass(1000);
  try {
    assert.equal(pass.remainingMs(), 900);
    assert.equal(lease.idleDelay(500), 300);
    clock.advance(300);
    assert.ok(lease.renewalDue());
  } finally {
    pass.dispose();
    lease.stop();
  }
  assert.equal(clock.pendingTimers(), 0);
});

await test('a confirmed renewal cannot extend a pass admitted under the previous lease', () => {
  const clock = new FakeClock();
  let expirations = 0;
  const lease = new HostCatalogueLease(1000, 300, clock, () => {
    expirations++;
  });
  lease.confirm(lease.begin());
  const original = lease.pass(900);
  clock.advance(300);
  lease.confirm(lease.begin());
  const next = lease.pass(1000);
  try {
    clock.advance(601);
    assert.equal(original.remainingMs(), 0);
    assert.equal(next.remainingMs(), 399);
    lease.requireActive();
    assert.equal(expirations, 0);
  } finally {
    original.dispose();
    next.dispose();
    lease.stop();
  }
  assert.equal(clock.pendingTimers(), 0);
});

await test('late renewal cannot bridge an expired old lease when timers have not fired', () => {
  const clock = new FakeClock();
  let expirations = 0;
  const lease = new HostCatalogueLease(1000, 300, clock, () => {
    expirations++;
  });
  lease.confirm(lease.begin());
  clock.advance(300);
  const renewal = lease.begin();
  clock.monotonic = 1001;
  assert.throws(() => lease.confirm(renewal), CatalogueLeaseExpired);
  assert.equal(expirations, 1);
  assert.throws(() => lease.pass(100), CatalogueLeaseExpired);
  lease.stop();
  assert.equal(clock.pendingTimers(), 0);
});

await test('monotonic admission detects expiry without timer delivery or wall-clock authority', () => {
  const clock = new FakeClock();
  let expirations = 0;
  const lease = new HostCatalogueLease(1000, 300, clock, () => {
    expirations++;
  });
  lease.confirm(lease.begin());
  clock.wall += 3_600_000;
  lease.requireActive();
  assert.equal(expirations, 0);
  clock.monotonic = 1000;
  assert.throws(() => lease.pass(100), CatalogueLeaseExpired);
  assert.equal(expirations, 1);
  lease.stop();
  assert.equal(clock.pendingTimers(), 0);
});

await test('a pending metadata publication does not renew availability or revive a stopped lease', () => {
  const clock = new FakeClock();
  let expirations = 0;
  const lease = new HostCatalogueLease(1000, 300, clock, () => {
    expirations++;
  });
  lease.confirm(lease.begin());
  clock.advance(300);
  const renewal = lease.begin();
  clock.advance(701);
  assert.equal(expirations, 1);
  assert.throws(() => lease.confirm(renewal), /no longer admissible/);
  assert.throws(() => lease.pass(100), CatalogueLeaseExpired);
  lease.stop();
  assert.equal(clock.pendingTimers(), 0);
});

await test('host idle waiting uses the injected clock and releases its timer on stop', async () => {
  const clock = new FakeClock();
  const stop = new AbortController();
  let finished = false;
  const waiting = waitForHostPoll(clock, 1000, stop.signal).then(() => {
    finished = true;
  });
  clock.advance(999);
  await Promise.resolve();
  assert.equal(finished, false);
  stop.abort();
  await waiting;
  assert.equal(finished, true);
  assert.equal(clock.pendingTimers(), 0);
});

await test('cleanup remains physically owned after its caller deadline until the original close settles', async () => {
  const clock = new FakeClock();
  const cleanup = new ComputerCleanup();
  const deadline = new OperationDeadline(1000, clock);
  let release: (() => void) | undefined;
  const closing = cleanup.run(
    deadline,
    () =>
      new Promise<void>((resolve) => {
        release = resolve;
      }),
  );
  const rejected = assert.rejects(closing, DeadlineExceeded);
  await Promise.resolve();
  clock.advance(1001);
  await rejected;
  assert.equal(cleanup.pending(), 1);
  if (release === undefined) assert.fail('Original close was not admitted');
  release();
  for (let turn = 0; turn < 5; turn++) await Promise.resolve();
  assert.equal(cleanup.pending(), 0);
  deadline.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

await test('cleanup refuses a queued physical close after monotonic expiry without timer delivery', async () => {
  const clock = new FakeClock();
  const cleanup = new ComputerCleanup();
  const deadline = new OperationDeadline(1000, clock);
  let calls = 0;
  const closing = cleanup.run(deadline, async () => {
    calls++;
  });
  clock.monotonic = 1001;
  await assert.rejects(closing, DeadlineExceeded);
  assert.equal(calls, 0);
  deadline.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

await test('admission timeout observes late work without admitting a late callback', async () => {
  const clock = new FakeClock(),
    deadline = new OperationDeadline(100, clock);
  let release = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let effects = 0;
  const running = withHostAdmission(
    deadline,
    new AbortController().signal,
    async (admitted) => {
      await gate;
      admitted();
      effects++;
    },
  );
  const rejected = assert.rejects(running, /admission expired/);
  await Promise.resolve();
  clock.advance(101);
  await rejected;
  release();
  for (let turn = 0; turn < 10; turn++) await Promise.resolve();
  assert.equal(effects, 0);
  deadline.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

await test('accepted admission does not impose its short budget on the persistent host lifetime', async () => {
  const clock = new FakeClock(),
    deadline = new OperationDeadline(100, clock);
  let release = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let entered = () => {};
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  let done = false;
  const running = withHostAdmission(
    deadline,
    new AbortController().signal,
    async (admitted) => {
      admitted();
      entered();
      await gate;
      done = true;
    },
  );
  await started;
  clock.advance(1000);
  await Promise.resolve();
  assert.equal(done, false);
  release();
  await running;
  assert.equal(done, true);
  deadline.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

await test('stop before queued admission causes zero callback effects', async () => {
  const clock = new FakeClock(),
    deadline = new OperationDeadline(100, clock),
    stop = new AbortController();
  let effects = 0;
  const running = withHostAdmission(deadline, stop.signal, async (admitted) => {
    admitted();
    effects++;
  });
  stop.abort();
  await assert.rejects(running, /admission expired/);
  assert.equal(effects, 0);
  deadline.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DeadlineExceeded,
  fromCallDeadline,
  isoTimestamp,
  OperationDeadline,
} from './deadline.ts';
import { FakeClock } from './contracts.test-support.ts';

await test('wire instants accept Java fractional precision and reject rollovers', () => {
  for (const value of [
    '2026-11-16T00:00:01Z',
    '2026-11-16T00:00:01.1Z',
    '2026-11-16T00:00:01.123Z',
    '2026-11-16T00:00:01.123456789Z',
  ])
    assert.equal(isoTimestamp(value), value);
  for (const invalid of [
    '2026-02-30T00:00:01Z',
    '2026-11-16 00:00:01Z',
    '2026-11-16T00:00:01+00:00',
    '2026-11-16T00:00:01.1234567890Z',
  ])
    assert.throws(() => isoTimestamp(invalid));
});

await test('queueing and nested work preserve earlier monotonic budget and abort signals', () => {
  const clock = new FakeClock();
  const deadline = fromCallDeadline('2026-11-16T00:00:01Z', 2000, clock);
  clock.advance(300);
  const nested = deadline.child(2000);
  assert.equal(nested.remainingMs(), 700);
  clock.wall -= 3_600_000;
  clock.advance(700);
  assert.ok(deadline.signal.aborted);
  assert.ok(nested.signal.aborted);
  assert.throws(() => nested.requireActive(), DeadlineExceeded);
  assert.throws(() => deadline.child(100), DeadlineExceeded);
  deadline.dispose();
  nested.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

await test('budget validation and disposal do not leak timers or reset a cutoff', () => {
  const clock = new FakeClock();
  for (const invalid of [0, -1, Infinity, 1.5, 300_001])
    assert.throws(() => new OperationDeadline(invalid, clock));
  assert.throws(
    () => fromCallDeadline('2026-11-15T00:00:00Z', 1000, clock),
    DeadlineExceeded,
  );
  const deadline = new OperationDeadline(1000, clock);
  deadline.dispose();
  assert.equal(clock.pendingTimers(), 0);
  clock.advance(1001);
  assert.throws(() => deadline.requireActive(), DeadlineExceeded);
});

await test('host cancellation aborts descendants without silently disposing physical-work budgets', () => {
  const clock = new FakeClock();
  const deadline = new OperationDeadline(1000, clock);
  const nested = deadline.child(500);
  deadline.cancel();
  assert.equal(deadline.remainingMs(), 0);
  assert.ok(nested.signal.aborted);
  assert.throws(() => deadline.requireActive(), DeadlineExceeded);
  assert.equal(clock.pendingTimers(), 2);
  deadline.dispose();
  nested.dispose();
  assert.equal(clock.pendingTimers(), 0);
});

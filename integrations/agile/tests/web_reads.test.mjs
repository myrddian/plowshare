import assert from 'node:assert/strict';
import test from 'node:test';
import { PendingReads } from '../src/plowshare_agile/web/reads.mjs';

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

test('identical concurrent GETs share a pending result, completed responses are not cached', async () => {
  const result = deferred();
  let calls = 0;
  const reads = new PendingReads(() => {
    calls++;
    return result.promise;
  });
  const first = reads.read('/board');
  const second = reads.read('/board');
  assert.equal(first, second);
  result.resolve({ tickets: [] });
  assert.deepEqual(await first, { tickets: [] });
  await second;
  assert.equal(calls, 1);
  await reads.read('/board');
  assert.equal(calls, 2);
  assert.equal(reads.pending.size, 0);
});

test('failed reads settle all waiters and require an explicit new read', async () => {
  let calls = 0;
  const reads = new PendingReads(() => {
    calls++;
    throw new Error('refused');
  });
  const first = reads.read('/board');
  const second = reads.read('/board');
  await Promise.all([
    assert.rejects(first, /refused/),
    assert.rejects(second, /refused/),
  ]);
  assert.equal(calls, 1);
  assert.equal(reads.pending.size, 0);
  await assert.rejects(reads.read('/board'), /refused/);
  assert.equal(calls, 2);
});

test('pending admission is bounded without blocking reuse of an admitted GET', async () => {
  const result = deferred();
  const reads = new PendingReads(() => result.promise, 1);
  const first = reads.read('/one');
  assert.equal(first, reads.read('/one'));
  await assert.rejects(reads.read('/two'), /Too many reads/);
  assert.equal(reads.pending.size, 1);
  result.resolve(1);
  await first;
  assert.equal(await reads.read('/two'), 1);
});

test('post-write invalidation aborts old GETs and late completion cannot erase replacement reads', async () => {
  const old = deferred(),
    replacement = deferred(),
    other = deferred();
  const signals = [];
  const reads = new PendingReads((path, signal) => {
    signals.push(signal);
    return path === '/other'
      ? other.promise
      : signals.length === 1
        ? old.promise
        : replacement.promise;
  });
  const first = reads.read('/ticket');
  const unrelated = reads.read('/other');
  await Promise.resolve();
  reads.invalidate(['/ticket']);
  assert.equal(signals[0].aborted, true);
  assert.equal(signals[1].aborted, false);
  const current = reads.read('/ticket');
  old.resolve('stale');
  await first;
  assert.equal(reads.read('/ticket'), current);
  assert.equal(reads.pending.size, 2);
  replacement.resolve('current');
  other.resolve('other');
  assert.equal(await current, 'current');
  assert.equal(await unrelated, 'other');
});

test('logout invalidates every pending GET and the next session cannot join them', async () => {
  const result = deferred();
  const signals = [];
  const reads = new PendingReads((path, signal) => {
    signals.push(signal);
    return result.promise;
  });
  const first = reads.read('/board'),
    second = reads.read('/ticket');
  await Promise.resolve();
  reads.invalidate();
  assert.equal(reads.pending.size, 0);
  assert.ok(signals.every((signal) => signal.aborted));
  const next = reads.read('/board');
  assert.notEqual(next, first);
  result.resolve('done');
  await Promise.all([first, second, next]);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { CapacityToolJournal } from './journal.ts';
import { call, MemoryJournal } from './provider.test-support.ts';
import { FakeClock } from './contracts.test-support.ts';
import type { ToolReceipt } from 'plowshare-client-ts';
import { decodeToolReceipt } from 'plowshare-client-ts';

function executing(): ToolReceipt {
  const invocation = call(new FakeClock(), 'observe', {
    sessionId: 'session',
    generation: 'generation',
    surfaceId: 'viewport',
  });
  return {
    invocationId: invocation.invocationId,
    request: JSON.stringify(invocation),
    phase: 'executing',
    result: null,
    occurredAt: null,
  };
}

await test('future result capacity rejects intent before delegated durable write', async () => {
  const delegate = new MemoryJournal('a'.repeat(64));
  const journal = await CapacityToolJournal.create(delegate, 1024);
  await assert.rejects(journal.save(executing()), /capacity/);
  assert.equal(delegate.rows.size, 0);
});

await test('journal preserves immutable requests, results and legal publication recovery transitions', async () => {
  const delegate = new MemoryJournal('a'.repeat(64));
  const journal = await CapacityToolJournal.create(delegate);
  const intent = executing();
  await journal.save(intent);
  const ready: ToolReceipt = {
    ...intent,
    phase: 'ready',
    result: { state: 'UNKNOWN', text: 'Interrupted after capture intent.' },
  };
  await journal.save(ready);
  await assert.rejects(
    journal.save({ ...ready, result: { state: 'COMPLETED', text: 'changed' } }),
    /immutable/,
  );
  await assert.rejects(
    journal.save({
      ...ready,
      request: intent.request.replace('"owner"', '"other"'),
    }),
    /immutable/,
  );
  const publishing: ToolReceipt = {
    ...ready,
    phase: 'publishing',
    occurredAt: '2026-11-16T00:00:00Z',
  };
  await journal.save(publishing);
  await journal.save(ready); // SDK proven NOT_SUBMITTED rollback retains the exact result.
  await journal.save(publishing);
  await journal.save({ ...publishing, phase: 'done' });
  await assert.rejects(journal.save(ready), /transition/);
});

await test('uncertain delegate save fences wrapper until reopened from original evidence', async () => {
  const delegate = new MemoryJournal('a'.repeat(64));
  const journal = await CapacityToolJournal.create(delegate);
  delegate.save = async () => {
    throw new Error('uncertain rename');
  };
  await assert.rejects(journal.save(executing()), /uncertain rename/);
  await assert.rejects(journal.all(), /uncertain/);
  await assert.rejects(journal.save(executing()), /unavailable/);
});

await test('reserved capacity covers READY and publication checkpoints at the admission ceiling', async () => {
  const delegate = new MemoryJournal('a'.repeat(64)),
    intent = executing();
  const sizing = await CapacityToolJournal.create(
    new MemoryJournal(delegate.identity),
  );
  await sizing.save(intent);
  const sized = sizing.status();
  assert.equal(sized.state, 'AVAILABLE');
  if (sized.state !== 'AVAILABLE') assert.fail('Missing settled capacity');
  assert.ok(sized.reservedBytes > 262_144);
  const ceiling = sized.encodedBytes + sized.reservedBytes;
  const journal = await CapacityToolJournal.create(delegate, ceiling);
  await journal.save(intent);
  await assert.rejects(journal.save(executing()), /capacity/);
  const ready: ToolReceipt = {
    ...intent,
    phase: 'ready',
    result: { state: 'COMPLETED', text: '"'.repeat(12_000) },
  };
  await journal.save(ready);
  const publishing: ToolReceipt = {
    ...ready,
    phase: 'publishing',
    occurredAt: '2026-11-16T00:00:00Z',
  };
  await journal.save(publishing);
  await journal.save({ ...publishing, phase: 'done' });
  assert.equal((await journal.all())[0]?.phase, 'done');
});

await test('interleaved intents cannot consume another result publication checkpoint', async () => {
  const delegate = new MemoryJournal('a'.repeat(64)),
    first = executing(),
    second = executing();
  const initial = await CapacityToolJournal.create(delegate);
  await initial.save(first);
  const ready: ToolReceipt = {
    ...first,
    phase: 'ready',
    result: { state: 'COMPLETED', text: 'Known original effect.' },
  };
  await initial.save(ready);
  await initial.save(second);
  const occupancy = initial.status();
  if (occupancy.state !== 'AVAILABLE') assert.fail('Missing settled capacity');
  const ceiling = occupancy.encodedBytes + occupancy.reservedBytes;
  const journal = await CapacityToolJournal.create(delegate, ceiling);
  assert.equal(journal.status().state, 'AVAILABLE');
  const publishing: ToolReceipt = {
    ...ready,
    phase: 'publishing',
    occurredAt: '2026-11-16T00:00:00.000Z',
  };
  for (const checkpoint of [publishing, ready, publishing]) {
    await journal.save(checkpoint);
    const status = journal.status();
    if (status.state !== 'AVAILABLE') assert.fail('Missing settled capacity');
    assert.equal(status.freeBytes, 0);
    assert.equal(status.encodedBytes + status.reservedBytes, ceiling);
    assert.deepEqual(status.phases, {
      executing: 1,
      ready: checkpoint.phase === 'ready' ? 1 : 0,
      publishing: checkpoint.phase === 'publishing' ? 1 : 0,
      done: 0,
    });
  }
  await journal.save({ ...publishing, phase: 'done' });
  const finished = journal.status();
  if (finished.state !== 'AVAILABLE') assert.fail('Missing settled capacity');
  assert.ok(finished.freeBytes > 0);
  assert.ok(Object.isFrozen(finished));
  assert.ok(Object.isFrozen(finished.phases));
  await assert.rejects(journal.save(executing()), /capacity/);
  assert.equal(delegate.rows.size, 2);
});

await test('restored unfinished receipts must retain checkpoint space before serving work', async () => {
  const delegate = new MemoryJournal('a'.repeat(64)),
    intent = executing();
  const ready: ToolReceipt = {
    ...intent,
    phase: 'ready',
    result: { state: 'COMPLETED', text: 'original'.repeat(100) },
  };
  await delegate.save(ready);
  const encoded = Buffer.byteLength(
    JSON.stringify({ identity: delegate.identity, receipts: [ready] }) + '\n',
  );
  await assert.rejects(
    CapacityToolJournal.create(delegate, encoded),
    /capacity/,
  );
  assert.deepEqual(await delegate.all(), [decodeToolReceipt(ready)]);
});

await test('capacity diagnostics omit unverified occupancy during an unsettled save', async () => {
  const delegate = new MemoryJournal('a'.repeat(64));
  const journal = await CapacityToolJournal.create(delegate);
  let rejectSave: (reason: Error) => void = () =>
    assert.fail('Save did not start');
  delegate.save = () =>
    new Promise((_resolve, reject) => {
      rejectSave = reject;
    });
  const writing = journal.save(executing());
  assert.deepEqual(journal.status(), {
    state: 'SAVING',
    maximumBytes: 2_000_000,
    maximumReceipts: 1000,
  });
  rejectSave(new Error('Uncertain fsync'));
  await assert.rejects(writing, /Uncertain/);
  assert.deepEqual(journal.status(), {
    state: 'SAVE_UNCERTAIN',
    maximumBytes: 2_000_000,
    maximumReceipts: 1000,
  });
});

await test('encoded timestamp bounds protect the reserved checkpoint slot', async () => {
  const delegate = new MemoryJournal('a'.repeat(64)),
    intent = executing();
  const journal = await CapacityToolJournal.create(delegate);
  await journal.save(intent);
  const ready: ToolReceipt = {
    ...intent,
    phase: 'ready',
    result: { state: 'UNKNOWN', text: 'Retained effect.' },
  };
  await journal.save(ready);
  const oversized: ToolReceipt = {
    ...ready,
    phase: 'publishing',
    occurredAt: '2026-11-16T00:00:00.' + '0'.repeat(100) + 'Z',
  };
  await assert.rejects(journal.save(oversized), /timestamp/);
  assert.deepEqual(await journal.all(), [decodeToolReceipt(ready)]);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { CapacityToolJournal } from './journal.ts';
import { call, MemoryJournal } from './provider.test-support.ts';
import { FakeClock } from './contracts.test-support.ts';
import type { ToolReceipt } from 'plowshare-client-ts';

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
  const ceiling =
    Buffer.byteLength(
      JSON.stringify({ identity: delegate.identity, receipts: [intent] }) +
        '\n',
    ) + 262_144;
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

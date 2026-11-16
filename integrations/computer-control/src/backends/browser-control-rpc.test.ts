import assert from 'node:assert/strict';
import { test } from 'node:test';
import { OperationDeadline } from '../deadline.ts';
import { awaitBrowserWork, browserTimeout } from './browser-control-rpc.ts';
import { FakeClock } from '../contracts.test-support.ts';

await test('vendor timeout always stays positive and refuses monotonic expiry without timer delivery', () => {
  const clock = new FakeClock(),
    deadline = new OperationDeadline(20, clock);
  try {
    clock.monotonic = 19.75;
    assert.equal(browserTimeout(deadline), 1);
    clock.monotonic = 20;
    assert.throws(() => browserTimeout(deadline));
  } finally {
    deadline.dispose();
  }
});

await test('already expired browser work remains observed through late rejection', async () => {
  const deadline = new OperationDeadline(1000);
  deadline.cancel();
  let reject!: (reason: Error) => void;
  const work = new Promise<void>((_resolve, refuse) => {
    reject = refuse;
  });
  const unhandled: unknown[] = [];
  const listener = (error: unknown) => {
    unhandled.push(error);
  };
  process.on('unhandledRejection', listener);
  try {
    await assert.rejects(awaitBrowserWork(work, deadline), {
      physicalSettlement: 'uncertain',
    });
    reject(new Error('Late vendor refusal'));
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(unhandled, []);
  } finally {
    process.off('unhandledRejection', listener);
    deadline.dispose();
  }
});

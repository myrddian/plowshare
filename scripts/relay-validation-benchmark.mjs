// Reproducible SDK CPU workload, independent of a server or database. Run with
// node --experimental-strip-types scripts/relay-validation-benchmark.mjs.
import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import {
  Worker,
  isMainThread,
  parentPort,
  workerData,
} from 'node:worker_threads';
import { decodePayload } from '../sdk/typescript/src/operations/schema.ts';

function option(name, fallback, maximum) {
  const raw = process.argv.find((value) => value.startsWith(`--${name}=`));
  const value =
    raw === undefined ? fallback : Number(raw.slice(name.length + 3));
  assert.ok(Number.isSafeInteger(value) && value > 0 && value <= maximum, name);
  return value;
}

if (isMainThread) {
  const concurrency = option('concurrency', 4, 32);
  const iterations = option('iterations', 3, 100);
  const bytes = option('bytes', 5 * 1024 * 1024, 50 * 1024 * 1024);
  assert.equal(bytes % 4, 0, 'bytes must be divisible by four');
  const workers = Array.from(
    { length: concurrency },
    () =>
      new Worker(new URL(import.meta.url), {
        workerData: { iterations, bytes },
      }),
  );
  let ready = 0;
  try {
    const results = await Promise.all(
      workers.map(
        (worker) =>
          new Promise((resolve, reject) => {
            worker.on('error', reject);
            worker.on('exit', (code) => {
              if (code !== 0) reject(new Error(`Worker exited ${code}`));
            });
            worker.on('message', (message) => {
              if (message === 'ready') {
                if (++ready === concurrency)
                  for (const peer of workers) peer.postMessage('go');
              } else resolve(message);
            });
          }),
      ),
    );
    console.log(
      JSON.stringify({ concurrency, bytes, iterations, results }, null, 2),
    );
  } finally {
    await Promise.all(workers.map((worker) => worker.terminate()));
  }
} else {
  assert.ok(parentPort);
  const port = parentPort;
  const publish = {
    project: 'fixture',
    topic: 'checks.requests',
    requestId: '11111111-1111-1111-1111-111111111111',
    occurredAt: '2026-10-09T00:00:00Z',
  };
  const texts = [
    'x'.repeat(workerData.bytes),
    'é'.repeat(workerData.bytes / 2),
    '😀'.repeat(workerData.bytes / 4),
    '\u0001'.repeat(workerData.bytes),
  ];
  port.postMessage('ready');
  port.once('message', () => {
    const started = performance.now();
    let requests = 0;
    // Deliberately saturate each client's event loop with a fixed burst. The
    // queued callback measures codec-induced delay, not network/broker latency.
    setImmediate(() => {
      port.postMessage({ requests, elapsedMs: performance.now() - started });
      port.close();
    });
    for (let iteration = 0; iteration < workerData.iterations; iteration++) {
      for (const text of texts) {
        assert.equal(
          decodePayload('relay.publish', { ...publish, text }).text,
          text,
        );
        requests++;
      }
    }
  });
}

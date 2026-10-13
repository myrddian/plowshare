import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decodeConfig } from './config.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import { id, secondId } from './provider.test-support.ts';

const base = {
  origin: 'https://example.invalid',
  tokenEnvironment: 'TOKEN',
  journalDirectory: '/tmp/mcp-config-fixture',
  requestTimeoutMs: 7000,
};
const policy = {
  protocol: '2026-07-28',
  allowHttp: false,
  allowPrivateNetwork: false,
};

await test('configured timeout defaults and shared policy merge independent binding overrides', () => {
  const legacy = decodeConfig({
    ...base,
    pollMs: 50,
    workers: [
      {
        ...policy,
        binding: id,
        generation: 1,
        timeoutMs: 2000,
        timeoutSeconds: 30,
      },
    ],
  });
  assert.equal(legacy.requestTimeoutMs, 7000);
  assert.equal(legacy.workers[0]?.pollMs, 50);
  assert.deepEqual(legacy.workers[0]?.options.limits, DEFAULT_LIMITS);
  const config = decodeConfig({
    ...base,
    requestTimeoutMs: 5000,
    clientInfo: { name: 'configured-executor', version: '2' },
    maxRecoveryCatalogues: 512,
    defaults: {
      ...policy,
      timeoutMs: 4000,
      timeoutSeconds: 60,
      pollMs: 200,
      renewalFraction: 0.2,
      limits: { maxPages: 80, maxWireBytes: 2000000, maxContentBlocks: 20 },
    },
    workers: [
      {
        binding: id,
        generation: 1,
        pollMs: 100,
        limits: { maxContentBlocks: 5 },
      },
      {
        binding: secondId,
        generation: 2,
        timeoutMs: 8000,
        allowPrivateNetwork: true,
      },
    ],
  });
  const first = config.workers[0],
    second = config.workers[1];
  assert.ok(first);
  assert.ok(second);
  assert.equal(first.options.limits?.maxPages, 80);
  assert.equal(first.options.limits?.maxContentBlocks, 5);
  assert.equal(second.options.limits?.maxContentBlocks, 20);
  assert.equal(first.options.policy.maxWireBytes, 2000000);
  assert.equal(first.options.policy.timeoutMs, 4000);
  assert.equal(second.options.policy.timeoutMs, 8000);
  assert.equal(first.options.policy.allowPrivateNetwork, false);
  assert.equal(second.options.policy.allowPrivateNetwork, true);
  assert.equal(first.pollMs, 100);
  assert.equal(second.pollMs, 200);
  assert.equal(first.renewalFraction, 0.2);
  assert.equal(config.maxRecoveryCatalogues, 512);
  assert.equal(config.requestTimeoutMs, 5000);
  assert.equal(config.clientInfo.name, 'configured-executor');
  assert.ok(Object.isFrozen(first.options));
  assert.ok(Object.isFrozen(first.options.limits));
});

await test('rejects invalid budgets, unknown settings and hidden policy defaults before startup', () => {
  const worker = {
    ...policy,
    binding: id,
    generation: 1,
    timeoutMs: 4000,
    timeoutSeconds: 60,
  };
  for (const limits of [
    { maxTools: 128 },
    { maxPages: 0 },
    { maxPages: 1.5 },
    { maxWireBytes: 16777217 },
    { maxCatalogueBytes: 60001 },
    { maxResultBytes: 16385 },
    { maxContentBlocks: 0 },
    { maxPages: null },
    { typo: 1 },
  ])
    assert.throws(() =>
      decodeConfig({ ...base, workers: [{ ...worker, limits }] }),
    );
  for (const extra of [
    { renewalFraction: 0.9 },
    { renewalFraction: NaN },
    { timeoutMs: 0 },
    { timeoutSeconds: 301 },
    { pollMs: null },
    { retries: 3 },
  ])
    assert.throws(() =>
      decodeConfig({ ...base, workers: [{ ...worker, ...extra }] }),
    );
  for (const extra of [
    { requestTimeoutMs: 0 },
    { maxWorkers: 0 },
    { maxRecoveryCatalogues: 4097 },
    { defaults: { timeoutMs: 0 } },
    { defaults: { limits: { maxTools: 128 } } },
    { clientInfo: { name: 'client', version: '1', extra: true } },
  ])
    assert.throws(() =>
      decodeConfig({
        ...base,
        ...extra,
        workers: [{ ...worker, timeoutMs: 1000 }],
      }),
    );
  assert.throws(() =>
    decodeConfig({
      ...base,
      maxWorkers: 1,
      workers: [worker, { ...worker, binding: secondId }],
    }),
  );
  assert.throws(() =>
    decodeConfig({ ...base, workers: [{ binding: id, generation: 1 }] }),
  );
});

await test('every timeout is configuration controlled and missing defaults cannot invoke SDK fallbacks', () => {
  const worker = {
    ...policy,
    binding: id,
    generation: 1,
    timeoutMs: 4000,
    timeoutSeconds: 60,
  };
  assert.throws(
    () =>
      decodeConfig({ ...base, requestTimeoutMs: undefined, workers: [worker] }),
    /requestTimeoutMs/,
  );
  assert.throws(
    () =>
      decodeConfig({ ...base, workers: [{ ...worker, timeoutMs: undefined }] }),
    /timeoutMs/,
  );
  assert.throws(
    () =>
      decodeConfig({
        ...base,
        workers: [{ ...worker, timeoutSeconds: undefined }],
      }),
    /timeoutSeconds/,
  );
  const config = decodeConfig({
    ...base,
    defaults: { timeoutMs: 9000, timeoutSeconds: 90 },
    workers: [
      { ...policy, binding: id, generation: 1 },
      {
        ...policy,
        binding: secondId,
        generation: 1,
        timeoutMs: 4000,
        timeoutSeconds: 60,
      },
    ],
  });
  assert.equal(config.workers[0]?.options.policy.timeoutMs, 9000);
  assert.equal(config.workers[0]?.options.timeoutSeconds, 90);
  assert.equal(config.workers[1]?.options.policy.timeoutMs, 4000);
  assert.equal(config.workers[1]?.options.timeoutSeconds, 60);
});

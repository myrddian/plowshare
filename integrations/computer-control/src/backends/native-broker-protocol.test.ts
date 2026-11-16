import assert from 'node:assert/strict';
import test from 'node:test';
import { FakeClock } from '../contracts.test-support.ts';
import { FakeNativeControlBackend } from '../native-provider.test-support.ts';
import {
  decodeNativeBrokerConfiguration,
  decodeNativeBrokerRequest,
  decodeNativeBrokerReply,
  NATIVE_BROKER_VERSION,
} from './native-broker-protocol.ts';
import type { NativeBrokerConfiguration } from './native-broker-protocol.ts';

const requestId = '11111111-1111-1111-1111-111111111111';
const lease = {
  leaseId: 'lease',
  targetId: 'native',
  sessionId: 'session',
  generation: 'generation',
};
const configuration: NativeBrokerConfiguration = {
  ownershipDirectory: '/fixture/native',
  target: { targetId: 'native', label: 'Native', surfaceId: 'display:1' },
  control: {
    enabled: true,
    tools: ['windows', 'focus', 'pointer', 'type', 'key', 'scroll'],
    allowedBundleIds: ['org.example.NativeFixture'],
  },
  maxWindows: 16,
  maxNodes: 200,
  maxVisitedNodes: 2000,
  cleanupBudgetMs: 2000,
};
function envelope(operation: string, result: unknown) {
  return { version: NATIVE_BROKER_VERSION, requestId, operation, result };
}
function wireObservation() {
  const value = new FakeNativeControlBackend(new FakeClock()).observation();
  const { bytes, ...image } = value.image;
  return {
    ...value,
    image: { ...image, bytesBase64: Buffer.from(bytes).toString('base64') },
  };
}

await test('native private outgoing codec validates complete configuration and lease correlation', () => {
  const checked = decodeNativeBrokerRequest({
    operation: 'acquire',
    lease,
    configuration,
  });
  assert.equal(checked.operation, 'acquire');
  assert.ok(Object.isFrozen(checked));
  for (const invalid of [
    { ...configuration, ownershipDirectory: 'relative' },
    { ...configuration, ownershipDirectory: '/' + 'x'.repeat(4096) },
    {
      ...configuration,
      target: { ...configuration.target, surfaceId: 'window:1' },
    },
    {
      ...configuration,
      control: { ...configuration.control, tools: ['navigate'] },
    },
    { ...configuration, maxVisitedNodes: 199 },
    { ...configuration, maxWindows: 33 },
    { ...configuration, cleanupBudgetMs: 0 },
    { ...configuration, credential: 'forbidden' },
  ])
    assert.throws(() => decodeNativeBrokerConfiguration(invalid));
  assert.throws(() =>
    decodeNativeBrokerRequest({
      operation: 'acquire',
      lease: { ...lease, targetId: 'other' },
      configuration,
    }),
  );
  assert.throws(() =>
    decodeNativeBrokerRequest({ operation: 'preflight', owner: 'forged' }),
  );
  assert.throws(() =>
    decodeNativeBrokerRequest({
      operation: 'observe',
      lease,
      request: {
        surfaceId: 'display:1',
        structured: true,
        selector: 'AXButton',
      },
    }),
  );
});

await test('native private observation decodes exact PNG bytes and refuses mixed/base64 variants', () => {
  const wire = wireObservation();
  const decoded = decodeNativeBrokerReply(envelope('observe', wire));
  assert.equal(decoded.operation, 'observe');
  if (decoded.operation !== 'observe') throw new Error('Expected observation');
  assert.deepEqual(
    decoded.result.image.bytes,
    new Uint8Array(Buffer.from(wire.image.bytesBase64, 'base64')),
  );
  for (const bytesBase64 of [
    'A',
    'AAAA=',
    'AA==junk',
    '????',
    'A'.repeat(1398108),
  ])
    assert.throws(() =>
      decodeNativeBrokerReply(
        envelope('observe', { ...wire, image: { ...wire.image, bytesBase64 } }),
      ),
    );
  assert.throws(() =>
    decodeNativeBrokerReply(
      envelope('observe', {
        ...wire,
        image: { ...wire.image, bytes: [137, 80, 78, 71] },
      }),
    ),
  );
  assert.throws(() =>
    decodeNativeBrokerReply(
      envelope('observe', {
        ...wire,
        image: { ...wire.image, mimeType: 'image/jpeg' },
      }),
    ),
  );
});

await test('native private delivery uncertainty cannot carry pixels or claim application success', () => {
  const observation = { state: 'CAPTURED', value: wireObservation() };
  for (const outcome of ['UNCERTAIN', 'NOT_DISPATCHED'])
    assert.throws(() =>
      decodeNativeBrokerReply(
        envelope('perform', {
          delivery: {
            outcome,
            physicalSettlement: 'settled',
            code: 'INPUT_FAILED',
          },
          observation,
        }),
      ),
    );
  const result = decodeNativeBrokerReply(
    envelope('perform', {
      delivery: {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'unicode-events',
      },
      observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
    }),
  );
  assert.equal(result.operation, 'perform');
  assert.throws(() =>
    decodeNativeBrokerReply(
      envelope('perform', {
        delivery: {
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'unicode-events',
          applicationAcknowledged: true,
        },
        observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
      }),
    ),
  );
});

await test('native helper cleanup proof cannot assert parent-observed process exit', () => {
  const reply = envelope('stop', {
    physicalSettlement: 'settled',
    heldInputsReleased: true,
  });
  assert.equal(decodeNativeBrokerReply(reply).operation, 'stop');
  for (const result of [
    { physicalSettlement: 'settled', heldInputsReleased: false },
    {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
    },
    { physicalSettlement: 'settled', heldInputsReleased: 'true' },
  ])
    assert.throws(() => decodeNativeBrokerReply(envelope('stop', result)));
  assert.throws(() =>
    decodeNativeBrokerReply({ ...reply, requestId: 'foreign' }),
  );
  assert.throws(() => decodeNativeBrokerReply({ ...reply, extra: true }));
  assert.throws(() =>
    decodeNativeBrokerReply({ ...reply, version: 'computer-control-native/1' }),
  );
});

await test('native preflight keeps independent permissions and unknown readiness explicit', () => {
  const result = {
    permissions: {
      screenCapture: true,
      accessibility: false,
      eventPosting: true,
      eventListening: false,
    },
    availability: 'unknown',
    secureInput: 'unknown',
    globalFence: 'held',
  };
  assert.deepEqual(
    decodeNativeBrokerReply(envelope('preflight', result)),
    envelope('preflight', result),
  );
  assert.throws(() =>
    decodeNativeBrokerReply(
      envelope('preflight', {
        ...result,
        permissions: { ...result.permissions, eventListening: 'false' },
      }),
    ),
  );
  assert.throws(() =>
    decodeNativeBrokerReply(
      envelope('preflight', { ...result, globalFence: 'stale' }),
    ),
  );
  assert.throws(() =>
    decodeNativeBrokerReply(
      envelope('preflight', { ...result, availability: 'ready' }),
    ),
  );
});

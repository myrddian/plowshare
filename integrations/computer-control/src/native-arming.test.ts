import assert from 'node:assert/strict';
import test from 'node:test';
import {
  decodeNativeArmProof,
  decodeNativeArmRequest,
  decodeNativeArmStatus,
} from './native-arming.ts';

const requestId = '11111111-1111-1111-1111-111111111111';
const challengeId = '22222222-2222-2222-2222-222222222222';
const helperGeneration = '33333333-3333-3333-3333-333333333333';
const armGeneration = '44444444-4444-4444-4444-444444444444';
const proofEpoch = '55555555-5555-5555-5555-555555555555';
function binding() {
  return {
    lease: {
      leaseId: 'native-lease',
      sessionId: 'session',
      generation: 'provider-generation',
      targetId: 'desktop',
    },
    helperGeneration,
  };
}
function proof() {
  return {
    kind: 'local-ui-live-route/1',
    proofEpoch,
    state: 'fresh',
    verifiedAt: '2026-11-17T00:00:01Z',
    displayGeneration: 'display',
    applicationGeneration: 'incarnations',
    policyFingerprint: 'a'.repeat(64),
  };
}
function pending() {
  return {
    state: 'PENDING',
    binding: binding(),
    requestId,
    challengeId,
    requestedAt: '2026-11-17T00:00:00Z',
    expiresAt: '2026-11-17T00:00:30Z',
  };
}
function armed() {
  return {
    state: 'ARMED',
    binding: binding(),
    requestId,
    challengeId,
    armGeneration,
    armedAt: '2026-11-17T00:00:01Z',
    expiresAt: '2026-11-17T00:00:31Z',
    proof: proof(),
  };
}
function revoked() {
  return {
    state: 'REVOKED',
    binding: binding(),
    requestId,
    armGeneration,
    revokedAt: '2026-11-17T00:00:02Z',
    reason: 'HOST_REVOKED',
  };
}

await test('arming requests are bounded host intents with no caller confirmation or ready flag', () => {
  assert.deepEqual(decodeNativeArmRequest({ requestId, durationMs: 30_000 }), {
    requestId,
    durationMs: 30_000,
  });
  for (const durationMs of [0, -1, 30_001, 1.5, NaN, Infinity, true, '1000'])
    assert.throws(() => decodeNativeArmRequest({ requestId, durationMs }));
  for (const extra of [
    { ready: true },
    { confirm: true },
    { nonce: 'answer' },
    { duration: 10 },
    { owner: { account: 'override' } },
  ])
    assert.throws(() =>
      decodeNativeArmRequest({ requestId, durationMs: 1000, ...extra }),
    );
  for (const bad of [
    null,
    [],
    {},
    { requestId },
    { requestId: requestId.toUpperCase().replace('1', 'A'), durationMs: 1000 },
  ])
    assert.throws(() => decodeNativeArmRequest(bad));
});
await test('every arming state strictly projects and freezes its complete host-only value', () => {
  const values = [
    { state: 'UNARMED', binding: binding() },
    pending(),
    armed(),
    revoked(),
    { ...revoked(), requestId: null, armGeneration: null },
  ];
  for (const value of values) {
    const decoded = decodeNativeArmStatus(value);
    assert.deepEqual(decoded, value);
    assert.equal(Object.isFrozen(decoded), true);
    assert.equal(Object.isFrozen(decoded.binding), true);
    assert.equal(Object.isFrozen(decoded.binding.lease), true);
    value.binding.lease.targetId = 'mutated-after-decode';
    assert.equal(decoded.binding.lease.targetId, 'desktop');
    assert.throws(() => decodeNativeArmStatus({ ...value, nonce: 'private' }));
  }
});
await test('state variants reject missing fields, cross-variant fields and fabricated proof', () => {
  for (const value of [pending(), armed(), revoked()]) {
    for (const field of Object.keys(value)) {
      const copy: Record<string, unknown> = { ...value };
      delete copy[field];
      assert.throws(() => decodeNativeArmStatus(copy), `missing ${field}`);
    }
  }
  for (const value of [
    { state: 'READY', binding: binding() },
    { state: 'UNARMED', binding: binding(), proof: proof() },
    { ...pending(), armGeneration },
    { ...armed(), requestedAt: pending().requestedAt },
    { ...revoked(), proof: proof() },
    { ...revoked(), requestId: null },
    { ...revoked(), reason: 'UNLOCKED' },
    { ...armed(), proof: { ...proof(), nonce: 'private' } },
  ])
    assert.throws(() => decodeNativeArmStatus(value));
});
await test('binding validates exact lease and a distinct process generation, with no native handles', () => {
  for (const bad of [
    { ...binding(), helperGeneration: 'provider-generation' },
    { ...binding(), helperGeneration: 'not-a-uuid' },
    {
      ...binding(),
      lease: { ...binding().lease, generation: helperGeneration },
    },
    { ...binding(), lease: { ...binding().lease, pid: 123 } },
    { ...binding(), owner: 'ambient' },
    { ...binding(), lease: { ...binding().lease, targetId: '' } },
    { ...binding(), lease: { ...binding().lease, sessionId: ' foreign' } },
  ])
    assert.throws(() => decodeNativeArmStatus({ ...pending(), binding: bad }));
});
await test('pending and grant intervals are independently capped including nanosecond edges', () => {
  for (const value of [pending(), armed()]) {
    const start =
      value.state === 'PENDING' ? pending().requestedAt : armed().armedAt;
    for (const expiresAt of [
      start,
      '2026-11-17T00:01:00Z',
      '2026-02-30T00:00:00Z',
      'not-a-date',
    ])
      assert.throws(() => decodeNativeArmStatus({ ...value, expiresAt }));
  }
  assert.throws(() =>
    decodeNativeArmStatus({
      ...pending(),
      expiresAt: '2026-11-17T00:00:30.000000001Z',
    }),
  );
  assert.throws(() =>
    decodeNativeArmStatus({
      ...armed(),
      expiresAt: '2026-11-17T00:00:31.000000001Z',
    }),
  );
  const instant = { ...pending(), expiresAt: '2026-11-17T00:00:00.000000001Z' };
  assert.equal(decodeNativeArmStatus(instant).state, 'PENDING');
});
await test('proof observations must lie within the grant and cannot assert another proof mechanism', () => {
  for (const bad of [
    { ...proof(), kind: 'unlocked' },
    { ...proof(), state: 'ready' },
    { ...proof(), policyFingerprint: 'A'.repeat(64) },
    { ...proof(), policyFingerprint: 'a'.repeat(63) },
    { ...proof(), proofEpoch: '' },
    { ...proof(), applicationGeneration: '' },
    { ...proof(), displayGeneration: '\nprivate' },
    { ...proof(), sourcePid: 1 },
    { ...proof(), verifiedAt: '2026-02-30T00:00:00Z' },
  ])
    assert.throws(() => decodeNativeArmProof(bad));
  for (const verifiedAt of [
    '2026-11-17T00:00:00.999999999Z',
    '2026-11-17T00:00:31Z',
  ])
    assert.throws(() =>
      decodeNativeArmStatus({ ...armed(), proof: { ...proof(), verifiedAt } }),
    );
});
await test('idle witness refresh is distinct from rearm and never changes the original grant', () => {
  const original = decodeNativeArmStatus(armed());
  const stale = decodeNativeArmStatus({
    ...armed(),
    proof: { ...proof(), state: 'refresh-required' },
  });
  const refreshed = decodeNativeArmStatus({
    ...armed(),
    proof: {
      ...proof(),
      proofEpoch: '66666666-6666-6666-6666-666666666666',
      verifiedAt: '2026-11-17T00:00:02Z',
    },
  });
  assert.equal(original.state, 'ARMED');
  assert.equal(stale.state, 'ARMED');
  assert.equal(refreshed.state, 'ARMED');
  if (
    original.state !== 'ARMED' ||
    stale.state !== 'ARMED' ||
    refreshed.state !== 'ARMED'
  )
    throw new Error('Invalid fixture');
  assert.equal(original.armGeneration, refreshed.armGeneration);
  assert.equal(original.expiresAt, refreshed.expiresAt);
  assert.notEqual(original.proof.proofEpoch, refreshed.proof.proofEpoch);
  assert.equal(stale.proof.state, 'refresh-required');
});
await test('every irreversible revocation reason is explicitly represented', () => {
  for (const reason of [
    'HOST_REVOKED',
    'LEASE_CLOSED',
    'SESSION_CHANGED',
    'PERMISSION_CHANGED',
    'DISPLAY_CHANGED',
    'APPLICATION_CHANGED',
    'POLICY_CHANGED',
    'INTERFERENCE',
    'SECURE_INPUT',
    'WITNESS_FAILED',
    'EXPIRED',
    'HELPER_STOPPED',
  ])
    assert.equal(
      decodeNativeArmStatus({ ...revoked(), reason }).state,
      'REVOKED',
    );
});

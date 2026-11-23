import assert from 'node:assert/strict';
import test from 'node:test';
import {
  decodeCaptureRequest,
  decodeCapturedImage,
  decodeCloseArguments,
  decodeObserveArguments,
  decodeOpenArguments,
  decodeOwner,
  decodeTargets,
  decodeTargetsArguments,
} from './contracts.ts';
import { FakeClock, image, owner, target } from './contracts.test-support.ts';

await test('tool boundaries reject unknown fields, missing values and coercion before backend work', () => {
  assert.deepEqual(decodeTargetsArguments({}), {});
  assert.deepEqual(decodeOpenArguments({ targetId: target.targetId }), {
    targetId: target.targetId,
  });
  assert.deepEqual(
    decodeCloseArguments({ sessionId: 'session', generation: 'generation' }),
    { sessionId: 'session', generation: 'generation' },
  );
  assert.deepEqual(
    decodeObserveArguments({
      sessionId: 'session',
      generation: 'generation',
      surfaceId: 'viewport',
    }),
    { sessionId: 'session', generation: 'generation', surfaceId: 'viewport' },
  );
  assert.deepEqual(
    decodeCaptureRequest({ targetId: 'target', surfaceId: 'viewport' }),
    { targetId: 'target', surfaceId: 'viewport' },
  );
  for (const invalid of [
    null,
    [],
    { targetId: '' },
    { targetId: 1 },
    { targetId: 'target', host: 'elsewhere' },
    { targetId: ' target' },
    { targetId: 'target\n' },
    { targetId: 'x'.repeat(257) },
  ])
    assert.throws(() => decodeOpenArguments(invalid));
  assert.throws(() => decodeTargetsArguments({ account: 'other' }));
  assert.throws(() =>
    decodeObserveArguments({
      sessionId: 'session',
      generation: 1,
      surfaceId: 'viewport',
    }),
  );
  assert.throws(() =>
    decodeCaptureRequest({
      targetId: 'target',
      surfaceId: 'viewport',
      crop: {},
    }),
  );
  assert.throws(() => decodeOwner({ ...owner, extra: true }));
});

await test('targets are bounded, strictly typed and defensively immutable', () => {
  const surfaces = [{ id: 'viewport', label: 'Viewport' }];
  const decoded = decodeTargets([{ ...target, surfaces }]);
  surfaces[0] = { id: 'other', label: 'Other' };
  assert.equal(decoded[0]?.surfaces[0]?.id, 'viewport');
  assert.ok(Object.isFrozen(decoded));
  for (const invalid of [
    [{ ...target, backend: 'unknown' }],
    [target, target],
    [{ ...target, surfaces: [] }],
    [{ ...target, capabilities: ['capture', 'capture'] }],
    [{ ...target, capabilities: ['shell'] }],
    [{ ...target, surfaces: [{ id: 'v', label: 'View', handle: 1 }] }],
    Array.from({ length: 33 }, () => target),
  ])
    assert.throws(() => decodeTargets(invalid));
});

await test('captured images copy bytes and reject MIME, signature, geometry and resource violations', () => {
  const original = image(new FakeClock());
  const decoded = decodeCapturedImage(original);
  original.bytes[0] = 0;
  assert.equal(decoded.bytes[0], 137);
  for (const invalid of [
    { ...decoded, bytes: new Uint8Array() },
    { ...decoded, bytes: new Uint8Array(1_048_577) },
    { ...decoded, mimeType: 'image/jpeg' },
    { ...decoded, width: 1601 },
    { ...decoded, height: 0 },
    { ...decoded, width: 1.5 },
    { ...decoded, transform: { a: 0, b: 0, c: 0, d: 0, e: 0, f: 0 } },
    { ...decoded, capturedAt: '2026-02-30T00:00:00.000Z' },
    { ...decoded, filename: '/private/file' },
  ])
    assert.throws(() => decodeCapturedImage(invalid));
  assert.equal(
    decodeCapturedImage({
      ...decoded,
      bytes: new Uint8Array([255, 216, 255]),
      mimeType: 'image/jpeg',
    }).mimeType,
    'image/jpeg',
  );
});

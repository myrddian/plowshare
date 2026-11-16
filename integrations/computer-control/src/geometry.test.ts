import assert from 'node:assert/strict';
import test from 'node:test';
import {
  framePoint,
  inverseTransform,
  transformPoint,
  validateTransform,
} from './geometry.ts';

await test('crop, rotation, negative desktop origin and mixed display scales round-trip', () => {
  const transforms = [
    { a: 0.5, b: 0, c: 0, d: 0.5, e: -1920, f: 60 },
    { a: 0, b: 2, c: -2, d: 0, e: 1440, f: -1080 },
    { a: 1.25, b: 0, c: 0, d: 1.5, e: 250, f: 90 },
  ];
  for (const transform of transforms) {
    const point = { x: 123, y: 45 };
    const roundTrip = transformPoint(
      inverseTransform(transform),
      transformPoint(transform, point),
    );
    assert.ok(Math.abs(roundTrip.x - point.x) < 1e-9);
    assert.ok(Math.abs(roundTrip.y - point.y) < 1e-9);
  }
  assert.deepEqual(framePoint(transforms[0] ?? assert.fail(), 300, 200, 0, 0), {
    x: -1920,
    y: 60,
  });
});

await test('nonfinite, singular, unknown and out-of-frame coordinates refuse', () => {
  const transform = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };
  for (const invalid of [
    null,
    { ...transform, a: NaN },
    { ...transform, d: Infinity },
    { ...transform, a: 0 },
    { ...transform, extra: 1 },
    { a: 1 },
  ])
    assert.throws(() => validateTransform(invalid));
  for (const [x, y] of [
    [-1, 0],
    [10, 0],
    [0, 10],
    [0.5, 0],
    [0, NaN],
  ])
    assert.throws(() => framePoint(transform, 10, 10, x ?? NaN, y ?? NaN));
});

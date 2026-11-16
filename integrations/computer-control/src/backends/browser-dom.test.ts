import assert from 'node:assert/strict';
import { test } from 'node:test';
import { decodeBrowserElementCheck } from './browser-dom.ts';

const valid = {
  connected: true,
  frame: false,
  editable: false,
  sensitive: false,
  focused: false,
  hit: true,
  bounds: { x: 1, y: 2, width: 3, height: 4 },
  borderLeft: 0,
  borderTop: 1,
};
await test('element readiness validates every boolean and finite bounded coordinate before input', () => {
  const checked = decodeBrowserElementCheck(valid);
  assert.ok(Object.isFrozen(checked) && Object.isFrozen(checked.bounds));
  for (const delta of [
    { connected: 'true' },
    { frame: 1 },
    { editable: null },
    { sensitive: undefined },
    { focused: 0 },
    { hit: 'yes' },
    { bounds: null },
    { borderLeft: NaN },
    { borderTop: Infinity },
    { borderLeft: -1 },
    { extra: true },
  ])
    assert.throws(() => decodeBrowserElementCheck({ ...valid, ...delta }));
  for (const delta of [
    { x: NaN },
    { y: Infinity },
    { width: -1 },
    { height: 32769 },
    { x: -32769 },
    { extra: true },
  ])
    assert.throws(() =>
      decodeBrowserElementCheck({
        ...valid,
        bounds: { ...valid.bounds, ...delta },
      }),
    );
  assert.deepEqual(
    decodeBrowserElementCheck({
      ...valid,
      connected: false,
      hit: false,
      bounds: null,
    }),
    { ...valid, connected: false, hit: false, bounds: null },
  );
});

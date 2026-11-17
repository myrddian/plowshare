import assert from 'node:assert/strict';
import test from 'node:test';
import {
  NativeControlError,
  decodeNativeAcquireRequest,
  decodeNativeAcquisition,
  decodeNativeActionResult,
  decodeNativeActionTarget,
  decodeNativeCleanupResult,
  decodeNativeControlPolicy,
  decodeNativeDelivery,
  decodeNativeFocusRequest,
  decodeNativeLease,
  decodeNativeObservation,
  decodeNativeObserveRequest,
  decodeNativePerformRequest,
  decodeNativePermissions,
  decodeNativeSnapshot,
  decodeNativeState,
  decodeNativeStateGuard,
  decodeNativeWindows,
  decodePreparedNativeAction,
  requireAllowedNativeBundle,
  requireNativeActionState,
  requireReadyNativeState,
  sameNativeGuard,
} from './native-control.ts';
import type {
  NativeDelivery,
  NativeObservation,
  NativeState,
  NativeStateGuard,
} from './native-control.ts';
import { FakeClock, image, owner } from './contracts.test-support.ts';

const guard: NativeStateGuard = {
  surfaceId: 'display-one',
  applicationId: 'application-one',
  windowId: 'ax-window-one',
  layoutGeneration: 'layout-one',
  focusGeneration: 'focus-one',
  displayGeneration: 'display-generation-one',
  interactionGeneration: 'interaction-one',
};
const lease = {
  leaseId: 'lease-one',
  targetId: 'desktop-one',
  sessionId: 'session-one',
  generation: 'generation-one',
};
const target = {
  kind: 'element' as const,
  snapshotId: 'snapshot-one',
  elementId: 'element-one',
};
const invocationId = '11111111-1111-1111-1111-111111111111';
function state(): NativeState {
  // Test-only affirmative readiness. This is not a native OS proof or a console-active fallback.
  return {
    ...guard,
    availability: 'ready',
    permissions: {
      screenCapture: true,
      accessibility: true,
      eventPosting: true,
      eventListening: true,
    },
    secureInput: 'clear',
    interference: 'clear',
    focusKnown: true,
    focusedElementId: target.elementId,
    display: {
      displayId: 'display-one',
      bounds: { x: -500, y: 0, width: 1000, height: 1000 },
      scaleX: 2,
      scaleY: 2,
      rotation: 0,
    },
    applicationLabel: 'Controlled fixture',
    bundleId: 'com.fixture.allowed',
  };
}
function observation(): NativeObservation {
  return {
    image: image(new FakeClock()),
    state: state(),
    snapshot: {
      snapshotId: target.snapshotId,
      rootElementId: null,
      guard,
      capturedAt: '2026-11-16T00:00:00Z',
      kind: 'ax',
      approximate: true,
      truncated: false,
      nodes: [
        {
          elementId: target.elementId,
          parentElementId: null,
          role: 'AXTextField',
          subrole: null,
          name: 'Controlled field',
          editable: true,
          focused: true,
          enabled: true,
          secure: false,
          bounds: { x: -290, y: 60, width: 40, height: 20 },
        },
      ],
    },
  };
}
function partial(delivered: unknown): unknown {
  return {
    outcome: 'PARTIAL',
    physicalSettlement: 'settled',
    code: 'INPUT_FAILED',
    delivered,
  };
}

await test('native policy is explicit, exact and immutable; browser capabilities are not native authority', () => {
  const input = {
      enabled: true,
      tools: ['windows', 'pointer'],
      allowedBundleIds: ['com.fixture.allowed'],
    },
    decoded = decodeNativeControlPolicy(input);
  input.tools.length = 0;
  input.allowedBundleIds.length = 0;
  assert.deepEqual(decoded.tools, ['windows', 'pointer']);
  assert.deepEqual(decoded.allowedBundleIds, ['com.fixture.allowed']);
  for (const delta of [
    { enabled: false },
    { tools: [] },
    { tools: ['navigate'] },
    { tools: ['pointer', 'pointer'] },
    { allowedBundleIds: [] },
    { allowedBundleIds: ['com.*'] },
    { allowedBundleIds: ['com.fixture.allowed', 'com.fixture.allowed'] },
    { allowedBundleIds: ['/Applications/Fixture.app'] },
    { origin: 'https://fixture.invalid' },
  ])
    assert.throws(() => decodeNativeControlPolicy({ ...decoded, ...delta }));
  assert.equal(
    requireAllowedNativeBundle(decoded, 'com.fixture.allowed'),
    'com.fixture.allowed',
  );
  assert.throws(
    () => requireAllowedNativeBundle(decoded, 'com.fixture.other'),
    (error: unknown) =>
      error instanceof NativeControlError &&
      error.code === 'TARGET_NOT_ALLOWED',
  );
});

await test('four permission flags remain independent and no missing flag is inferred from accessibility', () => {
  const names = [
    'screenCapture',
    'accessibility',
    'eventPosting',
    'eventListening',
  ] as const;
  for (const name of names) {
    const permissions = { ...state().permissions, [name]: false };
    assert.deepEqual(decodeNativePermissions(permissions), permissions);
    assert.throws(() =>
      requireReadyNativeState({
        ...state(),
        availability: 'unknown',
        permissions,
      }),
    );
  }
  assert.throws(() =>
    decodeNativePermissions({ screenCapture: true, accessibility: true }),
  );
  assert.throws(() =>
    decodeNativePermissions({ ...state().permissions, eventPosting: 1 }),
  );
  assert.throws(() =>
    decodeNativePermissions({ ...state().permissions, prompt: true }),
  );
});

await test('acquisition distinguishes no-work refusal and retains uncertain capability without upgrading it', () => {
  const request = {
    owner: { ...owner },
    session: { sessionId: lease.sessionId, generation: lease.generation },
    targetId: lease.targetId,
  };
  const decoded = decodeNativeAcquireRequest(request);
  request.owner.account = 'changed';
  assert.equal(decoded.owner.account, owner.account);
  assert.throws(() =>
    decodeNativeAcquireRequest({
      ...request,
      session: { ...request.session, targetId: lease.targetId },
    }),
  );
  assert.throws(() => decodeNativeAcquireRequest({ ...request, pid: 123 }));
  assert.deepEqual(decodeNativeAcquisition({ outcome: 'ACQUIRED', lease }), {
    outcome: 'ACQUIRED',
    lease,
  });
  assert.deepEqual(
    decodeNativeAcquisition({ outcome: 'NOT_ACQUIRED', code: 'BUSY' }),
    { outcome: 'NOT_ACQUIRED', code: 'BUSY' },
  );
  assert.equal(
    decodeNativeAcquisition({
      outcome: 'UNCERTAIN',
      lease: null,
      code: 'CLEANUP_UNCERTAIN',
    }).outcome,
    'UNCERTAIN',
  );
  assert.equal(
    decodeNativeAcquisition({
      outcome: 'UNCERTAIN',
      lease,
      code: 'CLEANUP_UNCERTAIN',
    }).outcome,
    'UNCERTAIN',
  );
  for (const value of [
    { outcome: 'NOT_ACQUIRED', code: 'BUSY', lease },
    { outcome: 'UNCERTAIN', code: 'CLEANUP_UNCERTAIN' },
    {
      outcome: 'UNCERTAIN',
      lease: { ...lease, generation: '' },
      code: 'CLEANUP_UNCERTAIN',
    },
    { outcome: 'UNCERTAIN', lease, code: 'raw helper stderr' },
    { outcome: 'ACQUIRED', lease, code: 'BUSY' },
    { outcome: 'UNKNOWN', lease },
  ])
    assert.throws(() => decodeNativeAcquisition(value));
  assert.throws(() => decodeNativeLease({ ...lease, helperPid: 123 }));
});

await test('readiness unknown/locked/inactive/secure/interference cannot become ready from stale focus', () => {
  requireReadyNativeState(state());
  assert.ok(sameNativeGuard(guard, decodeNativeStateGuard(guard)));
  for (const availability of [
    'unknown',
    'locked',
    'inactive',
    'unavailable',
  ] as const)
    assert.throws(() => requireReadyNativeState({ ...state(), availability }));
  for (const delta of [
    { focusKnown: false, focusedElementId: null },
    { secureInput: 'unknown' },
    { secureInput: 'active' },
    { interference: 'unknown' },
    { interference: 'detected' },
    {
      applicationId: null,
      windowId: null,
      bundleId: null,
      applicationLabel: null,
      focusedElementId: null,
    },
  ] as const)
    assert.throws(() => requireReadyNativeState({ ...state(), ...delta }));
  assert.throws(() => decodeNativeState({ ...state(), focusKnown: false }));
  assert.throws(() =>
    decodeNativeState({
      ...state(),
      permissions: { ...state().permissions, eventListening: false },
    }),
  );
  assert.throws(() =>
    decodeNativeState({
      ...state(),
      display: { ...state().display, rotation: 45 },
    }),
  );
  assert.throws(() =>
    decodeNativeState({
      ...state(),
      display: { ...state().display, scaleX: 0 },
    }),
  );
  assert.throws(() => decodeNativeState({ ...state(), consoleActive: true }));
  assert.throws(() => decodeNativeStateGuard({ ...guard, pid: 123 }));
});

await test('native observations copy pixels and AX metadata while preserving signed mixed-scale geometry', () => {
  const input = observation(),
    decoded = decodeNativeObservation(input);
  input.image.bytes[0] = 0;
  assert.equal(decoded.image.bytes[0], 137);
  assert.ok(Object.isFrozen(decoded.snapshot?.nodes));
  assert.ok(Object.isFrozen(decoded.state.permissions));
  assert.ok(Object.isFrozen(decoded.state.display.bounds));
  const rotated = {
    ...observation(),
    image: {
      ...image(new FakeClock()),
      transform: { a: 0, b: 1, c: -1, d: 0, e: -100, f: 10 },
    },
    state: { ...state(), display: { ...state().display, rotation: 90 } },
  };
  assert.equal(decodeNativeObservation(rotated).state.display.rotation, 90);
  for (const value of [
    {
      ...observation(),
      image: {
        ...image(new FakeClock()),
        transform: { a: 1, b: 0, c: 0, d: 1, e: 5000, f: 0 },
      },
    },
    {
      ...observation(),
      state: {
        ...state(),
        display: {
          ...state().display,
          bounds: { x: 0, y: 0, width: 100, height: 80 },
        },
      },
    },
    {
      ...observation(),
      image: { ...image(new FakeClock()), layoutGeneration: 'other' },
    },
  ])
    assert.throws(() => decodeNativeObservation(value));
  const readOnly = {
    ...observation(),
    state: {
      ...state(),
      availability: 'unknown',
      permissions: {
        screenCapture: true,
        accessibility: false,
        eventPosting: false,
        eventListening: false,
      },
      focusKnown: false,
      focusedElementId: null,
    },
    snapshot: null,
  };
  assert.equal(
    decodeNativeObservation(readOnly).state.permissions.eventPosting,
    false,
  );
});

await test('AX snapshots are bounded exact-reference projections and never carry values/native handles', () => {
  const source = observation();
  assert.ok(source.snapshot);
  const first = source.snapshot.nodes[0];
  assert.ok(first);
  assert.equal(decodeNativeSnapshot(source.snapshot).kind, 'ax');
  for (const delta of [
    { kind: 'dom-semantic' },
    { approximate: false },
    {
      nodes: Array.from({ length: 201 }, (_, index) => ({
        ...source.snapshot?.nodes[0],
        elementId: 'element-' + index,
      })),
    },
    { nodes: [{ ...first, value: 'secret' }] },
    { nodes: [{ ...first, nativeHandle: 123 }] },
    { nodes: [{ ...first, name: 'x'.repeat(257) }] },
    { rootElementId: 'absent' },
    {
      nodes: [{ ...first, parentElementId: target.elementId }],
    },
  ])
    assert.throws(() => decodeNativeSnapshot({ ...source.snapshot, ...delta }));
  const hidden = { ...source.snapshot, nodes: [], truncated: true };
  assert.equal(
    decodeNativeObservation({ ...source, snapshot: hidden }).snapshot?.nodes
      .length,
    0,
  );
  assert.throws(() =>
    decodeNativeObservation({
      ...source,
      state: { ...source.state, focusedElementId: null },
    }),
  );
  assert.throws(() =>
    decodeNativeObservation({
      ...source,
      snapshot: {
        ...source.snapshot,
        truncated: true,
        nodes: [{ ...first, focused: false }],
      },
    }),
  );
  assert.throws(() =>
    decodeNativeObservation({
      ...source,
      snapshot: {
        ...source.snapshot,
        guard: { ...guard, interactionGeneration: 'other' },
      },
    }),
  );
  const subtree = {
    ...source.snapshot,
    rootElementId: 'subtree',
    nodes: [{ ...first, elementId: 'subtree', focused: false }],
  };
  assert.equal(
    decodeNativeObservation({ ...source, snapshot: subtree }).snapshot
      ?.rootElementId,
    'subtree',
  );
});

await test('frame references require nonnegative integer pixels; prepared native coordinates remain signed', () => {
  assert.deepEqual(
    decodeNativeActionTarget({ kind: 'point', frameId: 'frame', x: 0, y: 1 }),
    { kind: 'point', frameId: 'frame', x: 0, y: 1 },
  );
  for (const value of [
    { kind: 'point', frameId: 'frame', x: -1, y: 0 },
    { kind: 'point', frameId: 'frame', x: 0.5, y: 0 },
    { kind: 'point', frameId: 'frame', x: NaN, y: 0 },
    { ...target, frameId: 'mixed' },
    { ...target, selector: 'raw AX selector' },
  ])
    assert.throws(() => decodeNativeActionTarget(value));
  assert.deepEqual(
    decodePreparedNativeAction({
      kind: 'move',
      target: { kind: 'point', x: -300.5, y: 50.25 },
    }),
    { kind: 'move', target: { kind: 'point', x: -300.5, y: 50.25 } },
  );
  assert.throws(() =>
    decodePreparedNativeAction({
      kind: 'move',
      target: { kind: 'point', x: Infinity, y: 0 },
    }),
  );
  assert.throws(() =>
    decodePreparedNativeAction({
      kind: 'move',
      target: { kind: 'point', x: -1e8, y: 0 },
    }),
  );
});

await test('native pointer variants and drag enforce owned bounded gestures without raw held-input escape', () => {
  for (const kind of ['move', 'click', 'double_click'] as const) {
    const value =
      kind === 'move'
        ? { kind, target }
        : { kind, target, button: 'left', modifiers: [] };
    assert.equal(decodePreparedNativeAction(value).kind, kind);
    assert.throws(() => decodePreparedNativeAction({ ...value, extra: true }));
  }
  const points = [
      { x: -300, y: 0, atMs: 0 },
      { x: -250, y: 20, atMs: 1000 },
    ],
    input = { kind: 'drag', points, button: 'left', modifiers: ['Shift'] };
  const decoded = decodePreparedNativeAction(input);
  const first = points[0];
  assert.ok(first);
  first.x = -400;
  assert.equal(decoded.kind, 'drag');
  if (decoded.kind !== 'drag') assert.fail();
  assert.equal(decoded.points[0]?.x, -300);
  assert.ok(Object.isFrozen(decoded.points));
  for (const value of [
    { ...input, points: points.slice(0, 1) },
    {
      ...input,
      points: Array.from({ length: 129 }, (_, index) => ({
        x: 0,
        y: 0,
        atMs: index,
      })),
    },
    {
      ...input,
      points: [
        { x: 0, y: 0, atMs: 0 },
        { x: 0, y: 0, atMs: 1001 },
      ],
    },
    {
      ...input,
      points: [
        { x: 0, y: 0, atMs: 0 },
        { x: 0, y: 0, atMs: 0 },
      ],
    },
    { ...input, button: 'back' },
    { ...input, modifiers: ['Shift', 'Shift'] },
    { kind: 'key_down', key: 'a' },
  ])
    assert.throws(() => decodePreparedNativeAction(value));
});

await test('native text preserves exact Unicode/whitespace and never accepts clipboard or insecure focus claims', () => {
  const text = '  café\n🙂  ',
    decoded = decodePreparedNativeAction({
      kind: 'type',
      target,
      mode: 'insert',
      text,
    });
  assert.equal(decoded.kind, 'type');
  if (decoded.kind !== 'type') assert.fail();
  assert.equal(decoded.text, text);
  assert.equal(
    decodePreparedNativeAction({
      kind: 'type',
      target,
      mode: 'replace',
      text: '',
    }).kind,
    'type',
  );
  assert.equal(
    decodePreparedNativeAction({
      kind: 'type',
      target,
      mode: 'insert',
      text: '🙂'.repeat(2048),
    }).kind,
    'type',
  );
  for (const value of [
    { kind: 'type', target, mode: 'insert', text: '' },
    { kind: 'type', target, mode: 'insert', text: 'x'.repeat(4097) },
    { kind: 'type', target, mode: 'insert', text: '\ud800' },
    { kind: 'type', target, mode: 'insert', text: '\udc00' },
    { kind: 'type', target, mode: 'clipboard', text: 'value' },
    {
      kind: 'type',
      target: { kind: 'point', x: 0, y: 0 },
      mode: 'replace',
      text: 'value',
    },
  ])
    assert.throws(() => decodePreparedNativeAction(value));
  requireNativeActionState(state(), guard, decoded);
  assert.throws(() =>
    requireNativeActionState(
      { ...state(), focusedElementId: 'other' },
      guard,
      decoded,
    ),
  );
});

await test('native safe chords are explicit and no-element focus is proven rather than wildcard', () => {
  for (const [key, modifiers] of [
    ['Enter', []],
    ['Tab', ['Shift']],
    ['a', ['Meta']],
    ['z', ['Meta', 'Shift']],
  ])
    assert.equal(
      decodePreparedNativeAction({
        kind: 'key',
        expectedFocus: null,
        key,
        modifiers,
      }).kind,
      'key',
    );
  for (const [key, modifiers] of [
    ['v', ['Meta']],
    ['c', ['Meta']],
    ['x', ['Meta']],
    ['o', ['Meta']],
    ['p', ['Meta']],
    ['s', ['Meta']],
    ['a', ['Control']],
    ['y', ['Control']],
    ['ArrowLeft', ['Alt']],
    ['Delete', ['Shift']],
    ['Tab', ['Meta']],
  ])
    assert.throws(() =>
      decodePreparedNativeAction({
        kind: 'key',
        expectedFocus: null,
        key,
        modifiers,
      }),
    );
  const action = decodePreparedNativeAction({
    kind: 'key',
    expectedFocus: null,
    key: 'Enter',
    modifiers: [],
  });
  assert.throws(() => requireNativeActionState(state(), guard, action));
  requireNativeActionState(
    { ...state(), focusedElementId: null },
    guard,
    action,
  );
  for (const key of [
    'applicationId',
    'windowId',
    'layoutGeneration',
    'focusGeneration',
    'displayGeneration',
    'interactionGeneration',
  ] as const)
    assert.throws(() =>
      requireNativeActionState(state(), { ...guard, [key]: 'stale' }, action),
    );
});

await test('native scroll reports exact integer event units without silent rounding or CSS conversion', () => {
  for (const units of ['pixels', 'lines'] as const) {
    const action = decodePreparedNativeAction({
      kind: 'scroll',
      target,
      dx: -1,
      dy: 1,
      units,
    });
    assert.equal(action.kind, 'scroll');
    assert.equal(
      decodeNativeDelivery({
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'cg-scroll',
        scroll: { dx: -1, dy: 1, units },
      }).outcome,
      'DISPATCHED',
    );
  }
  for (const value of [
    { kind: 'scroll', target, dx: 0.5, dy: 1, units: 'pixels' },
    { kind: 'scroll', target, dx: 0, dy: 0, units: 'pixels' },
    { kind: 'scroll', target, dx: 129, dy: 1, units: 'lines' },
    { kind: 'scroll', target, dx: 32769, dy: 1, units: 'pixels' },
    { kind: 'scroll', target, dx: 1, dy: 1, units: 'css-pixels' },
  ])
    assert.throws(() => decodePreparedNativeAction(value));
  assert.throws(() =>
    decodeNativeDelivery({
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism: 'cg-scroll',
    }),
  );
});

await test('typed delivery preserves known dispatch/partial extent separately from failed post observation', () => {
  for (const mechanism of [
    'cg-event',
    'ax-press',
    'ax-selected-text',
    'ax-set-value',
    'unicode-events',
    'focus',
  ] as const) {
    const delivery: NativeDelivery = {
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism,
    };
    const result = decodeNativeActionResult({
      delivery,
      observation: { state: 'FAILED', code: 'DEADLINE_EXPIRED' },
    });
    assert.equal(result.delivery.outcome, 'DISPATCHED');
  }
  for (const delivered of [
    { kind: 'pointer', moves: 0, clicks: 1 },
    { kind: 'drag', points: 1, buttonReleased: true },
    { kind: 'type', dispatchedUtf16Units: 2, cleared: false },
    { kind: 'key', keyDowns: 2, keyUps: 2 },
    { kind: 'scroll', dx: 1, dy: 0, units: 'lines' },
  ])
    assert.equal(decodeNativeDelivery(partial(delivered)).outcome, 'PARTIAL');
  for (const delivered of [
    { kind: 'pointer', moves: 0, clicks: 0 },
    { kind: 'drag', points: 1, buttonReleased: false },
    { kind: 'type', dispatchedUtf16Units: 0, cleared: false },
    { kind: 'key', keyDowns: 2, keyUps: 1 },
    { kind: 'scroll', dx: 0, dy: 0, units: 'pixels' },
  ])
    assert.throws(() => decodeNativeDelivery(partial(delivered)));
  for (const delivery of [
    {
      outcome: 'NOT_DISPATCHED',
      physicalSettlement: 'settled',
      code: 'STALE_OBSERVATION',
    },
    {
      outcome: 'UNCERTAIN',
      physicalSettlement: 'uncertain',
      code: 'INPUT_FAILED',
    },
    {
      outcome: 'UNCERTAIN',
      physicalSettlement: 'settled',
      code: 'INPUT_FAILED',
    },
  ]) {
    assert.equal(
      decodeNativeActionResult({
        delivery,
        observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
      }).observation.state,
      'FAILED',
    );
    assert.throws(() =>
      decodeNativeActionResult({
        delivery,
        observation: { state: 'CAPTURED', value: observation() },
      }),
    );
  }
  assert.throws(() =>
    decodeNativeDelivery({
      outcome: 'DISPATCHED',
      physicalSettlement: 'uncertain',
      mechanism: 'cg-event',
    }),
  );
  assert.throws(() =>
    decodeNativeDelivery({
      outcome: 'NOT_DISPATCHED',
      physicalSettlement: 'settled',
      code: 'raw stderr',
    }),
  );
  assert.throws(() =>
    decodeNativeActionResult({
      delivery: {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'cg-event',
      },
      observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
      dialogDecision: { decision: 'accept' },
    }),
  );
});

await test('settled cleanup requires explicit owned-input release AND actual helper exit proof', () => {
  assert.deepEqual(
    decodeNativeCleanupResult({
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
    }),
    {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
    },
  );
  for (const value of [
    {
      physicalSettlement: 'settled',
      heldInputsReleased: false,
      helperStopped: true,
    },
    {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: false,
    },
    { physicalSettlement: 'settled', heldInputsReleased: true },
    {
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
      killed: true,
    },
  ])
    assert.throws(() => decodeNativeCleanupResult(value));
  assert.equal(
    decodeNativeCleanupResult({
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
      helperStopped: true,
    }).physicalSettlement,
    'uncertain',
  );
});

await test('private request/window boundaries reject host overrides, malformed references and oversized lists', () => {
  assert.equal(
    decodeNativePerformRequest({
      invocationId,
      expected: guard,
      action: { kind: 'move', target: { kind: 'point', x: -1, y: 1 } },
      observationReserveMs: 500,
    }).action.kind,
    'move',
  );
  for (const delta of [
    { invocationId: 'not-uuid' },
    { observationReserveMs: Infinity },
    { dialogDecision: { decision: 'accept' } },
    { owner: 'other' },
    { expected: { ...guard, pid: 123 } },
  ])
    assert.throws(() =>
      decodeNativePerformRequest({
        invocationId,
        expected: guard,
        action: { kind: 'move', target: { kind: 'point', x: 0, y: 0 } },
        observationReserveMs: 500,
        ...delta,
      }),
    );
  assert.throws(() =>
    decodeNativeFocusRequest({
      invocationId,
      windowsId: 'windows',
      surfaceId: 'window',
      observationReserveMs: 0,
    }),
  );
  assert.deepEqual(
    decodeNativeObserveRequest({
      surfaceId: 'display',
      structured: true,
      root: target,
    }),
    { surfaceId: 'display', structured: true, root: target },
  );
  assert.throws(() =>
    decodeNativeObserveRequest({
      surfaceId: 'display',
      structured: false,
      root: target,
    }),
  );
  const window = {
    surfaceId: 'focus-window',
    applicationId: 'application-one',
    windowId: 'ax-window-one',
    captureSurfaceId: 'display-one',
    applicationLabel: 'Fixture',
    bundleId: 'com.fixture.allowed',
    title: 'Fixture',
    bounds: { x: -300, y: 0, width: 100, height: 100 },
    focused: true,
    state: 'available',
  };
  const windows = {
    windowsId: 'windows',
    capturedAt: '2026-11-16T00:00:00Z',
    windows: [window],
    truncated: false,
  };
  assert.equal(
    decodeNativeWindows(windows).windows[0]?.captureSurfaceId,
    'display-one',
  );
  assert.throws(() =>
    decodeNativeWindows({ ...windows, windows: [window, window] }),
  );
  assert.throws(() =>
    decodeNativeWindows({
      ...windows,
      windows: [{ ...window, cgWindowId: 123 }],
    }),
  );
  assert.throws(() =>
    decodeNativeWindows({
      ...windows,
      windows: Array.from({ length: 33 }, (_, index) => ({
        ...window,
        surfaceId: 'surface-' + index,
        windowId: 'window-' + index,
        focused: false,
      })),
    }),
  );
});

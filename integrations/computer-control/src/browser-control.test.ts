import assert from 'node:assert/strict';
import test from 'node:test';
import {
  BrowserControlError,
  browserHttpUrl,
  browserScrollConversion,
  decodeAnticipatedDialog,
  decodeBrowserAcquireRequest,
  decodeBrowserActionResult,
  decodeBrowserActionTarget,
  decodeBrowserCleanupResult,
  decodeBrowserControlPolicy,
  decodeBrowserDelivery,
  decodeBrowserFocusRequest,
  decodeBrowserLease,
  decodeBrowserNavigateRequest,
  decodeBrowserObservation,
  decodeBrowserObserveRequest,
  decodeBrowserPerformRequest,
  decodeBrowserScrollConversion,
  decodeBrowserSnapshot,
  decodeBrowserState,
  decodeBrowserWindows,
  decodePreparedBrowserAction,
  permittedBrowserUrl,
  requireBrowserActionState,
  requireBrowserNavigationState,
  requireReadyBrowserState,
} from './browser-control.ts';
import type {
  BrowserObservation,
  BrowserState,
  BrowserStateGuard,
} from './browser-control.ts';
import { FakeClock, image, owner } from './contracts.test-support.ts';

const guard: BrowserStateGuard = {
  surfaceId: 'tab-1',
  documentId: 'document-1',
  layoutGeneration: 'layout-one',
  focusGeneration: 'focus-1',
};
const element = {
  kind: 'element' as const,
  snapshotId: 'snapshot-1',
  elementId: 'element-1',
};
function state(): BrowserState {
  return {
    ...guard,
    readiness: 'ready',
    guardInstalled: true,
    url: 'https://fixture.invalid/',
    origin: 'https://fixture.invalid',
    requestedUrl: null,
    focusedElementId: 'element-1',
  };
}
function observation(): BrowserObservation {
  return {
    image: image(new FakeClock()),
    state: state(),
    snapshot: {
      snapshotId: element.snapshotId,
      rootElementId: null,
      guard,
      kind: 'dom-semantic',
      approximate: true,
      capturedAt: '2026-11-16T00:00:00Z',
      truncated: false,
      nodes: [
        {
          elementId: element.elementId,
          parentElementId: null,
          role: 'textbox',
          name: 'Controlled field',
          editable: true,
          focused: true,
          disabled: false,
          bounds: { x: 10, y: 10, width: 20, height: 10 },
        },
      ],
    },
  };
}
function policy() {
  return {
    enabled: true,
    tools: ['windows', 'focus', 'navigate', 'pointer', 'type', 'key', 'scroll'],
    allowedOrigins: ['https://fixture.invalid'],
  };
}
const failedObservation = { state: 'FAILED', code: 'OBSERVATION_FAILED' };

await test('control policy requires explicit exact tools/origins and preserves immutable copies', () => {
  const input = policy(),
    decoded = decodeBrowserControlPolicy(input);
  input.tools.length = 0;
  input.allowedOrigins.length = 0;
  assert.equal(decoded.tools.length, 7);
  assert.equal(decoded.allowedOrigins.length, 1);
  assert.ok(Object.isFrozen(decoded.tools));
  assert.ok(Object.isFrozen(decoded.allowedOrigins));
  for (const delta of [
    { enabled: false },
    { tools: [] },
    { tools: ['*'] },
    { tools: ['pointer', 'pointer'] },
    { allowedOrigins: [] },
    { allowedOrigins: ['https://fixture.invalid/path'] },
    { allowedOrigins: ['https://*.invalid'] },
    { allowedOrigins: ['https://user:secret@fixture.invalid'] },
    { allowedOrigins: ['https://fixture.invalid', 'https://fixture.invalid/'] },
    { endpoint: 'https://other.invalid' },
  ]) {
    assert.throws(() => decodeBrowserControlPolicy({ ...policy(), ...delta }));
  }
  assert.equal(
    permittedBrowserUrl(
      decodeBrowserControlPolicy(policy()),
      'https://fixture.invalid/a?q=1',
    ),
    'https://fixture.invalid/a?q=1',
  );
  assert.throws(
    () =>
      permittedBrowserUrl(
        decodeBrowserControlPolicy(policy()),
        'https://other.invalid/',
      ),
    (error: unknown) =>
      error instanceof BrowserControlError && error.code === 'ORIGIN_DENIED',
  );
});

await test('URL boundary rejects controls, credentials, nonHTTP schemes and oversized canonical expansion', () => {
  for (const value of [
    'javascript:alert(1)',
    'file:///private/fixture',
    'data:text/plain,value',
    'https://user:secret@fixture.invalid/',
    'https://fixture.invalid/\npath',
    'https://fixture.invalid/\tpath',
    ' https://fixture.invalid/',
    'https://fixture.invalid/' + 'é'.repeat(1000),
    'https://fixture.invalid/' + 'x'.repeat(4096),
  ]) {
    assert.throws(() => browserHttpUrl(value));
  }
  assert.equal(
    browserHttpUrl('https://fixture.invalid/é'),
    'https://fixture.invalid/%C3%A9',
  );
});

await test('lease and acquire boundary rejects model/native extras and defensively owns identity', () => {
  const input = {
    owner: { ...owner },
    session: { sessionId: 'session-1', generation: 'generation-1' },
    targetId: 'target-1',
  };
  const decoded = decodeBrowserAcquireRequest(input);
  input.owner.account = 'changed';
  input.session.sessionId = 'changed';
  assert.equal(decoded.owner.account, owner.account);
  assert.equal(decoded.session.sessionId, 'session-1');
  assert.throws(() =>
    decodeBrowserAcquireRequest({ ...input, provider: 'override' }),
  );
  assert.throws(() =>
    decodeBrowserAcquireRequest({
      ...input,
      session: { ...input.session, owner: 'override' },
    }),
  );
  const lease = {
    leaseId: 'lease-1',
    targetId: 'target-1',
    sessionId: 'session-1',
    generation: 'generation-1',
  };
  assert.deepEqual(decodeBrowserLease(lease), lease);
  assert.throws(() => decodeBrowserLease({ ...lease, nativeHandle: 123 }));
});

await test('quarantined and closed tabs cannot masquerade as ready image/input surfaces', () => {
  const quarantined = {
    ...state(),
    readiness: 'quarantined',
    url: 'chrome-error://chromewebdata/',
    origin: null,
    requestedUrl: 'https://fixture.invalid/popup',
    focusedElementId: null,
  };
  const decoded = decodeBrowserState(quarantined);
  requireBrowserNavigationState(decoded, guard);
  assert.throws(() => requireReadyBrowserState(decoded));
  assert.throws(() =>
    requireBrowserNavigationState({ ...decoded, guardInstalled: false }, guard),
  );
  assert.throws(() =>
    decodeBrowserObservation({ ...observation(), state: decoded }),
  );
  assert.throws(() =>
    decodeBrowserState({ ...state(), guardInstalled: false }),
  );
  assert.throws(() =>
    decodeBrowserState({ ...state(), origin: 'https://other.invalid' }),
  );
  assert.throws(() =>
    decodeBrowserState({ ...quarantined, focusedElementId: element.elementId }),
  );
  const closed = decodeBrowserState({
    ...quarantined,
    readiness: 'closed',
    guardInstalled: false,
    url: null,
  });
  assert.throws(() => requireBrowserNavigationState(closed, guard));
});

await test('bounded approximate DOM snapshots validate roots/parents, focus coherence and exact guard', () => {
  const valid = observation();
  assert.ok(valid.snapshot);
  const decoded = decodeBrowserObservation(valid);
  valid.image.bytes[0] = 0;
  assert.equal(decoded.image.bytes[0], 137);
  assert.ok(Object.isFrozen(decoded.snapshot?.nodes));
  const base = observation();
  assert.ok(base.snapshot);
  for (const snapshot of [
    { ...base.snapshot, approximate: false },
    { ...base.snapshot, kind: 'accessibility' },
    {
      ...base.snapshot,
      nodes: Array.from({ length: 201 }, (_, index) => ({
        ...base.snapshot?.nodes[0],
        elementId: 'element-' + index,
      })),
    },
    { ...base.snapshot, rootElementId: 'absent' },
    {
      ...base.snapshot,
      nodes: [{ ...base.snapshot.nodes[0], parentElementId: 'absent' }],
    },
    {
      ...base.snapshot,
      nodes: [{ ...base.snapshot.nodes[0], name: 'x'.repeat(257) }],
    },
  ])
    assert.throws(() => decodeBrowserSnapshot(snapshot));
  assert.throws(() =>
    decodeBrowserObservation({
      ...base,
      state: { ...base.state, focusedElementId: null },
    }),
  );
  assert.throws(() =>
    decodeBrowserObservation({
      ...base,
      state: { ...base.state, focusedElementId: 'another-element' },
    }),
  );
  assert.throws(() =>
    decodeBrowserObservation({
      ...base,
      snapshot: {
        ...base.snapshot,
        guard: { ...guard, documentId: 'another-document' },
      },
    }),
  );
  assert.equal(
    decodeBrowserSnapshot({
      ...base.snapshot,
      rootElementId: element.elementId,
    }).rootElementId,
    element.elementId,
  );
  const nodes = [
    { ...base.snapshot.nodes[0], elementId: 'root', focused: false },
    { ...base.snapshot.nodes[0], parentElementId: 'root' },
  ];
  assert.equal(
    decodeBrowserSnapshot({ ...base.snapshot, nodes }).nodes.length,
    2,
  );
  assert.throws(() =>
    decodeBrowserSnapshot({ ...base.snapshot, nodes: [...nodes].reverse() }),
  );
});

await test('subtree observation may exclude outside focus without claiming body focus', () => {
  const source = observation();
  assert.ok(source.snapshot);
  const child = {
    ...source.snapshot.nodes[0],
    elementId: 'subtree-root',
    parentElementId: null,
    focused: false,
  };
  const subtree = {
    ...source.snapshot,
    rootElementId: 'subtree-root',
    nodes: [child],
  };
  assert.equal(
    decodeBrowserObservation({ ...source, snapshot: subtree }).state
      .focusedElementId,
    element.elementId,
  );
  assert.throws(() =>
    decodeBrowserObservation({
      ...source,
      state: { ...source.state, focusedElementId: null },
      snapshot: { ...subtree, nodes: [{ ...child, focused: true }] },
    }),
  );
  assert.throws(() =>
    decodeBrowserObservation({
      ...source,
      snapshot: { ...subtree, nodes: [{ ...child, focused: true }] },
    }),
  );
});

await test('truncated snapshots may omit focus but cannot contradict included focus state', () => {
  const source = observation();
  assert.ok(source.snapshot);
  assert.equal(
    decodeBrowserObservation({
      ...source,
      snapshot: { ...source.snapshot, nodes: [], truncated: true },
    }).state.focusedElementId,
    element.elementId,
  );
  assert.throws(() =>
    decodeBrowserObservation({
      ...source,
      snapshot: {
        ...source.snapshot,
        truncated: true,
        nodes: [{ ...source.snapshot?.nodes[0], focused: false }],
      },
    }),
  );
  assert.throws(() =>
    decodeBrowserObservation({
      ...source,
      snapshot: {
        ...source.snapshot,
        rootElementId: element.elementId,
        nodes: [{ ...source.snapshot?.nodes[0], focused: false }],
      },
    }),
  );
});

await test('point references use integer image pixels while prepared points accept finite CSS fractions', () => {
  assert.deepEqual(
    decodeBrowserActionTarget({
      kind: 'point',
      frameId: 'frame-1',
      x: 3,
      y: 4,
    }),
    { kind: 'point', frameId: 'frame-1', x: 3, y: 4 },
  );
  for (const target of [
    { kind: 'point', frameId: 'frame-1', x: 0.5, y: 0 },
    { kind: 'point', frameId: 'frame-1', x: -1, y: 0 },
    { kind: 'point', frameId: 'frame-1', x: NaN, y: 0 },
    { ...element, selector: '#other' },
    { ...element, x: 0 },
    { kind: 'point', frameId: 'frame-1', x: 0, y: 0, snapshotId: 'mixed' },
  ])
    assert.throws(() => decodeBrowserActionTarget(target));
  assert.deepEqual(
    decodePreparedBrowserAction({
      kind: 'move',
      target: { kind: 'point', x: 0.5, y: 1.25 },
    }),
    { kind: 'move', target: { kind: 'point', x: 0.5, y: 1.25 } },
  );
  assert.throws(() =>
    decodePreparedBrowserAction({
      kind: 'move',
      target: { kind: 'point', x: Infinity, y: 0 },
    }),
  );
});

await test('typing and anticipated prompt preserve Unicode/whitespace with explicit insert/replace semantics', () => {
  const text = '  café\n🙂  ';
  assert.equal(
    decodePreparedBrowserAction({
      kind: 'type',
      target: element,
      mode: 'insert',
      text,
    }).kind,
    'type',
  );
  assert.deepEqual(
    decodeAnticipatedDialog({
      expectedType: 'prompt',
      decision: 'accept',
      promptText: text,
      exactMessage: '',
    }),
    {
      expectedType: 'prompt',
      decision: 'accept',
      promptText: text,
      exactMessage: '',
    },
  );
  const valid = decodePreparedBrowserAction({
    kind: 'type',
    target: element,
    mode: 'replace',
    text: '',
  });
  assert.equal(valid.kind, 'type');
  for (const value of [
    { kind: 'type', target: element, mode: 'insert', text: '' },
    { kind: 'type', target: element, mode: 'insert', text: 'x'.repeat(4097) },
    { kind: 'type', target: element, mode: 'insert', text: '\ud800' },
    { kind: 'type', target: element, mode: 'insert', text: '\udc00' },
    {
      kind: 'type',
      target: { kind: 'point', x: 0, y: 0 },
      mode: 'replace',
      text: 'value',
    },
    { kind: 'type', target: element, mode: 'clipboard', text: 'value' },
  ])
    assert.throws(() => decodePreparedBrowserAction(value));
  assert.equal(
    decodePreparedBrowserAction({
      kind: 'type',
      target: element,
      mode: 'insert',
      text: '🙂'.repeat(2048),
    }).kind,
    'type',
  );
  for (const value of [
    {
      expectedType: 'confirm',
      decision: 'accept',
      promptText: 'hidden injection',
    },
    { expectedType: 'prompt', decision: 'dismiss', promptText: '' },
    { expectedType: 'prompt', decision: 'accept', promptText: '\ud800' },
    {
      expectedType: 'prompt',
      decision: 'accept',
      promptText: 'x'.repeat(4097),
    },
    { expectedType: 'confirm', decision: 'accept', permanent: true },
  ])
    assert.throws(() => decodeAnticipatedDialog(value));
});

await test('safe key chords exclude clipboard/file/print/browserchrome and null focus is never a wildcard', () => {
  for (const [key, modifiers] of [
    ['Enter', []],
    ['Tab', ['Shift']],
    ['a', ['Control']],
    ['z', ['Meta', 'Shift']],
    ['y', ['Control']],
  ]) {
    assert.equal(
      decodePreparedBrowserAction({
        kind: 'key',
        expectedFocus: null,
        key,
        modifiers,
      }).kind,
      'key',
    );
  }
  for (const [key, modifiers] of [
    ['v', ['Control']],
    ['c', ['Meta']],
    ['x', ['Control']],
    ['o', ['Control']],
    ['p', ['Meta']],
    ['s', ['Control']],
    ['Tab', ['Control']],
    ['ArrowLeft', ['Alt']],
    ['Delete', ['Shift']],
    ['y', ['Meta']],
    ['Enter', ['Control', 'Control']],
    ['a', []],
  ])
    assert.throws(() =>
      decodePreparedBrowserAction({
        kind: 'key',
        expectedFocus: null,
        key,
        modifiers,
      }),
    );
  const bodyKey = decodePreparedBrowserAction({
    kind: 'key',
    expectedFocus: null,
    key: 'Enter',
    modifiers: [],
  });
  assert.throws(
    () => requireBrowserActionState(state(), guard, bodyKey),
    (error: unknown) =>
      error instanceof BrowserControlError && error.code === 'FOCUS_MISMATCH',
  );
  requireBrowserActionState(
    { ...state(), focusedElementId: null },
    guard,
    bodyKey,
  );
  const type = decodePreparedBrowserAction({
    kind: 'type',
    target: element,
    mode: 'insert',
    text: 'value',
  });
  requireBrowserActionState(state(), guard, type);
  assert.throws(() =>
    requireBrowserActionState(
      { ...state(), focusedElementId: 'other' },
      guard,
      type,
    ),
  );
  assert.throws(() =>
    requireBrowserActionState(state(), { ...guard, documentId: 'stale' }, type),
  );
  assert.throws(() =>
    requireBrowserActionState(
      state(),
      { ...guard, layoutGeneration: 'stale' },
      type,
    ),
  );
  assert.throws(() =>
    requireBrowserActionState(
      state(),
      { ...guard, focusGeneration: 'stale' },
      type,
    ),
  );
});

await test('drag is one bounded gesture with strict monotonic point timing and copied arrays', () => {
  const points = [
      { x: 0, y: 0, atMs: 0 },
      { x: 20.5, y: 30, atMs: 1000 },
    ],
    source = { kind: 'drag', points, button: 'left', modifiers: [] };
  const decoded = decodePreparedBrowserAction(source);
  const firstPoint = points[0];
  assert.ok(firstPoint);
  firstPoint.x = 100;
  assert.equal(decoded.kind, 'drag');
  if (decoded.kind !== 'drag') assert.fail();
  assert.equal(decoded.points[0]?.x, 0);
  assert.ok(Object.isFrozen(decoded.points));
  for (const value of [
    { ...source, points: points.slice(0, 1) },
    {
      ...source,
      points: Array.from({ length: 129 }, (_, index) => ({
        x: 0,
        y: 0,
        atMs: index,
      })),
    },
    {
      ...source,
      points: [
        { x: 0, y: 0, atMs: 0 },
        { x: 1, y: 1, atMs: 1001 },
      ],
    },
    {
      ...source,
      points: [
        { x: 0, y: 0, atMs: 0 },
        { x: 1, y: 1, atMs: 0 },
      ],
    },
    { ...source, button: 'back' },
    { ...source, modifiers: ['Hyper'] },
    { ...source, modifiers: ['Shift', 'Shift'] },
    {
      ...source,
      points: [
        { x: -1, y: 0, atMs: 0 },
        { x: 1, y: 1, atMs: 1 },
      ],
    },
  ])
    assert.throws(() => decodePreparedBrowserAction(value));
});

await test('scroll conversion is bounded before dispatch and exposed for successful/partial wheel delivery', () => {
  const input = decodePreparedBrowserAction({
    kind: 'scroll',
    target: { kind: 'point', x: 1, y: 1 },
    dx: 128,
    dy: -128,
    units: 'lines',
  });
  if (input.kind !== 'scroll') assert.fail();
  const converted = browserScrollConversion(input, 256);
  assert.deepEqual(converted, {
    dx: 32768,
    dy: -32768,
    units: 'css-pixels',
    requestedUnits: 'lines',
    pixelsPerUnit: 256,
  });
  assert.equal(
    decodeBrowserDelivery({
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism: 'wheel',
      scroll: converted,
    }).outcome,
    'DISPATCHED',
  );
  assert.equal(
    decodeBrowserDelivery({
      outcome: 'PARTIAL',
      physicalSettlement: 'settled',
      code: 'INPUT_FAILED',
      delivered: { kind: 'scroll', ...converted },
    }).outcome,
    'PARTIAL',
  );
  assert.throws(() => browserScrollConversion(input, 257));
  assert.throws(() => decodePreparedBrowserAction({ ...input, dx: 129 }));
  assert.throws(() =>
    decodeBrowserDelivery({
      outcome: 'DISPATCHED',
      physicalSettlement: 'settled',
      mechanism: 'wheel',
    }),
  );
  assert.throws(() =>
    decodeBrowserScrollConversion({ ...converted, requestedUnits: 'pixels' }),
  );
  assert.throws(() =>
    decodeBrowserScrollConversion({ ...converted, dx: Infinity }),
  );
});

await test('delivery and observation outcomes cannot conceal uncertainty or unproved partial input', () => {
  const dispatched = {
    outcome: 'DISPATCHED',
    physicalSettlement: 'settled',
    mechanism: 'mouse',
  };
  assert.equal(
    decodeBrowserActionResult({
      delivery: dispatched,
      observation: failedObservation,
      dialog: null,
    }).delivery.outcome,
    'DISPATCHED',
  );
  assert.equal(
    decodeBrowserActionResult({
      delivery: dispatched,
      observation: { state: 'CAPTURED', value: observation() },
      dialog: null,
    }).observation.state,
    'CAPTURED',
  );
  for (const delivery of [
    {
      outcome: 'UNCERTAIN',
      physicalSettlement: 'uncertain',
      code: 'INPUT_FAILED',
    },
    {
      outcome: 'NOT_DISPATCHED',
      physicalSettlement: 'settled',
      code: 'STALE_OBSERVATION',
    },
  ]) {
    assert.throws(() =>
      decodeBrowserActionResult({
        delivery,
        observation: { state: 'CAPTURED', value: observation() },
        dialog: null,
      }),
    );
    assert.equal(
      decodeBrowserActionResult({
        delivery,
        observation: failedObservation,
        dialog: null,
      }).observation.state,
      'FAILED',
    );
  }
  for (const delivery of [
    { ...dispatched, physicalSettlement: 'uncertain' },
    { ...dispatched, code: 'extra' },
    {
      outcome: 'PARTIAL',
      physicalSettlement: 'uncertain',
      code: 'INPUT_FAILED',
      delivered: { kind: 'drag', points: 1, buttonReleased: true },
    },
    {
      outcome: 'PARTIAL',
      physicalSettlement: 'settled',
      code: 'INPUT_FAILED',
      delivered: { kind: 'drag', points: 1, buttonReleased: false },
    },
    {
      outcome: 'PARTIAL',
      physicalSettlement: 'settled',
      code: 'INPUT_FAILED',
      delivered: { kind: 'key', keyDowns: 2, keyUps: 1 },
    },
    {
      outcome: 'UNCERTAIN',
      physicalSettlement: 'settled',
      code: 'browser said secret',
    },
  ])
    assert.throws(() => decodeBrowserDelivery(delivery));
  assert.deepEqual(
    decodeBrowserCleanupResult({
      physicalSettlement: 'uncertain',
      heldInputsReleased: false,
    }),
    { physicalSettlement: 'uncertain', heldInputsReleased: false },
  );
  assert.throws(() =>
    decodeBrowserCleanupResult({
      physicalSettlement: 'settled',
      heldInputsReleased: false,
    }),
  );
});

await test('prepared requests reject extras, bad reserves and incompatible subtree/dialog variants', () => {
  const request = {
    expected: guard,
    action: { kind: 'move', target: { kind: 'point', x: 1, y: 2 } },
    observationReserveMs: 500,
  };
  assert.equal(decodeBrowserPerformRequest(request).action.kind, 'move');
  assert.throws(() =>
    decodeBrowserPerformRequest({ ...request, owner: 'model-selected' }),
  );
  assert.throws(() =>
    decodeBrowserPerformRequest({ ...request, observationReserveMs: Infinity }),
  );
  assert.throws(() =>
    decodeBrowserNavigateRequest({
      expected: guard,
      url: 'file:///private',
      observationReserveMs: 500,
    }),
  );
  assert.throws(() =>
    decodeBrowserFocusRequest({
      windowsId: 'windows-1',
      surfaceId: 'tab-1',
      observationReserveMs: 0,
    }),
  );
  assert.deepEqual(
    decodeBrowserObserveRequest({
      surfaceId: 'tab-1',
      structured: true,
      root: element,
    }),
    { surfaceId: 'tab-1', structured: true, root: element },
  );
  assert.throws(() =>
    decodeBrowserObserveRequest({
      surfaceId: 'tab-1',
      structured: false,
      root: element,
    }),
  );
  assert.throws(() =>
    decodeBrowserObserveRequest({
      surfaceId: 'tab-1',
      structured: true,
      root: { ...element, selector: 'override' },
    }),
  );
});

await test('bounded tab listings preserve explicit quarantine metadata without duplicate surfaces', () => {
  const input = {
    windowsId: 'windows-1',
    capturedAt: '2026-11-16T00:00:00Z',
    tabs: [{ state: state(), title: 'Fixture' }],
    truncated: false,
  };
  assert.equal(decodeBrowserWindows(input).tabs.length, 1);
  assert.throws(() =>
    decodeBrowserWindows({ ...input, tabs: [...input.tabs, ...input.tabs] }),
  );
  assert.throws(() =>
    decodeBrowserWindows({
      ...input,
      tabs: Array.from({ length: 33 }, (_, index) => ({
        state: { ...state(), surfaceId: 'tab-' + index },
        title: 'Fixture',
      })),
    }),
  );
  assert.throws(() =>
    decodeBrowserWindows({
      ...input,
      tabs: [{ state: state(), title: 'x'.repeat(257) }],
    }),
  );
});

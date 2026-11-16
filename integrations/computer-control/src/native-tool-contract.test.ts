import assert from 'node:assert/strict';
import test from 'node:test';
import { canonicalToolSchema } from 'plowshare-client-ts';
import type { SchemaDefinition } from 'plowshare-client-ts';
import { validateSchemaArguments } from 'plowshare-client-ts/operations/tool-schemas';
import { browserToolDeclarations } from './browser-tool-catalogue.ts';
import {
  controlledObserveDeclaration,
  controlledToolDeclarations,
} from './native-tool-catalogue.ts';
import {
  checkedNativeTools,
  decodeNativeFocusArguments,
  decodeNativeKeyArguments,
  decodeNativeObserveArguments,
  decodeNativePointerArguments,
  decodeNativeScrollArguments,
  decodeNativeSessionArguments,
  decodeNativeTypeArguments,
} from './native-tool-arguments.ts';
import type { NativeToolName } from './native-control.ts';
import type { NativeControlBackend } from './native-control.ts';
import { nativeComposition } from './native-composition.ts';
import { computerDeclarations } from './provider.ts';

const session = { sessionId: 'session', generation: 'generation' };
const point = { kind: 'point', frameId: 'frame', x: 1, y: 2 };
const element = {
  kind: 'element',
  snapshotId: 'snapshot',
  elementId: 'element',
};
const budgets = { controlBudgetMs: 2000, observeBudgetMs: 10_000 };
function schema(name: NativeToolName): SchemaDefinition {
  const shape = controlledToolDeclarations('computer_', budgets, [], [name])[0]
    ?.inputSchema;
  assert.ok(shape);
  return shape;
}

await test('native declarations are opt-in and browser-only declarations are unchanged', () => {
  assert.deepEqual(
    controlledToolDeclarations('computer_', budgets, [], []),
    [],
  );
  assert.deepEqual(
    computerDeclarations('computer_', budgets),
    computerDeclarations('computer_', budgets, [], []),
  );
  assert.deepEqual(
    computerDeclarations('computer_', budgets, ['pointer']),
    computerDeclarations('computer_', budgets, ['pointer'], []),
  );
  assert.deepEqual(
    computerDeclarations(
      'computer_',
      budgets,
      ['navigate', 'pointer'],
      ['pointer', 'type'],
    ).map((row) => row.name),
    [
      'computer_targets',
      'computer_open',
      'computer_observe',
      'computer_close',
      'computer_navigate',
      'computer_pointer',
      'computer_type',
    ],
  );
  assert.deepEqual(
    controlledToolDeclarations(
      'computer_',
      budgets,
      ['pointer', 'navigate'],
      [],
    ),
    browserToolDeclarations('computer_', budgets, ['pointer', 'navigate']),
  );
  for (const invalid of [
    null,
    ['navigate'],
    ['pointer', 'pointer'],
    ['shell'],
    [1],
  ])
    assert.throws(() => checkedNativeTools(invalid));
  assert.deepEqual(checkedNativeTools(['type', 'pointer', 'windows']), [
    'pointer',
    'type',
    'windows',
  ]);
});

await test('native input schemas and decoders share strict reference vocabulary and bounds', () => {
  const samples = [
    {
      name: 'windows' as const,
      args: session,
      decode: decodeNativeSessionArguments,
    },
    {
      name: 'focus' as const,
      args: { ...session, windowsId: 'windows', surfaceId: 'surface' },
      decode: decodeNativeFocusArguments,
    },
    {
      name: 'pointer' as const,
      args: { ...session, action: 'click', target: point },
      decode: decodeNativePointerArguments,
    },
    {
      name: 'pointer' as const,
      args: {
        ...session,
        action: 'drag',
        frameId: 'frame',
        points: [
          { x: 1, y: 2, atMs: 0 },
          { x: 3, y: 4, atMs: 1000 },
        ],
      },
      decode: decodeNativePointerArguments,
    },
    {
      name: 'type' as const,
      args: {
        ...session,
        target: element,
        mode: 'insert',
        text: 'Unicode🙂\n',
      },
      decode: decodeNativeTypeArguments,
    },
    {
      name: 'key' as const,
      args: { ...session, frameId: 'frame', expectedFocus: null, key: 'Tab' },
      decode: decodeNativeKeyArguments,
    },
    {
      name: 'scroll' as const,
      args: { ...session, target: point, dx: 0, dy: -128, units: 'lines' },
      decode: decodeNativeScrollArguments,
    },
  ];
  for (const sample of samples) {
    validateSchemaArguments(schema(sample.name), sample.args);
    assert.doesNotThrow(() => sample.decode(sample.args));
    for (const field of [
      'owner',
      'leaseId',
      'expected',
      'nativeHandle',
      'deadline',
      'dialogDecision',
    ]) {
      const bad = {
        ...sample.args,
        [field]:
          field === 'dialogDecision'
            ? { expectedType: 'confirm', decision: 'accept' }
            : 'forged',
      };
      assert.throws(() => validateSchemaArguments(schema(sample.name), bad));
      assert.throws(() => sample.decode(bad));
    }
  }
});

await test('mixed catalogue exposes one name but native boundary still refuses browser dialog policy', () => {
  const declarations = controlledToolDeclarations(
    'computer_',
    budgets,
    ['navigate', 'pointer'],
    ['pointer', 'type'],
  );
  assert.deepEqual(
    declarations.map((row) => row.name),
    ['computer_navigate', 'computer_pointer', 'computer_type'],
  );
  const shared = declarations.find(
    (row) => row.name === 'computer_pointer',
  )?.inputSchema;
  assert.ok(shared);
  const args = {
    ...session,
    action: 'click',
    target: point,
    dialogDecision: { expectedType: 'confirm', decision: 'accept' },
  };
  assert.doesNotThrow(() => validateSchemaArguments(shared, args));
  assert.throws(() => decodeNativePointerArguments(args));
  const original = schema('pointer');
  assert.equal(Object.isFrozen(original), true);
  assert.equal(Reflect.set(original, 'type', 'null'), false);
  assert.equal(
    canonicalToolSchema(schema('pointer')),
    canonicalToolSchema(original),
  );
});

await test('native malformed gestures, text and unsafe key chords fail before dispatch', () => {
  for (const args of [
    {
      ...session,
      action: 'drag',
      frameId: 'frame',
      points: [
        { x: 1, y: 2, atMs: 1 },
        { x: 3, y: 4, atMs: 0 },
      ],
    },
    { ...session, action: 'click', target: { ...point, x: -1 } },
    { ...session, action: 'click', target: { ...point, elementId: 'mixed' } },
    { ...session, action: 'click', target: point, modifiers: ['Meta', 'Meta'] },
  ])
    assert.throws(() => decodeNativePointerArguments(args));
  for (const text of ['x'.repeat(4097), '\ud800', '\0'])
    assert.throws(() =>
      decodeNativeTypeArguments({
        ...session,
        target: element,
        mode: 'replace',
        text,
      }),
    );
  for (const key of ['v', 'c', 'x', 's', 'o', 'p'])
    assert.throws(() =>
      decodeNativeKeyArguments({
        ...session,
        frameId: 'frame',
        expectedFocus: null,
        key,
        modifiers: ['Meta'],
      }),
    );
  assert.throws(() =>
    decodeNativeScrollArguments({
      ...session,
      target: point,
      dx: 0,
      dy: 129,
      units: 'lines',
    }),
  );
});

await test('native integer wheel and macOS editing chords are narrower than browser variants', () => {
  const wheel = { ...session, target: point, dx: 0.5, dy: 1, units: 'pixels' };
  assert.throws(() => validateSchemaArguments(schema('scroll'), wheel));
  assert.throws(() => decodeNativeScrollArguments(wheel));
  for (const modifiers of [['Control'], ['Meta', 'Control']]) {
    const key = {
      ...session,
      frameId: 'frame',
      expectedFocus: element,
      key: 'a',
      modifiers,
    };
    assert.throws(() => validateSchemaArguments(schema('key'), key));
    assert.throws(() => decodeNativeKeyArguments(key));
  }
  for (const [key, modifiers] of [
    ['a', ['Meta']],
    ['z', ['Shift', 'Meta']],
    ['Tab', ['Shift']],
  ] as const) {
    const args = {
      ...session,
      frameId: 'frame',
      expectedFocus: element,
      key,
      modifiers,
    };
    assert.doesNotThrow(() => validateSchemaArguments(schema('key'), args));
    assert.doesNotThrow(() => decodeNativeKeyArguments(args));
  }
});

await test('native structured observation accepts exact subtree references and refuses selectors', () => {
  const shape = controlledObserveDeclaration(
    'computer_',
    10_000,
    true,
  ).inputSchema;
  assert.ok(shape);
  const value = { ...session, surfaceId: 'display', root: element };
  assert.doesNotThrow(() => validateSchemaArguments(shape, value));
  assert.equal(decodeNativeObserveArguments(value).structured, true);
  for (const bad of [
    { ...value, structured: false },
    { ...value, root: point },
    { ...value, selector: 'AXButton' },
  ]) {
    assert.throws(() => validateSchemaArguments(shape, bad));
    assert.throws(() => decodeNativeObserveArguments(bad));
  }
});

await test('native composition rejects foreign, duplicate or mismatched capabilities without calling them', () => {
  let calls = 0;
  const unexpected = async (): Promise<never> => {
    calls++;
    throw new Error('Native capability must not run during composition');
  };
  const backend: NativeControlBackend = {
    desktopIsolation: 'native-broker',
    targets: unexpected,
    capture: unexpected,
    nativeControl: {
      allowedTools: ['pointer'],
      acquire: unexpected,
      release: unexpected,
      windows: unexpected,
      observe: unexpected,
      focus: unexpected,
      perform: unexpected,
    },
  };
  const accepted = nativeComposition([backend], [backend], ['pointer']);
  assert.equal(accepted.controls[0], backend);
  assert.equal(Object.isFrozen(accepted.controls), true);
  assert.throws(() =>
    nativeComposition([backend], [{ ...backend }], ['pointer']),
  );
  assert.throws(() =>
    nativeComposition([backend], [backend, backend], ['pointer']),
  );
  assert.throws(() =>
    nativeComposition([backend], [backend], ['pointer', 'type']),
  );
  assert.throws(() => nativeComposition([backend], [], ['pointer']));
  assert.throws(() => nativeComposition([backend], [backend], []));
  for (const desktopIsolation of ['isolated', 'physical'] as const) {
    const foreignDomain = { ...backend, desktopIsolation };
    assert.throws(() =>
      nativeComposition([foreignDomain], [foreignDomain], ['pointer']),
    );
  }
  assert.throws(() =>
    nativeComposition(
      [backend, { targets: unexpected, capture: unexpected }],
      [backend],
      ['pointer'],
    ),
  );
  assert.throws(() =>
    nativeComposition(
      [
        backend,
        {
          desktopIsolation: 'physical',
          targets: unexpected,
          capture: unexpected,
        },
      ],
      [backend],
      ['pointer'],
    ),
  );
  assert.doesNotThrow(() =>
    nativeComposition(
      [
        backend,
        {
          desktopIsolation: 'isolated',
          targets: unexpected,
          capture: unexpected,
        },
      ],
      [backend],
      ['pointer'],
    ),
  );
  assert.doesNotThrow(() =>
    nativeComposition([{ targets: unexpected, capture: unexpected }], [], []),
  );
  assert.equal(calls, 0);
});

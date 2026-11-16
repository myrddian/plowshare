import assert from 'node:assert/strict';
import test from 'node:test';
import { canonicalToolSchema } from 'plowshare-client-ts';
import { validateSchemaArguments } from 'plowshare-client-ts/operations/tool-schemas';
import type { SchemaDefinition } from 'plowshare-client-ts';
import {
  checkedBrowserTools,
  decodeBrowserFocusArguments,
  decodeBrowserKeyArguments,
  decodeBrowserNavigateArguments,
  decodeBrowserObserveArguments,
  decodeBrowserPointerArguments,
  decodeBrowserScrollArguments,
  decodeBrowserSessionArguments,
  decodeBrowserTypeArguments,
} from './browser-tool-arguments.ts';
import {
  browserObserveDeclaration,
  browserToolDeclarations,
} from './browser-tool-catalogue.ts';
import type { BrowserToolName } from './browser-control.ts';

const session = { sessionId: 'session', generation: 'generation' };
const point = { kind: 'point', frameId: 'frame', x: 10, y: 20 };
const element = {
  kind: 'element',
  snapshotId: 'snapshot',
  elementId: 'element',
};
const budgets = { controlBudgetMs: 2000, observeBudgetMs: 10_000 };
function schema(name: BrowserToolName): SchemaDefinition {
  const value = browserToolDeclarations('computer_', budgets, [name])[0]
    ?.inputSchema;
  assert.ok(value);
  return value;
}

await test('browser catalogue is explicit, canonical and defensively immutable', () => {
  assert.deepEqual(browserToolDeclarations('computer_', budgets, []), []);
  const declarations = browserToolDeclarations('computer_', budgets, [
    'type',
    'windows',
    'pointer',
  ]);
  assert.deepEqual(
    declarations.map((value) => [
      value.name,
      value.timeoutSeconds,
      value.resultProfile,
    ]),
    [
      ['computer_pointer', 10, 'content/1'],
      ['computer_type', 10, 'content/1'],
      ['computer_windows', 2, 'content/1'],
    ],
  );
  const original = schema('pointer'),
    encoded = canonicalToolSchema(original);
  assert.equal(Object.isFrozen(original), true);
  assert.equal(Reflect.set(original, 'type', 'null'), false);
  assert.equal(canonicalToolSchema(schema('pointer')), encoded);
  for (const value of [null, ['pointer', 'pointer'], ['shell'], [1]])
    assert.throws(() => checkedBrowserTools(value));
  assert.throws(() => browserToolDeclarations('Computer', budgets, ['type']));
  assert.throws(() => browserObserveDeclaration('bad', 2000));
});

await test('SDK recursive schemas and typed boundary decoders accept the supported variants', () => {
  const samples = [
    {
      name: 'windows' as const,
      args: session,
      decode: decodeBrowserSessionArguments,
    },
    {
      name: 'focus' as const,
      args: { ...session, windowsId: 'windows', surfaceId: 'popup' },
      decode: decodeBrowserFocusArguments,
    },
    {
      name: 'navigate' as const,
      args: {
        ...session,
        observation: {
          kind: 'window',
          windowsId: 'windows',
          surfaceId: 'popup',
        },
        url: 'https://fixture.invalid/path',
      },
      decode: decodeBrowserNavigateArguments,
    },
    {
      name: 'navigate' as const,
      args: {
        ...session,
        observation: { kind: 'frame', frameId: 'frame' },
        url: 'https://fixture.invalid/',
      },
      decode: decodeBrowserNavigateArguments,
    },
    {
      name: 'pointer' as const,
      args: { ...session, action: 'click', target: element },
      decode: decodeBrowserPointerArguments,
    },
    {
      name: 'pointer' as const,
      args: { ...session, action: 'move', target: point },
      decode: decodeBrowserPointerArguments,
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
      decode: decodeBrowserPointerArguments,
    },
    {
      name: 'type' as const,
      args: {
        ...session,
        target: element,
        mode: 'replace',
        text: 'a"\\\n\t🙂',
      },
      decode: decodeBrowserTypeArguments,
    },
    {
      name: 'key' as const,
      args: { ...session, frameId: 'frame', expectedFocus: null, key: 'Tab' },
      decode: decodeBrowserKeyArguments,
    },
    {
      name: 'scroll' as const,
      args: { ...session, target: point, dx: -12.5, dy: 20, units: 'pixels' },
      decode: decodeBrowserScrollArguments,
    },
  ];
  for (const sample of samples) {
    validateSchemaArguments(schema(sample.name), sample.args);
    assert.doesNotThrow(() => sample.decode(sample.args));
  }
});

await test('mixed references, authority overrides and malformed variants fail at both boundaries', () => {
  const valid = { ...session, action: 'click', target: point };
  for (const args of [
    { ...valid, owner: 'other' },
    { ...valid, leaseId: 'forged' },
    { ...valid, deadline: '2099-01-01T00:00:00Z' },
    { ...valid, expected: { documentId: 'forged' } },
    { ...valid, button: null },
    { ...valid, modifiers: ['Shift', 'Shift'] },
    {
      ...valid,
      target: { ...point, snapshotId: 'snapshot', elementId: 'element' },
    },
    { ...valid, target: { ...point, x: 0.1 } },
    { ...valid, points: [] },
  ]) {
    assert.throws(() => validateSchemaArguments(schema('pointer'), args));
    assert.throws(() => decodeBrowserPointerArguments(args));
  }
});

await test('handler semantic checks supplement schema structure without coercion', () => {
  const drag = {
    ...session,
    action: 'drag',
    frameId: 'frame',
    points: [
      { x: 0, y: 0, atMs: 1 },
      { x: 2, y: 2, atMs: 0 },
    ],
  };
  validateSchemaArguments(schema('pointer'), drag);
  assert.throws(() => decodeBrowserPointerArguments(drag));
  assert.throws(() =>
    decodeBrowserPointerArguments({
      ...session,
      action: 'click',
      target: point,
      button: null,
    }),
  );
  assert.throws(() =>
    decodeBrowserScrollArguments({
      ...session,
      target: point,
      dx: '1',
      dy: 1,
      units: 'lines',
    }),
  );
  assert.throws(() =>
    decodeBrowserNavigateArguments({
      ...session,
      observation: { kind: 'frame', frameId: 'frame' },
      url: 'https://user:password@fixture.invalid/',
    }),
  );
  assert.throws(() =>
    decodeBrowserNavigateArguments({
      ...session,
      observation: { kind: 'frame', frameId: 'frame' },
      url: 'javascript:alert(1)',
    }),
  );
});

await test('text and keyboard contracts preserve Unicode while excluding implicit capabilities', () => {
  const text = 'hello🙂\n"\\';
  assert.equal(
    decodeBrowserTypeArguments({
      ...session,
      target: element,
      mode: 'insert',
      text,
    }).action.text,
    text,
  );
  for (const value of ['x'.repeat(4097), '\0', '\ud800'])
    assert.throws(() =>
      decodeBrowserTypeArguments({
        ...session,
        target: element,
        mode: 'insert',
        text: value,
      }),
    );
  for (const key of ['c', 'v', 'x', 'o', 'p', 's'])
    assert.throws(() =>
      decodeBrowserKeyArguments({
        ...session,
        frameId: 'frame',
        expectedFocus: element,
        key,
        modifiers: ['Control'],
      }),
    );
  assert.equal(
    decodeBrowserKeyArguments({
      ...session,
      frameId: 'frame',
      expectedFocus: element,
      key: 'a',
      modifiers: ['Meta'],
    }).action.key,
    'a',
  );
});

await test('structured subtree observation is explicit and never accepts selectors or disabled structure', () => {
  const declaration = browserObserveDeclaration('computer_', 10_000);
  const observeSchema = declaration.inputSchema;
  assert.ok(observeSchema);
  const value = { ...session, surfaceId: 'viewport', root: element };
  validateSchemaArguments(observeSchema, value);
  assert.equal(decodeBrowserObserveArguments(value).structured, true);
  for (const bad of [
    { ...value, structured: false },
    { ...value, root: { selector: 'input' } },
    { ...value, root: point },
  ]) {
    assert.throws(() => validateSchemaArguments(observeSchema, bad));
    assert.throws(() => decodeBrowserObserveArguments(bad));
  }
});

await test('anticipated dialog policy is confined to the initiating action and exact supported variant', () => {
  const value = {
    ...session,
    action: 'click',
    target: point,
    dialogDecision: {
      expectedType: 'confirm',
      decision: 'accept',
      exactMessage: 'Save?',
    },
  };
  validateSchemaArguments(schema('pointer'), value);
  assert.equal(
    decodeBrowserPointerArguments(value).dialogDecision?.exactMessage,
    'Save?',
  );
  for (const dialogDecision of [
    { expectedType: 'confirm', decision: 'accept', promptText: 'hidden' },
    { expectedType: 'prompt', decision: 'dismiss', promptText: 'hidden' },
    { expectedType: 'all', decision: 'accept' },
    { expectedType: 'confirm', decision: 'accept', permanent: true },
  ]) {
    assert.throws(() =>
      validateSchemaArguments(schema('pointer'), { ...value, dialogDecision }),
    );
    assert.throws(() =>
      decodeBrowserPointerArguments({ ...value, dialogDecision }),
    );
  }
});

await test('wheel schema and decoder share nonzero and unit-specific input bounds', () => {
  const shape = schema('scroll');
  for (const delta of [
    { dx: 0, dy: 128, units: 'lines' },
    { dx: -32768, dy: 1, units: 'pixels' },
  ]) {
    const args = { ...session, target: point, ...delta };
    validateSchemaArguments(shape, args);
    assert.doesNotThrow(() => decodeBrowserScrollArguments(args));
  }
  for (const delta of [
    { dx: 0, dy: 0, units: 'pixels' },
    { dx: 0, dy: 0, units: 'lines' },
    { dx: 129, dy: 1, units: 'lines' },
    { dx: 1, dy: -129, units: 'lines' },
    { dx: 32769, dy: 1, units: 'pixels' },
  ]) {
    const args = { ...session, target: point, ...delta };
    assert.throws(() => validateSchemaArguments(shape, args));
    assert.throws(() => decodeBrowserScrollArguments(args));
  }
});

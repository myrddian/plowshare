import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeContract } from 'plowshare-client-ts/operations/schema';
import type { Schema } from 'plowshare-client-ts/operations/schema';
import type { JsonValue } from 'plowshare-client-ts/operations/administration';
import { decodeDesktopRequest } from './request-boundary.ts';
import {
  DESKTOP_REQUEST_ACTIONS,
  indexRequestActions,
} from './request-index.ts';
import { DESKTOP_SCHEMA } from './request-schemas.ts';

function resolve(schema: Schema): Schema {
  while (schema.$ref !== undefined) {
    const next = DESKTOP_SCHEMA.$defs[schema.$ref.replace('#/$defs/', '')];
    assert.ok(next, 'Every generated reference must resolve');
    schema = next;
  }
  return schema;
}

/** Representative structural input for each generated shape, not a server operation. */
function sample(schema: Schema): JsonValue {
  const shape = resolve(schema);
  if (shape.anyOf !== undefined) {
    const first = shape.anyOf[0];
    assert.ok(first);
    return sample(first);
  }
  if ('const' in shape) {
    assert.notEqual(shape.const, undefined);
    return shape.const!;
  }
  switch (shape.type) {
    case 'object': {
      const result: Record<string, JsonValue> = {};
      for (const key of shape.required ?? []) {
        const field = shape.properties?.[key];
        assert.ok(field, `Required fixture field ${key} needs a schema`);
        result[key] = sample(field);
      }
      return result;
    }
    case 'array':
      return Array.from({ length: shape.minItems ?? 0 }, (_, index) => {
        const item = shape.prefixItems?.[index] ?? shape.items;
        assert.ok(item);
        return sample(item);
      });
    case 'string':
      return 'fixture';
    case 'number':
      return 1;
    case 'boolean':
      return true;
    case 'null':
      return null;
    default:
      return null;
  }
}

function record(
  value: JsonValue,
): value is Readonly<Record<string, JsonValue>> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

const generic = (value: unknown) =>
  decodeContract(
    DESKTOP_SCHEMA.request,
    DESKTOP_SCHEMA.$defs,
    value,
    'desktop request',
  );

function outcome(decode: (value: unknown) => unknown, value: unknown): unknown {
  try {
    return { accepted: true, value: decode(value) };
  } catch (reason) {
    assert.ok(reason instanceof Error);
    return { accepted: false, message: reason.message };
  }
}

await test('every generated request variant is reachable, in original order, through its action index', () => {
  const variants = resolve(DESKTOP_SCHEMA.request).anyOf;
  assert.ok(variants && variants.length > 200);
  const reachable = new Set<Schema>();
  for (const bucket of DESKTOP_REQUEST_ACTIONS.values()) {
    assert.ok(bucket.anyOf);
    assert.deepEqual(
      bucket.anyOf,
      variants.filter((variant) => bucket.anyOf?.includes(variant)),
    );
    for (const variant of bucket.anyOf) reachable.add(variant);
  }
  assert.equal(reachable.size, variants.length);
  for (const variant of variants) assert.ok(reachable.has(variant));
});

await test('bootstrap and scope literal-union actions select one candidate and avoid the full scan', () => {
  for (const action of ['bootstrap', 'scope']) {
    assert.equal(DESKTOP_REQUEST_ACTIONS.get(action)?.anyOf?.length, 1);
    let scans = 0;
    const value = new Proxy(
      { action },
      {
        ownKeys(target) {
          scans++;
          return Reflect.ownKeys(target);
        },
      },
    );
    assert.deepEqual(decodeDesktopRequest(value), { action });
    assert.equal(
      scans,
      2,
      'Only one object shape should inspect its keys and entries',
    );
    scans = 0;
    generic(value);
    assert.ok(scans > 2, 'The original decoder probes other object shapes');
  }
});

await test('all indexed action/variant pairs retain generic acceptance, rejection and copied results', () => {
  for (const [action, bucket] of DESKTOP_REQUEST_ACTIONS) {
    for (const variant of bucket.anyOf ?? []) {
      const value = sample(variant);
      assert.ok(record(value));
      const fixture: Record<string, JsonValue> = { ...value, action };
      const original = structuredClone(fixture);
      assert.deepEqual(
        decodeContract(variant, DESKTOP_SCHEMA.$defs, fixture, 'variant'),
        fixture,
      );
      const decoded = decodeDesktopRequest(fixture);
      assert.deepEqual(decoded, generic(fixture));
      assert.notEqual(decoded, fixture);
      assert.deepEqual(fixture, original);
      if (
        'payload' in decoded &&
        decoded.payload !== null &&
        typeof decoded.payload === 'object'
      )
        assert.notEqual(decoded.payload, fixture['payload']);
      for (const invalid of [
        { ...fixture, unsupported: true },
        { ...fixture, action: 'unknown-action' },
        { ...fixture, action: undefined },
        ...(Object.hasOwn(fixture, 'payload')
          ? [{ ...fixture, payload: undefined }]
          : []),
      ])
        assert.deepEqual(
          outcome(decodeDesktopRequest, invalid),
          outcome(generic, invalid),
        );
    }
  }
});

await test('malformed and unusual in-process actions retain the generic fallback behavior', () => {
  const poisoned: unknown = JSON.parse(
    '{"action":"bootstrap","__proto__":{"polluted":true}}',
  );
  const inherited: unknown = Object.create({ action: 'bootstrap' });
  const inputs: unknown[] = [
    null,
    undefined,
    [],
    1,
    'bootstrap',
    {},
    { action: 1 },
    { action: 'unknown-action' },
    { action: 'bootstrap', constructor: {} },
    poisoned,
    inherited,
    Object.defineProperty({}, 'action', {
      value: 'provider-management-pending',
    }),
    Object.defineProperty({}, 'action', {
      enumerable: true,
      get: () => 'scope',
    }),
    {
      action: 'workspace-layout',
      x: 0,
      y: 0,
      width: NaN,
      height: 10,
      visible: true,
    },
  ];
  for (const value of inputs)
    assert.deepEqual(
      outcome(decodeDesktopRequest, value),
      outcome(generic, value),
    );
});

await test('referenced literal unions index all actions and preserve multi-candidate order', () => {
  const first: Schema = {
    type: 'object',
    properties: { action: { $ref: '#/$defs/actions' } },
  };
  const second: Schema = {
    type: 'object',
    properties: { action: { const: 'scope' } },
  };
  const index = indexRequestActions(
    { $ref: '#/$defs/root' },
    {
      root: { anyOf: [first, second] },
      actions: { anyOf: [{ $ref: '#/$defs/bootstrap' }, { const: 'scope' }] },
      bootstrap: { const: 'bootstrap' },
    },
  );
  assert.deepEqual(index.get('bootstrap')?.anyOf, [first]);
  assert.deepEqual(index.get('scope')?.anyOf, [first, second]);
  assert.equal(index.has('missing'), false);
});

await test('unclassified or cyclic schema graphs leave the full union available', () => {
  const tagged: Schema = {
    type: 'object',
    properties: { action: { const: 'scope' } },
  };
  for (const action of [
    { type: 'string' },
    { anyOf: [{ const: 'scope' }, { type: 'string' }] },
    { $ref: '#/$defs/missing' },
    { $ref: '#/$defs/cycle' },
    { optional: true },
    { forbidden: true, const: 'scope' },
  ] satisfies Schema[]) {
    const schema: Schema = {
      anyOf: [tagged, { type: 'object', properties: { action } }],
    };
    assert.equal(
      indexRequestActions(schema, {
        cycle: { anyOf: [{ $ref: '#/$defs/cycle' }] },
      }).size,
      0,
    );
  }
  assert.equal(
    indexRequestActions(
      { $ref: '#/$defs/root' },
      { root: { $ref: '#/$defs/root' } },
    ).size,
    0,
  );
});

await test('candidate buckets retain nested value, prototype and depth guards', () => {
  const schema: Schema = {
    anyOf: [
      {
        type: 'object',
        properties: { action: { const: 'fixture' }, payload: {} },
        required: ['action', 'payload'],
      },
    ],
  };
  const bucket = indexRequestActions(schema, {}).get('fixture');
  assert.ok(bucket);
  const decode = (shape: Schema, value: unknown) =>
    decodeContract(shape, {}, value, 'fixture request');
  const cyclic: { nested?: unknown } = {};
  cyclic.nested = cyclic;
  let deep: unknown = 'value';
  for (let n = 0; n < 70; n++) deep = { nested: deep };
  const poisoned: unknown = JSON.parse('{"constructor":{"value":true}}');
  for (const payload of [
    cyclic,
    deep,
    poisoned,
    { prototype: true },
    { value: undefined },
    { value: Infinity },
    { value: () => 1 },
  ]) {
    const value = { action: 'fixture', payload };
    assert.throws(() => decode(schema, value), /contract/);
    assert.throws(() => decode(bucket, value), /contract/);
  }
});

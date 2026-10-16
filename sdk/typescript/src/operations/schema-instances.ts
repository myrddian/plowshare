import { utf8Length } from '../binding/files.ts';
import { SCHEMA_FIELDS } from './schema-definition.ts';
import type {
  SchemaDefinition,
  SchemaLiteral,
  SchemaType,
} from './schema-definition.ts';
export type SchemaScalar = string | number | boolean;
export type SchemaInstance =
  SchemaScalar | null | readonly SchemaInstance[] | SchemaObject;
export interface SchemaObject {
  readonly [name: string]: SchemaInstance;
}
const TYPES: readonly string[] = [
  'object',
  'array',
  'string',
  'number',
  'integer',
  'boolean',
  'null',
];
export const SCHEMA_DIALECT = 'https://json-schema.org/draft/2020-12/schema';
class Budget {
  private remaining = 10000;
  step(depth: number): void {
    if (depth > 64 || --this.remaining < 0)
      throw new Error('Schema validation exceeds work bound');
  }
}
function fail(): never {
  throw new Error('Invalid shared tool schema or value');
}
function validPrototype(value: object): boolean {
  const prototype: unknown = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}
function plain(value: unknown): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    validPrototype(value)
  );
}
function field(name: string): void {
  if (!name || name.length > 1024) fail();
  for (const character of name) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 32 || (code >= 127 && code <= 159)) fail();
  }
}
export function portableSchemaNumber(value: number): boolean {
  const [base = '', exponent = '0'] = value.toString().toLowerCase().split('e');
  return (
    Number.isFinite(value) &&
    Math.abs(value) <= Number.MAX_SAFE_INTEGER &&
    (base.split('.')[1] ?? '').replace(/0+$/, '').length - Number(exponent) <=
      18
  );
}
/** Conversion owns raw JSON and freezes the whole tree before a handler can observe it. */
export function decodeSchemaInstance(
  source: unknown,
  depth = 0,
  budget = new Budget(),
): SchemaInstance {
  budget.step(depth);
  if (depth > 32) fail();
  if (source === null || typeof source === 'boolean') return source;
  if (typeof source === 'string') {
    if (source.length > 4096 || source.includes('\0')) fail();
    return source;
  }
  if (typeof source === 'number') {
    if (!portableSchemaNumber(source)) fail();
    return source;
  }
  if (Array.isArray(source)) {
    const items: readonly unknown[] = source;
    if (items.length > 1024) fail();
    return Object.freeze(
      items.map((item) => decodeSchemaInstance(item, depth + 1, budget)),
    );
  }
  if (!plain(source) || Object.keys(source).length > 1024) fail();
  const result: Record<string, SchemaInstance> = {};
  for (const [name, value] of Object.entries(source)) {
    field(name);
    Object.defineProperty(result, name, {
      value: decodeSchemaInstance(value, depth + 1, budget),
      enumerable: true,
    });
  }
  return Object.freeze(result);
}
function literal(source: unknown): SchemaLiteral {
  if (source === null || typeof source === 'boolean') return source;
  if (typeof source === 'number' && portableSchemaNumber(source)) return source;
  if (
    typeof source === 'string' &&
    source.length <= 4096 &&
    !source.includes('\0')
  )
    return source;
  return fail();
}
/** Strict, bounded schema decoding; arbitrary extension keywords are never discarded. */
export function decodeSchemaDefinition(
  source: unknown,
  depth = 0,
  budget = new Budget(),
): SchemaDefinition {
  budget.step(depth);
  if (!plain(source)) fail();
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(source)) {
    if (!Object.hasOwn(SCHEMA_FIELDS, key)) fail();
    const kind = SCHEMA_FIELDS[key as keyof typeof SCHEMA_FIELDS];
    let decoded: unknown;
    if (kind === 'text') {
      if (
        typeof value !== 'string' ||
        value.includes('\0') ||
        value.length > (key === 'description' ? 32768 : 4096)
      )
        fail();
      decoded = value;
    } else if (kind === 'number' || kind === 'integer') {
      if (
        typeof value !== 'number' ||
        !portableSchemaNumber(value) ||
        (kind === 'integer' &&
          (!Number.isInteger(value) || value < 0 || value > 2147483647))
      )
        fail();
      decoded = value;
    } else if (kind === 'boolean') {
      if (typeof value !== 'boolean') fail();
      decoded = value;
    } else if (kind === 'literal') decoded = literal(value);
    else if (
      kind === 'schema' ||
      (kind === 'rule' && typeof value !== 'boolean')
    )
      decoded = decodeSchemaDefinition(value, depth + 1, budget);
    else if (kind === 'rule') decoded = value;
    else if (kind === 'map') {
      if (!plain(value) || Object.keys(value).length > 10000) fail();
      const map: Record<string, SchemaDefinition> = {};
      for (const [name, schema] of Object.entries(value)) {
        field(name);
        Object.defineProperty(map, name, {
          value: decodeSchemaDefinition(schema, depth + 1, budget),
          enumerable: true,
        });
      }
      decoded = Object.freeze(map);
    } else if (kind === 'types' && typeof value === 'string') {
      if (!TYPES.includes(value)) fail();
      decoded = value;
    } else {
      if (!Array.isArray(value) || value.length > 10000) fail();
      const entries: readonly unknown[] = value;
      if (kind === 'schemas') {
        if (['allOf', 'anyOf', 'oneOf'].includes(key) && !entries.length)
          fail();
        decoded = Object.freeze(
          entries.map((entry) =>
            decodeSchemaDefinition(entry, depth + 1, budget),
          ),
        );
      } else if (kind === 'literals') {
        const literals = entries.map(literal);
        if (
          key === 'enum' &&
          (!literals.length || new Set(literals).size !== literals.length)
        )
          fail();
        decoded = Object.freeze(literals);
      } else {
        const strings = entries.map((entry) => {
          if (typeof entry !== 'string') fail();
          if (kind === 'types') {
            if (!TYPES.includes(entry)) fail();
          } else field(entry);
          return entry;
        });
        if (
          new Set(strings).size !== strings.length ||
          (kind === 'types' && !strings.length)
        )
          fail();
        decoded = Object.freeze(strings);
      }
    }
    Object.defineProperty(result, key, { value: decoded, enumerable: true });
  }
  // Every keyword and value above was decoded against the owning vocabulary.
  const schema = Object.freeze(result) as SchemaDefinition;
  for (const [min, max] of [
    [schema.minimum, schema.maximum],
    [schema.minLength, schema.maxLength],
    [schema.minItems, schema.maxItems],
    [schema.minProperties, schema.maxProperties],
  ])
    if (min !== undefined && max !== undefined && min > max) fail();
  if (schema.multipleOf !== undefined && schema.multipleOf <= 0) fail();
  return schema;
}
function reference(root: SchemaDefinition, ref: string): SchemaDefinition {
  if (
    !ref.startsWith('#/$defs/') ||
    ref.slice(8).includes('/') ||
    /~(?![01])/.test(ref)
  )
    fail();
  const key = ref.slice(8).replace(/~1/g, '/').replace(/~0/g, '~');
  if (!root.$defs || !Object.hasOwn(root.$defs, key)) fail();
  return root.$defs[key] ?? fail();
}
export function validateSchemaDefinition(
  source: SchemaDefinition,
): SchemaDefinition {
  const root = decodeSchemaDefinition(source);
  if (
    root.type !== 'object' &&
    !(
      Array.isArray(root.type) &&
      root.type.length === 1 &&
      root.type[0] === 'object'
    )
  )
    fail();
  const budget = new Budget();
  function check(s: SchemaDefinition, depth: number): void {
    budget.step(depth);
    if (
      (s.$schema !== undefined && s.$schema !== SCHEMA_DIALECT) ||
      s.$id !== undefined ||
      s.$anchor !== undefined ||
      s.pattern !== undefined ||
      s.patternProperties !== undefined
    )
      throw new Error('Unsupported executable schema feature');
    if (s.$ref !== undefined) check(reference(root, s.$ref), depth + 1);
    for (const map of [s.$defs, s.properties])
      if (map) for (const child of Object.values(map)) check(child, depth + 1);
    for (const list of [s.prefixItems, s.allOf, s.anyOf, s.oneOf])
      if (list) for (const child of list) check(child, depth + 1);
    for (const child of [s.items, s.contains, s.not, s.if, s.then, s.else])
      if (child) check(child, depth + 1);
    if (typeof s.additionalProperties === 'object')
      check(s.additionalProperties, depth + 1);
  }
  check(root, 0);
  return root;
}
function array(v: SchemaInstance): v is readonly SchemaInstance[] {
  return Array.isArray(v);
}
function object(v: SchemaInstance): v is SchemaObject {
  return v !== null && typeof v === 'object' && !array(v);
}
function type(t: SchemaType, v: SchemaInstance): boolean {
  return t === 'null'
    ? v === null
    : t === 'array'
      ? array(v)
      : t === 'object'
        ? object(v)
        : t === 'integer'
          ? typeof v === 'number' && Number.isInteger(v)
          : typeof v === t;
}
function bound(
  n: number,
  min: number | undefined,
  max: number | undefined,
): boolean {
  return (min === undefined || n >= min) && (max === undefined || n <= max);
}
function decimal(v: number): [bigint, number] {
  const [m = '', e = '0'] = v.toString().toLowerCase().split('e');
  return [
    BigInt(m.replace('.', '')),
    Number(e) - (m.split('.')[1] ?? '').length,
  ];
}
function multiple(n: number, divisor: number): boolean {
  const [a, x] = decimal(n),
    [b, y] = decimal(divisor),
    base = Math.min(x, y);
  return (a * 10n ** BigInt(x - base)) % (b * 10n ** BigInt(y - base)) === 0n;
}
function equal(
  a: SchemaInstance,
  b: SchemaInstance,
  budget: Budget,
  depth: number,
): boolean {
  budget.step(depth);
  if (array(a) && array(b))
    return (
      a.length === b.length &&
      a.every(
        (item, i) => b[i] !== undefined && equal(item, b[i], budget, depth + 1),
      )
    );
  if (object(a) && object(b))
    return (
      Object.keys(a).length === Object.keys(b).length &&
      Object.entries(a).every(
        ([key, value]) =>
          Object.hasOwn(b, key) &&
          b[key] !== undefined &&
          equal(value, b[key], budget, depth + 1),
      )
    );
  return a === b;
}
/** Same executable profile and fail-closed work budget as Java/native SDKs. */
export function validateSchemaArguments(
  source: SchemaDefinition,
  input: SchemaInstance,
): void {
  const root = validateSchemaDefinition(source),
    value = decodeSchemaInstance(input),
    budget = new Budget();
  function match(
    s: SchemaDefinition,
    v: SchemaInstance,
    depth: number,
  ): boolean {
    budget.step(depth);
    if (s.$ref !== undefined && !match(reference(root, s.$ref), v, depth + 1))
      return false;
    if (s.type !== undefined) {
      const types = typeof s.type === 'string' ? [s.type] : s.type;
      if (!types.some((t) => type(t, v))) return false;
    }
    if (
      (s.const !== undefined && s.const !== v) ||
      (s.enum !== undefined && !s.enum.some((item) => item === v))
    )
      return false;
    if (s.allOf && !s.allOf.every((child) => match(child, v, depth + 1)))
      return false;
    if (s.anyOf && !s.anyOf.some((child) => match(child, v, depth + 1)))
      return false;
    if (
      s.oneOf &&
      s.oneOf.filter((child) => match(child, v, depth + 1)).length !== 1
    )
      return false;
    if (s.not && match(s.not, v, depth + 1)) return false;
    if (s.if) {
      const branch = match(s.if, v, depth + 1) ? s.then : s.else;
      if (branch && !match(branch, v, depth + 1)) return false;
    }
    if (
      typeof v === 'string' &&
      !bound(Array.from(v).length, s.minLength, s.maxLength)
    )
      return false;
    if (
      typeof v === 'number' &&
      (!bound(v, s.minimum, s.maximum) ||
        (s.exclusiveMinimum !== undefined && v <= s.exclusiveMinimum) ||
        (s.exclusiveMaximum !== undefined && v >= s.exclusiveMaximum) ||
        (s.multipleOf !== undefined && !multiple(v, s.multipleOf)))
    )
      return false;
    if (object(v)) {
      if (
        !bound(Object.keys(v).length, s.minProperties, s.maxProperties) ||
        s.required?.some((key) => !Object.hasOwn(v, key))
      )
        return false;
      for (const [key, entry] of Object.entries(v)) {
        const property =
          s.properties && Object.hasOwn(s.properties, key)
            ? s.properties[key]
            : undefined;
        if (property) {
          if (!match(property, entry, depth + 1)) return false;
        } else if (s.additionalProperties === false) return false;
        else if (
          typeof s.additionalProperties === 'object' &&
          !match(s.additionalProperties, entry, depth + 1)
        )
          return false;
      }
    }
    if (array(v)) {
      if (!bound(v.length, s.minItems, s.maxItems)) return false;
      let contained = false;
      for (let i = 0; i < v.length; i++) {
        const entry = v[i];
        if (entry === undefined) fail();
        const schema =
          i < (s.prefixItems?.length ?? 0) ? s.prefixItems?.[i] : s.items;
        if (schema && !match(schema, entry, depth + 1)) return false;
        if (s.contains && match(s.contains, entry, depth + 1)) contained = true;
        if (s.uniqueItems)
          for (let j = 0; j < i; j++) {
            const other = v[j];
            if (other === undefined) fail();
            if (equal(entry, other, budget, depth + 1)) return false;
          }
      }
      if (s.contains && !contained) return false;
    }
    return true;
  }
  if (!match(root, value, 0))
    throw new Error('Tool arguments do not satisfy the offered schema');
}
function hex(text: string): string {
  return Array.from(text, (char) => {
    const encoded = encodeURIComponent(char);
    return encoded.startsWith('%')
      ? encoded.replace(/%/g, '').toLowerCase()
      : Array.from(encoded, (c) =>
          c.charCodeAt(0).toString(16).padStart(2, '0'),
        ).join('');
  }).join('');
}
function canonical(value: unknown, legacyOrdering = false): string {
  if (value === null) return 'z;';
  if (typeof value === 'boolean') return value ? 'b1;' : 'b0;';
  if (typeof value === 'string') return `s${hex(value)};`;
  if (typeof value === 'number') {
    const [n, e] = decimal(value);
    let digits = n,
      exponent = e;
    while (digits !== 0n && digits % 10n === 0n) {
      digits /= 10n;
      exponent++;
    }
    return `n${digits}e${digits === 0n ? 0 : exponent};`;
  }
  if (Array.isArray(value)) {
    const items: readonly unknown[] = value;
    return `[${items.map((item) => canonical(item, legacyOrdering)).join('')}]`;
  }
  if (plain(value))
    return `{${Object.entries(value)
      .sort(([a], [b]) => {
        const left = keyOrder(a, legacyOrdering),
          right = keyOrder(b, legacyOrdering);
        return left < right ? -1 : left > right ? 1 : 0;
      })
      .map(
        ([key, v]) =>
          canonical(key, legacyOrdering) + canonical(v, legacyOrdering),
      )
      .join('')}}`;
  return fail();
}
function keyOrder(key: string, legacyOrdering: boolean): string {
  return legacyOrdering ? `s${hex(key)};` : hex(key);
}
/**
 * Validate the exact offered schema, including historical Java hashes. Never emit the legacy
 * identity for new calls or rewrite retained requests; journal replay fences remain authoritative.
 */
export async function matchesSchemaFingerprint(
  schema: SchemaDefinition,
  offered: string | undefined,
  sha256: (text: string) => Promise<string>,
): Promise<boolean> {
  const validated = validateSchemaDefinition(schema);
  return (
    offered === (await sha256(canonical(validated))) ||
    offered === (await sha256(canonical(validated, true)))
  );
}
/** Hash with the configured host's SHA-256; canonical tokens are independent of JSON writers. */
export function canonicalSchemaDefinition(schema: SchemaDefinition): string {
  return canonical(validateSchemaDefinition(schema));
}

/** Reject duplicate decoded keys and lossy decimals before JSON leaves this codec boundary. */
function strictSchemaJson(source: string, maximum: number): unknown {
  if (utf8Length(source) > maximum)
    throw new Error('Schema JSON exceeds byte bounds');
  const parsed: unknown = JSON.parse(source);
  const tokens =
    source.match(
      /"(?:\\.|[^"\\])*"|[{}[\]:,]|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null/g,
    ) ?? [];
  const stack: (Set<string> | null)[] = [];
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (
      token &&
      /^-?\d/.test(token) &&
      normalizedDecimal(token) !== normalizedDecimal(Number(token).toString())
    )
      throw new Error('Schema number cannot round-trip across SDK languages');
    if (token === '{') stack.push(new Set());
    else if (token === '[') stack.push(null);
    else if (token === '}' || token === ']') stack.pop();
    else if (token?.startsWith('"') && tokens[i + 1] === ':') {
      const key: unknown = JSON.parse(token),
        fields = stack.at(-1);
      if (typeof key !== 'string' || fields == null || fields.has(key))
        throw new Error('Duplicate schema field');
      fields.add(key);
    }
    if (stack.length > 64)
      throw new Error('Schema JSON exceeds nesting bounds');
  }
  return parsed;
}
export function parseSchemaInstance(source: string): SchemaInstance {
  return decodeSchemaInstance(strictSchemaJson(source, 16384));
}
export function parseSchemaDefinition(source: string): SchemaDefinition {
  return validateSchemaDefinition(
    decodeSchemaDefinition(strictSchemaJson(source, 65536)),
  );
}
function normalizedDecimal(source: string): string {
  const [mantissa = '', exponent = '0'] = source.toLowerCase().split('e');
  const negative = mantissa.startsWith('-');
  const digits = mantissa
    .replace(/[-.]/g, '')
    .replace(/^0+/, '')
    .replace(/0+$/, '');
  if (!digits) return '0';
  const trailing =
    mantissa.replace(/[-.]/g, '').length -
    mantissa.replace(/[-.]/g, '').replace(/0+$/, '').length;
  const scale =
    Number(exponent) - (mantissa.split('.')[1] ?? '').length + trailing;
  return `${negative ? '-' : ''}${digits}e${scale}`;
}

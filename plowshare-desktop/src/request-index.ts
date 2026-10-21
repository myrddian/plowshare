import type { Schema } from 'plowshare-client-ts/operations/schema';
import { DESKTOP_SCHEMA } from './request-schemas.ts';

type Definitions = Readonly<Record<string, Schema>>;

/** Inspect only trusted schema indirections, without changing decoder recursion rules. */
function resolve(
  schema: Schema | undefined,
  definitions: Definitions,
): Schema | undefined {
  const seen = new Set<Schema>();
  while (schema !== undefined) {
    if (schema.forbidden || schema.optional || seen.has(schema))
      return undefined;
    if (schema.$ref === undefined) return schema;
    seen.add(schema);
    schema = definitions[schema.$ref.replace('#/$defs/', '')];
  }
  return undefined;
}

function actions(
  schema: Schema | undefined,
  definitions: Definitions,
  seen = new Set<Schema>(),
): readonly string[] | undefined {
  const resolved = resolve(schema, definitions);
  if (!resolved || seen.has(resolved)) return undefined;
  if (resolved.anyOf === undefined)
    return typeof resolved.const === 'string' ? [resolved.const] : undefined;
  seen.add(resolved);
  const literals: string[] = [];
  for (const variant of resolved.anyOf) {
    const found = actions(variant, definitions, seen);
    if (found === undefined) return undefined;
    literals.push(...found);
  }
  seen.delete(resolved);
  return literals;
}

/**
 * Narrow the generated request union by its finite action vocabulary. Buckets
 * retain original variants in declaration order and still run the complete
 * validate-and-copy decoder. If any branch cannot be classified, use the
 * original union for every request rather than excluding a possible match.
 */
export function indexRequestActions(
  schema: Schema,
  definitions: Definitions,
): ReadonlyMap<string, Schema> {
  const buckets = new Map<string, Schema[]>();
  const variants = resolve(schema, definitions)?.anyOf;
  if (variants === undefined) return new Map();
  for (const variant of variants) {
    const shape = resolve(variant, definitions);
    if (
      !shape ||
      shape.type !== 'object' ||
      shape.anyOf !== undefined ||
      'const' in shape
    )
      return new Map();
    const literals = actions(shape.properties?.['action'], definitions);
    if (!literals?.length) return new Map();
    for (const action of new Set(literals)) {
      const bucket = buckets.get(action) ?? [];
      bucket.push(variant);
      buckets.set(action, bucket);
    }
  }
  // Keep the anyOf wrapper even for one candidate: invalid inputs retain the
  // original union's boundary error, rather than exposing a partial-field error.
  return new Map([...buckets].map(([action, anyOf]) => [action, { anyOf }]));
}

/** Constructed once from the loaded generated contract, never from renderer data. */
export const DESKTOP_REQUEST_ACTIONS = indexRequestActions(
  DESKTOP_SCHEMA.request,
  DESKTOP_SCHEMA.$defs,
);

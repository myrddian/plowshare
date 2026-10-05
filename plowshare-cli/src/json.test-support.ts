/** JSON assertions stay at the test transport boundary; fields are never cast to any. */
export function json(text: string): unknown {
  const value: unknown = JSON.parse(text);
  return value;
}
export function field(
  value: unknown,
  path: readonly (string | number)[],
): unknown {
  let selected = value;
  for (const key of path) {
    if (typeof key === 'number') {
      if (!Array.isArray(selected))
        throw new Error('expected fixture JSON array');
      const entries: readonly unknown[] = selected;
      selected = entries[key];
    } else {
      if (
        selected === null ||
        typeof selected !== 'object' ||
        Array.isArray(selected)
      )
        throw new Error('expected fixture JSON object');
      selected = (selected as Record<string, unknown>)[key];
    }
  }
  return selected;
}
export function text(
  value: unknown,
  path: readonly (string | number)[] = [],
): string {
  const selected = field(value, path);
  if (typeof selected !== 'string')
    throw new Error('expected fixture JSON text');
  return selected;
}
export function list(
  value: unknown,
  path: readonly (string | number)[] = [],
): readonly unknown[] {
  const selected = field(value, path);
  if (!Array.isArray(selected)) throw new Error('expected fixture JSON list');
  return selected;
}

export function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Expected fixture object.');
  return value as Record<string, unknown>;
}

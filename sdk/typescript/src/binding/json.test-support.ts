import { isObject, isList } from './values.ts';
import { outcomeIn, type Outcome } from './envelope.ts';
/** Fixture JSON stays unknown until the assertion or the production decoder checks it. */
export function json(text: string): unknown {
  const value: unknown = JSON.parse(text);
  return value;
}
export function record(value: unknown): Record<string, unknown> {
  if (!isObject(value)) throw new Error('Expected fixture object.');
  return value;
}
export function parseObject(text: string): Record<string, unknown> {
  return record(json(text));
}
export function list(value: unknown): readonly unknown[] {
  if (!isList(value)) throw new Error('Expected fixture array.');
  return value;
}
export function outcomes(text: string): Record<string, Outcome> {
  return Object.fromEntries(
    Object.entries(parseObject(text)).map(([key, value]) => [
      key,
      outcomeIn(value),
    ]),
  );
}

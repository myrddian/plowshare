import { utf8Length } from './files.ts';

/** Portable ceiling for Relay TEXT; server admission defaults to 5 MiB. Raw bytes; JSON escaping and frame metadata have separate transport budgets. */
export const MAX_RELAY_TEXT_BYTES = 50 * 1024 * 1024;

/** PostgreSQL text requires Unicode scalar values: never silently replace lone surrogates. */
export function validRelayText(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length <= MAX_RELAY_TEXT_BYTES &&
    value.trim().length > 0 &&
    !value.includes('\0') &&
    !/[\uD800-\uDFFF]/u.test(value) &&
    utf8Length(value) <= MAX_RELAY_TEXT_BYTES
  );
}

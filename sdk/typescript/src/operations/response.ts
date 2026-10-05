import type { Code } from '../binding/codes.ts';
import type { Operation, Payloads } from './direct.ts';
import type { Replies } from './replies.ts';
import { isList } from '../binding/values.ts';
// Shared, platform-free response field readers.
export const OK = 'OK';

/** Untrusted outcome accepted only by SDK boundary readers. */
export interface Answer {
  readonly code: string;
  readonly said?: string;
  readonly payload?: unknown;
}

/** Every field of an object-shaped value, or nothing at all. */
export function fieldsOf(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !isList(value)
    ? (value as Record<string, unknown>)
    : {};
}

/** A string field, or undefined for absent, null, or anything else. */
export function textAt(
  fields: Record<string, unknown>,
  name: string,
): string | undefined {
  const found = fields[name];
  return typeof found === 'string' ? found : undefined;
}

/** A number field, or undefined. `NaN` is not a count and does not survive. */
export function countAt(
  fields: Record<string, unknown>,
  name: string,
): number | undefined {
  const found = fields[name];
  return typeof found === 'number' && Number.isFinite(found)
    ? found
    : undefined;
}

/** The payload of an answer that succeeded with the code asked of it. */
export function bodyOf(
  answer: Answer,
  code: string,
): Record<string, unknown> | undefined {
  return answer.code === code ? fieldsOf(answer.payload) : undefined;
}

/** Local failures may use client status names but never raw payloads. */
export interface ApplicationAnswer<K extends Operation = Operation> {
  readonly code: string;
  readonly said?: string;
  readonly payload?: Replies[K];
}

/** Application-facing outcome: the owning adapter has decoded its operation DTO. */
export interface CheckedAnswer<K extends Operation = Operation> {
  readonly code: Code;
  readonly said?: string;
  readonly payload?: Replies[K];
}
/** Existing socket owners expose only known operations and decoded outcomes.
 * The adapter validates operation/payload pairing before I/O. */
export interface OperationTransport {
  ask<K extends Operation>(
    this: void,
    type: K,
    payload: Payloads[K],
  ): Promise<CheckedAnswer>;
}

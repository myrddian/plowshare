import { outcomeIn, type Outcome } from 'plowshare-client-ts/binding/envelope';
import {
  decodeReply,
  isOperation,
} from 'plowshare-client-ts/operations/schema';
import { RETRIEVAL_OPERATIONS } from 'plowshare-client-ts/operations/retrieval';
import type {
  RetrievalOperation,
  RetrievalReplies,
} from 'plowshare-client-ts/operations/retrieval';
import { field, json } from './json.test-support.ts';
/** Fixture copies are deliberately mutable; wire conversion remains checked. */
export type Mutable<T> = {
  -readonly [P in keyof T]: T[P] extends object ? Mutable<T[P]> : T[P];
};
export type FixtureReplies = {
  [K in RetrievalOperation]: Mutable<RetrievalReplies[K]>;
};
export function fixtures(source: string): FixtureReplies {
  const replies = field(json(source), ['replies']);
  if (!replies || typeof replies !== 'object' || Array.isArray(replies))
    throw new Error('Missing fixture replies.');
  const checked: Record<string, unknown> = {};
  for (const [type, payload] of Object.entries(replies)) {
    if (
      !isOperation(type) ||
      !RETRIEVAL_OPERATIONS.includes(type as RetrievalOperation)
    )
      throw new Error('Unknown fixture operation.');
    checked[type] = decodeReply(type, payload);
  }
  for (const type of RETRIEVAL_OPERATIONS)
    if (!Object.hasOwn(checked, type))
      throw new Error(`Missing fixture for ${type}.`);
  // Fixture files intentionally contain the retrieval catalog only. Each entry
  // has passed the production decoder; a missing lookup fails below.
  return checked as FixtureReplies;
}
export function replyOf(replies: FixtureReplies, operation: string): unknown {
  if (!Object.hasOwn(replies, operation))
    throw new Error(`No fixture for ${operation}.`);
  return Reflect.get(replies, operation) as unknown;
}

/** Historical transport fixtures can carry deliberately malformed payloads.
 * Their status envelope is still decoded; application tests own payload conversion. */
export function outcomeFixtures(text: string): Record<string, Outcome> {
  const value = json(text);
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Expected outcome fixtures.');
  return Object.fromEntries(
    Object.entries(value).map(([key, row]) => [key, outcomeIn(row)]),
  );
}

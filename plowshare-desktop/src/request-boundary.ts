import { decodeContract } from 'plowshare-client-ts/operations/schema';
import { DESKTOP_SCHEMA } from './request-schemas.ts';
import { DESKTOP_REQUEST_ACTIONS } from './request-index.ts';
import type { Request } from './shared.ts';

/** Renderer IPC is untrusted, regardless of the preload's static annotation.
 * Window authorization remains in main.ts and precedes this conversion. */
export function decodeDesktopRequest(value: unknown): Request {
  // Do not invoke accessors or route by inherited/non-enumerable fields. Those
  // unusual in-process inputs keep the original decoder's complete behavior.
  const descriptor =
    value !== null && typeof value === 'object' && !Array.isArray(value)
      ? Object.getOwnPropertyDescriptor(value, 'action')
      : undefined;
  const action: unknown = descriptor?.value;
  const schema =
    descriptor?.enumerable &&
    Object.hasOwn(descriptor, 'value') &&
    typeof action === 'string'
      ? (DESKTOP_REQUEST_ACTIONS.get(action) ?? DESKTOP_SCHEMA.request)
      : DESKTOP_SCHEMA.request;
  // Dispatch narrows candidates only; every field is still validated and copied.
  return decodeContract(
    schema,
    DESKTOP_SCHEMA.$defs,
    value,
    'desktop request',
  ) as Request;
}

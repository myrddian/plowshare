import { decodeContract } from 'plowshare-client-ts/operations/schema';
import { DESKTOP_SCHEMA } from './request-schemas.ts';
import type { Request } from './shared.ts';

/** Renderer IPC is untrusted, regardless of the preload's static annotation.
 * Window authorization remains in main.ts and precedes this conversion. */
export function decodeDesktopRequest(value: unknown): Request {
  // Every variant and nested field is checked against the generated union.
  return decodeContract(
    DESKTOP_SCHEMA.request,
    DESKTOP_SCHEMA.$defs,
    value,
    'desktop request',
  ) as Request;
}

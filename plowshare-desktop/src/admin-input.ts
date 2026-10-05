import { decodePayload } from 'plowshare-client-ts/operations/schema';
import type { Payloads } from 'plowshare-client-ts/operations/direct';
import type { ServerAdminOperation } from './shared.ts';
export type ServerAdminCall = {
  [K in ServerAdminOperation]: {
    readonly operation: K;
    readonly payload: Payloads[K];
  };
}[ServerAdminOperation];
/** UI form/JSON boundary. The controller receives the checked operation/DTO pair. */
export function decodeAdminCall(
  operation: ServerAdminOperation,
  value: unknown = {},
): ServerAdminCall {
  return {
    operation,
    payload: decodePayload(operation, value),
  } as ServerAdminCall;
}

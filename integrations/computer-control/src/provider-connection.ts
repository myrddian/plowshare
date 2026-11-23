import type { Payloads, Plowshare, Reply } from 'plowshare-client-ts';
import type { RequestOptions } from 'plowshare-client-ts/binding/acting';
import { succeeded } from 'plowshare-client-ts/binding/codes';
import { ConnectionFault } from 'plowshare-client-ts/binding/connection';
import {
  decodePayload,
  decodeReply,
} from 'plowshare-client-ts/operations/schema';
import type { OperationTransport } from 'plowshare-client-ts/operations/response';

/**
 * Adapt `checkedTransport(existingSession)` to the public ToolProvider request facade.
 * TUI/GUI retain their original event socket/session: no new socket, login or scope adoption.
 */
export function computerProviderConnection(
  transport: OperationTransport,
): Pick<Plowshare, 'request'> {
  return {
    async request<K extends keyof Payloads>(
      type: K,
      payload: Payloads[K],
      options?: RequestOptions,
    ): Promise<Reply<K>> {
      const checked = decodePayload(type, payload);
      const answer = await transport.ask(type, checked, options);
      const outcome = {
        code: answer.code,
        ...(answer.said === undefined ? {} : { said: answer.said }),
      };
      if (!succeeded(answer.code)) return { kind: 'refused', outcome };
      try {
        return {
          kind: 'success',
          outcome: { ...outcome, payload: decodeReply(type, answer.payload) },
        };
      } catch (cause) {
        throw new ConnectionFault(
          'INVALID_ENVELOPE',
          'Unreadable computer-provider reply; completion is unknown. No mutation was replayed.',
          { cause },
        );
      }
    },
  };
}

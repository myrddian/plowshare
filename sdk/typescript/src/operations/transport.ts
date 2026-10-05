import { outcomeIn } from '../binding/envelope.ts';
import type { Answer } from './response.ts';
import { ConnectionFault } from '../binding/connection.ts';
import { succeeded } from '../binding/codes.ts';
import { resultOf, VALIDATED_OPERATIONS } from './direct.ts';
import { decodeRequest, decodeReply } from './schema.ts';
import type { OperationTransport } from './response.ts';

/** Adapt an existing socket owner without opening another connection. Invalid
 * inputs never reach the wire. An unreadable reply means uncertain completion;
 * callers reconcile through durable reads rather than replaying the mutation. */
export function checkedTransport(wire: {
  ask(type: string, payload?: unknown): Promise<Answer>;
}): OperationTransport {
  return {
    async ask(type, payload) {
      const request = decodeRequest(type, payload);
      const raw = await wire.ask(request.type, request.payload);
      try {
        const answer = outcomeIn(raw);
        const detail = {
          code: answer.code,
          ...(answer.said === undefined ? {} : { said: answer.said }),
        };
        if (!succeeded(answer.code)) return detail;
        if (
          VALIDATED_OPERATIONS.includes(request.type) &&
          resultOf(request, answer).kind === 'invalid-response'
        )
          throw new Error('Operation coordinates or outcome do not match.');
        return {
          ...detail,
          payload: decodeReply(request.type, answer.payload),
        };
      } catch (cause) {
        throw new ConnectionFault(
          'INVALID_ENVELOPE',
          'Unreadable operation reply; completion is unknown. No mutation was replayed.',
          { cause },
        );
      }
    },
  };
}

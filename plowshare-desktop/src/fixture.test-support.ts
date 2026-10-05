import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { decodeRequest } from 'plowshare-client-ts/operations/schema';
import type { Request } from 'plowshare-client-ts/operations/direct';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
/** Fake wire replies are untrusted, including deliberate corruption cases. */
export function checkedSender(send: (request: Request) => Promise<Outcome>) {
  const transport = checkedTransport({
    ask: (type, payload) => send(decodeRequest(type, payload)),
  });
  return (request: Request) => transport.ask(request.type, request.payload);
}
/** A missing fixture element is a test setup error, never a nullable success. */
export function present<T>(value: T | null | undefined): T {
  if (value === undefined || value === null)
    throw new Error('expected test fixture value is absent');
  return value;
}

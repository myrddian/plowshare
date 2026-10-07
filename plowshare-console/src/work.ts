import type {
  FiringRecord,
  InboxMarked,
  InboxPage,
} from '../../sdk/typescript/src/operations/administrative-replies.ts';
import type {
  Payloads,
  Operation,
} from '../../sdk/typescript/src/operations/direct.ts';
import type { Replies } from '../../sdk/typescript/src/operations/replies.ts';
import { decodeReply } from '../../sdk/typescript/src/operations/schema.ts';
import { checkedTransport } from '../../sdk/typescript/src/operations/transport.ts';
import type { EventStream } from './events';

/** An explicit refusal, distinct from an uncertain transport/protocol failure. */
export class WorkRefused extends Error {}

/** Account-owned retained deliveries and authorized event admission records.
 * Paging is server paging; receipt mutations never run as part of reconciliation. */
export interface WorkRecords {
  unread(): Promise<number>;
  inbox(offset: number, limit: number): Promise<InboxPage>;
  markRead(id: string): Promise<InboxMarked>;
  firings(offset: number, limit: number): Promise<readonly FiringRecord[]>;
}

export function socketWorkRecords(stream: EventStream): WorkRecords {
  const transport = checkedTransport(stream);
  async function call<K extends Operation>(
    type: K,
    payload: Payloads[K],
  ): Promise<Replies[K]> {
    const reply = await transport.ask(type, payload);
    if (reply.code === 'INTERNAL_ERROR')
      throw new Error(
        'Work operation completion is unknown after a server error.',
      );
    if (reply.code !== 'OK')
      throw new WorkRefused(reply.said ?? `${type} was refused.`);
    return decodeReply(type, reply.payload);
  }
  return {
    unread: async () =>
      (await call('inbox.list', { unread: true, limit: 1 })).unread,
    inbox: (offset, limit) => call('inbox.list', { offset, limit }),
    markRead: (id) => call('inbox.read', { items: [id] }),
    firings: (offset, limit) => call('firing.list', { offset, limit }),
  };
}

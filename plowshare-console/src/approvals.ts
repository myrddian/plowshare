import type {
  ApprovalAnswered,
  ApprovalView,
} from '../../sdk/typescript/src/operations/administrative-replies.ts';
import { decodeReply } from '../../sdk/typescript/src/operations/schema.ts';
import { checkedTransport } from '../../sdk/typescript/src/operations/transport.ts';
import type { EventStream } from './events';

export type ApprovalDecision = 'once' | 'conversation' | 'project' | 'deny';

/** Account-wide or explicitly conversation-scoped requests. Decisions affect
 * only the displayed request;
 * the server retains ownership, access checks and continuation authority. */
export interface Approvals {
  list(conversation?: string): Promise<readonly ApprovalView[]>;
  answer(
    id: string,
    decision: ApprovalDecision,
    prefix?: readonly string[],
  ): Promise<ApprovalAnswered>;
}

/** An explicit server refusal establishes that this decision was not accepted. */
export class ApprovalRefused extends Error {}

/** Reuse the tab's socket. Decode before exposing DTOs; never replay a decision
 * after a lost or malformed reply, since the server may already have applied it. */
export function socketApprovals(stream: EventStream): Approvals {
  const transport = checkedTransport(stream);
  return {
    async list(conversation) {
      const answer = await transport.ask(
        'approval.list',
        conversation === undefined ? { mine: true } : { conversation },
      );
      if (answer.code !== 'OK') {
        throw new ApprovalRefused(
          answer.said ?? 'Approvals could not be read.',
        );
      }
      return decodeReply('approval.list', answer.payload).approvals;
    },
    async answer(id, decision, prefix) {
      const answer = await transport.ask(
        'approval.answer',
        decision === 'project'
          ? { id, decision, prefix: [...(prefix ?? [])] }
          : { id, decision },
      );
      if (answer.code !== 'OK') {
        // A handler can fail after recording a decision but before returning
        // its continuation receipt. Internal failure does not prove refusal.
        if (answer.code === 'INTERNAL_ERROR')
          throw new Error(
            'Approval completion is unknown after a server error.',
          );
        throw new ApprovalRefused(answer.said ?? 'That decision was refused.');
      }
      const receipt = decodeReply('approval.answer', answer.payload);
      if (receipt.id !== id) {
        throw new Error('The approval receipt names another request.');
      }
      return receipt;
    },
  };
}

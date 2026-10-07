import type {
  ApprovalAnswered,
  ApprovalRevoked,
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

/** Standing project grants remain server-owned. Reads require current project
 * access; revocation requires an explicit selection and is never replayed. */
export interface ProjectGrants {
  list(project: string): Promise<readonly ApprovalView[]>;
  revoke(id: string): Promise<ApprovalRevoked>;
}

export function socketProjectGrants(stream: EventStream): ProjectGrants {
  const transport = checkedTransport(stream);
  return {
    async list(project) {
      const answer = await transport.ask('approval.list', { project });
      if (answer.code !== 'OK')
        throw new ApprovalRefused(
          answer.said ?? 'Project grants could not be read.',
        );
      const grants = decodeReply('approval.list', answer.payload).approvals;
      if (
        grants.some(
          (grant) => grant.state !== 'allowed' || grant.scope !== 'project',
        )
      )
        throw new Error(
          'The project grant list contains a request that is not standing.',
        );
      return grants;
    },
    async revoke(id) {
      const answer = await transport.ask('approval.revoke', { id });
      if (answer.code !== 'OK') {
        if (answer.code === 'INTERNAL_ERROR')
          throw new Error('Grant revocation is unknown after a server error.');
        throw new ApprovalRefused(
          answer.said ?? 'That revocation was refused.',
        );
      }
      const receipt = decodeReply('approval.revoke', answer.payload);
      if (receipt.id !== id)
        throw new Error('The revocation receipt names another grant.');
      return receipt;
    },
  };
}

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

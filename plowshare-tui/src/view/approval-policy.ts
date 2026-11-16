import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Transport } from 'plowshare-client-ts/operations/direct';
import type { ApprovalPolicyView } from 'plowshare-client-ts/operations/approval-policy';
import type { ModeCommand } from '../logic/approval-policy.ts';

/** Preserve the owner's rules and use CAS. Neither an uncertain save nor a conflict is retried. */
export async function changeApprovalMode(
  transport: Transport,
  change: Extract<ModeCommand, { kind: 'change' }>,
  timeoutMs = 2000,
): Promise<ApprovalPolicyView> {
  const read = await transport.ask('approval.policy.get', {}, { timeoutMs });
  if (read.code !== 'OK')
    throw new Error('Could not read the saved personal approval policy.');
  const current = decodeReply('approval.policy.get', read.payload);
  const result = await transport.ask(
    'approval.policy.save',
    { policy: { ...current.policy, mode: change.mode, bot: change.bot } },
    { timeoutMs },
  );
  if (result.code !== 'OK')
    throw new Error(
      'Policy save was not confirmed. Inspect /approval policy get before trying again; the save is not retried.',
    );
  return decodeReply('approval.policy.save', result.payload);
}

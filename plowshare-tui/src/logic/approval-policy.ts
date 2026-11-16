import type { Payloads } from 'plowshare-client-ts/operations/direct';
type ApprovalMode = Payloads['approval.policy.save']['policy']['mode'];

export type ModeCommand =
  | { kind: 'unhandled' }
  | { kind: 'usage'; said: string }
  | { kind: 'change'; mode: ApprovalMode; bot: string | null };
export function approvalModeCommand(line: string): ModeCommand {
  if (!/^\/approval-mode(?:\s|$)/u.test(line.trim()))
    return { kind: 'unhandled' };
  const parts = line.trim().split(/\s+/u);
  const mode = parts[1]?.toUpperCase();
  if (
    ((mode === 'MANUAL' || mode === 'SMART') && parts.length === 2) ||
    (mode === 'AUTO' && parts.length === 3 && parts[2])
  )
    return { kind: 'change', mode, bot: mode === 'AUTO' ? parts[2]! : null };
  return {
    kind: 'usage',
    said: 'Use /approval-mode manual, /approval-mode smart, or /approval-mode auto PERSONAL_BOT. Use /approval policy get to inspect rules and /approval policy save with the complete policy and its current revision to edit them.',
  };
}

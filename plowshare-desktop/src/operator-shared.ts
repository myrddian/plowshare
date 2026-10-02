export const OPERATOR_KINDS = ['memory-write', 'memory-digest', 'agent-curate', 'conversation-lifecycle', 'conversation-resume', 'job-limits', 'approval-grant', 'approval-revoke', 'board-topup', 'caps'] as const;
export type OperatorKind = typeof OPERATOR_KINDS[number];
export interface OperatorView { kind: OperatorKind; project?: string; data: Record<string, unknown>; identity: string; preview?: { identity: string; summary: string; payload: Record<string, unknown>; subject?: Record<string, unknown> }; busy?: boolean; notice?: string; error?: string; receipt?: unknown }

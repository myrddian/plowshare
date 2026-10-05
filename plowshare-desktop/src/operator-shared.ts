import type { Replies } from 'plowshare-client-ts/operations/replies';
import type { Request as WsRequest } from 'plowshare-client-ts/operations/direct';
import type {
  MessageInstance,
  MessageDelivery,
} from 'plowshare-client-ts/operations/messaging';
import type { CapsFile, CapKey } from './caps.ts';

export interface OperatorInput {
  id?: string;
  agent?: string;
  body?: string;
  summary?: string;
  scope?: string;
  lifecycle?: 'active' | 'archived';
  decision?: 'once' | 'conversation' | 'project' | 'deny';
  makeDefault?: 'true' | 'false';
  prefix?: string;
  maxTurns?: number;
  maxModelCalls?: number;
  key?: CapKey;
  value?: number;
}
export interface OperatorData {
  instances?: readonly MessageInstance[];
  deliveries?: readonly MessageDelivery[];
  agents?: Replies['agent.list'];
  memories?: Replies['memory.index'];
  conversations?: readonly (Replies['conversation.list'][number] & {
    lifecycle: 'active' | 'archived';
  })[];
  jobs?: Replies['job.list'];
  approvals?: Replies['approval.list']['approvals'];
  topics?: Replies['board.topics']['topics'];
  caps?: Replies['orchestration.caps'];
  file?: CapsFile;
  messageInstance?: string;
  messageOffset?: number;
  messageMore?: boolean;
}
/** Details shown for a selected, validated record; each control owns its selection checks. */
export interface OperatorSubject {
  limits?: Replies['job.list'][number]['limits'];
  id?: string;
  name?: string;
  agent?: string;
  conversation?: string | null;
  state?: string;
  lifecycle?: string;
  project?: string | null;
  potTotal?: number | null;
  title?: string | null;
  label?: string | null;
  command?: readonly string[];
  commands?:
    | readonly (readonly string[])[]
    | Replies['agent.list'][number]['commands']
    | null;
  scope?: string | null;
  cwd?: string;
  side?: string;
  sender?: string;
  recipient?: string;
  body?: string;
}
export interface OperatorReceipt {
  code: string;
  said?: string;
  id?: string;
  job?: string;
  revoked?: boolean;
  kind?: string;
  busy?: boolean;
  note?: string;
}
export const OPERATOR_KINDS = [
  'memory-write',
  'memory-digest',
  'agent-curate',
  'conversation-lifecycle',
  'conversation-resume',
  'job-limits',
  'approval-grant',
  'approval-revoke',
  'board-topup',
  'message-deliveries',
  'message-open',
  'message-default',
  'message-stop',
  'message-archive',
  'caps',
] as const;
export type OperatorKind = (typeof OPERATOR_KINDS)[number];
export interface OperatorView {
  kind: OperatorKind;
  project?: string;
  data: OperatorData;
  identity: string;
  preview?: {
    identity: string;
    summary: string;
    payload: WsRequest['payload'] | { key: CapKey; value: number };
    subject?: OperatorSubject;
  };
  busy?: boolean;
  notice?: string;
  error?: string;
  receipt?: OperatorReceipt;
}

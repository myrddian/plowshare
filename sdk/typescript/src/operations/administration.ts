import type {
  ExternalMessage,
  ExternalResult,
  IncomingSource,
  AgentCard,
} from './external.ts';
import type { UsagePayloads } from './usage.ts';
import type { ScheduleDefinition } from './schedule-files.ts';
/** JSON choices/data are owned and interpreted by the server. */
export type JsonValue =
  | null
  | boolean
  | number
  | string
  | readonly JsonValue[]
  | { readonly [key: string]: JsonValue };
type Tier = { readonly project?: string | null };
type Id = { readonly id: string };
type Page = { readonly offset?: number; readonly limit?: number };
export interface AdministrativePayloads {
  'outgoing.send': Tier & {
    readonly requestId: string;
    readonly peer: string;
    readonly message: ExternalMessage;
    readonly conversation?: string | null;
  };
  'outgoing.status': Id;
  'outgoing.cancel': Id;
  'outgoing.peers': Tier;
  'approval.list': Tier & {
    readonly conversation?: string | null;
    readonly mine?: boolean;
  };
  'approval.answer': Id & {
    readonly decision: 'once' | 'conversation' | 'project' | 'deny';
    readonly prefix?: readonly string[];
  };
  'approval.revoke': Id;
  'board.topup': { readonly topic: string; readonly maxModelCalls: number };
  'board.open': {
    readonly project: string;
    readonly swarm?: string;
    readonly title: string;
    readonly label: string;
    readonly body: string;
    readonly requestId: string;
    readonly maxModelCalls?: number;
  };
  'board.retry': {
    readonly project: string;
    readonly topic: string;
    readonly member: string;
    readonly requestId: string;
    readonly maxTurns: number;
  };
  'board.post': {
    readonly project: string;
    readonly topic: string;
    readonly body: string;
    readonly requestId: string;
  };
  'buffer.purge': Record<string, never>;
  'retention.sweep': Record<string, never>;
  'provider.list': Record<string, never>;
  'provider.deregister': { readonly provider: string };
  'inbox.list': Page & { readonly unread?: boolean };
  'inbox.read': { readonly items: readonly string[] };
  'todos.read': { readonly conversation: string };
  'orchestration.start': Tier & {
    readonly agent: string;
    readonly definition: string;
    readonly request: string;
    readonly context?: string;
    readonly requestId: string;
  };
  'orchestration.receipt': { readonly requestId: string };
  'orchestration.definitions': Tier;
  'orchestration.list': Tier & {
    readonly state?: string;
    readonly limit?: number;
  };
  'orchestration.status': Id;
  'orchestration.answer': Id & {
    readonly answer?: string | null;
    readonly choices?: readonly OrchestrationChoice[];
  };
  'orchestration.cancel': Id;
  'orchestration.resume': Id & { readonly requestId: string };
  'orchestration.caps': { readonly project: string };
  'orchestration.record': {
    readonly root: string;
    readonly after?: number;
    readonly before?: number;
    readonly tail?: boolean;
    readonly limit?: number;
    readonly kinds?: readonly string[];
  };
  'schedule.save': {
    readonly name: string;
    readonly project?: string | null;
    readonly source: 'server' | 'workspace';
    readonly definition: ScheduleDefinition;
    readonly overwrite: boolean;
  };
  'schedule.sync': {
    readonly project?: string | null;
    readonly source: 'server' | 'workspace';
  };
  'schedule.files': Record<string, never>;
  'schedule.list': Record<string, never>;
  'schedule.define': {
    readonly schedule: string;
    readonly cron: string;
    readonly emits: string;
    readonly zone?: string;
  };
  'schedule.read': Tier & {
    readonly text: string;
    readonly zone?: string;
    readonly conversation?: string | null;
  };
  'schedule.pause': { readonly schedule: string; readonly paused: boolean };
  'schedule.forget': { readonly schedule: string };
  'trigger.list': Record<string, never>;
  'trigger.define': Tier & {
    readonly trigger: string;
    readonly event: string;
    readonly agent: string;
    readonly task: string;
    readonly conversation?: string | null;
    readonly maxModelCalls?: number;
    readonly maxTurns?: number;
    readonly queueCap?: number;
  };
  'trigger.pause': { readonly trigger: string; readonly paused: boolean };
  'trigger.forget': { readonly trigger: string };
  'event.fire': {
    readonly event: string;
    readonly data?: { readonly text?: string } | null;
  };
  'firing.list': Page & { readonly trigger?: string; readonly status?: string };
  'union.status': { readonly project: string };
  'union.conflict.list': { readonly project: string };
}

/** Persistent observer/presence operations are typed for platform adapters, not one-shot commands. */
export interface BoundPayloads {
  'incoming.catalog': { readonly project: string; readonly agent: string };
  'incoming.receive': {
    readonly project: string;
    readonly client: string;
    readonly agent: string;
    readonly requestId: string;
    readonly context?: string | null;
    readonly body: string;
    readonly command?: string | null;
    readonly source?: IncomingSource | null;
  };
  'incoming.status': {
    readonly project: string;
    readonly client: string;
    readonly id: string;
  };
  'incoming.cancel': {
    readonly project: string;
    readonly client: string;
    readonly id: string;
  };

  'outgoing.advertise': Tier & {
    readonly peers: readonly string[];
    readonly agentCards?: Readonly<Record<string, AgentCard>> | null;
  };
  'outgoing.claim': Tier & { readonly peers: readonly string[] };
  'outgoing.report': Id & {
    readonly revision: number;
    readonly state: string;
    readonly remoteTask?: string | null;
    readonly remoteContext?: string | null;
    readonly result?: ExternalResult | null;
    readonly error?: string | null;
  };
  'usage.subscribe': UsagePayloads['usage.subscribe'];
  'usage.unsubscribe': { readonly subscription: string };
  'job.stream': { readonly on?: boolean };
  'conversation.follow':
    | { readonly conversation: string; readonly conversations?: never }
    | {
        readonly conversations: readonly string[];
        readonly conversation?: never;
      };
  'union.enable': { readonly project: string };
  'union.begin': { readonly project: string };
  'union.ready': { readonly project: string; readonly commit: string };
  'union.abort': { readonly project: string };
  'union.disable': { readonly project: string };
  'union.hidden': {
    readonly project: string;
    readonly paths: readonly string[];
  };
  'union.conflict.open': {
    readonly project: string;
    readonly path: string;
    readonly theirsAuthor: string;
    readonly baseBlob?: string | null;
    readonly oursBlob?: string | null;
    readonly theirsBlob?: string | null;
    readonly runId?: string | null;
  };
  'union.conflict.resolve': {
    readonly project: string;
    readonly n: number;
    readonly resolution: 'mine' | 'theirs' | 'merged';
  };
}
export const BOUND_OPERATIONS = {
  'incoming.catalog': 'persistent inbound adapter',
  'incoming.receive': 'persistent inbound adapter',
  'incoming.status': 'persistent inbound adapter',
  'incoming.cancel': 'persistent inbound adapter',

  'outgoing.advertise': 'persistent outbound adapter',
  'outgoing.claim': 'persistent outbound adapter',
  'outgoing.report': 'persistent outbound adapter',
  'usage.subscribe': 'usage-view-snapshot-subscription',
  'usage.unsubscribe': 'usage-view-snapshot-subscription',
  'job.stream': 'persistent-observer',
  'conversation.follow': 'persistent-observer',
  'union.enable': 'rooted-filesystem-presence',
  'union.begin': 'rooted-filesystem-presence',
  'union.ready': 'rooted-filesystem-presence',
  'union.abort': 'rooted-filesystem-presence',
  'union.disable': 'rooted-filesystem-presence',
  'union.hidden': 'rooted-filesystem-presence',
  'union.conflict.open': 'rooted-filesystem-presence',
  'union.conflict.resolve': 'rooted-filesystem-presence',
} as const satisfies Record<keyof BoundPayloads, string>;

/** Structured person answer; labels and free text remain separate. */
export interface OrchestrationChoice {
  readonly header: string;
  readonly chosen: readonly string[];
  readonly other?: string;
  readonly note?: string;
}

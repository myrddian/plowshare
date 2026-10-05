import type { RelayReplies } from './relay.ts';
import type { IncomingSource, IncomingCommand, AgentCard } from './external.ts';
import type { OutgoingWork } from './outgoing.ts';
import type { MessagingReplies } from './messaging.ts';
import type { UsageInitial, UsageReplies } from './usage.ts';
import type { InformationReplies } from './information-replies.ts';
import type { ObservedJob } from '../binding/job-view.ts';
import type { AdministrativeReplies } from './administrative-replies.ts';
import type { ConversationReplies } from './conversation-replies.ts';
import type { InspectionReplies } from './inspection-replies.ts';
import type { RecordPageView } from './records.ts';
import type { RetrievalReplies } from './retrieval.ts';

export interface IncomingTask {
  readonly id: string;
  readonly context: string;
  readonly agent: string;
  readonly message: string;
  readonly state: string;
  readonly ending: string | null;
  readonly source: IncomingSource | null;
  readonly replies: readonly {
    readonly id: string;
    readonly body: string;
    readonly finalReply: boolean;
    readonly generated: boolean;
    readonly ending: string | null;
    readonly postedAt: string;
  }[];
  readonly createdAt: string;
}
/** The server's StartedJob; older accepted replies may omit agent metadata. */
export interface StartedJob {
  readonly id: string;
  readonly agent: string;
  readonly conversation?: string | null;
}
export type ObservedStartedJob = Pick<StartedJob, 'id'> &
  Partial<Pick<StartedJob, 'agent' | 'conversation'>>;
export interface ContextCount {
  readonly tokens: string | null;
  readonly basis: 'MEASURED' | 'ESTIMATED' | 'UNKNOWN';
  readonly source: string;
  readonly pool: string | null;
  readonly model: string | null;
  readonly revision: string | null;
  readonly countedAt: string;
  readonly elapsedMillis: number;
  readonly cached: boolean;
  readonly gaps: readonly string[];
}
/** All one-shot WS payload contracts. Raw refusal diagnostics remain inside the wire transport. */
export interface Replies
  extends
    MessagingReplies,
    RelayReplies,
    UsageReplies,
    InformationReplies,
    RetrievalReplies,
    AdministrativeReplies,
    ConversationReplies,
    InspectionReplies {
  'conversation.follow': void;
  'job.stream': void;
  'outgoing.advertise': void;
  'outgoing.claim': {
    readonly work: OutgoingWork | null;
    readonly action: 'send' | 'observe' | 'cancel' | null;
  };
  'outgoing.report': OutgoingWork;
  'union.enable': { readonly url: string };
  'union.begin': { readonly url: string };
  'union.ready': Readonly<Record<string, never>>;
  'union.abort': Readonly<Record<string, never>>;
  'union.disable': Readonly<Record<string, never>>;
  'union.hidden': {
    readonly syncHidden: readonly string[];
    readonly replaced: true;
  };
  'union.conflict.open': { readonly n: number };
  'union.conflict.resolve': Readonly<Record<string, never>>;
  'usage.subscribe': UsageInitial;
  'usage.unsubscribe': Readonly<Record<string, never>>;
  'incoming.catalog': {
    readonly name: string;
    readonly description: string;
    readonly served: boolean;
    readonly commands: readonly IncomingCommand[];
  };
  'incoming.receive': IncomingTask;
  'incoming.status': IncomingTask;
  'incoming.cancel': IncomingTask;
  'outgoing.send': OutgoingWork;
  'outgoing.status': OutgoingWork;
  'outgoing.cancel': OutgoingWork;
  'outgoing.peers': {
    readonly peers: readonly string[];
    readonly details?: readonly {
      readonly peer: string;
      readonly agentCard: AgentCard | null;
    }[];
  };
  'conversation.context.count': {
    readonly conversation: string;
    readonly agent: string;
    readonly projection: 'next';
    readonly count: ContextCount;
  };
  'memory.digest': ObservedStartedJob;
  'agent.curate': ObservedStartedJob;
  'agent.run': ObservedStartedJob;
  'conversation.resume': ObservedStartedJob;
  'document.ask': ObservedStartedJob;
  'job.status': ObservedJob;
  'job.cancel': ObservedJob;
  'orchestration.start': {
    readonly id: string;
    readonly state: string;
    readonly requestId: string;
  };
  'orchestration.receipt': {
    readonly id: string;
    readonly state: string;
    readonly requestId: string;
  };
  'orchestration.record': RecordPageView;
}
export type ReplyOperation = keyof Replies;

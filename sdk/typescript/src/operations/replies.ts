import type { ToolInvocationResult } from './tool-results.ts';
import type { ToolScopeReplies } from './tool-scopes.ts';
import type { ImageAdmissionStatus } from './image-admissions.ts';
import type { ProcReplies } from './proc.ts';
import type { RelayReplies } from './relay.ts';
import type { MessagingReplies } from './messaging.ts';
import type { UsageInitial, UsageReplies } from './usage.ts';
import type { InformationReplies } from './information-replies.ts';
import type { ObservedJob } from '../binding/job-view.ts';
import type { AdministrativeReplies } from './administrative-replies.ts';
import type { ConversationReplies } from './conversation-replies.ts';
import type { InspectionReplies } from './inspection-replies.ts';
import type { RecordPageView } from './records.ts';
import type { RetrievalReplies } from './retrieval.ts';
import type { ContainedApplicationRunning } from './application-deployments.ts';

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
/** Receipt evidence survives disconnect/restart; a pending receipt never authorizes a replay. */
export interface ActingTurnReceipt {
  readonly requestId: string;
  readonly conversation: string;
  readonly agent: string;
  readonly state: 'ADMITTED' | 'STARTED' | 'FINISHED' | 'UNCERTAIN';
  readonly job: string | null;
}

export interface Replies
  extends
    ProcReplies,
    MessagingReplies,
    ToolScopeReplies,
    RelayReplies,
    UsageReplies,
    InformationReplies,
    RetrievalReplies,
    AdministrativeReplies,
    ConversationReplies,
    InspectionReplies {
  'artifact.admission.status': ImageAdmissionStatus;
  'tool.invocation.read': ToolInvocationResult;
  'acting.context.open': {
    readonly context: string;
    readonly subject: string;
    readonly expiresAt: string;
  };
  'acting.context.close': Readonly<Record<string, never>>;
  'acting.turn.start': ActingTurnReceipt;
  'acting.turn.receipt': ActingTurnReceipt;
  'conversation.follow': void;
  'job.stream': void;
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
  'conversation.context.count': {
    readonly conversation: string;
    readonly agent: string;
    readonly projection: 'next';
    readonly count: ContextCount;
  };
  'memory.digest': ObservedStartedJob;
  'agent.curate': ObservedStartedJob;
  'agent.run': ObservedStartedJob;
  // Typed here rather than with the other contained operations because it is the only one that
  // answers ACCEPTED: a run is a handle to poll, so its reply belongs with the started-job shapes
  // and not with the conversation family's OK replies. Its own shape, not a StartedJob: the handle
  // is in `job` and the identity the caller correlates on is `child`.
  'application.child.run': ContainedApplicationRunning;
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

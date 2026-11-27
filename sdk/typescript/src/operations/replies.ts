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
/** Ex always resolves an existing internal conversation and returns that identity with its job. */
export type ConversationStartedJob = Omit<
  ObservedStartedJob,
  'conversation'
> & {
  readonly conversation: string;
};
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

export interface GoalAgentStarted {
  readonly goal: string;
  readonly conversation: string;
  readonly instance: string;
  readonly version: number;
  readonly requestId: string;
}

/** Current project-scoped status. Content is withheld when readable is false. */
export interface GoalStatus {
  readonly goal: string;
  readonly project: string;
  readonly conversation: string;
  readonly instance: string;
  readonly agent: string | null;
  readonly objective: string | null;
  readonly lifecycle: 'AD_HOC' | 'STANDING';
  readonly state: ConversationGoalStatus['state'];
  readonly version: number;
  readonly readable: boolean;
  readonly reason: ConversationGoalStatus['reason'];
  readonly detail: string | null;
  readonly latestResult: GoalResult | null;
  readonly nextScheduleAt: string | null;
  readonly schedules: readonly GoalScheduleWake[];
  readonly relayListeners: readonly GoalRelayWake[];
  readonly moreSchedules: boolean;
  readonly moreRelayListeners: boolean;
}

/** Latest committed cycle or terminal result and the conversation turn that produced it. */
export interface GoalResult {
  readonly job: string;
  readonly turn: number;
  readonly summary: string;
  readonly evidence: readonly string[];
}
export interface GoalScheduleWake {
  readonly schedule: string;
  readonly generation: string;
  readonly paused: boolean;
  readonly nextFireAt: string | null;
}
export interface GoalRelayWake {
  readonly binding: string;
  readonly topic: string | null;
  readonly generation: string;
  readonly failure:
    'SOURCE_CHANGED' | 'RETENTION_GAP' | 'UNSUPPORTED_PUBLICATION' | null;
  readonly pendingBatch: GoalPendingBatch | null;
}
export interface GoalPendingBatch {
  readonly id: string;
  readonly state: 'OPEN' | 'READY';
  readonly memberCount: number;
  readonly deadline: string;
}
export interface GoalPage {
  readonly goals: readonly GoalStatus[];
  readonly next: string | null;
}

export interface GoalAgentSources {
  readonly goal: string;
  readonly project: string;
  readonly sources: readonly {
    readonly topic: string;
    readonly generation: string;
    readonly kind: 'TEXT' | 'EMPTY';
  }[];
}

export interface GoalAgentListener {
  readonly binding: string;
  readonly goal: string;
  readonly topic: string;
  readonly generation: string;
}

/** Retained acknowledgement for a versioned removal of a dedicated Goal Agent listener. */
export interface GoalAgentUnlistenReceipt {
  readonly goal: string;
  readonly binding: string;
  readonly version: number;
  readonly requestId: string;
}

/** Coordinates of a dedicated Goal Agent answer accepted for durable delivery. */
export interface GoalAnswerReceipt {
  readonly goal: string;
  readonly conversation: string;
  readonly question: string;
  readonly requestId: string;
}

export interface ConversationGoalStatus {
  readonly goal: string;
  readonly conversation: string;
  readonly agent: string;
  readonly objective: string;
  readonly criteria: string | null;
  readonly state:
    | 'READY'
    | 'RUNNING'
    | 'IDLE'
    | 'WAITING'
    | 'PAUSING'
    | 'PAUSED'
    | 'BLOCKED'
    | 'STOPPING'
    | 'COMPLETE'
    | 'CANCELLED';
  readonly version: number;
  readonly activations: number;
  readonly maxActivations: number;
  readonly lastJob: string | null;
  readonly lastTurn: number | null;
  readonly reason:
    | 'OPERATOR_PAUSED'
    | 'ACTIVATION_LIMIT'
    | 'MODEL_ALLOWANCE'
    | 'NO_PROGRESS'
    | 'TURN_FAILED'
    | 'AUTHORITY_OR_SOURCE_CHANGED'
    | 'INELIGIBLE_CONVERSATION'
    | 'AGENT_BLOCKED'
    | 'UNCONFIRMED_TURN'
    | 'STOP_REQUESTED'
    | null;
  readonly detail: string | null;
  readonly modelCallsSpent: number | null;
  readonly modelCallLimit: number | null;
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
  'agent.run.ex': ConversationStartedJob;
  // Typed here rather than with the other contained operations because it is the only one that
  // answers ACCEPTED: a run is a handle to poll, so its reply belongs with the started-job shapes
  // and not with the conversation family's OK replies. Its own shape, not a StartedJob: the handle
  // is in `job` and the identity the caller correlates on is `child`.
  'application.child.run': ContainedApplicationRunning;
  'goal.start': GoalAgentStarted;
  'goal.list': GoalPage;
  'goal.status': GoalStatus;
  'goal.receipt': GoalAgentStarted;
  'goal.sources': GoalAgentSources;
  'goal.answer': GoalAnswerReceipt;
  'message.listen': GoalAgentListener;
  'message.unlisten': GoalAgentUnlistenReceipt;
  'conversation.goal.start': ConversationGoalStatus;
  'conversation.goal.get': ConversationGoalStatus;
  'conversation.goal.pause': ConversationGoalStatus;
  'conversation.goal.resume': ConversationGoalStatus;
  'conversation.goal.cancel': ConversationGoalStatus;
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

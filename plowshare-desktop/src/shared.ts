import type { UsageReportType, UsageFilter, UsageViewState } from 'plowshare-client-ts/operations/usage';
import type { OperatorKind, OperatorView } from './operator-shared.ts';
import type { InformationOperation, InformationScope } from 'plowshare-client-ts/operations/information';
import type { LogSearchResponse } from 'plowshare-client-ts/operations/retrieval';
import type { LibraryState, LibraryView, SearchKind } from './library-shared.ts';
import type { DefinitionView, OrchestrationStatus, ScheduleRecord, TriggerRecord, FiringRecord, ScheduleProposal } from 'plowshare-client-ts/operations/administrative-replies';
import type { RecordView } from 'plowshare-client-ts/operations/records';
import type { Choice } from 'plowshare-client-ts/operations/session';
import type { FilePresenceState } from './files-shared.ts';
import type { SyncState } from './sync-shared.ts';
import type { BoardInspection, BoardView } from './board-shared.ts';
import type { Agent, Approval, Conversation, Entry, Project, Load, Allowance, Pace, InboxItem, Run, RunStatus } from 'plowshare-client-ts/operations/client-views';
export type { Agent, Approval, Conversation, Entry, Project };

export interface Job {
  id: string;
  conversation: string;
  agent: string;
  task: string;
  source?: 'approval' | 'information' | 'maintenance';
  revision?: string;
  logAfter?: number;
  status: 'starting' | 'running' | 'cancelling' | 'finished' | 'interrupted' | 'unknown';
  text: string;
  thinking?: string;
  phase?: 'thinking' | 'answer' | 'tool';
  tool?: string;
  answered?: boolean;
  allowance?: Allowance;
  pace?: Pace;
  events: Record<string, unknown>[];
  ending?: string;
  detail?: string;
}
export interface History {
  entries: Entry[];
  more: boolean;
  oldest?: number;
  through?: number;
  error?: string;
}
export interface DesktopState {
  usage?: UsageViewState;
  mode: 'demo' | 'live';
  connected: boolean;
  connection: string;
  base: string;
  handle: string;
  projects: Project[];
  agents: Record<string, Agent[]>;
  conversations: Conversation[];
  history: Record<string, History>;
  jobs: Job[];
  approvals: Approval[];
  answeringApprovals?: string[];
  contexts: Record<string, ContextReading>;
  board: BoardInspection;
  files: FilePresenceState;
  sync?: SyncState;
  localMachine?: string;
  projectPreparing?: string[];
  projectFolders?: { name: string; path: string; machine: string; enabled: boolean; connected: boolean; files: FilePresenceState; sync?: SyncState; error?: string }[];
  projectConfigError?: string;
  connectionPersistenceError?: string;
  jobRecoveryError?: string;
  projectListError?: string;
  liveHistory?: { status: 'updating' | 'ready' | 'unavailable'; detail?: string };
  activity: AccountActivity;
  library?: LibraryState;
  operator?: OperatorView;
  authoring?: { project: string; agent: string; intent: string; revision?: string; conversation?: string; status: 'launching' | 'submitted' | 'unknown'; error?: string };
}
export type ActivityView = 'inbox' | 'runs' | 'definitions' | 'schedules' | 'builder';
export interface AccountActivity {
  view?: ActivityView;
  inbox: { items: InboxItem[]; read: string[]; unread?: number; more?: boolean; loaded: boolean; loading?: boolean; error?: string };
  runs: { items: Run[]; loaded: boolean; loading?: boolean; error?: string; limited?: boolean };
  details: Record<string, { value?: RunStatus; wire?: OrchestrationStatus; loading?: boolean; error?: string }>;
  definitions?: { project?: string; items: readonly DefinitionView[]; loading?: boolean; error?: string };
  records?: Record<string, { root: string; rows: readonly RecordView[]; through: number; oldest: number | null; more: boolean; kinds?: readonly string[]; loading?: boolean; error?: string }>;
  schedules?: { schedules: readonly ScheduleRecord[]; triggers: readonly TriggerRecord[]; firings: readonly FiringRecord[]; proposal?: ScheduleProposal; loading?: boolean; previewing?: boolean; busy?: boolean; error?: string; previewError?: string; notice?: string };
  decisions?: Record<string, { busy?: boolean; error?: string; notice?: string }>;

}
export const emptyActivity = (): AccountActivity => ({ inbox: { items: [], read: [], loaded: false }, runs: { items: [], loaded: false }, details: {} });
export interface ContextReading extends Load {
  status: 'ready' | 'unavailable';
  detail?: string;
  sample?: boolean;
}
export type Request =
  | { action: 'usage-open'; type: UsageReportType; filter: UsageFilter }
  | { action: 'usage-read'; type: UsageReportType | 'usage.calls' | 'conversation.context.count'; payload: unknown }
  | { action: 'usage-close' }
  | { action: 'operator-prepare'; kind: OperatorKind; project?: string }
  | { action: 'operator-preview'; identity: string; input: Record<string, unknown> }
  | { action: 'operator-apply'; identity: string }
  | { action: 'information'; operation: InformationOperation; scope: InformationScope; payload?: Record<string, unknown> }
  | { action: 'bootstrap' | 'demo' | 'disconnect' | 'refresh' | 'approvals-refresh' }
  | { action: 'connect'; base: string; handle: string; password: string }
  | { action: 'files-choose'; project?: string }
  | { action: 'files-withdraw'; project?: string }
  | { action: 'sync-refresh'; project: string }
  | { action: 'sync-inspect'; project: string; path: string }
  | { action: 'sync-change'; project: string; kind: 'on' | 'off' | 'now'; identity: string }
  | { action: 'sync-resolve'; project: string; how: 'mine' | 'theirs' | 'done'; identity: string; text?: string }
  | { action: 'project-open' | 'project-remove'; project: string }
  | { action: 'scope' | 'create'; project?: string }
  | { action: 'history'; conversation: string; before?: number }
  | { action: 'select'; conversation?: string }
  | { action: 'trajectory'; conversation: string }
  | { action: 'library'; view: LibraryView; project?: string | null }
  | { action: 'library-view'; view: LibraryView; project: string | null }
  | { action: 'library-refresh' | 'library-citations' }
  | { action: 'library-documents'; query: string; more?: boolean }
  | { action: 'library-document' | 'library-memory' | 'library-chunk' | 'library-conversation'; id: string }
  | { action: 'library-stance'; claim: string }
  | { action: 'library-search'; kind: SearchKind; query: string; mode: 'lexical' | 'semantic' | 'hybrid'; more?: boolean }
  | { action: 'library-maintain'; kind: 'invalidate' | 'resolve' | 'reembed' | 'reconsider'; identity: string; reason: string; accept?: boolean }
  | { action: 'builder-outputs'; id: string }
  | { action: 'builder-trajectory'; conversation: string }
  | { action: 'builder-prepare'; project: string }
  | { action: 'builder-start'; project: string; agent: string; intent: string; revision?: string }
  | { action: 'activity'; view: ActivityView }
  | { action: 'activity-view'; view: ActivityView }
  | { action: 'activity-refresh' }
  | { action: 'inbox-read'; id: string }
  | { action: 'inbox-more' }
  | { action: 'run-detail'; id: string }
  | { action: 'schedule-refresh' }
  | { action: 'schedule-preview'; text: string; zone: string; project?: string; conversation?: string }
  | { action: 'schedule-save'; identity: string }
  | { action: 'schedule-change'; kind: 'schedule' | 'trigger'; name: string; identity: string; paused?: boolean }
  | { action: 'schedule-fire'; trigger: string; identity: string }
  | { action: 'run-definitions'; project?: string }
  | { action: 'run-record'; id: string; before?: number; kinds?: string[] }
  | { action: 'run-answer'; id: string; question: string; answer?: string; choices?: Choice[] }
  | { action: 'run-cancel'; id: string }
  | { action: 'run-trajectory'; id: string; actor: 'conductor' | 'caller' }
  | { action: 'board-inspection' | 'board-view'; view: BoardView; project?: string }
  | { action: 'board-refresh' | 'board-more' }
  | { action: 'board-topic'; topic: string }
  | { action: 'board-trajectory'; conversation: string }
  | { action: 'context'; conversation: string; agent: string }
  | { action: 'open-link'; url: string }
  | { action: 'copy-text'; text: string }
  | { action: 'run'; conversation: string; agent: string; text: string }
  | { action: 'cancel'; job: string }
  | { action: 'answer'; id: string; decision: 'once' | 'deny' };
export interface Reply { usage?: unknown; information?: unknown; state: DesktopState; conversation?: string; notice?: string; rootProject?: string; view?: ActivityView; boardView?: BoardView }
export interface DesktopApi {
  request(request: Request): Promise<Reply>;
  subscribe(listener: (state: DesktopState) => void): () => void;
}
export const homeKey = (project?: string) => project ?? '';
export const contextKey = (conversation: string, agent: string) => JSON.stringify([conversation, agent]);
export const activeJob = (job: Job) => ['starting', 'running', 'cancelling', 'unknown'].includes(job.status);
/** The server's answered bit decides success; ending names retain their wire spelling. */
export const stoppedJob = (job: Job) => job.status === 'interrupted' || (job.status === 'finished' && job.answered !== true);
declare global { interface Window { plowshare: DesktopApi } }

/** Only account-owned run detail makes an orchestration conversation inspectable. */
export function inspectionConversation(state: DesktopState, id: string): boolean {
  return state.conversations.some(row => row.id === id) || (state.library?.search.value?.kind === 'conversation' && (state.library.search.value.reply as LogSearchResponse).hits.some(hit => hit.conversationId === id)) || Object.values(state.activity.details).some(detail =>
    detail.wire?.orchestration.conductorConversation === id || detail.wire?.orchestration.callerConversation === id);
}
export function runQuestion(value: OrchestrationStatus): string {
  const latest = [...value.messages].reverse().find(message => message.kind === 'question');
  return JSON.stringify([value.orchestration.id, value.orchestration.state, value.orchestration.pendingCap, latest?.id, latest?.structure, latest?.text]);
}

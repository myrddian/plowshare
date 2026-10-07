import type { FileStoreState } from 'plowshare-client-node/filestores';
import type {
  ApplicationFileListing,
  ApplicationFileDocument,
} from 'plowshare-client-ts/operations/conversation-replies';
import type { Preference } from './renderer/preferences.ts';
import type { JobNotification } from 'plowshare-client-ts/operations/push';
import type { ServerAdminCall } from './admin-input.ts';
import type { InformationCall } from 'plowshare-client-ts/operations/information';
import type {
  UsageCallRequest,
  UsageOperation,
} from 'plowshare-client-ts/operations/usage';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import type {
  RelayPayloads,
  RelayReplies,
} from 'plowshare-client-ts/operations/relay';
import type { OperatorInput } from './operator-shared.ts';
import type {
  ScheduleDefinition,
  ScheduleFile,
} from 'plowshare-client-ts/operations/schedule-files';
import type {
  UsageReportType,
  UsageFilter,
  UsageViewState,
  ContextSnapshot,
} from 'plowshare-client-ts/operations/usage';
import type { OperatorKind, OperatorView } from './operator-shared.ts';
import type {
  InformationOperation,
  InformationScope,
} from 'plowshare-client-ts/operations/information';
import type { LogSearchResponse } from 'plowshare-client-ts/operations/retrieval';
import type {
  LibraryState,
  LibraryView,
  SearchKind,
} from './library-shared.ts';
import type {
  DefinitionView,
  OrchestrationStatus,
  ScheduleRecord,
  TriggerRecord,
  FiringRecord,
  ScheduleProposal,
} from 'plowshare-client-ts/operations/administrative-replies';
import type { RecordView } from 'plowshare-client-ts/operations/records';
import type { Choice } from 'plowshare-client-ts/operations/session';
import type { StageScope } from './run-navigation.ts';
import type { FilePresenceState } from './files-shared.ts';
import type { SyncState } from './sync-shared.ts';
import type { BoardInspection, BoardView } from './board-shared.ts';
import type {
  Agent,
  Approval,
  Conversation,
  Entry,
  Project,
  Load,
  Allowance,
  Pace,
  InboxItem,
  Run,
  RunStatus,
} from 'plowshare-client-ts/operations/client-views';
export type { Agent, Approval, Conversation, Entry, Project };

export type ProjectAccessOperation =
  | 'project.access'
  | 'project.member.add'
  | 'project.member.remove'
  | 'project.member.role';
export type ServerAdminOperation =
  | 'admin.pricing.list'
  | 'admin.pricing.set'
  | 'admin.accounts'
  | 'admin.account.create'
  | 'admin.account.update'
  | 'admin.account.reset'
  | 'admin.sessions'
  | 'admin.session.revoke'
  | 'admin.audit'
  | 'admin.service.accounts'
  | 'admin.service.account.create'
  | 'admin.service.account.update'
  | 'admin.service.tokens'
  | 'admin.service.token.create'
  | 'admin.service.token.rotate'
  | 'admin.service.token.revoke';

export interface WorkspaceRoute {
  kind:
    | 'chat'
    | 'trajectory'
    | 'activity'
    | 'library'
    | 'board'
    | 'memories'
    | 'manage'
    | 'usage'
    | 'relay';
  label: string;
  project?: string;
  conversation?: string;
  title?: string;
}
export interface WorkspaceMessage {
  route?: WorkspaceRoute;
  shortcut?: string;
}

export interface Job {
  id: string;
  conversation: string;
  agent: string;
  task: string;
  source?: 'approval' | 'information' | 'maintenance';
  revision?: string;
  logAfter?: number;
  status:
    | 'starting'
    | 'running'
    | 'cancelling'
    | 'finished'
    | 'interrupted'
    | 'unknown';
  text: string;
  thinking?: string;
  phase?: 'thinking' | 'answer' | 'tool';
  tool?: string | undefined;
  answered?: boolean;
  allowance?: Allowance | undefined;
  pace?: Pace | undefined;
  events: JobNotification[];
  ending?: string;
  detail?: string | undefined;
}
export interface History {
  entries: Entry[];
  more: boolean;
  oldest?: number;
  through?: number;
  error?: string;
}
export interface DesktopState {
  localFileStores?: FileStoreState;
  applicationFiles?: {
    project: string;
    loading: boolean;
    saving: boolean;
    listing?: ApplicationFileListing;
    document?: ApplicationFileDocument;
    error?: string;
    uncertain?: boolean;
  };
  backgroundError?: string;
  usage?: UsageViewState;
  mode: 'demo' | 'live';
  connected: boolean;
  connection: string;
  localPreferences?: Preference;
  namedConnections?: {
    key: string;
    name: string;
    server: string;
    account: string;
    reconnect: boolean;
  }[];
  selectedConnection?: string;
  base: string;
  handle: string;
  projects: Project[];
  serverAdmin?: boolean;
  personal?:
    | {
        project: string;
        root?: string;
        section?: 'In' | 'Out' | 'Resources' | 'Archive' | 'Planning' | 'Bots';
        path?: string;
        entries?: { name: string; path: string; directory: boolean }[];
        text?: string;
        note?: string;
        error?: string | undefined;
        warning?: string | undefined;
        botLatest?: Record<string, string>;
        botsError?: string;
      }
    | undefined;
  agents: Record<string, Agent[]>;
  conversations: Conversation[];
  history: Record<string, History>;
  jobs: Job[];
  approvals: Approval[];
  answeringApprovals?: string[];
  contextSnapshots?: Record<
    string,
    { value?: ContextSnapshot; loading?: boolean; error?: string }
  >;
  contexts: Record<string, ContextReading>;
  board: BoardInspection;
  files: FilePresenceState;
  sync?: SyncState | undefined;
  localMachine?: string;
  projectPreparing?: string[];
  projectFolders?: {
    name: string;
    path: string;
    machine: string;
    enabled: boolean;
    connected: boolean;
    files: FilePresenceState;
    sync?: SyncState;
    error?: string;
  }[];
  projectConfigError?: string | undefined;
  connectionPersistenceError?: string;
  jobRecoveryError?: string;
  projectListError?: string | undefined;
  liveHistory?:
    | {
        status: 'updating' | 'ready' | 'unavailable';
        detail?: string;
      }
    | undefined;
  activity: AccountActivity;
  library?: LibraryState;
  operator?: OperatorView | undefined;
  authoring?:
    | {
        project: string;
        agent: string;
        intent: string;
        revision?: string;
        conversation?: string;
        status: 'launching' | 'submitted' | 'unknown';
        error?: string;
      }
    | undefined;
}
export type ActivityView =
  'inbox' | 'runs' | 'definitions' | 'schedules' | 'builder';
export interface AccountActivity {
  view?: ActivityView | undefined;
  inbox: {
    items: InboxItem[];
    read: string[];
    unread?: number | undefined;
    more?: boolean;
    loaded: boolean;
    loading?: boolean;
    error?: string;
  };
  runs: {
    items: Run[];
    loaded: boolean;
    loading?: boolean;
    error?: string;
    limited?: boolean;
  };
  details: Record<
    string,
    {
      value?: RunStatus;
      wire?: OrchestrationStatus;
      loading?: boolean;
      error?: string;
    }
  >;
  definitions?: {
    project?: string;
    items: readonly DefinitionView[];
    loading?: boolean;
    error?: string;
  };
  records?: Record<
    string,
    {
      root: string;
      rows: readonly RecordView[];
      through: number;
      oldest: number | null;
      more: boolean;
      kinds?: readonly string[];
      loading?: boolean;
      error?: string;
    }
  >;
  navigation?: Record<
    string,
    { rows: readonly RecordView[]; loading?: boolean; error?: string }
  >;
  schedules?: {
    files?: readonly ScheduleFile[];
    sourceProject?: string;
    schedules: readonly ScheduleRecord[];
    triggers: readonly TriggerRecord[];
    firings: readonly FiringRecord[];
    proposal?: ScheduleProposal;
    loading?: boolean;
    previewing?: boolean;
    busy?: boolean;
    error?: string;
    previewError?: string;
    notice?: string;
  };
  decisions?: Record<
    string,
    { busy?: boolean; error?: string; notice?: string }
  >;
}
export const emptyActivity = (): AccountActivity => ({
  inbox: { items: [], read: [], loaded: false },
  runs: { items: [], loaded: false },
  details: {},
});
export interface ContextReading extends Load {
  status: 'ready' | 'unavailable';
  detail?: string;
  sample?: boolean;
}
export type Request =
  | { action: 'filestore-load' }
  | { action: 'filestore-choose' }
  | { action: 'filestore-setup'; alias: string; root: string }
  | { action: 'application-runtime'; project: string }
  | { action: 'application-files'; project: string; path?: string }
  | { action: 'application-file-read'; project: string; path: string }
  | {
      action: 'application-file-save';
      project: string;
      path: string;
      text: string;
      revision: string;
    }
  | { action: 'usage' }
  | {
      action: 'context-snapshot';
      conversation: string;
      agent: string;
      measure?: boolean;
    }
  | { action: 'relay' }
  | {
      action: 'relay-read';
      type: 'relay.topics';
      payload: RelayPayloads['relay.topics'];
    }
  | {
      action: 'relay-read';
      type: 'relay.log';
      payload: RelayPayloads['relay.log'];
    }
  | { action: 'relay-operate'; payload: RelayPayloads['relay.operate'] }
  | { action: 'relay-trajectory'; conversation: string }
  | { action: 'usage-open'; type: UsageReportType; filter: UsageFilter }
  | (UsageCallRequest & { action: 'usage-read' })
  | { action: 'usage-close' }
  | { action: 'operator-prepare'; kind: OperatorKind; project?: string }
  | {
      action: 'operator-messages';
      identity: string;
      instance: string;
      offset?: number;
    }
  | {
      action: 'operator-preview';
      identity: string;
      input: OperatorInput;
    }
  | { action: 'operator-apply'; identity: string }
  | (InformationCall & { action: 'information'; scope: InformationScope })
  | {
      action:
        'bootstrap' | 'demo' | 'disconnect' | 'refresh' | 'approvals-refresh';
    }
  | {
      action: 'project-access';
      operation: ProjectAccessOperation;
      project: string;
      handle?: string;
      role?: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER';
    }
  | (ServerAdminCall & { action: 'server-admin' })
  | {
      action: 'server-project-create';
      name: string;
      workspace?: string;
      type?: 'MANAGED' | 'DISJOINT';
      writePaths?: string[];
    }
  | {
      action: 'server-setup';
      base: string;
      temporaryPassword: string;
      handle: string;
      password: string;
    }
  | {
      action: 'connect';
      base: string;
      handle: string;
      password: string;
      name?: string;
    }
  | { action: 'connection-select'; name: string }
  | {
      action: 'connection-preferences';
      server: string;
      account: string;
      preference: Preference;
    }
  | { action: 'connection-rename'; name: string; nextName: string }
  | { action: 'connection-remove'; name: string }
  | {
      action: 'personal-section';
      section: 'In' | 'Out' | 'Resources' | 'Archive' | 'Planning' | 'Bots';
      path?: string;
    }
  | { action: 'personal-bots' }
  | { action: 'personal-recreate' }
  | { action: 'files-choose'; project?: string }
  | { action: 'files-withdraw'; project?: string }
  | { action: 'sync-refresh'; project: string }
  | { action: 'sync-inspect'; project: string; path: string }
  | {
      action: 'sync-change';
      project: string;
      kind: 'on' | 'off' | 'now';
      identity: string;
    }
  | {
      action: 'sync-resolve';
      project: string;
      how: 'mine' | 'theirs' | 'done';
      identity: string;
      text?: string;
    }
  | { action: 'project-open' | 'project-remove'; project: string }
  | { action: 'scope' | 'create'; project?: string }
  | { action: 'history'; conversation: string; before?: number }
  | { action: 'select'; conversation?: string }
  | { action: 'trajectory'; conversation: string }
  | { action: 'board-post-topics'; project: string; more?: boolean }
  | {
      action: 'board-create';
      project: string;
      title: string;
      label: string;
      body: string;
      requestId: string;
      maxModelCalls?: number;
    }
  | {
      action: 'board-retry';
      project: string;
      topic: string;
      member: string;
      requestId: string;
      maxTurns: number;
      reconcile?: boolean;
    }
  | {
      action: 'board-post';
      project: string;
      topic: string;
      body: string;
      requestId: string;
    }
  | { action: 'workspace-chat'; manage?: boolean }
  | {
      action: 'workspace-layout';
      x: number;
      y: number;
      width: number;
      height: number;
      visible: boolean;
    }
  | { action: 'workspace-refresh' }
  | {
      action: 'library';
      view: LibraryView;
      project?: string | null;
      revision?: string;
      chapter?: string;
    }
  | {
      action: 'library-view';
      view: LibraryView;
      project: string | null;
      revision?: string;
      chapter?: string;
    }
  | { action: 'library-refresh' | 'library-citations' }
  | { action: 'library-documents'; query: string; more?: boolean }
  | {
      action:
        | 'library-document'
        | 'library-memory'
        | 'library-chunk'
        | 'library-conversation';
      id: string;
    }
  | { action: 'library-source-text'; id: string; offset: number }
  | { action: 'library-stance'; claim: string }
  | {
      action: 'library-search';
      kind: SearchKind;
      query: string;
      mode: 'lexical' | 'semantic' | 'hybrid';
      more?: boolean;
    }
  | {
      action: 'library-maintain';
      kind: 'invalidate' | 'resolve' | 'reembed' | 'reconsider';
      identity: string;
      reason: string;
      accept?: boolean;
    }
  | { action: 'builder-outputs'; id: string }
  | { action: 'builder-trajectory'; conversation: string }
  | { action: 'builder-prepare'; project: string }
  | {
      action: 'builder-start';
      project: string;
      agent: string;
      intent: string;
      revision?: string;
    }
  | { action: 'activity'; view: ActivityView }
  | { action: 'activity-view'; view: ActivityView }
  | { action: 'activity-refresh' }
  | { action: 'question-refresh' }
  | { action: 'inbox-read'; id: string }
  | { action: 'inbox-more' }
  | { action: 'run-detail'; id: string }
  | { action: 'schedule-refresh' }
  | {
      action: 'schedule-preview';
      text: string;
      zone: string;
      project?: string;
      conversation?: string;
    }
  | {
      action: 'schedule-save';
      identity: string;
      definition?: ScheduleDefinition;
      source?: 'server' | 'workspace';
      project?: string;
    }
  | {
      action: 'schedule-file-save';
      name: string;
      project?: string;
      source: 'server' | 'workspace';
      definition: ScheduleDefinition;
      overwrite: boolean;
      identity?: string;
    }
  | {
      action: 'schedule-sync';
      project?: string;
      source: 'server' | 'workspace';
    }
  | {
      action: 'schedule-change';
      kind: 'schedule' | 'trigger';
      name: string;
      identity: string;
      paused?: boolean;
    }
  | { action: 'schedule-fire'; trigger: string; identity: string }
  | { action: 'run-definitions'; project?: string }
  | { action: 'run-record'; id: string; before?: number; kinds?: string[] }
  | {
      action: 'run-answer';
      id: string;
      question: string;
      answer?: string;
      choices?: Choice[];
    }
  | { action: 'run-cancel'; id: string }
  | { action: 'run-resume'; id: string }
  | { action: 'run-trajectory'; id: string; actor: 'conductor' | 'caller' }
  | { action: 'run-stage-trajectory'; id: string; stage: string }
  | { action: 'delegate-trajectory'; conversation: string; step: string }
  | {
      action: 'board-inspection' | 'board-view';
      view: BoardView;
      project?: string;
    }
  | { action: 'board-refresh' | 'board-more' }
  | { action: 'board-topic'; topic: string }
  | { action: 'board-trajectory'; conversation: string }
  | { action: 'context'; conversation: string; agent: string }
  | { action: 'open-link'; url: string }
  | { action: 'copy-text'; text: string }
  | {
      action: 'workflow-start';
      conversation: string;
      agent: string;
      definition: string;
      text: string;
      requestId: string;
    }
  | { action: 'run'; conversation: string; agent: string; text: string }
  | { action: 'cancel'; job: string }
  | { action: 'answer'; id: string; decision: 'once' | 'deny' };
export interface Reply {
  fileStoreRoot?: string;
  relay?: RelayReplies['relay.topics' | 'relay.log'];
  relayControl?: RelayReplies['relay.operate'];
  administration?: Replies[ServerAdminOperation | ProjectAccessOperation];
  usage?: Replies[UsageOperation];
  information?: Replies[`information.${InformationOperation}`];
  state: DesktopState;
  conversation?: string;
  stageScope?: StageScope;
  notice?: string;
  rootProject?: string;
  view?: ActivityView;
  boardView?: BoardView;
  route?: WorkspaceRoute;
  libraryScreen?: 'library' | 'memories';
}
export interface DesktopApi {
  request(request: Request): Promise<Reply>;
  subscribe(listener: (state: DesktopState) => void): () => void;
  subscribeWorkspace?(
    listener: (message: WorkspaceMessage) => void,
  ): () => void;
}
export const homeKey = (project?: string) => project ?? '';
export const contextKey = (conversation: string, agent: string) =>
  JSON.stringify([conversation, agent]);
export const activeJob = (job: Job) =>
  ['starting', 'running', 'cancelling', 'unknown'].includes(job.status);
/** The server's answered bit decides success; ending names retain their wire spelling. */
export const stoppedJob = (job: Job) =>
  job.status === 'interrupted' ||
  (job.status === 'finished' && job.answered !== true);
declare global {
  interface Window {
    plowshare: DesktopApi;
  }
}

/** Only account-owned run detail makes an orchestration conversation inspectable. */
export function inspectionConversation(
  state: DesktopState,
  id: string,
): boolean {
  const known = new Set(state.conversations.map((row) => row.id));
  if (state.library?.search.value?.kind === 'conversation')
    for (const hit of (state.library.search.value.reply as LogSearchResponse)
      .hits)
      known.add(hit.conversationId);
  for (const detail of Object.values(state.activity.details))
    for (const conversation of [
      detail.wire?.orchestration.conductorConversation,
      detail.wire?.orchestration.callerConversation,
    ])
      if (conversation) known.add(conversation);
  // Only traverse histories reachable from an account-owned inspection surface.
  for (const parent of known)
    for (const entry of state.history[parent]?.entries ?? [])
      for (const call of entry.calls ?? [])
        if (call.opened?.conversation) known.add(call.opened.conversation);
  return known.has(id);
}
export function runQuestion(value: OrchestrationStatus): string {
  const latest = [...value.messages]
    .reverse()
    .find((message) => message.kind === 'question');
  return JSON.stringify([
    value.orchestration.id,
    value.orchestration.state,
    value.orchestration.pendingCap,
    latest?.id,
    latest?.structure,
    latest?.text,
  ]);
}

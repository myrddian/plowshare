import type { ScheduleFile } from './schedule-files.ts';
import type { Outcome } from '../binding/envelope.ts';
import type { OrchestrationChoice } from './administration.ts';
import type { Question } from './inspection.ts';
export type MessageStructure =
  | {
      readonly lead: string;
      readonly questions: readonly Question[];
      readonly choices?: never;
      readonly name?: string;
      readonly path?: string;
      readonly text?: string;
      readonly sha256?: string;
    }
  | {
      readonly lead?: never;
      readonly questions?: never;
      readonly choices: readonly OrchestrationChoice[];
      readonly name?: never;
      readonly path?: never;
      readonly text?: never;
      readonly sha256?: never;
    };
import { isBoardMessage, type BoardMessage } from './board.ts';
import {
  object,
  text,
  named,
  bool,
  count,
  nullable,
  list,
  record,
  strings,
  json,
  noContent,
  type Check,
} from './wire-checks.ts';

/** Complete server wire records. Nullable values and future fields remain intact. */
export interface ApprovalView {
  readonly id: string;
  readonly conversation: string;
  readonly askedIn: string;
  readonly agent: string;
  readonly side: string;
  readonly command: readonly string[];
  readonly cwd: string;
  readonly reason: string;
  readonly state: string;
  readonly scope: string | null;
  readonly prefix: readonly string[] | null;
  readonly defaultPrefix: readonly string[];
  readonly createdAt: string;
  readonly answeredAt: string | null;
  readonly commands: readonly (readonly string[])[] | null;
  readonly judged: string | null;
}
export interface ApprovalListed {
  readonly approvals: readonly ApprovalView[];
}
export interface ApprovalAnswered {
  readonly id: string;
  readonly state: string;
  readonly job: string | null;
  readonly busy: boolean;
  readonly note: string | null;
}
export interface ApprovalRevoked {
  readonly id: string;
  readonly revoked: boolean;
}
export interface BoardTopic {
  readonly id: string;
  readonly project: string;
  readonly parent: string | null;
  readonly root: string;
  readonly depth: number;
  readonly title: string;
  readonly label: string;
  readonly account: string;
  readonly openerKind: string;
  readonly opener: string;
  readonly originConversation: string | null;
  readonly state: string;
  readonly resolution: string | null;
  readonly potTotal: number | null;
  readonly potSpent: number | null;
  readonly reserve: number | null;
  readonly quietNotifiedAt: string | null;
  readonly openedAt: string;
  readonly closedAt: string | null;
}
export interface BufferPurgeReport {
  readonly fetchedPages: number;
  readonly resultSets: number;
}
export interface SweepReport {
  readonly marked: number;
  readonly conversations: number;
  readonly payloads: number;
  readonly characters: number;
  readonly prunedJobs: number;
}
export interface ProviderFacts {
  readonly providerKey: string;
  readonly name: string;
  readonly version: string;
  readonly description: string | null;
  readonly verbs: readonly string[];
  readonly costClass: string;
  readonly networkTier: string;
  readonly maxResults: number;
  readonly maxQueryLength: number;
  readonly domainExclusion: boolean;
}
export interface ProviderRegistration {
  readonly providerKey: string;
  readonly baseUrl: string;
  readonly facts: ProviderFacts;
  readonly lastHealthAt: string | null;
  readonly lastHealthStatus: string | null;
  readonly consecutiveFailures: number;
}
export interface InboxItem {
  readonly id: string;
  readonly handle: string;
  readonly kind: string;
  readonly firing: string | null;
  readonly conversation: string | null;
  readonly ending: string | null;
  readonly answer: string | null;
  readonly arrivedAt: string;
  readonly readAt: string | null;
  readonly about?: string | null;
}
export interface InboxPage {
  readonly items: readonly InboxItem[];
  readonly unread: number;
}
export interface InboxMarked {
  readonly marked: number;
  readonly unread: number;
}
export interface TodoView {
  readonly id: string;
  readonly parent: string | null;
  readonly position: number;
  readonly text: string;
  readonly status: string;
  readonly summary: string | null;
  readonly locked: boolean;
  readonly stage: string | null;
  readonly updatedAt: string;
}
export interface StageView {
  readonly id: string;
  readonly doneWhen: string;
  readonly mayReturnTo: readonly string[];
}
export interface DefinitionView {
  readonly name: string;
  readonly description: string | null;
  readonly tier: string | null;
  readonly stages: readonly StageView[];
  readonly triggers: readonly string[];
  readonly served: boolean;
  readonly withheld: string | null;
}
export interface RunView {
  readonly id: string;
  readonly definition: string;
  readonly tier: string;
  readonly project: string | null;
  readonly state: string;
  readonly pendingCap: string | null;
  readonly result: string | null;
  readonly failure: string | null;
  readonly returnsUsed: number;
  readonly maxReturns: number;
  readonly nudges: number;
  readonly restarts: number;
  readonly callerAgent: string;
  readonly callerConversation: string | null;
  readonly conductorConversation: string;
  readonly parent: string | null;
  readonly depth: number;
  readonly waitingFor: string | null;
  readonly createdAt: string;
  readonly endedAt: string | null;
  readonly stalledSince: string | null;
}
export interface ChildView {
  readonly id: string;
  readonly state: string;
}
export interface MessageView {
  readonly id: string;
  readonly kind: string;
  readonly text: string;
  readonly author: string;
  readonly createdAt: string;
  readonly deliveredAt: string | null;
  readonly capKind: string | null;
  readonly structure: MessageStructure | null;
}
export interface Definitions {
  readonly definitions: readonly DefinitionView[];
}
export interface OrchestrationListed {
  readonly orchestrations: readonly RunView[];
}
export interface OrchestrationStatus {
  readonly orchestration: RunView;
  readonly todos: readonly TodoView[];
  readonly messages: readonly MessageView[];
  readonly children: readonly ChildView[];
}
export interface OrchestrationAnswered {
  readonly id: string;
  readonly state: string;
}
export interface OrchestrationCancelled {
  readonly id: string;
  readonly state: string;
}
export interface SettingView {
  readonly value: number | null;
  readonly source: string;
}
export interface BooleanSettingView {
  readonly value: boolean | null;
  readonly source: string;
}
export interface CapsView {
  readonly project: string;
  readonly steps: SettingView;
  readonly budget: SettingView;
  readonly autoContinue: SettingView;
  readonly time: SettingView;
  readonly failedChecks: SettingView;
  readonly autoIncrease: BooleanSettingView;
  readonly applied: number;
  readonly said: string | null;
}
export interface ScheduleRecord {
  readonly name: string;
  readonly cron: string;
  readonly zone: string;
  readonly emits: string;
  readonly paused: boolean;
  readonly nextFireAt: string;
  readonly definedBy: string;
}
export interface ScheduleNames {
  readonly schedule: string;
  readonly trigger: string;
  readonly event: string;
}
export interface ScheduleProposal {
  readonly cron: string;
  readonly zone: string;
  readonly when: string;
  readonly agent: string;
  readonly task: string;
  readonly intoConversation: boolean;
  readonly project: string | null;
  readonly conversation: string | null;
  readonly nextFires: readonly string[];
  readonly names: ScheduleNames;
}
export interface TriggerRecord {
  readonly name: string;
  readonly event: string;
  readonly project: string | null;
  readonly conversation: string | null;
  readonly agent: string;
  readonly task: string;
  readonly maxModelCalls: number | null;
  readonly maxTurns: number | null;
  readonly queueCap: number;
  readonly paused: boolean;
  readonly definedBy: string;
}
export interface FiringRecord {
  readonly id: string;
  readonly event: string;
  readonly data: string;
  readonly schedule: string | null;
  readonly fireAt: string | null;
  readonly trigger: string | null;
  readonly target: string | null;
  readonly status: string;
  readonly supersededBy: string | null;
  readonly reason: string | null;
  readonly jobId: string | null;
  readonly arrivedAt: string;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
  readonly topic: string | null;
}
export interface BoardOpened {
  readonly requestId: string;
  readonly topic: BoardTopic;
  readonly message: BoardMessage;
}
export interface BoardRetried {
  readonly requestId: string;
  readonly member: string;
  readonly maxTurns: number;
  readonly message: BoardMessage;
}
export interface BoardPosted {
  readonly requestId: string;
  readonly message: BoardMessage;
}
export interface AdministrativeReplies {
  'approval.list': ApprovalListed;
  'approval.answer': ApprovalAnswered;
  'approval.revoke': ApprovalRevoked;
  'board.topup': BoardTopic;
  'board.open': BoardOpened;
  'board.retry': BoardRetried;
  'board.post': BoardPosted;
  'buffer.purge': BufferPurgeReport;
  'retention.sweep': SweepReport;
  'provider.list': readonly ProviderRegistration[];
  'provider.deregister': null;
  'inbox.list': InboxPage;
  'inbox.read': InboxMarked;
  'todos.read': readonly TodoView[];
  'orchestration.definitions': Definitions;
  'orchestration.list': OrchestrationListed;
  'orchestration.status': OrchestrationStatus;
  'orchestration.answer': OrchestrationAnswered;
  'orchestration.cancel': OrchestrationCancelled;
  'orchestration.resume': OrchestrationAnswered;
  'orchestration.caps': CapsView;
  'schedule.save': ScheduleFile;
  'schedule.sync': readonly ScheduleFile[];
  'schedule.files': readonly ScheduleFile[];
  'schedule.list': readonly ScheduleRecord[];
  'schedule.define': ScheduleRecord;
  'schedule.read': ScheduleProposal;
  'schedule.pause': null;
  'schedule.forget': null;
  'trigger.list': readonly TriggerRecord[];
  'trigger.define': TriggerRecord;
  'trigger.pause': null;
  'trigger.forget': null;
  'event.fire': readonly FiringRecord[];
  'firing.list': readonly FiringRecord[];
}
export type AdministrativeOperation = keyof AdministrativeReplies;

const approval = record({
  id: named,
  conversation: named,
  askedIn: text,
  agent: named,
  side: text,
  command: strings,
  cwd: text,
  reason: text,
  state: named,
  scope: nullable(text),
  prefix: nullable(strings),
  defaultPrefix: strings,
  createdAt: named,
  answeredAt: nullable(named),
  commands: nullable(list(strings)),
  judged: nullable(text),
});
export const boardTopicCheck: Check = record({
  id: named,
  project: named,
  parent: nullable(named),
  root: named,
  depth: count,
  title: text,
  label: text,
  account: named,
  openerKind: named,
  opener: named,
  originConversation: nullable(named),
  state: named,
  resolution: nullable(text),
  potTotal: nullable(count),
  potSpent: nullable(count),
  reserve: nullable(count),
  quietNotifiedAt: nullable(named),
  openedAt: named,
  closedAt: nullable(named),
});
const facts = record({
  providerKey: named,
  name: text,
  version: text,
  description: nullable(text),
  verbs: strings,
  costClass: named,
  networkTier: named,
  maxResults: count,
  maxQueryLength: count,
  domainExclusion: bool,
});
const provider = record(
  {
    providerKey: named,
    baseUrl: named,
    facts,
    lastHealthAt: nullable(named),
    lastHealthStatus: nullable(named),
    consecutiveFailures: count,
  },
  (row) =>
    object(row['facts']) && row['providerKey'] === row['facts']['providerKey'],
);
const inbox = record(
  {
    id: named,
    handle: named,
    kind: named,
    firing: nullable(named),
    conversation: nullable(named),
    ending: nullable(text),
    answer: nullable(text),
    arrivedAt: named,
    readAt: nullable(named),
  },
  (row) => row['about'] === undefined || nullable(text)(row['about']),
);
const todo = record({
  id: named,
  parent: nullable(named),
  position: count,
  text,
  status: named,
  summary: nullable(text),
  locked: bool,
  stage: nullable(text),
  updatedAt: named,
});
const stage = record({ id: named, doneWhen: text, mayReturnTo: strings });
const definition = record({
  name: named,
  description: nullable(text),
  tier: nullable(text),
  stages: list(stage),
  triggers: strings,
  served: bool,
  withheld: nullable(text),
});
const run = record({
  id: named,
  definition: named,
  tier: named,
  project: nullable(named),
  state: named,
  pendingCap: nullable(text),
  result: nullable(text),
  failure: nullable(text),
  returnsUsed: count,
  maxReturns: count,
  nudges: count,
  restarts: count,
  callerAgent: text,
  callerConversation: nullable(named),
  conductorConversation: text,
  parent: nullable(named),
  depth: count,
  waitingFor: nullable(text),
  createdAt: named,
  endedAt: nullable(named),
  stalledSince: nullable(named),
});
const message = record({
  id: named,
  kind: named,
  text,
  author: text,
  createdAt: named,
  deliveredAt: nullable(named),
  capKind: nullable(text),
  structure: json,
});
const setting = record({ value: nullable(count), source: text });
const scheduleAction = record({
  kind: (value) =>
    ['agent', 'skill', 'orchestration'].includes(value as string),
  agent: named,
  name: nullable(named),
  input: text,
  mode: nullable((value) =>
    ['INHERITED', 'SUMMARISED', 'NEW', 'DIRECT'].includes(value as string),
  ),
});
const scheduleTarget = record({
  kind: (value) =>
    ['mailbox', 'conversation', 'message'].includes(value as string),
  project: nullable(named),
  conversation: nullable(named),
  to: nullable(named),
  route: nullable(named),
});
const scheduleDefinition = record({
  version: (value) => value === 1,
  cron: named,
  zone: named,
  paused: bool,
  action: scheduleAction,
  target: scheduleTarget,
  limits: record({
    maxModelCalls: nullable(count),
    maxTurns: nullable(count),
    queueCap: count,
  }),
});
const scheduleFile = record({
  name: named,
  project: nullable(named),
  source: (value) => ['server', 'workspace'].includes(value as string),
  path: named,
  internalName: named,
  definition: nullable(scheduleDefinition),
  status: (value) => ['active', 'refused'].includes(value as string),
  error: nullable(text),
});
const schedule = record({
  name: named,
  cron: named,
  zone: named,
  emits: named,
  paused: bool,
  nextFireAt: named,
  definedBy: named,
});
const proposal = record(
  {
    cron: named,
    zone: named,
    when: text,
    agent: named,
    task: text,
    intoConversation: bool,
    project: nullable(named),
    conversation: nullable(named),
    nextFires: list(named),
    names: record({ schedule: named, trigger: named, event: named }),
  },
  (row) =>
    row['intoConversation'] === (row['conversation'] !== null) &&
    (row['intoConversation'] !== true || row['project'] === null),
);
const trigger = record({
  name: named,
  event: named,
  project: nullable(named),
  conversation: nullable(named),
  agent: named,
  task: text,
  maxModelCalls: nullable(count),
  maxTurns: nullable(count),
  queueCap: count,
  paused: bool,
  definedBy: named,
});
const firing = record({
  id: named,
  event: named,
  data: text,
  schedule: nullable(named),
  fireAt: nullable(named),
  trigger: nullable(named),
  target: nullable(named),
  status: named,
  supersededBy: nullable(named),
  reason: nullable(text),
  jobId: nullable(named),
  arrivedAt: named,
  startedAt: nullable(named),
  finishedAt: nullable(named),
  topic: nullable(named),
});
const readers = {
  'approval.list': record({ approvals: list(approval) }),
  'approval.answer': record({
    id: named,
    state: named,
    job: nullable(named),
    busy: bool,
    note: nullable(text),
  }),
  'approval.revoke': record({ id: named, revoked: bool }),
  'board.topup': boardTopicCheck,
  'board.open': record({
    requestId: named,
    topic: boardTopicCheck,
    message: isBoardMessage,
  }),
  'board.retry': record({
    requestId: named,
    member: named,
    maxTurns: (value) => count(value) && (value as number) > 0,
    message: isBoardMessage,
  }),
  'board.post': record({ requestId: named, message: isBoardMessage }),
  'buffer.purge': record({ fetchedPages: count, resultSets: count }),
  'retention.sweep': record({
    marked: count,
    conversations: count,
    payloads: count,
    characters: count,
    prunedJobs: count,
  }),
  'provider.list': list(provider),
  'provider.deregister': noContent,
  'inbox.list': record({ items: list(inbox), unread: count }),
  'inbox.read': record({ marked: count, unread: count }),
  'todos.read': list(todo),
  'orchestration.definitions': record({ definitions: list(definition) }),
  'orchestration.list': record({ orchestrations: list(run) }),
  'orchestration.status': record({
    orchestration: run,
    todos: list(todo),
    messages: list(message),
    children: list(record({ id: named, state: named })),
  }),
  'orchestration.answer': record({ id: named, state: named }),
  'orchestration.cancel': record({ id: named, state: named }),
  'orchestration.resume': record({ id: named, state: named }),
  'orchestration.caps': record({
    project: named,
    steps: setting,
    budget: setting,
    autoContinue: setting,
    time: setting,
    failedChecks: setting,
    autoIncrease: record({ value: nullable(bool), source: text }),
    applied: count,
    said: nullable(text),
  }),
  'schedule.save': scheduleFile,
  'schedule.sync': list(scheduleFile),
  'schedule.files': list(scheduleFile),
  'schedule.list': list(schedule),
  'schedule.define': schedule,
  'schedule.read': proposal,
  'schedule.pause': noContent,
  'schedule.forget': noContent,
  'trigger.list': list(trigger),
  'trigger.define': trigger,
  'trigger.pause': noContent,
  'trigger.forget': noContent,
  'event.fire': list(firing),
  'firing.list': list(firing),
} satisfies Record<AdministrativeOperation, Check>;
const emptyOperations: readonly AdministrativeOperation[] = [
  'provider.deregister',
  'schedule.pause',
  'schedule.forget',
  'trigger.pause',
  'trigger.forget',
];
export const ADMINISTRATIVE_OPERATIONS = Object.keys(
  readers,
) as readonly AdministrativeOperation[];
export function isAdministrativeOperation(
  type: string,
): type is AdministrativeOperation {
  return Object.hasOwn(readers, type);
}
/** One complete outcome, never a filtered/defaulted snapshot. NO_CONTENT is an acknowledgement, not an empty list. */
export function administrativeReply<T extends AdministrativeOperation>(
  type: T,
  outcome: Outcome,
): (Outcome & { readonly payload?: AdministrativeReplies[T] }) | undefined {
  return outcome.code ===
    (emptyOperations.includes(type) ? 'NO_CONTENT' : 'OK') &&
    readers[type](outcome.payload)
    ? (outcome as Outcome & { readonly payload?: AdministrativeReplies[T] })
    : undefined;
}

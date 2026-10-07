import { errorMessage } from 'plowshare-client-ts/binding/values';
import { isList } from 'plowshare-client-ts/binding/values';
export { savingSchedule } from 'plowshare-client-ts/operations/session';
import { parseCommand } from 'plowshare-client-ts/operations/commands';
import type { Request as MessageRequest } from 'plowshare-client-ts/operations/direct';
import { usageCommand, type UsageCommand } from './usage.ts';
import { informationCommand } from './information.ts';
import type { InformationCommand } from './information.ts';
import { flagAt } from 'plowshare-client-ts/operations/inspection';
export {
  inboxPageOf,
  runsOf,
  runStatusOf,
  unreadOf,
  changedOf,
  loadOf,
  reached,
} from 'plowshare-client-ts/operations/inspection';
import type {
  Run,
  Structure,
  Message,
  RunStatus,
} from 'plowshare-client-ts/operations/inspection';
export type {
  Run,
  RunStage,
  QuestionOption,
  Question,
  Draft,
  Structure,
  Message,
  Child,
  RunStatus,
  Allowance,
  Ended,
  Pace,
  Load,
  InboxItem,
  InboxPage,
} from 'plowshare-client-ts/operations/inspection';
import type {
  Agent,
  Approval,
  Entry,
} from 'plowshare-client-ts/operations/views';
export {
  projects,
  conversations,
  agents,
  opened,
  continued,
  approvalsOf,
  answeredOf,
  revokedOf,
  entriesOf,
  backPageOf,
  logThrough,
  logTotal,
  streamed,
  appendedOf,
} from 'plowshare-client-ts/operations/views';
export type {
  Conversation,
  Project,
  Agent,
  Approval,
  Answered,
  Entry,
  Opened,
  Asked,
  BackPage,
  Delta,
  Appended,
} from 'plowshare-client-ts/operations/views';
import {
  OK,
  bodyOf,
  countAt,
  fieldsOf,
  textAt,
} from 'plowshare-client-ts/operations/response';
import type { Answer } from 'plowshare-client-ts/operations/response';
export {
  OK,
  bodyOf,
  countAt,
  fieldsOf,
  textAt,
} from 'plowshare-client-ts/operations/response';
export type { ApplicationAnswer as Answer } from 'plowshare-client-ts/operations/response';
import {
  AGENT_RUN,
  listingInbox,
  type Decision,
  answeringApproval,
  revokingApproval,
  listingDefinitions,
  listingRuns,
  readingRun,
  answeringRun,
  cancellingRun,
  type Proposal,
  listingFirings,
  type Ask,
  speaking,
  listingProjects,
  listingConversations,
  listingAgents,
} from 'plowshare-client-ts/operations/session';
export * from 'plowshare-client-ts/operations/session';
import { retrievalCommand, retrievalWords } from './retrieval.ts';
import type { RetrievalCommand } from './retrieval.ts';
import { parse } from './markdown.ts';
import type { Block } from './markdown.ts';
import {
  MEMORY_COMMAND,
  SEARCH_COMMAND,
  JOB_COMMAND,
  BOARD_COMMAND,
  SWARM_COMMAND,
  AGENTS_COMMAND,
  ALWAYS_COMMAND,
  ANSWER_COMMAND,
  APPROVALS_COMMAND,
  BOTS_COMMAND,
  CANCEL_COMMAND,
  CAP_COMMAND,
  CD_COMMAND,
  COMMANDS,
  CONVERSATIONS_COMMAND,
  FIRE_COMMAND,
  DIAGNOSE_COMMAND,
  EARLIER_COMMAND,
  FIRINGS_COMMAND,
  HELP_COMMAND,
  HERE_COMMAND,
  INBOX_COMMAND,
  LOG_COMMAND,
  ORCHESTRATIONS_COMMAND,
  PROJECT_COMMAND,
  PROJECTS_COMMAND,
  RUNS_COMMAND,
  WATCH_COMMAND,
  SCHEDULE_COMMAND,
  SYNC_COMMAND,
  THEME_COMMAND,
  TRAJECTORY_COMMAND,
  refusal,
} from './wording.ts';
import { type SyncAction, syncAction } from './union.ts';

/*
 * ASKING A PERSON BEFORE A COMMAND RUNS. Spec 2026-09-15, §4.
 *
 * A run whose `run` call needs a person ends its turn `AWAITING` and leaves an
 * `asked` row behind. Nothing waits in memory: these three frames find the
 * question, answer it — which starts the continuing turn — and take back a
 * standing project approval. `approval.ts` is the key-by-key half.
 */

/** How a turn that stopped to ask a person ends. {@link listingAsked} finds what it asked. */
export const AWAITING = 'AWAITING';

/*
 * THE OTHER TWO FRAMES, WHICH CHANGE SOMETHING. Spec 2026-09-13, §7.
 *
 * <p>The block above said these two "stay routed and unspoken here — a person
 * who is going to answer a conductor's question or stop a run needs first to be
 * able to see it". Seeing it turned out not to be enough, and the reason is
 * measured rather than argued: on the first live orchestration run, {@code
 * implement_specification} asked its caller a question, the delivery told the
 * caller bot in as many words to answer it with {@code orchestration_answer} and
 * gave it the id, and the bot wrote a specification at the person instead. The
 * run sat in {@code asking} until somebody went and told the bot again.
 *
 * <p><b>That is what makes these two commands and not a convenience.</b> The
 * design routes a root run's question through the caller, which is a model; a
 * person with no way to answer a run directly depends on that model choosing to
 * pass a message along, and it is free not to.
 */

/** How every run id starts: {@code OrchestrationStore.PREFIX}. */
export const RUN_ID_PREFIX = 'orc_';

/**
 * What a run's state became, off an `orchestration.answer` or `.cancel` answer,
 * or nothing for a refusal.
 *
 * <p><b>One reader for both frames, because the server sends one shape.</b>
 * `Answered` and `Cancelled` are distinct records carrying the same two
 * components, and a second reader here would be a second thing to keep in step
 * with a single wire shape. `mirrors-the-server` pins both.
 */
export function settledRun(
  answer: Answer,
): { readonly id: string; readonly state: string } | undefined {
  const body = bodyOf(answer, OK);
  if (body === undefined) {
    return undefined;
  }
  const fields = fieldsOf(body);
  const id = textAt(fields, 'id');
  // The state and not just the id: "answered, and it is running again" and
  // "answered, and it is waiting on a child" are different things to be told,
  // and the id alone cannot say which.
  const state = textAt(fields, 'state');
  return id === undefined || state === undefined ? undefined : { id, state };
}

/** One stage of a definition, as a person reads it — never evaluated here. */
export interface Stage {
  readonly id: string;

  /** What the definition says finishes it. Absent where it says nothing. */
  readonly doneWhen?: string;
}

/**
 * One orchestration that could be started, or one file that could not be read —
 * `OrchestrationFrames.DefinitionView`.
 *
 * <p><b>`withheld` is one sentence and not a list</b>, unlike an agent's: a
 * definition is refused for a reason, where an agent can be served minus
 * several things it declared. Empty for a definition that was read.
 */
export interface Definition {
  readonly name: string;

  /** What it does, in the definition's own words. Empty for a refused file. */
  readonly description: string;

  /** `global` or `project`. Empty for a refused file, which has no tier. */
  readonly tier: string;

  /** Empty for a refused file, whose stages never took effect. */
  readonly stages: readonly Stage[];

  /** The sentences that say when this one is the thing to start. */
  readonly triggers: readonly string[];

  readonly served: boolean;

  /** Why this file is not served, in the server's own sentence. Empty when it is. */
  readonly withheld: string;
}

/**
 * One run waiting on a person, as the status line counts it and `/answer `
 * offers it.
 *
 * <p><b>The question is optional twice over.</b> It is absent until the run's
 * status has been read — the listing that finds a waiting run does not carry
 * what it asked — and absent for a run asking to have a cap raised, which asked
 * no question in words; `pendingCap` says which cap that is.
 */
export interface Waiting {
  readonly id: string;
  readonly definition: string;
  readonly question?: string;
  /** The question's options, when it has them — read with the question, from its status. */
  readonly structure?: Structure;
  readonly pendingCap?: string;
  /**
   * `approval` for a command waiting to be allowed, whose `definition` is the agent
   * that asked and whose `question` says the command; `stalled` for a run that has
   * gone quiet, which takes a look or a cancel rather than an answer; absent for an
   * ordinary run waiting on a question.
   */
  readonly kind?: 'approval' | 'stalled';
  /** The run that started it. Absent for a root, and for an approval or a stall. */
  readonly parent?: string;
  /** The agent that started it, which has a root's question too. See {@link Run.callerAgent}. */
  readonly callerAgent?: string;
  /** For an `approval`: the row whole, which its dialog is answered from. */
  readonly approval?: Approval;
}

/**
 * The asked approvals an account-wide listing named, as things waiting on a person —
 * leaving out the one the conversation on screen is asking, which that screen already
 * shows with the keys to answer it.
 *
 * @param here the conversation on screen, if any
 */
export function approvalsWaiting(
  approvals: readonly Approval[],
  here?: string,
): Waiting[] {
  return approvals
    .filter(
      (approval) =>
        approval.state === 'asked' && approval.conversation !== here,
    )
    .map((approval) => ({
      id: approval.id,
      definition: approval.agent,
      kind: 'approval',
      question: approvalAsks(approval),
      approval,
    }));
}

/**
 * What an approval asks, on one line: the command, where — or, for a set (V67), how many
 * acceptance commands, each of which the lines that show a set whole name.
 */
export function approvalAsks(approval: Approval): string {
  return approval.commands === undefined
    ? `run \`${approval.command.join(' ')}\` in ${approval.cwd} (${approval.side})`
    : `run ${countOf(approval.commands.length, 'acceptance command')} at its acceptance stage,` +
        ` in ${approval.cwd} (${approval.side})`;
}

function countOf(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/**
 * The latest question a run asked, or nothing for a run that asked none.
 *
 * <p><b>The latest, not the first.</b> A run can be answered and ask again, and
 * the messages keep both; the one it is waiting on is the last.
 */
function latestQuestion(status: RunStatus): Message | undefined {
  return [...status.messages]
    .reverse()
    .find((message) => message.kind === 'question');
}

/**
 * The latest question a run asked, or nothing for a run that asked none.
 *
 * <p><b>The latest, not the first.</b> A run can be answered and ask again, and
 * the messages keep both; the one it is waiting on is the last.
 */
export function questionOf(status: RunStatus): string | undefined {
  return latestQuestion(status)?.text;
}

/**
 * The options of the latest question a run asked, or nothing when it asked in words.
 *
 * <p><b>The latest question's own</b>, never an earlier one's: a run asked in words after asking
 * with options is answered in words.
 */
export function structureOf(status: RunStatus): Structure | undefined {
  return latestQuestion(status)?.structure;
}

/**
 * The `pendingCap` of a run asking the person whether it goes on after it ended
 * three turns in a row without progress — a question only a person may answer.
 */
export const STUCK = 'stuck';

/**
 * The `pendingCap` the retired acceptance verifier asked the person with (V65): whether spec.md's
 * acceptance commands stood as written. Nothing asks it any more (spec 2026-10-01); a run that
 * still holds one is the person's alone, and `accept` or their words answer it.
 */
export const UNCOVERED = 'uncovered';

/**
 * The `pendingCap` of a run whose acceptance checker could not resolve concerns with its conductor
 * (V77, spec 2026-10-01 §3): each concern with the conductor's reason and the checker's objection.
 * Only a person may answer it: `accept` accepts the conductor's reasons; `c1: <words>` per line
 * answers each, and any other words are the person's direction for all of them.
 */
export const CONCERNS = 'concerns';

/**
 * The `pendingCap` of a run whose acceptance commands all passed and that has `check:` lines — or
 * concerns its checker could not check — for the person (V77, spec 2026-10-01 §1): how the
 * product starts and what to check. Only a person may answer it: `accept` accepts the product, and
 * any other answer is their notes, which send the run back.
 */
export const PRODUCT_CHECK = 'product_check';

/**
 * The `pendingCap` of a run asking the person whether it goes on after its check — or its
 * acceptance commands — failed its project's `failed-checks` times (V69), with the end of the last
 * failure's output. A question only a person may answer, and never auto-continued: `go on` lets it
 * try again with the count started over, `stop` stops it.
 */
export const CHECK_FAILURES = 'check_failures';

/** The `pendingCap` of a run past its project's time cap (V69) — a cap, asked of both. */
export const TIME_CAP = 'time_cap';

/**
 * The `pendingCap` of a run asking the person whether to install the orchestration it drafted
 * (V71) — a question only a person may answer, with options: `Install` or `Don't install`.
 */
export const INSTALL = 'install';

/** The `pendingCap`s of the questions only a person may answer: the server refuses a model's. */
export const PERSON_ONLY_KINDS: readonly string[] = [
  STUCK,
  UNCOVERED,
  CHECK_FAILURES,
  INSTALL,
  CONCERNS,
  PRODUCT_CHECK,
];

/** The `pendingCap`s of a person-only question a person accepts or answers in words: `y` is `accept`. */
export const ACCEPT_KINDS: readonly string[] = [
  UNCOVERED,
  CONCERNS,
  PRODUCT_CHECK,
];

/**
 * What `/always caps` and the dialog's `a` set `auto-continue` to. Here, and not in `caps.ts`,
 * which re-exports it: `caps.ts` reads its answers with this file's helpers, and this file
 * importing it back made the two a cycle at load.
 */
export const ALWAYS_CAPS = 3;

/**
 * The `pendingCap`s of a run asking to have a cap raised — the question its parent model and the
 * person both get, the first answer settling it (spec 2026-09-29 §2).
 */
export const CAP_KINDS: readonly string[] = [
  'turn_cap',
  'call_budget',
  TIME_CAP,
];

/**
 * Which dialog a question waiting on the person is put to them in, or nothing for one that waits
 * in the list for `/answer` alone (spec 2026-09-29 §2, widened 2026-09-29):
 *
 * <ul>
 *   <li>`cap` — a run asking to have a cap raised ({@link CAP_KINDS}), asked of its parent model
 *       and the person at once;
 *   <li>`stuck` — a run asking whether it goes on ({@link STUCK}), which only the person may answer;
 *   <li>`accept` — a run asking the person to accept or answer in words, theirs alone: its product
 *       to check ({@link PRODUCT_CHECK}), its acceptance checker's concerns ({@link CONCERNS}), or a
 *       retired verifier question ({@link UNCOVERED});
 *   <li>`checks` — a run asking whether it goes on after its check kept failing ({@link
 *       CHECK_FAILURES}), the person's alone, with the failure's output;
 *   <li>`question` — a root run's own question, which its caller model has too. <b>Not a phase's</b>:
 *       a phase asks its parent conductor, whose question it is to answer.
 *   <li>`approval` — a command, or an acceptance set, waiting to be allowed in one of the person's
 *       runs, answered with the approval prompt's own keys (`approval.ts`). Not the one the
 *       conversation on screen is asking, which {@link approvalsWaiting} leaves out: that one is
 *       asked where it is, as it always was.
 * </ul>
 *
 * <p>Never a stall, which takes a look or a cancel and never an answer in words.
 */
export type DialogKind =
  'cap' | 'stuck' | 'accept' | 'checks' | 'question' | 'approval';

export function dialogKindOf(run: Waiting): DialogKind | undefined {
  // A COMMAND WAITING TO BE ALLOWED IN ONE OF THE PERSON'S RUNS: answered with the approval
  // prompt's own keys (`approval.ts`), in the dialog, rather than by typing `/answer <id> once`.
  // Measured 2026-09-29: "NONE of these requests are reaching a modal".
  if (run.kind === 'approval') {
    return run.approval === undefined ? undefined : 'approval';
  }
  if (run.kind !== undefined) {
    return undefined;
  }
  if (CAP_KINDS.includes(run.pendingCap ?? '')) {
    return 'cap';
  }
  if (run.pendingCap === STUCK) {
    return 'stuck';
  }
  if (ACCEPT_KINDS.includes(run.pendingCap ?? '')) {
    return 'accept';
  }
  if (run.pendingCap === CHECK_FAILURES) {
    return 'checks';
  }
  // AN INSTALL IS A QUESTION WITH OPTIONS, a phase's or a root's alike: only the person may
  // answer it, so it is theirs to see wherever in the tree it was asked.
  if (run.pendingCap === INSTALL) {
    return 'question';
  }
  return run.pendingCap === undefined && run.parent === undefined
    ? 'question'
    : undefined;
}

/**
 * The runs waiting now, from the ones waiting before and what a listing of the
 * asking runs just answered; and which of them still need their status read.
 *
 * <p><b>What was already read is kept</b>, so a check every few seconds reads a
 * run's status once rather than once a check. `stale` names the runs a push has
 * said are asking since — answered and asking again between two checks keeps
 * the id and changes the question — and those are read again.
 *
 * <p>Ordered as the listing ordered them, and a run that has stopped asking is
 * simply not carried over.
 *
 * <p><b>A child run is left out.</b> The listing is this account's, and a
 * child is on the account of the tree it belongs to — but its question is
 * delivered to the conductor that started it, which answers it itself. Offering
 * it to a person puts a question in front of them that was never theirs, and
 * the answer they type races the conductor's.
 *
 * <p><b>Except a child asking whether it goes on</b> (`pendingCap` `stuck`, spec
 * 2026-09-28): a phase that ended three turns without progress asks the person,
 * the server delivers that question to nobody else, and refuses its parent
 * conductor's answer. Left out here, nobody could ever see it.
 *
 * <p><b>And a child asking to have a cap raised</b> ({@link CAP_KINDS}, spec 2026-09-29 §2):
 * that one goes to its parent model and to the person at once, and whoever answers first
 * settles it — so the person's copy has to be somewhere they can answer it.
 */
export function waitingAfter(
  before: readonly Waiting[],
  asking: readonly Run[],
  stale: ReadonlySet<string> = new Set(),
): { readonly now: Waiting[]; readonly unread: string[] } {
  const now: Waiting[] = [];
  const unread: string[] = [];
  for (const run of asking) {
    // A child's own question is its conductor's — except one only the person may answer
    // (`stuck`, `uncovered`), and a cap, which is asked of both at once (spec 2026-09-29 §2).
    if (
      run.parent !== undefined &&
      !PERSON_ONLY_KINDS.includes(run.pendingCap ?? '') &&
      !CAP_KINDS.includes(run.pendingCap ?? '')
    ) {
      continue;
    }
    const known = before.find((was) => was.id === run.id);
    if (known !== undefined && !stale.has(run.id)) {
      now.push(known);
      continue;
    }
    now.push({
      id: run.id,
      definition: run.definition,
      ...(run.pendingCap === undefined ? {} : { pendingCap: run.pendingCap }),
      ...(run.parent === undefined ? {} : { parent: run.parent }),
      ...(run.callerAgent === undefined
        ? {}
        : { callerAgent: run.callerAgent }),
    });
    unread.push(run.id);
  }
  return { now, unread };
}

/**
 * The runs a `listingStalled` answer named that the store has actually marked, as things
 * waiting on a person — a look or a cancel, never an answer in words.
 *
 * <p><b>A child run is kept, unlike {@link waitingAfter}.</b> That function leaves a child
 * out because its question is its conductor's to answer; a child's stall carries no such
 * question and is exactly what a person needs to see — the conductor above it is as quiet
 * as it is, and nobody but a person can cancel a tree that has gone silent.
 */
export function stalledWaiting(runs: readonly Run[]): Waiting[] {
  return runs
    .filter((run) => run.stalledSince !== undefined)
    .map((run) => ({
      id: run.id,
      definition: run.definition,
      kind: 'stalled',
    }));
}

/**
 * The definitions an `orchestration.definitions` answer named, or nothing for a
 * refusal or a payload this build cannot read.
 *
 * <p><b>Nothing, and not an empty list</b>, on {@link projects}' rule. An empty
 * listing is a real state here and a meaningful one — a deployment with the
 * orchestration engine unwired answers exactly that, deliberately — so it is
 * precisely the state that must not be confused with "the server would not say".
 *
 * <p><b>A row this build cannot read is dropped, not the whole list</b>, on
 * {@link approvalsOf}' rule: the rows are independent, and one unreadable
 * definition takes nothing from the others.
 */
export function definitionsOf(answer: Answer): Definition[] | undefined {
  const rows = bodyOf(answer, OK)?.['definitions'];
  if (!isList(rows)) {
    return undefined;
  }
  return rows
    .map((row) => definitionIn(fieldsOf(row)))
    .filter((row): row is Definition => row !== undefined);
}

/** One `DefinitionView`, in the fields a person is shown. */
function definitionIn(fields: Record<string, unknown>): Definition | undefined {
  const name = textAt(fields, 'name');
  if (name === undefined) {
    return undefined;
  }
  const stages = fields['stages'];
  return {
    name,
    description: textAt(fields, 'description') ?? '',
    tier: textAt(fields, 'tier') ?? '',
    stages: isList(stages)
      ? stages
          .map((stage) => stageIn(fieldsOf(stage)))
          .filter((stage): stage is Stage => stage !== undefined)
      : [],
    triggers: sentencesAt(fields, 'triggers'),
    served: flagAt(fields, 'served'),
    withheld: textAt(fields, 'withheld') ?? '',
  };
}

/** One `StageView`: its id, and its done-when where it has one. */
function stageIn(fields: Record<string, unknown>): Stage | undefined {
  const id = textAt(fields, 'id');
  if (id === undefined) {
    return undefined;
  }
  const doneWhen = textAt(fields, 'doneWhen');
  return {
    id,
    ...(doneWhen === undefined || doneWhen === '' ? {} : { doneWhen }),
  };
}

/** One `ScheduleRecord`, in the fields a listing and a lookup read. */
export interface Schedule {
  readonly name: string;
  readonly cron: string;
  readonly zone: string;
  /** The event this schedule emits, which is how a trigger is joined to it. */
  readonly emits: string;
  readonly paused: boolean;
  /** Absent when the server has none to say. */
  readonly nextFireAt?: string;
}

/** One `TriggerRecord`, in the fields a listing and a lookup read. */
export interface Trigger {
  readonly name: string;
  readonly event: string;
  readonly agent: string;
  readonly task: string;
  readonly paused: boolean;
  readonly project?: string;
  readonly conversation?: string;
}

/** One `FiringRecord`, in the fields a listing reads. */
export interface Firing {
  readonly id: string;
  readonly event: string;
  readonly status: string;
  readonly arrivedAt: string;
  /** Absent when nobody was listening. */
  readonly trigger?: string;
  readonly reason?: string;
}

/** The triggers listening to this schedule: the ones whose event it emits. */
export function listeningTo(
  schedule: Schedule,
  triggers: readonly Trigger[],
): Trigger[] {
  return triggers.filter((trigger) => trigger.event === schedule.emits);
}

/**
 * Whether a line answers a `save? [y/n]` with yes. `y` or `yes`, in any case,
 * and nothing else: a confirmation that read `yeah` as yes would read the next
 * thing somebody typed as consent.
 */
export function answeredYes(line: string): boolean {
  const said = line.trim().toLowerCase();
  return said === 'y' || said === 'yes';
}

/**
 * An optional string field: `[true, value]` for a string, `[true]` for absent
 * or null, and `[false]` for anything else — which a validating reader refuses.
 */
function maybeTextAt(
  fields: Record<string, unknown>,
  name: string,
): readonly [boolean, string?] {
  const found = fields[name];
  if (found === undefined || found === null) {
    return [true];
  }
  return typeof found === 'string' ? [true, found] : [false];
}

/** Every row of an `OK` list, each read by `read`, or nothing if any row fails. */
function everyRow<Row>(
  answer: Answer,
  read: (fields: Record<string, unknown>) => Row | undefined,
): Row[] | undefined {
  const rows = rowsOf(answer);
  if (rows === undefined) {
    return undefined;
  }
  const found: Row[] = [];
  for (const row of rows) {
    const one = read(fieldsOf(row));
    if (one === undefined) {
      return undefined;
    }
    found.push(one);
  }
  return found;
}

/**
 * The proposal a `schedule.read` answer carried, or nothing for a refusal or a
 * payload this build cannot read.
 *
 * <p><b>Validated whole, on {@link inboxPageOf}'s rule.</b> A proposal is shown
 * and then saved as shown; one read with a field quietly defaulted would save
 * something the person was never shown. A proposal into a conversation that
 * names none is refused too: its trigger could not be defined.
 */
export function proposalOf(answer: Answer): Proposal | undefined {
  const body = bodyOf(answer, OK);
  if (body === undefined) {
    return undefined;
  }
  const cron = textAt(body, 'cron');
  const zone = textAt(body, 'zone');
  const when = textAt(body, 'when');
  const agent = textAt(body, 'agent');
  const task = textAt(body, 'task');
  const into = body['intoConversation'];
  const [projectOk, project] = maybeTextAt(body, 'project');
  const [conversationOk, conversation] = maybeTextAt(body, 'conversation');
  const fires = body['nextFires'];
  const names = fieldsOf(body['names']);
  const schedule = textAt(names, 'schedule');
  const trigger = textAt(names, 'trigger');
  const event = textAt(names, 'event');
  if (
    cron === undefined ||
    zone === undefined ||
    when === undefined ||
    agent === undefined ||
    task === undefined ||
    typeof into !== 'boolean' ||
    !projectOk ||
    !conversationOk ||
    !isList(fires) ||
    !fires.every((fire) => typeof fire === 'string') ||
    schedule === undefined ||
    trigger === undefined ||
    event === undefined ||
    (into && conversation === undefined)
  ) {
    return undefined;
  }
  return {
    cron,
    zone,
    when,
    agent,
    task,
    intoConversation: into,
    ...(project === undefined ? {} : { project }),
    ...(conversation === undefined ? {} : { conversation }),
    nextFires: fires,
    names: { schedule, trigger, event },
  };
}

/** The schedules a `schedule.list` answer named, or nothing if any row is unreadable. */
export function schedulesOf(answer: Answer): Schedule[] | undefined {
  return everyRow(answer, (fields) => {
    const name = textAt(fields, 'name');
    const cron = textAt(fields, 'cron');
    const zone = textAt(fields, 'zone');
    const emits = textAt(fields, 'emits');
    const paused = fields['paused'];
    const [nextOk, nextFireAt] = maybeTextAt(fields, 'nextFireAt');
    if (
      name === undefined ||
      cron === undefined ||
      zone === undefined ||
      emits === undefined ||
      typeof paused !== 'boolean' ||
      !nextOk
    ) {
      return undefined;
    }
    return {
      name,
      cron,
      zone,
      emits,
      paused,
      ...(nextFireAt === undefined ? {} : { nextFireAt }),
    };
  });
}

/** The triggers a `trigger.list` answer named, or nothing if any row is unreadable. */
export function triggersOf(answer: Answer): Trigger[] | undefined {
  return everyRow(answer, (fields) => {
    const name = textAt(fields, 'name');
    const event = textAt(fields, 'event');
    const agent = textAt(fields, 'agent');
    const task = textAt(fields, 'task');
    const paused = fields['paused'];
    const [projectOk, project] = maybeTextAt(fields, 'project');
    const [conversationOk, conversation] = maybeTextAt(fields, 'conversation');
    if (
      name === undefined ||
      event === undefined ||
      agent === undefined ||
      task === undefined ||
      typeof paused !== 'boolean' ||
      !projectOk ||
      !conversationOk
    ) {
      return undefined;
    }
    return {
      name,
      event,
      agent,
      task,
      paused,
      ...(project === undefined ? {} : { project }),
      ...(conversation === undefined ? {} : { conversation }),
    };
  });
}

/**
 * The firings an `event.fire` or `firing.list` answer named, or nothing if any
 * row is unreadable. Both answer with `FiringRecord`s.
 */
export function firingsOf(answer: Answer): Firing[] | undefined {
  return everyRow(answer, (fields) => {
    const id = textAt(fields, 'id');
    const event = textAt(fields, 'event');
    const status = textAt(fields, 'status');
    const arrivedAt = textAt(fields, 'arrivedAt');
    const [triggerOk, trigger] = maybeTextAt(fields, 'trigger');
    const [reasonOk, reason] = maybeTextAt(fields, 'reason');
    if (
      id === undefined ||
      event === undefined ||
      status === undefined ||
      arrivedAt === undefined ||
      !triggerOk ||
      !reasonOk
    ) {
      return undefined;
    }
    return {
      id,
      event,
      status,
      arrivedAt,
      ...(trigger === undefined ? {} : { trigger }),
      ...(reason === undefined ? {} : { reason }),
    };
  });
}

/**
 * Whether a run's change is worth checking the waiting runs over.
 *
 * <p><b>`asking`, and any change to a run already counted as waiting.</b> The
 * first is a run that has started waiting on a person, or asked again after an
 * answer. The second is a run that has stopped: answered from somewhere else,
 * or ended, and the count on the status line is now wrong. Nothing else. A push
 * lands on every committed change, and a listing per stage move would be the
 * check paying for runs nobody is waiting on — the timer catches anything this
 * lets pass.
 *
 * @param waiting the ids of the runs the last check found waiting
 */
export function rechecks(
  changed: { readonly id: string; readonly state: string },
  waiting: readonly string[],
): boolean {
  return changed.state === 'asking' || waiting.includes(changed.id);
}

/**
 * The one code that means "a handle, not an answer".
 *
 * Spelled here rather than imported from `codes.ts`, for the reason the frame
 * types above are. The mirror test holds it against `Code.java` so this is not
 * a third vocabulary quietly drifting from the other two.
 */
export const ACCEPTED = 'ACCEPTED';

/** A run has begun. Sent before the first model call. */
export interface Started {
  readonly kind: 'started';
  readonly job: string;
  readonly agent: string;
}

/**
 * One model call has been claimed and is about to be made.
 *
 * <b>`steps` and not `turns`.</b> A step is one model call plus every tool
 * result it asked for; a turn is one thing a person said. `JobEvent` renamed
 * this field because a console rendering "after 4 turns" for one question told
 * somebody they had spoken four times, and this module carries the corrected
 * word so that no view has to learn it twice.
 */
export interface Stepped {
  readonly kind: 'step';
  readonly job: string;
  readonly agent: string;
  readonly steps: number;
  readonly modelCalls: number;
}

/** One tool the model asked for is about to run. */
export interface Called {
  readonly kind: 'tool';
  readonly job: string;
  readonly agent: string;

  /** The name the <b>server</b> registered, never the name the model asked for. */
  readonly tool: string;
}

/** The run is over and its outcome is filed. The last event for a job. */
export interface Stopped {
  readonly kind: 'ended';
  readonly job: string;
  readonly agent: string;
  readonly ending: string;
  readonly steps: number;
  readonly modelCalls: number;
}

/**
 * The run is still there, said by a server with nothing else to report.
 *
 * <p><b>The one kind that is not about work</b>, and the reason it exists is
 * that a model call can take minutes: without it a person watching a still
 * terminal cannot tell a slow run from a dead connection, and this client used
 * to resolve that with a timeout on the answer -- which is a guess about how
 * long thinking takes.
 *
 * <p><b>It carries the counts</b>, which is what makes it progress rather than
 * a pulse. `JobEvent` sends `steps` and `modelCalls` on `ALIVE` for exactly
 * this: the beat that says "still here" says how far along too.
 *
 * <p>Before this kind existed `ALIVE` fell to {@link Other} -- correctly, that
 * branch is what tolerance is for -- and came out as "this run reported ALIVE"
 * every twenty seconds, in place of the progress the run had.
 */
export interface Beating {
  readonly kind: 'alive';
  readonly job: string;
  readonly agent: string;
  readonly steps: number;
  readonly modelCalls: number;
}

/**
 * A well-formed event whose `kind` this build has never heard of.
 *
 * Not an error. `JobEvent.kind` is a String so a client built against a later
 * server binds over a constant it does not know; this is where that tolerance
 * is spent, and the name travels so a view can still say something true.
 */
export interface Other {
  readonly kind: 'other';
  readonly job: string;
  readonly named: string;
}

/** A frame that is not a `JobEvent` at all, and so belongs to no job. */
export interface Unreadable {
  readonly kind: 'unreadable';
  readonly said: string;
}

/** What one arriving push means. */
export type Progress =
  Started | Stepped | Called | Beating | Stopped | Other | Unreadable;

/**
 * What one line a person typed turns out to be. See {@link typed}.
 *
 * <b>A closed union this module owns</b>, the shape {@link Progress} has and for
 * the same reason: the list is this client's rather than the server's, so a view
 * switching over it is told by the compiler when a fifth kind arrives. The two
 * listings are separate kinds rather than one carrying its frame type, so that
 * nothing in `view/` has to compare a dotted string to decide how to read the
 * answer.
 */
export type Typed =
  | { readonly kind: 'messaging'; readonly command: MessageRequest }
  | { readonly kind: 'usage-report'; readonly command: UsageCommand }
  | { readonly kind: 'information'; readonly command: InformationCommand }
  | { readonly kind: 'retrieval'; readonly command: RetrievalCommand }
  | { readonly kind: 'retrieval-error'; readonly text: string }
  /** Something to say to the agent, trimmed. */
  | { readonly kind: 'utterance'; readonly text: string }
  /** Show the projects. {@link projects} reads what comes back. */
  | { readonly kind: 'projects'; readonly ask: Ask }
  /** Show this home's conversations. {@link conversations} reads the answer. */
  | { readonly kind: 'conversations'; readonly ask: Ask }
  /**
   * Show who there is to talk to. {@link agents} reads the answer.
   *
   * <b>The same {@link Ask} as `agents` below, and two kinds all the same.</b>
   * One frame answers with both kinds in one list and the `bot` flag tells
   * them apart, so the difference is entirely in what is shown — which is the
   * spec's whole point, and the reason a single `/agents` showing a `bot`
   * column would have been the wrong shape. A person choosing somebody to
   * talk to is not scanning a list for a flag.
   */
  | { readonly kind: 'bots'; readonly ask: Ask }
  /** Show what there is to invoke. {@link agents} reads the answer. */
  | { readonly kind: 'agents'; readonly ask: Ask }
  /**
   * Show one agent whole: what it holds, what it may delegate to, where it may
   * reach, and what it may start.
   *
   * <b>The same {@link Ask} as the roster, and the name is picked out of the
   * answer.</b> There is no frame that reads one agent — `agent.list` is the
   * whole of what is on the wire — so a detail view is a listing read down to
   * one row, and a name nothing answers to is said by {@code
   * wording.describeAgent} rather than refused by a server.
   */
  | { readonly kind: 'agent'; readonly name: string; readonly ask: Ask }
  /**
   * Show what can be started here. {@link definitionsOf} reads the answer.
   *
   * <p>The ask carries whatever project was passed in, and nothing when there
   * was none — the server degrades that to the boot set, and the view says
   * which of the two it showed.
   */
  | {
      readonly kind: 'design';
      readonly text: string;
      readonly revision?: string;
    }
  | { readonly kind: 'orchestrations'; readonly ask: Ask }
  /** Show one definition whole, its stages one per line. Read off the same listing. */
  | { readonly kind: 'orchestration'; readonly name: string; readonly ask: Ask }
  /** Show this account's orchestration runs. {@link runsOf} reads the answer. */
  | { readonly kind: 'runs'; readonly ask: Ask }
  /** Show one run: its stages, its messages and its children. {@link runStatusOf} reads it. */
  | { readonly kind: 'run'; readonly id: string; readonly ask: Ask }
  /** Follow one run's tree — the one named, or the newest live one. The view reads it. */
  | { readonly kind: 'watch'; readonly run?: string }
  /**
   * Settle one run's question, in the person's own words.
   *
   * <b>The answer is carried whole and unedited.</b> Everything after the id is
   * the answer, spaces and all — a conductor asked a question and this is the
   * one place in the client where what a person types goes to a model without
   * anything in between reading it.
   */
  /** `/always`: this project's runs start commands without asking, or — `off` — ask again. */
  | { readonly kind: 'always'; readonly on: boolean }
  /** `/cap`: this project's caps, each with the source that set it. */
  | { readonly kind: 'cap' }
  /** `/cap steps|budget|auto|time|checks N`, or `/always caps`: one cap, written to the project's file. */
  | { readonly kind: 'capSet'; readonly key: CapKey; readonly value: number }
  | {
      readonly kind: 'answerApproval';
      readonly id: string;
      readonly decision: Decision;
      readonly ask: Ask;
    }
  | {
      readonly kind: 'answerRun';
      readonly id: string;
      readonly answer: string;
      readonly ask: Ask;
    }
  /** Stop one run, and with it every descendant it started. */
  | { readonly kind: 'cancelRun'; readonly id: string; readonly ask: Ask }
  | { readonly kind: 'resumeRun'; readonly id: string }
  /** Show what is waiting. {@link describeInbox} in `wording.ts` reads the answer. */
  | { readonly kind: 'inbox'; readonly ask: Ask }
  /** Read-only board/swarm inspection; paging and refresh are owned by the view. */
  | {
      readonly kind: 'board';
      readonly view: 'board' | 'swarm';
      readonly project?: string;
    }
  /**
   * Open the explorer on a conversation's log — spec 2026-09-29 §5: `/trajectory` in the
   * trajectory view, `/log` in the flat log view. Both read the same rows.
   *
   * <b>No ask.</b> With no conversation named, the one read is the one open where the person
   * is, which {@link typed} does not hold — the view does; and the explorer reads page by page
   * and descends into delegations, so it is the view's reads and not one frame.
   */
  | {
      readonly kind: 'trajectory';
      readonly view: 'trajectory' | 'log';
      readonly conversation?: string;
    }
  /**
   * Go further back through the conversation on screen. <b>No ask</b>, on `trajectory`'s reason: how
   * far back the screen already reaches is the view's to know, and {@link readingEarlier}
   * takes it.
   */
  | { readonly kind: 'earlier' }
  /** Ask Daedalus to inspect an explicit conversation, or the current one when absent. */
  | {
      readonly kind: 'diagnose';
      readonly conversation?: string;
      readonly question?: string;
    }
  /**
   * What this client can do, which is the first thing anybody types.
   *
   * <b>No ask.</b> It is answered from `wording.describeHelp` without a round
   * trip, which is what makes it the one command that works on a server too
   * old, too new or too broken to answer anything else.
   */
  | { readonly kind: 'help' }
  /** Refresh the selected agent's human command catalog without starting work. */
  | {
      readonly kind: 'command-catalog';
      readonly only: 'skill' | 'all';
      readonly ask: Ask;
    }
  /** Make this directory a project, and root it. The name is optional. */
  | { readonly kind: 'here'; readonly name?: string }
  /** Move to a project that already exists, by the name a person gave it. */
  | { readonly kind: 'project'; readonly name: string }
  /** Stand somewhere else, and look for a project rooted there. */
  | { readonly kind: 'cd'; readonly path: string }
  /** `/project` or `/cd`, typed with no argument, which each needs one of. */
  | { readonly kind: 'usage'; readonly command: string }
  /**
   * Show the runs waiting on a person. `held` when an answer was typed and not
   * sent, because nothing said which run it was for.
   */
  | { readonly kind: 'waitingRuns'; readonly held?: true }
  /** `/sync`, parsed into what it asked for. {@link syncAction} in `union.ts` reads it. */
  | { readonly kind: 'sync'; readonly action: SyncAction }
  /**
   * List the themes, or switch to the one named. Answered by the view: a
   * theme belongs to this terminal, so there is nothing to ask a server.
   */
  | { readonly kind: 'theme'; readonly name?: string }
  /**
   * A sentence to read into a schedule. <b>No ask</b>: the zone, the tier and
   * the conversation are the view's, and {@link readingSchedule} takes them.
   */
  | { readonly kind: 'schedule'; readonly text: string }
  | { readonly kind: 'schedule-file'; readonly text: string }
  /** Pause, resume or forget one schedule, and the triggers listening to it. */
  | {
      readonly kind: 'managing';
      readonly verb: 'pause' | 'resume' | 'forget';
      readonly name: string;
    }
  /**
   * The schedules and their triggers: two listings, joined by the view.
   * `/schedule list`, or `/schedule` with nothing after it.
   */
  | { readonly kind: 'schedules' }
  /** Fire one schedule's event now, found by the schedule's name. */
  | { readonly kind: 'fire'; readonly name: string }
  /** The last firings. {@link firingsOf} reads the answer. */
  | { readonly kind: 'firings'; readonly ask: Ask }
  /**
   * The project approvals standing where a person is. <b>No ask</b>, on
   * `log`'s reason: the frame needs a project, and a person standing in none
   * is told so by the view rather than sent a frame naming nothing.
   */
  | { readonly kind: 'approvals' }
  /** Take one standing approval back, by the id `/approvals` showed. */
  | { readonly kind: 'revoking'; readonly id: string; readonly ask: Ask }
  /** A slash and a word this client does not know, carried as typed. */
  | { readonly kind: 'unknown'; readonly named: string };

/**
 * Who is going to answer, decided out of the roster and whatever was named.
 *
 * <h2>A decision, and it is made before anything is created</h2>
 *
 * <p>This is the value {@link AGENT_LIST} exists at sign-in for. Every arm
 * except the first is a reason there will be no conversation, reached with
 * nothing opened and nothing spent — which is the fix: naming an agent this
 * server does not serve used to leave a conversation row behind, because the
 * name was validated by `agent.run` and `agent.run` comes after
 * `conversation.open`.
 *
 * <p><b>A closed union this module owns</b>, {@link Typed}'s shape and for its
 * reason: the compiler tells a view when a sixth state arrives, and
 * `wording.describeChoice` has an arm for each.
 */
export type Chosen =
  /**
   * Somebody will answer.
   *
   * @see Agent.bot for why the kind still matters here — an agent answers,
   *     and a person is told once that it is not a conversation
   */
  | {
      readonly kind: 'answering';
      readonly agent: Agent;
      /** Whether a person named this, or this client found it on its own. */
      readonly asked: boolean;
      /** Whether the tier's `bots/default` chose this one. */
      readonly preferred: boolean;
    }
  /** A name was given and this server declares nothing by it. */
  | {
      readonly kind: 'unknown';
      readonly asked: string;
      /** What would have worked — never the refused, which would not. */
      readonly served: readonly Agent[];
    }
  /** A name was given, and this server read that definition and refused it. */
  | {
      readonly kind: 'refused';
      readonly asked: string;
      readonly why: readonly string[];
    }
  /**
   * The tier names a default bot, and that name cannot answer — absent, refused,
   * or an agent rather than a bot. Its own arm and not `refused`, because nobody
   * asked for it by name, and not `none`, because the tier did say.
   */
  | {
      readonly kind: 'unmet';
      readonly named: string;
      readonly why: readonly string[];
    }
  /**
   * Nobody was named and this deployment serves nothing at all — no bot and no
   * agent.
   *
   * @param unreadable the definitions this server read and refused, which is
   *     what tells "nothing is defined" from "the only thing defined would not
   *     parse"
   */
  | { readonly kind: 'none'; readonly unreadable: readonly Agent[] };

/**
 * One turn in flight: everything known about it so far, in any order.
 *
 * A value, folded by {@link answering} and {@link following}. It holds no
 * promise, no timer and no socket, which is what lets the same model drive a
 * readline loop and a window.
 */
export interface Turn {
  /** The frame that starts it. The view sends this and folds the answer back. */
  readonly ask: Ask;

  /** The handle, once the `ACCEPTED` has arrived. */
  readonly job?: string;

  /** This job's events, in arrival order. */
  readonly progress: readonly Progress[];

  /**
   * Events seen before the handle was known.
   *
   * <b>The holding area the interleaving contract needs.</b> Several jobs may
   * be running on one socket — `JobEvent.job` exists for exactly that — so
   * before the handle arrives an event cannot be attributed, and dropping it
   * would lose the whole of a fast run. {@link answering} empties this.
   */
  readonly held: readonly Progress[];

  /** The ending event for this job, once it has been seen. */
  readonly stopped?: Stopped;

  /** Why there will be no run: the server's sentence, or this client's. */
  readonly refused?: string;
}

/** Every row of an answer that is an `OK` carrying a list, or nothing. */
function rowsOf(answer: Answer): unknown[] | undefined {
  if (answer.code !== OK || !isList(answer.payload)) {
    return undefined;
  }
  return answer.payload;
}

/**
 * The strings of a list field, with anything that is not one left out — and an
 * empty list for a field that is not a list at all.
 *
 * <p>{@link wordsAt}'s tolerant twin, and the difference is deliberate: that one
 * refuses the whole field so a validating reader can refuse the row, and this one
 * keeps what it can read because every caller here would rather show four tools
 * than drop the agent.
 */
function sentencesAt(
  fields: Record<string, unknown>,
  name: string,
): readonly string[] {
  const found = fields[name];
  return isList(found)
    ? found.filter((said): said is string => typeof said === 'string')
    : [];
}

/**
 * Who answers, out of what this deployment serves and whatever was named.
 *
 * <h2>The bot is the default, and that is the whole change</h2>
 *
 * <p>`PLOWSHARE_AGENT` used to be required, which made the ordinary way to use
 * this system — talking to somebody — the thing you had to configure. A
 * deployment serving exactly one bot has already answered the question, so this
 * client does not ask it again.
 *
 * <h2>A tier's own default, read before this client falls back to anything</h2>
 *
 * <p><b>`bots/default` is the project saying who answers, and a client that
 * counted bots instead would be overriding it.</b> `DefinitionResolver` reports
 * at most one row as {@link Agent.preferred}, and this reads it first, before
 * either the named branch above or the fallback below get a say — except a
 * person naming somebody outright still wins, on {@link speaking}'s own rule:
 * inventing a name here would be this client deciding who answers, and naming
 * one is the person doing exactly that.
 *
 * <p><b>An unmet default is refused rather than quietly worked around.</b> The
 * tier named somebody — absent, refused, or an agent rather than a bot — and
 * replacing that choice with whichever bot sorts first is the one thing
 * `bots/default` exists to prevent. So a preferred row that cannot answer comes
 * back as {@link Chosen}'s own `unmet` arm, and the fallback below is never
 * reached for a tier that named one.
 *
 * <h2>No default: the first available answers (ruling 15)</h2>
 *
 * <p><b>This used to refuse between several bots, and no longer does.</b> There
 * was no ranking between them then, so any choice was alphabetical order
 * wearing the clothes of a decision — but `bots/default` is that ranking now,
 * made explicit rather than inferred, and a tier that has not set one is a tier
 * that has not said it minds which bot answers. `agent.list`'s own order — the
 * server sorts by name — is what "first" means, so the same tier answers with
 * the same one every time.
 *
 * <p>A bot before an agent, because a bot is somebody to talk to and an agent
 * is a role to invoke; an agent before nothing, because a tier that serves only
 * agents still serves something — the shipped bot can be removed, and a
 * deployment that runs only agents is a legitimate deployment.
 *
 * <h2>Nothing at all, which is a real state</h2>
 *
 * <p>A tier that serves neither a bot nor an agent says there is nobody to talk
 * to, and leaves `PLOWSHARE_AGENT` as the way to reach an agent once one exists.
 *
 * <p><b>And it carries the refused definitions out with it</b>, because "nothing
 * is defined" and "the only thing defined would not parse" are different
 * situations and a person can act on exactly one of them. What is <i>not</i>
 * knowable from here is whether a refused definition said `bot: true`:
 * `AgentView.disabled` sends `bot: false` for every one, since a file that
 * failed to parse has no flag to read. So this hands the refusals up and
 * `wording.describeChoice` says only what they are — definitions this server
 * could not read — and claims nothing about which kind they were.
 *
 * @param rows what {@link agents} read off `agent.list`
 * @param named the name a person gave, when they gave one. A blank is treated
 *     as no name: the variable being set to an empty string is somebody not
 *     having set it
 */
export function whoAnswers(rows: readonly Agent[], named?: string): Chosen {
  const asked = (named ?? '').trim();
  if (asked !== '') {
    const found = rows.find((row) => row.name === asked);
    if (found === undefined) {
      return {
        kind: 'unknown',
        asked,
        served: rows.filter((row) => row.served),
      };
    }
    if (!found.served) {
      return { kind: 'refused', asked, why: found.withheld };
    }
    return { kind: 'answering', agent: found, asked: true, preferred: false };
  }
  // THE TIER'S OWN CHOICE, BEFORE THIS CLIENT'S FALLBACK. A project whose
  // bots/default names somebody is saying who answers here; counting bots
  // instead would hand the person a character the project did not choose, and
  // quietly so when the named one is missing. So an unmet default is said, and
  // the "only bot" rule below is never reached for a tier that named one.
  const preferred = rows.find((row) => row.preferred);
  if (preferred !== undefined) {
    if (preferred.served && preferred.bot) {
      return {
        kind: 'answering',
        agent: preferred,
        asked: false,
        preferred: true,
      };
    }
    return {
      kind: 'unmet',
      named: preferred.name,
      why: preferred.served
        ? [`${preferred.name} is an agent rather than a bot`]
        : preferred.withheld,
    };
  }
  // NO DEFAULT, SO THE FIRST AVAILABLE ANSWERS (ruling 15). A bot before an
  // agent, because a bot is somebody to talk to; an agent before nothing,
  // because a tier that serves only agents still serves something. "First" is
  // the server's listing order, which it sorts by name, so the same tier
  // answers with the same one every time.
  const served = rows.filter((row) => row.served);
  const first = served.find((row) => row.bot) ?? served[0];
  if (first !== undefined) {
    return { kind: 'answering', agent: first, asked: false, preferred: false };
  }
  return { kind: 'none', unreadable: rows.filter((row) => !row.served) };
}

/**
 * What one line typed at the prompt is, before anything is done with it.
 *
 * <h2>Commands, and why they are read here rather than in the view</h2>
 *
 * <p>A line beginning with `/` is a command this client answers itself; anything
 * else is a question for the agent. The reading is runtime-neutral and so is
 * the decision — a window would offer the same two listings and would reach the
 * same {@link Ask}s — and doing it here is what keeps the view from writing a
 * dotted type or a payload key, which is the whole of the dependency inversion
 * this module exists for.
 *
 * <p><b>Only a leading slash</b>. `what does /projects do?` is a question, and a
 * client that searched the line for a command would refuse to ask it.
 *
 * <p><b>And an unknown command is refused rather than sent.</b> A mistyped
 * `/porjects` passed on as an utterance costs a model call and comes back as an
 * agent's puzzled answer; `wording.describeUnknownCommand` is what a person gets
 * instead. The cost is stated: somebody who genuinely wanted to open a question
 * with a slash cannot, and would write it with a word in front.
 *
 * @param project the home a listing is asked for, when one is named. Carried
 *     rather than read from anywhere, because this module knows no environment
 */
const ADMIN_USAGE =
  'Use /admin status|accounts|account create|account update|account reset|sessions|session revoke|audit|pricing list|pricing set|service accounts|service account create|service account update|service tokens|service token create|service token rotate|service token revoke. Service token creation needs handle, name and scopes [{project,role}]; expiresInDays defaults to 30 (1–365). Account operations and session reads take a JSON payload with handle; audit accepts handle, before and limit. Pricing set needs billingRoute, model, expectedVersion from pricing list, mode and decimal-string rates in a JSON payload.';

/** Application source and server management commands keep /project NAME as local navigation. */
export function serverCommand(line: string, project?: string) {
  const text = line.trim();
  if (text === '/admin') return { kind: 'usage' as const, said: ADMIN_USAGE };
  if (
    !/^\/(?:admin\s+(?:status|accounts|sessions|audit|pricing\s+(?:list|set)|account\s+(?:create|update|reset)|session\s+revoke|service\s+(?:accounts|tokens|account\s+(?:create|update)|token\s+(?:create|rotate|revoke)))|application\s+(?:files|read|save)|project\s+(?:create|list|define|lend|unlend|workspace|move|forget|access|member-role|member-add|member-remove))(?:\s|$)/u.test(
      text,
    )
  )
    return { kind: 'unhandled' as const };
  return parseCommand(text.slice(1), project);
}

export function typed(
  line: string,
  project?: string,
  waiting: readonly string[] = [],
  usageContext: { conversation?: string; agent?: string } = {},
): Typed {
  const text = line.trim();
  if (text === '/admin') return { kind: 'retrieval-error', text: ADMIN_USAGE };
  // Qualified server commands travel unchanged through the ordinary conversation transport.
  if (/^\/(skill|orchestration):[^\s]+(?:\s|$)/u.test(text))
    return { kind: 'utterance', text: line };
  if (text === HELP_COMMAND) {
    return { kind: 'help' };
  }
  if (text === '/commands' || text === '/skills') {
    return {
      kind: 'command-catalog',
      only: text === '/skills' ? 'skill' : 'all',
      ask: listingAgents(project),
    };
  }
  if (text === THEME_COMMAND) {
    return { kind: 'theme' };
  }
  if (text.startsWith(`${THEME_COMMAND} `)) {
    return { kind: 'theme', name: text.slice(THEME_COMMAND.length).trim() };
  }
  if (text === PROJECTS_COMMAND) {
    return { kind: 'projects', ask: listingProjects() };
  }
  if (text === CONVERSATIONS_COMMAND) {
    return { kind: 'conversations', ask: listingConversations(project) };
  }
  if (text === BOTS_COMMAND) {
    return { kind: 'bots', ask: listingAgents(project) };
  }
  if (text === INBOX_COMMAND) {
    return { kind: 'inbox', ask: listingInbox() };
  }
  if (text === EARLIER_COMMAND) {
    return { kind: 'earlier' };
  }
  if (text === FIRINGS_COMMAND) {
    return { kind: 'firings', ask: listingFirings() };
  }
  // THE COMMANDS THAT TAKE AN ARGUMENT, READ BEFORE THE BARE-SLASH CHECK
  // BELOW SO THAT `/here`, `/project` and `/cd` do not fall through to
  // `unknown` for carrying one. `word` is compared and not `text`, which is
  // what keeps `/projects` — a whole command of its own — from being read as
  // `/project` with a stray `s` glued to the front of its argument.
  const [word = '', ...rest] = text.split(/\s+/);
  const argument = rest.length === 0 ? '' : text.slice(word.length).trim();
  if (word === '/message') {
    const parsed = parseCommand(text.slice(1), project);
    if (parsed.kind === 'request' && parsed.request.type.startsWith('message.'))
      return { kind: 'messaging', command: parsed.request };
    return {
      kind: 'retrieval-error',
      text:
        parsed.kind === 'usage'
          ? parsed.said
          : 'Use /message instances|instance|open|default|stop|archive|deliveries|delivery|cancel with a JSON payload or address.',
    };
  }
  // Direct operation grammar is shared by headless adapters and interpreted at the view edge.
  if (
    text.startsWith('/memory navigate ') &&
    !text.slice('/memory navigate '.length).trim().startsWith('{')
  ) {
    try {
      return {
        kind: 'retrieval',
        command: retrievalCommand(retrievalWords(text.slice(1)), project),
      };
    } catch (failure) {
      return {
        kind: 'retrieval-error',
        text:
          failure instanceof Error ? failure.message : errorMessage(failure),
      };
    }
  }
  if ([MEMORY_COMMAND, SEARCH_COMMAND, JOB_COMMAND].includes(word))
    return { kind: 'usage', command: word };
  // THE ROSTER AND THE TWO ORCHESTRATION SCREENS, EACH A LISTING THAT NARROWS
  // TO ONE ROW. `/bots` is not here: a bot's detail is the conversation you
  // have with it, and `/agents <name>` is where a capability list belongs.
  if (word === '/usage') {
    try {
      return {
        kind: 'usage-report',
        command: usageCommand(retrievalWords(text.slice(1)), {
          ...(project ? { project } : {}),
          ...usageContext,
        }),
      };
    } catch (failure) {
      return {
        kind: 'retrieval-error',
        text:
          failure instanceof Error ? failure.message : errorMessage(failure),
      };
    }
  }
  if (word === '/information') {
    try {
      return {
        kind: 'information',
        command: informationCommand(text.slice(1), project),
      };
    } catch (failure) {
      return {
        kind: 'retrieval-error',
        text:
          failure instanceof Error ? failure.message : errorMessage(failure),
      };
    }
  }
  if (word === '/conversation' || word === '/memory') {
    try {
      return {
        kind: 'retrieval',
        command: retrievalCommand(retrievalWords(text.slice(1)), project),
      };
    } catch (failure) {
      return {
        kind: 'retrieval-error',
        text:
          failure instanceof Error ? failure.message : errorMessage(failure),
      };
    }
  }
  if (word === AGENTS_COMMAND) {
    return narrowed(
      argument,
      AGENTS_COMMAND,
      (name) => ({ kind: 'agent', name, ask: listingAgents(project) }),
      () => ({ kind: 'agents', ask: listingAgents(project) }),
    );
  }
  if (word === '/design-orchestration') {
    if (!argument) return { kind: 'usage', command: word };
    const revise = /^--revise\s+([a-z][a-z0-9_]*)\s+([\s\S]+)$/.exec(argument);
    if (argument.startsWith('--revise') && !revise)
      return { kind: 'usage', command: word };
    return {
      kind: 'design',
      text: revise?.[2] ?? argument,
      ...(revise ? { revision: revise[1] } : {}),
    };
  }
  if (word === ORCHESTRATIONS_COMMAND) {
    return narrowed(
      argument,
      ORCHESTRATIONS_COMMAND,
      (name) => ({
        kind: 'orchestration',
        name,
        ask: listingDefinitions(project),
      }),
      () => ({ kind: 'orchestrations', ask: listingDefinitions(project) }),
    );
  }
  if (word === BOARD_COMMAND || word === SWARM_COMMAND) {
    const view = word === BOARD_COMMAND ? 'board' : 'swarm';
    const scope = argument || project;
    return {
      kind: 'board',
      view,
      ...(scope === undefined || scope === '' ? {} : { project: scope }),
    };
  }
  if (word === RUNS_COMMAND) {
    return narrowed(
      argument,
      RUNS_COMMAND,
      (id) => ({ kind: 'run', id, ask: readingRun(id) }),
      () => ({ kind: 'runs', ask: listingRuns() }),
    );
  }
  if (word === LOG_COMMAND || word === TRAJECTORY_COMMAND) {
    const view = word === LOG_COMMAND ? 'log' : 'trajectory';
    return narrowed(
      argument,
      word,
      (conversation) => ({ kind: 'trajectory', view, conversation }),
      () => ({ kind: 'trajectory', view }),
    );
  }
  if (word === WATCH_COMMAND) {
    return narrowed(
      argument,
      WATCH_COMMAND,
      (run) => ({ kind: 'watch', run }),
      () => ({ kind: 'watch' }),
    );
  }
  if (word === ANSWER_COMMAND) {
    return answeringOne(argument, waiting);
  }
  if (word === ALWAYS_COMMAND) {
    return argument === ''
      ? { kind: 'always', on: true }
      : argument === 'off'
        ? { kind: 'always', on: false }
        : argument === 'caps'
          ? { kind: 'capSet', key: 'auto-continue', value: ALWAYS_CAPS }
          : { kind: 'usage', command: ALWAYS_COMMAND };
  }
  if (word === CAP_COMMAND) {
    return capping(argument);
  }
  if (word === '/resume' || word === '/retry') {
    return argument === '' || /\s/.test(argument)
      ? { kind: 'usage', command: word }
      : { kind: 'resumeRun', id: argument };
  }
  if (word === CANCEL_COMMAND) {
    return argument === '' || /\s/.test(argument)
      ? { kind: 'usage', command: CANCEL_COMMAND }
      : { kind: 'cancelRun', id: argument, ask: cancellingRun(argument) };
  }
  if (word === DIAGNOSE_COMMAND) {
    return diagnosis(argument);
  }
  if (word === HERE_COMMAND) {
    return argument === ''
      ? { kind: 'here' }
      : { kind: 'here', name: argument };
  }
  if (word === PROJECT_COMMAND) {
    return argument === ''
      ? { kind: 'usage', command: PROJECT_COMMAND }
      : { kind: 'project', name: argument };
  }
  if (word === CD_COMMAND) {
    return argument === ''
      ? { kind: 'usage', command: CD_COMMAND }
      : { kind: 'cd', path: argument };
  }
  if (word === FIRE_COMMAND) {
    return argument === ''
      ? { kind: 'usage', command: FIRE_COMMAND }
      : { kind: 'fire', name: argument };
  }
  if (word === SCHEDULE_COMMAND) {
    return scheduling(argument);
  }
  if (word === APPROVALS_COMMAND) {
    return approving(argument);
  }
  if (word === SYNC_COMMAND) {
    const action = syncAction(argument);
    return action === undefined
      ? { kind: 'usage', command: SYNC_COMMAND }
      : { kind: 'sync', action };
  }
  if (text.startsWith('/')) {
    return { kind: 'unknown', named: text };
  }
  return { kind: 'utterance', text };
}

/**
 * A `caps:` setting `/cap` writes — `environment.ts`'s `CAP_KEYS`, spelled here rather than
 * imported, since this file is the parser's and the binding is the file's.
 */
export type CapKey =
  | 'steps'
  | 'budget'
  | 'auto-continue'
  | 'time'
  | 'failed-checks'
  | 'auto-increase';

/** `/cap`, `/cap steps N`, `/cap budget N`, `/cap auto N`, `/cap time N`, `/cap checks N`. */
function capping(argument: string): Typed {
  if (argument === '') {
    return { kind: 'cap' };
  }
  const [what, amount, ...rest] = argument.split(/\s+/);
  if (
    what === 'auto-increase' &&
    rest.length === 0 &&
    (amount === 'on' || amount === 'off')
  )
    return {
      kind: 'capSet',
      key: 'auto-increase',
      value: amount === 'on' ? 1 : 0,
    };
  const key: CapKey | undefined =
    what === 'steps'
      ? 'steps'
      : what === 'budget'
        ? 'budget'
        : what === 'auto'
          ? 'auto-continue'
          : what === 'time'
            ? 'time'
            : what === 'checks'
              ? 'failed-checks'
              : undefined;
  const value =
    amount !== undefined && /^[0-9]{1,7}$/.test(amount)
      ? Number(amount)
      : undefined;
  return key === undefined || value === undefined || rest.length > 0
    ? { kind: 'usage', command: CAP_COMMAND }
    : { kind: 'capSet', key, value };
}

/**
 * A listing that narrows to one row: nothing, which lists, or exactly one name.
 *
 * <p><b>Two names are the usage line and not the first of them.</b> A command
 * that took the first word and ignored the rest would answer a question nobody
 * asked — and the name of an agent or a run never has a space in it, so a second
 * word is a person who meant something else.
 *
 * @param one what to do with the single name, whatever it names
 * @param all what to do with none, which is the listing
 */
function narrowed(
  argument: string,
  command: string,
  one: (name: string) => Typed,
  all: () => Typed,
): Typed {
  if (argument === '') {
    return all();
  }
  const names = argument.split(/\s+/);
  const [name] = names;
  return names.length === 1 && name !== undefined
    ? one(name)
    : { kind: 'usage', command };
}

/**
 * What follows {@code /answer}: an id and the answer, or — with one run waiting
 * — the answer alone.
 *
 * <p><b>A first word that is a run id is always the id</b>, waiting or not. The
 * list of waiting runs is as old as the last check, and a run that began asking
 * since is not in it; reading its id as answer text for the one run that is
 * would send a person's words to a conductor they did not address.
 *
 * <p><b>Anything else goes to the one waiting run, or nowhere.</b> With two
 * waiting, or none this client has seen, it is held and the waiting runs are
 * shown instead: guessing which run somebody meant is this client answering on
 * their behalf.
 *
 * <p>The answer is handed over <b>with its own spacing intact</b>, only its
 * edges trimmed: an answer is prose, and a client that collapsed the whitespace
 * inside it would be editing what somebody said to a conductor. An id with
 * nothing after it is usage rather than a blank answer the server would refuse.
 *
 * @param waiting the ids of the runs the last check found waiting
 */
function answeringOne(argument: string, waiting: readonly string[]): Typed {
  if (argument === '') {
    return { kind: 'waitingRuns' };
  }
  const split = argument.search(/\s/);
  const first = split < 0 ? argument : argument.slice(0, split);
  if (first.startsWith(APPROVAL_ID_PREFIX)) {
    const decision = decisionIn(split < 0 ? '' : argument.slice(split + 1));
    return decision === undefined
      ? { kind: 'usage', command: ANSWER_COMMAND }
      : {
          kind: 'answerApproval',
          id: first,
          decision,
          ask: answeringApproval(first, decision),
        };
  }
  if (first.startsWith(RUN_ID_PREFIX)) {
    const answer = split < 0 ? '' : argument.slice(split + 1).trim();
    return answer === ''
      ? { kind: 'usage', command: ANSWER_COMMAND }
      : {
          kind: 'answerRun',
          id: first,
          answer,
          ask: answeringRun(first, answer),
        };
  }
  const [only, ...others] = waiting;
  if (only === undefined || others.length > 0) {
    return { kind: 'waitingRuns', held: true };
  }
  if (only.startsWith(APPROVAL_ID_PREFIX)) {
    // An approval takes a decision and nothing else: a sentence is held rather than
    // read as one, because "allow" is not a word to guess at.
    const decision = decisionIn(argument);
    return decision === undefined
      ? { kind: 'waitingRuns', held: true }
      : {
          kind: 'answerApproval',
          id: only,
          decision,
          ask: answeringApproval(only, decision),
        };
  }
  return {
    kind: 'answerRun',
    id: only,
    answer: argument,
    ask: answeringRun(only, argument),
  };
}

/** How every approval id starts: {@code RunApprovalStore}'s prefix. */
export const APPROVAL_ID_PREFIX = 'apr_';

/**
 * The decision a person typed for an approval, or nothing for anything else. `yes` and
 * `no` are `once` and `deny`: the narrowest allowance and the refusal. `project` is not
 * here, because it needs the prefix it covers, which `/approvals` is where to give.
 */
function decisionIn(text: string): Decision | undefined {
  switch (text.trim().toLowerCase()) {
    case 'once':
    case 'yes':
      return 'once';
    case 'conversation':
      return 'conversation';
    case 'deny':
    case 'no':
      return 'deny';
    default:
      return undefined;
  }
}

/**
 * What follows {@code /diagnose}: an optional id, then an optional question
 * behind {@code --}. The delimiter keeps a sentence from being mistaken for a
 * conversation id and leaves the question's spaces exactly as the person wrote
 * them after trimming its edges.
 */
function diagnosis(argument: string): Typed {
  if (argument === '') {
    return { kind: 'diagnose' };
  }
  if (argument === '--') {
    return { kind: 'usage', command: DIAGNOSE_COMMAND };
  }
  const current = argument.match(/^--\s+(.+)$/s);
  if (current !== null) {
    return { kind: 'diagnose', question: current[1]!.trim() };
  }
  const named = argument.match(/^(\S+)(?:\s+--\s+(.+))?$/s);
  if (named === null) {
    return { kind: 'usage', command: DIAGNOSE_COMMAND };
  }
  const conversation = named[1]!;
  const question = named[2]?.trim();
  return question === undefined
    ? { kind: 'diagnose', conversation }
    : { kind: 'diagnose', conversation, question };
}

/**
 * What follows `/schedule`: nothing or `list`, a sub-verb and one name, or a
 * sentence.
 *
 * <p><b>`list` is a sub-verb only when it is the whole remainder.</b> A
 * sentence can reasonably begin with it — "list the open PRs every morning" —
 * so `list` followed by anything more is read as that sentence.
 *
 * <p><b>The sub-verb is matched only as the first word</b>, so a sentence that
 * happens to start with "pause" is read as managing a schedule. That is
 * accepted rather than guarded: it is unlikely, and the usage line it gets
 * when it has more than one word after the verb says what happened.
 */
function scheduling(argument: string): Typed {
  if (argument === 'files' || /^(save|sync)(?:\s|$)/.test(argument))
    return { kind: 'schedule-file', text: `schedule ${argument}` };

  if (argument === '' || argument === 'list') {
    return { kind: 'schedules' };
  }
  const [verb = '', ...names] = argument.split(/\s+/);
  if (verb === 'pause' || verb === 'resume' || verb === 'forget') {
    const [name] = names;
    return names.length === 1 && name !== undefined
      ? { kind: 'managing', verb, name }
      : { kind: 'usage', command: `${SCHEDULE_COMMAND} ${verb}` };
  }
  return { kind: 'schedule', text: argument };
}

/**
 * What follows `/approvals`: nothing, which lists, or `revoke` and one id.
 * Anything else is the usage line, rather than a listing a person did not ask
 * for.
 */
function approving(argument: string): Typed {
  if (argument === '') {
    return { kind: 'approvals' };
  }
  const [verb = '', ...ids] = argument.split(/\s+/);
  const [id] = ids;
  return verb === 'revoke' && ids.length === 1 && id !== undefined
    ? { kind: 'revoking', id, ask: revokingApproval(id) }
    : { kind: 'usage', command: APPROVALS_COMMAND };
}

/**
 * What Tab may finish, and the characters it would be finishing.
 *
 * <b>`word` is what a completer replaces</b> — `node:readline` takes the pair
 * and swaps the one for the other — and it is the tail of the line rather than
 * the whole of it, so a name mentioned mid-sentence can be completed where it
 * is written.
 */
export interface Completion {
  /** The characters being finished. Empty when there is nothing under Tab. */
  readonly word: string;

  /** Everything that could finish them, in the order a person reads them. */
  readonly matches: readonly string[];
}

/**
 * What Tab offers for this line, which is <b>never a name nobody declared</b>.
 *
 * <h2>Two vocabularies, and where each one is a vocabulary</h2>
 *
 * <p><b>Commands complete only at the start of a line</b>, because that is the
 * only place a command is one: {@link typed} compares the <i>whole</i> trimmed
 * line to each of {@link COMMANDS}, so `what does /bo` is a question however it
 * is finished. Offering `/bots` there would complete somebody into a sentence
 * about a command rather than into the command, and this client would then send
 * it to an agent as a question.
 *
 * <p><b>Names complete anywhere else</b>, on the word under the cursor, which is
 * where a name is actually written — inside a question about one.
 *
 * <h2>Completion that guesses is worse than none</h2>
 *
 * <p>A name Tab offers reads as a name that works. This client has exactly one
 * list of names that do — the roster the server declared — and {@link
 * completable} is what narrows it to the ones this deployment will serve. There
 * is no fuzzy match here, no history, and no memory of what was typed before:
 * every match is a prefix of something the server sent.
 *
 * <p><b>An empty word completes nothing at all</b>, rather than everything. Tab
 * on nothing is not a request for the roster — `/bots` is the screen for that,
 * and it says who each of them is rather than dumping names.
 *
 * @param line what has been typed up to the cursor, which is what a completer
 *     is handed
 * @param names the served names, from {@link completable} over the roster
 */
export function completing(
  line: string,
  names: readonly string[],
  commands: readonly string[] = [],
): Completion {
  const word = /\S*$/.exec(line)?.[0] ?? '';
  if (word === '') {
    return { word, matches: [] };
  }
  const commanding = word.startsWith('/') && line.trimStart() === word;
  const candidates = commanding ? [...COMMANDS, ...commands] : names;
  return { word, matches: candidates.filter((each) => each.startsWith(word)) };
}

/**
 * The names out of a roster that Tab may finish: the ones that will answer.
 *
 * <p><b>A definition this server read and refused is left out</b>, which is the
 * one place this differs from "every name the server sent". It is a name the
 * server declared and a name that cannot run — `whoAnswers` refuses it with the
 * server's own reason — and a completion reads as a name that works, so
 * offering it hands somebody a name that fails at the first thing they ask of
 * it. `/agents` still lists it, with the refusal beside it, which is the screen
 * where that name is information rather than a suggestion.
 */
export function completable(rows: readonly Agent[]): string[] {
  return rows.filter((row) => row.served).map((row) => row.name);
}

/** A turn about to be spoken: the frame to send, and nothing known yet. */
export function taking(
  conversation: string,
  agent: string,
  text: string,
  session: string,
): Turn {
  return {
    ask: speaking(conversation, agent, text, session),
    progress: [],
    held: [],
  };
}

/** Whether this turn is over, by an ending or by a refusal. */
export function finished(turn: Turn): boolean {
  return turn.stopped !== undefined || turn.refused !== undefined;
}

export function followed(push: unknown): Progress {
  const event = fieldsOf(push);
  const job = textAt(event, 'job');
  const kind = textAt(event, 'kind');
  if (job === undefined || kind === undefined) {
    return {
      kind: 'unreadable',
      said: 'a frame arrived that is not a job event',
    };
  }
  const agent = textAt(event, 'agent') ?? '';
  const steps = countAt(event, 'steps') ?? 0;
  const modelCalls = countAt(event, 'modelCalls') ?? 0;
  switch (kind) {
    case 'started':
      return { kind: 'started', job, agent };
    case 'model_call':
      return { kind: 'step', job, agent, steps, modelCalls };
    case 'tool_called':
      return { kind: 'tool', job, agent, tool: textAt(event, 'tool') ?? '' };
    case 'alive':
      return { kind: 'alive', job, agent, steps, modelCalls };
    case 'ended':
      return {
        kind: 'ended',
        job,
        agent,
        ending: textAt(event, 'ending') ?? '',
        steps,
        modelCalls,
      };
    default:
      return { kind: 'other', job, named: kind };
  }
}

/** Whether this reading belongs to a job at all, and if so to which. */
function jobOf(seen: Progress): string | undefined {
  return seen.kind === 'unreadable' ? undefined : seen.job;
}

/** This turn with one more event folded into it, and its ending if that is it. */
function attached(turn: Turn, seen: Progress): Turn {
  return {
    ...turn,
    progress: [...turn.progress, seen],
    ...(seen.kind === 'ended' ? { stopped: seen } : {}),
  };
}

/**
 * This turn, having seen one frame that was not an answer to anything.
 *
 * Three outcomes, and the middle one is the interleaving contract:
 *
 *   - a frame this build cannot read is <b>dropped</b>. It belongs to no job,
 *     so attaching it to this turn would put an invention in somebody's
 *     transcript;
 *   - while the handle is unknown, the event is <b>held</b>. It may be this
 *     turn's — spec §4.3 says an event can outrun the answer that caused it —
 *     and it may be another job's, and there is nothing yet to tell them apart;
 *   - once the handle is known, it is attached if it matches and dropped if it
 *     does not.
 */
export function following(turn: Turn, push: unknown): Turn {
  const seen = followed(push);
  const job = jobOf(seen);
  if (job === undefined) {
    return turn;
  }
  if (turn.job === undefined) {
    return { ...turn, held: [...turn.held, seen] };
  }
  return job === turn.job ? attached(turn, seen) : turn;
}

/**
 * This turn, having been answered by the server.
 *
 * <b>Where `ACCEPTED` and `OK` are kept apart.</b> Only an `ACCEPTED` carrying
 * a handle starts a run; every other code — an `OK` included, and especially an
 * `OK` — leaves the turn refused with nothing to follow. See the header.
 *
 * It is also where the holding area is decided: the handle is what says which
 * of the events seen so far were this turn's, so they are replayed through
 * {@link following}'s own rule rather than through a second copy of it.
 */
export function answering(turn: Turn, answer: Answer): Turn {
  const body = bodyOf(answer, ACCEPTED);
  const job = body === undefined ? undefined : textAt(body, 'id');
  if (job === undefined) {
    return { ...turn, refused: refusedBecause(answer) };
  }
  return handing(turn, job);
}

/**
 * This turn, now that something named the job it is.
 *
 * <p><b>Apart from {@link answering} because `agent.run` is not the only frame
 * that starts a turn.</b> An `approval.answer` answers `OK` with the continuing
 * turn's `job` — not an `ACCEPTED` — and its events can outrun that answer
 * exactly as a run's can, so it takes the same holding area, decided by the
 * same rule.
 */
export function handing(turn: Turn, job: string): Turn {
  const handed: Turn = { ...turn, job, held: [] };
  return turn.held.reduce(
    (folded, seen) => (jobOf(seen) === job ? attached(folded, seen) : folded),
    handed,
  );
}

/**
 * Why this answer is not a run, in a sentence.
 *
 * The server's own whenever it sent one — client design §5.2, and the reason
 * `said`'s absence is load-bearing all the way down from `envelope.ts`. The two
 * arms below are the cases a server sentence would never cover, <b>because the
 * server does not think anything went wrong in either</b>: it answered a
 * success. Everything else is `wording.refusal`'s, which is where a sentence
 * about a refusal belongs and where it is written once.
 */
function refusedBecause(answer: Answer): string {
  if (answer.said !== undefined) {
    return answer.said;
  }
  if (answer.code === ACCEPTED) {
    return 'the run was accepted but the answer named no handle to follow it by';
  }
  if (answer.code === OK) {
    return (
      `${AGENT_RUN} answered OK where a run answers ${ACCEPTED}:` +
      ' nothing was started, and there is no handle to follow'
    );
  }
  return refusal(answer);
}

/**
 * One seam in a conversation's history: how far back it reaches, and what was
 * written in place of what it folded.
 *
 * <b>The summary goes through the grammar and the ordinal does not need to.</b>
 * A model wrote the summary — it is what the next turn was shown in place of
 * the history it replaces — so it is markdown for the same reason an answer is.
 */
export interface Fold {
  /**
   * The last turn this summary stands for.
   *
   * <b>Every turn from the conversation's first through this one was folded
   * into it, and all of them are still in the log at their own ordinals.</b> A
   * compaction deletes nothing; what this number says is how far back the
   * seam reaches, so that a reader knows which part of what they are looking
   * at the agent was being shown as a summary.
   */
  readonly throughOrdinal: number;

  readonly summary: readonly Block[];
}

/** Whether a fallback target produced this entry — the one provenance question a
 *  reader asks first, and false for a primary answer and for a row that names no
 *  model at all. */
export function answeredByFallback(entry: Entry): boolean {
  return entry.dispatch === 'fallback';
}

/**
 * One thing the chat view shows out of the log. <b>The same union for replay and for a
 * catch-up</b> (spec 2026-09-28 §4-§5): a turn a person reads back after a restart and one
 * that arrives live while they watch are drawn by one renderer, speaker included.
 */
export type Logged =
  | { readonly kind: 'person'; readonly turn: number; readonly text: string }
  | {
      readonly kind: 'harness';
      readonly turn: number;
      readonly source: string;
      readonly text: string;
    }
  | {
      readonly kind: 'answer';
      readonly turn: number;
      readonly body: readonly Block[];
      readonly cut?: { readonly shown: number; readonly length: number };
    }
  | { readonly kind: 'seam'; readonly fold: Fold };

/**
 * What the chat view shows of these entries, in their order: every utterance, attributed —
 * a `harness` one to its source, anything else (a `person`'s, or one written before speakers
 * were recorded) to the person; the answer that ends a turn; a fold, as a seam. Everything
 * else — tool results, answers that asked for tools, failed attempts, notices, hooks,
 * thinking, diagnostics — stays hidden, as it is today.
 */
export function loggedFrom(entries: readonly Entry[]): Logged[] {
  const shown: Logged[] = [];
  for (const each of entries) {
    const text = each.text;
    if (text === undefined) {
      continue;
    }
    if (each.kind === 'utterance') {
      shown.push(
        each.speaker === 'harness'
          ? {
              kind: 'harness',
              turn: each.turnOrdinal,
              source: each.speakerName ?? 'harness',
              text,
            }
          : { kind: 'person', turn: each.turnOrdinal, text },
      );
    } else if (
      each.kind === 'answer' &&
      (each.asked ?? 0) === 0 &&
      text.trim() !== ''
    ) {
      shown.push({
        kind: 'answer',
        turn: each.turnOrdinal,
        body: parse(text),
        ...(each.cut === true
          ? {
              cut: {
                shown: [...Array.from(text)].length,
                length: each.length ?? [...Array.from(text)].length,
              },
            }
          : {}),
      });
    } else if (each.kind === 'summary') {
      shown.push({
        kind: 'seam',
        fold: { throughOrdinal: each.turnOrdinal, summary: parse(text) },
      });
    }
  }
  return shown;
}

/** A conversation's history, rebuilt from the log: what to draw, and the two counts the banner says. */
export interface Replay {
  readonly items: readonly Logged[];
  /** How many turns the log holds — its latest turn's ordinal. */
  readonly held: number;
  /** How many of them are drawn. */
  readonly shown: number;
}

/**
 * What was read of the log's tail, up to the log's reach when it was read, to be drawn.
 *
 * <p>Entries past `through` were written after the reading began and are a push's to show. The
 * read is already bounded — {@link LOG_BACK} entries of the kinds drawn — so everything else is
 * drawn; `/earlier` is what goes further back. A fold whose summary was written within the read
 * is drawn where it fell, above the turns after it.
 */
export function replaying(entries: readonly Entry[], through: number): Replay {
  const held = entries.filter((each) => each.ordinal <= through);
  const items = loggedFrom(held);
  return {
    items,
    held: held.reduce((latest, each) => Math.max(latest, each.turnOrdinal), 0),
    shown: new Set(
      items.flatMap((item) =>
        item.kind === 'person' || item.kind === 'harness' ? [item.turn] : [],
      ),
    ).size,
  };
}

/** What a catch-up draws, and how far down the log it leaves the screen. */
export interface CatchUp {
  readonly items: readonly Logged[];
  readonly through: number;
}

/**
 * What was committed after `from` and up to `through`, to be drawn now.
 *
 * <p><b>`streamed`: a turn of this client's own just ended</b>, and its stream already put it
 * on the screen. It is the latest turn committed up to `through` — the server tells a follower
 * a turn's log grew before it tells it that turn ended — so it is left out, and any earlier
 * turn whose push was held while it streamed is drawn. Entries past `through` are a later
 * push's.
 *
 * <p><b>An entry read twice is drawn once</b>: the reading is paged,
 * and a fold committed between two pages sorts back among the turn it folds, moving every row
 * after it down one — so the next page begins with the row the last one ended on.
 */
export function catchingUp(
  entries: readonly Entry[],
  from: number,
  through: number,
  streamed: boolean,
): CatchUp {
  const seen = new Set<number>();
  const committed = entries.filter((each) => {
    if (
      each.ordinal <= from ||
      each.ordinal > through ||
      seen.has(each.ordinal)
    ) {
      return false;
    }
    seen.add(each.ordinal);
    return true;
  });
  const own = streamed
    ? committed.reduce((latest, each) => Math.max(latest, each.turnOrdinal), 0)
    : undefined;
  return {
    items: loggedFrom(
      committed.filter((each) => own === undefined || each.turnOrdinal < own),
    ),
    through: Math.max(from, through),
  };
}

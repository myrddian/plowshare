import { isList } from '../binding/values.ts';
import { commandEntry, type CommandEntry } from './conversation-replies.ts';
// Shared domain readers. Unknown fields are tolerated; frontend rendering stays outside this module.
import {
  OK,
  bodyOf,
  countAt,
  fieldsOf,
  textAt,
  type Answer,
} from './response.ts';
import { CONVERSATION_APPENDED } from './session.ts';

/** The conversation a person is speaking into, or one row of a listing. */
export interface Conversation {
  readonly id: string;

  /** The home it is held in, when the server named one. */
  readonly project?: string;

  /**
   * What the first turn in it was about, as the server derived and stored it.
   *
   * <p><b>Absent for a conversation nothing has named</b>, which is not a rare
   * state: there was no backfill, so every conversation that existed before
   * the column has no title permanently, and so does every conversation
   * opened and never spoken into — including every one this client opens, at
   * the moment it opens it.
   *
   * <p><b>A present null on the wire becomes an absence here</b>, which is
   * `textAt`'s rule and not a special case. The server sends `"title": null`
   * rather than omitting the key, deliberately, so that a client can tell a
   * conversation with no name from a server too old to have names; this module
   * has no third state to carry that distinction into and does not invent one
   * — nothing this client does differs between the two.
   *
   * <p><b>And it is not filled in here.</b> One derivation in one place is the
   * whole point of the column. What an unnamed conversation is <i>shown</i> as
   * is `wording.describeConversations`' decision, said out loud there.
   */
  readonly title?: string;
}

/**
 * One project, by the name a person chose for it, and where its files are.
 *
 * <b>Two more fields than before, and still not all four.</b> `lent` and
 * `exclusions` stay off, on {@link describeProjects}' reasoning: neither
 * answers "who can I talk to" or "which of these to move to". `workspace` and
 * `machine` earn their place because `/here`, `/project` and the startup offer
 * all have to say where a project's files actually are, which is a question
 * the bare name cannot answer.
 */
export interface Project {
  readonly name: string;
  readonly kind?: 'project' | 'personal';
  readonly type?: string;
  readonly role?: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER';
  readonly readOnly?: boolean;
  readonly displayName?: string;
  readonly routingIdentity?: string;
  readonly writePaths?: readonly string[];

  /** Where the files are on the machine that holds them. Never resolved here. */
  readonly workspace?: string;

  /** The client machine that roots it; absent when the server holds it. */
  readonly machine?: string;
}

/**
 * One thing this deployment declared: what a client choosing between them
 * needs, and what a person asking about one of them is shown.
 *
 * <b>All eleven of an `AgentView`'s components.</b> Four of them — `tools`,
 * `calls`, `scopes` and `orchestrations` — were read and dropped for as long as
 * the roster was the only screen: `/bots` and `/agents` answer "who can I talk
 * to", and a capability list on a row would make that question something a
 * person reads out of a table. `/agents <name>` is the second screen, and it is
 * the reason the four are kept.
 *
 * <p><b>`agent.list` is the only place any of the four is on the wire.</b> There
 * is no server-wide tool registry to ask, so what an agent holds is legible
 * exactly per agent — which is what the detail view says, and what it must not
 * be read as implying more than.
 */
export interface Agent {
  readonly displayName?: string;
  readonly origin?: string;
  /** What `PLOWSHARE_AGENT` takes, and what `agent.run` names. */
  readonly name: string;

  /**
   * Whether this is somebody to talk to rather than a role to invoke.
   *
   * <p><b>False for a definition this server could not read</b>, and that is
   * the server's own ruling rather than this module's default: a file that
   * failed to parse has no `bot:` to report, so `AgentView.disabled` says the
   * honestly sayable thing — nothing here can be talked to. The consequence
   * for this client is the one {@link whoAnswers} has to be careful about: a
   * refused definition is not evidence of an agent, it is evidence of
   * nothing.
   */
  readonly bot: boolean;

  /** Whether it can be run at all. False for one this server read and refused. */
  readonly served: boolean;

  /**
   * What this server took away, in the server's own sentences.
   *
   * <p>One entry — the refusal — for a definition that is not served; one per
   * delegation route or grant name dropped from one that is. Empty in the
   * ordinary case.
   */
  readonly withheld: readonly string[];

  /**
   * Who or what this is, in the definition's own words.
   *
   * <p><b>An empty string for a definition this server could not read</b>,
   * `AgentView.disabled`'s own rule: a file that failed to parse has no
   * `description:` to report. It is also what a server too old to send the
   * field looks like here, since {@link textAt} answers the same way for a
   * missing key as for one that could not be read — this module has no third
   * state to carry the difference into and does not invent one.
   */
  readonly description: string;

  /**
   * Whether this tier's `bots/default` names this row.
   *
   * <p>At most one row is. False from a server too old to send the field, which
   * is the same as that server naming no default — the old behaviour, exactly.
   */
  readonly preferred: boolean;

  /**
   * The specifier this agent's requests go out under, as its definition
   * writes it — a class such as `coder`, or a wire model.
   *
   * <p>Absent for a definition this server could not read, and from a server
   * too old to send it. Not what a pool resolved it to: that is decided per
   * call and this is read once, at sign-in.
   */
  readonly model?: string;

  /**
   * The capabilities this agent holds, in the server's own names.
   *
   * <p><b>Structural and not instructional</b>, which is `AgentView`'s own
   * ruling on the field: an agent without a file tool cannot open a file,
   * whatever it is asked to do. So this says what an agent <i>can</i> do
   * rather than what it was asked to confine itself to.
   *
   * <p>Empty for a definition this server read and refused, whose declarations
   * never took effect — and empty, indistinguishably, from a server that did
   * not send the field. This module carries no third state for that and does
   * not invent one; `mirrors-the-server.test.ts` is what keeps the name true.
   */
  readonly tools: readonly string[];

  /**
   * The agents it may delegate to. Empty for one that may not — and empty
   * exactly when `tools` lacks `agent_run`, which `AgentRegistry` refuses a
   * definition for disagreeing about.
   */
  readonly calls: readonly string[];

  /**
   * The places it may reach and how much of each, each written the way an
   * agent file writes it: `workspace:read`.
   *
   * <p><b>The enforced set and not a description of one.</b> The loader
   * refuses a graph in which a callee holds a grant its caller does not, and
   * `LocalProvider` cannot widen what it was handed — so a person reading this
   * is reading where the agent can reach.
   */
  readonly scopes: readonly string[];

  /** The orchestrations it may start. Empty from a server too old to say. */
  readonly orchestrations: readonly string[];
  readonly skills?: readonly string[];
  readonly commands?: readonly CommandEntry[];
}

/** A boolean field. Anything that is not `true` is false, including absent. */
function flagAt(fields: Record<string, unknown>, name: string): boolean {
  return fields[name] === true;
}

/** A followed log that grew, and the highest ordinal it now holds. */
export interface Appended {
  readonly conversation: string;
  readonly through: number;
}

/** The push that says a followed log grew, or nothing because this push is not one. */
export function appendedOf(push: unknown): Appended | undefined {
  const frame = fieldsOf(push);
  if (textAt(frame, 'kind') !== CONVERSATION_APPENDED) {
    return undefined;
  }
  const conversation = textAt(frame, 'conversation');
  const through = countAt(frame, 'through');
  return conversation === undefined || through === undefined
    ? undefined
    : { conversation, through };
}

/** A page of the log read backwards, turned round to be drawn. */
export interface BackPage {
  /** What was read, in conversation order — `(turnOrdinal, ordinal)`, a forward reading's. */
  readonly entries: readonly Entry[];
  /** How far the whole log reached when it was read — not the last row read. */
  readonly through: number;
  /** The smallest ordinal read, which the next `/earlier` reads before; nothing on an empty page. */
  readonly oldest?: number;
  /** Whether anything the chat draws lies before `oldest`. */
  readonly more: boolean;
  /** How many rows the reading covers, when the server said. */
  readonly total?: number;
}

/**
 * A page read backwards ({@link readingTail}, {@link readingEarlier}), or nothing off a refusal.
 *
 * <p><b>Turned round into conversation order, not merely reversed.</b> The server reads back by
 * ordinal, which is arrival; a fold's summary is written after the turns it stands for and a
 * forward reading sorts it back among them. Sorting by turn then ordinal draws it where a
 * forward reading — a catch-up, or the replay this one replaced — would have.
 *
 * <p>`oldest` falls back to the smallest ordinal on the page, and `more` to false — a server
 * that did not say is not offering anything earlier.
 */
export function backPageOf(answer: Answer): BackPage | undefined {
  if (answer.code !== OK || !isList(fieldsOf(answer.payload)['entries'])) {
    return undefined;
  }
  const body = fieldsOf(answer.payload);
  const entries = entriesOf(answer).sort(
    (one, other) =>
      one.turnOrdinal - other.turnOrdinal || one.ordinal - other.ordinal,
  );
  const oldest =
    countAt(body, 'oldest') ??
    (entries.length === 0
      ? undefined
      : Math.min(...entries.map((each) => each.ordinal)));
  const total = countAt(body, 'total');
  return {
    entries,
    through:
      countAt(body, 'through') ??
      entries.reduce((most, each) => Math.max(most, each.ordinal), 0),
    ...(oldest === undefined ? {} : { oldest }),
    more: flagAt(body, 'more'),
    ...(total === undefined ? {} : { total }),
  };
}

/** How far the log reaches — its highest ordinal — off a page, or nothing off a refusal. */
export function logThrough(answer: Answer): number | undefined {
  const body = answer.code === OK ? fieldsOf(answer.payload) : {};
  return countAt(body, 'through');
}

/**
 * The conversation a `conversation.open` answer opened, or nothing.
 *
 * Nothing for any answer that is not an `OK` carrying an id — the caller reads
 * the refusal through `wording.refusal`, which is where the server's own
 * sentence is preferred to this client's.
 */
export function opened(answer: Answer): Conversation | undefined {
  const body = bodyOf(answer, OK);
  const id = body === undefined ? undefined : textAt(body, 'id');
  if (body === undefined || id === undefined) {
    return undefined;
  }
  return conversationIn(body);
}

/**
 * The conversation a `conversation.latest` answer named, or nothing.
 *
 * <h2>Nothing means two things, and the code is what tells them apart</h2>
 *
 * <p>{@link projects} and {@link conversations} answer nothing for a refusal
 * alone, so that a caller cannot print an empty listing when what happened is
 * that the server would not say. This read has a third state those do not: an
 * `OK` carrying no payload, which is the ordinary answer for an agent with no
 * conversation in this tier yet — every first run. <b>Both arrive here as
 * nothing</b>, and the caller is expected to have looked at {@link Answer.code}
 * first, exactly as {@link Turn} handling does.
 *
 * <p>A third state carried in the return type was the alternative and was
 * rejected: the caller has the answer in its hand either way, and the two
 * situations do not merely differ in wording — one continues into a new
 * conversation and the other must not continue at all.
 */
export function continued(answer: Answer): Conversation | undefined {
  const body = bodyOf(answer, OK);
  return body === undefined ? undefined : conversationIn(body);
}

/** One `ConversationView`, in the three fields this module has a use for. */
function conversationIn(
  fields: Record<string, unknown>,
): Conversation | undefined {
  const id = textAt(fields, 'id');
  if (id === undefined) {
    return undefined;
  }
  const project = textAt(fields, 'project');
  const title = textAt(fields, 'title');
  return {
    id,
    ...(project === undefined ? {} : { project }),
    // Absent for a null, which `textAt` already decides. See
    // `Conversation.title` for why the present null is not carried further.
    ...(title === undefined ? {} : { title }),
  };
}

/** Every row of an answer that is an `OK` carrying a list, or nothing. */
function rowsOf(answer: Answer): unknown[] | undefined {
  if (answer.code !== OK || !isList(answer.payload)) {
    return undefined;
  }
  return answer.payload;
}

/**
 * The projects a `project.list` answer named, or nothing for a refusal.
 *
 * <p><b>Nothing, and not an empty list.</b> "There are no projects" and "the
 * server would not say" are different facts, and only the first is one a client
 * may put on a screen — so the caller is made to look at the code and reach for
 * {@code wording.refusal}, exactly as {@link opened} makes it.
 */
export function projects(answer: Answer): Project[] | undefined {
  const rows = rowsOf(answer);
  if (rows === undefined) {
    return undefined;
  }
  return rows
    .map((row) => fieldsOf(row))
    .map((fields): Project | undefined => {
      const name = textAt(fields, 'name');
      if (name === undefined) {
        return undefined;
      }
      const workspace = textAt(fields, 'workspace');
      const machine = textAt(fields, 'machine');
      return {
        name,
        ...(typeof fields['type'] === 'string' ? { type: fields['type'] } : {}),
        ...(['VIEWER', 'CONTRIBUTOR', 'MANAGER'].includes(
          String(fields['role']),
        )
          ? { role: fields['role'] as 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER' }
          : {}),
        ...(typeof fields['readOnly'] === 'boolean'
          ? { readOnly: fields['readOnly'] }
          : {}),
        ...(typeof fields['displayName'] === 'string'
          ? { displayName: fields['displayName'] }
          : {}),
        ...(typeof fields['routingIdentity'] === 'string'
          ? { routingIdentity: fields['routingIdentity'] }
          : {}),
        ...(isList(fields['writePaths']) &&
        fields['writePaths'].every((path) => typeof path === 'string')
          ? { writePaths: fields['writePaths'] }
          : {}),
        ...(workspace === undefined ? {} : { workspace }),
        ...(machine === undefined ? {} : { machine }),
        ...(fields['kind'] === 'personal'
          ? { kind: 'personal' as 'personal' | 'project' }
          : {}),
      };
    })
    .filter((row): row is Project => row !== undefined);
}

/**
 * The conversations a `conversation.list` answer named, or nothing for a
 * refusal.
 *
 * <p>Rows are read through the same {@link conversationIn} that {@link opened}
 * uses, because both are a `ConversationView` and a second reading of one field
 * is a second place for a null title to be handled differently.
 */
export function conversations(answer: Answer): Conversation[] | undefined {
  const rows = rowsOf(answer);
  if (rows === undefined) {
    return undefined;
  }
  return rows
    .map((row) => conversationIn(fieldsOf(row)))
    .filter((row): row is Conversation => row !== undefined);
}

/**
 * What an `agent.list` answer declared, or nothing for a refusal.
 *
 * <p><b>Nothing, and not an empty list</b>, on {@link projects}' rule. An empty
 * roster is a real and meaningful state here — {@code AgentListHandler} answers
 * one rather than a refusal, deliberately, so that a console meeting an empty
 * deployment does not report it broken — and it is exactly the state that must
 * not be confused with "the server would not say".
 *
 * <p><b>`served` and `bot` are read as flags, so anything that is not `true` is
 * false.</b> That is the safe direction for both: an unreadable row is treated
 * as not served rather than run, and as not a bot rather than talked to.
 * `mirrors-the-server.test.ts` holds both names against `AgentView.java`,
 * because a renamed boolean does not fail — it reads false, and a client would
 * confidently report a deployment that serves nothing.
 */
export function agents(answer: Answer): Agent[] | undefined {
  const rows = rowsOf(answer);
  if (rows === undefined) {
    return undefined;
  }
  return rows
    .map((row) => agentIn(fieldsOf(row)))
    .filter((row): row is Agent => row !== undefined);
}

/**
 * One `AgentView`, whole — every component of it.
 *
 * <p><b>The four declaration lists are read tolerantly and the row is not
 * dropped for them</b>, unlike `name`: a list this build cannot read reads as
 * empty, which the detail view renders as "holds nothing" rather than as a row
 * that is not there. A roster that lost a whole agent over an unreadable
 * `scopes` would be the worse of the two answers, since the name is still a name
 * that runs.
 */
function agentIn(fields: Record<string, unknown>): Agent | undefined {
  const name = textAt(fields, 'name');
  if (name === undefined) {
    return undefined;
  }
  return {
    name,
    ...(textAt(fields, 'displayName')
      ? { displayName: textAt(fields, 'displayName')! }
      : {}),
    ...(textAt(fields, 'origin') ? { origin: textAt(fields, 'origin')! } : {}),
    bot: flagAt(fields, 'bot'),
    served: flagAt(fields, 'served'),
    withheld: sentencesAt(fields, 'withheld'),
    description: textAt(fields, 'description') ?? '',
    preferred: flagAt(fields, 'preferred'),
    ...modelIn(fields),
    tools: sentencesAt(fields, 'tools'),
    calls: sentencesAt(fields, 'calls'),
    scopes: sentencesAt(fields, 'scopes'),
    orchestrations: sentencesAt(fields, 'orchestrations'),
    ...(isList(fields['skills'])
      ? { skills: sentencesAt(fields, 'skills') }
      : {}),
    ...(isList(fields['commands'])
      ? { commands: fields['commands'].filter(commandEntry) as CommandEntry[] }
      : {}),
  };
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

/** `model`, spread so an absent one stays an absent key. */
function modelIn(fields: Record<string, unknown>): { model?: string } {
  const model = textAt(fields, 'model');
  return model === undefined || model === '' ? {} : { model };
}

/** One command a run asked a person about — `ApprovalFrames.View`, in the fields this client reads. */
export interface Approval {
  readonly id: string;
  readonly conversation: string;
  readonly askedIn?: string;
  readonly agent: string;
  /** `server` or `local`: whose machine the command would run on. */
  readonly side: string;
  /** The whole command, program first. Empty only for a set, which names its own in `commands`. */
  readonly command: readonly string[];
  /**
   * An acceptance set's commands, each program first — one approval asked once for all of them
   * (V67). Absent for an approval of one command. A set takes once, conversation or deny, never
   * a project prefix.
   */
  readonly commands?: readonly (readonly string[])[];
  /** The command judge's one line about it, when it was shown it — the model's words, as data. */
  readonly judged?: string;
  readonly cwd: string;
  /** A hook's `ask` reason; absent when the mode asked. */
  readonly reason?: string;
  /** `asked`, `allowed`, `denied`, `used` or `revoked`. */
  readonly state: string;
  /** Absent while asked. */
  readonly scope?: string;
  /** The leading arguments a project approval covers; absent for any other scope. */
  readonly prefix?: readonly string[];
  /** Where a project prefix starts: the program and its first argument, as the server chose. */
  readonly defaultPrefix: readonly string[];
}

/** A list of strings, or nothing for anything that is not exactly that. */
function wordsAt(
  fields: Record<string, unknown>,
  name: string,
): readonly string[] | undefined {
  const found = fields[name];
  return isList(found) && found.every((word) => typeof word === 'string')
    ? found
    : undefined;
}

/**
 * The approvals an `approval.list` answer named, or nothing for a refusal.
 *
 * <p><b>A row this build cannot read is dropped, not the whole list</b>, on
 * {@link projects}' rule rather than {@link inboxPageOf}'s: each question is
 * answered by its own id, so one unreadable row takes nothing from the others,
 * and it stays open on the server for a client that can read it.
 */
export function approvalsOf(answer: Answer): Approval[] | undefined {
  const body = bodyOf(answer, OK);
  const rows = body?.['approvals'];
  if (!isList(rows)) {
    return undefined;
  }
  return rows
    .map((row) => approvalIn(fieldsOf(row)))
    .filter((row): row is Approval => row !== undefined);
}

/** A set's commands: a non-empty list of non-empty commands, or nothing for anything else. */
function commandsAt(
  fields: Record<string, unknown>,
): readonly (readonly string[])[] | undefined {
  const found = fields['commands'];
  if (!isList(found) || found.length === 0) {
    return undefined;
  }
  const read = found.map((each) => wordsAt({ each }, 'each'));
  return read.every((each) => each !== undefined && each.length > 0)
    ? (read as readonly (readonly string[])[])
    : undefined;
}

/** One `View`, in the fields it is made of. */
function approvalIn(fields: Record<string, unknown>): Approval | undefined {
  const id = textAt(fields, 'id');
  const command = wordsAt(fields, 'command');
  const commands = commandsAt(fields);
  // A set names its commands and runs no one command of its own; anything else names its one.
  if (
    id === undefined ||
    command === undefined ||
    (command.length === 0 && commands === undefined)
  ) {
    return undefined;
  }
  const judged = textAt(fields, 'judged');
  const reason = textAt(fields, 'reason');
  const scope = textAt(fields, 'scope');
  const prefix = wordsAt(fields, 'prefix');
  const askedIn = textAt(fields, 'askedIn');
  return {
    id,
    conversation: textAt(fields, 'conversation') ?? '',
    ...(askedIn === undefined ? {} : { askedIn }),
    agent: textAt(fields, 'agent') ?? '',
    side: textAt(fields, 'side') ?? '',
    command,
    ...(commands === undefined ? {} : { commands }),
    ...(judged === undefined ? {} : { judged }),
    cwd: textAt(fields, 'cwd') ?? '',
    ...(reason === undefined ? {} : { reason }),
    state: textAt(fields, 'state') ?? '',
    ...(scope === undefined ? {} : { scope }),
    ...(prefix === undefined ? {} : { prefix }),
    defaultPrefix: wordsAt(fields, 'defaultPrefix') ?? [],
  };
}

/** What an `approval.answer` said: the decision stands, and either a turn continues or the conversation was busy. */
export interface Answered {
  readonly id: string;
  /** `allowed` or `denied`. */
  readonly state: string;
  /** The continuing turn's job, to follow. Absent when the conversation was busy. */
  readonly job?: string;
  readonly busy: boolean;
  /** The server's sentence about why it was busy. */
  readonly note?: string;
}

/** The answer an `approval.answer` carried, or nothing for a refusal. */
export function answeredOf(
  answer: Answer,
  expected?: string,
): Answered | undefined {
  const body = bodyOf(answer, OK);
  const id = body === undefined ? undefined : textAt(body, 'id');
  if (
    body === undefined ||
    id === undefined ||
    id.trim() === '' ||
    (expected !== undefined && id !== expected) ||
    typeof body['busy'] !== 'boolean' ||
    typeof body['state'] !== 'string' ||
    body['state'].trim() === '' ||
    (body['job'] != null &&
      (typeof body['job'] !== 'string' ||
        body['job'].trim() === '' ||
        body['busy']))
  ) {
    return undefined;
  }
  const job = textAt(body, 'job');
  const note = textAt(body, 'note');
  return {
    id,
    state: textAt(body, 'state') ?? '',
    ...(job === undefined ? {} : { job }),
    busy: flagAt(body, 'busy'),
    ...(note === undefined ? {} : { note }),
  };
}

/** Whether an `approval.revoke` took the approval back, or nothing for a refusal. */
export function revokedOf(answer: Answer): boolean | undefined {
  const body = bodyOf(answer, OK);
  return body === undefined || textAt(body, 'id') === undefined
    ? undefined
    : flagAt(body, 'revoked');
}

/**
 * What one bare `JobEvent` means.
 *
 * The kinds are `JobEvent`'s own four; anything else is {@link Other} and
 * anything that is not an event at all is {@link Unreadable}. Neither throws —
 * see this file's header on why a new constant must not kill a terminal.
 */
/**
 * One piece of what a model is producing, read off a push.
 *
 * <p><b>Deliberately not a {@link Progress}.</b> A delta is not something a run
 * did; it is a preview of something it is still doing, it arrives in the
 * hundreds where lifecycle arrives four times a turn, and attaching it to a
 * {@link Turn} would grow that turn without bound for the whole of a long
 * answer. `following` never sees one.
 */
export interface Delta {
  readonly job: string;
  readonly part: 'thinking' | 'answer';
  readonly text: string;
}

/**
 * A delta, or nothing because this push is not one.
 *
 * <p><b>`part` is read in the case the server writes it</b>, which is upper —
 * Jackson spells an enum with `name()`, and `JobDeltaTest` pins that on the
 * other side of the wire precisely because this line has to agree with it. A
 * client reading the wrong case shows nothing and reports nothing, because a
 * delta it cannot read is indistinguishable from one that never came.
 *
 * <p><b>An empty `text` is not a delta.</b> The server never sends one, and
 * treating it as one would let a malformed frame blank the live region.
 */
export function streamed(push: unknown): Delta | undefined {
  const frame = fieldsOf(push);
  const job = textAt(frame, 'job');
  const part = textAt(frame, 'part');
  const text = textAt(frame, 'text');
  if (job === undefined || text === undefined || text === '') {
    return undefined;
  }
  if (part === 'THINKING') {
    return { job, part: 'thinking', text };
  }
  if (part === 'ANSWER') {
    return { job, part: 'answer', text };
  }
  return undefined;
}

/** Where a call's delegated child logs: `EntryView.AskedView.opened`. */
export interface Opened {
  readonly conversation: string;
  readonly agent: string;
}

/**
 * One tool call an answer asked for, as the log's page carries it: `EntryView.AskedView`.
 * `arguments` is cut at the page's cap and `length` is how long they really are; `salient` is
 * the argument a person recognises the call by, read by the server from the whole arguments.
 */
export interface Asked {
  readonly id: string;
  readonly name: string;
  readonly arguments: string;
  readonly length: number;
  readonly cut: boolean;
  readonly salient?: string;
  readonly opened?: Opened;
}

/**
 * One entry of a conversation's log, in the fields a provenance view reads.
 *
 * <b>The row and not the message.</b> This is a single recorded entry, kind
 * and all, including the kinds a model is never shown. What it carries beyond
 * the kind is the two questions this view exists to answer: what state the
 * entry is in, and which model produced it.
 *
 * <p>Every field is read defensively, on {@link Progress}'s rule: the server is
 * a version this client does not pin, and a `kind`, a `dispatch` or a
 * `completion` it has never heard of is carried as the string it arrived as
 * rather than refused. `dispatch` and the two beside it are absent on every kind
 * but an answer or a refusal, and on any row written before the server recorded
 * them — {@link answeredByFallback} reads the one question a person asks first.
 */
export interface Entry {
  readonly ordinal: number;
  readonly turnOrdinal: number;
  readonly kind: string;
  /** `stands`, or how the entry is no longer plainly itself — folded, ejected. */
  readonly state: string;
  /** `primary` or `fallback`, or undefined for a row that names no model. */
  readonly dispatch?: string;
  /** The model that produced it, as the server named it, or undefined. */
  readonly wireModel?: string;
  /** `answered`, `refused`, `cut_off`, `called_tools`, or undefined. */
  readonly completion?: string;
  /** The entry's text as the page carries it, which the server cuts; absent once ejected. */
  readonly text?: string;
  /** The entry's whole length, longer than `text` when the page cut it. */
  readonly length?: number;
  /** Whether the page cut `text` short. */
  readonly cut?: boolean;
  /** How many tools this entry asked for; absent when none — the answer that ends a turn. */
  readonly asked?: number;
  /** `person` or `harness`, on an utterance; absent on every other kind. */
  readonly speaker?: string;
  /** The person's handle, or the harness source: `orchestration <id>`, `approval <id>`, `event <id>`, `harness`. */
  readonly speakerName?: string;
  /** On a tool result: the id of the call it answers. */
  readonly toolCallId?: string;
  /** On an answer that asked for tools: the calls, in the order asked. */
  readonly calls?: readonly Asked[];
  /** On a tool result: its outcome in a word (`ok`, `exit 1`, `refused` …); absent before V62. */
  readonly outcome?: string;
  /** How long the model call (an answer) or the tool (a result) took. */
  readonly tookMillis?: number;
  /** When the row was written, as the server spelt it. */
  readonly recordedAt?: string;
  /** On a stored tool result: the handle `result_read` takes. */
  readonly handle?: string;
  /** The ordinal of the summary that folded this row away. */
  readonly supersededBy?: number;
  /** When this row's content was ejected. */
  readonly ejectedAt?: string;
}

/** Where a call opened a child, or nothing for one that did not — `AskedView.opened`. */
function openedIn(value: unknown): Opened | undefined {
  const fields = fieldsOf(value);
  const conversation = textAt(fields, 'conversation');
  const agent = textAt(fields, 'agent');
  return conversation === undefined || agent === undefined
    ? undefined
    : { conversation, agent };
}

/** One `AskedView`, read as the call it names, or nothing for one with no id or name. */
function askedIn(value: unknown): Asked[] {
  const fields = fieldsOf(value);
  const id = textAt(fields, 'id');
  const name = textAt(fields, 'name');
  if (id === undefined || name === undefined) {
    return [];
  }
  const salient = textAt(fields, 'salient');
  const opened = openedIn(fields['opened']);
  return [
    {
      id,
      name,
      arguments: textAt(fields, 'arguments') ?? '',
      length: countAt(fields, 'length') ?? 0,
      cut: flagAt(fields, 'cut'),
      ...(salient === undefined || salient === '' ? {} : { salient }),
      ...(opened === undefined ? {} : { opened }),
    },
  ];
}

/**
 * A conversation's log entries, in order, or an empty list.
 *
 * <b>A page and not an array.</b> {@link CONVERSATION_TRAJECTORY} answers with
 * an `EntryPageView` — `{ entries, total, offset, limit }` — so the entries
 * are read from under `entries` here. A page that named a total larger than
 * what it carried would be a conversation longer than one page; this client
 * shows what the page holds and says so, rather than pretending it is the
 * whole log.
 */
export function entriesOf(answer: Answer): Entry[] {
  const body = answer.code === OK ? fieldsOf(answer.payload) : {};
  const rows = body['entries'];
  if (!isList(rows)) {
    return [];
  }
  return rows.map((row) => {
    const fields = fieldsOf(row);
    const dispatch = textAt(fields, 'dispatch');
    const wireModel = textAt(fields, 'wireModel');
    const completion = textAt(fields, 'completion');
    const text = textAt(fields, 'excerpt');
    const length = countAt(fields, 'length');
    const calls = fields['toolCalls'];
    const asked = isList(calls) ? calls.length : 0;
    const speaker = textAt(fields, 'speaker');
    const speakerName = textAt(fields, 'speakerName');
    const listed = isList(calls) ? calls.flatMap(askedIn) : [];
    const toolCallId = textAt(fields, 'toolCallId');
    const outcome = textAt(fields, 'outcome');
    const tookMillis = countAt(fields, 'tookMillis');
    const recordedAt = textAt(fields, 'recordedAt');
    const handle = textAt(fields, 'handle');
    const supersededBy = countAt(fields, 'supersededBy');
    const ejectedAt = textAt(fields, 'ejectedAt');
    return {
      ordinal: countAt(fields, 'ordinal') ?? 0,
      turnOrdinal: countAt(fields, 'turnOrdinal') ?? 0,
      kind: textAt(fields, 'kind') ?? '',
      state: stateOf(fields),
      ...(dispatch === undefined ? {} : { dispatch }),
      ...(wireModel === undefined ? {} : { wireModel }),
      ...(completion === undefined ? {} : { completion }),
      ...(text === undefined ? {} : { text }),
      ...(length === undefined ? {} : { length }),
      ...(flagAt(fields, 'cut') ? { cut: true } : {}),
      ...(asked > 0 ? { asked } : {}),
      ...(speaker === undefined ? {} : { speaker }),
      ...(speakerName === undefined ? {} : { speakerName }),
      ...(listed.length === 0 ? {} : { calls: listed }),
      ...(toolCallId === undefined ? {} : { toolCallId }),
      ...(outcome === undefined || outcome === '' ? {} : { outcome }),
      ...(tookMillis === undefined ? {} : { tookMillis }),
      ...(recordedAt === undefined ? {} : { recordedAt }),
      ...(handle === undefined ? {} : { handle }),
      ...(supersededBy === undefined ? {} : { supersededBy }),
      ...(ejectedAt === undefined || ejectedAt === '' ? {} : { ejectedAt }),
    };
  });
}

/**
 * How many entries the whole log holds, which a page's own length does not
 * give: a tail of twenty says nothing about whether there are two hundred
 * before it. Falls back to what the page carries when the server sent no total,
 * so a reading is never claimed to be shorter than what is in hand.
 */
export function logTotal(answer: Answer): number {
  const body = answer.code === OK ? fieldsOf(answer.payload) : {};
  const total = countAt(body, 'total');
  if (total !== undefined) {
    return total;
  }
  const rows = body['entries'];
  return isList(rows) ? rows.length : 0;
}

/**
 * What an entry is besides itself — folded away, its payload ejected — or
 * `stands` when it is plainly what it says.
 *
 * <b>A word on every entry</b>, on the console's own reasoning: a column that is
 * blank on a healthy conversation is a column nobody can see, so the ordinary
 * case is named rather than left empty.
 */
function stateOf(fields: Record<string, unknown>): string {
  const marks: string[] = [];
  if (countAt(fields, 'supersededBy') !== undefined) {
    marks.push(`folded→${countAt(fields, 'supersededBy')}`);
  }
  const ejected = textAt(fields, 'ejectedAt');
  if (ejected !== undefined && ejected !== '') {
    marks.push('ejected');
  }
  return marks.length === 0 ? 'stands' : marks.join(' ');
}

import type { CommandEntry } from '../../../sdk/typescript/src/operations/conversation-replies.ts';

/**
 * The shapes this server sends, written down once.
 *
 * The console transport validates and projects these DTOs before a screen sees
 * them. ConsoleReplies checks its complete display contract on top of the
 * shared SDK contracts, including optional compatibility metadata.
 *
 * Every one of them is a copy of a Java record in
 * `io.aeyer.plowshare.server.api`, named the same, and the sentences below are
 * the halves of those records' javadoc that a renderer has to obey.
 */

/**
 * One turn: `GET /v1/conversations/{id}/turns`.
 *
 * `promptTokens` is `number | null` and **null is not zero**. `TurnView`
 * argues it at length and measured that it arrives as an explicit
 * `"promptTokens": null` rather than as an absent key; it is typed with the
 * `undefined` anyway, because a console that renders a field the server stops
 * sending should render an absence rather than crash.
 *
 * `ending` is a string and **nothing here enumerates the constants**. An
 * ending added on the server has to reach this console without a change to
 * this file, which is the whole reason `TurnView` sends the name rather than
 * an enum. See `describeEnding`, which looks one up and falls back rather than
 * switching.
 *
 * **There is no `agent`.** A turn on the wire does not say which agent
 * answered it, so a rebuilt transcript cannot either; only a run this tab
 * watched can be attributed, from the `agent` on its own events.
 */
export interface TurnView {
  readonly ordinal: number;
  readonly utterance: string;
  readonly answer: string;
  readonly ending: string;
  readonly promptTokens: number | null | undefined;
}

/**
 * One seam: `GET /v1/conversations/{id}/compactions`.
 *
 * `throughOrdinal` is the last turn folded into `summary`, and **every turn it
 * stands for is still in the turns answer at its own ordinal with its own
 * text** -- a compaction deletes nothing. That is why the seam is rendered as
 * a marker after that turn rather than in place of anything.
 */
export interface CompactionView {
  readonly throughOrdinal: number;
  readonly summary: string;
}

/**
 * One conversation: `GET /v1/conversations?project=` and `POST /v1/conversations`.
 *
 * `maxModelCalls` is what the row was opened with, and **this console no longer
 * chooses it**: the body it sends carries the project and nothing else, and the
 * server answers with `plowshare.conversations.default-budget`. It is read here
 * to be shown, which is how a person learns what a conversation may spend
 * without having been asked to invent it.
 *
 * `maxModelCalls` is `number | null`, and **null is not zero and not a very
 * large number** -- it is a lifted conversation's own answer, `Budget.limit`'s
 * refusal to invent one carried onto the wire. `noBudget` is the decision
 * itself, `noTurnCap`'s shape on the other knob: sent as its own field rather
 * than left to be read off the null, because a null here would otherwise have
 * to mean two things -- "no ceiling" and "this view could not price a budget
 * that is not its own to describe" -- and this console cannot tell them apart
 * from a number alone. `showBudget` renders neither state as the other.
 *
 * `maxTurns` and `noTurnCap` are the server's three states across two fields --
 * a number, no cap at all, or a conversation that decides neither and leaves it
 * to the agent answering. Declared rather than omitted so that this file says
 * what the row says; nothing on this screen renders them, because what bounds
 * one *run's* turns is reported on the job.
 */
export interface ConversationView {
  readonly id: string;
  readonly project: string | null;
  readonly maxModelCalls: number | null;
  /**
   * What the conversation has spent, or `null` for one whose allowance is not
   * its own to report.
   *
   * A different absence from `maxModelCalls`'s and a rarer one: that null is a
   * lifted conversation, which is an ordinary thing to be, while this one is a
   * row that owns no allowance at all. The server sent `0` for it until the
   * day it stopped, on the grounds that a measurement nobody took must not
   * arrive looking like one that came out zero.
   */
  readonly modelCallsSpent: number | null;
  readonly maxTurns?: number | null;
  readonly noTurnCap?: boolean;
  readonly noBudget?: boolean;
}

/**
 * One agent: `GET /v1/agents`. Carries no system prompt, by `AgentView`'s decision.
 *
 * `served` is false for an agent this server read and refused. It is on the list
 * rather than dropped from it because an agent that silently ceases to exist is
 * met at first use with no explanation; `withheld` is why, in sentences — the
 * refusal itself for one that is not served, and, for one that is, an entry per
 * thing the server took out of it: a delegation route it may not use, or a name
 * inside a grant it could never have issued, such as a tool no agent is bound.
 *
 * The two are one field on purpose. Both mean "this agent is running and
 * something it declared is not", which is one thing to say on a screen; the
 * distinction that must survive is from an agent that is **not** running, and
 * `served` is what carries it. A row with `served: true` and a non-empty
 * `withheld` is choosable and may be the default — the loss is a tooltip, not a
 * refusal.
 */
export interface AgentView {
  readonly name: string;
  readonly tools: readonly string[];
  readonly calls: readonly string[];
  readonly scopes: readonly string[];
  readonly served: boolean;
  readonly withheld: readonly string[];
  readonly commands?: readonly CommandEntry[];
}

/** What `POST /v1/agents/{name}/runs` answers with, before the run has done anything. */
export interface StartedJob {
  readonly id: string;
  readonly agent: string;
}

/**
 * One job: `GET /v1/jobs/{id}`.
 *
 * **This is the contractual record of a run and the event stream is not.**
 * `EventChannelHandler` offers each event to a bounded queue and drops on
 * overflow rather than let a slow listener hold a job's turn, so a console
 * that treated the stream as a log would render a job whose events were
 * dropped as a job that did nothing. `outcome` is null while the run is going
 * and present once it is over, whatever its ending -- which is the one bit
 * `JobView` says a caller needs.
 */
export interface JobView {
  readonly id: string;
  readonly agent: string;
  readonly state: string;
  readonly cancelRequested: boolean;
  /**
   * The conversation this run speaks into, or null for a run that has none --
   * a curator's ruling, say, which is many runs and not one agent's turn in
   * any of them.
   *
   * Null and not empty, and the two are different facts here on `dom.ts`'s own
   * discipline: an absence says so, and normalising the two into one value
   * would take that choice away from whatever screen reads this next.
   * `jobs.ts`'s `continueButton` reads this field and sends it to
   * `POST /v1/conversations/{id}/resume` for a run that has stopped and has
   * no conversation view of its own open to continue it from.
   */
  readonly conversation: string | null;
  readonly outcome: OutcomeView | null | undefined;
  /**
   * The two bounds this run went under, or null for a job that is not one
   * agent's run.
   *
   * Reported after the run has finished as well as during it, which is what
   * makes it readable as "the cap this run had" once it has stopped at one --
   * and that is the number a grant of another capful of turns is for.
   */
  readonly limits: LimitsView | null | undefined;
}

/**
 * What a run is bounded by. Five fields for two bounds, because *both* of them
 * can say "no ceiling", and each says it in its own field rather than as a large
 * number.
 *
 * `maxModelCalls` was `number` and is `number | null`, matching the pair beside
 * it and matching `ConversationView`. A run in a lifted conversation is spending
 * a budget with no total to report -- and `JobStore` shares one `Budget` by
 * reference down the whole delegation tree, so that is every job in it, parent
 * and children alike. `noBudget` is the decision itself, for the reason
 * `ConversationView.noBudget` gives.
 */
export interface LimitsView {
  readonly maxTurns: number | null | undefined;
  readonly noTurnCap: boolean;
  readonly maxModelCalls: number | null;
  readonly noBudget: boolean;
  readonly modelCallsSpent: number;
}

/** How a run ended. `answered` is the bit to read before believing `text`. */
export interface OutcomeView {
  readonly ending: string;
  readonly answered: boolean;
  /**
   * Whether a person should be offered the chance to continue this run.
   *
   * **The server's bit, and the reason no ending is enumerated to decide it.**
   * Which endings a grant continues is decided in `Turn`, against the
   * conversation's last turn, and it is not the same list as the one a person
   * is *shown*: `CANCELLED` is continued on request and never suggested,
   * because they asked it to stop. The server also folds in what this console
   * cannot see -- whether a grant could carry the run any further at all,
   * which depends on *which* number the grant for that ending would raise. A
   * `TURN_CAP` stop still needs spendable model calls behind it; a
   * `CALL_BUDGET` stop is continuable precisely because the grant raises the
   * thing that stopped it.
   *
   * This paragraph used to say the grant on offer is turns, and that turns
   * will not continue a run that ran out of model calls. Both halves were true
   * of an older offer and the second was a real server rule -- one that marked
   * every `CALL_BUDGET` stop unresumable, which is why the grant this console
   * learned to send for that ending was never once reached. `JobView
   * .OutcomeView.couldBeContinued` is where it was corrected.
   *
   * A console with its own copy of either list would go on offering a grant
   * the server had stopped taking, and nothing in this build would fail. It is
   * the same reason `ending` travels as a name rather than as an enum.
   *
   * Typed with the `undefined` and read as `=== true`, so a server that stops
   * sending it offers nothing rather than offering everything.
   */
  readonly resumable: boolean | undefined;
  readonly text: string;
  /**
   * How many steps the run completed: one model call plus the tool results it
   * asked for, each time round the agent's loop.
   *
   * Not `turns`, which is what the server called this field until it had two
   * words for two things. A turn is one thing a person said and everything
   * that answered it — what `turn_ordinal` and `GET /v1/conversations/{id}
   * /turns` mean — so "after 4 turns" for a single question told a reader they
   * had spoken four times. The server owns the vocabulary because this console
   * is one of three clients; see `Outcome` there.
   */
  readonly steps: number;
  readonly modelCalls: number;
  readonly detail: string | null;
}

/**
 * One frame off `/v1/events`, as `protocol.JobEvent` writes it.
 *
 * Lifecycle only: no prose, no tool arguments, no answer, no timestamp and no
 * sequence number. The kinds are `started`, `model_call`, `tool_called` and
 * `ended`; they travel as strings so a console built against a later server
 * binds a word it has never heard of, and the reader below ignores a kind it
 * does not know rather than failing over one.
 */
export interface JobEventFrame {
  readonly job: string;
  readonly kind: string;
  readonly agent: string;
  readonly tool: string | null;
  readonly ending: string | null;
  /** Steps completed, not turns. `OutcomeView.steps` says why the word moved. */
  readonly steps: number;
  readonly modelCalls: number;
}

/** The kinds this console renders. Not exhaustive over what may arrive. */
export const STARTED = 'started';
export const MODEL_CALL = 'model_call';
export const TOOL_CALLED = 'tool_called';
export const ENDED = 'ended';

/**
 * A decoded frame, or null for anything that is not one.
 *
 * The socket hands `unknown` -- `events.ts` parses JSON and does not validate
 * -- and everything below this line indexes fields, so the check is here and
 * once. A frame that fails it is dropped rather than thrown over: the stream
 * is droppable by design and one unreadable frame is one event lost, not a
 * broken page.
 */
export function asJobEvent(frame: unknown): JobEventFrame | null {
  if (typeof frame !== 'object' || frame === null) {
    return null;
  }
  const candidate = frame as Record<string, unknown>;
  if (
    typeof candidate['job'] !== 'string' ||
    typeof candidate['kind'] !== 'string'
  ) {
    return null;
  }
  return candidate as unknown as JobEventFrame;
}

/**
 * One command a run asked a person about: `ApprovalFrames.View`, as
 * `approval.list` answers it.
 *
 * Frames only -- there is no HTTP route for any of this, and none is coming;
 * spec 2026-09-15, asking a person, §4. `command` is the argv the run wanted,
 * whole arguments and never a shell string, which is what lets a project
 * approval be a *leading part* of it: `defaultPrefix` is the program and its
 * first argument, the server's suggestion and not a rule.
 */
export interface ApprovalView {
  readonly commands?: readonly (readonly string[])[] | null;
  readonly id: string;
  readonly conversation: string;
  readonly agent: string;
  /** `server` or `local`. */
  readonly side: string;
  readonly command: readonly string[];
  readonly cwd: string;
  /** The hook's `ask` reason, or null when the environment's mode asked. */
  readonly reason: string | null;
  /** `asked`, `allowed`, `denied`, `used` or `revoked`. */
  readonly state: string;
  /** Null while asked; `once`, `conversation` or `project` once allowed. */
  readonly scope: string | null;
  /** The arguments a `project` approval covers; null for any other scope. */
  readonly prefix: readonly string[] | null;
  readonly defaultPrefix: readonly string[];
  readonly createdAt: string;
  readonly answeredAt: string | null;
}

/** `approval.list`'s payload. */
export interface ApprovalList {
  readonly approvals: readonly ApprovalView[];
}

/** The four answers `approval.answer` takes. */
export type ApprovalDecision = 'once' | 'conversation' | 'project' | 'deny';

/**
 * `approval.answer`'s payload: `ApprovalFrames.Answered`.
 *
 * `job` is the continuing turn, started by the server in the same conversation
 * with the answer as its utterance; null with `busy` when that conversation
 * already had a turn in flight -- the decision is recorded either way, and
 * `note` is the server's sentence about why nothing continued.
 */
export interface ApprovalAnswered {
  readonly id: string;
  readonly state: string;
  readonly job: string | null;
  readonly busy: boolean;
  readonly note: string | null;
}

/** `approval.revoke`'s payload. `revoked` is false for an approval no longer standing. */
export interface ApprovalRevoked {
  readonly id: string;
  readonly revoked: boolean;
}

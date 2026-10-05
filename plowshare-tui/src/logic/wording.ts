import type { Answer } from 'plowshare-client-ts/operations/response';
import { isList } from 'plowshare-client-ts/binding/values';
import { projectLabel } from 'plowshare-client-ts/operations/project-label';
import {
  describeElapsed,
  describePace,
  tokens,
} from 'plowshare-client-ts/operations/pace';
export {
  describeElapsed,
  describePace,
} from 'plowshare-client-ts/operations/pace';
import type { PacePart } from 'plowshare-client-ts/operations/pace';
export type { PacePart } from 'plowshare-client-ts/operations/pace';
import { RETRIEVAL_HELP } from './retrieval.ts';
import type { Asking } from './approval.ts';
import type {
  Agent,
  Allowance,
  Approval,
  Answered,
  Chosen,
  Conversation,
  Definition,
  DialogKind,
  Entry,
  Firing,
  Fold,
  InboxPage,
  Pace,
  Progress,
  Project,
  Proposal,
  Run,
  RunStage,
  RunStatus,
  Schedule,
  Trigger,
  Waiting,
} from './session.ts';
import { DIALOG_KEYS } from './caps.ts';
import type {
  Caps,
  CapSetting,
  DialogKey,
  DialogOption,
  Picking,
} from './caps.ts';
// TYPES ONLY: `record.ts` takes values from `session.ts`, which takes values from here.
import type { Panel, Recorded, Tree, Viewed, Watch } from './record.ts';
import {
  ACCEPT_KINDS,
  ALWAYS_CAPS,
  CHECK_FAILURES,
  CONCERNS,
  PERSON_ONLY_KINDS,
  PRODUCT_CHECK,
  STUCK,
  TIME_CAP,
  UNCOVERED,
} from './session.ts';
import type { Phase } from './screen.ts';
export {
  describeSync,
  describeConflicts,
} from 'plowshare-client-ts/operations/union';
import { cleaned } from './clean.ts';
import { answeredEach, type Answering } from './questions.ts';
import { DEFAULT_COLUMNS, wrapText } from './wrap.ts';
import {
  callStanding,
  cutLines,
  failureCut,
  outcomeClass,
  stepKey,
  stepsOf,
  stripOf,
  turnsOf,
  type Call,
  type Step,
  type Turn,
} from './trajectory.ts';
import {
  fitTinted,
  lengthOf,
  padTinted,
  plainOf,
  selectedLine,
  shortenMiddle,
  tint,
  type Role,
  type Tint,
  type Tinted,
} from './tints.ts';
import {
  current,
  cursorOf,
  isFailure,
  matchesOf,
  panesOf,
  rowKey,
  rowsOf,
  turnOfRow,
  type Explorer,
  type Pane,
  type Row,
} from './explorer.ts';

/**
 * The sentences, which are the same sentence in a terminal and in a window.
 *
 * <h2>Why wording is logic and not view</h2>
 *
 * <p>The client design's §4.1 settles it with its own example: <i>"stopped at
 * its turn cap without reaching an answer" is the same sentence in a
 * terminal.</i> Nothing about that phrasing depends on there being a scrollback
 * rather than a window — it depends on what `TURN_CAP` means, which is a fact
 * about the server. A view that owned it would own it twice over the day the
 * GUI arrives, and the two copies would then disagree about what a person was
 * told happened to their run.
 *
 * <p>So the split runs along a line worth stating: <b>what to say is here; how
 * it looks is the view's.</b> Bold, colour, indentation, a spinner, a line of
 * dashes — none of that appears in this file, and `styleText` is `view/`'s to
 * import.
 *
 * <h2>Written against the frame outcomes, not carried over</h2>
 *
 * <p>The console has functions with these two names and this is deliberately
 * not them. The only copy spec §2 authorises is the markdown grammar; these are
 * written fresh against what `Outcome.Ending` and `JobView` actually say, and
 * against the vocabulary the server corrected — <b>steps and not turns</b>,
 * which is the mistake a console made in public.
 *
 * <h2>Two rules that keep these honest</h2>
 *
 * <p><b>A name this build has never heard of is said, not swallowed.</b>
 * `JobView` records why `ending` travels as a name rather than as an enum: the
 * list is the server's to change. So {@link describeEnding} is a `switch` with
 * a default arm that names the constant, and never a lookup in an object — a
 * lookup would answer for `constructor` and `toString` with whatever
 * `Object.prototype` happens to hold, which is the shape of bug that puts
 * `function Object() { [native code] }` on somebody's screen.
 *
 * <p><b>The server's own sentence wins whenever there is one.</b> {@link
 * refusal} writes nothing of its own while `said` is present, which is the
 * client design's §5.2 and the reason the absence is preserved all the way down
 * from `envelope.ts`: guessing a sentence by status once threw away every 409
 * sentence the server sent.
 *
 * <h2>The one import here is a type import, and that is not an accident</h2>
 *
 * <p>`session.ts` calls {@link refusal}, so the runtime dependency runs
 * <b>session -> wording</b> and only that way. What comes back the other way is
 * `import type` alone, which `verbatimModuleSyntax` erases entirely — so the
 * two files share a vocabulary without either one loading the other twice.
 * Declaring these shapes again here would have been the alternative, and it is
 * the one that drifts.
 */

/**
 * How a run ended, in a sentence that finishes "this run …".
 *
 * @param outcome `session.ts`'s `Ended`, off `job.status`, or its `Stopped`,
 *     off the ending event. Both carry `ending` and the sentence does not
 *     depend on which arrived first — which is the interleaving contract
 *     reaching even this far. `undefined` for a run that has not ended
 */
export function describeEnding(
  outcome: { readonly ending: string } | undefined,
): string {
  if (outcome === undefined) {
    return 'has not ended yet';
  }
  switch (outcome.ending) {
    case 'ANSWERED':
      return 'answered';
    case 'TURN_CAP':
      return 'stopped at its turn cap without reaching an answer';
    case 'CALL_BUDGET':
      return 'spent the whole model call budget without reaching an answer';
    case 'CANCELLED':
      return 'was asked to stop, and stopped at its next step boundary';
    case 'STUCK':
      return 'went round in a circle, repeating itself instead of getting anywhere';
    case 'UNAVAILABLE':
      return 'could not reach something it depends on, and gave up';
    case 'SUB_AGENT_FAILED':
      return 'was waiting on a job it had delegated, and that job failed';
    case 'SESSION_GONE':
      return 'was working in files on a machine that disconnected';
    case 'AWAITING':
      // A question, not a failure: the run asked before running a
      // command, and the answer is what starts the next turn.
      return 'stopped to ask you before running a command, and is waiting for your answer';
    case 'CALL_FAILURES':
      return 'kept writing tool calls as text instead of making them, and was stopped';
    default:
      // Named rather than guessed at. A server that grew a ninth ending
      // should reach a person as a word they can search for, not as a
      // sentence this client made up about a constant it has never met.
      return `ended ${outcome.ending}`;
  }
}

/**
 * What the server itself said about a run that did not answer, or nothing when
 * it said nothing.
 *
 * <h3>Why this is not {@link describeEnding} keeping its promise</h3>
 *
 * <p>This file's header states the rule — <b>the server's own sentence wins
 * whenever there is one</b> — and {@link describeEnding} is the one function
 * here that could not follow it, for a reason rather than an oversight. Its
 * answer is a *clause* that finishes "this run …", and it is called from the
 * push-event path, where `JobEvent` carries no failure text at all and never
 * will: that record's javadoc rules the event stream is the one surface a
 * cross-origin page could read without reaching the job endpoint, so it holds
 * the ending's <em>name</em> and nothing else. A clause that sometimes became a
 * whole sentence would read as "this run This run could not go on: …" on one
 * path and be missing on the other. So the clause stays a clause, and the
 * server's account arrives beside it as its own sentence.
 *
 * <h3>What it is for</h3>
 *
 * <p>`UNAVAILABLE` is three outages: the model endpoint unreachable, submitting
 * to it failing for some other reason, and a tool's own dependency gone. One
 * ending, three sentences, and `detail` names the type and the first line of the
 * message besides. Told only the ending, somebody whose "hello" died reads "could
 * not reach something it depends on" and has to go to a log file to learn whether
 * their chat endpoint or their embedding endpoint is down. Both facts were on the
 * wire the whole time.
 *
 * @param outcome the run, as `job.status` reported it, or `undefined` for one
 *     that has not ended. An answered run gets nothing: `text` is its answer,
 *     shown as the answer, and its `detail` is a truncation note that no surface
 *     has been designed for yet
 * @returns one sentence, or `undefined` when there is nothing of the server's to
 *     pass on — which is not a failure to describe but an absence to respect
 */
export function describeFailure(
  outcome:
    | {
        /**
         * Not read, and asked for anyway.
         *
         * Every caller has one, {@link describeEnding} is called on the same value a
         * line earlier, and the pair reads as two questions about one outcome rather
         * than as two unrelated shapes. Leaving it out would also make every literal
         * naming its stop an excess-property error, which is the form the cases in
         * this function's tests want to be written in.
         */
        readonly ending: string;
        readonly answered: boolean;
        readonly sentence?: string;
        readonly detail?: string;
      }
    | undefined,
): string | undefined {
  if (outcome === undefined || outcome.answered) {
    return undefined;
  }
  // Both, when there are both: the sentence says which of the three stops this
  // was, in the words of the code that stopped, and the detail says what
  // actually refused. Neither subsumes the other -- "something the tool
  // 'memory_recall' needs could not be reached" does not name the endpoint, and
  // "EmbeddingException: no embedding model is configured" does not name the
  // tool.
  const said = [outcome.sentence, outcome.detail].filter(
    (part): part is string => part !== undefined,
  );
  return said.length === 0 ? undefined : `the server said: ${said.join(' — ')}`;
}

/**
 * What one arriving event means, in a line, or nothing when it means nothing.
 *
 * <b>Added by task 7 and put here rather than in the view, which is the rule
 * this file exists to state.</b> `close_reader called plowshare_memory_write`
 * is the same line in a terminal and in a window; a view that wrote it would
 * write it twice the day the GUI arrives, and the two copies would then
 * disagree about what a person was told was happening.
 *
 * <p>The `switch` has an arm for every `Progress` kind and a `default` for
 * none, because {@link Progress} is a closed union this module owns — unlike
 * `ending`, whose list is the server's, which is why {@link describeEnding}
 * has the opposite shape. A `kind` the server invents arrives here as `other`
 * and is <b>named rather than swallowed</b>, for the same reason.
 *
 * @returns `undefined` for a frame that belongs to no job. Nothing, and not a
 *     sentence about nothing: `following` drops those because attaching one
 *     would put an invention in somebody's transcript, and a line on a screen
 *     is the same invention one layer up
 */
export function describeProgress(seen: Progress): string | undefined {
  switch (seen.kind) {
    case 'started':
      return `${seen.agent} started`;
    case 'step':
      return `${counted(seen.steps, 'step')}, ${counted(seen.modelCalls, 'model call')}`;
    case 'tool':
      return `${seen.agent} called ${seen.tool}`;
    case 'ended':
      return `this run ${describeEnding(seen)}`;
    case 'alive':
      // NOTHING TO SAY WHEN THERE IS NOTHING TO COUNT, and that absence
      // is load-bearing. A status region is REPLACED rather than appended
      // to, so a beat arriving before the first model call would wipe out
      // whatever the run last actually said and put "0 steps, 0 model
      // calls" in its place. Undefined leaves the last real line standing.
      return seen.steps === 0 && seen.modelCalls === 0
        ? undefined
        : `${counted(seen.steps, 'step')}, ${counted(seen.modelCalls, 'model call')}`;
    case 'other':
      return `this run reported ${seen.named}`;
    case 'unreadable':
      return undefined;
  }
}

/**
 * The connection went away while a run was still being followed.
 *
 * <p><b>Here and not in the view, on this file's own rule</b>, and it is worth
 * defending because it looks like a transport message rather than a sentence
 * about a run. What a person needs told is not that a socket closed — it is
 * that the thing they were watching is no longer being watched, and that the
 * work itself did not necessarily stop. That is the same in a terminal and in a
 * window, and it is the half a `close` event cannot say.
 *
 * <p><b>The second clause is the one that matters and it is not hedging.</b> A
 * run is server-side: `agent.run` hands back a handle and the job goes on
 * living in the server whether or not anybody holds a socket open to hear about
 * it. So this does not say the run failed, because it very probably did not,
 * and a client that reported a lost connection as a lost run would be telling
 * somebody their work was thrown away when it is still going.
 */
export function describeDrop(): string {
  return (
    'the connection to the server dropped before this run ended;' +
    ' the run itself may still be going, but this client can no longer follow it'
  );
}

/**
 * <b>Ctrl-C, during a run: what this client just asked the server for.</b>
 *
 * <h2>It says "asked", and every word after that is defending the word</h2>
 *
 * <p>`JobCancelHandler` is explicit: a cancel is honoured by the loop
 * <i>between</i> steps and is not a kill, so its answer still reads `RUNNING`
 * and a client shown `DONE` would be shown a state that is not yet true. Worse,
 * it may never become true — cancelling a run that has already finished
 * "changes nothing", so a run caught on its last step answers anyway. A person
 * told "stopped" would go looking for the answer that is about to land on their
 * screen, or would walk away from a run still spending.
 *
 * <p><b>So the handle is named and the second key is offered.</b> The handle,
 * because a run outlives this client and that id is how anybody asks after it
 * again; the key, because the boundary this stops at is on the far side of
 * whatever step is running, and a person who does not want to wait for a model
 * call to finish must be told how not to.
 */
export function describeCancelling(job: string): string {
  return (
    `asked ${job} to stop — a run stops at its next step boundary, so it may still` +
    ' answer; Ctrl-C again to leave without waiting'
  );
}

/**
 * <b>Ctrl-C, in the window before the run has a handle.</b>
 *
 * <p>`agent.run` has gone out and its `ACCEPTED` has not come back, so there is
 * a run and this client cannot name it — which is a different thing from there
 * being no run, and is said as the different thing. The alternative is a client
 * that sends `job.cancel` naming nothing, or one that says "stopped" about a
 * run it never reached.
 */
export function describeUnnamedRun(): string {
  return (
    'this run has not been given a handle yet, so there is nothing to ask the server' +
    ' to stop; Ctrl-C again to leave without waiting for one'
  );
}

/**
 * <b>Ctrl-C, at a prompt with nothing running.</b>
 *
 * <p>What the key means at a prompt is "throw away what I was typing", and that
 * is what happens: the line is discarded and the prompt comes back. What it
 * must not mean is the old behaviour — exiting the process without a word — so
 * this says what happened and names the key that does leave, because somebody
 * pressing Ctrl-C to get out has to be told which key that is.
 */
export function describeNothingRunning(): string {
  return (
    `nothing is running, so nothing was stopped — Ctrl-D leaves, and ${HELP_COMMAND}` +
    ' lists what this client knows'
  );
}

/**
 * <b>The second Ctrl-C: left without waiting for the server.</b>
 *
 * <p><b>Two sentences, because there are two truths and they are not
 * interchangeable.</b> A person who pressed twice after a cancel went out
 * leaves a run that was asked to stop; a person who pressed twice before the
 * handle arrived leaves a run that nothing asked to stop and that is still
 * spending. Saying the first about the second is the exact class of thing this
 * file exists to stop — a sentence that quietly claims an action nobody took.
 *
 * @param asked whether a `job.cancel` really went out for the run in flight
 */
export function describeLeaving(asked: boolean): string {
  return asked
    ? 'left without waiting — the run was asked to stop and does so at its next step' +
        ' boundary, whatever it has reached by then'
    : 'left without waiting — this run was never named by the server, so nothing was' +
        ' asked to stop and it may still be running';
}

/** `1 step`, `3 steps`. The English, so no caller writes it again. */
function counted(many: number, one: string): string {
  return `${many} ${one}${many === 1 ? '' : 's'}`;
}

/**
 * What a run cost, in a sentence.
 *
 * <b>Steps, never turns.</b> A step is one model call plus every tool result it
 * asked for; a turn is one thing a person said. `JobEvent` and `JobView` both
 * renamed this field after a console rendered "after 4 turns" for a single
 * question and told somebody they had spoken four times. The word is the
 * server's, and it stops here rather than being re-learned by each view.
 *
 * <p><b>An allowance with no ceiling is said as a decision.</b> `JobView`'s own
 * ruling: "no ceiling" is something somebody chose, so it is not rendered as a
 * very large number and not left as a silence either.
 *
 * @param spent what `job.status` reported, or `undefined` before anything has
 *     been counted — which is not the same as a run that cost nothing
 */
export function describeCost(
  spent:
    | {
        readonly steps: number;
        readonly modelCalls: number;
        readonly allowance?: Allowance;
      }
    | undefined,
): string {
  if (spent === undefined) {
    return 'nothing counted yet';
  }
  const own = `${counted(spent.steps, 'step')}, ${counted(spent.modelCalls, 'model call')}`;
  const allowance = spent.allowance;
  if (allowance === undefined) {
    return own;
  }
  // The tree's figure and not this job's: a delegating run's children spend
  // from the same allowance, so the second half answers a different question
  // from the first and the two numbers are allowed to differ.
  if (allowance.noBudget) {
    return `${own}; ${allowance.modelCallsSpent} spent against no ceiling`;
  }
  if (allowance.maxModelCalls === undefined) {
    return `${own}; ${allowance.modelCallsSpent} spent`;
  }
  return (
    `${own}; ${allowance.modelCallsSpent} of ${allowance.maxModelCalls}` +
    ' spent across the run tree'
  );
}

/**
 * Why an answer was not the success the ask needed, in a sentence.
 *
 * <b>The server's, whenever there is one</b>, including an empty one: an empty
 * string is a sentence somebody chose to send, and the binding keeps `said`
 * absent when nothing was said, so a present `''` is the server's own doing and
 * talking over it would be this client editing it.
 */
export function refusal(answer: Answer): string {
  if (answer.said !== undefined) {
    return answer.said;
  }
  return `it was refused, and the server answered ${answer.code} without saying why`;
}

/**
 * What a person sees when the server cannot be reached at all.
 *
 * <p><b>This is the likeliest first experience of this client</b>, and it was a
 * bare `fetch failed` until somebody ran it. The server is remote by design —
 * it needs Postgres and a model configuration, so it is not something a person
 * starts on their laptop to try this — which makes "the address is wrong" and
 * "the server is not up" the two ordinary cases rather than the exotic ones.
 * `TypeError: fetch failed` names neither, and names no address to check.
 */
export function describeUnreachable(base: string): string {
  return (
    `could not reach a Plowshare server at ${base}. Nothing was sent.` +
    ' Check PLOWSHARE_URL, and that the server is running and reachable' +
    ' from here.'
  );
}

/**
 * What a person types to see the projects that exist.
 *
 * <h2>Why the two command words are here and not in `session.ts`</h2>
 *
 * <p>They are vocabulary a person and this client share, and they appear inside
 * a sentence — {@link describeUnknownCommand} names them both, which is the one
 * thing somebody who mistyped needs. That sentence cannot be written anywhere
 * else without the word being spelled twice.
 *
 * <p><b>The direction of the dependency is what settles it.</b> `session.ts`
 * imports {@link refusal} from this file, so the runtime edge runs <b>session ->
 * wording</b>; a constant here can be read by `session.typed`, and a constant
 * over there could not be read by this sentence without closing the loop.
 *
 * <p><b>And a window would keep them.</b> These look terminal-shaped, and the
 * client design's §2 is why they are not: this client is line-oriented in a
 * terminal <i>and</i> in a window — a scrollback and a prompt — so a typed
 * command ports as it stands. A window that grows a menu item reaches the same
 * {@link Ask} by a different route; it does not need a different word for it.
 */
export const PROJECTS_COMMAND = '/projects';

/** What a person types to see the conversations in the home they are in. */
export const CONVERSATIONS_COMMAND = '/conversations';

/**
 * What a person types to see who there is to talk to.
 *
 * <b>Separate from {@link AGENTS_COMMAND}, and the whole point is that they are
 * different kinds of thing.</b> They send the same frame — `agent.list` answers
 * with both kinds and a `bot` flag — so the separation buys nothing on the wire
 * and everything on the screen. A single listing with a column would make
 * "which of these can I have a conversation with" a thing a person reads out of
 * a table, on the one screen where it is the only question they have.
 */
export const BOTS_COMMAND = '/bots';

/**
 * What a person types to see what there is to invoke — and, with a name, what
 * that one agent holds.
 *
 * <b>The detail view is about one agent and says nothing about the server.</b>
 * `agent.list` is the only frame that carries `tools`, `calls`, `scopes` or
 * `orchestrations` at all: there is no registry of what this deployment can do,
 * so what is shown here is one definition's declarations and must not be read as
 * a catalogue.
 */
export const AGENTS_COMMAND = '/agents';

/**
 * What a person types to see what orchestrations can be started here — and, with
 * a name, one of them whole.
 *
 * <b>Scoped by where the person is standing, and it says which.</b>
 * `orchestration.definitions` takes a project and degrades an absent one to the
 * boot set, so the same command answers two different questions depending on
 * where it is typed. A listing that did not name the set it showed would leave a
 * person to work out from the rows themselves which one they got.
 */
export const ORCHESTRATIONS_COMMAND = '/orchestrations';

/** Read-only project board and swarm inspection. */
export const BOARD_COMMAND = '/board';
export const SWARM_COMMAND = '/swarm';

/**
 * What a person types to see their orchestration runs — and, with an id, one
 * run's stages, its message history and its children.
 *
 * <b>Deliberately not `/jobs`.</b> A job run is what a turn does and it is
 * already visible through `{@value LOG_COMMAND}` and `{@value
 * CONVERSATIONS_COMMAND}`; these are orchestration runs, which outlive a turn
 * and have children. One word for both would make the more obscure of the two
 * the one nobody could ask for.
 */
export const RUNS_COMMAND = '/runs';

/**
 * What a person types to follow a run as it goes: its stages, its phases and everything it has
 * done, full height, until Esc. Spec 2026-09-28, the orchestration record §5.
 */
export const USAGE_COMMAND = '/usage';
export const WATCH_COMMAND = '/watch';

/**
 * What a person types to settle a conductor's question.
 *
 * <b>It exists because the caller is a model.</b> A root run's question is
 * delivered to whoever started it, which in practice is a bot, and the delivery
 * says in as many words: answer it with `orchestration_answer`, passing the id.
 * On the first live run a bot read that and wrote a specification at the person
 * instead, leaving the run in `asking`. This is the route that does not go
 * through anything that can decide otherwise.
 */
export const ANSWER_COMMAND = '/answer';

/**
 * What a person types to stop an orchestration run.
 *
 * <b>Not what Ctrl-C does.</b> Ctrl-C asks the turn on this screen to stop, which
 * is one job; this takes a run id and ends that run and every descendant it
 * started. Two different things with one obvious name between them, so the name
 * goes to the one a person cannot otherwise reach — Ctrl-C needs no spelling.
 */
export const CANCEL_COMMAND = '/cancel';

/**
 * What a person types to let this project's runs start commands on this machine without
 * asking, and — with `off` — to have them ask again.
 *
 * <b>It edits the file, and the file is still the setting.</b> The JSON manifest or legacy `.plowshare/environment.yml`'s
 * local command mode is what both the server and this client read on every run; a project that
 * never had one asks. This writes `open` or `ask` into it, so the setting is where anyone
 * would look for it, survives the session, and is taken back by the same command.
 */
export const ALWAYS_COMMAND = '/always';

/**
 * What a person types to see this project's caps — steps per turn, model calls per run, how many
 * caps a run may pass without asking — and, with `steps`, `budget` or `auto` and a number, to set
 * one.
 *
 * <b>It edits the file, and the file is still the setting</b> — {@link ALWAYS_COMMAND}'s model:
 * The JSON manifest or legacy `.plowshare/environment.yml`'s caps are what the server reads on every turn, so this writes
 * there rather than holding a number this client would forget on exit, and then tells the server
 * to read the file again and apply it to the runs already going.
 */
export const CAP_COMMAND = '/cap';

/**
 * What a person types to see what scheduled runs left for them.
 *
 * <b>The console's `/inbox` had a socket to itself; this is the same listing
 * from a second client.</b> `InboxListHandler` refuses a socket with no
 * account, which is why the ask this command sends is the loud one — a
 * refusal here is a sentence, where the same ask at connect is silent. See
 * `main.ts`.
 */
export const INBOX_COMMAND = '/inbox';

/**
 * What a person types to read the log of the conversation they are in — every
 * entry, its state, and which model answered.
 *
 * <b>Not the conversation, the log.</b> The turns are what was said; this is the
 * provenance view, and it shows the rows a model never sees — a recorded refusal
 * that a fallback answered in its place — beside the target that gave each
 * answer. It is how a rerouted refusal is legible at all.
 */
export const LOG_COMMAND = '/log';

/**
 * What a person types to explore this conversation step by step: every call, what came back, and
 * the logs of the agents it handed work to. Spec 2026-09-29 §4.
 *
 * <b>Declared here, ahead of the "Tool lines" section below</b>, so that {@link COMMANDS}' array
 * literal — evaluated once, at module load — never reads this binding before it exists; a
 * `const` declared after the array that names it is in its temporal dead zone at that point and
 * the module would fail to load.
 */
export const TRAJECTORY_COMMAND = '/trajectory';

/**
 * What a person types to see the entries of the conversation on screen before the ones shown.
 *
 * <b>Printed now, and labelled as earlier.</b> A terminal's scroll only grows at the bottom, so
 * "above" is a block drawn below what is there, introduced as earlier history and in the order
 * it happened. The tail on opening is a display decision, and this is how a person gets past it.
 */
export const EARLIER_COMMAND = '/earlier';

/** Ask Daedalus to diagnose this conversation, or the id named after the command. */
export const DIAGNOSE_COMMAND = '/diagnose';

/** Makes the directory the client stands in a project, and roots it. */
export const HERE_COMMAND = '/here';

/** Moves to a project that already exists, rooting it when its files are here. */
export const PROJECT_COMMAND = '/project';

/** Changes the directory the client stands in, and looks for a project there. */
export const CD_COMMAND = '/cd';

/** Keep a project's files on the server too, and reconcile them. Spec 2026-09-14 §11.1. */
export const SYNC_COMMAND = '/sync';

/**
 * What a person types first, which was refused until now.
 *
 * <b>Measured: it is the first thing anybody types</b>, and this client answered
 * it with `there is no /help`. That is the worst available first impression,
 * because it is also the reply to a genuine mistake — so somebody who typed it
 * could not tell a client with no help from a client that had not understood
 * them.
 */
export const HELP_COMMAND = '/help';

/**
 * Which colours this client draws in. Bare, it lists the themes; with a name,
 * it switches. Answered by the view with no round trip, like `/help`: a theme
 * is a fact about this terminal and nothing a server holds.
 */
export const THEME_COMMAND = '/theme';

/**
 * Schedule something out of one sentence, or — with `pause`, `resume` or
 * `forget` and a name — manage one already saved.
 */
export const SCHEDULE_COMMAND = '/schedule';

/** Fire one schedule now, by name, rather than waiting for it. */
export const FIRE_COMMAND = '/fire';

/** What the last firings did. */
export const FIRINGS_COMMAND = '/firings';

/**
 * The commands this project lets runs start without asking, and — with
 * `revoke` and an id — taking one back. Spec 2026-09-15, asking a person, §5.
 */
export const APPROVALS_COMMAND = '/approvals';

/**
 * Every command this client knows, in the order {@link describeHelp} says them.
 *
 * <b>One list, read by the help text and by the test that proves every line of
 * it reaches something.</b> A second hand-written list in the help would drift,
 * and the way it drifts is the bad way round: a command advertised and then
 * refused. Task 5's completer is the third reader.
 */
export const MEMORY_COMMAND = '/memory';
export const SEARCH_COMMAND = '/search';
export const JOB_COMMAND = '/job';

export const COMMANDS: readonly string[] = [
  '/admin',
  '/message',
  MEMORY_COMMAND,
  SEARCH_COMMAND,
  JOB_COMMAND,
  USAGE_COMMAND,
  '/design-orchestration',
  HELP_COMMAND,
  '/commands',
  '/skills',
  BOTS_COMMAND,
  AGENTS_COMMAND,
  ORCHESTRATIONS_COMMAND,
  RUNS_COMMAND,
  WATCH_COMMAND,
  BOARD_COMMAND,
  SWARM_COMMAND,
  ANSWER_COMMAND,
  CANCEL_COMMAND,
  '/resume',
  '/retry',
  ALWAYS_COMMAND,
  CAP_COMMAND,
  PROJECTS_COMMAND,
  CONVERSATIONS_COMMAND,
  '/conversation',
  '/memory',
  INBOX_COMMAND,
  SCHEDULE_COMMAND,
  FIRE_COMMAND,
  FIRINGS_COMMAND,
  APPROVALS_COMMAND,
  LOG_COMMAND,
  TRAJECTORY_COMMAND,
  EARLIER_COMMAND,
  DIAGNOSE_COMMAND,
  HERE_COMMAND,
  PROJECT_COMMAND,
  CD_COMMAND,
  SYNC_COMMAND,
  THEME_COMMAND,
];

/**
 * What this client can do, in the lines a person reads when they ask.
 *
 * <p><b>The last line is the one that earns its place.</b> Without it the help
 * reads as the whole of what this client is for, and the one thing it is
 * actually for — saying something to whoever answers — is the thing the help
 * does not mention. Somebody who opened a terminal and typed `/help` first
 * would come away thinking they had found a listing tool.
 */
export function describeHelp(): string[] {
  return [
    ...RETRIEVAL_HELP.map((line) =>
      line.startsWith('conversation ') || line.startsWith('memory ')
        ? `/` + line
        : line,
    ),
    ...COMMANDS.map(
      (command) =>
        `${command}${describeArguments(command)} — ${describeCommand(command)}`,
    ),
    '/memory read <id>; /memory recall|navigate <question> — use the current project; JSON project:null selects global',
    'anything else you type is said to whoever is answering',
  ];
}

/**
 * What a command takes after it, as the help writes it: `[name]` when it may
 * take one, `<name>` when it must. Empty for a command that takes nothing, and
 * for `/theme`, whose argument the command menu offers by name.
 */
export function describeArguments(command: string): string {
  switch (command) {
    case '/admin':
      return ' status|accounts|account|sessions|session|audit|pricing|service [JSON]';
    case USAGE_COMMAND:
      return ' [models|conversation|project|agent|run|orchestration|pools|calls] [target] [--days N] [--direct] [--reference PRESET] [--json]';
    case '/design-orchestration':
      return ' [--revise name] <intent>';
    case '/message':
      return ' instances|instance|open|default|stop|archive|deliveries|delivery|cancel [JSON or address]';
    case MEMORY_COMMAND:
      return ' index|read|recall|write|navigate|digest|curate|proposals|resolve|reconsider|invalidate|reembed [JSON payload]';
    case SEARCH_COMMAND:
      return ' <text or JSON payload>';
    case JOB_COMMAND:
      return ' status|cancel <job-id>';
    case HERE_COMMAND:
      return ' [name]';
    case AGENTS_COMMAND:
    case ORCHESTRATIONS_COMMAND:
      return ' [name]';
    case RUNS_COMMAND:
      return ' [id]';
    case WATCH_COMMAND:
      return ' [run]';
    case ANSWER_COMMAND:
      return ' [id] <your answer>';
    case '/resume':
    case '/retry':
    case CANCEL_COMMAND:
      return ' <id>';
    case ALWAYS_COMMAND:
      return ' [off]';
    case CAP_COMMAND:
      return '';
    case DIAGNOSE_COMMAND:
      return ' [conversation-id] [-- question]';
    case PROJECT_COMMAND:
      return ' <name> | create|define|member-add|member-remove <JSON>';
    case CD_COMMAND:
      return ' <path>';
    case SCHEDULE_COMMAND:
      return ' <sentence> | list | pause|resume|forget <name>';
    case FIRE_COMMAND:
      return ' <name>';
    case APPROVALS_COMMAND:
      return ' [revoke <id>]';
    case SYNC_COMMAND:
      return ' [on | off | conflicts | hidden <paths> | resolve <path> mine|theirs|merge|done]';
    case BOARD_COMMAND:
    case SWARM_COMMAND:
      return ' [project]';
    case TRAJECTORY_COMMAND:
      return ' [conversation]';
    case LOG_COMMAND:
      return ' [conversation]';
    default:
      return '';
  }
}

/**
 * What one command is for, in the few words the help and the command menu both
 * show — one table, so the two can never disagree about a command.
 */
export function describeCommand(command: string): string {
  switch (command) {
    case '/admin':
      return 'manage server users, service accounts, scoped tokens, model pricing, roles, password recovery, sessions and audit history';
    case USAGE_COMMAND:
      return 'recorded tokens, booked cost and separate reference comparison from the server';
    case '/message':
      return 'manage persistent message instances and inspect deliveries over WS';
    case MEMORY_COMMAND:
      return 'direct memory access over WS';
    case SEARCH_COMMAND:
      return 'search conversations in the current tier';
    case JOB_COMMAND:
      return 'inspect or request cancellation of an accepted job';
    case HELP_COMMAND:
      return 'this';
    case '/commands':
      return 'refresh the commands the selected agent can run';
    case '/skills':
      return 'refresh the skills the selected agent can run, including those hidden from the model';
    case BOTS_COMMAND:
      return 'who there is to talk to';
    case AGENTS_COMMAND:
      return 'what there is to invoke; with a name, what that one holds';
    case '/design-orchestration':
      return 'interview and draft an agent-driven procedure; review installation yourself';
    case ORCHESTRATIONS_COMMAND:
      return 'the orchestrations that can be started where you are standing';
    case RUNS_COMMAND:
      return 'your orchestration runs; with an id, how one of them is going';
    case WATCH_COMMAND:
      return 'follow a run as it goes: its stages, its phases and everything it has done';
    case ANSWER_COMMAND:
      return 'answer the question a run is waiting on, without going through its caller';
    case '/resume':
    case '/retry':
      return 'resume a failed root orchestration from its saved progress';
    case CANCEL_COMMAND:
      return 'stop a run, and with it every run it started';
    case ALWAYS_COMMAND:
      return "let this project's runs start commands on this machine without asking";
    case CAP_COMMAND:
      return (
        "this project's caps: steps per turn, model calls per run, caps passed" +
        ' without asking, minutes per run, failed checks before asking;' +
        ` ${CAP_COMMAND} steps N, budget N, auto N, time N, checks N set one` +
        '; auto-increase on|off renews steps and budget'
      );
    case PROJECTS_COMMAND:
      return 'the projects this server holds';
    case CONVERSATIONS_COMMAND:
      return 'what is open in the home you are in';
    case INBOX_COMMAND:
      return 'what scheduled runs left for you';
    case SCHEDULE_COMMAND:
      return (
        'schedule something in a sentence; save/sync JSON and files manage monitored definitions; list shows your schedules and who each one' +
        ' wakes'
      );
    case FIRE_COMMAND:
      return 'fire a schedule now instead of waiting for it';
    case FIRINGS_COMMAND:
      return 'what the last firings did';
    case APPROVALS_COMMAND:
      return 'the commands this project lets runs start without asking you';
    case LOG_COMMAND:
      return "every row of this conversation's log, with its state, timing and model";
    case BOARD_COMMAND:
      return 'inspect project topics, full messages, decisions and agent seats';
    case SWARM_COMMAND:
      return 'inspect live swarm seats, model queues and shared pool occupancy';
    case TRAJECTORY_COMMAND:
      return 'explore this conversation step by step: every call, what came back, and the logs of agents it handed work to';
    case EARLIER_COMMAND:
      return 'the part of this conversation before what is on the screen';
    case DIAGNOSE_COMMAND:
      return 'ask Daedalus why a conversation went wrong';
    case HERE_COMMAND:
      return 'make this directory a project and lend it its files';
    case PROJECT_COMMAND:
      return 'move to another project, or create and administer a server project with a JSON payload';
    case CD_COMMAND:
      return 'stand somewhere else, and root the project found there';
    case SYNC_COMMAND:
      return "keep this project's files on the server too";
    case THEME_COMMAND:
      return 'the colours this client draws in, and switching them';
    default:
      return '';
  }
}

/** The line above a tail that has more before it, saying how to reach it. */
export function describeEarlierHint(): string {
  return `— earlier entries: ${EARLIER_COMMAND} —`;
}

/**
 * What introduces a block `/earlier` drew. <b>Said as earlier</b>, because it is printed below
 * what was already on the screen and would otherwise read as the conversation carrying on.
 */
export function describeEarlier(entries: number): string {
  return `— ${entries} earlier ${entries === 1 ? 'entry' : 'entries'} —`;
}

/** There is nothing before what is on the screen: the conversation starts there. */
export function describeBeginning(): string {
  return '— that is the beginning of this conversation —';
}

/** `/earlier` with no conversation on the screen to go back through. */
export function describeNoEarlier(): string {
  return 'there is no conversation on the screen to go back through';
}

/**
 * `/earlier` while none of the log is on the screen — its reading was refused, and the next
 * thing the log gains fills it. Not the beginning: nobody has looked.
 */
export function describeNotYetShown(): string {
  return "this conversation's log is not on the screen yet, so there is nowhere to go back from";
}

/** A diagnosis needs a persisted target; no current conversation means there is no implicit one. */
export function describeNoDiagnosis(): string {
  return (
    `there is no current conversation to diagnose — use ${DIAGNOSE_COMMAND}` +
    ' <conversation-id> [-- question]'
  );
}

/** The separate conversation is named because it remains in the append-only archive. */
export function describeDiagnosing(target: string, diagnostic: string): string {
  return `Daedalus is diagnosing ${target} in diagnostic conversation ${diagnostic}`;
}

/**
 * A line that began with a slash and named nothing this client knows.
 *
 * <b>Refused rather than sent.</b> A mistyped command passed on as an utterance
 * costs a model call and comes back as an agent's puzzled answer about a word
 * the person never meant as a question — so the reply names what they typed and
 * points at the one place that lists the rest.
 *
 * <p><b>It named both commands when there were two, and now names none.</b>
 * With five, reciting them here would be the help text written a second time,
 * in a second place, drifting from the first — and the sentence a person needs
 * from a typo is short.
 */
export function describeUnknownCommand(named: string): string {
  return (
    `there is no ${named} — ${HELP_COMMAND} lists what this client knows,` +
    ' and everything else you type is a question for whoever is answering'
  );
}

/**
 * The tools, delegation routes and scopes are left off both rosters, on
 * {@link describeProjects}' reasoning: none of the three answers "who can I
 * talk to" or "what can I invoke", which is the one question either listing is
 * opened with.
 *
 * <p>`description` is different, and is why {@link rosterLine} exists at all: it
 * is where a bot says who he is rather than what he does, and a bare name
 * defeats the point for exactly the row where it matters most. It was left off
 * `AgentView` for a while and is on it now — this file no longer has to work
 * around its absence, only around its length.
 *
 * <p><b>Cut to one line, at a word, with `…` when anything was cut.</b> A
 * description is prose and the shipped bot's runs to several sentences;
 * printing all of it would mean `toListing` drawing a paragraph where a roster
 * expects a row. {@link DESCRIPTION_BUDGET} is chosen to read as a clause
 * somebody would actually say out loud — long enough to say who a character is,
 * short enough that the roster stays a roster — and the cut lands on a space
 * rather than inside a word, because "Pythagor…" reads as broken where
 * "Pythagoreans…" reads as merely unfinished.
 */
const DESCRIPTION_BUDGET = 120;

/**
 * A description, cut down to {@link DESCRIPTION_BUDGET} characters at a word
 * boundary, with an ellipsis when anything was cut. The empty string passes
 * through unchanged, which is `rosterLine`'s cue to print the name alone.
 *
 * <p>Whitespace is collapsed first, since a description parsed out of a YAML
 * block scalar carries the newline at the end of every wrapped line in the
 * file, and a roster row built from those verbatim would draw more visual
 * lines than the one it claims to be.
 */
function briefly(description: string): string {
  return clipped(description, DESCRIPTION_BUDGET);
}

/** `text` on one line, cut at a word to `budget` characters, with `…` when anything was cut. */
function clipped(text: string, budget: number): string {
  const said = text.replace(/\s+/g, ' ').trim();
  if (said.length <= budget) {
    return said;
  }
  const cut = said.slice(0, budget);
  const lastSpace = cut.lastIndexOf(' ');
  return `${lastSpace > 0 ? cut.slice(0, lastSpace) : cut}…`;
}

/**
 * One roster row: the name alone for a definition with nothing to say about
 * itself, the name and enough of its description to be useful otherwise.
 *
 * @param row a served bot or agent — never a refused one, which {@link
 *     unreadable} renders on its own terms
 */
function rosterLine(row: Agent): string {
  return row.description === ''
    ? row.name
    : `${row.name} — ${briefly(row.description)}`;
}

/**
 * The bots, one line each: who there is to talk to.
 *
 * @returns one line per bot, then the definitions this server could not read —
 *     see {@link unreadable} for why those are in both listings
 */
export function describeBots(rows: readonly Agent[]): string[] {
  const bots = rows.filter((row) => row.served && row.bot);
  const said =
    bots.length === 0
      ? [
          `there are no bots on this server — ${AGENTS_COMMAND} is what it does serve`,
        ]
      : bots.map((row) => rosterLine(row));
  return [
    ...said,
    ...bots.flatMap((row) =>
      (row.commands ?? []).map(
        (command) => `  ${command.command} — ${command.description}`,
      ),
    ),
    ...unreadable(rows),
  ];
}

/** Model discovery flags never filter the human's granted command catalog. */
export function describeCommandCatalog(
  agent: Agent,
  only: 'skill' | 'all' = 'all',
): string[] {
  const entries = (agent.commands ?? []).filter(
    (command) => only === 'all' || command.kind === only,
  );
  const heading = `${entries.length} ${only === 'skill' ? 'skills' : 'commands'} available to ${agent.name}`;
  const lines = entries.map(
    (command) =>
      `${command.command} ${command.argumentHint} — ${command.description} · ${command.executor}${command.kind === 'skill' && command.mode === null ? ' (specify --mode=INHERITED|SUMMARISED|NEW|DIRECT)' : command.mode ? ` · ${command.mode}` : ''}`,
  );
  return [
    heading,
    ...(agent.commands === undefined
      ? ['Command discovery is unavailable; update the server.']
      : lines),
    ...agent.withheld,
  ];
}

/**
 * The agents, one line each: what there is to invoke.
 *
 * @returns one line per agent, then the definitions this server could not read
 */
export function describeAgents(rows: readonly Agent[]): string[] {
  const agents = rows.filter((row) => row.served && !row.bot);
  const said =
    agents.length === 0
      ? ['there are no agents on this server']
      : agents.map((row) => rosterLine(row));
  return [...said, ...unreadable(rows)];
}

/**
 * The definitions this server read and refused, with the reason for each.
 *
 * <h2>In both listings, because this client cannot say which one they belong
 * in</h2>
 *
 * <p>`AgentView.disabled` sends `bot: false` for every refused definition, and
 * says why in its own javadoc: a file that failed to parse has no `bot:` to
 * read, just as it has no `exported`. So the flag is a statement about this
 * server rather than about the file, and a client that filed these under
 * `{@value AGENTS_COMMAND}` would be reporting a fact it does not have.
 *
 * <p>The alternative — leaving them out of both — is worse and the server
 * already rules against it: {@code AgentListHandler} keeps a disabled agent on
 * its list rather than filtering it, because filtering would make the least
 * readable definitions the ones that vanish. A person whose bot disappeared
 * needs to be told it was refused, on the screen where they went looking for
 * it.
 */
function unreadable(rows: readonly Agent[]): string[] {
  const refused = rows.filter((row) => !row.served);
  if (refused.length === 0) {
    return [];
  }
  return [
    `${counted(refused.length, 'definition')} on this server could not be read, so it` +
      ` cannot say whether ${refused.length === 1 ? 'it is' : 'any of them is'} a bot:`,
    ...refused.map((row) => `${row.name} — ${row.withheld.join('; ')}`),
  ];
}

/**
 * One list of names on one line, or the sentence that says there are none.
 *
 * <p><b>An empty list is said and not skipped.</b> "This agent may not delegate"
 * is information — it is the structural fact that no instruction can talk it out
 * of — and a heading quietly missing from the view would read as a client that
 * did not know rather than as an agent that may not.
 */
function holding(said: string, none: string, names: readonly string[]): string {
  return names.length === 0 ? none : `${said}: ${names.join(', ')}`;
}

/**
 * One agent, whole: whether it is served, what it is, and the four things it
 * declared.
 *
 * <h2>About this agent, and not about this server</h2>
 *
 * <p>`agent.list` is the only place `tools`, `calls`, `scopes` and
 * `orchestrations` appear on the wire — there is no registry frame — so these
 * lines are one definition's declarations. They are worded as what this agent
 * holds and may reach, never as what exists: a line reading `tools: read_file`
 * with no subject would read as the server's inventory, which this client has no
 * way to know and no frame to ask for.
 *
 * <p><b>A name nothing answers to is said as that</b>, rather than as an empty
 * view. The roster was just read, so this client knows the name is not there and
 * can say so in one line instead of drawing headings over nothing.
 *
 * @param name what the person typed after the command
 * @param rows the whole roster, which is what `agent.list` answered with
 */
export function describeAgent(name: string, rows: readonly Agent[]): string[] {
  const row = rows.find((each) => each.name === name);
  if (row === undefined) {
    return [
      `nothing on this server is called ${name} — ${AGENTS_COMMAND} lists what is,` +
        ` and ${BOTS_COMMAND} lists who there is to talk to`,
    ];
  }
  // NOT SERVED IS THE WHOLE SCREEN. `AgentView.disabled` empties the four
  // declaration lists for a definition this server refused, so headings under
  // this row would each say "nothing" about a file whose declarations never
  // took effect — which reads as an agent that asked for nothing rather than
  // one that was not read.
  if (!row.served) {
    return [`${name} is not served:`, ...row.withheld.map((why) => `  ${why}`)];
  }
  const kind = row.bot
    ? 'a bot, somebody to talk to'
    : 'an agent, a role to invoke';
  return [
    `${name} — ${kind}${row.model === undefined ? '' : `, answering as ${row.model}`}`,
    ...(row.description === '' ? [] : [row.description]),
    holding('tools it holds', 'it holds no tools', row.tools),
    holding(
      'agents it may call',
      'it may not delegate to any agent',
      row.calls,
    ),
    holding(
      'places it may reach',
      'it may reach nothing outside a conversation',
      row.scopes,
    ),
    holding(
      'orchestrations it may start',
      'it may start no orchestration',
      row.orchestrations,
    ),
    // WITHHELD ON A SERVED AGENT IS THE SECOND HALF OF THE DISABLE RULE, and
    // it belongs under the lists rather than instead of them: this is an
    // agent that runs, minus something it declared, and the four lines above
    // are already the enforced set.
    ...(row.withheld.length === 0
      ? []
      : ['this server took away:', ...row.withheld.map((why) => `  ${why}`)]),
  ];
}

/**
 * Who is answering, or why nobody is, in the lines said at sign-in.
 *
 * <p><b>A `switch` over a closed union this module's neighbour owns</b>, which
 * is {@link describeProgress}'s shape and for its reason: a sixth state added
 * to `Chosen` fails the compiler here rather than falling through to a default
 * that says something plausible about a case nobody has thought about.
 *
 * @returns the lines to print. Every arm but the first is also a reason the
 *     session is over, and the view reads that off `kind` rather than off these
 */
export function describeChoice(chosen: Chosen): string[] {
  switch (chosen.kind) {
    case 'answering':
      return [talkingTo(chosen)];
    case 'unknown':
      return [
        `this server declares nothing called ${chosen.asked}, and nothing has been` +
          ' opened. PLOWSHARE_AGENT has to name one of these, or be unset to' +
          ' talk to a bot:',
        ...chosen.served.map((row) => row.name),
      ];
    case 'refused':
      return [
        `${chosen.asked} is defined on this server and is not being served:` +
          ` ${chosen.why.join('; ')}`,
      ];
    case 'unmet':
      return [
        `this tier's default bot is ${chosen.named}, and it cannot answer:` +
          ` ${chosen.why.join('; ')}`,
      ];
    case 'none':
      return [
        'nothing is served here — no bot and no agent — so there is nobody to talk to.',
        ...unreadable(chosen.unreadable),
      ];
  }
}

/**
 * Who a person is talking to, and — once — what kind of thing it is.
 *
 * <h2>Naming an agent says once that this is not a conversation</h2>
 *
 * <p>Spec §4: an agent is written to be <i>consumed</i> — by a harness, by
 * another agent, over MCP — and its answer may be a structure rather than a
 * sentence, as `image_reader`'s is, which answers under a JSON schema. A person
 * who reaches one from a terminal and gets a wall of JSON back has not hit a
 * bug, and telling them that afterwards is worse than telling them before.
 *
 * <p><b>One sentence, and not a refusal.</b> Somebody who names an agent gets
 * the agent: spec §4 keeps that as the specialised path rather than as a
 * mistake, and a client that argued about it would be refusing the thing the
 * variable exists for.
 *
 * <p><b>And nothing at all is said about kinds when a bot is what answers</b>,
 * named or not, because there the kind is not news — a bot is what a
 * conversation is with.
 *
 * <h2>Which bot, and why, when nobody named one</h2>
 *
 * <p><b>A preferred bot says so</b>, because `bots/default` is the tier's own
 * choice and a person should be able to tell it from this client's fallback.
 * <b>An unpreferred, unasked bot says it is the first served</b> rather than
 * "the only bot this server serves" — ruling 15 means it may not be the only
 * one, and a sentence that claimed it was would be wrong the day a second bot
 * is added and this client keeps talking to the same one for the same reason.
 */
function talkingTo(
  chosen: Extract<Chosen, { readonly kind: 'answering' }>,
): string {
  const { agent, asked, preferred } = chosen;
  if (!agent.bot) {
    return (
      `talking to ${agent.name}, which is an agent rather than a bot — an agent is` +
      ' written to be consumed, by a harness, by another agent or over MCP, and its' +
      ' answer may be a structure rather than a sentence'
    );
  }
  if (preferred) {
    // THE TIER'S, NOT THE PROJECT'S: the global tier has a bots/default too,
    // and it is not a project.
    return `talking to ${agent.name}, this tier's default`;
  }
  return asked
    ? `talking to ${agent.name}`
    : `talking to ${agent.name}, the first bot served here`;
}

/** The global tier, said as a place, or the project a person is already in. */
function tier(project: string | undefined): string {
  if (project) project = projectLabel(project);
  return project?.startsWith('personal:')
    ? 'Personal'
    : (project ?? 'global resources');
}

/**
 * What a person is told when `/project` or `/cd` was typed with no argument.
 *
 * <p>Each needs one: `/project` needs the name of the project to move to, and
 * `/cd` needs the directory to stand in. `PROJECTS_COMMAND` is pointed at
 * here rather than repeated, since it is the one place a name can be read off.
 */
export function describeUsage(command: string): string {
  switch (command) {
    case '/design-orchestration':
      return '/design-orchestration [--revise name] <intent> starts a granted builder in the rooted project; /runs, /watch and /answer follow its interview and human installation review';
    case PROJECT_COMMAND:
      return `${PROJECT_COMMAND} needs the name of a project — ${PROJECTS_COMMAND} lists them`;
    case CD_COMMAND:
      return `${CD_COMMAND} needs a directory to go to`;
    case FIRE_COMMAND:
      return `${FIRE_COMMAND} <name> needs the name of a schedule — ${SCHEDULE_COMMAND} list lists them`;
    case APPROVALS_COMMAND:
      return `${APPROVALS_COMMAND} revoke <id> needs exactly one approval id — ${APPROVALS_COMMAND} lists them`;
    case AGENTS_COMMAND:
      return `${AGENTS_COMMAND} takes one agent's name, or nothing at all — ${AGENTS_COMMAND} lists them`;
    case ORCHESTRATIONS_COMMAND:
      return `${ORCHESTRATIONS_COMMAND} takes one orchestration's name, or nothing at all`;
    case RUNS_COMMAND:
      return `${RUNS_COMMAND} takes one run id, or nothing at all — ${RUNS_COMMAND} lists them`;
    case WATCH_COMMAND:
      return `${WATCH_COMMAND} takes one run id, or nothing at all — the newest live run`;
    case ANSWER_COMMAND:
      return (
        `${ANSWER_COMMAND} <id> <your answer> needs both: the run's id and what to tell` +
        ` it — ${ANSWER_COMMAND} alone shows what is waiting, ${RUNS_COMMAND} <id> what it asked`
      );
    case '/resume':
    case '/retry':
      return '/resume <run-id> resumes a failed root orchestration; /retry is an alias';
    case CANCEL_COMMAND:
      return (
        `${CANCEL_COMMAND} needs exactly one run id, and stops that run and every run` +
        ` it started — ${RUNS_COMMAND} lists them`
      );
    case ALWAYS_COMMAND:
      return (
        `${ALWAYS_COMMAND} takes nothing, to stop asking, or off, to ask again, or caps,` +
        ' to continue three caps without asking'
      );
    case CAP_COMMAND:
      return (
        `${CAP_COMMAND} takes nothing, to show the caps, or steps N, budget N, auto N,` +
        ' time N (minutes), checks N or auto-increase on|off'
      );
    default:
      // `/schedule pause`, `/schedule resume`, `/schedule forget`: one name each.
      return `${command} <name> needs exactly one schedule name — ${SCHEDULE_COMMAND} list lists them`;
  }
}

/** `/here` succeeded: this directory is now rooted at the named project. */
export function describeRooted(project: string, root: string): string {
  project = projectLabel(project);
  return `rooting ${project} at ${root} — runs in it read these files`;
}

/** Said at startup when this client stands in no project the server knows. */
export function describeUnrooted(): string {
  return `no project here — ${HERE_COMMAND} roots this directory`;
}

/**
 * Said at startup when this directory is already a project on the server, and
 * simply has not been rooted by this client yet.
 */
export function describeOffered(project: string, root: string): string {
  return (
    `the server knows ${root} on this machine as ${project} — ${HERE_COMMAND} ${project}` +
    ' roots it'
  );
}

/**
 * `/project` or `/here` moved a person, in the one line that says where they
 * landed and who they are talking to there.
 *
 * @param changed whether this is a different bot from the one they were just
 *     talking to — said, because moving tiers can start a different
 *     conversation and a person should be told that happened
 */
export function describeMovedTo(
  project: string | undefined,
  who: string,
  changed: boolean,
): string {
  return changed
    ? `now in ${tier(project)}, talking to ${who} — a different bot, so a different` +
        ' conversation'
    : `now in ${tier(project)}, talking to ${who}`;
}

/**
 * Spec §6.1: nobody answers in the tier asked for, so nothing moved.
 *
 * @param rowSurvives whether a refused `/here` already created the project on the
 *     server (ruling 9) — said, because a person will see it in `/projects`
 */
export function describeRefusedMove(
  target: string | undefined,
  chosen: Chosen,
  staying: { readonly project?: string; readonly who: string },
  rowSurvives: boolean,
): string[] {
  const stay = `staying in ${tier(staying.project)} with ${staying.who}.`;
  const leftover =
    rowSurvives && target !== undefined
      ? [
          `the server now knows ${target} as a project, with no bot and nothing rooted.`,
        ]
      : [];
  if (chosen.kind === 'none') {
    return [
      `${tier(target)} serves no bot and no agent, and nothing global answers for it.`,
      target === undefined
        ? stay
        : `${stay} Add one to ${target}'s bots/, or name one in its bots/default.`,
      ...leftover,
    ];
  }
  return [...describeChoice(chosen).slice(0, 1), stay, ...leftover];
}

/**
 * Where a project's files really are, said when they are not here.
 *
 * <p>A project rooted elsewhere still runs — its files travel over the file
 * channel to whichever machine holds them — so this is informational and not a
 * refusal.
 *
 * <p><b>Three places, not two</b> (spec §5, "either another session roots them,
 * or nobody does"). A row with a machine is rooted there; a row with a workspace
 * and no machine is one this server holds; a row with neither is rooted nowhere,
 * and calling that "the server's own" would send a person looking for files on a
 * disk that has none.
 */
export function describeFilesElsewhere(
  project: string,
  where: { readonly machine?: string; readonly workspace?: string },
): string {
  if (where.machine !== undefined) {
    return (
      `${project} is rooted on ${where.machine}, not here — its runs read files there while` +
      ' that machine is connected'
    );
  }
  if (where.workspace !== undefined) {
    return `${project}'s files are the server's own, so runs read them there`;
  }
  return (
    `nothing roots ${project} yet, so its runs have no project files to read —` +
    ` ${HERE_COMMAND} in its directory roots it`
  );
}

/** `/project` named somebody this server has never heard of. */
export function describeNoSuchProject(name: string): string {
  return `no project is called ${name} — ${PROJECTS_COMMAND} lists them. Nothing changed.`;
}

/** `/project` named the tier a person is already in. */
export function describeAlreadyIn(project: string): string {
  project = projectLabel(project);
  return `already in ${project}`;
}

/** `/here` was asked to name a directory that is already a different project. */
export function describeAlreadyMarked(
  root: string,
  existing: string,
  asked: string,
): string {
  return (
    `${root} is already the project ${existing}, so it cannot also be ${asked}.` +
    ' Nothing changed.'
  );
}

/** `/cd` succeeded: this is where the client now stands. */
export function describeMoved(
  directory: string,
  rooting: string | undefined,
): string {
  return rooting === undefined
    ? `in ${directory} — no project here; ${HERE_COMMAND} roots it`
    : `in ${directory} — still rooting ${rooting}`;
}

/** `/cd` was asked to stand somewhere that is not a directory at all. */
export function describeNotADirectory(asked: string): string {
  return `${asked} is not a directory this client can stand in. Nothing changed.`;
}

/** `/here` was asked of a client with no directory to lend — a headless one. */
export function describeNoDirectory(): string {
  return 'this client was started without a directory to root, so there is nothing here to lend';
}

/** The server would not let this client root a project it asked to root. */
export function describeRootRefused(
  project: string,
  reason: string | undefined,
): string {
  project = projectLabel(project);
  return reason === undefined
    ? `the server would not let this client root ${project}`
    : `the server would not let this client root ${project}: ${reason}`;
}

/** A rooting this client held was lost — the file channel it ran over closed. */
export function describeRootLost(
  project: string,
  reason: string | undefined,
): string {
  project = projectLabel(project);
  return reason === undefined
    ? `no longer rooting ${project} — the file channel closed`
    : `no longer rooting ${project} — the file channel closed: ${reason}`;
}

/** Rooted, but the local marker that would be found again next start was not. */
export function describeUnmarked(root: string, why: string): string {
  return (
    `rooted, but ${root}/.plowshare/project could not be written, so the next start will` +
    ` not find it: ${why}`
  );
}

/**
 * The projects, one line each: the name, and nothing else about them.
 *
 * <p><b>The name is the whole of what this listing is for.</b> It is the word a
 * person chose, and it is what `PLOWSHARE_PROJECT` takes — so a listing of names
 * answers the question somebody opens it with, which is which of these to put in
 * that variable.
 *
 * <p><b>What is deliberately left out.</b> A `ProjectView` also carries the
 * workspace, the lent directories and the effective exclusions, and
 * `ProjectListHandler` says what that adds up to: a map of this server's disk
 * and of where its secrets are fenced off. A scrollback gets piped, pasted and
 * grepped, and none of those three is needed to pick a name.
 *
 * @returns one line per project, or a single sentence when there are none — a
 *     listing that printed nothing would be indistinguishable from a command
 *     that did not run
 */
export function describeProjects(rows: readonly Project[]): string[] {
  if (rows.length === 0) {
    return ['no projects have been defined on this server'];
  }
  return rows.map((row) =>
    row.kind === 'personal'
      ? `Personal · ${row.name}`
      : row.type === 'DISJOINT'
        ? `${row.displayName ?? row.name} · DISJOINT · no sync`
        : row.name,
  );
}

/**
 * The conversations in one home, one line each.
 *
 * <p><b>The title first, the id after it.</b> A person reads the name; the id is
 * what they would have to type or paste somewhere else, so it travels on the
 * same line rather than being hidden — available, and not shouted.
 *
 * @returns one line per conversation, or a single sentence when there are none
 */
export function describeConversations(rows: readonly Conversation[]): string[] {
  if (rows.length === 0) {
    return ['no conversations have been opened here yet'];
  }
  return rows.map((row) => `${named(row)} — ${row.id}`);
}

/**
 * What is waiting for the signed-in account, oldest-said first.
 *
 * <p><b>When it arrived and how it ended, then what it said.</b> Those are the
 * two things a person scans an inbox for — is this old, and did it need me —
 * before they read the answer itself, so they are on the first line and the
 * answer is indented under it rather than run on beside it.
 *
 * @returns one two-line block per item, or a single sentence when there is
 *     nothing unread
 */
export function describeInbox(page: InboxPage): string[] {
  if (page.items.length === 0) {
    return ['nothing unread in your inbox'];
  }
  return page.items.flatMap((item) => [
    `${item.arrivedAt} · ${item.ending ?? item.kind.replace('.', ' ')}`,
    ...item.answer.split('\n').map((line) => `  ${line}`),
  ]);
}

/**
 * What a conversation is called, or the stand-in for one that is not called
 * anything.
 *
 * <p><b>The fallback is said, not shown.</b> The conversation is real; it simply
 * has no name yet — every conversation that existed before the title column has
 * one permanently, since there was no backfill, and so does every conversation
 * opened and never spoken into. That makes the unnamed row the ordinary one
 * here rather than the edge case.
 *
 * <p>So the phrase goes where the name would have gone and the id keeps its own
 * place on the line. The alternative — the id standing bare in the name's slot —
 * is what a console did with `cnv_3134E666E2D847AD`, and it reads as though that
 * <i>is</i> what the conversation is called. The server deliberately sends a
 * null rather than inventing an `"Untitled"`, so that what stands in for it is
 * each client's own decision said out loud; this is this client's, written once
 * here rather than in whatever prints it.
 */
function named(row: Conversation): string {
  return row.title ?? '(not yet named)';
}

/**
 * What a person is told when nothing is being continued.
 *
 * <b>Said at the top of a first run, and only when there is really nothing.</b>
 * The banner has to be able to tell a person which of the two happened, and the
 * failure this pair exists to prevent is the one that claims a conversation was
 * picked up when none was — so this sentence says what is true of a fresh
 * start, that the server holds nothing yet and will not until somebody speaks.
 *
 * <p>It was written inline in `view/main.ts`, which was this file's own rule
 * being broken by the one line that predates {@link describeContinuing}. Its
 * opening words are load-bearing in one other place: `composition.test.ts` cuts
 * the scrollback into turns by skipping the preamble, and finds it by prefix.
 */
export function describeNew(): string {
  return 'new conversation — nothing is kept until you speak';
}

/**
 * What a person is told when the conversation they had is still there.
 *
 * <h2>It is being viewed, and the sentence says so</h2>
 *
 * <p>Enzo, setting this: <i>"it's not restored, it's viewed — you can't undo
 * the append-only log... you're just traversing the log."</i> Nothing here
 * claims a session came back, because none did: what came back is an id, and
 * what is below is what the log holds now. A case in `session.test.ts` holds
 * the words "restored" and "resumed" out of these lines, which is cheap and is
 * the exact thing a later edit would get wrong.
 *
 * <h2>The second line is about this client and not about the log</h2>
 *
 * <p>A tail is shown — `session.LOG_BACK` says how long and that nothing has
 * measured it — so the count has to be said out loud, or a person reading a
 * conversation from the middle would think that was where it began. <b>It is a
 * display decision and not a truncation</b>: the log keeps what it keeps, and
 * the line is phrased as what is below rather than as what was lost.
 *
 * @param row the conversation being carried on with
 * @param held how many turns the log answered with
 * @param shown how many of them are below
 * @returns one line, or two when a tail is being shown
 */
export function describeContinuing(
  row: Conversation,
  held: number,
  shown: number,
): string[] {
  const opening = `continuing ${named(row)} — ${row.id}`;
  if (shown >= held) {
    return [opening];
  }
  return [
    opening,
    `${counted(held, 'turn')} in this log; the last ${shown} are below`,
  ];
}

/**
 * Where a fold happened, said as the fact it is.
 *
 * <h2>A fold is a fact and not a gap to see through</h2>
 *
 * <p>Compaction summarises the turns up to a point and the summary is what the
 * next turn was shown in their place. The turns themselves are still in the log
 * — `CompactionView` promises it, and this client may well have some of them
 * above this line — so the sentence does not say they are gone. What it must
 * not do is the other thing: <b>offer to unfold one</b>. There is no frame that
 * answers that, there is nothing to ask for, and a line reading "show all"
 * would promise a client that cannot exist. A case holds that word and its
 * synonyms out of this sentence.
 *
 * @param fold the seam, off a `summary` entry in the log. Its summary is printed under this
 *     line by whatever is rendering markdown, which is the view's job
 */
export function describeSeam(fold: Fold): string {
  return (
    `folded at turn ${fold.throughOrdinal} — everything up to it was summarised into` +
    ' what follows, and the summary is what the next turn was shown in its place'
  );
}

/**
 * Which model answered, in a person's words, or `''` for an entry that names
 * none — what the explorer's inspector says of an answer.
 *
 * <b>Provenance is said only where there is any.</b> An utterance and a tool
 * result name no model, so they get nothing rather than a "no model" clause
 * that would be noise on every second row. A fallback is named as one — a
 * reader can see an assistant reply came from the model the agent falls back
 * to and not from the one that would have refused.
 */
export function describeProvenance(entry: Entry): string {
  if (entry.dispatch === undefined) {
    return '';
  }
  const named = entry.wireModel === undefined ? '' : ` (${entry.wireModel})`;
  const outcome =
    entry.completion === 'refused'
      ? ', a refusal'
      : entry.completion === 'cut_off'
        ? ', cut off at the token limit'
        : entry.completion === 'called_tools'
          ? ', asked for tools'
          : '';
  return entry.dispatch === 'fallback'
    ? `answered by the fallback${named}${outcome}`
    : `answered by the agent's own model${named}${outcome}`;
}

/**
 * The heading over a person's utterance read back out of the log. <b>A person's only</b>:
 * something the harness said is {@link describeHarness}'s, and is never "you said".
 */
export function describeSpokenIn(turn: number): string {
  return `turn ${turn}, you said:`;
}

/**
 * Something the harness said into a conversation, as a person reads it: `⚙`, where it came
 * from, and the first line of what it said, with the rest to go under it.
 *
 * @param source what the log recorded: `orchestration <id>`, `approval <id>`, `event <id>`
 *     or `harness`
 */
export function describeHarness(
  source: string,
  text: string,
): { readonly heading: string; readonly rest: string } {
  const [first = '', ...more] = text.split('\n');
  return {
    heading: `⚙ ${harnessNamed(source)}: ${first.trim()}`,
    rest: more.join('\n').trim(),
  };
}

/** A source as a person names it: a run, an approval, an event, or the harness itself. */
function harnessNamed(source: string): string {
  const [kind, id] = source.split(' ', 2);
  if (id === undefined || id === '') {
    return 'harness';
  }
  if (kind === 'orchestration') {
    return `run ${id}`;
  }
  if (kind === 'approval' || kind === 'event') {
    return `${kind} ${id}`;
  }
  return 'harness';
}

/** An answer the page cut short, said under it rather than passed off as the whole. */
export function describeCut(shown: number, length: number): string {
  return (
    `cut here — the log holds all ${length} characters of this answer, and the page` +
    ` carries the first ${shown}`
  );
}

/**
 * The four sentences of changing a password because the server insists.
 *
 * <p><b>Here rather than in the view, on this file's rule</b>, and it holds:
 * every one of these is the same sentence in a window with a form in it. What
 * is terminal-specific is that the typing is drawn as dots, and that is not a
 * sentence.
 */
export const PASSWORD_MUST_CHANGE =
  'this account has a password somebody else chose. Set your own to go on.';

/** What to type. Says the one rule the server will refuse on, before it does. */
export const PASSWORD_ASK =
  'a new password — anything but an obvious one (`password`, `changeme`, `admin`…):';

/** Asked twice, because a typo here locks somebody out of their own server. */
export const PASSWORD_AGAIN = 'and again, to be sure:';

/** They did not match. Not a failure of anything, so it does not read as one. */
export const PASSWORD_DIFFERED =
  'those two did not match. Nothing was changed — try again.';

/** Done, and what happens next, because the client is about to reconnect. */
export const PASSWORD_CHANGED =
  'password changed. Signing in again with it — the old session ended when it changed.';

/**
 * Who is being talked to and how full their context is, as the status line
 * says it.
 *
 * <p><b>What it says and how full that is, and never a colour.</b> `pressure` is
 * the fact a view colours by; which colour is the view's.
 */
export interface Standing {
  /** `who:server:model`, or `who:server` while the model is unknown. */
  readonly triplet: string;

  /** `16.2K/120K 13%`, or less of it; absent when nothing is known yet. */
  readonly load?: string;

  /** Set from 70% of the window, and `full` from 90%. */
  readonly pressure?: 'filling' | 'full';

  /**
   * How full, from 0 to 1, whenever the window is known — 0 before a turn has
   * been measured, which a view draws as an empty track. Absent with no
   * window, since there is nothing to be a fraction of.
   */
  readonly filled?: number;

  /** The last run's pace, most important first; a view drops from the end. */
  readonly pace?: readonly PacePart[];

  /** `⚠ 2 sync conflicts`, from {@link syncNotice} in `union.ts`; absent with none open. */
  readonly sync?: string;
}

/** The phase as the working line says it. */
export function describePhase(phase: Phase): string {
  switch (phase.kind) {
    case 'processing':
      return 'processing prompt';
    case 'thinking':
      return 'thinking';
    case 'responding':
      return 'responding';
    case 'tool':
      return `calling ${phase.tool}`;
  }
}

/** From here a load is `filling`. */
export const FILLING_AT = 70;

/** From here a load is `full`. */
export const FULL_AT = 90;

/**
 * The status line: who answers, on which server, under which model, and how
 * much of that model's context the conversation now takes.
 *
 * <p><b>The server is the Plowshare server this client signed in to</b> — host
 * and port, no scheme — and not the pool that served a call. Which pool served
 * one is decided per call; this line is about where a person is.
 *
 * <p><b>The load is the newest measured prompt</b>, the model's own count, and
 * the window is the ceiling compaction folds under. Before a turn has been
 * measured the used half is a dash, which says "not counted" rather than the
 * zero that would say "counted, and empty".
 *
 * @param server the base URL signed in to, as `PLOWSHARE_URL` gave it
 */
export function describeStanding(state: {
  readonly who: string;
  readonly server: string;
  readonly model?: string;
  readonly sent?: number;
  readonly limit?: number;
  readonly pace?: Pace;
  readonly sync?: string;
}): Standing {
  const paced =
    state.pace === undefined ? {} : { pace: describePace(state.pace) };
  const sync = state.sync === undefined ? {} : { sync: state.sync };
  return { ...loaded(state), ...paced, ...sync };
}

/** The triplet and the load, without the pace. */
function loaded(state: {
  readonly who: string;
  readonly server: string;
  readonly model?: string;
  readonly sent?: number;
  readonly limit?: number;
}): Standing {
  const triplet = [state.who, hostOf(state.server), state.model]
    .filter((part): part is string => part !== undefined && part !== '')
    .join(':');
  const { sent, limit } = state;
  if (limit === undefined || limit <= 0) {
    return sent === undefined
      ? { triplet }
      : { triplet, load: `peak ${tokens(sent)}` };
  }
  if (sent === undefined) {
    return { triplet, load: `—/${tokens(limit)}`, filled: 0 };
  }
  // Never 0% for a prompt that cost something: a person reads 0% as empty.
  const percent = Math.max(1, Math.round((sent / limit) * 100));
  const pressure =
    percent >= FULL_AT ? 'full' : percent >= FILLING_AT ? 'filling' : undefined;
  return {
    triplet,
    load: `peak ${tokens(sent)}/${tokens(limit)} ${percent}%`,
    filled: Math.min(1, sent / limit),
    ...(pressure === undefined ? {} : { pressure }),
  };
}

/**
 * `http://127.0.0.1:8080/` as `127.0.0.1:8080`. By hand, because `URL` is a
 * platform global and this file touches none — `neutrality.test.ts`.
 */
function hostOf(server: string): string {
  const authority =
    server.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split(/[/?#]/)[0] ?? '';
  const host = authority.slice(authority.lastIndexOf('@') + 1);
  return host === '' ? server : host;
}

/**
 * Heartbeats stopped arriving while a run was still being followed.
 *
 * <p><b>Two things a person has to be able to tell apart</b>, and a client that
 * conflated them would report a finished turn as a failure or, worse, the
 * reverse: <i>the run ended</i>, and <i>the run is still going and this client
 * has lost sight of it</i>. This is the second. The first arrives as an `ended`
 * event and says so in its own words.
 *
 * <p><b>It does not say the run failed, because it very probably did not.</b>
 * A run is server-side -- `agent.run` hands back a handle and the job goes on
 * living in the server whether or not anybody is listening -- so what stopped
 * is the watching, not necessarily the work. The same sentence in a window.
 */
export function describeSilence(seconds: number): string {
  return (
    `nothing from the server for ${seconds}s — the run is probably still` +
    ' going, but this client has lost sight of it'
  );
}

/**
 * One moment, as a person reads it in the zone it is shown in: `Tue 15 Sep 09:00`.
 *
 * <p><b>Shown as it arrived when it cannot be formatted</b> — a zone this
 * runtime cannot name, or a string that is not a moment. A fire time is the
 * safeguard beside a model's `when`, so an unformattable one must still reach
 * the screen rather than disappear or throw.
 */
export function describeWhen(iso: string, zone: string): string {
  const moment = new Date(iso);
  if (Number.isNaN(moment.getTime())) {
    return iso;
  }
  let parts: Intl.DateTimeFormatPart[];
  try {
    parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(moment);
  } catch {
    return iso;
  }
  const part = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((each) => each.type === type)?.value ?? '';
  return `${part('weekday')} ${part('day')} ${part('month')} ${part('hour')}:${part('minute')}`;
}

/** The indent a multi-line task's later lines take, under `task: `. */
const TASK_INDENT = '      ';

/**
 * A proposal, whole, ending in the question it is waiting on.
 *
 * <p><b>Nothing is dropped</b>, because the person is about to say yes to
 * exactly these lines: `when` beside the cron and the zone, the computed fire
 * times beside that — the model's `when` is unchecked, the fire times are not —
 * who runs, where, where the result goes, and the task verbatim, every line.
 *
 * <p>Meant to be shown as plain text rather than through the markdown grammar:
 * a cron's asterisks and a task's underscores are characters, not emphasis.
 */
export function describeProposal(proposal: Proposal): string[] {
  const runsIn =
    proposal.project ??
    (proposal.intoConversation ? "this conversation's home" : 'global');
  const results = proposal.intoConversation
    ? 'this conversation'
    : 'your inbox';
  const [first = '', ...rest] = proposal.task.split('\n');
  return [
    `${proposal.when} → ${proposal.cron} (${proposal.zone})`,
    `next: ${proposal.nextFires.map((fire) => describeWhen(fire, proposal.zone)).join(' · ')}`,
    `agent: ${proposal.agent} · runs in: ${runsIn} · results: ${results}`,
    [`task: ${first}`, ...rest.map((line) => `${TASK_INDENT}${line}`)].join(
      '\n',
    ),
    'save? [y/n]',
  ];
}

/** A proposal saved: its name, and the first time it will fire. */
export function describeSaved(proposal: Proposal): string {
  const first = proposal.nextFires[0];
  return first === undefined
    ? `saved ${proposal.names.schedule}`
    : `saved ${proposal.names.schedule} · next ${describeWhen(first, proposal.zone)}`;
}

/**
 * A proposal not saved.
 *
 * @param ranNothing whether the line that answered was something else — a
 *     command or a sentence — which was taken as the answer and not run
 */
export function describeNotSaved(ranNothing: boolean): string {
  return ranNothing
    ? 'not saved — that line was taken as the answer and was not run, so type it again'
    : 'not saved';
}

/**
 * The schedule was saved and its trigger was refused, so the schedule was
 * taken away again: a schedule nobody listens to fires into nothing.
 *
 * @param removed whether the `schedule.forget` that followed succeeded; when
 *     it did not, the schedule is still there and the person is told its name
 */
export function describeUndone(schedule: string, removed: boolean): string {
  return removed
    ? `removed the schedule ${schedule} again, so nothing was saved`
    : `the schedule ${schedule} was saved and could not be removed again —` +
        ` ${SCHEDULE_COMMAND} forget ${schedule} removes it`;
}

/** A schedule paused or resumed, with its triggers. */
export function describePaused(name: string, paused: boolean): string {
  return `${paused ? 'paused' : 'resumed'} ${name}`;
}

/** A schedule forgotten, with its triggers. */
export function describeForgot(name: string): string {
  return `forgot ${name}`;
}

/** `/fire` named a schedule this account does not have. */
export function describeNoSchedule(name: string): string {
  return `no schedule named ${name} — ${SCHEDULE_COMMAND} list lists them`;
}

/** How far a trigger's task is shown in a listing, before it is cut. */
const TASK_BUDGET = 60;

/** A task's first line, cut at {@link TASK_BUDGET} with `…` when anything was cut. */
function firstLineOf(task: string): string {
  const lines = task.split('\n');
  const first = lines[0] ?? '';
  return first.length > TASK_BUDGET || lines.length > 1
    ? `${first.slice(0, TASK_BUDGET)}…`
    : first;
}

/** One trigger, as the line under the schedule that wakes it. */
function listenerLine(trigger: Trigger): string {
  const line = `  → ${trigger.agent}: ${firstLineOf(trigger.task)}`;
  return trigger.paused ? `${line} · paused` : line;
}

/**
 * The schedules, each followed by the triggers listening to its event, then
 * the triggers no schedule wakes.
 *
 * <p>Joined by `emits == event`, which is the only thing that ties the two: a
 * schedule emits an event, and a trigger listens for one by name.
 */
export function describeSchedules(
  schedules: readonly Schedule[],
  triggers: readonly Trigger[],
): string[] {
  if (schedules.length === 0 && triggers.length === 0) {
    return ['no schedules'];
  }
  const emitted = new Set(schedules.map((schedule) => schedule.emits));
  const lines = schedules.length === 0 ? ['no schedules'] : [];
  lines.push(
    ...schedules.flatMap((schedule) => {
      const head = [`${schedule.name} · ${schedule.cron} (${schedule.zone})`];
      if (schedule.nextFireAt !== undefined) {
        head.push(`next ${describeWhen(schedule.nextFireAt, schedule.zone)}`);
      }
      if (schedule.paused) {
        head.push('paused');
      }
      return [
        head.join(' · '),
        ...triggers
          .filter((trigger) => trigger.event === schedule.emits)
          .map(listenerLine),
      ];
    }),
  );
  const orphans = triggers.filter((trigger) => !emitted.has(trigger.event));
  if (orphans.length > 0) {
    lines.push('triggers with no schedule:', ...orphans.map(listenerLine));
  }
  return lines;
}

/** One firing's outcome: who took it, how it went, and why when there is a why. */
function firingOutcome(firing: Firing): string {
  const head = `${firing.trigger ?? '(nobody listening)'} · ${firing.status}`;
  return firing.reason === undefined ? head : `${head} · ${firing.reason}`;
}

/** The firings, newest first, each with when it arrived in the given zone. */
export function describeFirings(
  firings: readonly Firing[],
  zone: string,
): string[] {
  if (firings.length === 0) {
    return ['no firings yet'];
  }
  return firings.map(
    (firing) =>
      `${describeWhen(firing.arrivedAt, zone)} · ${firingOutcome(firing)}`,
  );
}

/** What firing a schedule by hand made: one line a firing. */
export function describeFired(
  event: string,
  firings: readonly Firing[],
): string[] {
  if (firings.length === 0) {
    return [`fired ${event}, and nothing is listening to it`];
  }
  return firings.map(firingOutcome);
}

/*
 * ASKING A PERSON BEFORE A COMMAND RUNS. Spec 2026-09-15, asking a person, §5.
 */

/**
 * One question: the command, where it would run and why it was asked.
 *
 * <p><b>The command is joined with spaces and not quoted</b>, the way the
 * server's own utterance writes it: this is a person reading what would run,
 * and a shell-quoting pass would be this client inventing a syntax the run
 * never used.
 */
export function describeQuestion(approval: Approval): string[] {
  if (approval.commands !== undefined) {
    // AN ACCEPTANCE SET (V67): every command, one answer for all of them — and why the command
    // judge, when it was shown them, put them to the person.
    const count = approval.commands.length;
    return [
      `a run asks to run ${count} acceptance command${count === 1 ? '' : 's'} on the` +
        ` ${approval.side} side, one answer for all of them:`,
      ...approval.commands.map((command) => `  ${command.join(' ')}`),
      `  in ${approval.cwd}`,
      ...(approval.judged === undefined || approval.judged === ''
        ? []
        : [
            `  the command judge did not find them clearly safe: ${approval.judged}`,
          ]),
    ];
  }
  return [
    `a run asks to run a command on the ${approval.side} side:`,
    `  ${approval.command.join(' ')}`,
    `  in ${approval.cwd}`,
    ...(approval.reason === undefined || approval.reason === ''
      ? []
      : [`  because ${approval.reason}`]),
  ];
}

/**
 * The keys that answer a question, from where it stands.
 *
 * <p><b>While choosing the prefix, the prefix is the line that changes</b>, and
 * what it leaves out is shown after it, so a person pressing ← watches the
 * covered part shrink rather than reading a number.
 */
export function describeKeys(state: Asking): string[] {
  switch (state.kind) {
    case 'choosing':
      // A set takes no project prefix (V67), so it is not offered one.
      return state.approval.commands !== undefined
        ? ['o once · c for this conversation · d deny · esc leave it for later']
        : [
            'o once · c for this conversation · p for this project · d deny · esc leave it for later',
          ];
    case 'prefixing': {
      const covered = state.approval.command.slice(0, state.length).join(' ');
      const rest = state.approval.command.slice(state.length).join(' ');
      return [
        `allow any command starting: ${covered}${rest === '' ? '' : `   (not: ${rest})`}`,
        '← → one argument less or more · enter allow it for this project · esc back',
      ];
    }
    default:
      return [];
  }
}

/** What a person decided, as their own entry: the answer, and for a project the prefix it covers. */
export function describeDecided(state: Asking): string {
  if (state.kind !== 'answered') {
    return '';
  }
  const payload = state.ask.payload;
  const prefix = isList(payload['prefix'])
    ? (payload['prefix'] as string[]).join(' ')
    : '';
  const set = state.approval.commands;
  const it =
    set === undefined
      ? 'it'
      : set.length === 1
        ? 'the one command'
        : `all ${set.length}`;
  switch (payload['decision']) {
    case 'once':
      return `allow ${it} once`;
    case 'conversation':
      return `allow ${it} for this conversation`;
    case 'project':
      return `allow any command starting ${prefix} for this project`;
    default:
      return set === undefined ? 'deny it' : `deny ${it}`;
  }
}

/** Escape at the choice: nothing was sent, and where the question still is. */
export function describeLeftOpen(approval: Approval): string {
  return (
    `left unanswered — the run stays stopped at its question (${approval.id}); say something` +
    ' to carry on, and it asks again if it still needs that command'
  );
}

/**
 * What an answer did. A continuing turn says nothing here — following it is the
 * sentence — so this is only ever the busy case, in the server's own words when
 * it sent some.
 */
export function describeAnswered(answered: Answered): string {
  const decided = answered.state === 'denied' ? 'denied' : 'allowed';
  const why =
    answered.note === undefined || answered.note === ''
      ? 'this conversation is busy'
      : answered.note;
  return (
    `${decided}, and that stands — but no turn was started to carry on (${why}); your next` +
    ' message carries on'
  );
}

/**
 * `/approvals`: the project approvals standing in this project, one a line,
 * with the id `revoke` takes.
 */
export function describeApprovals(rows: readonly Approval[]): string[] {
  if (rows.length === 0) {
    return ['no commands are approved for this project — a run asks you first'];
  }
  return [
    ...rows.map(
      (row) =>
        `${row.id}  ${row.side}  ${(row.prefix ?? row.command).join(' ')} …`,
    ),
    `${APPROVALS_COMMAND} revoke <id> takes one back`,
  ];
}

/** `/approvals` somewhere that is no project, where nothing can be approved. */
export function describeNoApprovals(): string {
  return `approvals belong to a project, and you are in none — ${PROJECTS_COMMAND} lists them`;
}

/** `/approvals revoke <id>`: whether it was taken back. */
export function describeRevoked(id: string, revoked: boolean): string {
  return revoked
    ? `${id} revoked — a run asks you again before running those commands`
    : `${id} was not revoked: only a standing project approval can be, and this one is not`;
}

/*
 * ORCHESTRATIONS, READ ONLY. Spec 2026-09-13, §7.
 *
 * Four screens over three frames: what can be started here, one of those whole,
 * what this account has started, and one run's stages, questions and children.
 * Nothing here starts, answers or cancels anything — every sentence below is
 * about something that already exists.
 */

/**
 * A refused definition, as one row: its name and the server's own reason.
 *
 * <p><b>A row whose reason came back empty still says it was refused.</b> The
 * server always sends one, so this is the shape of a payload this build could not
 * read — and `… could not be read:` with nothing after the colon reads as a
 * client that broke mid-sentence rather than as a reason that is missing.
 */
function refusedDefinition(row: Definition): string {
  return (
    `${row.name} — could not be read` +
    `${row.withheld === '' ? ', and this server did not say why' : `: ${row.withheld}`}`
  );
}

/**
 * What can be started where a person is standing, and <b>which set that is</b>.
 *
 * <p><b>The first line names the scope, always.</b> `orchestration.definitions`
 * is scoped by project and degrades an absent project to the boot set, so the
 * same command answers two different questions and the rows do not say which one
 * was answered. A person standing in a project who saw only global definitions
 * would reasonably conclude their project defines none.
 *
 * <p><b>The header names the scope that was <i>asked for</i>, not the one that
 * answered.</b> `RequestedProjectId.forListing` degrades an unresolvable project
 * name — and an unreachable archive — to the boot set as well, so
 * `orchestrations in plowshare:` can sit above global rows and nothing on the
 * wire says it happened. This is the honest reading of what this client knows:
 * the project it asked under. Saying nothing would be worse, since then the two
 * scopes are indistinguishable even in the ordinary case.
 *
 * <p><b>A refused file is a row with its reason</b>, as the roster already shows
 * a withheld agent: filtering would make the least readable definitions the ones
 * that vanish, and somebody whose orchestration disappeared needs to be told it
 * was refused on the screen they went looking for it.
 *
 * @param project where the person is, or nothing for the boot set
 */
export function describeOrchestrations(
  rows: readonly Definition[],
  project: string | undefined,
): string[] {
  if (rows.length === 0) {
    return [`no orchestrations in ${tier(project)}`];
  }
  return [
    `orchestrations in ${tier(project)}:`,
    ...rows.flatMap((row) => {
      if (!row.served) {
        return [refusedDefinition(row)];
      }
      const head =
        row.description === ''
          ? `${row.name} · ${row.tier}`
          : `${row.name} · ${row.tier} — ${briefly(row.description)}`;
      return [
        head,
        // The stage ids alone, joined, because this is the listing: the
        // order is what says what the orchestration does, and each
        // stage's done-when is what `/orchestrations <name>` is for.
        ...(row.stages.length === 0
          ? []
          : [`  stages: ${row.stages.map((stage) => stage.id).join(' → ')}`]),
        ...row.triggers.map((trigger) => `  when ${trigger}`),
      ];
    }),
  ];
}

/**
 * One definition, with its stages one per line and the done-when of each that
 * has one.
 *
 * <p><b>Read out of the listing rather than asked for by name</b>, because there
 * is no frame that answers one definition. So a name nothing answers to is said
 * here, in a sentence that names the set that was searched — the scope is the
 * likeliest reason a name is missing.
 */
export function describeOrchestration(
  name: string,
  rows: readonly Definition[],
  project: string | undefined,
): string[] {
  const row = rows.find((each) => each.name === name);
  if (row === undefined) {
    return [
      `no orchestration in ${tier(project)} is called ${name} —` +
        ` ${ORCHESTRATIONS_COMMAND} lists the ones that are`,
    ];
  }
  if (!row.served) {
    return [refusedDefinition(row)];
  }
  return [
    `${row.name} · ${row.tier}`,
    // WHOLE, AND NOT CUT DOWN. `briefly` is the roster's rule, where a long
    // description would draw a paragraph where a row was promised; this
    // screen is the one place the definition's own sentence belongs entire.
    ...(row.description === '' ? [] : [row.description]),
    ...(row.stages.length === 0
      ? ['it declares no stages']
      : [
          'stages:',
          ...row.stages.map((stage) =>
            stage.doneWhen === undefined
              ? `  ${stage.id}`
              : `  ${stage.id} — done when ${stage.doneWhen}`,
          ),
        ]),
    ...(row.triggers.length === 0
      ? []
      : ['it is for:', ...row.triggers.map((trigger) => `  ${trigger}`)]),
  ];
}

/**
 * One run's heading: what it is, where it has got to, and when it began.
 *
 * <p><b>A part that came back empty is left out rather than separated.</b>
 * `session.runIn` requires only the id and the state, so a row this build could
 * not otherwise read would otherwise draw `orc_1 ·  ·  ·` — separators around
 * nothing, which reads as a broken client rather than as a row with gaps.
 *
 * <p><b>The pending cap is named beside the state</b>, because that is what it
 * qualifies: `asking (turn_cap)` is a run stopped on a raise-the-cap question,
 * which is a different thing to answer from a question about the work, and
 * `asking` alone does not say which is waiting.
 */
function runLine(row: Run, zone: string): string {
  return [
    row.id,
    row.definition,
    row.pendingCap === undefined
      ? row.state
      : `${row.state} (${row.pendingCap})`,
    describeWhen(row.createdAt, zone),
    ...(row.parent === undefined ? [] : [`child of ${row.parent}`]),
  ]
    .filter((part) => part !== '')
    .join(' · ');
}

/**
 * This account's orchestration runs, newest first, and the id that opens one.
 *
 * <p><b>A child says whose child it is.</b> Its parent may not be among the
 * twenty rows shown, which is exactly when the id is the only thing that makes
 * the relation legible — and a child run listed as though somebody had started
 * it is the one reading that would be wrong.
 */
export function describeRuns(rows: readonly Run[], zone: string): string[] {
  if (rows.length === 0) {
    return [
      `no orchestration runs on this account — ${ORCHESTRATIONS_COMMAND} lists what can` +
        ' be started',
    ];
  }
  return [
    ...rows.map((row) => runLine(row, zone)),
    `${RUNS_COMMAND} <id> shows one run's stages, what it has asked and what it started`,
  ];
}

/**
 * One run: where it stands, its stages, its message history and its children.
 *
 * <h2>An empty section is left out rather than headed</h2>
 *
 * <p>A run that has just started has no stages, has asked nothing and has
 * started nobody, and three headings over nothing would read as three things
 * that went missing. What is always said is the run itself — the one line that
 * is never empty.
 *
 * <p><b>The stages are the conductor's todo list</b>, which is where the engine
 * keeps them: `orchestration.status` answers with `todos`, and they are shown
 * under the word the definition uses for them. A row that belongs to a declared
 * stage names it; one the conductor added itself does not, and that difference is
 * worth keeping on the screen.
 *
 * <h2>The outcome is the point of the screen</h2>
 *
 * <p>`result` and `failure` are the two things a person opens a run to read once
 * it is over, and `RunView` sets exactly one of them: the result once the state
 * is `finished`, the failure once it is terminal any other way. A view that said
 * a run had ended and not what it ended as would send somebody to the
 * conversation archive for the one fact they came here for. Each gets its own
 * labelled line rather than joining the heading, because either can be a
 * sentence — a failure in particular is the engine's own explanation, not a word.
 */
export function describeRun(status: RunStatus, zone: string): string[] {
  const run = status.run;
  return [
    runLine(run, zone),
    ...(run.depth === 0 ? [] : [`depth ${run.depth}`]),
    ...(run.waitingFor === undefined ? [] : [`waiting for ${run.waitingFor}`]),
    ...(run.endedAt === undefined
      ? []
      : [`ended ${describeWhen(run.endedAt, zone)}`]),
    ...(run.result === undefined ? [] : [`result: ${run.result}`]),
    ...(run.failure === undefined
      ? []
      : [`it stopped because: ${run.failure}`]),
    ...(status.stages.length === 0
      ? []
      : [
          'stages:',
          ...status.stages.map((stage) =>
            [
              `  ${stage.status} · ${stage.text}`,
              ...(stage.stage === undefined ? [] : [`(${stage.stage})`]),
              ...(stage.summary === undefined ? [] : [`— ${stage.summary}`]),
            ].join(' '),
          ),
        ]),
    ...(status.messages.length === 0
      ? []
      : [
          'messages:',
          ...status.messages.map(
            (message) =>
              `  ${message.kind} · ${message.author}: ${message.text}`,
          ),
        ]),
    ...(status.children.length === 0
      ? []
      : [
          'children:',
          ...status.children.map((child) => `  ${child.id} · ${child.state}`),
        ]),
  ];
}

/**
 * How many runs are waiting on a person, for the status line — or nothing for
 * none, which is the status line saying nothing about it, as it does for an
 * empty inbox.
 */
export function describeWaitingCount(count: number): string | undefined {
  if (count <= 0) {
    return undefined;
  }
  // Not "runs", and not "your answer": a command waiting to be allowed is counted
  // here too, and so is a run that has gone quiet, which is not answered but looked
  // at or stopped. A bare /answer lists all three and says which is which.
  return `${count} waiting on you · ${ANSWER_COMMAND}`;
}

/**
 * That a run has started waiting on a person, and what it asked, in one line.
 *
 * <p><b>Printed once per run, by the background check and not by the push.</b>
 * The push carries an id and a state and nothing more, so a line written from it
 * could only say where to go and look; the check reads the run's status, so the
 * line can say the question itself and the person can answer without leaving
 * the prompt.
 *
 * <p>A run asking to have a cap raised asked nothing in words, and says which
 * cap. A run whose status could not be read is pointed at rather than described.
 */
export function describeNowAsking(
  run: Waiting,
  columns = DEFAULT_COLUMNS,
): string {
  if (run.kind === 'approval') {
    const decisions = `${ANSWER_COMMAND} ${run.id} once, or ${ANSWER_COMMAND} ${run.id} deny`;
    const set = run.approval?.commands;
    if (set !== undefined) {
      // A SET IS ONE ITEM (V67), its commands one per line under it — wrapped here, on
      // `wrap.ts`'s rule, so a long command is rows the surface counts — then the decisions,
      // which answer every one of them.
      const width = Math.max(NARROWEST_BODY, columns - 4);
      return [
        `${run.definition} wants to run ${set.length} acceptance command${set.length === 1 ? '' : 's'}` +
          ' at its acceptance stage:',
        ...set.flatMap((command) =>
          wrapText(command.join(' '), width).map((row) => `    ${row}`),
        ),
        `— ${decisions}`,
      ].join('\n');
    }
    // A command, not a question: it takes a decision rather than an answer in words,
    // so the line gives both decisions in full rather than "/answer to reply".
    return `${run.definition} wants to ${run.question ?? 'run a command'} — ${decisions}`;
  }
  if (run.kind === 'stalled') {
    // Nothing to answer: a stall is a decision for a person to make about the run
    // itself, not a question the conductor is waiting on words for.
    return (
      `${run.definition} (${run.id}) has done nothing for a while` +
      ` — ${RUNS_COMMAND} ${run.id} to look, ${CANCEL_COMMAND} ${run.id} to stop it`
    );
  }
  const who = `${run.definition} (${run.id}) is asking`;
  const what = askedBy(run);
  return what === undefined
    ? `${who} — ${RUNS_COMMAND} ${run.id} to read it`
    : `${who}: ${what} — ${ANSWER_COMMAND} to reply`;
}

/**
 * The runs waiting on a person, for a bare `/answer` — or for one that held an
 * answer back because nothing said which run it was for.
 *
 * <p><b>The shortcut is offered only when it applies.</b> `/answer <your
 * answer>` reaches the one run waiting and is held with two, so a list of two
 * that suggested it would suggest the one form that is refused.
 */
export function describeWaitingRuns(
  waiting: readonly Waiting[],
  held = false,
): string[] {
  const notSent = 'that answer was not sent';
  if (waiting.length === 0) {
    return [
      held
        ? `${notSent} — nothing is waiting for an answer`
        : 'nothing is waiting for an answer',
    ];
  }
  // The shortcut counts what a bare answer could actually reach, not every row shown: a
  // stalled run is listed here for visibility, same as an asking one, but never takes a
  // bare answer — so a lone stalled run must not read as the one thing `<your answer>`
  // would reach.
  const answerable = waiting.filter((run) => run.kind !== 'stalled').length;
  return [
    held ? `${notSent} — say which run it is for:` : 'waiting for an answer:',
    ...waiting.map(
      (run) => `  ${run.id}  ${run.definition} — ${detailOf(run)}`,
    ),
    answerable === 1
      ? `${ANSWER_COMMAND} <your answer> answers it`
      : `${ANSWER_COMMAND} <id> <your answer> answers one — type ${ANSWER_COMMAND} and a space to pick`,
  ];
}

/**
 * What answering a command approval from the waiting list did. The run carries on
 * either way — allowed, it runs the command; denied, it goes on without it.
 */
export function describeApprovalAnswered(id: string, state: string): string {
  return state === 'denied'
    ? `${id} denied — the run carries on without it`
    : `${id} allowed — the run carries on`;
}

/**
 * Each answerable waiting run as a row of the `/answer ` menu: its id, then what it is and what
 * it asked.
 *
 * <p><b>A stalled run is not offered.</b> It is listed by a bare `/answer` beside the rest, so the
 * person sees it, but it takes `/runs` or `/cancel` and never an answer — picking it here would
 * only compose an `/answer <id> …` the server refuses.
 */
export function waitingOffers(
  waiting: readonly Waiting[],
): { name: string; detail: string }[] {
  return waiting
    .filter((run) => run.kind !== 'stalled')
    .map((run) => ({
      name: run.id,
      detail: `${run.definition} — ${detailOf(run)}`,
    }));
}

/** How much of a question a one-line mention of it keeps. */
const QUESTION_BUDGET = 80;

/**
 * What a waiting run is doing, on one line — shared by {@link describeWaitingRuns}, which lists
 * every kind of waiting row alongside the others, and {@link waitingOffers}, which lists all but
 * the stalled ones.
 */
function detailOf(run: Waiting): string {
  return run.kind === 'stalled' ? 'stalled' : (askedBy(run) ?? 'asking');
}

/** What a waiting run asked, on one line, or nothing when that is not known. */
function askedBy(run: Waiting): string | undefined {
  if (run.question !== undefined && run.question.trim() !== '') {
    return clipped(run.question, QUESTION_BUDGET);
  }
  if (run.pendingCap === STUCK) {
    // The server's own question says why and gives both commands; this is only what
    // shows before it has been read.
    return 'stopped making progress — go on?';
  }
  if (run.pendingCap === UNCOVERED) {
    return 'whether its acceptance commands stand as written';
  }
  if (run.pendingCap === PRODUCT_CHECK) {
    return 'to check the product: what no command could';
  }
  if (run.pendingCap === CONCERNS) {
    return "about its acceptance checker's concerns";
  }
  if (run.pendingCap === CHECK_FAILURES) {
    return 'whether it goes on after its check kept failing';
  }
  if (run.pendingCap === TIME_CAP) {
    return 'whether it goes on past its time cap';
  }
  return run.pendingCap === undefined
    ? undefined
    : `asking to raise its ${run.pendingCap}`;
}

/**
 * What a settled run is now doing, after an answer or a cancel landed.
 *
 * <p><b>The state and not a congratulation.</b> An answer can leave a run
 * `running` again, or `waiting` on a child it had already started, or even
 * `asking` a second question the conductor had queued — and "answered" alone
 * would read as finished to somebody who has just unblocked a tree. The verb
 * says what this client did; the state says what the server did with it.
 */
export function describeSettled(
  settled: { readonly id: string; readonly state: string },
  verb: string,
): string {
  return `${settled.id} ${verb} — it is now ${settled.state}`;
}

/**
 * What `/always` did: the project, the file it wrote, and the way back — and, turned on,
 * how many approvals that were already waiting it allowed, since those were asked before
 * the file said anything.
 */
export function describeAlways(
  project: string,
  file: string,
  on: boolean,
  allowed: number,
): string {
  if (!on) {
    return `commands in ${project} ask first again — ${file} says mode: ask`;
  }
  const waiting =
    allowed === 0
      ? ''
      : `; ${allowed} waiting approval${allowed === 1 ? '' : 's'} allowed once`;
  return (
    `commands in ${project} now run without asking — ${file} says mode: open${waiting}.` +
    ` ${ALWAYS_COMMAND} off to ask again`
  );
}

/** `/always` where there is no project rooted on this machine to write the file in. */
export function describeAlwaysNeedsAProject(): string {
  return (
    `${ALWAYS_COMMAND} sets the project's own command policy, and there is no` +
    ' project rooted on this machine — /here roots this directory'
  );
}

/**
 * `/cap`'s answer: each cap and where it came from, and how many live runs took it. The time cap
 * (V69) is `none` unset; the failed-checks limit always has a number — the server's default five
 * when no file sets it — and says it is never auto-continued, since a repeating failure is exactly
 * what the person is asked to see.
 */
export function describeCaps(caps: Caps): string {
  const one = (name: string, setting: CapSetting, own: string): string =>
    setting.value === undefined
      ? `${name} each run's own ${own}`
      : `${name} ${setting.value} (${setting.source})`;
  const applied =
    caps.applied === 0
      ? ''
      : ` — applied to ${caps.applied} live run${caps.applied === 1 ? '' : 's'}`;
  const time =
    caps.time.value === undefined
      ? 'time none'
      : `time ${caps.time.value} minutes (${caps.time.source})`;
  const checks =
    caps.failedChecks.value === undefined
      ? ''
      : ` · checks ${caps.failedChecks.value} (${caps.failedChecks.source}), never auto-continued`;
  return (
    `caps for ${caps.project}: ${one('steps', caps.steps, 'max-turns')}` +
    ` · ${one('budget', caps.budget, 'max-model-calls')}` +
    ` · ${
      caps.autoContinue.value === undefined
        ? 'auto-continue off'
        : one('auto-continue', caps.autoContinue, '')
    }` +
    (caps.autoIncrease?.value === undefined
      ? ''
      : ` · automatic increases ${caps.autoIncrease.value ? 'on' : 'off'} (${caps.autoIncrease.source})`) +
    ` · ${time}${checks}${applied}` +
    (caps.said === undefined ? '' : `; ${caps.said}`)
  );
}

/** What `/cap … N` or `/always caps` wrote. */
export function describeCapSet(
  file: string,
  key: string,
  value: number,
  caps: Caps | undefined,
): string {
  return (
    `${file} now says ${key}: ${value}` +
    (caps === undefined
      ? ' — the server could not be asked to apply it yet'
      : ` — ${describeCaps(caps)}`)
  );
}

/** `/cap` with a value its setting cannot take: said as that, and the file left alone. */
export function describeCapOutOfRange(
  key: string,
  value: number,
  least: number,
  most: number,
): string {
  return `${key} is from ${least} to ${most}, and ${value} is out of that range; nothing was written`;
}

/** `/cap` where there is no project rooted on this machine to write the file in. */
export function describeCapNeedsAProject(): string {
  return (
    `${CAP_COMMAND} sets the project's own caps, and there is no` +
    ' project rooted on this machine — /here roots this directory'
  );
}

/**
 * A cap question as the dialog, or the plain surface, puts it (spec 2026-09-29 §2): the run and
 * why it stopped, the last thing it did — which is what a person weighs going on against — and
 * the keys. The plain surface reads lines, so there an empty one is what esc is in the dialog.
 * `always` is what `a` will write — at least `ALWAYS_CAPS`, more when more is set already.
 */
export function describeCapDialog(
  run: Waiting,
  milestone: string | undefined,
  plain: boolean,
  always: number = ALWAYS_CAPS,
): string[] {
  const why =
    run.pendingCap === 'call_budget'
      ? 'spent its model-call budget'
      : run.pendingCap === TIME_CAP
        ? 'ran past its time cap'
        : 'stopped at its turn cap';
  return [
    `${run.definition} (${run.id}) ${why}`,
    `last: ${milestone ?? 'nothing recorded yet'}`,
    `y continue · n stop · a always (auto-continue ${always}) · w watch · ` +
      (plain ? 'an empty line decides later' : 'esc later'),
  ];
}

/** A cap question somebody else answered first — said as its dialog is taken away. */
export function describeCapSettled(
  id: string,
  author: string,
  answer: string,
): string {
  return `${id}'s cap question was answered by ${author}: ${answer}`;
}

/** How many rows of a question its dialog shows before it says where the rest is. */
export const DIALOG_QUESTION_LINES = 12;

/**
 * How many rows a failing check's question shows (V69): its output is the reason it is asked —
 * some twenty lines of it, with the sentences around them — so more than any other question's.
 */
export const DIALOG_CHECK_LINES = 30;

/** What the dialog's box takes of a terminal's width: its edge and its padding, each side. */
export const DIALOG_EDGE = 4;

/**
 * Any other question the person is put in a dialog — a root run's own, a run asking whether it
 * goes on (`pendingCap` {@link STUCK}), or one the person accepts or answers in words ({@link
 * ACCEPT_KINDS}: its product to check, its acceptance checker's concerns): the run, the question
 * whole, and the keys.
 *
 * <p><b>Whole, and wrapped here</b>, on `wrap.ts`'s rule: to the width inside the dialog's box when
 * the surface says how wide it is, and inside {@link DEFAULT_COLUMNS} when it does not — the box
 * draws a row per line, and a line Ink wrapped again would be rows nobody counted. At most
 * {@link DIALOG_QUESTION_LINES} of them, and fewer where the surface has less `room` (the rows its
 * lines may take, the box's edge aside), then a line saying where the rest is.
 *
 * <p><b>Who else has it</b>, for a root's question: its caller model is delivered it too, and the
 * first answer settles it. A stuck run's question, a product check and a checker's concerns are the
 * person's alone, so they say nobody.
 *
 * <p>A root's question is answered in words, so `r` only puts `/answer` in the composer; a stuck
 * run's `y` is "go on" and `n` stops it; an accept question's `y` is `accept`, and its `r` is the
 * person's notes or direction in words, as a root's is. The plain surface reads lines, so there an empty one is
 * what esc is in the dialog.
 */
export function describeQuestionDialog(
  run: Waiting,
  plain: boolean,
  columns = DEFAULT_COLUMNS,
  room = Number.POSITIVE_INFINITY,
): string[] {
  const width = Math.max(NARROWEST_BODY, columns - DIALOG_EDGE);
  const stuck = run.pendingCap === STUCK;
  const accepting = ACCEPT_KINDS.includes(run.pendingCap ?? '');
  const checks = run.pendingCap === CHECK_FAILURES;
  const heading = `${run.id} (${run.definition}) asks:`;
  const later = plain ? 'an empty line decides later' : 'esc later';
  const keys =
    stuck || checks
      ? `y go on · n stop · w watch · ${later}`
      : accepting
        ? `y accept · r reply · w watch · ${later}`
        : `r reply · w watch · ${later}`;
  const also = alsoHas(run);
  const rest = `… ${WATCH_COMMAND} ${run.id} for the rest`;
  const rows =
    run.question === undefined
      ? ['its question could not be read — w watches the run']
      : wrapText(cleaned(run.question), width);
  const taken = (lines: readonly string[]): number =>
    lines.reduce((sum, line) => sum + wrapText(line, width).length, 0);
  // The heading, who else has it, the keys and the line after a cut are never what gives way.
  // A FAILING CHECK'S OUTPUT, A PRODUCT'S CHECKLIST AND A CHECKER'S CONCERNS are the reason they
  // are asked, so each shows more than any other question.
  const most = Math.max(
    1,
    Math.min(
      checks || accepting ? DIALOG_CHECK_LINES : DIALOG_QUESTION_LINES,
      room - taken([heading, ...also, keys, rest]),
    ),
  );
  const shown = rows.length <= most ? rows : [...rows.slice(0, most), rest];
  return [heading, ...shown, ...also, keys];
}

/**
 * Who else has a question: the caller model that started a root, whose first answer settles it as
 * the person's would. Nobody, for a question only the person may answer — a stuck run's, a
 * failing check's, an install's, a product check, a checker's concerns ({@link PERSON_ONLY_KINDS}).
 */
function alsoHas(run: Waiting): string[] {
  const personOnly = PERSON_ONLY_KINDS.includes(run.pendingCap ?? '');
  return personOnly || run.callerAgent === undefined
    ? []
    : [
        `${run.callerAgent}, which started it, has it too; the first answer settles it.`,
      ];
}

/** What each key of a harness question is called in its list, by kind — the same answers the keys line names. */
const OPTION_LABELS: Readonly<
  Record<Exclude<DialogKind, 'approval'>, Partial<Record<DialogKey, string>>>
> = {
  // `always` IS SAID WITH ITS COUNT by `dialogOptionsOf`, which is given what `a` will write.
  cap: {
    continue: 'go on',
    stop: 'stop here',
    watch: 'watch the run first',
    later: 'decide later',
  },
  stuck: {
    continue: 'go on',
    stop: 'stop the run',
    watch: 'watch the run first',
    later: 'decide later',
  },
  // A FAILING CHECK'S `n` IS `/answer <id> stop` (V69), which the server caps the run on.
  checks: {
    continue: 'go on',
    stop: 'stop the run',
    watch: 'watch the run first',
    later: 'decide later',
  },
  accept: {
    continue: 'accept',
    reply: 'reply in words — your notes or direction',
    watch: 'watch the run first',
    later: 'decide later',
  },
  question: {
    reply: 'reply in words',
    watch: 'watch the run first',
    later: 'decide later',
  },
};

const OPTION_LETTERS: Readonly<Record<DialogKey, string>> = {
  continue: 'y',
  stop: 'n',
  always: 'a',
  watch: 'w',
  reply: 'r',
  later: '',
};

/**
 * A harness question's keys as the options its modal lists, in `DIALOG_KEYS`' order. `always` is
 * what a cap's `a` will write — {@link describeCapDialog}'s own — so the list says the number too.
 */
export function dialogOptionsOf(
  kind: Exclude<DialogKind, 'approval'>,
  always: number = ALWAYS_CAPS,
): DialogOption[] {
  return DIALOG_KEYS[kind].map((key) => ({
    key,
    letter: OPTION_LETTERS[key],
    label:
      key === 'always'
        ? `always go on (auto-continue ${always})`
        : (OPTION_LABELS[kind][key] ?? key),
  }));
}

/**
 * A harness question in the modal — spec 2026-09-29-orchestration-studio §2.5, decision (c): its
 * own lines as they always were, but its keys as a list the arrows move over, each with its letter,
 * so the gesture is the one a question with options takes.
 */
export function describePickingDialog(
  lines: readonly string[],
  state: Picking,
): string[] {
  if (state.kind === 'picked') {
    return [...lines];
  }
  const options = state.options.map(
    (option, index) =>
      `${index === state.focus ? '›' : ' '} ${option.letter.padEnd(1)}  ${option.label}`,
  );
  return [
    ...lines,
    ...options,
    '↑↓ move · enter or a letter picks · esc later',
  ];
}

/** From how many columns a focused option's preview is drawn beside the options, not below them. */
export const PREVIEW_BESIDE = 100;

/** How wide the rule above a preview drawn below the options is, at most. */
const PREVIEW_RULE = 40;

/**
 * A question with options as its modal draws it — spec 2026-09-29-orchestration-studio §2.5: the
 * run, a chip per question, the lead on the first, the question, its options with what is chosen
 * and where the arrows are, any words kept or being typed, the focused option's preview, and the
 * keys that mean something now.
 *
 * <p><b>The preview is what gives way first.</b> Everything else is the question and how to answer
 * it; a preview is a look at one option, and a terminal with too few rows for it loses it first.
 * Then the lead and question are cut, as a question in words is — at most {@link
 * DIALOG_QUESTION_LINES}, and fewer where `room` is short, then a line saying where the rest is —
 * and then each option's description is shortened to one row. The labels and the keys never are.
 *
 * @param viewable whether the surface has a viewer to show an install question's whole draft in:
 *     only then, and only on a question that carries one, do the keys offer `v`
 */
export function describeQuestionsDialog(
  run: Waiting,
  state: Answering,
  columns = DEFAULT_COLUMNS,
  room = Number.POSITIVE_INFINITY,
  viewable = false,
): string[] {
  const width = Math.max(NARROWEST_BODY, columns - DIALOG_EDGE);
  const heading = `${run.id} (${run.definition}) asks:`;
  if (state.kind === 'answered' || state.kind === 'left') {
    return [heading];
  }
  const { structure, at, focus, answers } = state;
  const question = structure.questions[at];
  const answer = answers[at];
  if (question === undefined || answer === undefined) {
    return [heading];
  }
  // THE MODEL'S WORDS ARE CLEANED AS THEY ARE DRAWN, never as they are read: a label is sent back
  // as the server wrote it, which is what it is matched against. A header, a label and a
  // description are one row's worth each, so a line break in one is a space.
  const drawn = (text: string): string => cleaned(text).replace(/\n/gu, ' ');
  const chips = structure.questions
    .map((each, index) => {
      const given = answers[index];
      const done = given !== undefined && answeredEach(given);
      // THE CURRENT CHIP KEEPS ITS SPACE UNANSWERED: the bracket is its own delimiter, so the
      // mark's slot stays a space until answered; a chip that is not current has no bracket to
      // hold that rhythm, so its mark's slot is empty until there is a mark to show.
      return index === at
        ? `[${drawn(each.header)}${done ? '✓' : ' '}]`
        : ` ${drawn(each.header)}${done ? '✓' : ''} `;
    })
    .join(' ');
  // NARROWED ONCE: a boolean computed from `state.kind` would not let `state.text` be read below.
  const typing = state.kind === 'typing' ? state : undefined;
  // ONE ROW EACH, up to the two thousand characters the server takes: words being typed show
  // their end, where the cursor is; words kept show their start.
  const worded = (head: string, text: string, typed: boolean): string => {
    const line = `${head}${text}${typed ? '▏' : ''}`;
    return line.length <= width
      ? line
      : typed
        ? `${head}…${text.slice(text.length - (width - head.length - 2))}▏`
        : `${line.slice(0, width - 1)}…`;
  };
  const words = [
    ...(typing?.what === 'other'
      ? [worded('  o. Other: ', typing.text, true)]
      : answer.other === undefined
        ? []
        : [worded('  o. Other: ', answer.other, false)]),
    ...(typing?.what === 'note'
      ? [worded('  n. Note: ', typing.text, true)]
      : answer.note === undefined
        ? []
        : [worded('  n. Note: ', answer.note, false)]),
  ];
  const missing =
    state.kind === 'choosing' && state.missing !== undefined
      ? [`Answer "${drawn(state.missing)}" first.`]
      : [];
  const last = at === structure.questions.length - 1;
  const keys =
    state.kind === 'typing'
      ? 'type · enter keeps it · esc drops it'
      : `↑↓ move · 1-${question.options.length} or ${question.multi ? 'space toggles' : 'enter picks'}` +
        ` · o other · n note · tab next${last ? ' · enter sends' : ''}` +
        `${viewable && structure.draft !== undefined ? ' · v view draft' : ''} · esc later`;
  const preview =
    state.kind === 'choosing' ? question.options[focus]?.preview : undefined;
  const beside = preview !== undefined && columns >= PREVIEW_BESIDE;
  const optionWidth = beside ? Math.floor((width - 3) / 2) : width;
  // WHO ELSE HAS IT, as a question in words says it: the caller model's answer may come first.
  const also = alsoHas(run);
  const bottom = [...words, ...missing, ...also, keys];
  // Rows, not lines: a line wider than the box is drawn on as many rows as it wraps to.
  const taken = (lines: readonly string[]): number =>
    lines.reduce((sum, line) => sum + wrapText(line, width).length, 0);
  // WHAT GIVES WAY, IN ORDER: the preview first (below); then the lead and question, cut to what
  // leaves each option a row, as a question in words is cut (`describeQuestionDialog`); then each
  // option's description, shortened to its one row. The labels, the words and the keys never do.
  const fixed =
    taken([heading, chips]) + question.options.length + taken(bottom);
  const said = [
    ...(at === 0 && structure.lead !== ''
      ? wrapText(cleaned(structure.lead), width)
      : []),
    ...wrapText(cleaned(question.question), width),
  ];
  const told =
    said.length <= Math.min(DIALOG_QUESTION_LINES, room - fixed)
      ? said
      : [
          ...said.slice(
            0,
            Math.max(1, Math.min(DIALOG_QUESTION_LINES, room - fixed - 1)),
          ),
          `… ${WATCH_COMMAND} ${run.id} for the rest`,
        ];
  const top = [heading, chips, ...told];
  const topRows = taken([heading, chips]) + told.length;
  const heads = question.options.map((option, index) => {
    const picked = answer.chosen.includes(index);
    const box = question.multi
      ? picked
        ? '[x]'
        : '[ ]'
      : picked
        ? '(•)'
        : '( )';
    const pointer = state.kind === 'choosing' && index === focus ? '›' : ' ';
    return `${pointer} ${index + 1}. ${box} ${drawn(option.label)} — `;
  });
  const whole = question.options.flatMap((option, index) =>
    wrapText(`${heads[index] ?? ''}${drawn(option.description)}`, optionWidth),
  );
  const options =
    topRows + whole.length + taken(bottom) <= room
      ? whole
      : question.options.flatMap((option, index) => {
          const head = heads[index] ?? '';
          const space = optionWidth - head.length;
          const description = drawn(option.description);
          const line = head + description;
          return line.length <= optionWidth
            ? [line]
            : // A LABEL TOO WIDE TO LEAVE ITS DESCRIPTION A COLUMN keeps itself and loses the rest.
              space < 2
              ? wrapText(head.slice(0, -' — '.length), optionWidth)
              : [`${head}${description.slice(0, space - 1)}…`];
        });
  if (preview === undefined) {
    return [...top, ...options, ...bottom];
  }
  const spare = room - topRows - options.length - taken(bottom);
  const previewLines = cleaned(preview).split('\n');
  if (beside) {
    const right = width - 3 - optionWidth;
    const rows = Math.max(
      options.length,
      Math.min(previewLines.length, Math.max(0, spare) + options.length),
    );
    const paired = Array.from({ length: rows }, (_, index) => {
      const left = (options[index] ?? '').padEnd(optionWidth);
      const shown = previewLines[index];
      return shown === undefined
        ? left.trimEnd()
        : `${left} │ ${shown.length > right ? `${shown.slice(0, right - 1)}…` : shown}`;
    });
    return [...top, ...paired, ...bottom];
  }
  // BELOW, AFTER A RULE, AND ONLY WHAT FITS: the rule and at least one line, or nothing. The
  // words, missing line and who else has it always come before the rule, and the keys after it
  // — the preview is what gives way, not what it sits above.
  if (spare < 2) {
    return [...top, ...options, ...bottom];
  }
  const shown = previewLines
    .slice(0, spare - 1)
    .map((line) =>
      line.length > width ? `${line.slice(0, width - 1)}…` : line,
    );
  return [
    ...top,
    ...options,
    ...words,
    ...missing,
    ...also,
    '─'.repeat(Math.min(width, PREVIEW_RULE)),
    ...shown,
    keys,
  ];
}

/**
 * A command approval from one of the person's runs as its dialog puts it: the run's agent, the
 * approval prompt's own question ({@link describeQuestion}) and keys ({@link describeKeys}) —
 * wrapped to the box on {@link describeQuestionDialog}'s rule, and cut to the `room` there is,
 * the keys never what gives way. A set's commands are its lines, one each. The plain surface is
 * printed the same lines, and reads the next one as the keys (`approval.strokesOf`) — an empty
 * one there is later, as esc is here.
 */
export function describeApprovalDialog(
  approval: Approval,
  state: Asking,
  plain: boolean,
  columns = DEFAULT_COLUMNS,
  room = Number.POSITIVE_INFINITY,
): string[] {
  const width = Math.max(NARROWEST_BODY, columns - DIALOG_EDGE);
  const heading = `${approval.id} (${approval.agent}) asks:`;
  const keys = describeKeys(state).map((line) =>
    plain && state.kind === 'choosing'
      ? line.replace(
          'esc leave it for later',
          'an empty line or esc leaves it for later',
        )
      : line,
  );
  // A line that fits is left as it is — the prefix line's spacing is part of what it says — and
  // one that does not is wrapped, its indent kept on every row.
  const fit = (line: string): string[] => {
    if (line.length <= width) {
      return [line];
    }
    const indent = /^ */u.exec(line)?.[0] ?? '';
    return wrapText(line.slice(indent.length), width - indent.length).map(
      (row) => indent + row,
    );
  };
  const rows = describeQuestion(approval).flatMap(fit);
  const rest = (more: number): string =>
    `… ${more} more line${more === 1 ? '' : 's'}` +
    (approval.commands === undefined
      ? ''
      : ' — one answer covers every command');
  const taken = (lines: readonly string[]): number =>
    lines.reduce((sum, line) => sum + wrapText(line, width).length, 0);
  const most = Math.max(
    1,
    Math.min(
      DIALOG_QUESTION_LINES,
      room - taken([heading, ...keys, rest(rows.length)]),
    ),
  );
  const shown =
    rows.length <= most
      ? rows
      : [...rows.slice(0, most), rest(rows.length - most)];
  return [heading, ...shown, ...keys.flatMap(fit)];
}

/** A command approval whose dialog is up was answered elsewhere, or withdrawn: taken away, and said. */
export function describeApprovalSettled(id: string): string {
  return (
    `${id} is no longer asked — answered elsewhere, or withdrawn because its run ended or` +
    ' what it asked about changed'
  );
}

/** A question somebody else answered first — said as its dialog is taken away. */
export function describeQuestionSettled(
  id: string,
  author: string,
  answer: string,
): string {
  return `${id}'s question was answered by ${author}: ${answer}`;
}

/** What a lone `r` prints on a surface that reads lines: the command to type, and where the answer goes. */
export function describeReplyWith(id: string): string {
  return `to reply, type: ${ANSWER_COMMAND} ${id} <your answer>`;
}

/*
 * THE RUNS PANEL. Spec 2026-09-28, the orchestration record §4: every live root with its stages
 * as a checklist, its live phases under it, what it is doing and its last few milestones.
 */

/** One stage's glyph: done, going, dropped, or not started. */
function glyphOf(status: string): string {
  return status === 'done'
    ? '✓'
    : status === 'in_progress'
      ? '●'
      : status === 'dropped'
        ? '–'
        : '○';
}

/** How many characters of children one phases line holds before the next line takes the rest. */
const CHILDREN_WIDTH = 60;

/**
 * The children under each stage that has any — `phases: utils ✓ readme ○` (spec 2026-09-29 §4).
 * Each child by its text, cut to 24 characters.
 *
 * <p><b>Wrapped, not cut.</b> The panel and the viewer draw each line as one row and cut its
 * end, and the end is exactly where the phases not yet run are — the measured run's missing
 * README was the last child (final review). So past {@link CHILDREN_WIDTH} the children go on to
 * a next line, indented under the first child: a panel line short enough for an 80-column
 * terminal, and every child shown.
 */
export function describePhaseChildren(stages: readonly RunStage[]): string[] {
  return describePhaseChildrenTinted(stages).map(plainOf);
}

/** A stage's glyph's colour: done, going, or anything else. */
function glyphRole(status: string): Role {
  return status === 'done'
    ? 'ok'
    : status === 'in_progress'
      ? 'waiting'
      : 'muted';
}

/**
 * {@link describePhaseChildren} in colour, as {@link describeChecklistTinted} draws a checklist:
 * the stage's name, each child's text and its glyph as done, going or not.
 */
export function describePhaseChildrenTinted(
  stages: readonly RunStage[],
): Tinted[] {
  return stages
    .filter((stage) => stage.stage !== undefined && stage.id !== undefined)
    .map((stage) => ({
      stage,
      children: stages.filter((each) => each.parent === stage.id),
    }))
    .filter(({ children }) => children.length > 0)
    .flatMap(({ stage, children }) => {
      const head = `${stage.stage ?? ''}: `;
      const rows: Tint[][] = [];
      let row: Tint[] = [];
      let width = 0;
      for (const child of children) {
        const name =
          child.text.length > 24 ? `${child.text.slice(0, 23)}…` : child.text;
        const one = [
          tint(name, 'text'),
          tint(` ${glyphOf(child.status)}`, glyphRole(child.status)),
        ];
        const length = name.length + 2;
        if (width !== 0 && width + 1 + length > CHILDREN_WIDTH) {
          rows.push(row);
          row = one;
          width = length;
        } else {
          row = width === 0 ? one : [...row, tint(' '), ...one];
          width = width === 0 ? length : width + 1 + length;
        }
      }
      rows.push(row);
      return rows.map((each, at): Tinted => [
        at === 0 ? tint(head, 'text') : tint(' '.repeat(head.length)),
        ...each,
      ]);
    });
}

/**
 * A run's stages as one line — `goal ✓ spec ✓ code ● review ○`. Only the rows that are a
 * definition's stages: a note the conductor added to its own list is not a stage.
 */
export function describeChecklist(stages: readonly RunStage[]): string {
  return stages
    .filter((each) => each.stage !== undefined)
    .map((each) => `${each.stage ?? ''} ${glyphOf(each.status)}`)
    .join(' ');
}

/** {@link describeChecklist} in colour: each stage's name, and its glyph as done, going or not. */
export function describeChecklistTinted(stages: readonly RunStage[]): Tinted {
  return stages
    .filter((each) => each.stage !== undefined)
    .flatMap((each, at) => [
      ...(at === 0 ? [] : [tint(' ')]),
      tint(each.stage ?? '', 'text'),
      tint(` ${glyphOf(each.status)}`, glyphRole(each.status)),
    ]);
}

/**
 * One clock per zone, made once: building an `Intl.DateTimeFormat` loads the zone's rules and
 * costs far more than formatting with one, and a viewer draws a row's clock on every redraw.
 * `null` is a zone `Intl` refused, so it is not asked again.
 */
const clocks = new Map<string, Intl.DateTimeFormat | null>();

function clockIn(zone: string): Intl.DateTimeFormat | null {
  let clock = clocks.get(zone);
  if (clock === undefined) {
    try {
      clock = new Intl.DateTimeFormat('en-GB', {
        timeZone: zone,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
      });
    } catch {
      clock = null;
    }
    clocks.set(zone, clock);
  }
  return clock;
}

/** A moment as `HH:MM:SS` in `zone`; as it arrived when it cannot be. */
export function describeClock(iso: string, zone: string): string {
  const moment = new Date(iso);
  if (Number.isNaN(moment.getTime())) {
    return iso;
  }
  return clockIn(zone)?.format(moment) ?? iso;
}

/** What a tool line reads as when the run it belongs to ended before its outcome was written. */
export const OUTCOME_LOST = 'unknown';

const ACTORS: readonly Role[] = ['actor1', 'actor2', 'actor3', 'actor4'];

/** A stable colour per agent name; the conductor is drawn plainly. */
export function actorRole(actor: string): Role {
  if (actor === 'conductor') {
    return 'actor';
  }
  let hash = 0;
  for (const point of actor) {
    hash = (hash * 31 + (point.codePointAt(0) ?? 0)) >>> 0;
  }
  return ACTORS[hash % ACTORS.length] ?? 'actor1';
}

/** Each milestone's mark, by `RecordKind`; a kind this build does not know is a dot. */
const MILESTONE_GLYPH: Readonly<Record<string, string>> = {
  run_started: '▶',
  run_ended: '■',
  stage_moved: '◆',
  phase_started: '┬',
  phase_ended: '┴',
  approval_asked: '?',
  question_asked: '?',
  approval_answered: '!',
  question_answered: '!',
  stalled: '…',
  call_failure: '⚠',
  delegated: '↳',
  delegate_returned: '↲',
  // The acceptance checker's work (V77): a concern, a WHY and its answer, a verdict.
  concern: '◇',
};

/** A row's text as one line, cleaned to draw: a tinted line never holds a newline, which the view counts on. */
const flat = (text: string): string => cleaned(text).replace(/\s*\n\s*/gu, ' ');

/**
 * One row of the record: its clock, who it is (the conductor plainly, each agent in a colour of
 * its own, padded to `actorWidth`), a rail per level below the root's conductor — a phase run
 * (not `root`) is one, a delegate another — then a tool line's call and its outcome, coloured:
 * `✓`, `✗` and what it said, `…` while the call runs, {@link OUTCOME_LOST} when its run ended
 * first (`lost`); or a milestone's mark, its text and its detail after a dash.
 */
export function describeRecorded(
  row: Recorded,
  zone: string,
  lost = false,
  actorWidth = 9,
  root?: string,
): Tinted {
  const clock = tint(`${describeClock(row.at, zone)}  `, 'time');
  const actor = tint(`${row.actor.padEnd(actorWidth)}  `, actorRole(row.actor));
  const depth =
    (root !== undefined && row.run !== root ? 1 : 0) +
    (row.actor === 'conductor' ? 0 : 1);
  const rails = depth === 0 ? [] : [tint('│ '.repeat(depth), 'rail')];
  // A phase run's line opens with its phase, as `RecordKeeper.labelled` writes it (spec
  // 2026-09-29 §4): drawn in its own colour after the glyph, so the glyphs stay in one column.
  // An actor's name in that place is a tool line's own `actor · `, not a phase.
  const labelled =
    root !== undefined && row.run !== root ? /^(\S+) · /u.exec(row.text) : null;
  const phase =
    labelled?.[1] !== undefined && labelled[1] !== row.actor
      ? labelled[1]
      : undefined;
  const text =
    phase === undefined ? row.text : row.text.slice(phase.length + 3);
  const label =
    phase === undefined ? [] : [tint(`${flat(phase)} · `, 'accent')];
  if (row.tool) {
    const said = flat(
      text.startsWith(`${row.actor} · `)
        ? text.slice(row.actor.length + 3)
        : text,
    );
    const standing =
      row.detail === undefined
        ? lost
          ? 'unknown'
          : 'waiting'
        : outcomeClass(row.detail);
    // Running is an outcome not yet written; a settled one that only waits (`asked`) is not.
    const glyph =
      row.detail === undefined && !lost
        ? tint('◌ ', 'waiting')
        : tint('● ', standing);
    const outcome =
      row.detail === undefined
        ? tint(lost ? OUTCOME_LOST : '…', lost ? 'unknown' : 'waiting')
        : standing === 'ok'
          ? tint('✓', 'ok')
          : // `ran`: the outcome is not known, so it is not drawn as a failure either.
            standing === 'unknown'
            ? tint(`· ${flat(row.detail)}`, 'unknown')
            : tint(`✗ ${flat(row.detail)}`, standing);
    return [
      clock,
      actor,
      ...rails,
      glyph,
      ...label,
      tint(said, 'text'),
      tint('  '),
      outcome,
    ];
  }
  // A check's result is the end of its text, as `RecordKeeper.checkRan` writes it: its detail is
  // empty, and the command before the result could hold either word.
  const glyph =
    row.kind === 'check_ran' && / passed$/u.test(text)
      ? tint('✓ ', 'ok')
      : row.kind === 'check_ran' &&
          / (?:failed \(exit [^)]*\)|timed out)$/u.test(text)
        ? tint('✗ ', 'fail')
        : tint(`${MILESTONE_GLYPH[row.kind] ?? '·'} `, 'milestone');
  return [
    clock,
    actor,
    ...rails,
    glyph,
    ...label,
    tint(flat(text), 'strong'),
    ...(row.detail === undefined
      ? []
      : [tint(` — ${flat(row.detail)}`, 'muted')]),
  ];
}

/** A run's heading: id, definition, how long it has been going (when `now` is given), its state
 *  unless running. */
function runHeading(run: Tree['run'], now: number | undefined): string {
  const started = Date.parse(run.createdAt);
  const elapsed =
    now === undefined || Number.isNaN(started)
      ? ''
      : `  ${describeElapsed(now - started)}`;
  return `${run.id}  ${run.definition}${elapsed}${run.state === 'running' ? '' : `  ${run.state}`}`;
}

/** The most lines the panel gives an asking tree's question, the line that says it was cut among them. */
export const PANEL_QUESTION_LINES = 6;

/** The panel's own padding, which every line of it is drawn inside. */
export const PANEL_PADDING = 1;

/** The narrowest a body is wrapped to, however little room a terminal leaves beside its indent. */
export const NARROWEST_BODY = 20;

/**
 * The question an asking tree waits on, whole — its body, or its line when the record kept none —
 * wrapped to `columns` under a `?`, and cut at {@link PANEL_QUESTION_LINES} with a last line that
 * says where the rest is. Nothing for a tree that is not asking.
 */
function questionLines(tree: Tree, columns: number): Tinted[] {
  const question = tree.question;
  // `record.ts`'s `treeAsking`, said again here: a value from there would close the import loop
  // the note above the imports keeps open.
  const asking =
    tree.run.state === 'asking' ||
    tree.phases.some((phase) => phase.run.state === 'asking');
  if (question === undefined || !asking) {
    return [];
  }
  const said = question.body ?? question.text.replace(/^asked: /u, '');
  const rows = wrapText(
    cleaned(said),
    Math.max(NARROWEST_BODY, columns - PANEL_PADDING - 4),
  );
  const shown =
    rows.length <= PANEL_QUESTION_LINES
      ? rows
      : rows.slice(0, PANEL_QUESTION_LINES - 1);
  return [
    ...shown.map((row, at): Tinted =>
      at === 0
        ? [tint('  '), tint('? ', 'milestone'), tint(row, 'text')]
        : [tint('    '), tint(row, 'text')],
    ),
    ...(shown.length === rows.length
      ? []
      : [
          [
            tint('    '),
            tint(`… ${WATCH_COMMAND} ${tree.run.id} for the rest`, 'muted'),
          ],
        ]),
  ];
}

/**
 * One live tree: its heading and checklist, each live phase indented the same way under it, the
 * question it waits on when it is asking — before the rest, so a panel cut at its foot keeps it —
 * what it is doing now (`activity`) and its last milestones. `now` undefined leaves the elapsed
 * times out, which is what a pipe prints; `columns` is how wide the question is wrapped to fit.
 */
export function describeTree(
  tree: Tree,
  now: number | undefined,
  zone: string,
  activity: boolean,
  columns = DEFAULT_COLUMNS,
): Tinted[] {
  const lines: Tinted[] = [[tint(runHeading(tree.run, now), 'strong')]];
  const stages = describeChecklistTinted(tree.stages);
  if (stages.length > 0) {
    lines.push([tint('  '), ...stages]);
  }
  for (const line of describePhaseChildrenTinted(tree.stages)) {
    lines.push([tint('  '), ...line]);
  }
  for (const phase of tree.phases) {
    lines.push([
      tint('  └ ', 'rail'),
      tint(runHeading(phase.run, now), 'text'),
    ]);
    const own = describeChecklistTinted(phase.stages);
    if (own.length > 0) {
      lines.push([tint('    '), ...own]);
    }
    for (const line of describePhaseChildrenTinted(phase.stages)) {
      lines.push([tint('    '), ...line]);
    }
  }
  lines.push(...questionLines(tree, columns));
  if (activity && tree.activity !== undefined) {
    // No clock, and no column to line the actor up with.
    lines.push([
      tint('  now: ', 'muted'),
      ...describeRecorded(tree.activity, zone, false, 0, tree.run.id).slice(1),
    ]);
  }
  for (const mark of tree.milestones) {
    lines.push([
      tint('  '),
      ...describeRecorded(mark, zone, false, 9, tree.run.id),
    ]);
  }
  return lines;
}

/** The runs panel: every live tree, and the same without what changes by the second; `columns`
 *  as wide as the surface says it is. */
export function describePanel(
  trees: readonly Tree[],
  now: number,
  zone: string,
  columns = DEFAULT_COLUMNS,
): Panel {
  return {
    lines: trees.flatMap((tree) =>
      describeTree(tree, now, zone, true, columns),
    ),
    settled: trees.flatMap((tree) =>
      describeTree(tree, undefined, zone, false, columns).map(plainOf),
    ),
  };
}

/** How the viewer is to be drawn, beyond what it holds. */
export interface WatchDrawn {
  /**
   * How many body lines the surface shows: only those are worded. The body is drawn from its
   * end, so these are the last of it; left out, all of it is — a pipe prints it whole.
   */
  readonly room?: number;
  /** Which open tool lines will never be told their outcome — `record.ts`'s `outcomeLost`. */
  readonly lost?: (row: Recorded) => boolean;
  /** How wide the surface is, which a row's body is wrapped to; {@link DEFAULT_COLUMNS} when absent. */
  readonly columns?: number;
}

/** Where a row's text starts in the viewer: the cursor's column, the clock, and the actors'. */
function watchIndent(actorWidth: number): number {
  return 1 + '00:00:00  '.length + actorWidth + 2;
}

/** The viewer's actor column: as wide as the widest actor it holds, so it does not shift as it scrolls. */
function actorColumn(watch: Watch): number {
  return watch.rows.reduce((most, row) => Math.max(most, row.actor.length), 9);
}

/**
 * A row's body as the viewer draws it under the row: wrapped to what `columns` leaves beside the
 * indent, and — `most` given — cut to that many lines, the last saying how many more there are,
 * so one row always fits the screen whole. None for a row without a body.
 */
function bodyRows(
  row: Recorded,
  actorWidth: number,
  columns: number,
  most?: number,
): Tint[] {
  if (row.body === undefined) {
    return [];
  }
  const rows = wrapText(
    cleaned(row.body),
    Math.max(NARROWEST_BODY, columns - PANEL_PADDING - watchIndent(actorWidth)),
  ).map((said) => tint(said, 'text'));
  if (most === undefined || rows.length <= most) {
    return rows;
  }
  return most <= 0
    ? []
    : [
        ...rows.slice(0, most - 1),
        tint(`… ${rows.length - most + 1} more lines`, 'muted'),
      ];
}

/**
 * How many lines each row of `watch` is drawn in when the viewer shows its rows in `room` lines
 * and is `columns` wide: one, and a row's body under it — cut so the row fits the room whole. What
 * {@link describeWatch} draws and what `record.ts`'s `watchKeyed` scrolls by, so the two agree.
 */
export function watchRowHeight(
  watch: Watch,
  room: number,
  columns = DEFAULT_COLUMNS,
): (row: Recorded) => number {
  const width = actorColumn(watch);
  return (row) =>
    1 + bodyRows(row, width, columns, Math.max(0, room - 1)).length;
}

/**
 * The viewer: the tree as the panel draws it (without its activity and milestones, which the
 * body shows whole), then the record up to where it is scrolled, oldest first — the way to
 * earlier rows, or the record's beginning, at the top — and the keys; and `said`, why the last
 * key's read was refused, when it was.
 *
 * <p><b>Only the rows on screen are worded</b> when `drawn.room` says how many that is. A viewer
 * holds up to thousands and is redrawn on every key, push and tick; wording every one — a clock
 * each — to show the last twenty was most of what a keypress cost.
 */
export function describeWatch(
  watch: Watch,
  tree: Tree | undefined,
  now: number,
  zone: string,
  said?: string,
  drawn: WatchDrawn = {},
): Viewed {
  const head =
    tree === undefined
      ? [[tint(watch.root, 'strong')]]
      : describeTree({ ...tree, milestones: [] }, now, zone, false);
  const end = watch.rows.length - watch.back;
  const room = drawn.room === undefined ? undefined : Math.max(0, drawn.room);
  const columns = drawn.columns ?? DEFAULT_COLUMNS;
  // The actors in one column, as wide as the widest held, so it does not shift as it scrolls.
  const width = actorColumn(watch);
  // A body is cut to leave its row room on the screen whole, beside the line above the rows.
  const most = room === undefined ? undefined : Math.max(0, room - 2);
  // AS MANY ROWS AS FIT, counted in lines: a row with a body is drawn in more than one.
  let start = end;
  let used = 0;
  if (room === undefined) {
    start = 0;
  } else {
    while (start > 0) {
      const row = watch.rows[start - 1];
      const tall =
        row === undefined ? 1 : 1 + bodyRows(row, width, columns, most).length;
      if (used + tall > room) {
        break;
      }
      start -= 1;
      used += tall;
    }
  }
  // The line above the rows is on screen only when every shown row fits below it.
  const whole = room === undefined || (start === 0 && used + 1 <= room);
  const shown = watch.rows.slice(start, end);
  const lost = drawn.lost ?? ((): boolean => false);
  // The cursor's row: the one it names, or the bottom one on screen.
  const cursor = watch.at ?? watch.rows[end - 1]?.ordinal;
  const indent = ' '.repeat(watchIndent(width));
  return {
    head,
    body: [
      ...(whole
        ? [
            [
              tint(
                watch.more
                  ? '— earlier: e —'
                  : '— the beginning of this record —',
                'muted',
              ),
            ],
          ]
        : []),
      // The cursor's row: what ↑↓, x and [ ] move, and what Enter opens.
      // Marked `▸` as well as by its background, so it shows under NO_COLOR; every other row
      // gets a space there, so the columns stay put.
      ...shown.flatMap((row): Tinted[] => {
        const line = describeRecorded(row, zone, lost(row), width, watch.root);
        // Its body under it, indented to where its text starts: the rest of what it said.
        const under = bodyRows(row, width, columns, most).map(
          (said): Tinted => [tint(indent), said],
        );
        return [
          row.ordinal === cursor
            ? selectedLine([tint('▸', 'selected'), ...line])
            : [tint(' '), ...line],
          ...under,
        ];
      }),
    ],
    foot:
      `${watch.tools ? 'milestones and tool activity' : 'milestones'} · ↑↓ PgUp PgDn scroll · ⏎ open` +
      ` · x X failure · [ ] milestone · e earlier · t ${watch.tools ? 'milestones only' : 'tool activity too'}` +
      ' · f follow · esc back',
    ...(said === undefined ? {} : { said }),
  };
}

/** Enter in `/watch` on a row whose run this client has no conversation for. */
export function describeNothingToOpen(): string {
  return 'this row names no run whose log can be opened here';
}

/** `/watch` with nothing named, on an account with no runs at all. */
export function describeNothingToWatch(): string {
  return (
    `nothing to watch — this account has no orchestration runs; ${ORCHESTRATIONS_COMMAND}` +
    ' lists what can be started'
  );
}

// ─── Tool lines: spec 2026-09-29 §4 ────────────────────────────────────────────────

export type Density = 'compact' | 'full' | 'hidden';
export const DENSITIES: readonly Density[] = ['compact', 'full', 'hidden'];

export function nextDensity(density: Density): Density {
  return (
    DENSITIES[(DENSITIES.indexOf(density) + 1) % DENSITIES.length] ?? 'compact'
  );
}

export function describeDensity(density: Density): string {
  switch (density) {
    case 'compact':
      return 'tool lines: compact — one line a call, a failure shows its output · ctrl-t for more';
    case 'full':
      return 'tool lines: full — every call shows its output · ctrl-t for less';
    case 'hidden':
      return 'tool lines: hidden — one line per turn from here on · ctrl-t for more';
  }
}

// `TRAJECTORY_COMMAND` is declared beside `LOG_COMMAND` above, so that `COMMANDS`'
// array literal — evaluated at module load — never reads it before it exists.

/** How wide the tool name's column is. */
const TOOL_COLUMN = 10;

export function describeTook(millis: number): string {
  if (millis < 1000) {
    return `${Math.max(0, Math.round(millis))}ms`;
  }
  if (millis < 60_000) {
    return `${(millis / 1000).toFixed(1)}s`;
  }
  return describeElapsed(millis);
}

const GLYPH: Readonly<Record<ReturnType<typeof callStanding>, string>> = {
  ok: '●',
  fail: '●',
  waiting: '●',
  unknown: '·',
};

/** A `file_edit` that replaced text, read from arguments the page did not cut; else undefined. */
export function editOf(
  call: Call,
): { readonly old: string; readonly new: string } | undefined {
  if (call.tool !== 'file_edit' || call.argumentsCut) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(call.arguments) as {
      old?: unknown;
      new?: unknown;
    };
    return typeof parsed.old === 'string' && typeof parsed.new === 'string'
      ? { old: parsed.old, new: parsed.new }
      : undefined;
  } catch {
    return undefined;
  }
}

const lineCount = (text: string): number =>
  text === '' ? 0 : text.replace(/\n+$/u, '').split('\n').length;

/** How big a result was: lines when it came whole, characters when the page cut it; an edit's lines changed. */
function sizeOf(call: Call): string {
  const result = call.result;
  if (call.opened !== undefined) {
    return `↳ ${call.opened.agent}`;
  }
  const edit = editOf(call);
  if (edit !== undefined) {
    return `+${lineCount(edit.new)} −${lineCount(edit.old)}`;
  }
  if (result === undefined || result.text === undefined) {
    return '';
  }
  if (result.cut === true) {
    const length = result.length ?? lengthOf(result.text);
    return length >= 1000
      ? `${(length / 1000).toFixed(1)}k chars`
      : `${length} chars`;
  }
  const lines = cleaned(result.text).replace(/\n+$/u, '').split('\n').length;
  return `${lines} ${lines === 1 ? 'line' : 'lines'}`;
}

function markOf(call: Call): Tint {
  const standing = callStanding(call);
  const outcome = call.result?.outcome;
  switch (standing) {
    case 'ok':
      return tint('✓', 'ok');
    case 'fail':
      return tint(`✗ ${flat(outcome ?? 'failed')}`, 'fail');
    case 'waiting':
      return tint(`… ${flat(outcome ?? '')}`.trimEnd(), 'waiting');
    case 'unknown':
      return tint(
        call.unanswered
          ? '· never answered'
          : outcome === undefined || outcome === ''
            ? '·'
            : `· ${flat(outcome)}`,
        'unknown',
      );
  }
}

/**
 * A line with `left` fixed, `middle` shortened to fit, and right-hand parts pinned to the right
 * edge. `attempts` is tried in order — the first whose parts leave `middle` at least 8 columns
 * is drawn — so a caller lists its right side from fullest to barest; the last attempt is
 * always drawn. It never wraps. The explorer's rows are laid out by this too.
 */
export function pinned(
  left: Tinted,
  middle: string,
  attempts: readonly (readonly Tint[])[],
  width: number,
  middleRole: Role = 'salient',
): Tinted {
  const leftWidth = lengthOf(plainOf(left));
  for (const [at, tail] of attempts.entries()) {
    const tailWidth = lengthOf(plainOf(tail));
    const room = width - leftWidth - tailWidth - 1;
    if (room >= 8 || at === attempts.length - 1) {
      const shown = shortenMiddle(middle, Math.max(0, room));
      const gap = Math.max(1, width - leftWidth - lengthOf(shown) - tailWidth);
      return fitTinted(
        [...left, tint(shown, middleRole), tint(' '.repeat(gap)), ...tail],
        width,
      );
    }
  }
  return fitTinted(left, width);
}

/** One call's first line: its mark, its tool in a column, its salient argument, the rest pinned right. */
function callHead(
  call: Call,
  glyph: Tint,
  attempts: readonly (readonly Tint[])[],
  width: number,
): Tinted {
  const left = [
    tint('  '),
    glyph,
    tint(' '),
    tint(call.tool.padEnd(TOOL_COLUMN), 'tool'),
    tint(' '),
  ];
  return pinned(left, flat(call.salient ?? ''), attempts, width);
}

/** The gap between the outcome and the time. */
const RIGHT_GAP = '   ';

export function describeCallLines(
  call: Call,
  density: Density,
  width: number,
): Tinted[] {
  if (density === 'hidden') {
    return [];
  }
  const standing = callStanding(call);
  const size = sizeOf(call);
  const took = call.result?.tookMillis;
  const mark = markOf(call);
  const sized =
    standing === 'ok' && size !== ''
      ? tint(`${mark.text} ${size}`, 'ok')
      : mark;
  const time =
    took === undefined
      ? []
      : [tint(RIGHT_GAP), tint(describeTook(took), 'time')];
  const attempts: Tint[][] = [[sized, ...time], [sized], [mark]];
  const head = callHead(call, tint(GLYPH[standing], standing), attempts, width);
  const rail = (line: string, role: Role = 'muted'): Tinted =>
    fitTinted(
      [tint('  │ ', standing === 'fail' ? 'fail' : 'rail'), tint(line, role)],
      width,
    );
  const edit =
    density === 'full' && standing !== 'fail' ? editOf(call) : undefined;
  if (edit !== undefined) {
    const removed = cleaned(edit.old)
      .split('\n')
      .slice(0, 6)
      .map((line) => rail(`- ${line}`, 'diffRemoved'));
    const added = cleaned(edit.new)
      .split('\n')
      .slice(0, 6)
      .map((line) => rail(`+ ${line}`, 'diffAdded'));
    return [head, ...removed, ...added];
  }
  const showBody = density === 'full' || standing === 'fail';
  const text = call.result?.text;
  if (!showBody || text === undefined || text === '') {
    return [head];
  }
  // A failure's excerpt is the line that says why and the one after it, not the head a `run`
  // opens with (its status line and stdout banner); full density gives each side one more.
  const each = density === 'full' ? 3 : 2;
  const cut =
    standing === 'fail'
      ? failureCut(text, each, each)
      : cutLines(text, each, each);
  const more =
    cut.hidden === 0 && call.result?.cut !== true
      ? []
      : [
          fitTinted(
            [
              tint('  │ ', 'rail'),
              tint(`… +${cut.hidden} lines · ${TRAJECTORY_COMMAND}`, 'muted'),
            ],
            width,
          ),
        ];
  return [
    head,
    ...cut.head.map((line) => rail(line)),
    ...cut.tail.map((line) => rail(line)),
    ...more,
  ];
}

export function describePendingLine(
  call: Call,
  elapsedMillis: number,
  width: number,
): Tinted {
  return callHead(
    { ...call, pending: true },
    tint('◌', 'waiting'),
    [[tint(describeTook(elapsedMillis), 'time')]],
    width,
  );
}

/** The calls running beyond the ones drawn pending, counted on one line under them. */
export function describeMoreRunning(count: number): Tinted {
  return [tint(`  +${count} running`, 'muted')];
}

export function describeReasoningLines(
  step: Step,
  density: Density,
  width: number,
): Tinted[] {
  const text = cleaned(step.entry.text ?? '').trim();
  if (density === 'hidden' || text === '') {
    return [];
  }
  const lines = text.split('\n').filter((line) => line.trim() !== '');
  const shown = density === 'full' ? lines.slice(0, 6) : lines.slice(0, 1);
  return shown.map((line, at) =>
    fitTinted(
      [tint(at === 0 ? '  ✻ ' : '    ', 'reasoning'), tint(line, 'reasoning')],
      width,
    ),
  );
}

export function describeTraceTurn(turn: Turn): Tinted {
  const failed = turn.failed === 0 ? '' : ` · ${turn.failed} failed`;
  return [
    tint('  ⚙ ', 'muted'),
    tint(
      `${turn.calls} ${turn.calls === 1 ? 'call' : 'calls'}${failed} · ${describeTook(turn.toolMillis)}`,
      turn.failed === 0 ? 'muted' : 'fail',
    ),
  ];
}

/**
 * A turn's times, appended to the cost note `describeCost` begins — which already counts the
 * model calls, so this says only how long they took.
 */
export function describeTurnTimes(turn: Turn): string {
  const failed = turn.failed === 0 ? '' : ` · ${turn.failed} failed`;
  return (
    `model ${describeTook(turn.modelMillis)}` +
    ` · ${turn.calls} ${turn.calls === 1 ? 'tool' : 'tools'} ${describeTook(turn.toolMillis)}${failed}`
  );
}

// ─── The explorer: spec 2026-09-29 §5-6 ────────────────────────────────────────────────

export interface ExploreSize {
  readonly rows: number;
  readonly columns: number;
}

export interface ExploreExtras {
  /** Child conversations whose logs were read, by id: their step counts. */
  readonly children: ReadonlyMap<
    string,
    { readonly steps: number; readonly more: boolean }
  >;
  readonly zone: string;
}

export const WIDE_COLUMNS = 120;

/** Header, rules and keys: what the list does not get. */
const CHROME = 4;
const STRIP = 2;

/** Whether the strip is drawn: only when it leaves the list at least 8 rows. */
const stripped = (size: ExploreSize): boolean =>
  size.rows - 1 - CHROME - STRIP >= 8;

export function explorerListRoom(size: ExploreSize): number {
  return Math.max(1, size.rows - 1 - CHROME - (stripped(size) ? STRIP : 0) - 1);
}

const BADGE: Readonly<
  Record<string, { readonly word: string; readonly role: Role }>
> = {
  person: { word: 'USER', role: 'badgeUser' },
  reasoning: { word: 'THINK', role: 'badgeThink' },
  answer: { word: 'ASST', role: 'badgeAnswer' },
  call: { word: 'TOOL', role: 'badgeTool' },
  fold: { word: 'FOLD', role: 'badgeFold' },
  note: { word: 'NOTE', role: 'badgeNote' },
  hook: { word: 'HOOK', role: 'badgeHook' },
  plan: { word: 'PLAN', role: 'badgePlan' },
  fail: { word: 'FAIL', role: 'badgeFail' },
  turn: { word: 'TURN', role: 'accent' },
};

/** A log row's badge, by its entry kind: the trajectory's badges, so the two views read alike. */
const ENTRY_BADGE: Readonly<Record<string, string>> = {
  utterance: 'person',
  answer: 'answer',
  tool_result: 'call',
  thinking: 'reasoning',
  summary: 'fold',
  hook: 'hook',
  plan: 'plan',
  attempt_failed: 'fail',
  refusal: 'fail',
};

function badgeOf(row: Row): { readonly word: string; readonly role: Role } {
  if (row.kind === 'turn') {
    return BADGE['turn'] as { word: string; role: Role };
  }
  if (row.kind === 'entry') {
    return BADGE[ENTRY_BADGE[row.entry.kind] ?? 'note'] as {
      word: string;
      role: Role;
    };
  }
  const step = row.step;
  if (step.kind === 'note') {
    const kind = step.entry.kind;
    return (
      kind === 'hook'
        ? BADGE['hook']
        : kind === 'plan'
          ? BADGE['plan']
          : isFailure(row)
            ? BADGE['fail']
            : BADGE['note']
    ) as { word: string; role: Role };
  }
  return BADGE[step.kind] as { word: string; role: Role };
}

const firstLine = (text: string | undefined): string =>
  cleaned(text ?? '')
    .split('\n')
    .find((line) => line.trim() !== '')
    ?.trim() ?? '';

function rowLine(
  row: Row,
  first: boolean,
  width: number,
  extras: ExploreExtras,
): Tinted {
  const badge = badgeOf(row);
  const gutter = tint(
    first ? String(turnOfRow(row)).padStart(4) : '    ',
    'muted',
  );
  const left: Tint[] = [
    gutter,
    tint(' '),
    tint(badge.word.padEnd(5), badge.role),
    tint(' '),
  ];
  if (row.kind === 'turn') {
    const turn = row.turn;
    const failed = turn.failed === 0 ? '' : ` · ${turn.failed} failed`;
    return pinned(
      left,
      `${turn.steps.length} steps · ${turn.calls} calls${failed}`,
      [
        [
          tint(
            `model ${describeTook(turn.modelMillis)} · tools ${describeTook(turn.toolMillis)}`,
            'time',
          ),
        ],
        [],
      ],
      width,
      turn.failed === 0 ? 'text' : 'fail',
    );
  }
  if (row.kind === 'entry') {
    const entry = row.entry;
    const state =
      entry.supersededBy !== undefined
        ? `superseded by #${entry.supersededBy}`
        : entry.ejectedAt !== undefined
          ? 'ejected'
          : entry.handle !== undefined
            ? '⧉ handle'
            : entry.state;
    const muted =
      entry.supersededBy !== undefined || entry.ejectedAt !== undefined;
    const head = [
      tint(`#${entry.ordinal}`.padStart(5), 'muted'),
      tint(' '),
      tint(`t${entry.turnOrdinal}`.padEnd(4), 'muted'),
      tint(
        entry.kind.padEnd(15),
        muted ? 'muted' : isFailure(row) ? 'fail' : 'tool',
      ),
      tint(state.padEnd(18), muted ? 'muted' : 'text'),
      tint(
        (entry.tookMillis === undefined
          ? ''
          : describeTook(entry.tookMillis)
        ).padEnd(8),
        'time',
      ),
      tint(
        (entry.wireModel ?? '').padEnd(
          entry.wireModel === undefined ? 0 : entry.wireModel.length + 2,
        ),
        'muted',
      ),
    ];
    return fitTinted(
      [...head, tint(firstLine(entry.text), muted ? 'muted' : 'text')],
      width,
    );
  }
  const step = row.step;
  if (step.kind === 'call') {
    const standing = callStanding(step);
    const mark = markOf(step);
    const child =
      step.opened === undefined
        ? undefined
        : extras.children.get(step.opened.conversation);
    const size =
      step.opened !== undefined && child !== undefined
        ? `↳ ${step.opened.agent} · ${child.steps}${child.more ? '+' : ''} steps`
        : sizeOf(step);
    const sized =
      standing === 'ok' && size !== ''
        ? tint(`${mark.text} ${size}`, 'ok')
        : mark;
    const took = step.result?.tookMillis;
    const time =
      took === undefined ? [] : [tint('   '), tint(describeTook(took), 'time')];
    const output = step.result?.text;
    const preview =
      standing === 'fail' && output !== undefined
        ? firstLine(failureCut(output, 1, 0).head.join('\n'))
        : firstLine(output);
    const summary = [preview, flat(step.salient ?? '')]
      .filter(Boolean)
      .join(' · ');
    return pinned(
      [...left, tint(step.tool.padEnd(TOOL_COLUMN), 'tool'), tint(' ')],
      summary,
      [[sized, ...time], [sized], [mark]],
      width,
      standing === 'fail' ? 'fail' : 'salient',
    );
  }
  const role: Role =
    step.kind === 'reasoning'
      ? 'reasoning'
      : step.kind === 'fold' || step.kind === 'note'
        ? isFailure(row)
          ? 'fail'
          : 'muted'
        : 'text';
  const text =
    step.kind === 'note'
      ? `${step.entry.kind}: ${firstLine(step.entry.text)}`
      : firstLine(step.entry.text);
  return fitTinted([...left, tint(text, role)], width);
}

/** Hard-wraps `text`, cleaned to draw, to `width`, keeping its own line breaks. */
function wrapped(text: string, width: number): string[] {
  const out: string[] = [];
  for (const line of cleaned(text).replace(/\n+$/u, '').split('\n')) {
    const points = [...Array.from(line)];
    if (points.length === 0) {
      out.push('');
    }
    for (let at = 0; at < points.length; at += Math.max(1, width)) {
      out.push(points.slice(at, at + Math.max(1, width)).join(''));
    }
  }
  return out;
}

const PANE_WORD: Readonly<Record<Pane, string>> = {
  payload: 'Payload',
  result: 'Result',
  text: 'Text',
  timing: 'Timing',
};

/** The cut, said as a cut, wrapped to the pane: its two clauses wrap on their own, so a narrow
 *  pane breaks between them rather than through a word such as "result_read". */
function cutNote(shown: number, length: number, width: number): Tinted[] {
  const head = `shows ${shown.toLocaleString('en-GB')} of ${length.toLocaleString('en-GB')} characters`;
  const tail = 'the rest is reachable only by the agent, through result_read';
  return [...wrapped(head, width), ...wrapped(tail, width)].map((line) => [
    tint(line, 'muted'),
  ]);
}

function field(name: string, value: string, role: Role = 'text'): Tinted {
  return [tint(name.padEnd(11), 'muted'), tint(value, role)];
}

function inspectorLines(
  row: Row | undefined,
  pane: Pane,
  width: number,
  extras: ExploreExtras,
): Tinted[] {
  if (row === undefined) {
    return [];
  }
  const badge = badgeOf(row);
  const ordinal =
    row.kind === 'step'
      ? row.step.ordinal
      : row.kind === 'entry'
        ? row.entry.ordinal
        : undefined;
  const head: Tinted = [
    tint(badge.word, badge.role),
    tint(
      ` · turn ${turnOfRow(row)}${ordinal === undefined ? '' : ` · #${ordinal}`}`,
      'muted',
    ),
  ];
  const tabs: Tinted = panesOf(row).flatMap((each, at) => [
    ...(at === 0 ? [] : [tint('  ')]),
    each === pane
      ? tint(`[${PANE_WORD[each]}]`, 'selected')
      : tint(PANE_WORD[each], 'muted'),
  ]);
  const body: Tinted[] = [];
  const text = (value: string, role: Role = 'text'): void => {
    body.push(...wrapped(value, width).map((line) => [tint(line, role)]));
  };
  const step = row.kind === 'step' ? row.step : undefined;
  const entry =
    row.kind === 'step'
      ? row.step.entry
      : row.kind === 'entry'
        ? row.entry
        : undefined;
  /** A call's arguments, pretty-printed when they parse, and said as cut when they were. */
  const argumentsOf = (asked: {
    readonly arguments: string;
    readonly cut: boolean;
    readonly length: number;
  }): void => {
    let parsed: unknown;
    try {
      parsed = asked.cut ? undefined : JSON.parse(asked.arguments);
    } catch {
      parsed = undefined;
    }
    text(
      parsed === undefined ? asked.arguments : JSON.stringify(parsed, null, 2),
    );
    if (asked.cut) {
      body.push([
        tint(
          `shows ${[...Array.from(asked.arguments)].length.toLocaleString('en-GB')} of ` +
            `${asked.length.toLocaleString('en-GB')} characters of the arguments`,
          'muted',
        ),
      ]);
    }
  };
  if (step?.kind === 'call' && pane === 'payload') {
    const edit = editOf(step);
    if (edit !== undefined) {
      body.push(
        ...cleaned(edit.old)
          .split('\n')
          .map((line) => [tint(`- ${line}`, 'diffRemoved')]),
      );
      body.push(
        ...cleaned(edit.new)
          .split('\n')
          .map((line) => [tint(`+ ${line}`, 'diffAdded')]),
      );
    } else {
      argumentsOf({
        arguments: step.arguments,
        cut: step.argumentsCut,
        length: step.argumentsLength,
      });
    }
  } else if (step?.kind === 'call' && pane === 'result') {
    if (step.pending) {
      text('still running', 'waiting');
    } else if (step.unanswered) {
      text('never answered — the turn went on without it', 'unknown');
    } else {
      if (step.result?.outcome !== undefined) {
        body.push(field('outcome', step.result.outcome, callStanding(step)));
      }
      const output = step.result?.text ?? '';
      text(output.trim() === '' ? 'no tool output recorded' : output);
      if (step.result?.cut === true) {
        body.push(
          ...cutNote(
            [...Array.from(step.result.text ?? '')].length,
            step.result.length ?? 0,
            width,
          ),
        );
      }
    }
  } else if (pane === 'timing') {
    const result = step?.kind === 'call' ? step.result : undefined;
    const at = result?.recordedAt ?? entry?.recordedAt;
    if (at !== undefined)
      body.push(field('recorded', describeClock(at, extras.zone)));
    const took = step?.kind === 'call' ? result?.tookMillis : entry?.tookMillis;
    if (took !== undefined) body.push(field('took', describeTook(took)));
    if (entry?.wireModel !== undefined)
      body.push(field('model', describeProvenance(entry)));
    if (entry?.completion !== undefined)
      body.push(field('completion', entry.completion));
    if (result?.outcome !== undefined)
      body.push(
        field(
          'outcome',
          result.outcome,
          step?.kind === 'call' && callStanding(step) === 'fail'
            ? 'fail'
            : 'ok',
        ),
      );
    if (result?.handle !== undefined)
      body.push(
        field(
          'handle',
          `${result.handle} · stored; only the agent reads it back`,
        ),
      );
    if (step?.kind === 'call') {
      for (const hook of step.hooks)
        body.push(field('hook', firstLine(hook.text), 'badgeHook'));
      if (step.opened !== undefined) {
        const child = extras.children.get(step.opened.conversation);
        body.push(
          field(
            'opened',
            `${step.opened.agent}'s log${child === undefined ? '' : ` · ${child.steps}${child.more ? '+' : ''} steps`} · → to open`,
            'accent',
          ),
        );
      }
    }
    if (row.kind === 'turn') {
      body.push(
        field(
          'model',
          `${row.turn.modelCalls} calls · ${describeTook(row.turn.modelMillis)}`,
        ),
      );
      body.push(
        field(
          'tools',
          `${row.turn.calls} calls · ${describeTook(row.turn.toolMillis)}`,
        ),
      );
      if (row.turn.failed > 0)
        body.push(field('failed', String(row.turn.failed), 'fail'));
    }
  } else if (row.kind === 'turn') {
    for (const each of row.turn.steps) {
      body.push(
        rowLine({ kind: 'step', step: each }, false, width, extras).slice(3),
      );
    }
  } else {
    const said = entry?.text ?? '';
    const calls = row.kind === 'entry' ? (entry?.calls ?? []) : [];
    if (said.trim() !== '' || calls.length === 0) {
      text(said);
    }
    if (entry?.cut === true) {
      body.push(
        ...cutNote([...Array.from(said)].length, entry.length ?? 0, width),
      );
    }
    // AN ANSWER THAT ONLY ASKED FOR TOOLS SAYS WHAT IT ASKED, not an empty pane: each call's
    // tool and salient argument, then its arguments.
    for (const [at, asked] of calls.entries()) {
      if (at > 0 || said.trim() !== '') {
        body.push([]);
      }
      body.push([
        tint(asked.name, 'tool'),
        tint('  '),
        tint(flat(asked.salient ?? ''), 'salient'),
      ]);
      argumentsOf(asked);
    }
  }
  return [
    head,
    tabs,
    [tint('─'.repeat(Math.max(0, width)), 'rail')],
    ...body.map((line) => fitTinted(line, width)),
  ];
}

function headerOf(ex: Explorer, width: number): Tinted {
  const level = current(ex);
  const steps = stepsOf(level.entries);
  const turns = turnsOf(steps);
  const sum = (of: (turn: (typeof turns)[number]) => number): number =>
    turns.reduce((total, turn) => total + of(turn), 0);
  const failed = sum((turn) => turn.failed);
  const crumbs = ex.levels.map((each) => each.label).join(' › ');
  const stats =
    ` · ${turns.length} ${turns.length === 1 ? 'turn' : 'turns'} · ${steps.length} steps` +
    ` · model ${describeTook(sum((turn) => turn.modelMillis))} · tools ${describeTook(sum((turn) => turn.toolMillis))}` +
    (failed === 0 ? '' : ` · ${failed} failed`) +
    (ex.view === 'log' ? ' · log' : '');
  const state = level.follow
    ? tint('● following', 'ok')
    : tint('○ paused', 'muted');
  return pinned(
    [tint(' '), tint(crumbs, 'strong')],
    stats,
    [[state]],
    width,
    'muted',
  );
}

const NOTE_WORDS: Readonly<Record<NonNullable<Explorer['note']>, string>> = {
  noFailureBelow: 'no failure below this row',
  noFailureAbove: 'no failure above this row — g loads earlier',
  noMatch: 'nothing matches that in what is loaded',
  notADoor:
    'nothing opens from this row — → opens the log of an agent a call handed work to',
  noLink:
    'this call handed work to an agent, but which log it opened was not recorded — it ran before the server kept that link',
  atTop: 'this is the conversation you started in — q closes',
};

function footOf(ex: Explorer, wide: boolean): string {
  if (ex.search?.typing === true) {
    return ` search: ${ex.search.query}▏  enter keeps · esc cancels`;
  }
  if (ex.focus === 'inspector' && !wide) {
    return ' ↑↓ scroll  tab next pane  esc back to the list  q close';
  }
  return ` ↑↓ move  ⏎ inspect  → open  ← back  [ ] turn  x failure  / search  t fold  v ${ex.view === 'log' ? 'trajectory' : 'log'}  q close`;
}

function saidOf(ex: Explorer): string | undefined {
  if (ex.note !== undefined) {
    return ` ${NOTE_WORDS[ex.note]}`;
  }
  if (ex.search !== undefined && !ex.search.typing) {
    const level = current(ex);
    const count = matchesOf(ex).length;
    const total = level.total ?? level.entries.length;
    return (
      ` ${count} ${count === 1 ? 'match' : 'matches'} in ${level.entries.length} of ${total.toLocaleString('en-GB')} entries` +
      (level.more ? ' · g loads earlier' : '')
    );
  }
  return undefined;
}

/** How the explorer's columns fall at `size`: the list's width, and the inspector's beside or instead of it. */
function layoutOf(size: ExploreSize): {
  width: number;
  wide: boolean;
  listWidth: number;
  inspectorWidth: number;
} {
  const width = Math.max(1, size.columns - 1);
  const wide = size.columns >= WIDE_COLUMNS;
  const listWidth = wide ? Math.floor(width * 0.55) : width;
  return {
    width,
    wide,
    listWidth,
    inspectorWidth: Math.max(0, wide ? width - listWidth - 3 : width - 1),
  };
}

/**
 * How far the inspector scrolls before its last line reaches the bottom of the list's room: the
 * inspector's head (badge, panes, rule) stays put, and the rest scrolls under it.
 */
export function explorerScrollLimit(
  ex: Explorer,
  size: ExploreSize,
  extras: ExploreExtras,
): number {
  const lines = inspectorLines(
    rowsOf(ex)[cursorOf(ex)],
    ex.pane,
    layoutOf(size).inspectorWidth,
    extras,
  );
  return Math.max(0, lines.length - explorerListRoom(size));
}

export function describeExplorer(
  ex: Explorer,
  size: ExploreSize,
  extras: ExploreExtras,
): Tinted[] {
  const { width, wide, listWidth, inspectorWidth } = layoutOf(size);
  const room = explorerListRoom(size);
  const rows = rowsOf(ex);
  const at = cursorOf(ex);
  const top = Math.max(
    0,
    Math.min(at - Math.floor(room / 2), rows.length - room),
  );
  const shown = rows.slice(top, top + room);
  const list = shown.map((row, index) => {
    const absolute = top + index;
    const previous = rows[absolute - 1];
    const first =
      previous === undefined || turnOfRow(previous) !== turnOfRow(row);
    const line = rowLine(row, first, Math.max(0, listWidth - 1), extras);
    const marked: Tinted = [
      tint(absolute === at ? '▸' : ' ', 'selected'),
      ...line,
    ];
    return absolute === at
      ? selectedLine(padTinted(marked, listWidth))
      : padTinted(marked, listWidth);
  });
  const inspecting = !wide && ex.focus === 'inspector';
  const inspectorAll =
    wide || inspecting
      ? inspectorLines(rows[at], ex.pane, inspectorWidth, extras)
      : [];
  // Scrolled no further than its last line, whatever the state says: a resize can shorten it.
  const scroll = Math.max(0, Math.min(ex.scroll, inspectorAll.length - room));
  const inspector = inspectorAll
    .slice(0, 3)
    .concat(inspectorAll.slice(3 + scroll));
  const body: Tinted[] = [];
  for (let line = 0; line < room; line += 1) {
    if (wide) {
      body.push([
        ...(list[line] ?? padTinted([], listWidth)),
        tint(' │ ', 'rail'),
        ...(inspector[line] ?? []),
      ]);
    } else if (inspecting) {
      body.push([tint(' '), ...(inspector[line] ?? [])]);
    } else if (list[line] !== undefined) {
      body.push(list[line] as Tinted);
    }
  }
  const steps =
    stripped(size) && ex.view === 'trajectory' && !ex.folded
      ? stepsOf(current(ex).entries)
      : undefined;
  const strip =
    steps === undefined
      ? []
      : (() => {
          const drawn = stripOf(
            steps,
            Math.max(0, width - 7),
            rows[at]?.kind === 'step'
              ? steps.findIndex(
                  (step) => stepKey(step) === rowKey(rows[at] as Row),
                )
              : undefined,
          );
          return [
            [tint(' model ', 'muted'), ...drawn.model],
            [tint(' tools ', 'muted'), ...drawn.tools],
          ];
        })();
  const rule = (join: string): Tinted => [
    tint(
      wide
        ? `${'─'.repeat(listWidth + 1)}${join}${'─'.repeat(Math.max(0, width - listWidth - 2))}`
        : '─'.repeat(Math.max(0, width)),
      'rail',
    ),
  ];
  const said = saidOf(ex);
  return [
    headerOf(ex, width),
    ...strip,
    rule('┬'),
    ...body,
    rule('┴'),
    ...(said === undefined ? [] : [[tint(said, 'muted')]]),
    [tint(footOf(ex, wide), 'muted')],
  ]
    .map((line) => fitTinted(line, width))
    .slice(0, Math.max(1, size.rows - 1));
}

export function describeExplorerListing(ex: Explorer, width: number): string[] {
  const rows = rowsOf(ex);
  const extras: ExploreExtras = { children: new Map(), zone: 'UTC' };
  return rows.flatMap((row, at) => {
    const previous = rows[at - 1];
    const first =
      previous === undefined || turnOfRow(previous) !== turnOfRow(row);
    const head = plainOf(rowLine(row, first, width, extras)).trimEnd();
    if (row.kind !== 'step' || row.step.kind !== 'call') return [head];
    return [
      head,
      ...inspectorLines(row, 'result', Math.max(1, width - 4), extras)
        .slice(3)
        .map((line) => `    ${plainOf(line)}`),
    ];
  });
}

export function describeNoTrajectory(): string {
  return 'no conversation to explore yet — say something first, or name one: /trajectory <conversation>';
}

/** What `/trajectory` and `/log` say when the log they were to open was refused. */
export function describeTrajectoryUnreadable(conversation: string): string {
  return `the log of ${conversation} could not be read — it may not exist, or not be yours`;
}

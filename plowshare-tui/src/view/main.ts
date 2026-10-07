import {
  FileStores,
  manageFileStores,
  promptFileStore,
  localStorePath,
} from 'plowshare-client-node/filestores';
import {
  Connections,
  manageConnections,
  resolveConnection,
  userConfigDirectory,
} from 'plowshare-client-node/connections';
import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './background.ts';
import { parseCommand as parseScheduleFileCommand } from 'plowshare-client-ts/operations/commands';
import { UsageClient, runUsage } from '../logic/usage.ts';
import { serverCommand } from '../logic/session.ts';
import { listingConversations } from 'plowshare-client-ts/operations/session';
import {
  AUTHORING,
  authoringReady,
  authoringRequest,
} from 'plowshare-client-ts/operations/authoring';
import { InformationClient } from '../logic/information.ts';
import { preparePersonalStore } from 'plowshare-client-node/personal';
import {
  Credentials,
  credentialDirectory,
} from 'plowshare-client-node/credentials';
import { inspectBoard } from './board.ts';
import { retrieve, describeRetrieval } from '../logic/retrieval.ts';
import { randomUUID } from 'node:crypto';
import { realpath, stat } from 'node:fs/promises';
import { homedir, hostname } from 'node:os';
import { isAbsolute, join, relative, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { isSea } from 'node:sea';

import {
  changePassword,
  MustChangePassword,
  openFiles,
  openSocket,
  PasswordRefused,
  refresh,
  SignInRefused,
  signIn,
} from 'plowshare-client-ts/binding/auth';
import type { Claim, SocketDoor } from 'plowshare-client-ts/binding/auth';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type {
  Operation,
  Payloads,
} from 'plowshare-client-ts/operations/direct';
import {
  decodeRequest,
  decodeReply,
} from 'plowshare-client-ts/operations/schema';
import type { Socket } from 'plowshare-client-ts/binding/connection';
import { jobStatusOf } from 'plowshare-client-ts/binding/job-view';
import { JobLifecycle } from 'plowshare-client-ts/jobs';
import type { Ticket } from 'plowshare-client-ts/jobs';
import {
  resultOf,
  VALIDATED_OPERATIONS,
  dispatch,
  parseDirect,
} from 'plowshare-client-ts/operations/direct';
import {
  askingAbout,
  pressingOn,
  settledAsking,
  strokesOf,
} from '../logic/approval.ts';
import type { Asking, Stroke } from '../logic/approval.ts';
import {
  agents,
  answeredOf,
  answeredYes,
  answering,
  appendedOf,
  approvalsOf,
  AWAITING,
  handing,
  listingApprovals,
  listingAsked,
  revokedOf,
  cancelling,
  catchingUp,
  checking,
  completable,
  continued,
  continuing,
  backPageOf,
  conversations,
  savingSchedule,
  entriesOf,
  finished,
  firingEvent,
  firingsOf,
  changedOf,
  followed,
  following,
  followingLog,
  forgettingSchedule,
  forgettingTrigger,
  inboxPageOf,
  INBOX_LIST,
  listeningTo,
  listingAgents,
  listingProjects,
  listingSchedules,
  listingTriggers,
  loadOf,
  loggedFrom,
  logThrough,
  logTotal,
  measuring,
  OK,
  opened,
  opening,
  pausingSchedule,
  pausingTrigger,
  projects,
  proposalOf,
  reached,
  readingAfter,
  readingEarlier,
  readingInbox,
  readingLogBefore,
  readingLogTail,
  readingSchedule,
  readingTail,
  readingTrace,
  replaying,
  schedulesOf,
  streamed,
  streaming,
  taking,
  triggersOf,
  typed,
  unreadOf,
  whoAnswers,
  definitionsOf,
  listingDefinitions,
  listingRuns,
  runStatusOf,
  runsOf,
  settledRun,
  listingAsking,
  questionOf,
  readingRun,
  rechecks,
  structureOf,
  waitingAfter,
  approvalsWaiting,
  listingMyApprovals,
  answeringApproval,
  listingStalled,
  stalledWaiting,
  answeringRun,
  cancellingRun,
  resumingRun,
  dialogKindOf,
} from '../logic/session.ts';
import {
  LIVE_STATES,
  MILESTONE_KINDS,
  PANEL_MILESTONES,
  RECORD_TAIL,
  TOOL_CALL,
  followFrom,
  kindsOf,
  latestQuestion,
  listingLive,
  liveRoots,
  liveRunsOf,
  oldestOf,
  outcomeLost,
  panelReads,
  readingQuestion,
  readingRecordAfter,
  readingRecordEarlier,
  readingRecordTail,
  readingToolLine,
  recordPageOf,
  recordedOf,
  settledToRead,
  treeAsking,
  watchEarlier,
  watchGrew,
  watchKeyed,
  watchCursor,
  watchSettled,
  watching as watchingFrom,
  followingEnd,
} from '../logic/record.ts';
import type {
  Phase as RunPhase,
  RecordedPush,
  Tree,
  Viewed,
  Watch,
} from '../logic/record.ts';
import { plainOf } from '../logic/tints.ts';
import { DEFAULT_COLUMNS } from '../logic/wrap.ts';
import type {
  Agent,
  Answer,
  Answered,
  Appended,
  Approval,
  Ask,
  BackPage,
  CapKey,
  Conversation,
  Definition,
  Ended,
  Entry,
  Load,
  Logged,
  Pace,
  Progress,
  Run,
  Draft,
  Schedule,
  Structure,
  Trigger,
  Turn,
  Waiting,
} from '../logic/session.ts';
import {
  answeringAbout,
  answeringOn,
  draftAsked,
  settledAnswering,
} from '../logic/questions.ts';
import type { Answering } from '../logic/questions.ts';
import {
  describeDraftView,
  draftViewAbout,
  draftViewOn,
} from '../logic/draftview.ts';
import {
  describeAnswered,
  describeApprovals,
  describeDecided,
  describeKeys,
  describeLeftOpen,
  describeNoApprovals,
  describeQuestion,
  describeRevoked,
  describeAgent,
  describeAgents,
  describeBots,
  describeCancelling,
  describeChoice,
  describeBeginning,
  describeContinuing,
  describeEarlier,
  describeEarlierHint,
  describeNoEarlier,
  describeNotYetShown,
  describeOrchestration,
  describeOrchestrations,
  describeRun,
  describeRuns,
  describeNowAsking,
  describeWaitingRuns,
  describeApprovalAnswered,
  describeConversations,
  describeAlways,
  describeAlwaysNeedsAProject,
  describeCaps,
  describeCapSet,
  describeCapNeedsAProject,
  describeCapOutOfRange,
  describeCost,
  describeCut,
  describeDrop,
  describeEnding,
  describeFailure,
  describeHarness,
  describeHelp,
  describeCommandCatalog,
  describeSettled,
  describeInbox,
  describeLeaving,
  describeNew,
  describeNoTrajectory,
  describeNothingRunning,
  describeTrajectoryUnreadable,
  describeProgress,
  describeProjects,
  describeSeam,
  describeSilence,
  describeSpokenIn,
  describeUnknownCommand,
  describeUnnamedRun,
  describeUsage,
  describeAlreadyIn,
  describeAlreadyMarked,
  describeFilesElsewhere,
  describeMoved,
  describeDiagnosing,
  describeMovedTo,
  describeNoDiagnosis,
  describeNoDirectory,
  describeNoSuchProject,
  describeNotADirectory,
  describeOffered,
  describeRefusedMove,
  describeRootLost,
  describeRootRefused,
  describeRooted,
  describeUnmarked,
  describeUnrooted,
  PASSWORD_AGAIN,
  PASSWORD_ASK,
  PASSWORD_CHANGED,
  PASSWORD_DIFFERED,
  PASSWORD_MUST_CHANGE,
  describeStanding,
  describeUnreachable,
  refusal,
  THEME_COMMAND,
  describeFired,
  describeFirings,
  describeForgot,
  describeNoSchedule,
  describeNotSaved,
  describePaused,
  describeProposal,
  describeSaved,
  describeSchedules,
  describePanel,
  describeNothingToOpen,
  describeNothingToWatch,
  describeWatch,
  watchRowHeight,
  DENSITIES,
  describeCallLines,
  describeDensity,
  describeReasoningLines,
  describeTraceTurn,
  describeTurnTimes,
  nextDensity,
  describeCapDialog,
  describeCapSettled,
  describeRecorded,
  describeQuestionDialog,
  describeQuestionSettled,
  describeReplyWith,
  ANSWER_COMMAND,
  describeApprovalDialog,
  describeApprovalSettled,
  describeQuestionsDialog,
  dialogOptionsOf,
  describePickingDialog,
} from '../logic/wording.ts';
import type { Density } from '../logic/wording.ts';
import { traceGrew, traceOpened, turnOf } from '../logic/trace.ts';
import type { Trace } from '../logic/trace.ts';
import type { Call, Turn as TracedTurn } from '../logic/trajectory.ts';
import { parse } from '../logic/markdown.ts';
import type { Block } from '../logic/markdown.ts';
import { appended, entered, phaseAfter, traced } from '../logic/screen.ts';
import type { Live, Moved, Phase } from '../logic/screen.ts';
import type { Voice } from '../logic/screen.ts';
import { enforcing } from './files/enforcer.ts';
import { ASK, capRange, OPEN } from 'plowshare-client-ts/binding/environment';
import {
  readCapsFile,
  saveCapsFile,
  localModePreview,
  saveSettingsFile,
} from 'plowshare-client-node/settings';
import {
  alwaysAutoContinue,
  capsOf,
  DIALOG_KEYS,
  dialogKeyOfLine,
  pickingAbout,
  pickingOn,
  readingCaps,
} from '../logic/caps.ts';
import type { DialogKey, DialogOption, Picking } from '../logic/caps.ts';
import {
  discover,
  resolveMarked,
  clientProject,
  mark,
  markedName,
  namedAfter,
  thisMachine,
  within,
} from './files/marker.ts';
import { rooter, sameClaim } from './files/rooter.ts';
import { plain } from './plain.ts';
import { terminal } from './terminal/mounting.ts';
import type { Surface } from './surface.ts';
import type { Depth } from './look.ts';
import { probeTerminal } from './theme/probe.ts';
import { theming } from './theme/theming.ts';
import { readSettings, settingsPath, writeSettings } from './theme/loader.ts';
import type { Theming } from './theme/theming.ts';
import { noticingWrites, syncer, type Syncer } from './sync/syncer.ts';
import type { SyncAction } from '../logic/union.ts';
import { toListing } from './scrollback.ts';
import { explore } from './exploring.ts';
import type { Exploring, Reads } from './exploring.ts';

/**
 * <b>The composition: the first time every layer of this module runs at
 * once.</b>
 *
 * <p>Sign in, open, prompt, send, render, repeat. Six files that had only ever
 * been exercised one at a time are assembled here — the auth that gets onto the
 * wire, the connection that correlates answers, the session model that folds a
 * turn out of frames, the grammar, and the emitter next door — and
 * `composition.test.ts` drives the whole of it over a real socket against a
 * server scripted to answer like Plowshare.
 *
 * <h2>What this file owns, which is less than it looks</h2>
 *
 * <p>Three things and no more: <b>the order</b>, <b>the mutable now</b>, and
 * <b>the one wait</b>. Everything else is somebody else's:
 *
 * <ul>
 * <li><b>Every sentence is `logic/wording.ts`'s.</b> Not one line of English
 *     about a run is written here, because the same line will be written in a
 *     window one day and two copies drift.
 * <li><b>Every frame is `logic/session.ts`'s.</b> This file never writes a
 *     dotted type or a payload key; it hands `ask.type` and `ask.payload` to
 *     the connection, which is the whole of the dependency inversion.
 * <li><b>The refresh-before-ticket ordering is `auth.ts`'s.</b> The spec calls
 *     it the single thing a TUI author gets wrong; {@link openSocket} owns it
 *     so that nothing here has an opportunity to.
 * </ul>
 *
 * <h2>A turn is a value, and the only mutable thing is which value is current</h2>
 *
 * <p>`session.ts` folds a turn with pure functions, so the state here is one
 * `let`. It has to be a `let` and not a local, because {@link onPush} and the
 * turn's own `await` see the same turn from two directions — the socket's
 * message handler and the promise continuation — and the second must fold into
 * whatever the first has already produced. Hence `latest`: after an `await`,
 * the turn worth folding into is the one the pushes have been updating, never
 * the one this function was holding when it suspended.
 *
 * <p><b>That is where the interleaving contract is actually spent.</b>
 * `connection.ask` resolves in a microtask, so frames the server wrote before
 * the answer are handled while `turn.job` is still unknown — and
 * `session.ts`'s holding area is what keeps them. Nothing here arranges for
 * that; the delivery order of a real socket does, and the test scripts a server
 * that sends one turn's events before its `ACCEPTED` and the next turn's after.
 *
 * <h2>The one wait, and why it cannot miss</h2>
 *
 * <p>After the `ACCEPTED`, the run is followed by events, and the turn is over
 * when the `ended` event arrives. So there is a promise that {@link onPush}
 * settles. The check that guards it — `finished(turn)` — and the promise's own
 * executor are in one synchronous block, and nothing can run between them, so
 * an ending that arrived <i>before</i> the answer (held, then replayed by
 * `answering`) is seen by the check and never waited for. A version that
 * awaited first and checked second would hang on exactly the fast runs.
 *
 * <p><b>And it has a second way to settle, which a whole-plan review found it
 * missing.</b> The wait above is settled by an `ended` event and by nothing
 * else, so a socket that goes away mid-run settles it never: `connection.ts`
 * fails every outstanding <i>ask</i> on a close, and during those minutes there
 * is no ask outstanding to fail. Against a real server that is not the edge
 * case it sounds like — a proxy's idle cutoff, a laptop sleeping, a deploy, a
 * restart — and every one of them left this client sitting at a live prompt
 * with a dead socket and nothing to say. So the promise takes a `reject` as
 * well, `onClose` is what calls it, and {@link Dropped} is what carries it out
 * to a single sentence. `composition.test.ts` scripts the server to hang up
 * after the `ACCEPTED` and before the ending, and that case was a five-second
 * vitest timeout — the hang itself — before this existed.
 */
export interface Talking {
  /** Production reconnect retries; omitted by embedded callers that own recovery. */
  readonly reconnect?: { readonly attempts: number; readonly delayMs: number };

  /**
   * Where the server is and how to reach it.
   *
   * <b>Both listeners are this file's</b> and neither is a caller's to
   * supply: `onPush` is how a turn is followed and `onClose` is how the
   * following is failed, and they are two halves of the one wait that
   * {@link converse} owns.
   */
  readonly door: Omit<SocketDoor, 'onPush' | 'onClose'>;

  readonly handle: string;
  readonly password: string;
  readonly credentials?: Credentials;

  /**
   * Who answers, when a person said.
   *
   * <b>Optional, and that is the change task 3 made.</b> It was required, so
   * the ordinary way to use this system — talking to somebody — was the thing
   * you had to configure. With nobody named, `agent.list` is asked at sign-in
   * and `whoAnswers` reads the default off what this deployment serves; see
   * its javadoc for what happens when there are several bots or none.
   *
   * <p><b>Still not defaulted here</b>, which is `session.ts`'s reason and
   * survives the change intact: inventing a name in this file would be this
   * client deciding who answers. What is new is that `agent.list` can answer
   * the question, which it could not when nothing asked it.
   */
  readonly agent?: string;

  /** The home the conversation is held in, when one is named. */
  readonly project?: string;

  /**
   * The directory the person started in, when this client may root it.
   *
   * <p>Absent, nothing is discovered and `/here`, `/project` and `/cd` say there
   * is no directory — which is every composition case written before this, and
   * a client somebody embeds without a disk.
   */
  readonly here?: string;

  /** What this machine calls itself in a claim. `run` fills it from `PLOWSHARE_MACHINE` or the host. */
  readonly machine?: string;

  /**
   * Whether to ask the server for the tokens as a model produces them.
   *
   * <p><b>On by default here, and that is a decision about the audience.</b>
   * The streaming design makes deltas opt-in precisely so the MCP adapter and
   * the web console do not get a firehose neither asked for — but this client
   * is a person watching a terminal, and watching is what it is for. A model
   * that thinks for ninety seconds behind a still screen is the problem the
   * whole feature exists to solve.
   *
   * <p>Turned off with `PLOWSHARE_TOKENS=0`, for a slow link or a scripted
   * run that would rather not carry them.
   *
   * <p><b>Absent means off, and `run` is what turns it on.</b> That is the
   * way round the protocol already works — a listener that did not ask gets
   * nothing — and it keeps the default of this FUNCTION the quiet one, so
   * that a caller which never heard of deltas sends the frames it always
   * sent. The terminal is the thing with an opinion about watching a model
   * think, and the terminal is `run`.
   */
  readonly tokens?: boolean;

  /**
   * How long without a heartbeat before this client says it has lost sight.
   *
   * <h3>Why it is a parameter and not a constant</h3>
   *
   * <p><b>So a test can be about the behaviour rather than about waiting.</b>
   * The real value is a minute; a composition case that had to sit through
   * one would be a case nobody runs.
   *
   * <h3>Why a minute</h3>
   *
   * <p>The server beats every twenty seconds (`plowshare.jobs.heartbeat`), so
   * this is three missed beats. Two would cry wolf on one dropped frame or
   * one busy moment; four is most of two minutes of a person staring at a
   * terminal wondering.
   *
   * <p><b>And it is a guess about the server, which is the weakness.</b> An
   * operator who configures a slower beat makes this client cry wolf. The
   * repair is for the server to advertise its interval — nothing does — so
   * until then this only ever complains after it has heard at least one beat:
   * a client pointed at a server too old to send any says nothing at all,
   * rather than accusing every run of having died.
   */
  readonly silence?: number;

  /**
   * How often the runs waiting on a person are checked for, in milliseconds.
   *
   * <p><b>A parameter for {@link silence}'s reason</b>: a composition case
   * about the check should not sit through the real interval. Fifteen seconds
   * is the real one — a question nobody sees for a quarter of a minute has
   * lost nothing, and a push that a run started asking checks at once anyway;
   * the timer is only for what a push did not say, such as a run answered
   * from another client.
   *
   * <p><b>Absent means no background check, and `run` is what turns it on</b>,
   * on {@link tokens}' rule: a caller that never heard of it sends the frames
   * it always sent. A bare `/answer` still checks, because somebody asked.
   */
  readonly waitingEvery?: number;

  readonly surface: Surface;

  /**
   * The themes `/theme` lists and switches between. Absent where there is
   * nothing to draw in colour, and `/theme` says so.
   */
  readonly theming?: Theming;

  /**
   * Where this client's settings are kept (`tui.json`): the tool-line density Ctrl-T chose is
   * read from it at the start and saved to it on each press. Absent, the density starts compact
   * and a choice lasts only as long as the session — a test, or a client embedded without a disk.
   */
  readonly settings?: string;

  /**
   * The IANA zone a sentence like "at 9am" is read in, and fire times are
   * shown in. Absent, it is this terminal's own — see {@link terminalZone};
   * a test names one so its fire times do not depend on the machine.
   */
  readonly zone?: string;
}

/** This terminal's IANA zone, or UTC when the runtime cannot say. */
export function terminalZone(): string {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return typeof zone === 'string' && zone !== '' ? zone : 'UTC';
}

/**
 * <b>The opener, in the six lines `auth.ts`'s `Opening` contract needs.</b>
 *
 * <p>It resolves when the socket is <i>open</i>, and that is the whole of why
 * the type is a promise. `Socket` has no `open` subscription — `connection.ts`
 * names the omission and its reason — so waiting for readiness belongs to
 * whoever constructed the thing, and a `send` before the handshake completes
 * throws `InvalidStateError` on a real `WebSocket` while passing silently
 * against any fake. `real-socket.test.ts` wrote these lines out against a real
 * server in task 5 so that this file could copy something measured;
 * `composition.test.ts` injects <b>this</b> one rather than a copy, so the
 * opener that ships is the opener that is proven.
 *
 * <p><b>Named `openingSocket` and not `opening`, which is not fussiness.</b>
 * `session.ts` exports an `opening` — the frame that opens a conversation — and
 * this file imports it. Written as `opening`, the two collided: the suite still
 * passed, because the bundler this module is run through resolved the clash one
 * way and happened to pick the one that works, and <b>`tsc -b --force` is what
 * refused it</b> (TS2440, plus TS2339 on the wrong call's result). A duplicate
 * declaration is a syntax error in ESM and a coin toss in a transform, which is
 * as clean a demonstration as this module will get of why the plan says the
 * type check is not optional and not `--noEmit`.
 */
export function openingSocket(url: string): Promise<Socket> {
  return new Promise((open, fail) => {
    const socket = new WebSocket(url);
    socket.addEventListener('open', () => open(socket));
    // THE ADDRESS WITHOUT ITS QUERY. The query carries a ticket and, for the
    // file channel, a root — and this message is put on a person's screen.
    socket.addEventListener('error', () =>
      fail(new Error(`could not open ${url.split('?')[0] ?? url}`)),
    );
  });
}

/**
 * Whether this stream should be written escape sequences.
 *
 * <p>Here rather than in `scrollback.ts`, so that the emitter stays a pure
 * function of its arguments and this is the only file in `view/` that reads a
 * `process`. `NO_COLOR` is honoured because it is the convention a person
 * reaches for when a colour is unreadable to them, and a client that ignored it
 * would be ignoring an accessibility request.
 */
export function coloured(stream: { readonly isTTY?: boolean }): boolean {
  return stream.isTTY === true && (process.env['NO_COLOR'] ?? '') === '';
}

/**
 * How many colours the terminal takes.
 *
 * <p>The same signals Ink's own colour library reads, so the transcript this
 * client encodes and the chrome Ink encodes agree: 24-bit where `COLORTERM`
 * says so or the terminal is one known to take it, the xterm-256 cube
 * everywhere else.
 */
export function depthOf(
  env: Readonly<Record<string, string | undefined>>,
): Depth {
  const colourterm = (env['COLORTERM'] ?? '').toLowerCase();
  const program = env['TERM_PROGRAM'] ?? '';
  const truecolor =
    colourterm === 'truecolor' ||
    colourterm === '24bit' ||
    [
      'iTerm.app',
      'WezTerm',
      'ghostty',
      'vscode',
      'Hyper',
      'Tabby',
      'rio',
    ].includes(program) ||
    env['FORCE_COLOR'] === '3';
  return truecolor ? 'truecolor' : '256';
}

/**
 * <b>The socket went away while a run was being followed.</b>
 *
 * <p>Its own class, and private to this file, for the reason {@link
 * SignInRefused} is its own class: it is the one thing that can come out of the
 * follow-wait that is not a run's outcome, and {@link converse} has to be able
 * to tell it from a programming mistake. A rejection carrying a bare `Error`
 * would have to be recognised by its message, which is the arrangement that
 * quietly stops working the day somebody improves the wording.
 *
 * <p>The sentence is `wording.ts`'s and not this class's — see
 * {@link describeDrop} — so that the terminal and the window one day say the
 * same thing about the same event.
 */
class ResponseUnreadable extends Error {}

class Dropped extends Error {
  constructor() {
    super(describeDrop());
    this.name = 'Dropped';
  }
}

/**
 * <b>Somebody pressed Ctrl-C a second time and is leaving now.</b>
 *
 * <p>{@link Dropped}'s shape, for {@link Dropped}'s reason and one more. The
 * reason it shares: it comes out of the follow-wait and is not a run's outcome,
 * so {@link converse} has to be able to tell it from a programming mistake
 * without matching on a message.
 *
 * <p><b>The reason it is a rejection at all is the promise it rejects.</b> A
 * turn's wait is settled by an `ended` event and is minutes long — that is what
 * a cancel is for — so a second press that merely stopped taking input would
 * leave a person sitting there while this client waited for the run they had
 * just asked to stop. The plan is explicit: <i>do not make them wait on the
 * network to leave.</i>
 *
 * @param asked whether a `job.cancel` really went out, which decides which of
 *     {@link describeLeaving}'s two sentences is true
 */
class Quit extends Error {
  constructor(asked: boolean) {
    super(describeLeaving(asked));
    this.name = 'Quit';
  }
}

/**
 * One conversation, from sign-in to the end of input.
 *
 * <p>Returns when the person ends the input, when the conversation could not be
 * opened, or when the socket dropped mid-run. Everything it has to say, it says
 * through {@link Talking.surface} — a dropped connection included, which is why
 * {@link Dropped} is caught here rather than allowed out to {@link run}.
 */
export async function converse(talking: Talking): Promise<void> {
  const surface = talking.surface;

  /**
   * Entry keys: a counter, not a clock.
   *
   * Two entries can land in the same millisecond — a refusal and the line
   * explaining it, most obviously — and a key that collides drops a row.
   */
  let keyed = 0;
  /** One entry on the transcript, from text this file has as a string. */
  const show = (voice: Voice, text: string, note?: string): void => {
    keyed += 1;
    surface.show(entered(keyed, voice, parse(text), note));
  };
  /** One entry from a body that arrived already parsed. */
  const showBody = (
    voice: Voice,
    body: readonly Block[],
    note?: string,
  ): void => {
    keyed += 1;
    surface.show(entered(keyed, voice, body, note));
  };
  /** When the run in flight started, for whoever draws an elapsed time. */
  let started = 0;
  /** What the model is producing right now, or nothing seen yet this turn. */
  let live: Live | undefined;
  /** The last lifecycle line, kept so a beat with nothing to say cannot erase it. */
  let reported: string | undefined;
  /** What the model is doing, as the events and deltas of this turn show it. */
  let phase: Phase | undefined;
  /**
   * What the tracer has drawn of the turn in flight, or nothing between turns and for a turn in
   * a conversation other than the one on screen — spec 2026-09-29 §4.
   */
  let trace: Trace | undefined;
  /** The calls asked for and not yet answered, as the tracer last read them. */
  let calls: readonly Call[] = [];
  /**
   * The person's turn the tracer is drawing: the lowest turn among the rows it read past where
   * it opened. Rows of a later turn — a harness turn landing just after `ended` — are not its.
   */
  let traceTurn: number | undefined;
  /** When each call still running was first seen pending, by id, for its own elapsed time. */
  const firstSeen = new Map<string, number>();
  /** How tool lines are drawn: what Ctrl-T last chose, kept in the settings across sessions. */
  const saved =
    talking.settings === undefined
      ? undefined
      : readSettings(talking.settings).density;
  let density: Density =
    saved !== undefined && DENSITIES.includes(saved) ? saved : 'compact';
  /** Trace reads, one at a time, each from where the last one left off. */
  let tracing: Promise<void> = Promise.resolve();
  /** The conversation a trace read is queued for and has not begun reading, or nothing. */
  let traceQueued: string | undefined;
  /** This turn's working state, whole: one place builds it, so no field is forgotten. */
  const workingOn = (job: string) => ({
    job,
    since: started,
    ...(live === undefined ? {} : { live }),
    ...(reported === undefined ? {} : { said: reported }),
    ...(phase === undefined ? {} : { phase }),
    ...(calls.length === 0
      ? {}
      : {
          calls,
          callsSince: Object.fromEntries(
            calls.map((call) => [call.id, firstSeen.get(call.id) ?? started]),
          ),
        }),
  });
  /** How long without a beat before saying so. See {@link Talking.silence}. */
  const silence = talking.silence ?? 60_000;
  /** Whether a heartbeat has ever arrived, which is what makes the clock honest. */
  let beaten = false;
  /** The watchdog, or nothing between turns. */
  let watching: ReturnType<typeof setTimeout> | undefined;
  /** Whether the silence has already been reported for this turn. */
  let mourned = false;

  const stopWatching = (): void => {
    if (watching !== undefined) {
      clearTimeout(watching);
      watching = undefined;
    }
  };

  /**
   * Waits out the silence, and says so if it passes.
   *
   * <p><b>The clock is here and not in `logic/`</b>, which is the layering
   * rather than an accident: a timer is a runtime, `logic/` has none, and
   * `src/neutrality.test.ts` refuses `setTimeout` in that directory. What
   * `logic/` owns is the sentence and the reading of the event; what this
   * owns is the waiting.
   */
  const watch = (): void => {
    stopWatching();
    watching = setTimeout(() => {
      // Only after a beat has actually been heard -- see
      // `Talking.silence`. And only once per turn: a run that has gone
      // quiet is one fact, not one fact every minute.
      if (!beaten || mourned || turn === undefined) {
        return;
      }
      mourned = true;
      show('trouble', describeSilence(Math.round(silence / 1000)));
    }, silence);
    // A watchdog must not be the reason a terminal will not exit.
    watching.unref?.();
  };

  /**
   * Takes a new password, twice, and sets it. The new one, or nothing.
   *
   * <p><b>Twice, because a typo here locks somebody out of their own
   * server.</b> There is no password recovery in this system — that is
   * structural rather than an omission — so the cost of one mistyped
   * character is an account nobody can reach.
   *
   * <p>Returns nothing when the input ended, which is Ctrl-D or a surface
   * that cannot take a password at all. `plain` is the second: it is what a
   * pipe gets, readline echoes, and there is no terminal to mute — so it
   * answers `undefined` rather than putting a credential on a screen.
   */
  const changing = async (access: string): Promise<string | undefined> => {
    show('client', PASSWORD_MUST_CHANGE);
    for (;;) {
      show('client', PASSWORD_ASK);
      const first = await surface.asked(true);
      if (first === undefined) {
        return undefined;
      }
      show('client', PASSWORD_AGAIN);
      const again = await surface.asked(true);
      if (again === undefined) {
        return undefined;
      }
      if (first !== again) {
        show('trouble', PASSWORD_DIFFERED);
        continue;
      }
      try {
        await changePassword(talking.door, access, talking.password, first);
        return first;
      } catch (refused) {
        if (refused instanceof PasswordRefused) {
          // Not a failure of the attempt -- the server named a rule
          // and this says which -- so the loop goes round rather than
          // ending the session.
          show('trouble', refused.message);
          continue;
        }
        throw refused;
      }
    }
  };

  /** The turn in flight, or nothing between turns. See the header. */
  let turn: Turn | undefined;
  const jobs = new JobLifecycle();
  let activeTicket: Ticket | undefined;
  /** Settles the one wait, once the run has ended. */
  let ending: (() => void) | undefined;
  /** Fails the one wait, when the socket goes away under it. */
  let dropped: ((trouble: Error) => void) | undefined;

  /** The turn the pushes have been updating, or `fallback` between turns. */
  const latest = (fallback: Turn): Turn => turn ?? fallback;

  /**
   * One event, as a <i>state</i> rather than as a line.
   *
   * <p><b>This is the fix the whole port exists for.</b> These lines used to
   * go through `say`, which put `0 steps, 1 model call` in the transcript
   * between two things a person had read — where it read as something the bot
   * had said. A run's progress is a state that gets replaced.
   *
   * <p>Reported even when `describeProgress` has nothing to say, because the
   * <i>existence</i> of a run is itself worth drawing: a surface with a
   * spinner starts it here, before the first lifecycle line arrives.
   */
  const report = (seen: Progress): void => {
    const job = turn?.job;
    if (job === undefined) {
      return;
    }
    // ANYTHING ABOUT THIS RUN RESETS THE CLOCK, not only a heartbeat. A
    // server sending tool calls every few seconds is plainly alive, and a
    // client that insisted on the beat specifically would announce silence
    // in the middle of a run that was talking to it.
    if (seen.kind === 'alive') {
      beaten = true;
    }
    watch();
    const line = describeProgress(seen);
    if (line !== undefined) {
      // KEPT, SO A BEAT WITH NOTHING TO COUNT DOES NOT ERASE IT. The
      // region is replaced rather than appended to, so an event carrying
      // no sentence would otherwise blank whatever the run last said.
      reported = line;
    }
    const moved: Moved =
      seen.kind === 'step'
        ? { kind: 'call' }
        : seen.kind === 'tool'
          ? { kind: 'tool', tool: seen.tool }
          : { kind: 'other' };
    phase = phaseAfter(phase, moved);
    surface.working(workingOn(job));
  };

  // THE RUNS WAITING ON A PERSON — the state of the background check below,
  // up here because `onPush` reads it and a push can land the moment the
  // socket opens, before the check itself exists.
  /** The runs the last check found waiting, in the order the listing gave them. */
  let waiting: readonly Waiting[] = [];
  /** Runs a push has said are asking since the last check, to be read again. */
  const stale = new Set<string>();
  /** The check in flight, so a second asks for one more pass rather than racing it. */
  let checkingWaiting: Promise<void> | undefined;
  let checkAgain = false;
  /** Whether the background check is on. See {@link Talking.waitingEvery}. */
  let watchingWaiting = false;
  /**
   * The conversation on screen, whose own approvals it shows inline and the check leaves
   * out. A function set once `place` exists further down: the first check runs at connect,
   * before that `let` has been reached, and reading it there would throw.
   */
  let hereNow: () => string | undefined = () => undefined;
  /**
   * What an `orchestration.recorded` push does to the runs panel. Set once the panel can be read, on {@link hereNow}'s
   * pattern: a push can land the moment the socket opens.
   */
  let panelRecorded: (root: string) => void = () => undefined;
  /** What an `orchestration.changed` push does to the runs panel; set beside {@link panelRecorded}. */
  let panelChanged: (changed: {
    readonly id: string;
    readonly state: string;
  }) => void = () => undefined;
  /** What the check's tick does to the panel: redraws it on a fresh clock, set beside it. */
  let panelTick: () => void = () => undefined;
  /** What an `orchestration.recorded` push does to a `/watch` that is open; set while one is. */
  let watchPushed: (recorded: RecordedPush) => void = () => undefined;
  /** What the check's tick does to a `/watch` that is open: redraws it, so its elapsed times move. */
  let watchTick: () => void = () => undefined;
  /** Questions waiting for their dialog, oldest first. The dialog is further down. */
  const dialogs: Waiting[] = [];
  /** The question whose dialog is up now: drawn, or printed and waiting on its line. */
  let dialogUp: Waiting | undefined;
  /**
   * Where a command approval's printed dialog stands, between the lines that answer it — `p`
   * then the arrows, on a surface that reads lines. Nothing while none is up.
   */
  let approvalAsking: Asking | undefined;
  /**
   * What the check does once it has queued the questions it found: takes away a dialog
   * whose question was settled elsewhere, and puts up the next. Set once a dialog can be put
   * up, on {@link hereNow}'s pattern — the queue is kept up here, so a check that lands before
   * then loses nothing, and the next prompt puts it up.
   */
  let dialogsChanged: (asking: ReadonlySet<string>) => void = () => undefined;
  /**
   * What a `conversation.appended` push does. Set once the connection exists, on
   * {@link hereNow}'s pattern: a push can land the moment the socket opens, before anything
   * it would read with is there.
   */
  let grew: (grown: Appended) => void = () => undefined;
  /** Whoever else hears a `conversation.appended` push: an explorer that is open, for its levels. */
  const appendedListeners = new Set<
    (conversation: string, through: number) => void
  >();
  /** How far pushes have said the followed log reaches, or nothing; set beside {@link grew}. */
  let reach: () => number | undefined = () => undefined;
  /**
   * How far the followed log reached when each run's `ended` landed, by run, for the turn in
   * flight. The server pushes a turn's `conversation.appended` before its `ended` and cannot
   * start another turn in that conversation until this one has ended, so <b>when that push
   * arrived</b> this is the end of the turn the stream showed: a harness turn that lands after
   * it is beyond it, however soon. <b>Best effort, not exact</b>: pushes are not durable, and
   * when this turn's was lost this is only how far pushes reached before it — no further than
   * the screen already shows, which is how `caughtUp` tells, and reads the log's reach instead.
   * <b>Kept by run and read at arrival</b>, because an `ended` can outrun the handle that says
   * it is this turn's, and pushes after it are read before that handle is.
   */
  const endedAt = new Map<string, number | undefined>();

  function onPush(push: unknown): void {
    // A BARE PUSH WITH NO `job`, CHECKED FIRST FOR THAT REASON. It carries
    // no `protocol_version` and belongs to no turn — `followed` below
    // would call it unreadable, which is true but is not the same thing as
    // nothing having happened.
    const count = unreadOf(push);
    if (count !== undefined) {
      surface.unread(count);
      // A COMMAND APPROVAL RAISED UNDER AN ORCHESTRATION ARRIVES THIS WAY: the server
      // puts it in the inbox, since no person has its conversation open. So the inbox
      // changing is a reason to look at what is waiting as well as to count it.
      if (watchingWaiting) {
        background(checkWaiting());
      }
      return;
    }
    // A RUN THAT HAS STARTED WAITING ON A PERSON, AND IT IS HANDLED UP HERE
    // FOR THE SAME REASON THE UNREAD COUNT IS.
    //
    // It carries no `job`, so everything below would read it as unreadable;
    // and the gate a few lines down returns early between turns, which is
    // exactly when this arrives. An orchestration outlives the turn that
    // started it — that is the whole point of one — so the question lands
    // while nobody is watching a run, and a line printed only during a turn
    // would be a line printed only when it is not needed.
    // THE RECORD OF A RUN TREE GREW. Bare, like the pushes above, and for their reason: it
    // lands between turns. The panel re-reads; an open `/watch` of that tree reads what is new.
    const recorded = recordedOf(push);
    if (recorded !== undefined) {
      panelRecorded(recorded.root);
      watchPushed(recorded);
      return;
    }
    const changed = changedOf(push);
    if (changed !== undefined) {
      // A run of this account moving can start, end or move a live tree.
      panelChanged(changed);
      // The push names a run and a state and not the question, so it does
      // not print anything itself: it sends the check, which reads the
      // question and says it. `rechecks` decides which changes earn that.
      if (
        watchingWaiting &&
        rechecks(
          changed,
          waiting.map((run) => run.id),
        )
      ) {
        if (changed.state === 'asking') {
          stale.add(changed.id);
        }
        background(checkWaiting());
      }
      return;
    }
    // THE LOG OF THE CONVERSATION ON SCREEN GREW. Bare and with no `job`, like the two
    // pushes above, and handled up here for their reason: it lands between turns, which is
    // exactly when the gate below returns early.
    const grown = appendedOf(push);
    if (grown !== undefined) {
      for (const listener of appendedListeners) {
        listener(grown.conversation, grown.through);
      }
      grew(grown);
      return;
    }
    const current = turn;
    // A DELTA FIRST, BECAUSE IT IS NOT AN EVENT AND MUST NOT BE READ AS ONE.
    // Both travel this socket bare; `followed` would call a delta
    // unreadable and put "a frame arrived that is not a job event" on the
    // screen once per token.
    const piece = streamed(push);
    if (piece !== undefined) {
      // ONLY THIS RUN'S. The gate `onPush` applies to events applies here
      // for the same reason: several jobs share one socket, and another
      // run's tokens in this run's status region is another run's
      // business on this screen.
      //
      // <b>AND AN UNATTRIBUTABLE DELTA IS DROPPED, NOT HELD.</b> An event
      // that arrives before this client knows its own handle is kept by
      // `following` and replayed once `answering` says whose it was --
      // the unknown-handle window is the normal path, not a sliver, so
      // that machinery is not optional for events. Deltas do not get it:
      // they are droppable by design, the loss is the first few tokens of
      // a preview that catches up within milliseconds, and holding them
      // would mean a second buffer for text that is already allowed to
      // have holes in it. Said here because the cost is real and small
      // rather than absent.
      if (current?.job !== undefined && piece.job === current.job) {
        live = appended(live, piece.part, piece.text);
        phase = phaseAfter(phase, { kind: 'delta', part: piece.part });
        surface.working(workingOn(current.job));
      }
      return;
    }
    if (current === undefined) {
      // Between turns. A `JobEvent` with nobody waiting for it belongs to
      // a run this session is not following, and saying something about
      // it would be putting another job's business on this screen.
      return;
    }
    const seen = followed(push);
    if (seen.kind === 'ended' && !endedAt.has(seen.job)) {
      endedAt.set(seen.job, reach());
    }
    const delivery = jobs.push(push);
    if (delivery === undefined || delivery.ticket !== activeTicket) {
      return;
    }
    const next = following(current, push);
    turn = next;
    // ONLY THIS JOB'S, AND ONLY ONCE THE HANDLE SAYS WHICH JOB THAT IS.
    //
    // <b>This gate used to read `next.job === undefined || …`</b>, which
    // printed every event that arrived before the handle landed, whatever
    // job it belonged to. It looked like the interleaving contract being
    // honoured — see the header on why an event can outrun its answer —
    // and it was the one place in this client written as though the handle
    // lands first. `session.ts` was never fooled: `following` HOLDS an
    // unattributable event and `answering` then drops the ones the handle
    // says were somebody else's, so a foreign event was printed and not
    // attached. Several jobs on one socket is not exotic — a second
    // terminal, or an agent that delegates — and `JobEvent.job` exists for
    // exactly that.
    //
    // <b>What replaces it does not lose the progress</b>, which is the
    // reason the old gate was written the way it was. The backlog is
    // reported by `speak` the moment `answering` says which of it was this
    // turn's, so a person still watches a fast run move; they simply watch
    // it move one microtask later, and they never watch somebody else's.
    if (
      seen.kind !== 'unreadable' &&
      next.job !== undefined &&
      seen.job === next.job
    ) {
      report(seen);
    }
    if (finished(next)) {
      ending?.();
    }
  }

  /**
   * The socket went away.
   *
   * <p>Fails the follow-wait if there is one, and does nothing at all if
   * there is not: between turns there is no run to lose, and the next ask
   * will be refused by the connection with its own sentence. A `Dropped` with
   * nobody awaiting it would be an unhandled rejection, which is a crash
   * rather than a message.
   */
  let connectionLost = false;
  let reconnectNow: (() => Promise<void>) | undefined;
  function onClose(): void {
    connectionLost = true;
    jobs.reset(true);
    dropped?.(new Dropped());
    if (talking.reconnect) background(reconnectNow?.().catch(() => undefined));
  }

  let session =
    talking.credentials === undefined
      ? await signIn(talking.door, talking.handle, talking.password)
      : talking.password
        ? await talking.credentials.login(
            talking.door,
            talking.handle,
            talking.password,
          )
        : await talking.credentials.session();
  if (session.setupRequired) {
    show(
      'trouble',
      'Finish first-run setup with plowshare-cli setup --url ' +
        talking.door.base +
        ', then reconnect with your administrator account or the saved session.',
    );
    return;
  }
  if (session.mustChangePassword) {
    // THE FLAG USED TO END THE SESSION HERE, WHICH MADE THIS CLIENT
    // UNUSABLE ON A FRESH SERVER.
    //
    // It printed one sentence and returned. Every account this server
    // seeds is flagged, so the FIRST run of this client against a new
    // deployment hit it, with no way out that did not involve curl --
    // measured on 2026-09-12 by doing exactly that. The server was never
    // the problem: `/v1/auth/password` has always existed and the login
    // that set this flag handed back a live access token to call it with.
    //
    // So it is asked for here instead. A flagged session can do nothing
    // else, so there is no question of carrying on without it.
    const changed = await changing(session.tokens.access);
    if (changed === undefined) {
      return;
    }
    show('client', PASSWORD_CHANGED);
    // A NEW SIGN-IN, BECAUSE THE OLD TOKENS ARE GONE. The server revokes
    // every access token presented to the change -- correctly, a password
    // change must not leave the old credential working -- so the token
    // above is dead and the socket below would be opened with nothing.
    session =
      talking.credentials === undefined
        ? await signIn(talking.door, talking.handle, changed)
        : await talking.credentials.login(
            talking.door,
            talking.handle,
            changed,
          );
  }
  const loginHandle =
    talking.handle ||
    (talking.credentials === undefined
      ? ''
      : (await talking.credentials.session()).handle);
  // BEFORE THE SOCKET, so a directory that cannot be resolved fails with
  // nothing opened rather than leaving a connection behind.
  const machine = talking.machine ?? thisMachine(process.env, hostname());
  /** Where the person stands, canonical; `/cd` moves it. Absent, nothing is discovered. */
  let directory =
    talking.here === undefined ? undefined : await realpath(talking.here);
  const initialEpoch = jobs.generation;
  const openedSocket = await openSocket(
    {
      ...talking.door,
      onPush: (push) => {
        if (jobs.current(initialEpoch)) onPush(push);
      },
      onClose: () => {
        if (jobs.current(initialEpoch)) onClose();
      },
    },
    session.tokens,
  );
  // THE PAIR ROTATES ON EVERY OPEN. The one sign-in returned is retired now,
  // and the file channel refreshes again each time a project is rooted.
  let wire = openedSocket.connection;
  const connection = {
    async ask<K extends Operation>(
      type: K,
      payload: Payloads[K],
    ): Promise<Outcome> {
      const epoch = jobs.generation,
        current = wire;
      const request = decodeRequest(type, payload);
      const outcome = await current.ask(request.type, request.payload);
      if (!jobs.current(epoch)) throw new Dropped();
      if (VALIDATED_OPERATIONS.includes(request.type)) {
        const result = resultOf(request, outcome);
        if (result.kind === 'invalid-response')
          throw new ResponseUnreadable(
            `Unreadable ${type} reply; prior state is retained and completion is unknown.`,
          );
      }
      return {
        code: outcome.code,
        ...(outcome.said === undefined ? {} : { said: outcome.said }),
        ...(['OK', 'ACCEPTED', 'CREATED', 'NO_CONTENT'].includes(outcome.code)
          ? { payload: decodeReply(request.type, outcome.payload) }
          : {}),
      };
    },
    close() {
      wire.close();
    },
  };
  let tokens = openedSocket.tokens;
  // REFRESHED ONE AT A TIME. A refresh token is single use, and the file
  // channel and a union's git both refresh: two at once would each present
  // the same token and the second would find it already retired.
  let renewal: Promise<unknown> = Promise.resolve();
  const renewing = <T>(work: () => Promise<T>): Promise<T> => {
    const next = renewal.then(work, work);
    renewal = next.catch(() => undefined);
    return next;
  };
  /** The rooted project's union lifecycle, when one is rooted. Spec 2026-09-14 §4. */
  let sync: Syncer | undefined;
  /** `⚠ 2 sync conflicts` for the status line, or nothing. */
  let syncNotice: string | undefined;
  const rooting = rooter({
    open: (claim) =>
      renewing(async () => {
        // KEPT THE MOMENT IT ROTATES, not when the open succeeds: a ticket or
        // an upgrade that fails after the refresh has still retired the old
        // pair, and the restore that follows a failed root opens again.
        const opened = await openFiles(
          talking.door,
          tokens,
          claim,
          (renewed) => {
            tokens = renewed;
          },
        );
        return opened.socket;
      }),
    // An agent's write is what a union pushes after, debounced.
    answering: (root) => noticingWrites(enforcing(root), () => sync?.changed()),
    onLost: (claim, closing) => {
      show('trouble', describeRootLost(claim.project, closing.reason));
      // The server no longer counts this session as rooting it, so it
      // would refuse every union frame the syncer asks from here on.
      background(unsyncing());
    },
  });

  // ASKED ONCE, QUIETLY, AND NOT WITH `listingInbox`. A socket with no
  // account gets `BAD_REQUEST` here — `InboxListHandler`'s rule, not a
  // client-side guess — and that must not read as trouble on a screen
  // somebody has not typed anything into yet. `limit: 1` because the count
  // is all this is for; `/inbox` is what asks to see the twenty.
  background(
    connection.ask(INBOX_LIST, { unread: true, limit: 1 }).then(
      (outcome) => {
        const page = outcome.payload as { unread?: number } | undefined;
        if (outcome.code === OK && typeof page?.unread === 'number') {
          surface.unread(page.unread);
        }
      },
      () => undefined,
    ),
  );

  // THE RUNS WAITING ON A PERSON, CHECKED IN THE BACKGROUND.
  //
  // A run's question used to reach a person only as a push naming its id,
  // and answering it meant `/runs <id>` to read it and `/answer <id>` to say
  // something back. This keeps the list itself: the status line counts it,
  // `/answer ` offers it, and each run that starts asking is said once, with
  // its question, so answering is `/answer` and the reply.
  //
  // Quiet on every failure. A socket with no account is refused the listing
  // and a server without orchestrations answers it with nothing; neither is
  // news to somebody who has not typed anything, and the last list stands.
  const withQuestion = async (run: Waiting): Promise<Waiting> => {
    const read = readingRun(run.id);
    const said = await connection
      .ask(read.type, read.payload)
      .catch(() => undefined);
    const status = said === undefined ? undefined : runStatusOf(said);
    const question = status === undefined ? undefined : questionOf(status);
    const structure = status === undefined ? undefined : structureOf(status);
    return question === undefined
      ? run
      : { ...run, question, ...(structure === undefined ? {} : { structure }) };
  };
  const checkOnce = async (): Promise<void> => {
    const listing = listingAsking();
    const mine = listingMyApprovals();
    const stalling = listingStalled();
    const [listed, approved, stalled] = await Promise.all([
      connection.ask(listing.type, listing.payload).catch(() => undefined),
      connection.ask(mine.type, mine.payload).catch(() => undefined),
      connection.ask(stalling.type, stalling.payload).catch(() => undefined),
    ]);
    const runs = listed === undefined ? undefined : runsOf(listed);
    const approvals =
      approved === undefined ? undefined : approvalsOf(approved);
    const stallable = stalled === undefined ? undefined : runsOf(stalled);
    const before = {
      runs: waiting.filter(
        (run) => run.kind !== 'approval' && run.kind !== 'stalled',
      ),
      approvals: waiting.filter((run) => run.kind === 'approval'),
      stalled: waiting.filter((run) => run.kind === 'stalled'),
    };
    // EACH THIRD STANDS ON ITS OWN. A server too old for one of these frames refuses
    // it, and the other two are still worth having; whichever third fails keeps what
    // the last check found rather than dropping runs a person had already been told
    // about.
    let runsNow = before.runs;
    let fresh: string[] = [];
    if (runs !== undefined) {
      // Cleared only once the listing is in hand, so a failed check does not
      // forget which runs a push said to read again.
      const marked = new Set(stale);
      stale.clear();
      const { now, unread } = waitingAfter(before.runs, runs, marked);
      runsNow = await Promise.all(
        now.map((run) =>
          unread.includes(run.id) ? withQuestion(run) : Promise.resolve(run),
        ),
      );
      fresh = unread;
    }
    const approvalsNow =
      approvals === undefined
        ? before.approvals
        : approvalsWaiting(approvals, hereNow());
    const known = new Set(before.approvals.map((approval) => approval.id));
    fresh = [
      ...fresh,
      ...approvalsNow.filter((a) => !known.has(a.id)).map((a) => a.id),
    ];
    const stalledNow =
      stallable === undefined ? before.stalled : stalledWaiting(stallable);
    const knownStalled = new Set(before.stalled.map((run) => run.id));
    fresh = [
      ...fresh,
      ...stalledNow
        .filter((run) => !knownStalled.has(run.id))
        .map((run) => run.id),
    ];
    const read = [...runsNow, ...approvalsNow, ...stalledNow];
    waiting = read;
    surface.waiting(read);
    for (const run of read) {
      if (fresh.includes(run.id)) {
        show(
          'client',
          describeNowAsking(run, surface.columns?.() ?? DEFAULT_COLUMNS),
        );
      }
    }
    // A QUESTION THE PERSON CAN ANSWER IS PUT TO THEM (spec 2026-09-29 §2, widened): a cap, a
    // stuck run, a root's own question — `dialogKindOf` says which. A fresh one is queued for
    // its dialog, and one no longer waiting leaves the queue.
    for (const run of read) {
      if (
        fresh.includes(run.id) &&
        dialogKindOf(run) !== undefined &&
        dialogUp?.id !== run.id &&
        !dialogs.some((each) => each.id === run.id)
      ) {
        dialogs.push(run);
      }
    }
    const still = new Set(read.map((run) => run.id));
    for (let at = dialogs.length - 1; at >= 0; at -= 1) {
      if (!still.has(dialogs[at]?.id ?? '')) {
        dialogs.splice(at, 1);
      }
    }
    dialogsChanged(still);
  };
  /** One check, or — with one already running — one more pass after it. */
  const checkWaiting = (): Promise<void> => {
    if (checkingWaiting !== undefined) {
      checkAgain = true;
      return checkingWaiting;
    }
    checkingWaiting = (async () => {
      try {
        do {
          checkAgain = false;
          await checkOnce();
        } while (checkAgain);
      } finally {
        checkingWaiting = undefined;
      }
    })();
    return checkingWaiting;
  };
  const waitingTimer =
    talking.waitingEvery === undefined
      ? undefined
      : setInterval(() => {
          background(checkWaiting());
          panelTick();
          watchTick();
        }, talking.waitingEvery);
  if (waitingTimer !== undefined) {
    watchingWaiting = true;
    background(checkWaiting());
  }

  /** Whether Ctrl-C has already been pressed against the run in flight. */
  let pressed = false;
  /** The run a `job.cancel` really went out for, or nothing. */
  let stopping: string | undefined;

  /**
   * <b>Ctrl-C: the first press stops the run, the second leaves.</b>
   *
   * <h2>What it used to do, which is the whole reason this exists</h2>
   *
   * <p>Quit the client. The run went on server-side — `agent.run` hands back
   * a handle and the job lives in the server whether or not anybody is
   * listening — so the key that everybody presses to stop something was the
   * key that abandoned it, spending a budget nobody was watching to produce
   * an answer nobody would read. `job.cancel` has been routed since the socket
   * surface was built and this client had never called it.
   *
   * <h2>The second press is not a nicety</h2>
   *
   * <p>A cancel is honoured between steps and a step can be a model call that
   * takes a minute, so the first press is a request with an unbounded wait
   * behind it. A person who wants out must always get out: the second press
   * fails the follow-wait with {@link Quit} rather than waiting for an ending
   * that is, by definition, not coming yet.
   *
   * <p><b>And it falls back to closing the prompt</b>, for the moments when
   * there is no follow-wait to fail — between the `agent.run` going out and
   * its answer coming back, which is milliseconds rather than minutes. The
   * sentence is said here in that case, because nothing downstream will.
   *
   * <h2>Three states and not two</h2>
   *
   * <p>Nothing running is its own case and gets its own sentence: the key
   * means "throw away what I was typing" at a prompt, the surface does the
   * throwing away, and this says so rather than exiting without a word.
   * <b>A run with no handle yet is the third</b>: there is a run and this
   * client cannot name it, which is not the same as there being no run, and a
   * `job.cancel` naming nothing would be a frame invented to look busy.
   */
  function interrupted(): void {
    const current = turn;
    if (current === undefined) {
      show('client', describeNothingRunning());
      return;
    }
    if (pressed) {
      const leave = dropped;
      if (leave !== undefined) {
        leave(new Quit(stopping !== undefined));
        return;
      }
      show('client', describeLeaving(stopping !== undefined));
      surface.close();
      return;
    }
    pressed = true;
    const job = current.job;
    if (job === undefined) {
      show('client', describeUnnamedRun());
      return;
    }
    stopping = job;
    const stop = cancelling(job);
    // SAID BEFORE THE ANSWER AND NOT AFTER IT. What this client did is
    // known now; what the server makes of it is a round trip away, and a
    // person holding a key down is owed the first of those immediately.
    show('client', describeCancelling(job));
    // Not awaited, because this is a keypress and not a turn: the run goes
    // on being followed and its ending arrives through `onPush` as it
    // always did. The cancel's own answer says nothing this client acts on
    // — `JobCancelHandler` answers with the job still RUNNING even when the
    // request landed — so the only thing worth reporting is a refusal.
    background(
      connection.ask(stop.type, stop.payload).then(
        (said) => {
          if (said.code !== OK) {
            show('trouble', refusal(said));
          } else if (activeTicket !== undefined && turn?.job === job) {
            jobs.cancelling(activeTicket);
          }
        },
        // A cancel that never reached the server is not worth a second
        // sentence: the socket going away is already said, once, by
        // `onClose` failing the wait this turn is sitting in.
        () => undefined,
      ),
    );
  }

  surface.onInterrupt(interrupted);

  // CTRL-T: the next tool-line density, said once and kept for the next session.
  surface.density?.(() => {
    density = nextDensity(density);
    show('client', describeDensity(density));
    if (talking.settings !== undefined) {
      try {
        writeSettings(talking.settings, { density });
      } catch {
        // The choice still holds for this session.
      }
    }
  });

  /**
   * One turn: send it, follow it, read what it ended as, render it.
   *
   * <p><b>`agent` is a parameter rather than `talking.agent`</b>, because who
   * answers is no longer necessarily what a caller named — `whoAnswers` may
   * have read it off the roster. Passing it keeps the resolved name and the
   * name that was asked for from ever being confused for one another.
   */
  const speak = async (
    conversation: string,
    agent: string,
    text: string,
  ): Promise<void> => {
    await carry(conversation, async () => {
      const ticket = jobs.begin(conversation);
      activeTicket = ticket;
      const now = taking(conversation, agent, text, talking.door.session);
      turn = now;
      const said = await connection.ask(now.ask.type, now.ask.payload);
      if (!jobs.current(ticket.generation)) throw new Dropped();
      const answered = answering(latest(now), said);
      if (answered.job === undefined) {
        jobs.failed(ticket, said.code !== 'ACCEPTED');
        return answered;
      }
      return await attachJob(answered, ticket, answered.job);
    });
  };

  /** The core owns the early-event buffer; the TUI owns its progress rendering. */
  const attachJob = async (
    holding: Turn,
    ticket: Ticket,
    handle: string,
  ): Promise<Turn> => {
    if (!jobs.current(ticket.generation)) throw new Dropped();
    const accepted = jobs.accept(ticket, handle);
    let next = handing(holding, handle);
    for (const event of accepted.events) {
      jobs.push(event);
      next = following(next, event);
    }
    turn = next;
    // Buffer pressure makes push-only completion unreliable. Read durable
    // status immediately; never replay the submission to recover its output.
    if (accepted.needsReconcile) {
      const request = checking(handle);
      const answer = await connection.ask(request.type, request.payload);
      jobs.observe(ticket, answer, ticket.generation);
      const over = reached(answer, handle);
      if (over !== undefined)
        turn = following(latest(next), {
          job: handle,
          kind: 'ended',
          ending: over.ending,
        });
    }
    return latest(next);
  };

  /**
   * A turn from whatever starts it to its rendered ending — and, when it
   * ended asking a person something, to their answer and the turn that
   * answer starts.
   *
   * <p><b>`begin` is the only difference between a turn a person spoke and
   * one an approval continued</b>: it sends the frame and hands back the turn
   * with its job, or refused, or nothing when there is nothing to follow and
   * it has already said why. Everything after — the backlog, the wait, the
   * outcome, the clearing — is the one path, so a continued turn is followed
   * exactly as a spoken one is.
   */
  const carry = async (
    conversation: string,
    begin: () => Promise<Turn | undefined>,
  ): Promise<void> => {
    // EVERY PATH OUT OF THIS TURN CLEARS THE RUN STATE, AND THE OUTER
    // `finally` IS NOT ENOUGH. It runs when the conversation ends, so
    // between turns a spinner sat under the prompt saying "this run
    // answered" and counting upwards — watched doing it for three minutes
    // against a live server. A turn owns its own status, so a turn puts it
    // down.
    let carried: Carried = { ran: false };
    let unreadable = false;
    // A TURN OF THIS CLIENT'S OWN IS UNDER WAY: a push about the log waits for it to end,
    // so nothing read out of the log is drawn in the middle of what this turn streams. And
    // it is a new turn, so a catch-up still reading from before it draws nothing either.
    streamingOwn = true;
    begun += 1;
    // THE TRACER OPENS AT THE LOG'S REACH, so it reads this turn's rows and nothing the screen
    // already shows — and only in the conversation on screen, whose pushes are what move it.
    trace =
      tail !== undefined && tail.conversation === conversation
        ? traceOpened(reachOf(tail))
        : undefined;
    traceTurn = undefined;
    calls = [];
    firstSeen.clear();
    try {
      carried = await speaking(conversation, begin);
    } catch (error) {
      unreadable = error instanceof ResponseUnreadable;
      throw error;
    } finally {
      if (
        activeTicket !== undefined &&
        talking.reconnect &&
        (unreadable ||
          !jobs.current(activeTicket.generation) ||
          (carried.ran && carried.over === undefined))
      ) {
        const handle = jobs.snapshot(activeTicket)?.handle;
        if (handle) {
          recoveringJobs.set(handle, conversation);
          if (!connectionLost && !recoveryClosing) {
            recoveryTimer ??= setInterval(() => {
              background(reconcileRecovered());
            }, 2000);
            recoveryTimer.unref?.();
          }
        } else uncertainConversations.add(conversation);
      }
      if (activeTicket !== undefined) jobs.forget(activeTicket);
      activeTicket = undefined;
      trace = undefined;
      traceTurn = undefined;
      calls = [];
      firstSeen.clear();
      // The watchdog belongs to the turn, so it goes when the turn does.
      //
      // <b>This is hygiene rather than correctness, and the difference
      // was measured.</b> Removing this line breaks no test, because the
      // `turn === undefined` check inside the callback is what actually
      // stops a finished run being mourned — a stray timer fires and says
      // nothing. What this buys is that the timer does not fire at all,
      // which matters to a process that would otherwise hold one per turn
      // for a minute after each. Said plainly because an unguarded line
      // that looks guarded is how the next person deletes it and is right
      // to think nothing happened.
      stopWatching();
      surface.working(undefined);
    }
    await settle(conversation, carried);
    // AFTER THE CLEARING, NOT INSIDE IT: a question asked under a spinner
    // saying the run is still going would be asked about a run that is not.
    if (carried.over?.ending === AWAITING) {
      await awaiting(conversation);
    }
  };

  /**
   * What a turn came to: its ending when one was read, whether a run was handed over at all,
   * and how far the followed log reached when it ended — see {@link endedAt}.
   */
  interface Carried {
    readonly over?: Ended;
    readonly ran: boolean;
    readonly through?: number;
  }

  /** The turn itself. Wrapped by {@link carry}, which owns the clearing. */
  const speaking = async (
    conversation: string,
    begin: () => Promise<Turn | undefined>,
  ): Promise<Carried> => {
    // A new run is a run nobody has asked to stop. Reset here rather than
    // where a turn ends, so that every path into a turn — including one
    // taken after a refusal — starts a person's first press at the first.
    pressed = false;
    stopping = undefined;
    started = Date.now();
    // A NEW TURN SHOWS NOTHING OF THE LAST ONE. Both are per-turn state and
    // a preview left standing would be the previous answer, under a spinner
    // for this one.
    live = undefined;
    reported = undefined;
    phase = undefined;
    // Each turn watches for its own silence. `mourned` resets so a second
    // quiet run is reported too, and `beaten` does NOT -- a server that
    // beat once beats always, and forgetting it would make the first event
    // of every turn re-earn the right to complain.
    mourned = false;
    endedAt.clear();
    let now = await begin();
    if (now === undefined) {
      turn = undefined;
      return { ran: false };
    }
    now = latest(now);
    turn = now;
    const refused = now.refused;
    if (refused !== undefined) {
      turn = undefined;
      show('trouble', refused);
      return { ran: false };
    }
    // THE BACKLOG, NOW THAT THE HANDLE SAYS WHOSE IT WAS. `answering` has
    // just replayed `held` through `following`'s own rule, so `progress`
    // holds exactly the events that outran the answer AND belong to this
    // run — it was empty a line ago, because nothing can be attached to a
    // turn with no handle. Reporting it here is what keeps `onPush`'s gate
    // able to be strict without a person losing sight of a fast run.
    for (const seen of now.progress) {
      report(seen);
    }
    if (!finished(now)) {
      if (activeTicket !== undefined && !jobs.current(activeTicket.generation))
        throw new Dropped();
      await new Promise<void>((done, fail) => {
        ending = done;
        dropped = fail;
      });
      now = latest(now);
    }
    ending = undefined;
    dropped = undefined;
    turn = undefined;
    const job = now.job;
    if (job === undefined) {
      // Unreachable through `answering`, which sets one or the other.
      // Written rather than asserted, because a client that threw here
      // would end a session over a frame it could have described.
      show('trouble', 'the run started but named no handle to follow it by');
      return { ran: false };
    }
    const through = endedAt.get(job) ?? reach();
    // EVERY TOOL LINE OF THE TURN ABOVE ITS ANSWER: the last trace read lands before the
    // answer is shown, on every path — a turn that filed no outcome still ran its tools.
    const sum = await traceDrained(conversation, job);
    const asked = checking(job);
    const status = await connection.ask(asked.type, asked.payload);
    if (activeTicket !== undefined) jobs.observe(activeTicket, status);
    const over = reached(status, job);
    if (over === undefined) {
      show(
        'trouble',
        status.code === OK
          ? jobStatusOf(status, job) === undefined
            ? 'the run status could not be read; completion remains unknown'
            : 'the run has not filed an outcome yet'
          : refusal(status),
      );
      return { ran: true, ...(through === undefined ? {} : { through }) };
    }
    // THE LAST RUN'S PACE, ONLY WHEN IT HAS ONE. A run that ended before any
    // call came back measured nothing, and the pace standing from the run
    // before is still the truest thing to show.
    if (over.pace !== undefined) {
      pace = over.pace;
      stand();
    }
    // THE ANSWER AND ITS PRICE ARE ONE ENTRY, WHICH IS NEW.
    //
    // They were two lines, so scrolling past an answer meant scrolling past
    // what it cost separately, and a surface had no way to know the second
    // belonged to the first. As a `note` the pairing is in the data, and
    // where it is drawn is the surface's to decide.
    //
    // <b>A run that said nothing still reports</b> — an agent whose whole
    // output was a tool call ends with an empty body and a real cost, and a
    // turn that printed nothing at all would look like one that never ran.
    const times =
      sum !== undefined && sum.calls > 0 ? ` · ${describeTurnTimes(sum)}` : '';
    const ended = `this run ${describeEnding(over)} — ${describeCost(over)}${times}`;
    if (over.said.length > 0) {
      showBody('bot', over.said, ended);
    } else {
      show('client', ended);
    }
    // WHY, IN THE SERVER'S OWN WORDS, AND ONLY WHEN IT HAD SOME.
    //
    // Its own entry rather than a longer first line: the line above is what
    // happened and what it cost, which is true of a run that answered, and
    // this is an outage somebody has to go and fix. `trouble` is the channel
    // for the second kind, and a surface that colours them differently
    // already knows how.
    //
    // After the ending and never instead of it. `describeEnding` names the
    // kind of stop, which is the part a person can act on without knowing
    // anything about this server; the type and message underneath it are for
    // whoever owns the endpoint.
    const why = describeFailure(over);
    if (why !== undefined) {
      show('trouble', why);
    }
    return { over, ran: true, ...(through === undefined ? {} : { through }) };
  };

  /**
   * The next strokes for a question: one key from a surface that has keys,
   * or a line read as strokes from one that does not — which is shown the
   * keys first, since it has no region to draw them in. Nothing once input
   * has ended.
   */
  const strokesFor = async (
    state: Asking,
    changed: boolean,
  ): Promise<Stroke[] | undefined> => {
    if (surface.choosing !== undefined) {
      const stroke = await surface.choosing(describeKeys(state));
      return stroke === undefined ? undefined : [stroke];
    }
    if (changed) {
      showPlain('client', describeKeys(state));
    }
    const line = await surface.asked();
    return line === undefined ? undefined : strokesOf(line);
  };

  /**
   * A turn ended `AWAITING`: find what it asked, let the person answer each
   * question, and follow the turn the answers start.
   *
   * <h3>Every answer is decided before any is sent</h3>
   *
   * <p>One batch can ask about several commands, and each answer the server
   * records starts a turn when the conversation is free. Sent one at a time
   * between questions, the first answer's turn would be running — and
   * retrying the second command, and asking about it again — while the
   * person was still reading the second question. Decided first and sent
   * back to back, the first answer that is not busy starts the one turn, and
   * the rest are already standing when that turn reaches their commands; a
   * busy reply after it is that turn, so it is not reported as a problem.
   *
   * <p><b>A question list that is empty says nothing</b>: an `AWAITING` turn
   * need not be a command — an orchestration waits on a person the same way —
   * and the ending line above has already said the run is waiting.
   */
  const awaiting = async (conversation: string): Promise<void> => {
    const listed = await settled(listingAsked(conversation));
    const open = approvalsOf(listed);
    if (open === undefined) {
      show('trouble', refusal(listed));
      return;
    }
    const decided: Ask[] = [];
    for (const approval of open.filter((row) => row.state === 'asked')) {
      showPlain('client', describeQuestion(approval));
      let state = askingAbout(approval);
      let changed = true;
      while (!settledAsking(state)) {
        const strokes = await strokesFor(state, changed);
        if (strokes === undefined) {
          // Input ended at the question. Nothing is sent, and the
          // prompt that asks next finds the end and leaves.
          return;
        }
        const was = state;
        state = strokes.reduce(pressingOn, state);
        changed = state !== was;
      }
      if (state.kind === 'answered') {
        show('person', describeDecided(state));
        decided.push(state.ask);
      } else {
        show('client', describeLeftOpen(approval));
      }
    }
    if (decided.length === 0) {
      return;
    }
    await carry(conversation, async () => {
      const ticket = jobs.begin(conversation);
      activeTicket = ticket;
      const first = decided[0] as Ask;
      // HELD BEFORE THE FIRST ANSWER GOES, so the continuing turn's
      // events that outrun the answer naming it are kept, on the same
      // rule `agent.run`'s are.
      const holding: Turn = { ask: first, progress: [], held: [] };
      turn = holding;
      let job: string | undefined;
      const busy: Answered[] = [];
      for (const ask of decided) {
        const said = await connection.ask(ask.type, ask.payload);
        const answered = answeredOf(said);
        if (answered === undefined) {
          show('trouble', refusal(said));
        } else if (answered.job !== undefined && job === undefined) {
          job = answered.job;
        } else if (answered.busy) {
          busy.push(answered);
        }
      }
      if (job === undefined) {
        for (const answered of busy) {
          show('client', describeAnswered(answered));
        }
        return undefined;
      }
      return await attachJob(latest(holding), ticket, job);
    });
  };

  /**
   * One listing: send the frame, read the rows, put them on the screen.
   *
   * <p><b>Nothing is opened by this, and that is the property worth naming.</b>
   * A conversation is tentative until somebody speaks — see the loop below —
   * so a listing that opened one to show it would undo that for anybody who
   * typed `/conversations` first, and would add the row they were looking for
   * to the list they were looking at. `composition.test.ts` pins it on what
   * goes out rather than on what is printed.
   *
   * @param read what to make of the answer — `projects`, `conversations`,
   *     `agents`, `definitionsOf` or `runsOf`, every one of which answers
   *     nothing at all for a refusal rather than an empty list
   * @param describe what those rows say, which is `wording.ts`'s and not this
   *     file's — every sentence here is the same sentence in a window. A
   *     describer needing more than the rows (the project a listing was
   *     scoped to, the zone a time is shown in) is closed over at the call
   *     site, which is where the view holds those and the logic does not
   */
  const list = async <Row>(
    ask: Ask,
    read: (
      said: Awaited<ReturnType<typeof connection.ask>>,
    ) => Row[] | undefined,
    describe: (rows: readonly Row[]) => string[],
  ): Promise<void> => {
    const said = await connection.ask(ask.type, ask.payload);
    const rows = read(said);
    if (rows === undefined) {
      // The server's own sentence whenever it sent one. An empty listing
      // here would tell a person they have nothing, where what happened
      // is that they were not told.
      show('trouble', refusal(said));
      return;
    }
    show('client', toListing(describe(rows)));
  };

  /**
   * The log of a conversation that is already going, put on the screen.
   *
   * <p><b>Read from the log, by the renderer a push uses</b> (spec 2026-09-28 §5): who spoke
   * each turn is the log's to say, so a result the harness delivered reads back as the
   * harness's and never as "you said". Neither a restoration nor a resumption — the banner
   * says which conversation this is, and what is below is what the log holds now.
   *
   * <p><b>One read, back from the log's end</b>: the last {@link LOG_BACK} entries the chat
   * draws, and a line above them offering `/earlier` when the log holds more.
   *
   * <p><b>The opening line is printed whatever the read does</b>: a history this client could
   * not read back is a conversation a person is still in.
   */
  const showing = async (going: Conversation): Promise<void> => {
    const read = await readingBack(going.id);
    if ('refused' in read) {
      show('client', describeContinuing(going, 0, 0)[0] ?? '');
      show('trouble', refusal(read.refused));
      // Nothing of the log is on the screen: the next push, or the end of the next
      // turn, fills it rather than every push being held as though a fill were running.
      shownTo(going.id, undefined);
      return;
    }
    const replay = replaying(read.entries, read.through);
    for (const line of describeContinuing(going, replay.held, replay.shown)) {
      show('client', line);
    }
    if (tail !== undefined && tail.conversation === going.id) {
      reachedBack(tail, read);
    }
    rendering(replay.items);
    shownTo(going.id, read.through);
  };

  /** The zone a schedule sentence is read in and fire times are shown in. */
  const zone = talking.zone ?? terminalZone();

  /**
   * Lines put up exactly as written, set in as a listing.
   *
   * <p>Not through the markdown grammar, which is what {@link show} does:
   * a cron's asterisks and a task's underscores are characters a person is
   * about to say yes to, and emphasis would quietly eat them.
   */
  const showPlain = (voice: Voice, lines: readonly string[]): void => {
    showBody(voice, [
      { kind: 'para', spans: [{ kind: 'text', text: toListing(lines) }] },
    ]);
  };

  /**
   * One ask whose rejection is an answer rather than a throw.
   *
   * <p>A connection that went away rejects every ask, and an unguarded
   * `await` on one would end the session from inside a command — the rule
   * `/inbox`'s read receipt follows. The sentence is the rejection's own.
   */
  // Keep the explicit retry key through an uncertain reply. A new terminal timestamp identifies
  // a later failed attempt; reconnecting or repeating /retry alone must not invent another claim.
  const resumeKeys = new Map<
    string,
    { failure: string | undefined; requestId: string }
  >();
  const resumeRun = async (id: string): Promise<Answer> => {
    const said = await settled(readingRun(id));
    const status = runStatusOf(said);
    if (status === undefined) return said;
    if (status.run.state !== 'failed' || status.run.parent !== undefined)
      return { code: 'REFUSED', said: 'Choose a failed root run to resume.' };
    let retained = resumeKeys.get(id);
    if (retained === undefined || retained.failure !== status.run.endedAt) {
      retained = { failure: status.run.endedAt, requestId: randomUUID() };
      resumeKeys.set(id, retained);
    }
    return settled(resumingRun(id, retained.requestId));
  };

  const settled = (ask: Ask): Promise<Answer> =>
    connection.ask(ask.type, ask.payload).then(
      (outcome) => outcome,
      (trouble: unknown) => ({
        code: 'UNSENT',
        said:
          trouble instanceof Error ? trouble.message : errorMessage(trouble),
      }),
    );

  /**
   * Read the followed conversation's new rows and write whatever tool lines are settled; what
   * is still running goes to the working state. One read at a time, each after the last.
   *
   * <p><b>A trace put down while this read was out is left down</b>: the turn has ended, and
   * what the read brings back belongs to nobody on screen.
   *
   * <p><b>A burst of pushes is one read</b>: while a read of the same conversation is queued and
   * not yet begun, asking again is that read — it begins after every push so far, so it reads
   * what they said. Measured 2026-09-30: every push was a read of its own.
   */
  const traceNow = (conversation: string): Promise<void> => {
    if (traceQueued === conversation) {
      return tracing;
    }
    traceQueued = conversation;
    tracing = tracing
      .then(async () => {
        // Cleared as it begins and not as it ends: a push while it reads earns one more.
        traceQueued = undefined;
        const opened = trace;
        if (opened === undefined) {
          return;
        }
        const rows: Entry[] = [];
        for (;;) {
          const page = await settled(
            readingTrace(conversation, opened.through, rows.length),
          );
          if (page.code !== OK || trace !== opened) {
            return;
          }
          const got = entriesOf(page);
          rows.push(...got);
          if (got.length === 0 || rows.length >= logTotal(page)) {
            break;
          }
        }
        if (traceTurn === undefined && rows.length > 0) {
          traceTurn = rows.reduce(
            (least, row) => Math.min(least, row.turnOrdinal),
            Number.MAX_SAFE_INTEGER,
          );
        }
        const ours = traceTurn;
        const next = traceGrew(opened, rows);
        trace = next.trace;
        calls = next.pending.filter((call) => call.turn === ours);
        const seenAt = Date.now();
        for (const call of calls) {
          if (!firstSeen.has(call.id)) {
            firstSeen.set(call.id, seenAt);
          }
        }
        const width = (surface.columns?.() ?? 80) - 1;
        const lines = next.written
          .filter((step) => step.turn === ours)
          .flatMap((step) =>
            step.kind === 'call'
              ? describeCallLines(step, density, width)
              : describeReasoningLines(step, density, width),
          );
        if (lines.length > 0) {
          keyed += 1;
          surface.show(traced(keyed, lines));
        }
        const job = turn?.job;
        if (job !== undefined) {
          surface.working(workingOn(job));
        }
      })
      .catch(() => undefined);
    return tracing;
  };

  /**
   * The turn's tool lines, all of them, before its answer is shown: the reads its pushes asked
   * for, and — with tool lines hidden — the one line that sums them. What the tracer holds of
   * the turn, for the answer's note, or nothing when it traced none. <b>Puts the tracer
   * down</b>, so no read still queued can write a line under the answer, and takes the calls
   * out of the working state — each is in the scrollback now, or never will be.
   *
   * <p><b>One more read only when there is something to read</b>: a push said the log reaches
   * past what was read, or a call drawn pending has not been written. The server pushes a
   * turn's `conversation.appended` before its `ended`, so every push of the turn has queued its
   * read by now; a turn whose pushes were all lost and that drew nothing pending draws no tool
   * lines — its answer still stands, and `/trajectory` has the calls — rather than every turn
   * paying a read.
   */
  const traceDrained = async (
    conversation: string,
    job: string,
  ): Promise<TracedTurn | undefined> => {
    await tracing;
    const read = trace;
    // What the working region draws as running now; the read below may settle them unseen.
    const drawn = calls.length > 0;
    if (
      read !== undefined &&
      ((tail !== undefined && tail.known > read.through) || drawn)
    ) {
      await traceNow(conversation);
    }
    const held = trace;
    const ours = traceTurn;
    trace = undefined;
    if (drawn) {
      calls = [];
      surface.working(workingOn(job));
    }
    if (held === undefined || ours === undefined) {
      return undefined;
    }
    const sum = turnOf(held, ours);
    if (sum !== undefined && sum.calls > 0 && density === 'hidden') {
      keyed += 1;
      surface.show(traced(keyed, [describeTraceTurn(sum)]));
    }
    return sum;
  };

  /*
   * THE RUNS PANEL. Spec 2026-09-28, the orchestration record §4. Kept only when the background
   * check is on and the surface draws one.
   *
   * <p><b>Read on a push and never on a clock, and no more than the push is about.</b> The record
   * is written by the harness as each thing happens and pushed as it is, so a push is the only
   * time there is anything new to read: `orchestration.recorded` reads its own tree again, and
   * nothing else; `orchestration.changed` reads the tree its run is in, or lists the live runs
   * when a run in no tree has gone live (a new root), and is passed over otherwise. The listing
   * is read at connect and then only for that. The check's tick only redraws what the reads
   * found, so its elapsed times move.
   */

  /** The live trees the last reads found, newest root first — what `/watch` falls back to. */
  let liveTrees: readonly Tree[] = [];

  /** A tree's two halves: what `orchestration.status` says, and what the record's tails do. */
  type Status = Pick<Tree, 'run' | 'stages' | 'phases'>;
  type Tails = Pick<Tree, 'activity' | 'milestones'>;

  /** A tree's status half: the run's stages and its live phases' stages. Nothing for a run
   *  whose status cannot be read. */
  const statusOf = async (id: string): Promise<Status | undefined> => {
    const read = runStatusOf(await settled(readingRun(id)));
    if (read === undefined) {
      return undefined;
    }
    const phases = await Promise.all(
      read.children
        .filter((child) => LIVE_STATES.includes(child.state))
        .map(async (child): Promise<RunPhase | undefined> => {
          const said = runStatusOf(await settled(readingRun(child.id)));
          return said === undefined
            ? undefined
            : { run: said.run, stages: said.stages };
        }),
    );
    return {
      run: read.run,
      stages: read.stages,
      phases: phases.filter((phase): phase is RunPhase => phase !== undefined),
    };
  };

  /** A tree's record half: its latest tool line and its last `marks` milestones — nothing when
   *  either read was refused, so a refusal is never read as a tree with no milestones. */
  const tailsOf = async (
    id: string,
    marks: number,
  ): Promise<Tails | undefined> => {
    const [activity, milestones] = await Promise.all([
      settled(readingRecordTail(id, [TOOL_CALL], 1)),
      settled(readingRecordTail(id, MILESTONE_KINDS, marks)),
    ]);
    const tools = recordPageOf(activity);
    const marked = recordPageOf(milestones);
    if (tools === undefined || marked === undefined) {
      return undefined;
    }
    const latest = tools.rows.at(-1);
    return {
      ...(latest === undefined ? {} : { activity: latest }),
      milestones: marked.rows,
    };
  };

  /** A tree from its two halves. */
  const treeFrom = (status: Status, tails: Tails | undefined): Tree => ({
    run: status.run,
    stages: status.stages,
    phases: status.phases,
    ...(tails?.activity === undefined ? {} : { activity: tails.activity }),
    milestones: tails?.milestones ?? [],
  });

  /**
   * A tree with the question it waits on, when its root or a phase is asking: the latest among
   * its last milestones, or `kept` — the one it was drawn with, when its milestones have not
   * moved since — or else read alone. Only an asking tree is asked about, so a busy tree's tool
   * lines cost no more than they did.
   */
  const questioned = async (
    tree: Tree,
    kept?: Tree['question'],
  ): Promise<Tree> => {
    if (!treeAsking(tree)) {
      return tree;
    }
    const known = latestQuestion(tree.milestones) ?? kept;
    if (known !== undefined) {
      return { ...tree, question: known };
    }
    const read = recordPageOf(
      await settled(readingQuestion(tree.run.id)),
    )?.rows.at(-1);
    return read === undefined ? tree : { ...tree, question: read };
  };

  /** One tree, both halves read side by side. Nothing for a run whose status cannot be read. */
  const treeOf = async (
    id: string,
    marks: number,
  ): Promise<Tree | undefined> => {
    const [status, tails] = await Promise.all([
      statusOf(id),
      tailsOf(id, marks),
    ]);
    return status === undefined
      ? undefined
      : questioned(treeFrom(status, tails));
  };

  /**
   * This account's live runs, newest first, or nothing when a listing was refused. One listing
   * per live state ({@link listingLive}), so a root that has been going a while is not listed
   * out behind newer runs.
   */
  const listLive = async (): Promise<Run[] | undefined> =>
    liveRunsOf(await Promise.all(listingLive().map((ask) => settled(ask))));

  /** The panel as the last reads left it, on the clock as it is now. */
  const drawPanel = (): void => {
    surface.panel?.(
      liveTrees.length === 0
        ? undefined
        : describePanel(
            liveTrees,
            Date.now(),
            zone,
            surface.columns?.() ?? DEFAULT_COLUMNS,
          ),
    );
  };

  /**
   * Whether a listing is being read. A push that lands meanwhile may be about a tree it has
   * read already, or about to, and cannot be judged against trees that are not in yet — so it
   * is read again after it.
   */
  let panelListingNow = false;

  /** Every live tree, from a listing; a refused listing keeps the last. */
  const listPanel = async (): Promise<void> => {
    panelListingNow = true;
    try {
      const runs = await listLive();
      if (runs === undefined) {
        return;
      }
      const trees = await Promise.all(
        liveRoots(runs).map((run) => treeOf(run.id, PANEL_MILESTONES)),
      );
      liveTrees = trees.filter((tree): tree is Tree => tree !== undefined);
    } finally {
      panelListingNow = false;
    }
  };

  /**
   * A held tree after a line was recorded in it: the record's tails first, and its status only
   * when a milestone came with them — the viewer's rule. A stage or a phase moves only with a
   * milestone, and a tool line, two pushes a call, is most of what a busy tree records. Kept as
   * it was when the tails were refused, and undefined when the status then was.
   */
  const recordedIn = async (held: Tree): Promise<Tree | undefined> => {
    const tails = await tailsOf(held.run.id, PANEL_MILESTONES);
    if (tails === undefined) {
      return held;
    }
    if (tails.milestones.at(-1)?.ordinal === held.milestones.at(-1)?.ordinal) {
      return questioned(treeFrom(held, tails), held.question);
    }
    const status = await statusOf(held.run.id);
    return status === undefined
      ? undefined
      : questioned(treeFrom(status, tails));
  };

  /** The trees of `roots` again, in their places — whole, or for `recorded` ones the record
   *  first ({@link recordedIn}): one that is no longer live leaves the panel, and one whose
   *  status cannot be read keeps what it was. */
  const rereadPanel = async (
    asked: readonly string[],
    recorded: readonly string[],
  ): Promise<void> => {
    const held = (root: string): Tree | undefined =>
      liveTrees.find((tree) => tree.run.id === root);
    const whole = asked.filter((root) => held(root) !== undefined);
    const tails = recorded.filter(
      (root) => !asked.includes(root) && held(root) !== undefined,
    );
    const read = new Map(
      await Promise.all([
        ...whole.map(
          async (root) => [root, await treeOf(root, PANEL_MILESTONES)] as const,
        ),
        ...tails.map(
          async (root) => [root, await recordedIn(held(root) as Tree)] as const,
        ),
      ]),
    );
    liveTrees = liveTrees.flatMap((tree) => {
      if (!read.has(tree.run.id)) {
        return [tree];
      }
      const again = read.get(tree.run.id);
      return again === undefined
        ? [tree]
        : LIVE_STATES.includes(again.run.state)
          ? [again]
          : [];
    });
  };

  /** What the pushes since the last pass asked to be read: a listing, trees by root, and trees
   *  by root a line was recorded in, which are read record first. */
  let panelListing = false;
  const panelRoots = new Set<string>();
  const panelRecords = new Set<string>();
  let panelling: Promise<void> | undefined;
  /**
   * One pass over what was asked, or — with one already running — what was asked is picked up
   * by its next pass: {@link checkWaiting}'s shape, so a burst of pushes costs two reads and
   * not one each. A listing reads every tree, so it takes the roots asked beside it.
   */
  const refreshPanel = (): Promise<void> => {
    if (panelling !== undefined) {
      return panelling;
    }
    panelling = (async () => {
      try {
        while (panelListing || panelRoots.size > 0 || panelRecords.size > 0) {
          const listing = panelListing;
          const roots = [...panelRoots];
          const recorded = [...panelRecords];
          panelListing = false;
          panelRoots.clear();
          panelRecords.clear();
          await (listing ? listPanel() : rereadPanel(roots, recorded));
          drawPanel();
        }
      } catch {
        // Quiet, as the waiting check is: the next push reads again.
      } finally {
        panelling = undefined;
      }
    })();
    return panelling;
  };
  if (watchingWaiting && surface.panel !== undefined) {
    panelRecorded = (root) => {
      // A tree the panel does not hold is not one it shows: a root is listed by the
      // `orchestration.changed` its start pushes, not by its first line.
      if (panelListingNow || liveTrees.some((tree) => tree.run.id === root)) {
        panelRecords.add(root);
        background(refreshPanel());
      }
    };
    panelChanged = (changed) => {
      const reads = panelReads(changed, liveTrees);
      if (reads === undefined && !panelListingNow) {
        return;
      }
      if (reads === undefined || reads === 'list') {
        panelListing = true;
      } else {
        panelRoots.add(reads.root);
      }
      background(refreshPanel());
    };
    panelTick = () => {
      if (liveTrees.length > 0) {
        drawPanel();
      }
    };
    panelListing = true;
    background(refreshPanel());
  }

  /*
   * `/watch`: THE VIEWER. Spec 2026-09-28, the orchestration record §5. Opens on the tail with
   * tool activity; reads earlier on demand; follows `orchestration.recorded` for its tree; Esc
   * puts it away. A surface without a viewer prints the tail once.
   *
   * <p><b>One read at a time, and each applied to the viewer as it stands when it lands.</b> A
   * push's read, an earlier page and a filter's fresh tail all rewrite what the viewer holds; run
   * side by side, a tail read with the old kinds could land after the filter's and put tool lines
   * back under "milestones", or a filter's tail replace rows a push had just brought.
   *
   * <p><b>And the keys never wait on one.</b> A read is queued and draws when it lands; the next
   * key is taken at once. An ask has no timeout, so a key loop that awaited a read that never
   * answered would drop Esc with it, and hold the person in the viewer.
   */

  /**
   * The run `/watch` opens when none is named: the newest live root — the panel's, or a listing's
   * when there is no panel — else the newest root; else the newest run, which the record resolves
   * to its tree's root. Nothing for an account with no runs; the refusal for one whose runs
   * could not be listed, which is not the same thing.
   */
  const rootToWatch = async (): Promise<
    { readonly id: string } | { readonly refused: Answer } | undefined
  > => {
    const held = liveTrees[0]?.run.id;
    if (held !== undefined) {
      return { id: held };
    }
    const answers = await Promise.all(listingLive().map((ask) => settled(ask)));
    const live = liveRunsOf(answers);
    if (live === undefined) {
      const refused = answers.find((answer) => runsOf(answer) === undefined);
      return refused === undefined ? undefined : { refused };
    }
    const newestLive = liveRoots(live)[0]?.id;
    if (newestLive !== undefined) {
      return { id: newestLive };
    }
    const listed = await settled(listingRuns());
    const runs = runsOf(listed);
    if (runs === undefined) {
      return { refused: listed };
    }
    const newest =
      runs.find((run) => run.parent === undefined)?.id ?? runs[0]?.id;
    return newest === undefined ? undefined : { id: newest };
  };

  /** @param wanted asked once the record is read, before the viewer opens: false opens none */
  const watchRun = async (
    named: string | undefined,
    wanted?: () => boolean,
  ): Promise<void> => {
    const found = named === undefined ? await rootToWatch() : { id: named };
    if (found === undefined) {
      show('client', describeNothingToWatch());
      return;
    }
    if ('refused' in found) {
      show('trouble', refusal(found.refused));
      return;
    }
    const target = found.id;
    const first = await settled(readingRecordTail(target));
    const opened = recordPageOf(first);
    if (opened === undefined) {
      show('trouble', refusal(first));
      return;
    }
    let state: Watch = watchingFrom(opened, true);
    // The status half alone: the header draws no activity or milestones, the body is the record.
    const treeNow = async (): Promise<Tree | undefined> => {
      const status = await statusOf(state.root);
      return status === undefined ? undefined : treeFrom(status, undefined);
    };
    let tree = await treeNow();
    if (wanted?.() === false) {
      return;
    }
    /** Why the last key's read was refused, until the next key. */
    let said: string | undefined;
    /**
     * The viewer as it is drawn now — worded only as far as the surface has room for, which it
     * says of the frame around the body (the header, the keys, a refusal), so the frame is
     * worded twice and the body once.
     */
    const viewed = (): Viewed => {
      const now = Date.now();
      const lost = outcomeLost(tree);
      const columns = surface.columns?.() ?? DEFAULT_COLUMNS;
      const frame = describeWatch(state, tree, now, zone, said, {
        room: 0,
        lost,
        columns,
      });
      const room = surface.viewBody?.(frame);
      return describeWatch(
        state,
        tree,
        now,
        zone,
        said,
        room === undefined ? { lost, columns } : { room, lost, columns },
      );
    };
    const view = surface.view?.bind(surface);
    const viewKey = surface.viewKey?.bind(surface);
    if (view === undefined || viewKey === undefined) {
      // NO KEYS TO READ EARLIER WITH, so no line that says to press one: the header and the
      // tail, and where the record begins when the tail reaches it.
      const once = viewed();
      showPlain(
        'client',
        [...once.head, ...(state.more ? once.body.slice(1) : once.body)].map(
          plainOf,
        ),
      );
      return;
    }
    /** Whether the viewer is still up: a read that lands after Esc draws nothing. */
    let open = true;
    const draw = (): void => {
      if (open) {
        view(viewed());
      }
    };
    let reading: Promise<void> = Promise.resolve();
    const serially = (work: () => Promise<void>): Promise<void> => {
      reading = reading.then(work).catch(() => undefined);
      return reading;
    };
    /** Whether a push's read is waiting its turn; pushes meanwhile are covered by it. */
    let growQueued = false;
    /** The tool lines pushes said were settled, which that read reads alone when they are
     *  further up than it reads from. */
    const settledLines = new Set<number>();
    const grow = (): void => {
      if (growQueued) {
        return;
      }
      growQueued = true;
      background(
        serially(async () => {
          // Cleared as it starts and not as it ends: a push during this read may be about a
          // row it has already missed, so it earns one more.
          growQueued = false;
          // CUT SHORT, AND BACK AT THE END: the end afresh, as `f` reads it.
          if (state.cut === true && followingEnd(state)) {
            settledLines.clear();
            await latest();
            return;
          }
          let after = followFrom(state.rows, state.through);
          // Judged before the forward read moves `after`: a line it covers is read with it.
          const alone = settledToRead(state, settledLines, after);
          settledLines.clear();
          let milestone = false;
          // CUT SHORT, SCROLLED UP: nothing below what it holds is read — the rows it let go
          // lie between — and the lines it holds that were settled still are, alone.
          while (state.cut !== true) {
            const fresh = recordPageOf(
              await settled(
                readingRecordAfter(state.root, after, kindsOf(state)),
              ),
            );
            if (fresh === undefined) {
              break;
            }
            state = watchGrew(state, fresh);
            milestone ||= fresh.rows.some((row) => !row.tool);
            // A full page is not the end of what was written: `through` is the record's
            // highest, not the read's, and reading on from it would leave a gap.
            const last = fresh.rows.at(-1)?.ordinal;
            if (fresh.rows.length < RECORD_TAIL || last === undefined) {
              break;
            }
            after = last;
          }
          // A LINE SETTLED FAR ABOVE THE END — a long delegation's `agent_run`, with the
          // delegate's hundreds of lines after it — is out of the forward read's reach, and
          // is read alone, by the ordinal its push named.
          for (const ordinal of alone) {
            const row = recordPageOf(
              await settled(readingToolLine(state.root, ordinal)),
            )?.rows.find((each) => each.ordinal === ordinal);
            if (row !== undefined) {
              state = watchSettled(state, row);
            }
          }
          // A STAGE OR A PHASE MOVES ONLY WITH A MILESTONE, so a tool line — two pushes a
          // call — costs no status read.
          if (milestone) {
            tree = (await treeNow()) ?? tree;
          }
          draw();
        }),
      );
    };
    watchPushed = (recorded) => {
      if (recorded.root === state.root) {
        if (recorded.settled !== undefined) {
          settledLines.add(recorded.settled);
        }
        grow();
      }
    };
    /**
     * The end afresh, for a viewer that was cut short ({@link Watch.cut}) and follows the end
     * again: the tail it opened with, in the kinds it reads now. Applied only if it still
     * follows when the read lands — a person who scrolled up meanwhile keeps their place, and
     * following again reads it again.
     */
    const latest = async (): Promise<void> => {
      const tools = state.tools;
      const answer = await settled(
        readingRecordTail(state.root, kindsOf({ tools })),
      );
      const page = recordPageOf(answer);
      if (page === undefined) {
        said = refusal(answer);
      } else if (followingEnd(state) && state.tools === tools) {
        state = watchingFrom(page, tools);
        tree = (await treeNow()) ?? tree;
      }
      draw();
    };
    /** Whether a read of the end afresh is waiting its turn; pushes and keys meanwhile are that one. */
    let latestQueued = false;
    const readLatest = (): void => {
      if (latestQueued) {
        return;
      }
      latestQueued = true;
      background(
        serially(async () => {
          latestQueued = false;
          if (state.cut === true && followingEnd(state)) {
            await latest();
          }
        }),
      );
    };
    /** Whether an earlier page is waiting its turn; more presses meanwhile are that one. */
    let earlierQueued = false;
    const readEarlier = (): void => {
      if (earlierQueued) {
        return;
      }
      earlierQueued = true;
      background(
        serially(async () => {
          earlierQueued = false;
          const oldest = oldestOf(state);
          if (oldest === undefined || !state.more) {
            return;
          }
          const answer = await settled(
            readingRecordEarlier(state.root, oldest, kindsOf(state)),
          );
          const page = recordPageOf(answer);
          if (page === undefined) {
            said = refusal(answer);
          } else {
            state = watchEarlier(state, page);
          }
          draw();
        }),
      );
    };
    /**
     * The filter asked for, which the viewer's changes to when its tail has been read — so the
     * keys line never names a filter the rows are not. Pressed twice before that, it is back
     * where it was and nothing is read.
     */
    let wantedTools = state.tools;
    let refilterQueued = false;
    const refilter = (): void => {
      if (refilterQueued) {
        return;
      }
      refilterQueued = true;
      background(
        serially(async () => {
          refilterQueued = false;
          const tools = wantedTools;
          if (tools === state.tools) {
            return;
          }
          const answer = await settled(
            readingRecordTail(state.root, kindsOf({ tools })),
          );
          const page = recordPageOf(answer);
          if (page === undefined) {
            said = refusal(answer);
            wantedTools = state.tools;
          } else {
            state = watchingFrom(page, tools);
          }
          draw();
        }),
      );
    };
    watchTick = draw;
    draw();
    try {
      for (;;) {
        const key = await viewKey();
        if (key === undefined) {
          break;
        }
        said = undefined;
        // Scrolled by the lines each row is drawn in: a row with a body under it is more than one.
        const room = surface.viewRoom?.() ?? 1;
        const next = watchKeyed(
          state,
          key,
          room,
          watchRowHeight(state, room, surface.columns?.() ?? DEFAULT_COLUMNS),
        );
        if (next.then === 'close') {
          break;
        }
        // ENTER OPENS THE CURSOR ROW'S RUN in the explorer, on that row's actor — the
        // conductor's own log, landed where the actor's delegation is. The viewer is put
        // away meanwhile, and what its reads bring is held until it is back.
        if (next.then === 'open') {
          const row = watchCursor(state);
          const run =
            row === undefined
              ? undefined
              : [
                  tree?.run,
                  ...(tree?.phases ?? []).map((phase) => phase.run),
                ].find((each) => each?.id === row.run);
          const conversation = run?.conductorConversation;
          if (
            row !== undefined &&
            run !== undefined &&
            conversation !== undefined &&
            conversation.length > 0
          ) {
            open = false;
            view(undefined);
            const shown = await explore(exploring(), {
              conversation,
              label: run.definition,
              view: 'trajectory',
              land: row.actor === 'conductor' ? 'end' : { agent: row.actor },
            });
            open = true;
            if (shown === 'refused') {
              said = describeTrajectoryUnreadable(conversation);
            }
          } else {
            said = describeNothingToOpen();
          }
          draw();
          continue;
        }
        if (next.then === 'refilter') {
          wantedTools = !wantedTools;
          refilter();
        } else {
          state = next.watch;
          if (next.then === 'earlier') {
            readEarlier();
          } else if (next.then === 'latest') {
            readLatest();
          }
        }
        draw();
      }
    } finally {
      // NOT WAITING ON A READ IN FLIGHT: Esc is a person leaving, and a slow answer would
      // hold them in the viewer. What lands after is dropped by `open`.
      open = false;
      watchPushed = () => undefined;
      watchTick = () => undefined;
      view(undefined);
    }
  };

  /*
   * THE CAP DIALOG. Spec 2026-09-29 §2: a run of the person's that stopped at a cap asks them and
   * its parent model at once, and the first answer settles it. On a surface that has one, the
   * question is a modal over the composer — `y` continue, `n` stop, `a` always, `w` watch, esc
   * later; on one that reads lines, it is printed with its keys and the next line answers.
   *
   * <p><b>And every other question the person can answer</b> (`dialogKindOf`): a run asking
   * whether it goes on — `y` go on, `n` stop — and a root run's own question, which its caller
   * model has too and which is answered in words: `r` leaves `/answer <id> ` in the composer.
   * Measured 2026-09-29: a root's question went to the bot that started it, and the person saw
   * only a cut line in the record until they thought to type `/answer`.
   *
   * <p><b>Up only while the line loop waits at the prompt</b>, one at a time, the rest queued:
   * never over a turn, whose Ctrl-C it would take and whose stream it would interleave with; never
   * over `/watch`, which holds the keys; never over a password or a confirmation, whose line it
   * would take. A line that arrives while one is up is handled as ever, and the dialog comes up
   * again at the next prompt.
   */

  /** Whether a dialog's milestone or question is being read — the one moment it is neither queued nor up. */
  let readingMilestone = false;
  /** Whether a dialog's key is being acted on: a `w` has the viewer up, a `y` its answer out. */
  let acting = false;
  /** Whether the line loop is waiting at the prompt: the only time a dialog comes up. */
  let atPrompt = false;
  /** Whether the question is printed and answered with a line rather than drawn and keyed. */
  const byLine = surface.capDialog === undefined;

  /**
   * The latest milestone of one run, as the record words it, or nothing. The record is the whole
   * tree's, so the page is the widest one read and this run's own rows are picked out of it.
   */
  const lastMilestone = async (id: string): Promise<string | undefined> => {
    const page = recordPageOf(
      await settled(readingRecordTail(id, MILESTONE_KINDS, RECORD_TAIL)),
    );
    const row = page?.rows.filter((each) => each.run === id).at(-1);
    return row === undefined ? undefined : plainOf(describeRecorded(row, zone));
  };

  /** The auto-continue each dialog's `a` was shown, by run: what `a` then writes. */
  const alwaysShown = new Map<string, number>();

  /** Put up the next queued question, when nothing else holds the prompt. */
  const openDialogs = async (): Promise<void> => {
    if (readingMilestone || acting || dialogUp !== undefined || !atPrompt) {
      return;
    }
    const run = dialogs.shift();
    if (run === undefined) {
      return;
    }
    const kind = dialogKindOf(run) ?? 'cap';
    if (kind === 'approval' && run.approval !== undefined) {
      await askApproval(run, run.approval);
      return;
    }
    readingMilestone = true;
    let milestone: string | undefined;
    let always = 0;
    let asked = run;
    try {
      if (kind === 'cap') {
        milestone = await lastMilestone(run.id);
        // The count `a` will write, read now so the label says it (re-review) and `a` then
        // writes exactly what the person was shown.
        always = await alwaysValue();
      } else if (run.question === undefined) {
        // The check's read of it failed: once more, since the question is the dialog.
        asked = await withQuestion(run);
      }
    } finally {
      readingMilestone = false;
    }
    if (kind === 'cap') {
      alwaysShown.set(run.id, always);
    }
    // THE READ TOOK A MOMENT, in which a line may have left the prompt — the question waits for
    // the next one — or a check found it settled, which only the waiting list now says.
    if (!waiting.some((each) => each.id === run.id)) {
      background(openDialogs());
      return;
    }
    if (!atPrompt || acting || dialogUp !== undefined) {
      dialogs.unshift(run);
      return;
    }
    // A QUESTION WITH OPTIONS OPENS IN THE MODAL WHERE THERE IS ONE, answered a key at a time;
    // on a surface that reads lines it is shown in words and answered in words, as ever.
    if (
      kind === 'question' &&
      asked.structure !== undefined &&
      !byLine &&
      surface.questionDialog !== undefined
    ) {
      await askQuestions(asked, asked.structure);
      return;
    }
    // DRAWN AS A LIST, THE KEYS LINE IS A ROW PER OPTION AND A HINT: the question is fitted to
    // the room those extra rows leave, so the whole dialog fits, not only its question.
    const listed =
      !byLine && surface.questionDialog !== undefined && kind !== 'approval';
    const room = surface.dialogRoom?.();
    const lines =
      kind === 'cap'
        ? describeCapDialog(run, milestone, byLine, always)
        : describeQuestionDialog(
            asked,
            byLine,
            surface.columns?.() ?? DEFAULT_COLUMNS,
            listed && room !== undefined
              ? room - DIALOG_KEYS[kind].length
              : room,
          );
    dialogUp = asked;
    if (byLine) {
      for (const line of lines) {
        show('client', line);
      }
      return;
    }
    // THE SAME MODAL AS A QUESTION WITH OPTIONS, where the surface has one (spec
    // 2026-09-29-orchestration-studio §2.5, decision (c)): the question's own lines, its keys as
    // a list. What each answer sends is unchanged — `actingOn` takes the same key it always did.
    const key = listed
      ? await pickedIn(asked, lines.slice(0, -1), dialogOptionsOf(kind, always))
      : // A CAP'S KEYS GO UNSAID, so a surface that predates the others is asked as it always was.
        await (kind === 'cap'
          ? surface.capDialog?.(lines)
          : surface.capDialog?.(lines, DIALOG_KEYS[kind]));
    // TAKEN DOWN BY SOMEBODY ELSE — settled elsewhere, or a line came first — who says what next.
    if (dialogUp !== asked) {
      return;
    }
    dialogUp = undefined;
    if (key !== undefined) {
      await actingOn(asked, key);
    }
  };

  /** A harness question's key, picked from its list in the modal; `undefined` if the modal went away. */
  const pickedIn = async (
    run: Waiting,
    lines: readonly string[],
    options: readonly DialogOption[],
  ): Promise<DialogKey | undefined> => {
    let state: Picking = pickingAbout(options);
    let again = false;
    while (state.kind === 'picking') {
      const stroke = await surface.questionDialog?.(
        describePickingDialog(lines, state),
        again,
      );
      if (dialogUp !== run || stroke === undefined) {
        return undefined;
      }
      state = pickingOn(state, stroke);
      again = true;
    }
    return state.key;
  };

  /**
   * THE APPROVAL DIALOG (item 5, measured 2026-09-29: "NONE of these requests are reaching a
   * modal"). A command — or an acceptance set — waiting to be allowed in one of the person's runs
   * is put to them as every other question is, one at a time, and answered with the approval
   * prompt they already know: `approval.ts`'s states and keys, `o` `c` `p` `d`, the arrows and
   * esc, a key at a time — `p` only for one command, a set taking `o`, `c` or `d`. The answer
   * goes the way `/answer <id> once` sends it. On a surface with no modal for it the question is
   * printed with its keys and the next line answers it, read as `strokesOf` reads it.
   */
  const approvalByLine = surface.approvalDialog === undefined;

  const askApproval = async (
    run: Waiting,
    approval: Approval,
  ): Promise<void> => {
    if (!waiting.some((each) => each.id === run.id)) {
      background(openDialogs());
      return;
    }
    let state: Asking = askingAbout(approval);
    dialogUp = run;
    if (approvalByLine) {
      approvalAsking = state;
      for (const line of describeApprovalDialog(
        approval,
        state,
        true,
        surface.columns?.() ?? DEFAULT_COLUMNS,
      )) {
        show('client', line);
      }
      return;
    }
    let again = false;
    while (!settledAsking(state)) {
      const stroke = await surface.approvalDialog?.(
        describeApprovalDialog(
          approval,
          state,
          false,
          surface.columns?.() ?? DEFAULT_COLUMNS,
          surface.dialogRoom?.(),
        ),
        again,
      );
      // TAKEN DOWN BY SOMEBODY ELSE — settled elsewhere, or a line came first — who says what next.
      if (dialogUp !== run) {
        return;
      }
      if (stroke === undefined) {
        dialogUp = undefined;
        return;
      }
      state = pressingOn(state, stroke);
      again = true;
    }
    dialogUp = undefined;
    await actingOnApproval(state);
  };

  /**
   * An approval's dialog settled: an answer sent as `/answer <id> …` sends it, and said as that
   * says it; esc left it in the waiting list, where `/answer` still reaches it. The next question
   * is put up after.
   */
  const actingOnApproval = async (state: Asking): Promise<void> => {
    acting = true;
    try {
      if (state.kind === 'answered') {
        show('person', describeDecided(state));
        const said = await settled(state.ask);
        const answered = answeredOf(said);
        if (answered === undefined) {
          show('trouble', refusal(said));
        } else {
          show(
            'client',
            answered.busy
              ? describeAnswered(answered)
              : describeApprovalAnswered(answered.id, answered.state),
          );
        }
        background(checkWaiting());
      }
    } finally {
      acting = false;
    }
    background(openDialogs());
  };

  /**
   * A question with options from one of the person's runs (spec 2026-09-29-orchestration-studio
   * §2.5), put to them in the modal and answered a key at a time — `questions.ts`'s states —
   * then sent as its choices. Esc leaves it in the waiting list, where `/answer` still reaches it
   * in words. The Studio's install question opens its whole draft on `v` ({@link viewDraft}),
   * where the surface has a viewer to show it in.
   */
  const askQuestions = async (
    run: Waiting,
    structure: Structure,
  ): Promise<void> => {
    let state: Answering = answeringAbout(run.id, structure);
    dialogUp = run;
    let again = false;
    const viewable =
      surface.view !== undefined && surface.viewKey !== undefined;
    while (!settledAnswering(state)) {
      const stroke = await surface.questionDialog?.(
        describeQuestionsDialog(
          run,
          state,
          surface.columns?.() ?? DEFAULT_COLUMNS,
          surface.dialogRoom?.(),
          viewable,
        ),
        again,
      );
      // TAKEN DOWN BY SOMEBODY ELSE — settled elsewhere, or a line came first — who says what next.
      if (dialogUp !== run) {
        return;
      }
      if (stroke === undefined) {
        dialogUp = undefined;
        return;
      }
      // THE DRAFT IS READ, NOT ANSWERED: the question stays up to the rest of the client
      // meanwhile, and comes back in the state it was left in.
      const draft = viewable ? draftAsked(state, stroke) : undefined;
      if (draft === undefined) {
        state = answeringOn(state, stroke);
      } else {
        await viewDraft(draft);
        if (dialogUp !== run) {
          return;
        }
      }
      again = true;
    }
    dialogUp = undefined;
    await actingOnAnswers(run, state);
  };

  /**
   * An install question's draft read whole — `draftview.ts` — in the viewer `/watch` uses, until
   * esc or the end of input puts it away. The body is worded to the room the frame leaves it, as
   * `/watch` asks it of the frame, and scrolled by that room.
   */
  const viewDraft = async (draft: Draft): Promise<void> => {
    const view = surface.view?.bind(surface);
    const viewKey = surface.viewKey?.bind(surface);
    if (view === undefined || viewKey === undefined) {
      return;
    }
    let state = draftViewAbout(draft);
    const columns = (): number => surface.columns?.() ?? DEFAULT_COLUMNS;
    const room = (): number | undefined =>
      surface.viewBody?.(describeDraftView(state, columns(), 0));
    try {
      while (!state.closed) {
        view(describeDraftView(state, columns(), room()));
        const key = await viewKey();
        if (key === undefined) {
          return;
        }
        state = draftViewOn(state, key, room() ?? 1, columns());
      }
    } finally {
      view(undefined);
    }
  };

  /**
   * A question with options settled: its choices sent and said; esc sent nothing. A refusal is
   * said, and — once the check has the run still asking, so not answered by somebody else first —
   * the modal is put up again ahead of the rest, its question to answer afresh.
   */
  const actingOnAnswers = async (
    run: Waiting,
    state: Answering,
  ): Promise<void> => {
    acting = true;
    try {
      if (state.kind === 'answered') {
        const said = await settled(state.ask);
        const now = settledRun(said);
        if (now === undefined) {
          show('trouble', refusal(said));
          await checkWaiting();
          if (
            waiting.some((each) => each.id === run.id) &&
            !dialogs.some((each) => each.id === run.id)
          ) {
            dialogs.unshift(run);
          }
        } else {
          show('client', describeSettled(now, 'answered'));
          background(checkWaiting());
        }
      }
    } finally {
      acting = false;
    }
    background(openDialogs());
  };

  /** A dialog's key acted on, and the next question put up after it. */
  const actingOn = async (run: Waiting, key: DialogKey): Promise<void> => {
    acting = true;
    try {
      await actOnDialog(run, key);
    } finally {
      acting = false;
    }
    background(openDialogs());
  };

  /**
   * The `a` key's `auto-continue`: at least `ALWAYS_CAPS`, never below what is in effect
   * now — read from the server, which knows both files — so `a` never lowers a count the
   * person set higher (final review). Unknown, it is `ALWAYS_CAPS`.
   */
  const alwaysValue = async (): Promise<number> => {
    const claim = rooting.current();
    const now =
      claim === undefined
        ? undefined
        : capsOf(await settled(readingCaps(claim.project)))?.autoContinue.value;
    return alwaysAutoContinue(now);
  };

  /** What a key in the dialog, or a lone letter on a surface that reads lines, does. */
  const actOnDialog = async (run: Waiting, key: DialogKey): Promise<void> => {
    // Later: it stays in the waiting list, where `/answer` reaches it, and is not asked again.
    if (key === 'later') {
      return;
    }
    // REPLY: THE WORDS ARE THE PERSON'S. `/answer` and the id are left where they type, and the
    // question stays waiting — sent, it goes the way every `/answer` goes.
    if (key === 'reply') {
      const command = `${ANSWER_COMMAND} ${run.id} `;
      if (!byLine && surface.prefill !== undefined) {
        surface.prefill(command);
      } else {
        show('client', describeReplyWith(run.id));
      }
      return;
    }
    if (key === 'watch') {
      // The prompt may have been left while the record was read — a line sent, a turn begun —
      // and a viewer opened over that would take the keys that stop it.
      await watchRun(run.id, byLine ? undefined : () => atPrompt);
      // A LOOK BEFORE DECIDING, so the question comes back once the viewer is put away —
      // unless it was settled while they looked.
      if (waiting.some((each) => each.id === run.id)) {
        dialogs.unshift(run);
      }
      return;
    }
    // `a` IS `y` AND A SETTING: with the setting not made, the `yes` alone would be an answer
    // the person did not give, so nothing is sent and the question is put up again, the line
    // above it saying why.
    if (
      key === 'always' &&
      !(await setCap(
        'auto-continue',
        alwaysShown.get(run.id) ?? (await alwaysValue()),
      ))
    ) {
      dialogs.unshift(run);
      return;
    }
    alwaysShown.delete(run.id);
    // A STUCK RUN IS ANSWERED AS `/answer <id> go on` AND STOPPED AS `/cancel <id>`: its `n` is
    // not a "no" it would read as words to act on, but the run ended. A PERSON'S ACCEPT
    // QUESTION'S `y` — a product check, a checker's concerns (V77) — IS `/answer <id> accept`;
    // anything else it is answered with is the person's words.
    // A FAILING CHECK'S QUESTION (V69) IS ANSWERED IN ITS OWN WORDS: `y` is `/answer <id> go on`,
    // which starts the count over, and `n` is `/answer <id> stop`, which the server caps it on.
    const kind = dialogKindOf(run);
    const stuck = kind === 'stuck';
    const cancelling = stuck && key === 'stop';
    const said = await settled(
      cancelling
        ? cancellingRun(run.id)
        : answeringRun(
            run.id,
            stuck
              ? 'go on'
              : kind === 'accept'
                ? 'accept'
                : kind === 'checks'
                  ? key === 'stop'
                    ? 'stop'
                    : 'go on'
                  : key === 'stop'
                    ? 'no'
                    : 'yes',
          ),
    );
    const now = settledRun(said);
    // A REFUSAL IS THE SERVER'S SENTENCE: when the parent model answered first, it says who
    // and with what (`Orchestrations.answeredAlready`), which is all a person needs of it.
    if (now === undefined) {
      show('trouble', refusal(said));
    } else {
      show(
        'client',
        describeSettled(now, cancelling ? 'cancelled' : 'answered'),
      );
    }
    background(checkWaiting());
  };

  /** A question whose dialog is up was answered by someone else first: taken away, and said. */
  const settledElsewhere = async (run: Waiting): Promise<void> => {
    dialogUp = undefined;
    if (run.kind === 'approval') {
      // Answered by `/answer`, or withdrawn — its run ended, or its set changed. Which, the
      // listing does not say; that it is no longer the person's to answer is what matters.
      approvalAsking = undefined;
      if (!approvalByLine) {
        surface.closeDialog?.();
      }
      show('client', describeApprovalSettled(run.id));
      background(openDialogs());
      return;
    }
    if (!byLine) {
      surface.closeDialog?.();
    }
    const status = runStatusOf(await settled(readingRun(run.id)));
    const answer = status?.messages
      .filter((each) => each.kind === 'answer')
      .at(-1);
    if (answer !== undefined) {
      show(
        'client',
        dialogKindOf(run) === 'cap'
          ? describeCapSettled(run.id, answer.author, answer.text)
          : describeQuestionSettled(run.id, answer.author, answer.text),
      );
    }
    background(openDialogs());
  };

  dialogsChanged = (asking) => {
    if (dialogUp !== undefined && !asking.has(dialogUp.id)) {
      background(settledElsewhere(dialogUp));
      return;
    }
    background(openDialogs());
  };

  /** A question answered or cancelled with a line of its own is not put up again. */
  const forgetDialog = (id: string): void => {
    const at = dialogs.findIndex((each) => each.id === id);
    if (at >= 0) {
      dialogs.splice(at, 1);
    }
  };

  /*
   * THE LOG OF THE CONVERSATION ON SCREEN, AS THE SOURCE OF WHAT IS ON IT.
   * Spec 2026-09-28-the-log-is-the-source §3-§5.
   */

  /**
   * How far down the followed conversation's log this screen has shown — the spec's
   * `shownThrough` — and the furthest a `conversation.appended` has said the log reaches.
   *
   * <p><b>`shown` is nothing when the fill was refused</b>: none of the log is on the screen,
   * so there is no ordinal to catch up from, and the next push — or the end of this client's
   * own turn — runs the fill again rather than holding every push for ever.
   *
   * <p><b>`oldest` and `more` are the top of the screen, as `shown` is the bottom</b>: the
   * smallest ordinal drawn, which `/earlier` reads before, and whether the log holds anything
   * drawn before it. `/earlier` moves them and never `shown`.
   */
  interface Tail {
    readonly conversation: string;
    shown: number | undefined;
    known: number;
    oldest: number | undefined;
    more: boolean;
  }
  /** The conversation on screen's log, once it is followed; nothing before that. */
  let tail: Tail | undefined;
  /**
   * How far the log is known to reach: the furthest a push has said, or the screen has shown —
   * `shown` counts only once a fill has set it, since it holds at the top of the range before.
   */
  const reachOf = (at: Tail): number =>
    Math.max(
      at.known,
      at.shown === undefined || at.shown === Number.MAX_SAFE_INTEGER
        ? 0
        : at.shown,
    );
  /** Whether a turn of this client's own is between its start and its settling. */
  let streamingOwn = false;
  /**
   * How many turns of this client's own have begun. <b>`streamingOwn` alone cannot say that one
   * came and went</b>: a read begun before a turn and answered after it has settled finds it
   * false again, and would draw the turn its stream already drew.
   */
  let begun = 0;
  /** Catch-ups, one at a time, each reading from where the last one left the screen. */
  let catching: Promise<void> = Promise.resolve();

  /**
   * Follow a conversation: pushes about its log come to this socket from now on. Until the
   * screen is filled, `shown` holds at the top of the range, so a push that lands first is
   * remembered and not drawn — the reading that fills the screen covers it.
   */
  const follow = async (conversation: string): Promise<void> => {
    tail = {
      conversation,
      shown: Number.MAX_SAFE_INTEGER,
      known: 0,
      oldest: undefined,
      more: false,
    };
    // NOT FATAL AND NOT SAID. A server too old to know the frame refuses it, and this
    // client then shows what its own turns stream, exactly as it always did.
    await settled(followingLog(conversation));
  };

  /**
   * The screen now shows the followed log through `through` — or, when the fill was refused,
   * none of it.
   *
   * <p><b>And catches up at once on what a push said lies beyond.</b> A push that landed
   * while a long log filled page by page was held, and nothing will push for it again.
   */
  const shownTo = (conversation: string, through: number | undefined): void => {
    if (tail === undefined || tail.conversation !== conversation) {
      return;
    }
    tail.shown = through;
    if (streamingOwn) {
      return;
    }
    // A fill that was refused tries once more at once when a push landed while it read: that
    // push was held for the fill, and nothing will push for it again. Once — a second refusal
    // leaves the screen unfilled for the next push or the next turn's end, not a loop.
    if (through === undefined ? tail.known > 0 : tail.known > through) {
      background(catchUp(false));
    }
  };

  /** What the log says, put on the screen by the one renderer replay and catch-up share. */
  const rendering = (items: readonly Logged[]): void => {
    for (const item of items) {
      if (item.kind === 'person') {
        show('client', describeSpokenIn(item.turn));
        show('person', item.text);
      } else if (item.kind === 'harness') {
        const said = describeHarness(item.source, item.text);
        show('client', said.heading);
        if (said.rest !== '') {
          show('client', said.rest);
        }
      } else if (item.kind === 'answer') {
        showBody(
          'bot',
          item.body,
          item.cut === undefined
            ? undefined
            : describeCut(item.cut.shown, item.cut.length),
        );
      } else {
        show('client', describeSeam(item.fold));
        if (item.fold.summary.length > 0) {
          showBody('bot', item.fold.summary);
        }
      }
    }
  };

  /**
   * The tail of a conversation's log — the last {@link LOG_BACK} entries the chat draws, in one
   * read back from its end — in the order it happened, and how far the log reached when it was
   * read. What opening and a fill both draw.
   */
  const readingBack = async (
    conversation: string,
  ): Promise<BackPage | { readonly refused: Answer }> => {
    const asked = readingTail(conversation);
    const said = await connection.ask(asked.type, asked.payload);
    return backPageOf(said) ?? { refused: said };
  };

  /**
   * The screen's top is now where this tail read reached back to: remember it for `/earlier`,
   * and say above the tail that there is more before it.
   */
  const reachedBack = (at: Tail, read: BackPage): void => {
    at.oldest = read.oldest;
    at.more = read.more;
    if (read.more) {
      show('client', describeEarlierHint());
    }
  };

  /**
   * `/earlier`: the {@link LOG_BACK} drawn entries before the oldest on the screen, printed now
   * as a block labelled as earlier history, oldest first — a terminal's scroll only grows at
   * the bottom. <b>Never moves `shown`</b>: what the log gains is still counted from the
   * bottom of the screen, which this does not touch.
   */
  const earlier = async (): Promise<void> => {
    const at = tail;
    if (at === undefined) {
      show('client', describeNoEarlier());
      return;
    }
    if (at.shown === undefined || at.shown === Number.MAX_SAFE_INTEGER) {
      show('client', describeNotYetShown());
      return;
    }
    if (!at.more || at.oldest === undefined) {
      show('client', describeBeginning());
      return;
    }
    const said = await settled(readingEarlier(at.conversation, at.oldest));
    const read = backPageOf(said);
    if (read === undefined) {
      show('trouble', refusal(said));
      return;
    }
    // Another conversation came on the screen while this read: what came back is not its.
    if (tail !== at) {
      return;
    }
    show('client', describeEarlier(read.entries.length));
    rendering(loggedFrom(read.entries));
    at.oldest = read.oldest ?? at.oldest;
    at.more = read.more;
    if (!read.more) {
      show('client', describeBeginning());
    }
  };

  /** Every entry written to a log after an ordinal, page by page, or nothing on a refusal. */
  const readAfter = async (
    conversation: string,
    after: number,
  ): Promise<Entry[] | undefined> => {
    const rows: Entry[] = [];
    for (;;) {
      const page = await settled(
        readingAfter(conversation, after, rows.length),
      );
      if (page.code !== OK) {
        return undefined;
      }
      const got = entriesOf(page);
      rows.push(...got);
      if (got.length === 0 || rows.length >= logTotal(page)) {
        return rows;
      }
    }
  };

  /**
   * The explorer's reads over this socket: the tail and the page before an ordinal, every kind,
   * and everything after an ordinal page by page — the tracer's read, which narrows to no kind.
   */
  const explorerReads: Reads = {
    tail: async (conversation) =>
      backPageOf(await settled(readingLogTail(conversation))),
    before: async (conversation, ordinal) =>
      backPageOf(await settled(readingLogBefore(conversation, ordinal))),
    after: async (conversation, ordinal) => {
      const rows: Entry[] = [];
      for (;;) {
        const page = await settled(
          readingTrace(conversation, ordinal, rows.length),
        );
        if (page.code !== OK) {
          return rows.length === 0 ? undefined : rows;
        }
        const got = entriesOf(page);
        rows.push(...got);
        if (got.length === 0 || rows.length >= logTotal(page)) {
          return rows;
        }
      }
    },
    follow: async (conversation) => {
      await settled(followingLog(conversation));
    },
  };
  /**
   * What the explorer opens with, as it is now: `/trajectory` and `/log` call it, and `/watch`
   * will. <b>`restore`</b> is the conversation the chat follows, which a descent's follow of a
   * child replaces on the server and closing puts back.
   */
  const exploring = (): Exploring => ({
    surface,
    reads: explorerReads,
    zone,
    appended: (listener) => {
      appendedListeners.add(listener);
      return () => {
        appendedListeners.delete(listener);
      };
    },
    ...(tail?.conversation === undefined ? {} : { restore: tail.conversation }),
    print: (lines) => showPlain('client', lines),
  });

  /**
   * Whether a catch-up begun for `at` when `gen` turns of this client's own had begun has lost
   * the screen since: another conversation is on it, or a turn of this client's own is
   * streaming or has begun and settled. That turn's settling catches up from where this one
   * left the screen, so what this one read is dropped rather than drawn around that turn.
   */
  const overtaken = (at: Tail, gen: number): boolean =>
    tail !== at || begun !== gen || streamingOwn;

  /**
   * The fill a refused replay never did, done now: the same bounded read and the same
   * renderer. <b>`ownThrough`</b>: how far the log reached when a turn of this client's own
   * ended in this conversation — the latest turn up to there is the one its stream drew, and
   * is left out so that it is on the screen once.
   *
   * @returns whether the screen was filled
   */
  const filling = async (
    at: Tail,
    ownThrough: number | undefined,
  ): Promise<boolean> => {
    const gen = begun;
    const read = await readingBack(at.conversation);
    // Refused again: still unfilled, and the next chance tries again. A turn of this
    // client's own that began meanwhile has the screen; its settling fills it.
    if ('refused' in read || overtaken(at, gen)) {
      return false;
    }
    const own =
      ownThrough === undefined
        ? undefined
        : read.entries
            .filter((each) => each.ordinal <= ownThrough)
            .reduce((latest, each) => Math.max(latest, each.turnOrdinal), 0);
    reachedBack(at, read);
    rendering(
      replaying(read.entries, read.through).items.filter(
        (item) => item.kind === 'seam' || item.turn !== own,
      ),
    );
    at.shown = read.through;
    return true;
  };

  /**
   * Draw what the followed log gained since the screen last showed it, up to the furthest a
   * push has said it reaches. <b>`streamed`</b>: a turn of this client's own just ended in
   * this conversation, and its stream already drew it — see `catchingUp`. <b>`ownThrough`</b>:
   * how far the log reached when that stream ended, which splits what it drew from what
   * landed after it.
   */
  const caughtUp = async (
    streamed: boolean,
    ownThrough: number | undefined,
    queued: number,
  ): Promise<void> => {
    const at = tail;
    const gen = begun;
    // Queued before a turn of this client's own and reached while it streams — or after it
    // has settled, when `streamingOwn` is false again but `begun` has moved: that turn's
    // settling queued its own catch-up behind this one, and this one, not knowing the turn
    // was streamed, would draw it a second time.
    if (at === undefined || streamingOwn || gen !== queued) {
      return;
    }
    let held = streamed;
    if (at.shown === undefined) {
      if (!(await filling(at, streamed ? ownThrough : undefined))) {
        return;
      }
      // The fill has left the streamed turn out already.
      held = false;
    } else if (held && (ownThrough === undefined || ownThrough <= at.shown)) {
      // THE PUSH FOR THE TURN THAT STREAMED WAS LOST: when it ended, no push had said the
      // log reached past what the screen already showed. So how far it reaches is read now,
      // from the turn's end (spec §4), and all of it counted as shown — the stream drew
      // the turn, and taking whatever a later push brings for it would draw it twice.
      const reached = logThrough(
        await settled(readingAfter(at.conversation, at.shown, 0, 1)),
      );
      if (overtaken(at, gen)) {
        return;
      }
      if (reached !== undefined) {
        at.shown = Math.max(at.shown, reached);
      }
      held = false;
    }
    const from = at.shown;
    const through = at.known;
    if (from === undefined || through <= from) {
      return;
    }
    const read = await readAfter(at.conversation, from);
    // A refusal leaves the screen where it was: the next push, or a turn's end, tries again.
    // A turn of this client's own that began during the read has the screen until it
    // settles, and the catch-up then reads this stretch again.
    if (read === undefined || overtaken(at, gen)) {
      return;
    }
    // Held here, the ending's reach went past the screen: only what it covers can be the
    // streamed turn — the latest up to it — and what landed after it is drawn whole.
    const split =
      held &&
      ownThrough !== undefined &&
      ownThrough > from &&
      ownThrough < through;
    const items = split
      ? [
          ...catchingUp(read, from, ownThrough, true).items,
          ...catchingUp(read, ownThrough, through, false).items,
        ]
      : catchingUp(read, from, through, held).items;
    rendering(items);
    at.shown = through;
  };

  /** One catch-up after another, never two reading the same stretch at once. */
  const catchUp = (streamed: boolean, ownThrough?: number): Promise<void> => {
    const queued = begun;
    catching = catching
      .then(() => caughtUp(streamed, ownThrough, queued))
      .catch(() => undefined);
    return catching;
  };

  /**
   * A turn of this client's own has ended: let pushes through again, and catch up on what
   * they said while it streamed — counting the turn it streamed as shown only when that turn
   * was in the conversation on screen (a `/diagnose` streams in Daedalus's), and only when a
   * run was handed over at all.
   */
  const settle = async (
    conversation: string,
    carried: Carried,
  ): Promise<void> => {
    streamingOwn = false;
    await catchUp(
      carried.ran && tail?.conversation === conversation,
      carried.through,
    );
  };

  grew = (grown) => {
    const at = tail;
    if (at === undefined || grown.conversation !== at.conversation) {
      return;
    }
    // THE TRACER READS EVEN WHILE THIS CLIENT'S OWN TURN STREAMS, which is the point of it: a
    // call is drawn pending the moment the log says it was asked.
    if (trace !== undefined) {
      background(traceNow(grown.conversation));
    }
    at.known = Math.max(at.known, grown.through);
    // WAITS WHILE A TURN OF THIS CLIENT'S OWN STREAMS (spec §4): `settle` catches up then.
    // An unfilled screen is filled by the first push that says there is a log to fill it.
    if (!streamingOwn && (at.shown === undefined || at.known > at.shown)) {
      background(catchUp(false));
    }
  };
  reach = () => tail?.known;

  /**
   * `/schedule <sentence>`: read it, show the whole proposal, and save it
   * only if the very next line is a yes.
   *
   * <h3>The pending confirmation is this function waiting on the prompt</h3>
   *
   * <p>The same shape {@link changing} takes for a password: the loop is
   * inside this call, so the next line a person enters is read here and never
   * reaches the dispatch below. <b>Anything but `y` or `yes` cancels</b> — and
   * a command or a sentence typed instead is not run, because a person who
   * typed it did not see it was being read as the answer; they are told so
   * and asked to type it again.
   */
  const schedule = async (
    text: string,
    project?: string,
    conversation?: string,
  ): Promise<'ended' | undefined> => {
    const read = await settled(
      readingSchedule(text, zone, project, conversation),
    );
    const proposal = proposalOf(read);
    if (proposal === undefined) {
      show('trouble', refusal(read));
      return;
    }
    showPlain('client', describeProposal(proposal));
    const reply = await surface.asked();
    if (reply === undefined) {
      // The input ended at the question: nothing is saved, and the
      // session ends here as it would have at the prompt.
      return 'ended';
    }
    const answered = reply.trim();
    if (answered !== '') {
      show('person', answered);
    }
    if (!answeredYes(answered)) {
      const declined = ['', 'n', 'no'].includes(answered.toLowerCase());
      show('client', describeNotSaved(!declined));
      return;
    }
    const defined = await settled(savingSchedule(proposal, project));
    if (
      defined.code !== OK ||
      (defined.payload as { status?: string } | undefined)?.status !== 'active'
    ) {
      show(
        'trouble',
        defined.code === OK
          ? String(
              (defined.payload as { error?: string })?.error ??
                'The saved schedule file was refused',
            )
          : refusal(defined),
      );
      show(
        'client',
        'The schedule file request was not replayed. Use /schedule files to inspect what was saved.',
      );
      return;
    }
    showPlain('client', [describeSaved(proposal)]);
    return undefined;
  };

  /** Both listings, or nothing after saying why one of them was refused. */
  const scheduled = async (): Promise<
    { schedules: Schedule[]; triggers: Trigger[] } | undefined
  > => {
    const listed = await settled(listingSchedules());
    const schedules = schedulesOf(listed);
    if (schedules === undefined) {
      show('trouble', refusal(listed));
      return undefined;
    }
    const heard = await settled(listingTriggers());
    const triggers = triggersOf(heard);
    if (triggers === undefined) {
      show('trouble', refusal(heard));
      return undefined;
    }
    return { schedules, triggers };
  };

  /**
   * `/schedule pause|resume|forget <name>`: the schedule and every trigger
   * listening to its event.
   *
   * <p><b>A name the listing does not have is still sent</b>, so that the
   * server's own NOT_FOUND sentence is what a person reads. The schedule
   * goes first whenever its clock is being stopped — paused or forgotten —
   * and last when it is resumed, so no tick fires into an event nobody is
   * listening to.
   */
  const manage = async (
    verb: 'pause' | 'resume' | 'forget',
    name: string,
  ): Promise<void> => {
    const both = await scheduled();
    if (both === undefined) {
      return;
    }
    const found = both.schedules.find((each) => each.name === name);
    const fileReply = await settled({ type: 'schedule.files', payload: {} });
    if (fileReply.code !== OK) {
      show('trouble', refusal(fileReply));
      return;
    }
    const managed = decodeReply('schedule.files', fileReply.payload).some(
      (file) => file.internalName === name,
    );
    const listening =
      managed || found === undefined ? [] : listeningTo(found, both.triggers);
    const asks: Ask[] =
      verb === 'forget'
        ? [
            forgettingSchedule(name),
            ...listening.map((each) => forgettingTrigger(each.name)),
          ]
        : verb === 'pause'
          ? [
              pausingSchedule(name, true),
              ...listening.map((each) => pausingTrigger(each.name, true)),
            ]
          : [
              ...listening.map((each) => pausingTrigger(each.name, false)),
              pausingSchedule(name, false),
            ];
    for (const ask of asks) {
      const done = await settled(ask);
      if (done.code !== 'NO_CONTENT' && done.code !== OK) {
        show('trouble', refusal(done));
        return;
      }
    }
    show(
      'client',
      verb === 'forget'
        ? describeForgot(name)
        : describePaused(name, verb === 'pause'),
    );
  };

  /** `/fire <name>`: the named schedule's event, emitted now. */
  const fire = async (name: string): Promise<void> => {
    const listed = await settled(listingSchedules());
    const schedules = schedulesOf(listed);
    if (schedules === undefined) {
      show('trouble', refusal(listed));
      return;
    }
    const found = schedules.find((each) => each.name === name);
    if (found === undefined) {
      show('client', describeNoSchedule(name));
      return;
    }
    const fired = await settled(firingEvent(found.emits));
    const firings = firingsOf(fired);
    if (firings === undefined) {
      show('trouble', refusal(fired));
      return;
    }
    showPlain('client', describeFired(found.emits, firings));
  };

  /** Where a person is: the tier, who answers there, and what they are already saying. */
  interface Place {
    readonly project?: string;
    readonly who: Agent;
    conversation?: string;
  }
  /** Where a person asked to go. */
  interface Target {
    readonly project?: string;
    /** Present when arriving means lending this machine's files. */
    readonly claim?: Claim;
  }
  type Entered = 'entered' | 'root-refused' | 'declined';
  let place: Place | undefined;
  hereNow = () => place?.conversation;
  /**
   * Daedalus's continuing conversation in each tier, once this client has
   * found or opened it. A diagnosis must stay out of the conversation it is
   * examining, but a second diagnosis must stay in the first diagnostic
   * conversation: that history is where an operator's correction and a
   * narrower follow-up become evidence rather than a brand-new prompt.
   */
  const diagnostics = new Map<string, Conversation>();
  /**
   * Whether `job.stream` has gone out. Once per session: the subscription is
   * the socket's and not the tier's, and a start that is retried in the global
   * tier after a refused root would otherwise ask for it twice.
   */
  let subscribed = false;

  /** How full the conversation at {@link place} was when last asked. */
  let load: Load = {};

  /** How the last run at {@link place} went at the model, or nothing yet. */
  let pace: Pace | undefined;

  /**
   * Put who is being talked to, and how full that is, on the status line.
   *
   * <p>The model the context was priced under when there is one, and the
   * roster's otherwise: the roster is what is known before a conversation
   * exists, and a conversation does not exist until somebody speaks.
   */
  const stand = (): void => {
    if (surface.status === undefined || place === undefined) {
      return;
    }
    const model = load.model ?? place.who.model;
    surface.status(
      describeStanding({
        who: place.who.name,
        server: talking.door.base,
        ...(model === undefined ? {} : { model }),
        ...(load.sent === undefined ? {} : { sent: load.sent }),
        ...(load.limit === undefined ? {} : { limit: load.limit }),
        ...(pace === undefined ? {} : { pace }),
        ...(syncNotice === undefined ? {} : { sync: syncNotice }),
      }),
    );
  };

  /**
   * Ask how full the conversation is now, and say so.
   *
   * <p><b>Only for a surface that draws it</b> — see {@link Surface.status}.
   * <b>And never a failure</b>: a refusal, or a server too old to know the
   * frame, leaves the line as it was. A status line is not a reason to put
   * trouble in the scrollback, and a socket that really went away is said by
   * the next ask that matters.
   */
  const measure = async (): Promise<void> => {
    const here = place;
    if (surface.status === undefined || here?.conversation === undefined) {
      return;
    }
    const asking = measuring(here.conversation, here.who.name);
    const said = await connection
      .ask(asking.type, asking.payload)
      .catch(() => undefined);
    const read = said === undefined ? undefined : loadOf(said);
    // A move made while this was out is a different place, and its line is
    // not this answer's to overwrite.
    if (read !== undefined && place === here) {
      load = read;
      stand();
    }
  };

  /**
   * Arrive somewhere, or change nothing. Spec §6.1.
   *
   * <p>Order: root (if the target lends files), ask who answers there, ask
   * what they were already saying — and only then move. Every failure after
   * the root puts the previous rooting back, so a refused move leaves the
   * person where they were, with the files they had.
   *
   * <p><b>Startup is this with `starting`</b>, and keeps the banner it always
   * had: the choice line after the roster (and the streaming request), before
   * the conversation is looked up, exactly where it was said before a place
   * existed. A move says where it landed instead, once it has landed.
   */
  const enter = async (target: Target, starting: boolean): Promise<Entered> => {
    const before = rooting.current();
    const moving =
      target.claim !== undefined && !sameClaim(before, target.claim);
    if (moving && target.claim !== undefined) {
      // THE OLD UNION PUSHES WHAT IT HAS WHILE ITS CLAIM IS STILL HELD:
      // `root` lets go of the old claim before it tries the new one.
      await unsyncing();
      try {
        await rooting.root(target.claim);
      } catch (refused) {
        show(
          'trouble',
          describeRootRefused(
            target.claim.project,
            refused instanceof Error ? refused.message : undefined,
          ),
        );
        // THE ROOTER LET GO OF THE OLD CLAIM BEFORE IT TRIED THE NEW ONE,
        // so an open that failed has left nothing held. Put back what the
        // person had — best effort, because a second failure has nothing
        // further to fall back to and `onLost` is not involved in either.
        if (before !== undefined) {
          await rooting.root(before).then(
            () => syncing(before),
            () => undefined,
          );
        }
        return 'root-refused';
      }
      await syncing(target.claim);
    }
    const undo = async (): Promise<void> => {
      if (!moving) {
        return;
      }
      await unsyncing();
      if (before === undefined) {
        await rooting.release();
      } else {
        await rooting.root(before).then(
          () => syncing(before),
          () => undefined,
        );
      }
    };
    // WHO ANSWERS, SETTLED BEFORE ANYTHING IS CREATED.
    //
    // <b>One `agent.list` at sign-in, and it does three jobs.</b> It
    // validates the name a person gave; it is what `whoAnswers` reads a
    // default off, so `PLOWSHARE_AGENT` is no longer required; and it is
    // the data Task 5's completion needs.
    //
    // <b>The first of those is a measured bug and not a tidying.</b>
    // `PLOWSHARE_AGENT=no_such_bot` left `cnv_31355ABEA50EA42E` behind
    // against a real server, with zero model calls: `AgentRunHandler`
    // refuses an unknown name and refuses it well — it lists what the
    // server serves — but `agent.run` comes after `conversation.open`, so
    // the refusal arrives with the junk row already written. A read that
    // creates nothing, asked first, is the whole fix.
    //
    // <b>And it is before the banner</b>, which is the order a person
    // needs: who they are talking to, then that nothing is kept yet.
    const roster = listingAgents(target.project);
    const declared = await connection.ask(roster.type, roster.payload);
    const rows = agents(declared);
    if (rows === undefined) {
      // The server's own sentence. A client that fell back to a name
      // here would be guessing who answers out of a refusal.
      show('trouble', refusal(declared));
      await undo();
      return 'declined';
    }
    // A ROOTING LOST WHILE LISTING. `root` waited for the claim to land, so a
    // refusal has already rejected it above; what can still arrive here is
    // a later close — a server restart, a server too old to say the claim
    // landed and so waited out — and if it has, onLost has said why.
    if (moving && !sameClaim(rooting.current(), target.claim)) {
      await undo();
      return 'root-refused';
    }
    // ASKED FOR, OR NOTHING ARRIVES. The server sends deltas only to a
    // session that subscribed -- that is what keeps the firehose off the
    // MCP adapter and the console -- so a client that rendered them and
    // never sent this frame would have the whole feature built and switched
    // off. It was, for exactly as long as it took to notice.
    //
    // After the roster rather than before: this is the second frame of the
    // session either way, and putting it here keeps sign-in's own ordering
    // -- who answers, then what is kept -- unchanged.
    if (starting && !subscribed && talking.tokens === true) {
      subscribed = true;
      const wanted = streaming(true);
      const allowed = await connection.ask(wanted.type, wanted.payload);
      if (allowed.code !== OK) {
        // NOT FATAL, AND THAT IS THE POINT OF ASKING SEPARATELY. A
        // server too old to know this frame refuses it and answers
        // everything else exactly as before; a client that ended the
        // session here would refuse to talk to a server that works.
        // `trouble`, like every other refusal this client renders.
        // It is not fatal -- the conversation goes on without the
        // preview -- but it IS the server declining something that was
        // asked for, and dressing that as this client talking about
        // itself would put it in the wrong voice.
        show('trouble', refusal(allowed));
      }
    }
    const chosen = whoAnswers(rows, talking.agent);
    if (chosen.kind !== 'answering') {
      // Every other arm is a reason there will be no conversation, and
      // each of them has been reached with nothing opened and nothing
      // spent. `wording.ts` says which — and, for a move, where the
      // person is staying instead.
      const lines =
        starting || place === undefined
          ? describeChoice(chosen)
          : describeRefusedMove(
              target.project,
              chosen,
              place.project === undefined
                ? { who: place.who.name }
                : { project: place.project, who: place.who.name },
              moving && before === undefined && target.claim !== undefined,
            );
      const [first, ...rest] = lines;
      show('client', first ?? '');
      if (rest.length > 0) {
        show('client', toListing(rest));
      }
      await undo();
      return 'declined';
    }
    if (starting) {
      show('client', describeChoice(chosen)[0] ?? '');
    }
    let going: Conversation | undefined;
    // WHAT THIS BOT WAS ALREADY SAYING, WHICH IS WHAT MAKES IT A BOT.
    //
    // <b>A bot has one conversation and talking to it continues that
    // one</b> (spec §3). Which one is the server's to derive — the newest
    // in this tier it has answered in — so this asks and does not guess:
    // a client holding an id of its own would be a stored pointer, and a
    // stored pointer can disagree with what the archive has.
    //
    // <b>Only for a bot.</b> Naming an agent is the specialised path and a
    // unit of work: an agent's answer may be a structure rather than a
    // sentence, the relationship is over when the job is, and joining a
    // person silently onto an old work log is not continuity, it is a
    // conversation they did not ask to be in.
    //
    // <b>A refusal ends the session rather than starting a second
    // thread.</b> It is the roster's rule one line up, met again: a client
    // that opened a new conversation out of a refusal would be guessing
    // that there is no history — and branching, with everything it drags
    // in, is exactly what this design does not have. (For a move, "ends"
    // is "does not happen": the person stays where they were.)
    if (chosen.agent.bot) {
      const asking = continuing(chosen.agent.name, target.project);
      const answered = await connection.ask(asking.type, asking.payload);
      if (answered.code !== OK) {
        show('trouble', refusal(answered));
        await undo();
        return 'declined';
      }
      // Nothing, for a bot nobody has spoken to here — an OK carrying no
      // payload, which is the ordinary first run and not a refusal.
      going = continued(answered);
    }
    // AND ASKED AGAIN, LAST. The check after the roster covers a close that
    // landed while listing; `conversation.latest` is one more round trip, and
    // a rooting lost during it would otherwise commit a move onto a channel
    // that is already gone. Nothing awaits between here and the commit.
    if (moving && !sameClaim(rooting.current(), target.claim)) {
      await undo();
      return 'root-refused';
    }
    // COMMITTED. Nothing below can refuse the move.
    //
    // AND IT IS WHAT TAB COMPLETES, which is the fourth job this one frame
    // does and the decision task 3 left open. The completer is handed the
    // roster sign-in already has rather than asking for one of its own: Tab
    // is pressed at typing speed, and a completer that made a round trip
    // would stall a prompt mid-word for a reason nothing on screen
    // explains. `/commands` and `/skills` refresh this catalog explicitly;
    // until then, completion uses only names the server has declared.
    surface.completing(
      completable(rows),
      Object.fromEntries(
        rows
          .filter((row) => row.served)
          .map((row) => [row.name, row.description]),
      ),
      (chosen.agent.commands ?? []).map((command) => ({
        name: command.command,
        detail: `${command.description} · ${command.mode ?? (command.kind === 'skill' ? 'specify --mode=INHERITED|SUMMARISED|NEW|DIRECT' : 'orchestration')} · ${command.executor}`,
      })),
    );
    if (!starting) {
      const changed =
        place !== undefined && place.who.name !== chosen.agent.name;
      show(
        'client',
        describeMovedTo(target.project, chosen.agent.name, changed),
      );
    }
    place = {
      ...(target.project === undefined ? {} : { project: target.project }),
      who: chosen.agent,
      ...(going === undefined ? {} : { conversation: going.id }),
    };
    load = {};
    pace = undefined;
    stand();
    if (going !== undefined) {
      await follow(going.id);
      await showing(going);
      await measure();
    } else {
      // Nothing on screen has a log yet; a push about the last place is not this one's.
      tail = undefined;
      // <b>Nothing exists on the server until somebody says something.</b>
      //
      // This used to open a conversation before printing the first prompt,
      // which meant opening this client and quitting -- reading the banner,
      // changing your mind, mistyping the agent name -- left a row behind
      // with no turns in it. Measured rather than supposed: a run stopped
      // before its first answer left `cnv_3134E666E2D847AD` in
      // `GET /v1/conversations`, which is the list a console opens on.
      //
      // A terminal is not the console, and that is the reason for the
      // difference. The console is a place you go to *look at* conversations,
      // so it opens one deliberately from a list. A terminal is a place you
      // go to *have* one, and every front end shaped like this -- Claude,
      // ChatGPT -- starts you in a chat that is not real yet and makes it
      // real with the first message. Deferring the open is what makes
      // starting this client free.
      //
      // The cost, stated because it is real: a refusal to open now lands
      // after somebody has typed rather than before. That is the same
      // trade those front ends make, and it is the better half of it --
      // the alternative charges every start for a failure that almost never
      // happens.
      //
      // AND IT IS SAID ONLY WHEN IT IS TRUE. The banner has to say which of
      // the two things happened, so a start that picked a conversation up
      // prints its own opening line above and this one not at all — a client
      // that said both would be claiming to have continued something and to
      // be holding nothing, in two consecutive lines.
      show('client', describeNew());
    }
    return 'entered';
  };

  /**
   * Start the union lifecycle for a claim just rooted. `connect` is not
   * awaited: a union that is not one answers `union.status` and does
   * nothing, and one that is reconciles behind the prompt, saying trouble
   * as trouble lines and conflicts on the status line.
   */
  const syncing = async (claim: Claim): Promise<void> => {
    if (clientProject(claim.project)) {
      await unsyncing();
      return;
    }
    await unsyncing();
    const made: Syncer = syncer({
      claim,
      handle: loginHandle,
      base: talking.door.base,
      asker: connection,
      bearer: () =>
        renewing(async () => {
          tokens = await (talking.door.renew?.() ??
            refresh(talking.door, tokens));
          return tokens.access;
        }),
      tell: (trouble, lines) => {
        for (const line of lines) {
          show(trouble ? 'trouble' : 'client', line);
        }
      },
      standing: (notice) => {
        // A syncer already let go of — a leave that outlived its
        // patience, a tick it had queued — no longer speaks for the line.
        if (sync !== made) {
          return;
        }
        syncNotice = notice;
        stand();
      },
    });
    sync = made;
    background(made.connect());
  };
  /**
   * Push what a union has and stop its timer, before the claim is let go.
   *
   * <p><b>Bounded, because a quit must not hang on a hub.</b> `leave` waits
   * behind whatever the syncer is doing — a whole reconcile — and then
   * pushes; git's own timeout is minutes. After {@link LEAVE_PATIENCE_MS}
   * this returns and the leave carries on unwatched. The syncer is detached
   * synchronously, before any wait, so a late leave cannot put it back.
   */
  const unsyncing = async (): Promise<void> => {
    const leaving = sync;
    sync = undefined;
    syncNotice = undefined;
    stand();
    if (leaving === undefined) {
      return;
    }
    let patience: ReturnType<typeof setTimeout> | undefined;
    const waited = new Promise<void>((done) => {
      patience = setTimeout(done, LEAVE_PATIENCE_MS);
      patience.unref?.();
    });
    await Promise.race([leaving.leave(), waited]);
    clearTimeout(patience);
  };
  /** `/sync …`: only a project rooted on this machine has a syncer to ask. */
  const syncRun = async (action: SyncAction): Promise<void> => {
    if (place?.project && clientProject(place.project)) {
      show(
        'trouble',
        'Client-only DISJOINT projects cannot be synced or exported',
      );
      return;
    }
    if (sync === undefined) {
      show(
        'trouble',
        'sync needs a project rooted on this machine — /here roots this directory',
      );
      return;
    }
    await sync.run(action);
  };

  /**
   * `/always`: set the rooted project's local command mode to `open`, or with `off` to `ask`, in
   * its JSON manifest or legacy `.plowshare/environment.yml` — both server and client read it on
   * every run, so the setting stays in the one place anyone would look for it.
   *
   * <p><b>Turned on, it also allows what was already waiting</b> — once, each local approval
   * whose working directory is inside this project. Those were asked before the file said
   * anything, and a run stopped on one would otherwise sit until somebody answered it.
   */
  async function always(on: boolean): Promise<void> {
    const claim = rooting.current();
    if (claim === undefined) {
      show('client', describeAlwaysNeedsAProject());
      return;
    }
    let file: string;
    try {
      const before = await readCapsFile(claim.root);
      file = before.file;
      await saveSettingsFile(
        claim.root,
        before,
        localModePreview(before, on ? OPEN : ASK),
      );
    } catch (trouble) {
      show(
        'trouble',
        `Project command configuration could not be updated (${errorMessage(trouble)})`,
      );
      return;
    }
    let allowed = 0;
    if (on) {
      const mine = listingMyApprovals();
      const open = approvalsOf(await settled(mine)) ?? [];
      for (const approval of open) {
        const inside = relative(claim.root, approval.cwd);
        const within =
          inside === '' || (!inside.startsWith('..') && !isAbsolute(inside));
        if (
          approval.state !== 'asked' ||
          approval.side !== 'local' ||
          !within
        ) {
          continue;
        }
        const answer = answeringApproval(approval.id, 'once');
        if (answeredOf(await settled(answer)) !== undefined) {
          allowed++;
        }
      }
    }
    show('client', describeAlways(claim.project, file, on, allowed));
    background(checkWaiting());
  }

  /** `/cap`: the caps as the server reads them now, applied to the runs already going. */
  async function showCaps(): Promise<void> {
    const project = rooting.current()?.project ?? place?.project;
    if (project === undefined) {
      show('client', describeCapNeedsAProject());
      return;
    }
    const answer = await settled(readingCaps(project));
    const caps = capsOf(answer);
    show(
      caps === undefined ? 'trouble' : 'client',
      caps === undefined
        ? (answer.said ?? 'the caps could not be read')
        : describeCaps(caps),
    );
  }

  /**
   * `/cap steps|budget|auto|time|checks N` and `/always caps`: caps in the rooted project's
   * JSON manifest or legacy `.plowshare/environment.yml` — then the server reads it again
   * and applies it to the runs already going.
   *
   * @returns whether the file now says it — the dialog's `a` sends its `yes` only then
   */
  async function setCap(key: CapKey, value: number): Promise<boolean> {
    const claim = rooting.current();
    if (claim === undefined) {
      show('client', describeCapNeedsAProject());
      return false;
    }
    // OUT OF RANGE IS THE VALUE'S FAULT, NOT THE FILE'S: written, it made a file that did not
    // parse, and the line blamed the file (Task 14's review).
    const { least, most } = capRange(key);
    if (value < least || value > most) {
      show('trouble', describeCapOutOfRange(key, value, least, most));
      return false;
    }
    let file: string;
    try {
      const before = await readCapsFile(claim.root);
      file = before.file;
      await saveCapsFile(claim.root, before, key, value);
    } catch (trouble) {
      show(
        'trouble',
        `Project cap configuration could not be written (${errorMessage(trouble)})`,
      );
      return false;
    }
    show(
      'client',
      describeCapSet(
        file,
        key,
        value,
        capsOf(await settled(readingCaps(claim.project))),
      ),
    );
    return true;
  }

  /** `/here [name]`: make the directory a person stands in a project, and root it. */
  const rootHere = async (named: string | undefined): Promise<void> => {
    if (directory === undefined) {
      show('client', describeNoDirectory());
      return;
    }
    const detected = await discover(directory);
    if (detected?.kind === 'DISJOINT') {
      if (named && named !== detected.project) {
        show('trouble', 'The manifest belongs to a different project');
        return;
      }
      const found = await resolveMarked(detected, connection, machine);
      await enter(
        {
          project: found.project,
          claim: { project: found.project, root: found.root, machine },
        },
        false,
      );
      return;
    }
    const root = directory;
    const existing = await markedName(root);
    if (existing !== undefined && named !== undefined && existing !== named) {
      show('client', describeAlreadyMarked(root, existing, named));
      return;
    }
    const name = named ?? existing ?? namedAfter(root);
    const claim: Claim = { project: name, machine, root };
    // ALREADY IN IT MEANS THE TIER TOO, not merely the claim. A remote
    // `/project` moves the tier and keeps this rooting, so the same claim
    // can be held while a person stands in another project — and `/here`
    // then means "back to this one", which `enter` does over the channel
    // already open (the claim is the same, so nothing is re-rooted).
    if (sameClaim(rooting.current(), claim) && place?.project === name) {
      show('client', describeAlreadyIn(name));
      return;
    }
    if ((await enter({ project: name, claim }, false)) !== 'entered') {
      return;
    }
    // THE MARKER LAST, once somebody has answered. A refused move leaves
    // no file behind that the next start would find and try again.
    try {
      await mark(root, name);
      show('client', describeRooted(name, root));
    } catch (trouble) {
      show(
        'trouble',
        describeUnmarked(
          root,
          trouble instanceof Error ? trouble.message : errorMessage(trouble),
        ),
      );
    }
  };

  /** `/project <name>`: move to a tier the server knows, rooting it only when it is here. */
  const toProject = async (name: string): Promise<void> => {
    if (place?.project === name) {
      show('client', describeAlreadyIn(name));
      return;
    }
    const listed = listingProjects();
    const answer = await connection.ask(listed.type, listed.payload);
    const rows = projects(answer);
    if (rows === undefined) {
      show('trouble', refusal(answer));
      return;
    }
    const row = rows.find((each) => each.name === name);
    if (row === undefined) {
      show('client', describeNoSuchProject(name));
      return;
    }
    // RULING 11: rooted here only when the row, the directory and the
    // marker all agree. It never goes looking.
    const workspace = row.workspace;
    const local =
      directory !== undefined &&
      workspace !== undefined &&
      row.machine === machine &&
      within(directory, workspace) &&
      (await markedName(workspace)) === name;
    if (local && workspace !== undefined) {
      if (
        (await enter(
          { project: name, claim: { project: name, machine, root: workspace } },
          false,
        )) === 'entered'
      ) {
        show('client', describeRooted(name, workspace));
      }
      return;
    }
    if ((await enter({ project: name }, false)) === 'entered') {
      show('client', describeFilesElsewhere(name, row));
    }
  };

  /** `/cd <path>`: stand somewhere else, and root the project found there if it is a new one. */
  const cd = async (asked: string): Promise<void> => {
    const from = directory ?? process.cwd();
    const spelled =
      asked === '~' || asked.startsWith('~/')
        ? join(homedir(), asked.slice(1))
        : asked;
    let target: string;
    try {
      target = await realpath(resolve(from, spelled));
      if (!(await stat(target)).isDirectory()) {
        throw new Error('not a directory');
      }
    } catch {
      show('client', describeNotADirectory(asked));
      return;
    }
    directory = target;
    const detected = await discover(target);
    const found = detected
      ? await resolveMarked(detected, connection, machine)
      : undefined;
    const held = rooting.current();
    // STILL ROOTING IT, AND STANDING IN IT, is the only time a `/cd` inside
    // the held root changes nothing. After a remote `/project` the tier is
    // elsewhere, and walking back into the held root is walking back into
    // its project — `enter` with the same claim, so nothing is re-rooted.
    if (
      found === undefined ||
      (held !== undefined &&
        held.root === found.root &&
        place?.project === held.project)
    ) {
      show('client', describeMoved(target, held?.project));
      return;
    }
    const claim: Claim = { project: found.project, machine, root: found.root };
    if ((await enter({ project: found.project, claim }, false)) === 'entered') {
      show('client', describeRooted(found.project, found.root));
    }
  };

  const recoveringJobs = new Map<string, string>();
  const uncertainConversations = new Set<string>();
  let reconnecting: Promise<void> | undefined;
  let recoveryTimer: ReturnType<typeof setInterval> | undefined;
  let recoveryReading = false;
  let recoveryClosing = false;
  const recoveryNotices = new Set<string>();
  const reconcileRecovered = async () => {
    if (recoveryClosing || connectionLost || recoveryReading) return;
    recoveryReading = true;
    try {
      for (const [job, conversation] of recoveringJobs) {
        const said = await connection.ask('job.status', { job });
        const status = jobStatusOf(said, job);
        if (
          !status ||
          (status.conversation != null && status.conversation !== conversation)
        ) {
          if (!recoveryNotices.has(job))
            show(
              'trouble',
              `Job ${job} cannot be reconciled; completion remains unknown.`,
            );
          recoveryNotices.add(job);
          continue;
        }
        const ended = reached(said, job);
        if (ended) {
          recoveringJobs.delete(job);
          if (ended.said.length)
            showBody(
              'bot',
              ended.said,
              `Recovered job ${job} · ${describeEnding(ended)}`,
            );
          else
            show('client', `Recovered job ${job} · ${describeEnding(ended)}`);
        }
      }
    } catch {
      /* The next read retries status only, never a submission. */
    } finally {
      recoveryReading = false;
    }
  };
  const recoverConnection = (): Promise<void> => {
    if (recoveryClosing || !connectionLost) return Promise.resolve();
    if (reconnecting) return reconnecting;
    if (!talking.reconnect) return Promise.reject(new Dropped());
    const retry = talking.reconnect;
    reconnecting = (async () => {
      show(
        'trouble',
        'Connection lost. Reconnecting; submitted work will not be sent again.',
      );
      const claim = rooting.current();
      await unsyncing();
      await rooting.release();
      for (
        let attempt = 0;
        attempt < retry.attempts && !recoveryClosing;
        attempt++
      ) {
        if (attempt)
          await new Promise((resolve) =>
            setTimeout(
              resolve,
              Math.min(10_000, retry.delayMs * 2 ** (attempt - 1)),
            ),
          );
        try {
          const epoch = jobs.generation;
          const opened = await renewing(() =>
            openSocket(
              {
                ...talking.door,
                onPush: (push) => {
                  if (jobs.current(epoch)) onPush(push);
                },
                onClose: () => {
                  if (jobs.current(epoch)) onClose();
                },
              },
              tokens,
            ),
          );
          if (recoveryClosing) {
            opened.connection.close();
            return;
          }
          wire = opened.connection;
          tokens = opened.tokens;
          connectionLost = false;
          turn = undefined;
          ending = undefined;
          dropped = undefined;
          if (talking.tokens) {
            const ask = streaming(true);
            await connection.ask(ask.type, ask.payload);
          }
          if (claim) {
            try {
              await rooting.root(claim);
              await syncing(claim);
            } catch (error) {
              show(
                'trouble',
                `Files could not be restored: ${errorMessage(error)}. Use /here to reconnect them.`,
              );
            }
          }
          if (place?.conversation) {
            await follow(place.conversation);
            const ask = listingConversations(place.project);
            const going = conversations(
              await connection.ask(ask.type, ask.payload),
            )?.find((row) => row.id === place?.conversation);
            if (going) await showing(going);
          }
          await checkWaiting();
          await measure();
          show(
            'client',
            'Reconnected. Known jobs are reconciled through status reads; uncertain submissions remain withheld.',
          );
          await reconcileRecovered();
          recoveryTimer ??= setInterval(() => {
            background(reconcileRecovered());
          }, 2000);
          recoveryTimer.unref?.();
          return;
        } catch (error) {
          wire.close();
          connectionLost = true;
          if (
            error instanceof SignInRefused ||
            error instanceof MustChangePassword
          )
            throw error;
          if (attempt + 1 === retry.attempts) throw new Dropped();
        }
      }
    })().finally(() => {
      reconnecting = undefined;
    });
    return reconnecting;
  };
  reconnectNow = recoverConnection;

  try {
    let start: Target =
      talking.project === undefined ? {} : { project: talking.project };
    // RULING 12: a named tier skips discovery. Otherwise the nearest marker
    // on the way up is the project, and with none the server is asked
    // whether it already knows this directory — offered, never rooted.
    if (talking.project === undefined && directory !== undefined) {
      const detected = await discover(directory);
      const found = detected
        ? await resolveMarked(detected, connection, machine)
        : undefined;
      if (found !== undefined) {
        start = {
          project: found.project,
          claim: { project: found.project, machine, root: found.root },
        };
      } else {
        const listed = listingProjects();
        const rows =
          projects(await connection.ask(listed.type, listed.payload)) ?? [];
        const personal = rows.find((row) => row.kind === 'personal');
        if (personal) {
          try {
            const store = await preparePersonalStore(
              talking.door.base,
              loginHandle,
              personal.name,
            );
            const root = store.root;
            if (store.warning) show('trouble', store.warning);
            start = {
              project: personal.name,
              claim: { project: personal.name, machine, root },
            };
          } catch (error) {
            start = { project: personal.name };
            show(
              'trouble',
              `Personal files could not be mounted: ${errorMessage(error)}`,
            );
          }
        }
        const standing = directory;
        const known = rows.find(
          (row) => row.machine === machine && row.workspace === standing,
        );
        show(
          'client',
          known === undefined
            ? describeUnrooted()
            : describeOffered(known.name, standing),
        );
      }
    }
    if (start.project === undefined && directory === undefined) {
      const listed = listingProjects();
      const personal = (
        projects(await connection.ask(listed.type, listed.payload)) ?? []
      ).find((row) => row.kind === 'personal');
      if (personal) start = { project: personal.name };
    }
    let arrived = await enter(start, true);
    if (arrived === 'root-refused') {
      // RULING 10: files refused is not talking refused.
      arrived = await enter(
        start.project?.startsWith('personal:')
          ? { project: start.project }
          : {},
        true,
      );
    } else if (arrived === 'entered' && start.claim !== undefined) {
      show('client', describeRooted(start.claim.project, start.claim.root));
    }
    if (arrived !== 'entered') {
      return;
    }
    for (;;) {
      try {
        if (connectionLost && talking.reconnect) await recoverConnection();
        atPrompt = true;
        background(openDialogs());
        const asked = await surface.asked();
        atPrompt = false;
        if (asked === undefined) {
          return;
        }
        if (connectionLost && talking.reconnect) await recoverConnection();
        const text = asked.trim();
        // A QUESTION'S DIALOG UP WHEN A LINE ARRIVES. Printed, a lone key of its own answers
        // it — an empty line is later — and anything else is a line like any other. Drawn, the
        // line is not its answer (the dialog held the keys; the line was typed ahead of it): it
        // is taken down and put up again at the next prompt.
        const up = dialogUp;
        // A COMMAND APPROVAL'S PRINTED DIALOG: the line is read as the approval prompt's keys.
        // One that moves it — `p`, an arrow, enter, `o`, `c`, `d`, esc or an empty line for
        // later — answers it, and one that leaves it where it was is a line like any other.
        if (
          up !== undefined &&
          up.kind === 'approval' &&
          up.approval !== undefined &&
          approvalByLine
        ) {
          const was = approvalAsking ?? askingAbout(up.approval);
          const strokes: Stroke[] =
            text === '' && was.kind === 'choosing'
              ? [{ kind: 'escape' }]
              : strokesOf(text);
          const next = strokes.reduce(pressingOn, was);
          if (next !== was) {
            if (text !== '') {
              show('person', text);
            }
            if (settledAsking(next)) {
              dialogUp = undefined;
              approvalAsking = undefined;
              await actingOnApproval(next);
            } else {
              approvalAsking = next;
              showPlain('client', describeKeys(next));
            }
            continue;
          }
          dialogUp = undefined;
          approvalAsking = undefined;
          dialogs.unshift(up);
        }
        if (up !== undefined && dialogUp === up) {
          dialogUp = undefined;
          const key = byLine
            ? dialogKeyOfLine(text, DIALOG_KEYS[dialogKindOf(up) ?? 'cap'])
            : undefined;
          if (key !== undefined) {
            if (text !== '') {
              show('person', text);
            }
            await actingOn(up, key);
            continue;
          }
          dialogs.unshift(up);
          surface.closeDialog?.();
        }
        if (text === '') {
          continue;
        }
        // WHAT THEY TYPED, AS AN ENTRY OF THEIR OWN.
        //
        // A terminal that redraws clears the composer on submit, so without
        // this the line vanishes and the transcript is one half of a
        // conversation. It was never needed before because readline's echo
        // stood in for it — an echo a piped stdin never had either, which is
        // why the old transcripts read as answers with no questions.
        //
        // Every line, commands included: `/help` is a thing a person did,
        // and a screen that showed the reply without the request would be
        // harder to read back than one that showed both.
        show('person', text);
        // WHAT THE LINE IS, BEFORE IT IS TREATED AS AN UTTERANCE. The
        // reading is `session.ts`'s, so this file goes on writing no dotted
        // type and no payload key; the three arms below are the order and
        // the printing, which is all this file ever owns.
        //
        // <b>Before the conversation is opened, deliberately.</b> Looking is
        // not speaking: somebody who types `/conversations` first is asking
        // what is there, and a client that opened one to answer would put
        // the row they were asking about into the list.
        const now = place;
        if (now === undefined) {
          return;
        }
        // A stalled run's id is left out of what a bare `/answer` can reach: it takes a
        // look or a cancel, never an answer in words, and guessing it as the one thing
        // waiting would hand a conductor a reply nobody meant for it.
        const answerable = waiting
          .filter((run) => run.kind !== 'stalled')
          .map((run) => run.id);
        const management = serverCommand(text, now.project);
        const direct =
          management.kind !== 'unhandled'
            ? management
            : text.trim().startsWith('/')
              ? parseDirect(text.trim().slice(1), now.project)
              : { kind: 'unhandled' as const };
        if (direct.kind === 'usage') {
          show('trouble', direct.said);
          continue;
        }
        if (direct.kind === 'request') {
          const result = await dispatch(connection, direct.request);
          if (result.kind === 'refused')
            show('trouble', refusal(result.outcome));
          else if (result.kind === 'invalid-response')
            show(
              'trouble',
              `${direct.request.type}: invalid response; completion is unknown`,
            );
          else {
            const detail =
              result.outcome.payload === undefined
                ? ''
                : `\n${JSON.stringify(result.outcome.payload, null, 2)}`;
            const follow =
              result.kind === 'accepted'
                ? `; /job status ${result.job} or /job cancel ${result.job}`
                : '';
            show(
              'client',
              `${direct.request.type}: ${result.kind}${follow}${detail}`,
            );
          }
          continue;
        }
        const line = typed(asked, now.project, answerable, {
          ...(now.conversation ? { conversation: now.conversation } : {}),
          agent: now.who.name,
        });
        if (line.kind === 'projects') {
          await list(line.ask, projects, describeProjects);
          continue;
        }
        if (line.kind === 'conversations') {
          await list(line.ask, conversations, describeConversations);
          continue;
        }
        if (line.kind === 'command-catalog') {
          const listed = await settled(line.ask);
          const rows = agents(listed);
          if (rows === undefined) {
            show('trouble', refusal(listed));
            continue;
          }
          const selected = rows.find(
            (row) => row.name === now.who.name && row.served,
          );
          surface.completing(
            completable(rows),
            Object.fromEntries(
              rows
                .filter((row) => row.served)
                .map((row) => [row.name, row.description]),
            ),
            (selected?.commands ?? []).map((command) => ({
              name: command.command,
              detail: `${command.description} · ${command.mode ?? (command.kind === 'skill' ? 'specify --mode=INHERITED|SUMMARISED|NEW|DIRECT' : 'orchestration')} · ${command.executor}`,
            })),
          );
          place = { ...now, who: selected ?? { ...now.who, commands: [] } };
          if (selected)
            showPlain('client', describeCommandCatalog(selected, line.only));
          else
            show(
              'trouble',
              `The selected agent ${now.who.name} is unavailable; no commands are offered.`,
            );
          continue;
        }
        // THE TWO ROSTERS, WHICH ARE ONE FRAME AND TWO SCREENS. Asked
        // again rather than served out of what sign-in saw: `agent.define`
        // exists, so a definition can arrive under a running client, and a
        // listing that showed a stale roster would be the one screen a
        // person goes to when something has just changed.
        if (line.kind === 'bots') {
          await list(line.ask, agents, describeBots);
          continue;
        }
        if (line.kind === 'agents') {
          await list(line.ask, agents, describeAgents);
          continue;
        }
        // ONE AGENT, WHOLE — the roster read down to a row. `agent.list` is
        // the only frame that carries `tools`, `calls`, `scopes` or
        // `orchestrations`, so there is no second frame to ask for one
        // agent, and a name nothing answers to is said by `describeAgent`
        // out of what came back rather than refused by a server.
        //
        // <b>`showPlain`, like the proposal.</b> These lines carry a
        // definition's own words and a grant written as `workspace:read`,
        // and the markdown grammar would quietly eat the punctuation in
        // text a person is reading to find out what an agent may do.
        if (line.kind === 'agent') {
          const roster = await settled(line.ask);
          const rows = agents(roster);
          if (rows === undefined) {
            show('trouble', refusal(roster));
          } else {
            showPlain('client', describeAgent(line.name, rows));
          }
          continue;
        }
        // ORCHESTRATIONS, WHICH ARE SCOPED BY WHERE A PERSON IS STANDING.
        // `typed` built the ask out of `now.project`, and the same project
        // is handed to the wording so the screen says which set it showed:
        // the server degrades an absent project — and an unreachable
        // archive — to the boot set, and the rows do not say which happened.
        if (line.kind === 'design') {
          try {
            const task = authoringRequest(line.text, line.revision);
            const roster = agents(await settled(listingAgents(now.project)));
            const definitions = definitionsOf(
              await settled(listingDefinitions(now.project)),
            );
            const caller =
              roster?.find(
                (row) =>
                  row.name === now.who.name &&
                  row.served &&
                  row.orchestrations.includes(AUTHORING),
              ) ??
              roster?.find(
                (row) => row.served && row.orchestrations.includes(AUTHORING),
              );
            const claim = rooting.current();
            authoringReady(
              now.project,
              claim !== undefined && claim.project === now.project,
              caller,
              definitions?.find((row) => row.name === AUTHORING),
            );
            if (
              line.revision &&
              !definitions?.some(
                (row) => row.name === line.revision && row.served,
              )
            )
              throw new Error('Choose an available definition to revise.');
            // A separate caller conversation makes the authoring launch traceable.
            const start = opening(now.project);
            const made = opened(await settled(start));
            if (!made)
              throw new Error(
                'Could not open the authoring conversation; no turn was submitted.',
              );
            show(
              'client',
              `authoring caller ${caller!.name} · conversation ${made.id}; /runs then /watch <run> follows the builder. /answer handles its interview and explicit installation decision. A missing launch reply is never replayed.`,
            );
            await speak(
              made.id,
              caller!.name,
              `${task}\n\nConnected project workspace (data): ${JSON.stringify({ project: now.project, root: claim!.root })}. Verify with file_roots; write drafts only in this run’s artifacts directory.`,
            );
          } catch (error) {
            if (error instanceof Dropped || error instanceof Quit) throw error;
            show(
              'trouble',
              error instanceof Error ? error.message : errorMessage(error),
            );
          }
          continue;
        }
        if (line.kind === 'orchestrations') {
          await list(line.ask, definitionsOf, (rows: readonly Definition[]) =>
            describeOrchestrations(rows, now.project),
          );
          continue;
        }
        if (line.kind === 'orchestration') {
          const listed = await settled(line.ask);
          const rows = definitionsOf(listed);
          if (rows === undefined) {
            show('trouble', refusal(listed));
          } else {
            showPlain(
              'client',
              describeOrchestration(line.name, rows, now.project),
            );
          }
          continue;
        }
        // THE RUNS, WHICH NEED AN ACCOUNT AND NOT A PROJECT.
        // `orchestration.list` and `.status` both call `requireHandle` and
        // refuse a socket without one, so the refusal is what a signed-out
        // person is shown — `runsOf` answers nothing rather than an empty
        // list precisely so that this cannot come out as "you have no runs".
        if (line.kind === 'runs') {
          await list(line.ask, runsOf, (rows: readonly Run[]) =>
            describeRuns(rows, zone),
          );
          continue;
        }
        if (line.kind === 'run') {
          const said = await settled(line.ask);
          const status = runStatusOf(said);
          if (status === undefined) {
            show('trouble', refusal(said));
          } else {
            showPlain('client', describeRun(status, zone));
          }
          continue;
        }
        if (line.kind === 'watch') {
          await watchRun(line.run);
          continue;
        }
        // Explicit decisions: the server rechecks authority and the current state.
        //
        // "is not waiting for an answer; nothing changed" and "has already
        // ended; nothing changed" are the two sentences a person actually
        // needs here, and both are written over there — one client's guess
        // at which of them applied would be a guess about a race it lost.
        if (
          line.kind === 'answerRun' ||
          line.kind === 'cancelRun' ||
          line.kind === 'resumeRun'
        ) {
          const said =
            line.kind === 'resumeRun'
              ? await resumeRun(line.id)
              : await settled(line.ask);
          const now = settledRun(said);
          if (now === undefined) {
            show('trouble', refusal(said));
          } else {
            forgetDialog(now.id);
            show(
              'client',
              describeSettled(
                now,
                line.kind === 'answerRun'
                  ? 'answered'
                  : line.kind === 'resumeRun'
                    ? 'resumed'
                    : 'cancelled',
              ),
            );
            // The count is wrong now, and the push that would say so
            // may be a moment behind; the check is cheap.
            background(checkWaiting());
          }
          continue;
        }
        // `/always`: THE PROJECT'S OWN FILE, EDITED, AND WHAT WAS ALREADY WAITING.
        if (line.kind === 'always') {
          await always(line.on);
          continue;
        }
        // `/cap`: SHOWS THE CAPS AS THE SERVER READS THEM. `/cap N`: WRITES THEM,
        // LIKE `/always`.
        if (line.kind === 'cap') {
          await showCaps();
          continue;
        }
        if (line.kind === 'capSet') {
          await setCap(line.key, line.value);
          continue;
        }
        // A COMMAND APPROVAL, ANSWERED FROM WHEREVER THIS SCREEN IS. The server
        // continues whatever raised it -- a conductor through its engine -- and
        // says whether it could start that now or left the decision standing.
        if (line.kind === 'answerApproval') {
          const said = await settled(line.ask);
          const answered = answeredOf(said);
          if (answered === undefined) {
            show('trouble', refusal(said));
          } else {
            show(
              'client',
              answered.busy
                ? describeAnswered(answered)
                : describeApprovalAnswered(answered.id, answered.state),
            );
            background(checkWaiting());
          }
          continue;
        }
        // A BARE `/answer`, OR ONE THAT WAS HELD. Checked first, so the list
        // shown is the server's now and not the last timer's.
        if (line.kind === 'waitingRuns') {
          await checkWaiting();
          showPlain('client', describeWaitingRuns(waiting, line.held === true));
          continue;
        }
        if (line.kind === 'inbox') {
          const outcome = await connection.ask(line.ask.type, line.ask.payload);
          // VALIDATED, NOT CAST. On `projects`' and `conversations`'
          // rule: a code that is not `OK` and an `OK` this build cannot
          // read are the same "nothing to show" from here, and both are
          // said with the server's own sentence rather than thrown into
          // `describeInbox` as data it never promised to accept.
          const page = inboxPageOf(outcome);
          if (page === undefined) {
            show('trouble', refusal(outcome));
            continue;
          }
          show('client', toListing(describeInbox(page)));
          // MARKED READ ONLY ONCE SHOWN, AND ONLY WHAT WAS SHOWN. A
          // client that marked a page read before painting it would lose
          // items to a crash between the two asks; marking more than
          // `page.items` would flag rows this screen never displayed.
          if (page.items.length > 0) {
            const read = readingInbox(page.items.map((item) => item.id));
            // A REFUSAL IS SAID; A DROPPED SOCKET IS NOT A SECOND
            // FAILURE TO REPORT. The page already reached the screen —
            // what happens to the read receipt is real but secondary,
            // and a rejection here (the connection going away between
            // the two asks) must not propagate out of this loop the
            // way an unguarded `await` would, ending the whole
            // session over a receipt nobody is waiting on.
            await connection.ask(read.type, read.payload).then(
              (marked) => {
                if (marked.code !== OK) {
                  show('trouble', refusal(marked));
                }
              },
              () => undefined,
            );
          }
          continue;
        }
        if (line.kind === 'earlier') {
          await earlier();
          continue;
        }
        if (line.kind === 'board') {
          await inspectBoard(
            {
              surface,
              read: settled,
              print: (lines) => showPlain('client', lines),
              trajectory: async (conversation, label) => {
                const shown = await explore(exploring(), {
                  conversation,
                  label,
                  view: 'trajectory',
                });
                if (shown === 'refused')
                  throw new Error(describeTrajectoryUnreadable(conversation));
              },
            },
            line.view,
            line.project,
          );
          continue;
        }
        // THE EXPLORER — spec 2026-09-29 §5. With no conversation named it is the one open
        // where the person is; nothing open is not a refusal but a person who has not said
        // anything yet, told so rather than sent a frame naming no conversation. Bare
        // `/trajectory` lands on a failure in the latest turn, since that is what a person opens
        // it for then; with none there it opens on the last row, following (`levelOf`).
        if (line.kind === 'trajectory') {
          const conversation = line.conversation ?? now.conversation;
          if (conversation === undefined) {
            show('client', describeNoTrajectory());
            continue;
          }
          const shown = await explore(exploring(), {
            conversation,
            label:
              line.conversation === undefined ? now.who.name : conversation,
            view: line.view,
            ...(line.view === 'trajectory' && line.conversation === undefined
              ? { land: 'failure' as const }
              : {}),
          });
          if (shown === 'refused') {
            show('trouble', describeTrajectoryUnreadable(conversation));
          }
          continue;
        }
        if (line.kind === 'diagnose') {
          // A DIAGNOSIS IS NOT A CHANGE OF SPEAKER. Continue Daedalus's
          // separate conversation in this home, opening one only when he
          // has none, and leave `now.conversation` untouched. The
          // separation preserves the original agent pinning; the
          // continuation preserves corrections and follow-up questions.
          const target = line.conversation ?? now.conversation;
          if (target === undefined) {
            show('client', describeNoDiagnosis());
            continue;
          }
          const home = now.project ?? '';
          let diagnostic = diagnostics.get(home);
          if (diagnostic === undefined) {
            const latest = continuing('daedalus', now.project);
            const found = await connection.ask(latest.type, latest.payload);
            if (found.code !== OK) {
              show('trouble', refusal(found));
              continue;
            }
            diagnostic = continued(found);
          }
          if (diagnostic === undefined) {
            const start = opening(now.project);
            const openedDiagnosis = await connection.ask(
              start.type,
              start.payload,
            );
            diagnostic = opened(openedDiagnosis);
            if (diagnostic === undefined) {
              show('trouble', refusal(openedDiagnosis));
              continue;
            }
          }
          diagnostics.set(home, diagnostic);
          show('client', describeDiagnosing(target, diagnostic.id));
          const focus =
            line.question === undefined
              ? ' Diagnose the run generally and find the earliest relevant divergence.'
              : ` The operator's current question is the controlling scope: ${line.question}`;
          await speak(
            diagnostic.id,
            'daedalus',
            `Diagnose conversation ${target}.${focus} Read the persisted trajectory around that scope; do not substitute an earlier unrelated problem. This request supersedes any prior diagnostic scope in this Daedalus conversation.`,
          );
          continue;
        }
        if (line.kind === 'retrieval-error') {
          show('trouble', line.text);
          continue;
        }
        if (line.kind === 'messaging') {
          try {
            const result = await dispatch(connection, line.command);
            if (result.kind !== 'completed')
              throw new Error(
                result.outcome.said ||
                  'The message control response was refused or incomplete.',
              );
            show('client', JSON.stringify(result.outcome.payload, null, 2));
          } catch (failure) {
            show(
              'trouble',
              failure instanceof Error
                ? failure.message
                : errorMessage(failure),
            );
          }
          continue;
        }
        if (line.kind === 'usage-report') {
          try {
            show(
              'client',
              await runUsage(new UsageClient(connection), line.command),
            );
          } catch (failure) {
            show(
              'trouble',
              failure instanceof Error
                ? failure.message
                : errorMessage(failure),
            );
          }
          continue;
        }
        if (line.kind === 'information') {
          try {
            const result = await new InformationClient(
              connection,
              line.command.scope,
            ).invoke(line.command);
            show('client', JSON.stringify(result, null, 2));
          } catch (failure) {
            show(
              'trouble',
              failure instanceof Error
                ? failure.message
                : errorMessage(failure),
            );
          }
          continue;
        }
        if (line.kind === 'retrieval') {
          try {
            const result = await retrieve(connection, line.command);
            show(
              'client',
              line.command.json
                ? JSON.stringify(result)
                : describeRetrieval(result),
            );
          } catch (failure) {
            show(
              'trouble',
              failure instanceof Error
                ? failure.message
                : errorMessage(failure),
            );
          }
          continue;
        }
        if (line.kind === 'help') {
          // Answered here, with no round trip, which is what makes it
          // the one command that works against a server too old, too new
          // or too broken to answer anything else.
          show(
            'client',
            toListing([
              ...describeHelp(),
              ...(now.who.commands ?? []).map(
                (command) =>
                  `${command.command} ${command.argumentHint} — ${command.description}${command.kind === 'skill' && command.mode === null ? ' (specify --mode=INHERITED|SUMMARISED|NEW|DIRECT)' : ''}`,
              ),
            ]),
          );
          continue;
        }
        if (line.kind === 'theme') {
          // Answered here, like /help: a theme is this terminal's, not
          // the server's.
          const themes = talking.theming;
          if (themes === undefined) {
            show(
              'client',
              'this terminal is drawn without colour, so there are no themes to choose',
            );
          } else if (line.name === undefined) {
            show('client', toListing(themes.describe()));
          } else {
            const chose = themes.choose(line.name);
            show(chose.ok ? 'client' : 'trouble', toListing(chose.lines));
          }
          continue;
        }
        // SCHEDULING. Each is guarded against a dropped socket by `settled`,
        // so none of them can end the session from inside a command.
        if (line.kind === 'schedule-file') {
          const parsed = parseScheduleFileCommand(line.text, now.project);
          if (parsed.kind !== 'request')
            show(
              'trouble',
              parsed.kind === 'usage'
                ? parsed.said
                : 'Use /schedule save <JSON>, /schedule sync <JSON> or /schedule files',
            );
          else {
            const done = await settled(parsed.request);
            if (done.code !== OK) show('trouble', refusal(done));
            else showPlain('client', [JSON.stringify(done.payload, null, 2)]);
          }
          continue;
        }
        if (line.kind === 'schedule') {
          if (
            (await schedule(line.text, now.project, now.conversation)) ===
            'ended'
          ) {
            return;
          }
          continue;
        }
        if (line.kind === 'managing') {
          await manage(line.verb, line.name);
          continue;
        }
        if (line.kind === 'schedules') {
          const both = await scheduled();
          if (both !== undefined) {
            showPlain(
              'client',
              describeSchedules(both.schedules, both.triggers),
            );
          }
          continue;
        }
        if (line.kind === 'fire') {
          await fire(line.name);
          continue;
        }
        if (line.kind === 'firings') {
          const listed = await settled(line.ask);
          const firings = firingsOf(listed);
          if (firings === undefined) {
            show('trouble', refusal(listed));
          } else {
            showPlain('client', describeFirings(firings, zone));
          }
          continue;
        }
        if (line.kind === 'sync') {
          await syncRun(line.action);
          continue;
        }
        // STANDING APPROVALS. The listing needs the project a person is
        // in, which `typed` does not hold, so it is built here; revoking
        // names its own id and is sent as read.
        if (line.kind === 'approvals') {
          if (now.project === undefined) {
            show('client', describeNoApprovals());
            continue;
          }
          const listed = await settled(listingApprovals(now.project));
          const rows = approvalsOf(listed);
          if (rows === undefined) {
            show('trouble', refusal(listed));
          } else {
            showPlain('client', describeApprovals(rows));
          }
          continue;
        }
        if (line.kind === 'revoking') {
          const said = await settled(line.ask);
          const revoked = revokedOf(said);
          if (revoked === undefined) {
            show('trouble', refusal(said));
          } else {
            show('client', describeRevoked(line.id, revoked));
          }
          continue;
        }
        if (line.kind === 'unknown') {
          show('client', describeUnknownCommand(line.named));
          continue;
        }
        if (line.kind === 'usage') {
          show('client', describeUsage(line.command));
          continue;
        }
        // WHERE A PERSON IS. Each goes through `enter`, so none of them can
        // leave the conversation in one tier and the files in another.
        if (line.kind === 'here') {
          await rootHere(line.name);
          continue;
        }
        if (line.kind === 'project') {
          await toProject(line.name);
          continue;
        }
        if (line.kind === 'cd') {
          await cd(line.path);
          continue;
        }
        if (now.conversation === undefined) {
          const start = opening(now.project);
          const first = await connection.ask(start.type, start.payload);
          const made = opened(first);
          if (made === undefined) {
            // The server's own sentence whenever it sent one, which is
            // what `refusal` is for and why `said`'s absence is
            // preserved from the envelope all the way up to here.
            show('trouble', refusal(first));
            return;
          }
          now.conversation = made.id;
          show('client', `conversation ${made.id}`);
          // FOLLOWED FROM ITS FIRST ENTRY, which is this client's own and about to stream.
          await follow(made.id);
          shownTo(made.id, 0);
        }
        // `line.text` and not `text`: the same characters, taken from the
        // reading that decided this was an utterance rather than from the
        // string beside it, so the two can never come apart.
        if (
          talking.reconnect &&
          (uncertainConversations.has(now.conversation) ||
            [...recoveringJobs.values()].includes(now.conversation))
        ) {
          show(
            'trouble',
            'This conversation has active or uncertain work. Inspect /job status and /trajectory before starting more work.',
          );
          continue;
        }
        await speak(now.conversation, now.who.name, line.text);
        await measure();
      } catch (error) {
        if (error instanceof ResponseUnreadable) {
          show('trouble', error.message);
          continue;
        }
        if (
          talking.reconnect &&
          !(error instanceof Quit) &&
          (error instanceof Dropped || connectionLost)
        ) {
          await recoverConnection();
          continue;
        }
        throw error;
      }
    }
  } catch (trouble) {
    // The two failures this function answers rather than raises, and they
    // are the same shape for the same reason: each is a way a turn ends
    // that is not an outcome. A socket that went away mid-run ends the
    // conversation — every later ask would be refused by a closed
    // connection anyway — and a second Ctrl-C ends it because somebody said
    // so. Either way a person gets one sentence about what happened and the
    // loop stops, rather than a stack trace or a prompt that no longer
    // reaches anything.
    if (!(trouble instanceof Dropped) && !(trouble instanceof Quit)) {
      throw trouble;
    }
    show('trouble', trouble.message);
  } finally {
    // EVERY PATH OUT OF A RUN CLEARS IT, AND THIS IS THE LAST OF THEM.
    // A working state left standing is a spinner that never stops, and the
    // two failures caught above — a dropped socket, a second Ctrl-C — are
    // exactly the paths that leave one behind.
    surface.working(undefined);
    recoveryClosing = true;
    clearInterval(recoveryTimer);
    reconnectNow = undefined;
    await reconnecting?.catch(() => undefined);
    clearInterval(recoveryTimer);
    clearInterval(waitingTimer);
    await unsyncing();
    await rooting.release();
    connection.close();
  }
}

/** How long a quit, a move or a lost rooting waits for a union's last push. */
const LEAVE_PATIENCE_MS = 5_000;

/**
 * Who a person named, out of the environment, or nothing because they did not.
 *
 * <h2>It threw here, and now it does not, which is task 3's change</h2>
 *
 * <p>This used to refuse at startup when `PLOWSHARE_AGENT` was unset, and that
 * was the right fix for the wrong problem. The problem it solved was timing:
 * before it, the empty string travelled — {@link converse} signed in, opened a
 * socket, opened a conversation, printed a prompt, and the first thing a person
 * typed came back refused, because `AgentRunHandler` reads `agent` with
 * `Payloads.required` before it reads anything else. The refusal happened after
 * three round trips and after a person had composed a question.
 *
 * <p><b>What was never right is that the variable had to be set at all.</b> The
 * ordinary way to use this system is to talk to a bot, so requiring a name made
 * the ordinary case the configured one. `agent.list` at sign-in is what
 * replaces the throw: an unset variable is now a question the server answers,
 * and a <i>wrong</i> one is refused at sign-in with nothing created — which is
 * earlier than this function ever managed, since it could only ever check for
 * blankness and never for existence.
 *
 * <p><b>A blank reads as absent, and that is deliberate.</b> `PLOWSHARE_AGENT=`
 * in a shell profile is somebody not naming an agent, not somebody naming one
 * called `''`; `whoAnswers` trims and treats it the same way, so neither end
 * can decide differently from the other.
 *
 * <p><b>Its own function, taking the environment, so that it has a test.</b>
 * {@link run} reads a real `process` and drives a real terminal. `coloured` is
 * the precedent.
 */
export function agentNamed(environment: {
  readonly PLOWSHARE_AGENT?: string;
}): string | undefined {
  const agent = (environment.PLOWSHARE_AGENT ?? '').trim();
  return agent === '' ? undefined : agent;
}

/**
 * The entry point: the environment, a terminal, and {@link converse}.
 *
 * <h2>The password comes from the environment and is not typed at the
 * prompt</h2>
 *
 * <p>Deliberate, and worth the sentence. `readline` echoes what is typed, so a
 * password asked for here would land in the scrollback, in the terminal's own
 * scroll buffer, and in whatever the person pastes into an issue afterwards.
 * Turning the echo off is a handful of lines of raw-mode handling that has to
 * be got exactly right on every exit path — including a crash — and getting it
 * wrong leaves a terminal that does not echo anything at all. The client design
 * §2 calls v1 <b>dev-only, run from source</b>, and an environment variable is
 * the honest shape for that. It is named as a thing to fix rather than left as
 * a thing to find.
 */
/**
 * Whether this is `fetch` declining to reach the host at all.
 *
 * <p>Matched on the shape rather than the message: `fetch` reports every
 * transport failure as a `TypeError` reading "fetch failed" and puts the real
 * reason — ECONNREFUSED, ENOTFOUND, a TLS refusal — in `cause`. The message is
 * the part with no information in it, so the presence of a cause under a
 * TypeError is what is tested.
 */
export function unreachable(trouble: unknown): boolean {
  return (
    trouble instanceof TypeError &&
    'cause' in trouble &&
    trouble.cause !== undefined
  );
}

export async function run(args: readonly string[] = []): Promise<void> {
  const fileStores = new FileStores(userConfigDirectory());
  if (args[0] === 'filestore') {
    process.stdout.write(
      JSON.stringify(
        await manageFileStores(fileStores, args.slice(1), promptFileStore),
        null,
        2,
      ) + '\n',
    );
    return;
  }
  const registry = new Connections(userConfigDirectory());
  if (args[0] === 'connection') {
    process.stdout.write(
      JSON.stringify(
        await manageConnections(registry, args.slice(1)),
        null,
        2,
      ) + '\n',
    );
    return;
  }
  const selection: { name?: string; server?: string; account?: string } = {};
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index],
      value = args[index + 1];
    if (
      !value ||
      !['--connection', '--server', '--account'].includes(flag ?? '')
    )
      throw new Error(
        'Use --connection NAME, --server ORIGIN or --account HANDLE.',
      );
    if (flag === '--connection') selection.name = value;
    else if (flag === '--server') selection.server = value;
    else selection.account = value;
  }
  if (!selection.account && process.env['PLOWSHARE_ACCOUNT'])
    selection.account = process.env['PLOWSHARE_ACCOUNT'];
  const selected = await resolveConnection(registry, selection, process.env);
  const base =
    selected?.server ?? selection.server ?? process.env['PLOWSHARE_URL'];
  if (!base?.trim())
    throw new Error(
      'Set PLOWSHARE_URL to the server origin before starting the TUI.',
    );
  const project = process.env['PLOWSHARE_PROJECT'];
  // WHERE THE PERSON STARTED, WHICH IS NOT process.cwd(). bin/plowshare-talk
  // changes into the client's own directory to run it, so the directory a
  // person means arrives in PLOWSHARE_HERE; run directly, cwd is right.
  const here = await localStorePath(
    fileStores,
    process.env['PLOWSHARE_HERE'] ?? process.cwd(),
  );
  // WHICH SURFACE, AND IT IS BOTH STREAMS RATHER THAN ONE.
  //
  // <b>The Ink surface needs raw mode, and raw mode needs a terminal on the
  // INPUT.</b> A TTY stdout with a piped stdin is not a corner case here — it
  // is how every demo and every screenshot of this client has been driven,
  // `printf … | node src/view/main.ts` — and asking only about stdout would
  // mount a full-screen view over a stdin that cannot be put into raw mode,
  // which throws where the plain surface simply works.
  //
  const colour = coloured(process.stdout);
  const interactive =
    process.stdin.isTTY === true && process.stdout.isTTY === true;
  let storeState = await fileStores.load();
  if (storeState.status === 'needs-setup' && interactive) {
    const input = await promptFileStore();
    if (input) storeState = await fileStores.initialize(input);
  }
  if (storeState.status !== 'loaded')
    process.stderr.write(
      storeState.message + ' Use filestore setup or filestore status.\n',
    );
  // Asked BEFORE Ink mounts: the terminal's answers arrive on stdin, and once
  // Ink is reading stdin they would arrive as typing. See `probe.ts`.
  const probe =
    colour && interactive
      ? await probeTerminal({
          stdin: process.stdin,
          stdout: process.stdout,
          env: process.env,
        })
      : undefined;
  const themes = colour
    ? theming({
        env: process.env,
        home: homedir(),
        cwd: here,
        depth: depthOf(process.env),
        ...(probe === undefined ? {} : { probe }),
        watching: interactive,
      })
    : undefined;
  const look = themes?.current();
  const surface = interactive
    ? terminal({
        colour,
        ...(look === undefined ? {} : { look }),
        ...(themes === undefined
          ? {}
          : { arguments: { [THEME_COMMAND]: themes.offers } }),
      })
    : plain({
        input: process.stdin,
        output: process.stdout,
        colour,
        ...(look === undefined ? {} : { look }),
      });
  themes?.onChange((next) => surface.restyle?.(next));
  try {
    const credentials =
      process.env['PLOWSHARE_PASSWORD'] ||
      (process.env['PLOWSHARE_HANDLE'] && !selected && !selection.account)
        ? undefined
        : new Credentials(
            base,
            credentialDirectory(),
            undefined,
            selected?.account ?? selection.account,
          );
    const agent = agentNamed(process.env);
    await converse({
      // The id this client listens under. Generated here because
      // this is the only layer that may read a platform: the
      // binding takes it, and builds none of its own.
      door: {
        base,
        fetch,
        open: openingSocket,
        session: randomUUID(),
        ...(credentials === undefined
          ? {}
          : { renew: () => credentials.renew({ base, fetch }) }),
      },
      ...(credentials === undefined ? {} : { credentials }),
      handle: credentials
        ? ''
        : (process.env['PLOWSHARE_HANDLE'] ??
          selected?.account ??
          selection.account ??
          ''),
      password: process.env['PLOWSHARE_PASSWORD'] ?? '',
      ...(agent === undefined ? {} : { agent }),
      ...(project === undefined ? {} : { project }),
      here,
      // `PLOWSHARE_TOKENS=0` turns the live preview off. Anything else,
      // including unset, leaves it on -- see `Talking.tokens`.
      tokens: process.env['PLOWSHARE_TOKENS'] !== '0',
      waitingEvery: 15_000,
      reconnect: { attempts: 5, delayMs: 500 },
      surface,
      ...(themes === undefined ? {} : { theming: themes }),
      settings: settingsPath(process.env, homedir()),
    });
  } catch (trouble) {
    // Three shapes, and the first two are sentences somebody can act on
    // rather than stack traces they cannot.
    const fault = (text: string): void =>
      surface.show(entered(1, 'trouble', parse(text)));
    if (
      trouble instanceof SignInRefused ||
      trouble instanceof MustChangePassword
    ) {
      fault(trouble.message);
    } else if (unreachable(trouble)) {
      // The likeliest first run of all: a remote server that is not up,
      // or an address with a typo in it. `fetch` says only "fetch
      // failed", which names neither and names no address.
      fault(describeUnreachable(base));
    } else {
      fault(trouble instanceof Error ? trouble.message : errorMessage(trouble));
    }
    process.exitCode = 1;
  } finally {
    themes?.close();
    surface.close();
  }
}

// Run only when this file is what node was asked to run, so that importing it
// — which `composition.test.ts` does — starts nothing.
// Embedded modules share the executable URL; the native launcher owns startup.
if (
  !isSea() &&
  process.argv[1] !== undefined &&
  process.argv[1] === fileURLToPath(import.meta.url)
) {
  background(run(process.argv.slice(2)));
}

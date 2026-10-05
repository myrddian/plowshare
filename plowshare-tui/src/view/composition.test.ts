import { background } from './background.test-support.ts';
import { outcomes } from './json.test-support.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import { isList } from 'plowshare-client-ts/binding/values';
import { demoBoard } from '../logic/board-demo.ts';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import {
  chmod,
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  writeFile,
} from 'node:fs/promises';
import { createServer } from 'node:http';
import type { IncomingMessage, Server, ServerResponse } from 'node:http';
import type { Socket as Stream } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

/** The session this client listens under -- named, so the fake server can
 *  refuse an upgrade without it exactly as the real handler does. */
const LISTENING_AS = 'a-listening-session';

import { CURRENT_VERSION } from 'plowshare-client-ts/binding/envelope';
import { converse, openingSocket } from './main.ts';
import type { Entry, Working } from '../logic/screen.ts';
import { plainOf } from '../logic/tints.ts';
import type { Tinted } from '../logic/tints.ts';
import type { ExploreKey } from '../logic/explorer.ts';
import type { BackPage, Entry as LogEntry, Waiting } from '../logic/session.ts';
import { explore, type Reads } from './exploring.ts';
import { MOST_HELD } from '../logic/record.ts';
import type { Panel, Viewed, ViewKey } from '../logic/record.ts';
import { toTerminal } from './scrollback.ts';
import type { Surface } from './surface.ts';
import {
  describeDensity,
  describeNothingToOpen,
  describeTrajectoryUnreadable,
} from '../logic/wording.ts';
import type { Standing } from '../logic/wording.ts';
import { describeCapNeedsAProject } from '../logic/wording.ts';
import type { CapKey, DialogKey } from '../logic/caps.ts';
import type { Stroke } from '../logic/approval.ts';
import type { QuestionStroke } from '../logic/questions.ts';

/**
 * <b>The composition proof: every layer of this module, driven at once, over a
 * real socket, against a server that answers like Plowshare.</b>
 *
 * <h2>Why this file is the point of task 7</h2>
 *
 * <p>Task 6's closing note is the reason it exists: <i>"nothing has yet driven
 * `agent.run → events → job.status` as a composition."</i> Every layer had its
 * own suite and every suite was green — the envelope's, the connection's, the
 * auth's, the session's, the grammar's — and a stack of six green layers is
 * exactly the shape of thing that fails on the day it is assembled. What runs
 * below is `signIn` → `refresh` → `ticket` → a real WebSocket upgrade →
 * `conversation.open` → `agent.run` → bare `JobEvent`s → `job.status` →
 * `parse` → `toTerminal`, with nothing faked between the view and the wire.
 *
 * <p><b>And it needs no Postgres, no model and no running server</b>, which is
 * not a convenience but the whole reason it can exist at all: the real server
 * needs both and is meant to be remote, so a composition test that required one
 * would be a composition test nobody runs. Forty lines of RFC 6455 buy a
 * proof that runs in the suite.
 *
 * <h2>Each case gets its own server, and that is a correction</h2>
 *
 * <p><b>The cases here used to share one `heard` and one turn counter at module
 * scope</b>, so the second read what the first had written and the order vitest
 * happened to run them in was load-bearing. A whole-plan review named it, and
 * {@link plowshare} is the answer: a case starts a server, scripts it with the
 * {@link Move}s it needs, and {@link afterEach} puts it down. Nothing below
 * reads a run it did not make, and each case fails or passes on its own.
 *
 * <h2>The events arrive before the answer, on purpose</h2>
 *
 * <p>Two turns are spoken and <b>the server sends them in opposite orders</b>:
 * the first turn's `JobEvent`s go out <i>before</i> the `ACCEPTED` that carries
 * the handle, the second turn's after. Task 4 proved the connection tolerates
 * either, and task 6 proved the turn model does — both by mutation, both alone.
 * This is the first time the <i>stack</i> is asked, and the assertion is that
 * the two turns render identically.
 *
 * <p><b>That assertion is now made, where it used to be only claimed.</b> The
 * case named "renders a turn whose events outran its answer exactly like one
 * that did not" asserted the <i>server's</i> write order and never compared the
 * two renderings at all — the same review named that too. {@link turnsIn} cuts
 * the scrollback into one list of lines per turn and the two lists are compared
 * outright, which is the claim the name has always made.
 *
 * <p><b>The opener is `main.ts`'s own</b>, imported rather than copied, so what
 * is driven over the wire below is the six lines that ship rather than six that
 * look like them. `real-socket.test.ts` wrote those lines against a real server
 * in task 5; this is where the shipped copy of them is used.
 *
 * <p>The path it exercises is worth naming, because it is not obvious that it
 * is reached: `connection.ask`'s promise resolves in a microtask, so frames
 * that the server wrote before the answer are handled by `onPush` while
 * `turn.job` is still unknown — which is `session.ts`'s holding area, taken off
 * the shelf by a real socket's delivery order rather than by a test's
 * arrangement.
 *
 * <p><b>MEASURED, by mutation, because a case that has never refused anything
 * proves nothing.</b> `main.ts` folds the answer into `latest(now)` — the turn
 * the pushes have been updating — rather than into the one it was holding when
 * it suspended. Changed to `answering(now, said)` and the suite run:
 *
 * <pre>
 *   × signs in, opens, speaks twice and renders both answers   5009ms timeout
 *   × renders a turn whose events outran its answer …
 *   ✓ reports the progress of a run while it is running        7ms
 * </pre>
 *
 * <p>The first turn <b>hung</b> — its events had been held and were then
 * dropped, so the `ended` event never arrived and the one wait never settled —
 * while the answer-first turn went on passing. That asymmetry is the proof
 * that the two orders below are genuinely two orders and that only one of them
 * would have caught this. The plant was removed.
 *
 * <h2>The socket drops mid-run, which is the common failure and not the edge
 * one</h2>
 *
 * <p>{@link Move} `drops-mid-run` is the case a whole-plan review asked for,
 * and the reason it belongs here rather than in `connection.test.ts` is that
 * the connection was never the half that was broken. `connection.ts` has always
 * subscribed to `close` and stranded every outstanding ask; what had no close
 * signal at all was the <b>view's</b> follow-wait — minutes long, settled only
 * by an `ended` event that a dropped socket will never deliver. Against a real
 * server that is not an edge case: a proxy's idle cutoff, a laptop sleeping, a
 * deploy or a restart all produce it, and every one of them left this client
 * hanging with a live prompt and nothing to say.
 *
 * <p><b>MEASURED before the fix, which is the standard every finding on this
 * branch has been held to.</b> With the case below written and `onClose` not
 * yet threaded through, `npx vitest run` reported:
 *
 * <pre>
 *   × says so when the socket drops mid-run, instead of hanging  5002ms timeout
 * </pre>
 *
 * <p>Not an assertion failure — a <b>timeout</b>, which is the hang itself,
 * reproduced. The five-second wait is vitest's default; the real one is
 * unbounded.
 *
 * <h2>The server, and why it is hand-rolled twice</h2>
 *
 * <p>Node ships a WebSocket client and no WebSocket server, and `ws` is a
 * dependency this module will not take for a test (client design §6). What a
 * server needs for this exchange is in RFC 6455 §1.3 and §5 and nothing more: a
 * SHA-1 of the client's key against the protocol's fixed GUID, an unmask for
 * the frames a client sends, and a two-byte header for the ones it sends back.
 *
 * <p><b>The codec below is lifted from `src/real-socket.test.ts` and that
 * duplication is deliberate.</b> Sharing it would mean one of two things, and
 * both are worse than forty duplicated lines: a helper module in `src/`, which
 * is in no tsconfig project and so would put the shared half <i>out</i> of type
 * checking, or moving task 5's file — the standing proof that a real
 * `WebSocket` is a `Socket` — for a reason that has nothing to do with what it
 * proves. This copy is type-checked, because `src/view/` is a project and this
 * file is in it. That is also why the test lives here rather than beside its
 * sibling: task 6 recorded three test files in no tsconfig project and asked
 * for no fourth.
 */

/** RFC 6455 §1.3: the constant a server hashes the client's key against. */
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** One unmasked text frame, header and payload, for a payload under 64 KiB. */
function textFrame(text: string): Buffer {
  const payload = Buffer.from(text, 'utf8');
  if (payload.length < 126) {
    return Buffer.concat([Buffer.from([0x81, payload.length]), payload]);
  }
  const header = Buffer.alloc(4);
  header[0] = 0x81;
  header[1] = 126;
  header.writeUInt16BE(payload.length, 2);
  return Buffer.concat([header, payload]);
}

/**
 * RFC 6455 §5.5.1: a close frame carrying 1000, "normal closure".
 *
 * <p><b>A frame and not a `destroy()`</b>, which is a deliberate choice about
 * what is being tested. A reset TCP connection also reaches the client as a
 * `close` — every `WebSocket` turns an abrupt disconnect into one — but it gets
 * there through the socket's error path on a timing this test would not
 * control, and a case that sometimes arrives is a case that sometimes passes.
 * The client handles both identically, because it subscribes only to `close`,
 * so the deterministic one is the one worth scripting.
 */
function closeFrame(): Buffer {
  const frame = Buffer.alloc(4);
  frame[0] = 0x88;
  frame[1] = 2;
  frame.writeUInt16BE(1000, 2);
  return frame;
}

/** RFC 6455 §5.5: a close frame carrying a code and a reason, as a refused claim is closed. */
function closeFrameWith(code: number, reason: string): Buffer {
  const said = Buffer.from(reason, 'utf8');
  const frame = Buffer.alloc(4 + said.length);
  frame[0] = 0x88;
  frame[1] = 2 + said.length;
  frame.writeUInt16BE(code, 2);
  said.copy(frame, 4);
  return frame;
}

/** Whole text messages read off a stream, and the bytes of the next one. */
function read(buffered: Buffer): {
  messages: string[];
  rest: Buffer;
  closed?: true;
} {
  const messages: string[] = [];
  let rest = buffered;
  for (;;) {
    if (rest.length < 2) {
      return { messages, rest };
    }
    const opcode = (rest[0] ?? 0) & 0x0f;
    const flagged = rest[1] ?? 0;
    const masked = (flagged & 0x80) !== 0;
    let length = flagged & 0x7f;
    let at = 2;
    if (length === 126) {
      if (rest.length < 4) {
        return { messages, rest };
      }
      length = rest.readUInt16BE(2);
      at = 4;
    }
    const mask = rest.subarray(at, masked ? at + 4 : at);
    at += masked ? 4 : 0;
    if (rest.length < at + length) {
      return { messages, rest };
    }
    const payload = Buffer.from(rest.subarray(at, at + length));
    if (masked) {
      for (let index = 0; index < payload.length; index += 1) {
        payload[index] = (payload[index] ?? 0) ^ (mask[index % 4] ?? 0);
      }
    }
    rest = rest.subarray(at + length);
    if (opcode === 0x1) {
      messages.push(payload.toString('utf8'));
    }
    if (opcode === 0x8) {
      return { messages, rest: Buffer.alloc(0), closed: true };
    }
  }
}

/** What the server was asked, so the assertions can read the wire. */
/** The real server's own refusal list, so the fake refuses what it refuses. */
const PLACEHOLDERS = new Set([
  'changeme',
  'change_me',
  'change-me',
  'please-change-me',
  'password',
  'admin',
  'admin123',
  'changeit',
  'letmein',
  'default',
  'root',
]);

interface Heard {
  readonly paths: string[];
  readonly frames: { type: string; payload: unknown }[];
  /** The `Authorization` each auth request carried, by path. */
  readonly bearing: Map<string, string>;
  /** The `Cookie` each auth request carried, by path. */
  readonly cookied: Map<string, string>;
  /** Runs whose events were dropped because they named no listening session,
   *  which is what the real server does with them. */
  readonly unrouted: string[];

  /** Every new password this server was asked to set, in order. */
  readonly passwords: string[];

  /** Every `job.stream` payload this server was sent, in order. */
  readonly subscribed: unknown[];

  /** The session the upgrade listened under, as the query carried it. */
  readonly sessions: string[];

  /** The ticket the upgrade presented, as the query carried it. */
  readonly tickets: string[];
  /** What the server wrote down the socket, in order, as a label per write. */
  readonly wrote: string[];

  /** Every refresh token presented after it had been rotated away, in order. */
  readonly refused: string[];

  /** Every `/v1/files` upgrade, as its query carried the claim. */
  readonly files: {
    session: string;
    ticket: string;
    project: string;
    machine: string;
    root: string;
  }[];

  /** `ready <project>` as each claim was said to land, and `agent.list <tier>` as
   *  each roster was asked for, interleaved in the order they happened. */
  readonly landings: string[];
}

/**
 * How the scripted server answers one `agent.run`, one entry per turn.
 *
 * <p>The first two orders are both inside the contract (spec §4.3). The third
 * is inside no contract — it is the wire going away underneath a run — and has
 * to be survivable anyway.
 */
type Move =
  /** Every event, then the `ACCEPTED` that names the handle they belong to. */
  | 'events-first'
  | 'events-overflow'
  /** The `ACCEPTED`, a tick, then the events. See {@link ran}. */
  | 'answer-first'
  /** The `ACCEPTED`, two events, then a close frame and no ending at all. */
  | 'drops-mid-run'
  | 'drops-before-handle'
  /**
   * The `ACCEPTED`, a beat or two, and then nothing — the socket stays open.
   *
   * <p><b>The case a close frame cannot stand in for.</b> A wire that goes
   * away announces itself and `onClose` fails the wait; a server that simply
   * stops talking announces nothing, and the socket sits there perfectly
   * healthy while the run behind it is unreachable. That is an idle proxy, a
   * sleeping laptop, a machine that swapped — and before the heartbeat there
   * was nothing in this client that could tell it from thinking.
   */
  | 'beats-then-quiet'
  /**
   * The `ACCEPTED`, one event, and then silence — with no beat ever sent.
   *
   * <p><b>An older server, and the case that stops this client crying
   * wolf.</b> Heartbeats are new; a server built before them simply does not
   * send any, and a client that read their absence as lost contact would
   * accuse every run on every older deployment of having died. Separate from
   * {@link 'beats-then-quiet'} precisely because the two look identical on
   * the wire after the fact and must not be treated the same.
   */
  | 'quiet-from-the-start'
  /**
   * The `ACCEPTED`, deltas with a HOLE in them, then the real answer.
   *
   * <p><b>The fake drops deltas because the real one does.</b> The server's
   * token queue drops freely by design -- that is what stops a stream of them
   * evicting the event that says a run ended -- so a fake that delivered
   * every token would let this client come to depend on a guarantee it does
   * not have. That is the shape of the fake that accepted any session and the
   * fake that validated no upgrade, met a third time.
   *
   * <p>The deltas here spell something that is <i>not</i> the answer, which
   * is the only way to tell a client that shows the outcome from one that
   * assembled its own and got lucky.
   */
  | 'streams-with-a-hole'
  /**
   * Another job's events either side of this turn's `ACCEPTED`, both of
   * which in practice land before this client knows its own handle.
   *
   * <p>Two runs on one socket, which `JobEvent.job` exists for and which a
   * second terminal or a delegating agent produces without anyone arranging
   * it.
   */
  | 'a-second-run-too'
  /**
   * The `ACCEPTED` and three events, and then the run waits to be stopped:
   * the cancel lands between steps and the ending it files is `CANCELLED`.
   *
   * <p>The ending is <b>withheld until the cancel arrives</b> rather than
   * timed, because a scripted race that depends on a timer is a case that
   * sometimes passes. Nothing about the client's side of it is arranged:
   * it hears its events, somebody presses the key, and the ending comes.
   */
  | 'stops-when-asked'
  /**
   * <b>The case that matters, and the one a lenient fake would hide.</b>
   *
   * <p>The cancel arrives while the run is already finishing, so what the
   * server files is the ending the run had reached — `ANSWERED` — and the
   * cancel changes nothing at all. `JobCancelHandler` says so in as many
   * words: <i>"Cancelling a finished job is not an error. It changes
   * nothing"</i>, and its answer reads `RUNNING` even when it is honoured,
   * because a cancel is taken at a step boundary and is not a kill.
   *
   * <p>A fake that stopped the run the instant it was asked would prove this
   * client right about the one outcome it cannot get wrong. This is the
   * outcome it can: a person told "stopped" about a run that answered has
   * been told something false, and would go looking for an answer that is
   * sitting on their screen.
   */
  | 'finishes-under-the-cancel'
  /**
   * The cancel is answered `OK`, the run goes on, and no ending ever comes.
   *
   * <p>Also inside the contract, and the reason the second Ctrl-C exists: a
   * step can be a model call that takes a minute, and the boundary the cancel
   * is honoured at is on the far side of it. A person who wants out must not
   * be made to wait for the network to agree.
   */
  | 'goes-on-anyway';

/** The answer the scripted agent gives, as the markdown a model would write. */
const ANSWER = [
  '## What I did',
  '',
  'I read `pom.xml` and found **three** modules.',
  '',
  '```',
  'mvn -q test',
  '```',
].join('\n');

/**
 * `project.list`'s answer: every `ProjectView`, with every field it carries.
 *
 * <b>Written from the Java rather than from what this client happens to read</b>
 * — `ProjectView` is `(name, workspace, lent, exclusions)` and all four are
 * here, so a client that started reading a field the server does not send would
 * fail here rather than pass against a fake shaped like its own expectations.
 * Task 2 found exactly that trap one layer down: a parity test agrees when both
 * surfaces are wrong in the same way.
 */
const EVERY_PROJECT: readonly unknown[] = [
  {
    name: 'plowshare',
    workspace: '/srv/plowshare',
    lent: [],
    exclusions: ['/opt/plowshare'],
  },
  {
    name: 'notes',
    workspace: '/srv/notes',
    lent: ['/srv/shared'],
    exclusions: [],
  },
];

/**
 * `conversation.list`'s answer: every `ConversationView`, in row order.
 *
 * <p><b>The first row's `"title": null` is the point of this fixture.</b> The
 * column was added with no backfill, so every conversation that existed before
 * it has a null title permanently and so does every conversation opened and
 * never spoken into — the null is the ordinary row here, not the edge — and
 * `ConversationView` sends it as a present null rather than as an absent key so
 * that a client can tell it from a server too old to have titles. A fixture
 * that quietly omitted the key would prove this client handles a case the real
 * server does not produce.
 */
const EVERY_CONVERSATION: readonly unknown[] = [
  {
    id: 'cnv_3134E666E2D847AD',
    project: null,
    maxModelCalls: 20,
    modelCallsSpent: 0,
    maxTurns: null,
    noTurnCap: false,
    noBudget: false,
    title: null,
  },
  {
    id: 'cnv_9F1A2B3C4D5E6F70',
    project: null,
    maxModelCalls: null,
    modelCallsSpent: 7,
    maxTurns: 8,
    noTurnCap: false,
    noBudget: true,
    title: 'how many modules are there',
  },
];

/**
 * `agent.list`'s answer: every `AgentView`, with every field the record carries.
 *
 * <p><b>Written from the Java rather than from what this client happens to
 * read</b>, which is {@link EVERY_PROJECT}'s rule and the one this branch has
 * broken three times. `AgentView` is `(name, tools, calls, scopes, served,
 * withheld, bot, description, preferred, model)` and all ten are here —
 * including the three this client never looks at — so a fake shaped like the
 * client's own expectations cannot be what proves the client right.
 *
 * <p>The three rows are the three states a roster has: the shipped bot, an
 * ordinary agent, and <b>a definition this server read and refused</b>. The
 * third is `AgentView.disabled`'s own shape — empty declaration lists, `served:
 * false`, the reason in `withheld`, `bot: false` and `description: ''` because
 * a file that failed to parse has no `bot:` or `description:` to report, and
 * `model: null` because it has no `model:` either.
 *
 * <p>The first two descriptions are the shipped definitions' own opening
 * sentences, not a fixture placeholder — the same reason the tool lists above
 * are the real ones and not `['a_tool']`.
 */
const EVERY_AGENT: readonly unknown[] = [
  {
    name: 'aristoxenus',
    tools: [
      'memory_recall',
      'memory_read',
      'memory_write',
      'result_read',
      'result_list',
    ],
    calls: [],
    scopes: [],
    served: true,
    withheld: [],
    bot: true,
    description:
      'Aristoxenus of Tarentum, who broke with the Pythagoreans over whether a' +
      ' harmony is judged by the ear that hears it or by the elegance of the ratio' +
      " behind it, and took the ear's side.",
    preferred: false,
    model: 'reasoning',
  },
  {
    name: 'close_reader',
    tools: ['read_file', 'memory_write'],
    calls: [],
    scopes: ['workspace:read'],
    served: true,
    withheld: [],
    bot: false,
    description:
      'Answers a question about ONE uploaded document, named the way a person' +
      ' names one, rather than by an id nobody has.',
    preferred: false,
    model: 'reasoning',
  },
  {
    name: 'hermippus',
    tools: [],
    calls: [],
    scopes: [],
    served: false,
    withheld: ['bot: expected a boolean, and read "yes"'],
    bot: false,
    description: '',
    preferred: false,
    model: null,
  },
];

/**
 * A conversation this scripted server says the bot is still having, and the log
 * under it.
 *
 * <p><b>The log has a seam in it, and that is the point of the fixture.</b> A
 * fake that only ever answered unfolded turns would prove a client right about
 * the case it cannot get wrong: every turn present, nothing to place, no order
 * to decide. Compaction is the ordinary fate of a long conversation and the
 * seam is the thing a client renders badly — dropped, or put in the wrong
 * place, or dressed up as something that can be opened.
 */
interface Going {
  /** One `ConversationView`, as `conversation.latest` answers with one. */
  readonly conversation: unknown;
  /**
   * The conversation's log, in conversation order — `(turn_ordinal, ordinal)` — as
   * `conversation.trajectory` pages it. A case that pushes about the log appends to it.
   */
  readonly log: LogRow[];
}

/** What a case can vary about the server's world beyond the conversation. */
interface World {
  /** `agent.list` by the payload's project; `''` is the global tier. Unlisted tiers get `roster`. */
  readonly rosters?: Readonly<Record<string, readonly unknown[]>>;
  /** `project.list`'s rows, in place of {@link EVERY_PROJECT}. */
  readonly projects?: readonly unknown[];
  /**
   * `conversation.latest` by agent. When present, an unlisted agent has no
   * continuing conversation; when absent, the older `going` fixture answers
   * every latest request for compatibility with the general conversation
   * cases.
   */
  readonly latestByAgent?: Readonly<Record<string, unknown>>;
  /**
   * Each `/v1/files` upgrade is accepted and then closed 1003 with this reason.
   *
   * <p><b>And every `agent.list` asked while such a close is still on its way
   * is answered only once it has landed.</b> The client now waits for the claim
   * to land before it asks, so a refused claim should never reach a roster at
   * all; this hold is kept because it is what made ruling 13's order
   * deterministic when a close could arrive before or after the roster, and a
   * client that stopped waiting would race again. A fake that let
   * the two race would make the case beside it pass on some runs and not
   * others. Holding the answer until the client has hung up pins the order a
   * refusal is actually tested in: landed before the move commits.
   */
  readonly refusingRoots?: string;
  /** A project whose `/v1/files` upgrade is answered 403, so opening it rejects. */
  readonly unopenable?: string;
  /**
   * How long after the 101 an accepted claim is said to have landed. Default 0:
   * at once, as the real handler does once it has declared and attached — which
   * is still after the client's `open` has fired, and this is how long "after" is.
   */
  readonly landingAfter?: number;
  /**
   * When `conversation.latest` is asked, the newest open file channel is closed
   * 1003 with this reason first, and the answer waits for that close to land —
   * a rooting lost in the last gap before a move commits.
   */
  readonly losingRootOnLatest?: string;
}

/** One row of a conversation's log, as the fake keeps it and `EntryView` writes it. */
interface LogRow {
  readonly ordinal: number;
  readonly turnOrdinal: number;
  readonly [component: string]: unknown;
}

/**
 * One `EntryView`, every component, as `EntryView.of` writes it — written from the Java, on
 * {@link EVERY_PROJECT}'s rule. An utterance is a person's unless `said` names the harness.
 */
function logged(
  ordinal: number,
  turnOrdinal: number,
  kind: string,
  excerpt: string,
  said: {
    readonly speaker?: string;
    readonly speakerName?: string;
    readonly asked?: number;
  } = {},
): LogRow {
  const utterance = kind === 'utterance';
  return {
    ordinal,
    turnOrdinal,
    kind,
    excerpt,
    length: [...Array.from(excerpt)].length,
    cut: false,
    ejectedAt: null,
    supersededBy: null,
    toolCallId: null,
    outcome: null,
    toolCalls: Array.from({ length: said.asked ?? 0 }, (_, at) => ({
      id: `c${at}`,
      name: 'file_read',
      arguments: '{}',
      length: 2,
      cut: false,
      salient: null,
      opened: null,
    })),
    handle: null,
    recordedAt: '2026-09-11T00:00:00Z',
    tookMillis: null,
    dispatch: kind === 'answer' ? 'primary' : null,
    wireModel: null,
    completion: null,
    speaker: utterance ? (said.speaker ?? 'person') : null,
    speakerName: utterance ? (said.speakerName ?? 'someone') : null,
  };
}

/**
 * `conversation.trajectory`'s answer over a log: `EntryPageView`, every component — the page,
 * how many the reading covers, the window it was read through, the log's highest ordinal, the
 * page's smallest, and whether the reading holds anything older.
 *
 * <p>As `EntryStore` reads it: `kinds` narrows the rows and the total and never `through`, and
 * so does `drawn`, which leaves out every answer that asked for tools; a forward reading is in
 * the log's order and says nothing of `more`; `before`, or `tail` for the end, reads newest
 * first by ordinal.
 */
function paged(log: readonly LogRow[], asked: unknown): Reply {
  const window = (asked ?? {}) as {
    after?: number;
    before?: number;
    tail?: boolean;
    kinds?: string[];
    drawn?: boolean;
    offset?: number;
    limit?: number;
  };
  const offset = window.offset ?? 0;
  const limit = Math.min(window.limit ?? 100, 100);
  const kinds = window.kinds;
  const backwards = window.before !== undefined || window.tail === true;
  const covered = log
    .filter((row) => kinds === undefined || kinds.includes(String(row['kind'])))
    .filter(
      (row) =>
        window.drawn !== true ||
        row['kind'] !== 'answer' ||
        !isList(row['toolCalls']) ||
        row['toolCalls'].length === 0,
    )
    .filter((row) =>
      backwards
        ? row.ordinal < (window.before ?? Number.MAX_SAFE_INTEGER)
        : row.ordinal > (window.after ?? 0),
    );
  const ordered = backwards
    ? [...covered].sort((one, other) => other.ordinal - one.ordinal)
    : covered;
  const entries = ordered.slice(offset, offset + limit);
  return {
    code: 'OK',
    payload: {
      entries,
      total: covered.length,
      offset,
      limit,
      through: log.reduce((most, row) => Math.max(most, row.ordinal), 0),
      oldest:
        entries.length === 0
          ? null
          : Math.min(...entries.map((row) => row.ordinal)),
      more: backwards ? covered.length > offset + entries.length : null,
    },
  };
}

/** The entry kinds the chat draws, which every read of the log on screen narrows to. */
const DRAWN = ['utterance', 'answer', 'summary'];

/** The one read that replays a conversation: forty drawn entries, back from the log's end. */
const tailOf = (conversation: string): unknown => ({
  conversation,
  tail: true,
  limit: 40,
  kinds: DRAWN,
  drawn: true,
});

/** The chat's own reads of the log — every one but the tracer's, which names no `kinds`. */
const chatReads = (fake: Fake): unknown[] =>
  fake.heard.frames
    .filter((frame) => frame.type === 'conversation.trajectory')
    .map((frame) => frame.payload)
    .filter((payload) => !tracerRead(payload));

/**
 * `agent.run` for a turn that runs `ls` once: the question and the answer asking for it are
 * pushed together, and once the call is drawn pending its result and the reply land — pushed,
 * unless `pushed` is false — and the run ends. `before` happens just before the result lands.
 */
function runningLs(
  fake: Fake,
  going: Going,
  prompt: ReturnType<typeof scripted>,
  options: {
    readonly pushed?: boolean;
    readonly before?: () => void;
    readonly burst?: number;
  } = {},
): void {
  fake.script('agent.run', (payload) => {
    const task = displayText(
      (payload as { task?: unknown } | undefined)?.task ?? '',
    );
    going.log.push(logged(11, 5, 'utterance', task));
    going.log.push({
      ...logged(12, 5, 'answer', ''),
      toolCalls: [
        {
          id: 'c0',
          name: 'run',
          arguments: '{"command":"ls"}',
          length: 16,
          cut: false,
          salient: 'ls',
          opened: null,
        },
      ],
    });
    setImmediate(() => {
      fake.push(event('job-own', 'started'));
      for (let at = 0; at < (options.burst ?? 1); at += 1) {
        fake.push(appendedTo(CONTINUING, going.log));
      }
    });
    background(
      until(() =>
        prompt.states.some(
          (state) => state?.calls?.some((call) => call.id === 'c0') === true,
        ),
      ).then(() => {
        options.before?.();
        going.log.push({
          ...logged(13, 5, 'tool_result', 'build\ndocs'),
          toolCallId: 'c0',
          outcome: 'ok',
          tookMillis: 53,
        });
        if (options.pushed !== false) {
          fake.push(appendedTo(CONTINUING, going.log));
        }
        going.log.push(logged(14, 5, 'answer', 'Two directories.'));
        if (options.pushed !== false) {
          fake.push(appendedTo(CONTINUING, going.log));
        }
        fake.push(
          event('job-own', 'ended', {
            ending: 'ANSWERED',
            steps: 2,
            modelCalls: 2,
          }),
        );
      }),
    );
    return { code: 'ACCEPTED', payload: { id: 'job-own' } };
  });
}

/** Each trace entry shown, its lines as plain text. */
const tracesIn = (shown: readonly Entry[]): string[] =>
  shown
    .filter((entry) => entry.voice === 'trace')
    .map((entry) => (entry.lines ?? []).map(plainOf).join('\n'));

/** Where this turn's answer is: the bot entry carrying its ending as its note, not a reply the replay drew. */
const ownAnswerIn = (shown: readonly Entry[]): number =>
  shown.findIndex(
    (entry) =>
      entry.voice === 'bot' && entry.note?.startsWith('this run ') === true,
  );

/** Whether a `conversation.trajectory` payload is the tracer's: it names no `kinds`. */
const tracerRead = (payload: unknown): boolean =>
  (payload as { kinds?: unknown } | undefined)?.kinds === undefined;

/** The conversation `STILL_GOING` is, which the live cases push about. */
const CONTINUING = 'cnv_9F1A2B3C4D5E6F70';

/**
 * The conversation the shipped bot has been having: four turns, one fold, and one turn the
 * harness spoke — a run's ending, delivered.
 *
 * <p><b>Written in the order the server reads the log</b>, `(turn_ordinal, ordinal)`: the
 * summary was written last (ordinal 7) but sorts among turn 2, the turn it folds through, so
 * a client that ordered by ordinal, or put the seam at the end, or dropped it, renders
 * something the assertions can see. Turn 3 stopped at its cap: its failed attempt is in the
 * log and must stay hidden, and the runtime's own answer says how it ended.
 */
const STILL_GOING: Going = {
  conversation: {
    id: CONTINUING,
    project: null,
    maxModelCalls: null,
    modelCallsSpent: 7,
    maxTurns: 8,
    noTurnCap: false,
    noBudget: true,
    title: 'how many modules are there',
  },
  log: [
    logged(1, 1, 'utterance', 'what is in this repo'),
    logged(2, 1, 'answer', 'Four modules and a client.'),
    logged(3, 2, 'utterance', 'which module is biggest'),
    logged(4, 2, 'answer', 'The **server**, by far.'),
    logged(
      7,
      2,
      'summary',
      'Asked about the repository: four modules, the server the biggest.',
    ),
    logged(5, 3, 'utterance', 'how long has that been true'),
    logged(
      6,
      3,
      'attempt_failed',
      'TURN_CAP: This run stopped at its turn cap without reaching an answer.',
    ),
    logged(
      8,
      3,
      'answer',
      'This run stopped at its turn cap without reaching an answer.',
    ),
    logged(
      9,
      4,
      'utterance',
      "The orchestration 'build' (id orc_318408A44038F859) stopped: it is stuck.\n\nIts work is left as it stopped.",
      { speaker: 'harness', speakerName: 'orchestration orc_318408A44038F859' },
    ),
    logged(
      10,
      4,
      'answer',
      'The build run got stuck; I have left it as it was.',
    ),
  ],
};

/** The highest of something across a log. */
const highest = (log: readonly LogRow[], of: (row: LogRow) => number): number =>
  log.reduce((most, row) => Math.max(most, of(row)), 0);

/** A run's ending as `Delivery` speaks it into a caller's conversation, and the bot's reply: one harness-started turn. */
function delivered(
  log: LogRow[],
  turn: number,
  run: string,
  reply: string,
): void {
  const next = highest(log, (row) => row.ordinal) + 1;
  log.push(
    logged(
      next,
      turn,
      'utterance',
      `The orchestration 'build' (id ${run}) stopped: it is stuck.\n\nIts work is left as it stopped.`,
      { speaker: 'harness', speakerName: `orchestration ${run}` },
    ),
  );
  log.push(logged(next + 1, turn, 'answer', reply));
}

/** The push `ConversationAppended` sends when a log grows, bare, as `/v1/events` delivers it. */
function appendedTo(conversation: string, log: readonly LogRow[]): string {
  return JSON.stringify({
    kind: 'conversation.appended',
    conversation,
    through: highest(log, (row) => row.ordinal),
  });
}

/** Waits, bounded, until `check` holds — {@link heard}'s shape, failing rather than hanging. */
async function until(check: () => boolean): Promise<void> {
  for (let turn = 0; turn < 400; turn += 1) {
    if (check()) {
      return;
    }
    await new Promise<void>((done) => {
      setTimeout(done, 5);
    });
  }
  throw new Error('what the case was waiting for never happened');
}

/** A surface whose input is a list of steps, each run when the prompt asks, so a case can push between turns. */
function stepping(
  steps: (() => Promise<string | undefined>)[],
): ReturnType<typeof scripted> {
  const surface = scripted([]);
  return {
    ...surface,
    asked: () => steps.shift()?.() ?? Promise.resolve(undefined),
  };
}

/**
 * `agent.run` in the order the server really keeps for a turn in a followed conversation: the
 * turn's entries land in the log, `ConversationAppended` pushes, and only then does `ended` go
 * out on the same socket. The log's answer is deliberately NOT what `job.status` answers, so a
 * case can tell a turn this client streamed from one it re-read out of the log.
 *
 * @param before what else reaches the log first — a harness turn committed just before this one
 */
function speakingInto(
  fake: Fake,
  conversation: string,
  log: LogRow[],
  before?: () => void,
): void {
  let jobs = 0;
  fake.script('agent.run', (payload) => {
    jobs += 1;
    const job = `job-own-${jobs}`;
    before?.();
    const task = displayText(
      (payload as { task?: unknown } | undefined)?.task ?? '',
    );
    const turn = highest(log, (row) => row.turnOrdinal) + 1;
    const next = highest(log, (row) => row.ordinal) + 1;
    log.push(logged(next, turn, 'utterance', task));
    log.push(
      logged(next + 1, turn, 'answer', `the log's own answer to ${task}`),
    );
    setImmediate(() => {
      fake.push(appendedTo(conversation, log));
      fake.push(event(job, 'started'));
      fake.push(
        event(job, 'ended', { ending: 'ANSWERED', steps: 1, modelCalls: 1 }),
      );
    });
    return { code: 'ACCEPTED', payload: { id: job } };
  });
}

/** Turns of a person talking to the build bot, a question and a reply each — the measured case has twenty. */
function aLongConversation(turns: number): LogRow[] {
  const log: LogRow[] = [];
  for (let turn = 1; turn <= turns; turn += 1) {
    log.push(logged(log.length + 1, turn, 'utterance', `question ${turn}`));
    log.push(logged(log.length + 1, turn, 'answer', `reply ${turn}`));
  }
  return log;
}

/**
 * A log longer than one page, written the way a working bot's is: turns that read files between
 * the question and the reply — tool calls and results, hidden from the chat view — and, when
 * `folded`, one fold through turn 15, written after turn 16 and sorted back among turn 15.
 */
function aLogOfPages(turns: number, folded = true): LogRow[] {
  const log: LogRow[] = [];
  let ordinal = 0;
  const next = (): number => {
    ordinal += 1;
    return ordinal;
  };
  let afterFifteen = 0;
  for (let turn = 1; turn <= turns; turn += 1) {
    log.push(logged(next(), turn, 'utterance', `question ${turn}`));
    for (let round = turn % 2 === 1 ? 2 : 1; round > 0; round -= 1) {
      log.push(logged(next(), turn, 'answer', 'let me look', { asked: 1 }));
      log.push(logged(next(), turn, 'tool_result', 'what the file held'));
    }
    log.push(logged(next(), turn, 'answer', `reply ${turn}`));
    if (turn === 15) {
      afterFifteen = log.length;
    }
    if (turn === 16 && folded) {
      log.splice(
        afterFifteen,
        0,
        logged(next(), 15, 'summary', 'Fifteen turns of reading, summarised.'),
      );
    }
  }
  return log;
}

/** How many lines put up were exactly this. */
const times = (said: readonly string[], line: string): number =>
  said.filter((each) => each === line).length;

/** One `JobEvent`, bare — no envelope, which is what `/v1/events` publishes. */
function event(
  job: string,
  kind: string,
  more: Record<string, unknown> = {},
): string {
  return JSON.stringify({ job, kind, agent: 'close_reader', ...more });
}

/** The other run's events, which this session is not following. */
function foreign(kind: string, more: Record<string, unknown> = {}): string {
  return JSON.stringify({
    job: 'job-other',
    kind,
    agent: 'other_reader',
    ...more,
  });
}

/** One enveloped answer to the frame with this id. */
const currentAdministrativeFixtures = JSON.parse(
  readFileSync(
    new URL(
      '../../../test-support/contracts/ws-administrative-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<string, Reply>;
const inspectionFixtures = JSON.parse(
  readFileSync(
    new URL(
      '../../../test-support/contracts/ws-inspection-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
) as Record<string, Reply>;
const retrievalFixtures = (
  JSON.parse(
    readFileSync(
      new URL(
        '../../../test-support/contracts/ws-retrieval-fixtures.json',
        import.meta.url,
      ),
      'utf8',
    ),
  ) as { replies: Record<string, unknown> }
).replies;
// Examples contain intentional questions and batch approvals; older scripted
// scenarios need neutral DTO defaults rather than those example behaviours.
const approvalTemplate = (
  currentAdministrativeFixtures['approval.list']!.payload as {
    approvals: Record<string, unknown>[];
  }
).approvals[0]!;
approvalTemplate['commands'] = null;
approvalTemplate['judged'] = null;
approvalTemplate['defaultPrefix'] = [];
const runTemplate = (
  currentAdministrativeFixtures['orchestration.list']!.payload as {
    orchestrations: Record<string, unknown>[];
  }
).orchestrations[0]!;
for (const key of ['pendingCap', 'parent', 'stalledSince'])
  runTemplate[key] = null;
runTemplate['conductorConversation'] = '';
approvalTemplate['reason'] = '';
const statusTemplate = currentAdministrativeFixtures['orchestration.status']!
  .payload as Record<string, unknown>;
statusTemplate['orchestration'] = runTemplate;
const messageTemplate = (
  statusTemplate['messages'] as Record<string, unknown>[]
)[0]!;
messageTemplate['structure'] = null;

function completeFixture(template: unknown, value: unknown): unknown {
  if (isList(template) && isList(value))
    return value.map((row) => completeFixture(template[0], row));
  if (
    !template ||
    !value ||
    typeof template !== 'object' ||
    typeof value !== 'object' ||
    isList(value)
  )
    return value;
  const result = {
    ...(template as Record<string, unknown>),
    ...(value as Record<string, unknown>),
  };
  for (const [key, held] of Object.entries(value))
    if (key in (template as Record<string, unknown>))
      result[key] = completeFixture(
        (template as Record<string, unknown>)[key],
        held,
      );
  return result;
}
function answer(id: string, type: string, payload: unknown): string {
  // Complete current DTO metadata for older behaviour fixtures. Explicit
  // malformed identities and values are retained for refusal tests.
  const outcome = payload as Reply;
  if (outcome && ['OK', 'CREATED'].includes(outcome.code)) {
    const value = outcome.payload;
    const row = (v: unknown) => v as Record<string, unknown>;
    const conversation = (v: unknown) => ({
      project: null,
      maxModelCalls: null,
      modelCallsSpent: null,
      maxTurns: null,
      noTurnCap: false,
      noBudget: false,
      title: null,
      ...row(v),
    });
    let full = completeFixture(
      currentAdministrativeFixtures[type]?.payload ??
        inspectionFixtures[type]?.payload ??
        retrievalFixtures[type],
      value,
    );
    if (type === 'agent.list' && isList(value))
      full = value.map((v) => ({
        tools: [],
        calls: [],
        scopes: [],
        served: true,
        withheld: [],
        bot: false,
        description: '',
        preferred: false,
        model: null,
        orchestrations: [],
        ...row(v),
      }));
    if (type === 'project.list' && isList(value))
      full = value.map((v) => ({
        machine: null,
        members: [],
        lent: [],
        exclusions: [],
        ...row(v),
      }));
    if (type === 'conversation.list' && isList(value))
      full = value.map(conversation);
    if (
      ['conversation.open', 'conversation.latest'].includes(type) &&
      value &&
      typeof value === 'object'
    )
      full = conversation(value);
    if (type === 'conversation.context' && value && typeof value === 'object') {
      const v = row(value),
        prefix = v['prefix'];
      full = {
        sent: null,
        sentAtTurn: null,
        turns: 0,
        turnsMeasured: 0,
        measuredTurns: [],
        systemPromptTokens: null,
        toolTokens: null,
        messageTokens: null,
        cacheHitRate: null,
        unavailable: [],
        prefix: null,
        ...v,
      };
      if (prefix && typeof prefix === 'object') {
        const p = row(prefix),
          tokens = { tokens: 0, basis: 'fixture', how: 'fixture' };
        (full as Record<string, unknown>)['prefix'] = {
          ...p,
          systemPromptTokens: p['systemPromptTokens'] ?? tokens,
          toolTokens: p['toolTokens'] ?? tokens,
        };
      }
    }
    if (
      ['conversation.chat', 'conversation.trajectory'].includes(type) &&
      value &&
      isList(row(value)['entries'])
    ) {
      const v = row(value),
        entries = (v['entries'] as Record<string, unknown>[]).map((e) => ({
          turnOrdinal: 0,
          excerpt: null,
          length: 0,
          cut: false,
          ejectedAt: null,
          supersededBy: null,
          toolCallId: null,
          toolCalls: [],
          handle: null,
          recordedAt: null,
          tookMillis: null,
          dispatch: null,
          wireModel: null,
          completion: null,
          speaker: null,
          speakerName: null,
          outcome: null,
          ...e,
          ...(isList(e['toolCalls'])
            ? {
                toolCalls: (e['toolCalls'] as Record<string, unknown>[]).map(
                  (c) => ({
                    length: 0,
                    cut: false,
                    salient: null,
                    opened: null,
                    ...c,
                  }),
                ),
              }
            : {}),
        }));
      const ordinals = entries.map((e) =>
        Number((e as Record<string, unknown>)['ordinal']),
      );
      full = {
        offset: 0,
        limit: Math.max(1, entries.length),
        total: entries.length,
        through: Math.max(0, ...ordinals),
        oldest: entries.length ? Math.min(...ordinals) : null,
        more: false,
        ...v,
        entries,
      };
    }
    if (
      type === 'orchestration.record' &&
      value &&
      isList(row(value)['rows'])
    ) {
      const v = row(value),
        entries = (v['rows'] as Record<string, unknown>[]).map((e) => ({
          detail: null,
          ...e,
        }));
      full = {
        ...v,
        limit: v['limit'] ?? 100,
        total: v['total'] ?? entries.length,
        rows: entries,
      };
    }
    payload = { ...outcome, ...(full === undefined ? {} : { payload: full }) };
  }
  return JSON.stringify({
    id,
    type,
    protocol_version: CURRENT_VERSION,
    payload,
  });
}

/** A scripted Plowshare, listening, with the wire it heard. */
interface Fake {
  /** The origin, for a `Talking`'s door. */
  readonly base: string;
  readonly heard: Heard;

  /** Makes this server's account one whose password somebody else chose. */
  flag(): void;

  /** Makes this server one that has never heard of `job.stream`. */
  forgetStreaming(): void;

  /**
   * What `inbox.list` answers with from here on. Empty and zero by default,
   * which is a boring `OK` every case that never mentions the inbox can
   * ignore.
   */
  scriptInbox(items: readonly InboxRow[], unread: number): void;

  /** Makes `inbox.read` answer with this refusal instead of `OK`. */
  refuseInboxRead(said: string): void;

  /**
   * Makes every frame of this type answer with this, from here on. What the
   * scheduling cases script the eleven scheduling frames with; a function
   * sees the payload, for an answer that depends on what was asked, and may
   * answer later — a case that must hold a read open across a turn.
   */
  script(
    type: string,
    reply: Reply | ((payload: unknown) => Reply | Promise<Reply>),
  ): void;

  /** Asks the newest open file channel, as `FileChannelHandler.ask` does, and waits for the reply. */
  askFiles(request: Record<string, unknown>): Promise<Record<string, unknown>>;

  /** How many file channels this server still holds open. */
  openFileChannels(): number;

  /** Writes one bare frame down the newest events socket, as a job's event arrives. */
  push(frame: string): void;

  stop(): Promise<void>;
}

/** An answer's code, and the sentence or payload it carries. */
interface Reply {
  readonly code: string;
  readonly said?: string;
  readonly payload?: unknown;
}

/** One `InboxItem`, exactly as `InboxListHandler` would write it. */
interface InboxRow {
  readonly id: string;
  readonly arrivedAt: string;
  readonly ending: string;
  readonly answer: string;
}

/**
 * One server, scripted turn by turn, listening on a port of its own.
 *
 * <p>A factory rather than a `beforeAll`, so that no case reads another's wire
 * and no case depends on the order vitest runs them in. The cost is one listen
 * and one close per case, which is a millisecond.
 */
async function plowshare(
  moves: readonly Move[],
  roster: readonly unknown[] = EVERY_AGENT,
  going?: Going,
  world: World = {},
): Promise<Fake> {
  const heard: Heard = {
    paths: [],
    frames: [],
    bearing: new Map(),
    cookied: new Map(),
    passwords: [],
    subscribed: [],
    tickets: [],
    sessions: [],
    unrouted: [],
    wrote: [],
    files: [],
    refused: [],
    landings: [],
  };
  const upgraded: Stream[] = [];
  /** The file channels still open, newest last. */
  const fileStreams: Stream[] = [];
  /** The events sockets, newest last, for {@link Fake.push}. */
  const eventStreams: Stream[] = [];
  /** Who is waiting for a file reply, by the request's id. */
  const replies = new Map<string, (reply: Record<string, unknown>) => void>();
  /** Refused file channels whose close has not landed yet. See {@link World.refusingRoots}. */
  const refusing = new Set<Promise<void>>();
  /** Which turn this is, so each one can be scripted on its own. */
  let turns = 0;
  /**
   * The run whose ending is still to come, for the moves that withhold one.
   *
   * <p>What `job.cancel` acts on: a cancel is about a run, and this server
   * has to know which one it is holding the ending of before it can decide
   * what arriving now does to it.
   */
  /** Whether this server's account still has a password somebody else chose. */
  let flagged = false;
  /** Whether this server has heard of `job.stream` at all. */
  let knowsStreaming = true;
  /** How many pairs this server has issued, and the one refresh token still live. */
  let rotations = 0;
  let live: string | undefined;
  let holding:
    | { readonly job: string; readonly move: Move; readonly stream: Stream }
    | undefined;
  /** Runs this server really stopped, which `job.status` then reports. */
  const stopped = new Set<string>();
  /**
   * What `inbox.list` answers with, once `scriptInbox` has been called.
   * `undefined` until then, so a case that never mentions the inbox gets the
   * same generic "this fake server does not answer" refusal as any other
   * frame nothing scripted for — which is also the shape of the real
   * `BAD_REQUEST` `InboxListHandler` sends a socket with no account.
   */
  let inboxItems: readonly InboxRow[] | undefined;
  let inboxUnread = 0;
  /** Set to make `inbox.read` answer with a refusal instead of `OK`. */
  let inboxReadRefusal: string | undefined;
  /** Frames answered by script, by type. See {@link Fake.script}. */
  const scriptedReplies = new Map<
    string,
    Reply | ((payload: unknown) => Reply | Promise<Reply>)
  >();

  function auth(request: IncomingMessage, response: ServerResponse): void {
    const path = (request.url ?? '').split('?')[0] ?? '';
    heard.paths.push(path);
    heard.bearing.set(path, String(request.headers['authorization'] ?? ''));
    heard.cookied.set(path, String(request.headers['cookie'] ?? ''));
    if (path === '/v1/auth/login') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      // A FLAGGED ACCOUNT GETS A TOKEN AND NO REFRESH, which is what the
      // real server does: the access token is live precisely so that
      // `/v1/auth/password` can be called with it, and the refresh is
      // withheld so the restricted chain cannot be rotated into an
      // unrestricted one.
      response.end(
        JSON.stringify(
          flagged
            ? {
                access: 'access-flagged',
                refresh: null,
                mustChangePassword: true,
              }
            : { access: 'access-1', refresh: 'refresh-1' },
        ),
      );
      if (!flagged) {
        rotations = 1;
        live = 'refresh-1';
      }
      return;
    }
    if (path === '/v1/auth/password') {
      let body = '';
      request.on('data', (chunk: Buffer) => {
        body += chunk.toString('utf8');
      });
      request.on('end', () => {
        const asked = JSON.parse(body || '{}') as Record<string, unknown>;
        heard.passwords.push(displayText(asked['newPassword'] ?? ''));
        // The real server's own list, and the real 400. A fake that
        // accepted anything would let this client ship a loop that
        // cannot recover from the one refusal it will actually meet.
        if (
          PLACEHOLDERS.has(
            displayText(asked['newPassword'] ?? '').toLowerCase(),
          )
        ) {
          response.writeHead(400);
          response.end();
          return;
        }
        // Changed, and the flag comes off — so the sign-in that follows
        // is an ordinary one, exactly as the real server behaves.
        flagged = false;
        response.writeHead(204);
        response.end();
      });
      return;
    }
    if (path === '/v1/auth/refresh') {
      // 204 with no body and the rotated pair in Set-Cookie, which is what
      // the real endpoint does and the reason `auth.ts` carries five lines
      // of cookie parser.
      //
      // <b>AND IT ROTATES FOR REAL.</b> Each refresh issues the next pair,
      // and presenting a refresh token already rotated away is a 401 — the
      // real server's reuse rule. A fake that always issued the same pair
      // let a client that lost a rotation pass every case here.
      const presented = /(?:^|;\s*)ps_refresh=([^;]*)/.exec(
        String(request.headers['cookie'] ?? ''),
      )?.[1];
      if (live === undefined || presented !== live) {
        heard.refused.push(presented ?? '');
        response.writeHead(401);
        response.end();
        return;
      }
      rotations += 1;
      live = `refresh-${rotations}`;
      response.writeHead(204, {
        'Set-Cookie': [
          `ps_access=access-${rotations}; Path=/; HttpOnly`,
          `ps_refresh=${live}; Path=/; HttpOnly`,
        ],
      });
      response.end();
      return;
    }
    if (path === '/v1/auth/ticket') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ ticket: 'tkt-1' }));
      return;
    }
    response.writeHead(404);
    response.end();
  }

  /** One write down the socket, labelled so assertions can read the order. */
  function wrote(stream: Stream, label: string, frame: Buffer): void {
    heard.wrote.push(label);
    stream.write(frame);
  }

  /** One `agent.run`, answered in whichever order this turn's move names. */
  function ran(stream: Stream, id: string, move: Move): void {
    turns += 1;
    const job = `job-${turns}`;
    const events = [
      event(job, 'started'),
      event(job, 'model_call', { steps: 1, modelCalls: 1 }),
      event(job, 'tool_called', { tool: 'plowshare_memory_write' }),
      event(job, 'ended', { ending: 'ANSWERED', steps: 2, modelCalls: 2 }),
    ];
    const accepted = textFrame(
      answer(id, 'agent.run', {
        code: 'ACCEPTED',
        payload: { id: job },
      }),
    );
    const push = (): void => {
      for (const each of events) {
        wrote(stream, `event ${job}`, textFrame(each));
      }
    };
    // THE INTERLEAVING, SCRIPTED. `events-first` has the events outrun the
    // answer that caused them; `answer-first` has them follow it.
    //
    // <b>`setImmediate` on the second, and it is load-bearing rather than
    // tidy.</b> Written back-to-back, both orders reach the client the
    // same way: the socket delivers all five frames before the microtask
    // that resolves `ask` gets to run, so even the answer-first script is
    // an events-before-answer case at the view. Measured — with the
    // holding-area handoff broken in `main.ts`, a back-to-back
    // answer-first turn hung exactly like the other one. A tick between
    // them is what makes that move genuinely the case it claims to be:
    // the handle is known before its first event arrives.
    if (move === 'events-first') {
      push();
      wrote(stream, `accepted ${job}`, accepted);
      return;
    }
    if (move === 'events-overflow') {
      push();
      // Other sessions can crowd out our completion before ACCEPTED.
      for (let n = 0; n < 200; n++) {
        wrote(
          stream,
          `foreign ${n}`,
          textFrame(event(`foreign-${n}`, 'started')),
        );
      }
      wrote(stream, `accepted ${job}`, accepted);
      return;
    }
    if (move === 'drops-before-handle') {
      wrote(stream, 'close', closeFrame());
      stream.end();
      return;
    }
    if (move === 'drops-mid-run') {
      wrote(stream, `accepted ${job}`, accepted);
      setImmediate(() => {
        wrote(stream, `event ${job}`, textFrame(events[0] ?? ''));
        wrote(stream, `event ${job}`, textFrame(events[1] ?? ''));
        // And then the wire goes away, with no `ended` behind it —
        // which is every idle cutoff, sleep, deploy and restart there
        // is, and which used to leave this client waiting forever.
        wrote(stream, 'close', closeFrame());
        stream.end();
      });
      return;
    }
    if (move === 'beats-then-quiet') {
      wrote(stream, `accepted ${job}`, accepted);
      setImmediate(() => {
        // Started, one beat, and then silence with the socket still
        // open. NO `ended` and NO close: the run is simply unreachable
        // and nothing says so.
        wrote(stream, `event ${job}`, textFrame(events[0] ?? ''));
        // THE BEAT IS BUILT HERE RATHER THAN ADDED TO `events`, and
        // that is not tidiness. Three other scripts index that array by
        // position and two cases assert its rendering exactly --
        // inserting a beat into it shifted every one of them and broke
        // five tests that have nothing to do with heartbeats.
        wrote(
          stream,
          `event ${job}`,
          textFrame(event(job, 'alive', { steps: 1, modelCalls: 1 })),
        );
        // And then nothing. The socket stays open.
      });
      return;
    }
    if (move === 'streams-with-a-hole') {
      wrote(stream, `accepted ${job}`, accepted);
      setImmediate(() => {
        wrote(stream, `event ${job}`, textFrame(events[0] ?? ''));
        // A TICK BEFORE THE DELTAS, AND IT IS THE SAME KIND OF
        // LOAD-BEARING AS THE ONE IN `answer-first`. `connection.ask`
        // resolves in a microtask and `answering` runs in the
        // continuation after it, so frames written back-to-back with
        // the ACCEPTED all reach this client while `turn.job` is still
        // undefined -- and an unattributable delta is DROPPED rather
        // than held, unlike an event. Without this tick the case would
        // assert nothing about attribution and everything about timing.
        setTimeout(() => {
          // Thinking, then a partial answer. `## What I di` is the
          // real answer's opening with its last characters missing --
          // a hole, exactly as a full token queue produces one.
          wrote(
            stream,
            `delta ${job}`,
            textFrame(
              JSON.stringify({
                job,
                part: 'THINKING',
                text: 'Let me count them.',
              }),
            ),
          );
          wrote(
            stream,
            `delta ${job}`,
            textFrame(
              JSON.stringify({ job, part: 'ANSWER', text: '## What I di' }),
            ),
          );
          for (const each of events.slice(1)) {
            wrote(stream, `event ${job}`, textFrame(each));
          }
        }, 20);
      });
      return;
    }
    if (move === 'quiet-from-the-start') {
      wrote(stream, `accepted ${job}`, accepted);
      setImmediate(() => {
        // One ordinary event and then nothing, ever. No beat, no
        // ending, no close.
        wrote(stream, `event ${job}`, textFrame(events[0] ?? ''));
      });
      return;
    }
    if (move === 'a-second-run-too') {
      // ONE BEFORE THE ACCEPTED AND ONE AFTER IT, and the interesting
      // part is that BOTH of them reach the client while it still does
      // not know its own handle. Measured, in the failure this case
      // produced before `main.ts`'s gate was fixed: the scrollback
      // carried `other_reader started` AND `other_reader called
      // other_tool`, and the second of those was written after the
      // `ACCEPTED` that names this turn's job.
      //
      // That is the whole of why the old gate was a real bug rather
      // than a narrow one. `connection.ask` resolves in a microtask and
      // `answering` runs in the continuation after it, so every frame
      // the socket has already delivered — the answer included — is
      // handled with `turn.job` still undefined. <b>The unknown-handle
      // window is the normal path, not a sliver</b>, which is what a
      // whole-plan review measured independently and what this case
      // reproduces.
      wrote(stream, 'event job-other', textFrame(foreign('started')));
      wrote(stream, `accepted ${job}`, accepted);
      setImmediate(() => {
        push();
        wrote(
          stream,
          'event job-other',
          textFrame(foreign('tool_called', { tool: 'other_tool' })),
        );
      });
      return;
    }
    if (
      move === 'stops-when-asked' ||
      move === 'finishes-under-the-cancel' ||
      move === 'goes-on-anyway'
    ) {
      // EVERYTHING BUT THE ENDING, which `job.cancel` below decides the
      // fate of. The tick matters for the same reason it does above: the
      // handle has to be known before the events land, so that a person
      // pressing Ctrl-C over one of those lines is pressing it against a
      // run this client can name.
      holding = { job, move, stream };
      wrote(stream, `accepted ${job}`, accepted);
      setImmediate(() => {
        for (const each of events.slice(0, 3)) {
          wrote(stream, `event ${job}`, textFrame(each));
        }
      });
      return;
    }
    wrote(stream, `accepted ${job}`, accepted);
    setImmediate(push);
  }

  /**
   * One `job.cancel`, answered the way `JobCancelHandler` answers it.
   *
   * <p><b>Three properties of the real handler, and every one of them is
   * load-bearing here.</b> A job nobody holds is a 404 and nothing is
   * cancelled. A job that is held is answered `OK` with the job <i>as it
   * stands</i> — `state` still `RUNNING`, `cancelRequested` true — because
   * the request is honoured between steps and a premature `DONE` is a client
   * that stops listening to a run still going. And cancelling a run that has
   * already finished is not an error and changes nothing, which is the whole
   * of {@link Move} `finishes-under-the-cancel`.
   */
  function cancelled(stream: Stream, id: string, named: unknown): void {
    const run = holding;
    if (run === undefined || named !== run.job) {
      wrote(
        stream,
        'job.cancel refused',
        textFrame(
          answer(id, 'job.cancel', {
            code: 'NOT_FOUND',
            said: `no job ${String(named)}. Nothing was stopped.`,
          }),
        ),
      );
      return;
    }
    if (run.move !== 'goes-on-anyway') {
      // THE ENDING, WRITTEN BEFORE THE ANSWER TO THE CANCEL. Which of the
      // two endings it is, is the whole difference between the case this
      // client must get right and the case it cannot get wrong: a run
      // that stops when asked files CANCELLED, and one that was already
      // finishing files what it had reached.
      const ending = run.move === 'stops-when-asked' ? 'CANCELLED' : 'ANSWERED';
      if (ending === 'CANCELLED') {
        stopped.add(run.job);
      }
      wrote(
        stream,
        `event ${run.job}`,
        textFrame(
          event(run.job, 'ended', {
            ending,
            steps: 2,
            modelCalls: 2,
          }),
        ),
      );
    }
    // <b>Written from the Java</b>, which is {@link EVERY_PROJECT}'s rule:
    // `JobView` is `(id, agent, state, cancelRequested, conversation,
    // outcome, limits)` and all seven are here, including the four this
    // client never reads off a cancel.
    wrote(
      stream,
      'job.cancel answer',
      textFrame(
        answer(id, 'job.cancel', {
          code: 'OK',
          payload: {
            id: run.job,
            agent: 'close_reader',
            state: 'RUNNING',
            cancelRequested: true,
            conversation: 'c-1',
            outcome: null,
            limits: {
              modelCallsSpent: 1,
              maxModelCalls: 40,
              noBudget: false,
              maxTurns: 8,
              noTurnCap: false,
            },
          },
        }),
      ),
    );
  }

  /** The frames this server answers, and the order it answers them in. */
  function spokenBy(stream: Stream, message: string): void {
    const asked = JSON.parse(message) as {
      id: string;
      type: string;
      payload: unknown;
    };
    heard.frames.push({ type: asked.type, payload: asked.payload });
    const scriptedReply = scriptedReplies.get(asked.type);
    if (scriptedReply !== undefined) {
      const reply =
        typeof scriptedReply === 'function'
          ? scriptedReply(asked.payload)
          : scriptedReply;
      if (reply instanceof Promise) {
        background(
          reply.then((later) => {
            wrote(
              stream,
              `${asked.type} scripted`,
              textFrame(answer(asked.id, asked.type, later)),
            );
          }),
        );
        return;
      }
      wrote(
        stream,
        `${asked.type} scripted`,
        textFrame(answer(asked.id, asked.type, reply)),
      );
      return;
    }
    if (asked.type === 'agent.list') {
      const tier =
        (asked.payload as { project?: string } | null)?.project ?? '';
      heard.landings.push(`agent.list ${tier}`);
      const listed = (): void => {
        wrote(
          stream,
          'agent.list answer',
          textFrame(
            answer(asked.id, asked.type, {
              code: 'OK',
              payload: (world.rosters?.[tier] ?? roster).map((row) => ({
                orchestrations: [],
                ...(row as Record<string, unknown>),
              })),
            }),
          ),
        );
      };
      if (refusing.size > 0) {
        background(Promise.all([...refusing]).then(listed));
        return;
      }
      listed();
      return;
    }
    if (asked.type === 'project.list') {
      wrote(
        stream,
        'project.list answer',
        textFrame(
          answer(asked.id, asked.type, {
            code: 'OK',
            payload: world.projects ?? EVERY_PROJECT,
          }),
        ),
      );
      return;
    }
    if (asked.type === 'conversation.list') {
      wrote(
        stream,
        'conversation.list answer',
        textFrame(
          answer(asked.id, asked.type, {
            code: 'OK',
            payload: EVERY_CONVERSATION,
          }),
        ),
      );
      return;
    }
    if (asked.type === 'conversation.latest') {
      // NO PAYLOAD AT ALL FOR A BOT WITH NO CONVERSATION HERE, which is
      // what `Outcome` really writes: it is NON_NULL, so the absence
      // travels as a missing key rather than as `"payload": null`, and a
      // fake that sent an explicit null would be proving this client
      // handles a shape the server does not produce.
      const latest = (): void => {
        const agent =
          (asked.payload as { agent?: string } | undefined)?.agent ?? '';
        const conversation =
          world.latestByAgent === undefined
            ? going?.conversation
            : world.latestByAgent[agent];
        wrote(
          stream,
          'conversation.latest answer',
          textFrame(
            answer(
              asked.id,
              asked.type,
              conversation === undefined
                ? { code: 'OK' }
                : { code: 'OK', payload: conversation },
            ),
          ),
        );
      };
      const losing = fileStreams[fileStreams.length - 1];
      if (world.losingRootOnLatest !== undefined && losing !== undefined) {
        losing.on('close', latest);
        losing.end(closeFrameWith(1003, world.losingRootOnLatest));
        return;
      }
      latest();
      return;
    }
    if (asked.type === 'conversation.follow') {
      wrote(
        stream,
        'conversation.follow answer',
        textFrame(answer(asked.id, asked.type, { code: 'OK' })),
      );
      return;
    }
    if (asked.type === 'conversation.trajectory') {
      wrote(
        stream,
        'conversation.trajectory answer',
        textFrame(
          answer(asked.id, asked.type, paged(going?.log ?? [], asked.payload)),
        ),
      );
      return;
    }
    if (asked.type === 'conversation.context') {
      // `ContextView` with every component, priced against the agent
      // asked about: the shipped definitions' `reasoning`, on a node
      // loaded at 120K.
      const payload = asked.payload as { agent?: string } | undefined;
      wrote(
        stream,
        'conversation.context answer',
        textFrame(
          answer(asked.id, asked.type, {
            code: 'OK',
            payload: {
              sent: 16_234,
              sentAtTurn: 1,
              turns: 1,
              turnsMeasured: 1,
              measuredTurns: [
                { turn: 1, promptTokens: 16_234, grewBy: null, since: null },
              ],
              systemPromptTokens: null,
              toolTokens: null,
              messageTokens: null,
              cacheHitRate: null,
              unavailable: [],
              prefix: {
                agent: payload?.agent ?? null,
                model: 'reasoning',
                systemPromptCharacters: 900,
                toolCharacters: 4_000,
                systemPromptTokens: null,
                toolTokens: null,
                tools: [],
                contextLength: 120_000,
              },
            },
          }),
        ),
      );
      return;
    }
    if (asked.type === 'conversation.open') {
      wrote(
        stream,
        'conversation.open answer',
        textFrame(
          answer(asked.id, asked.type, {
            code: 'OK',
            payload: { id: 'c-1' },
          }),
        ),
      );
      return;
    }
    if (asked.type === 'agent.run') {
      // <b>Routed by session, exactly as the server routes.</b>
      // `JobEvents.publish` delivers "to whatever holds the listener role
      // on `session`, or drop[s] it", so a run that names no session has
      // every event -- including its ENDED -- dropped, and a client waits
      // for ever. That shipped: this fake used to emit to whatever socket
      // was open, so a client that never named a session passed every
      // test here and then hung against the first real server, with the
      // answer sitting finished on the other side.
      const named = (asked.payload as { session?: unknown } | undefined)
        ?.session;
      if (typeof named !== 'string' || named !== LISTENING_AS) {
        heard.unrouted.push(asked.id);
        return;
      }
      ran(
        stream,
        asked.id,
        moves[turns] ?? moves[moves.length - 1] ?? 'answer-first',
      );
      return;
    }
    if (asked.type === 'job.stream' && knowsStreaming) {
      // ANSWERED AND RECORDED. A fake that refused this would make every
      // case assert around a refusal entry; one that accepted it without
      // recording would let a client ship a subscription nobody could
      // prove it sent -- which is what happened, for as long as it took
      // to notice the whole feature was built and switched off.
      heard.subscribed.push(asked.payload);
      wrote(
        stream,
        `answer ${asked.id}`,
        textFrame(answer(asked.id, asked.type, { code: 'OK' })),
      );
      return;
    }
    if (asked.type === 'job.cancel') {
      cancelled(
        stream,
        asked.id,
        (asked.payload as { job?: unknown } | undefined)?.job,
      );
      return;
    }
    if (asked.type === 'job.status') {
      // A run that really stopped when it was asked to reports what it
      // stopped as, and reports no answer: there was none. A fake that
      // answered ANSWERED here whatever had happened would let a client
      // print an answer to a run it had just stopped.
      const named = (asked.payload as { job?: unknown } | undefined)?.job;
      const cut = typeof named === 'string' && stopped.has(named);
      wrote(
        stream,
        'job.status answer',
        textFrame(
          answer(asked.id, asked.type, {
            code: 'OK',
            payload: {
              id: named,
              state: 'DONE',
              outcome: {
                ending: cut ? 'CANCELLED' : 'ANSWERED',
                answered: !cut,
                steps: 2,
                modelCalls: 2,
                text: cut ? '' : ANSWER,
                // `OutcomeView.pace`, every component: null for a run
                // that measured nothing, and a reasoning count this
                // endpoint did not report.
                pace: cut
                  ? null
                  : {
                      toolCalls: 1,
                      completionTokens: 1_210,
                      reasoningTokens: null,
                      firstTokenMillis: 1_830,
                      tokensPerSecond: 38.4,
                      reasoningEstimated: false,
                    },
              },
              limits: {
                modelCallsSpent: 2,
                maxModelCalls: 40,
                noBudget: false,
                maxTurns: 8,
                noTurnCap: false,
              },
            },
          }),
        ),
      );
      return;
    }
    if (asked.type === 'inbox.list' && inboxItems !== undefined) {
      wrote(
        stream,
        'inbox.list answer',
        textFrame(
          answer(asked.id, asked.type, {
            code: 'OK',
            payload: { items: inboxItems, unread: inboxUnread },
          }),
        ),
      );
      return;
    }
    if (asked.type === 'inbox.read') {
      if (inboxReadRefusal !== undefined) {
        wrote(
          stream,
          'inbox.read refused',
          textFrame(
            answer(asked.id, asked.type, {
              code: 'BAD_REQUEST',
              said: inboxReadRefusal,
            }),
          ),
        );
        return;
      }
      if (inboxItems !== undefined) {
        const marked =
          (asked.payload as { items?: readonly string[] } | undefined)?.items
            ?.length ?? 0;
        wrote(
          stream,
          'inbox.read answer',
          textFrame(
            answer(asked.id, asked.type, {
              code: 'OK',
              payload: { marked, unread: Math.max(0, inboxUnread - marked) },
            }),
          ),
        );
        return;
      }
    }
    stream.write(
      textFrame(
        answer(asked.id, asked.type, {
          code: 'NOT_FOUND',
          said: `this fake server does not answer ${asked.type}`,
        }),
      ),
    );
  }

  const server: Server = createServer(auth);
  server.on('upgrade', (request, stream: Stream) => {
    upgraded.push(stream);
    const url = request.url ?? '';
    const asked = new URL(url, 'http://x').searchParams;
    const pathname = new URL(url, 'http://x').pathname;
    if (pathname === '/v1/files') {
      heard.files.push({
        session: asked.get('session') ?? '',
        ticket: asked.get('ticket') ?? '',
        project: asked.get('project') ?? '',
        machine: asked.get('machine') ?? '',
        root: asked.get('root') ?? '',
      });
      stream.on('error', () => undefined);
      if (
        world.unopenable !== undefined &&
        asked.get('project') === world.unopenable
      ) {
        stream.write('HTTP/1.1 403 Forbidden\r\n\r\n');
        stream.end();
        return;
      }
      const key = String(request.headers['sec-websocket-key'] ?? '');
      const accept = createHash('sha1')
        .update(key + GUID)
        .digest('base64');
      stream.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
          `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
      );
      if (world.refusingRoots !== undefined) {
        const landed = new Promise<void>((done) => {
          stream.on('close', () => done());
        });
        // READ TO THE END, or the client's own hang-up is never seen:
        // a stream nobody reads stays paused, and a paused stream emits
        // no 'end' and so no 'close'.
        stream.resume();
        refusing.add(landed);
        background(landed.then(() => refusing.delete(landed)));
        stream.write(closeFrameWith(1003, world.refusingRoots));
        stream.end();
        return;
      }
      fileStreams.push(stream);
      // THE CLAIM LANDS, said as `FileChannelHandler.ready` says it — and
      // only to a client that asked, as the real handler only tells one.
      if (asked.get('ready') === '1') {
        const project = asked.get('project') ?? '';
        setTimeout(() => {
          if (!stream.destroyed) {
            heard.landings.push(`ready ${project}`);
            stream.write(textFrame(JSON.stringify({ ready: true, project })));
          }
        }, world.landingAfter ?? 0);
      }
      let buffered: Buffer = Buffer.alloc(0);
      stream.on('data', (chunk: Buffer) => {
        buffered = Buffer.concat([buffered, chunk]);
        const { messages, rest, closed } = read(buffered);
        buffered = rest;
        for (const message of messages) {
          const reply = JSON.parse(message) as { id?: string };
          replies.get(reply.id ?? '')?.(reply);
        }
        // A CLIENT THAT HANGS UP IS ANSWERED, as the real channel answers
        // it: the close frame back, then the end. Left unanswered, the
        // client's `close` never fires and the rooter waits out its
        // patience on every move — and a released channel looks held.
        if (closed === true) {
          stream.end(closeFrame());
        }
      });
      stream.on('close', () => {
        const at = fileStreams.indexOf(stream);
        if (at >= 0) {
          fileStreams.splice(at, 1);
        }
      });
      return;
    }
    heard.tickets.push(asked.get('ticket') ?? '');
    heard.sessions.push(asked.get('session') ?? '');
    eventStreams.push(stream);

    // <b>Refused exactly as the real handler refuses it.</b>
    // EventChannelHandler closes any socket that opens without a session
    // -- "a listener on a session nothing can name is one no job will ever
    // reach" -- and this fake used to accept whatever it was sent. That is
    // why a client that never sent one passed every test here and was
    // closed by the first real server it met: a fake that validates
    // nothing tests a client against its own assumptions.
    if ((asked.get('session') ?? '') === '') {
      stream.write('HTTP/1.1 400 Bad Request\r\n\r\n');
      stream.end();
      return;
    }
    const key = String(request.headers['sec-websocket-key'] ?? '');
    const accept = createHash('sha1')
      .update(key + GUID)
      .digest('base64');
    stream.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );
    let buffered: Buffer = Buffer.alloc(0);
    stream.on('data', (chunk: Buffer) => {
      buffered = Buffer.concat([buffered, chunk]);
      const { messages, rest } = read(buffered);
      buffered = rest;
      for (const message of messages) {
        spokenBy(stream, message);
      }
    });
    // A stream the client hung up on, or one this server ended itself. An
    // unhandled 'error' on a socket is an uncaught exception in the
    // process, which would fail whichever case happened to be running.
    stream.on('error', () => undefined);
  });
  await new Promise<void>((listening) => {
    server.listen(0, '127.0.0.1', listening);
  });
  const bound = server.address();
  const base =
    typeof bound === 'object' && bound !== null
      ? `http://127.0.0.1:${bound.port}`
      : '';
  return {
    base,
    heard,
    flag(): void {
      flagged = true;
    },
    forgetStreaming(): void {
      knowsStreaming = false;
    },
    scriptInbox(items: readonly InboxRow[], unread: number): void {
      inboxItems = items;
      inboxUnread = unread;
    },
    refuseInboxRead(said: string): void {
      inboxReadRefusal = said;
    },
    script(
      type: string,
      reply: Reply | ((payload: unknown) => Reply | Promise<Reply>),
    ): void {
      scriptedReplies.set(type, reply);
    },
    askFiles(
      request: Record<string, unknown>,
    ): Promise<Record<string, unknown>> {
      const stream = fileStreams.at(-1);
      if (stream === undefined) {
        return Promise.reject(new Error('no file channel is open'));
      }
      return new Promise((answered, failed) => {
        const id = String(request['id']);
        const timer = setTimeout(
          () => failed(new Error(`no reply to ${id}`)),
          2_000,
        );
        replies.set(id, (reply) => {
          clearTimeout(timer);
          answered(reply);
        });
        stream.write(textFrame(JSON.stringify(request)));
      });
    },
    openFileChannels(): number {
      return fileStreams.length;
    },
    push(frame: string): void {
      const stream = eventStreams.at(-1);
      if (stream !== undefined) {
        wrote(stream, 'pushed', textFrame(frame));
      }
    },
    async stop(): Promise<void> {
      for (const stream of upgraded) {
        stream.destroy();
      }
      await new Promise<void>((closed) => {
        server.close(() => closed());
      });
      expect(
        heard.paths.filter(
          (path) =>
            ![
              '/v1/auth/login',
              '/v1/auth/password',
              '/v1/auth/refresh',
              '/v1/auth/ticket',
            ].includes(path),
        ),
      ).toEqual([]);
    },
  };
}

/** Every server a case started, put down by {@link afterEach}. */
const running: Fake[] = [];

/**
 * The same, with a server too old to know `job.stream`.
 *
 * <p>Its frame router answers NOT_FOUND for anything it has never heard of,
 * which is what the real one does and what every deployment that has not taken
 * this change will do.
 */
async function plowshareRefusingStream(moves: readonly Move[]): Promise<Fake> {
  const fake = await scriptedPlowshare(moves);
  fake.forgetStreaming();
  return fake;
}

/** The same, with an account whose password somebody else chose. */
async function withAFlaggedAccount(moves: readonly Move[]): Promise<Fake> {
  const fake = await scriptedPlowshare(moves);
  fake.flag();
  return fake;
}

/** {@link plowshare}, registered for teardown. What the cases below call. */
async function scriptedPlowshare(
  moves: readonly Move[],
  roster?: readonly unknown[],
  going?: Going,
  world?: World,
): Promise<Fake> {
  const fake = await plowshare(moves, roster, going, world);
  running.push(fake);
  return fake;
}

afterEach(async () => {
  while (running.length > 0) {
    await running.pop()?.stop();
  }
});

/**
 * When somebody presses Ctrl-C, said as a line they would have been reading.
 *
 * <p><b>A line and not a timer</b>, for {@link Move}'s reason: a press
 * scheduled on a clock lands somewhere different on a loaded machine, and a
 * case that sometimes presses in the right place is a case that sometimes
 * passes. Naming the line ties the press to a point in the exchange — "the
 * moment this appeared on their screen, they hit the key" — which is both
 * deterministic and the thing that actually happens.
 */
interface Pressing {
  /** The press happens the first time a line containing this is put up. */
  readonly when: string;
  /** How many presses, because the second one is the way out. Default one. */
  readonly times?: number;
}

/**
 * A surface that hands over scripted lines and keeps everything it was told.
 *
 * <b>It keeps two views of the same traffic and both are load-bearing.</b>
 * {@link said} is the flat line stream these cases were written against —
 * every entry rendered, every note under it, every progress line — and it is
 * what the ordering and parity assertions still read, because the property
 * they hold is about what reaches a person and in what order. {@link shown}
 * and {@link states} are the structured record, and they are how the new
 * assertions tell an answer from a progress line at all: in the flat stream
 * those were indistinguishable, which is precisely the defect this port fixes.
 */
function scripted(
  lines: readonly string[],
  pressing?: Pressing,
): Surface & {
  readonly said: string[];
  readonly shown: Entry[];
  readonly states: (Working | undefined)[];
  /** The names this client offered Tab, which is what a completer completes. */
  readonly offered: string[];
  readonly commandOffers: string[][];
} {
  const queue = [...lines];
  const said: string[] = [];
  const shown: Entry[] = [];
  const states: (Working | undefined)[] = [];
  const offered: string[] = [];
  const commandOffers: string[][] = [];
  let interrupt: (() => void) | undefined;
  let pressed = false;
  /** The flat stream, and the press that a line landing may provoke. */
  const line = (text: string): void => {
    said.push(text);
    if (pressing === undefined || pressed || !text.includes(pressing.when)) {
      return;
    }
    // Once, and from inside the write, which is where a real press
    // happens: the person is reading a line as it lands and reaches for
    // the key.
    pressed = true;
    interrupt?.();
    // AND THE LATER PRESSES ON LATER TURNS OF THE LOOP, because that is
    // what a second press is. Two keypresses cannot reach a program in
    // the same tick, and pressing twice synchronously here made this
    // case prove something false: the `job.cancel` the first press
    // wrote had not left the socket before the second press closed it,
    // so the server never heard the frame a real person's cancel does.
    for (let press = 1; press < (pressing.times ?? 1); press += 1) {
      setImmediate(() => interrupt?.());
    }
  };
  return {
    said,
    shown,
    states,
    offered,
    commandOffers,
    show(entry: Entry): void {
      shown.push(entry);
      const body = toTerminal(entry.body);
      if (body !== '') {
        line(body);
      }
      const note = entry.note;
      if (note !== undefined) {
        line(note);
      }
    },
    working(state: Working | undefined): void {
      states.push(state);
      const progress = state?.said;
      if (progress !== undefined) {
        line(progress);
      }
    },
    asked(): Promise<string | undefined> {
      return Promise.resolve(queue.shift());
    },
    unread: () => {},
    waiting: () => {},
    completing(
      names: readonly string[],
      _details?: Readonly<Record<string, string>>,
      commands: readonly {
        readonly name: string;
        readonly detail: string;
      }[] = [],
    ): void {
      offered.push(...names);
      commandOffers.push(commands.map((command) => command.name));
    },
    onInterrupt(listener: () => void): void {
      interrupt = listener;
    },
    close(): void {
      said.push('(closed)');
    },
  };
}

/** The two questions the parity case asks, named so `turnsIn` can drop them. */
const ASKED = ['how many modules?', 'and what did you run?'] as const;

/**
 * The scrollback cut into one list of lines per turn, the opening line dropped.
 *
 * <p>A turn's last line is its summary, and `describeCost`'s em dash is what
 * makes that line findable without the view having to mark it. Everything from
 * after the previous summary through the next one is what that turn put on the
 * screen, which is the thing the two delivery orders have to agree about.
 */
function turnsIn(
  said: readonly string[],
  asked: readonly string[] = [],
): string[][] {
  const turns: string[][] = [];
  let current: string[] = [];
  for (const line of said) {
    // Preamble, not part of any turn: the banner printed before anything
    // is opened, and the id printed when the first message opens it.
    if (
      line.startsWith('conversation ') ||
      line.startsWith('new conversation') ||
      line.startsWith('talking to ')
    ) {
      continue;
    }
    // AND THE PERSON'S OWN LINE, which is part of the turn but is the one
    // part of it that SHOULD differ between two turns — they asked two
    // different questions. The property here is that the two delivery
    // orders put the same things on the screen in the same order; the
    // questions are what is being varied, not what is being compared.
    if (asked.includes(line)) {
      continue;
    }
    current.push(line);
    if (line.startsWith('this run ') && line.includes(' — ')) {
      turns.push(current);
      current = [];
    }
  }
  return turns;
}

/**
 * The same, with nobody named — which is now the ordinary way to start.
 *
 * <p>`Talking.agent` is optional rather than empty-string-able, so the absence
 * is a deleted key and not a blank. A `''` would be a name this client was
 * given, and the whole point of the optional field is that there is a
 * difference between being given nothing and being given nothing useful.
 */
function withNoAgentNamed(
  talking: Parameters<typeof converse>[0],
): Parameters<typeof converse>[0] {
  const { agent: _agent, ...rest } = talking;
  return rest;
}

/**
 * Waits until the scripted server has heard this frame, or gives up.
 *
 * <p>For the one case that leaves with a frame in flight. A fixed wait would be
 * a race on a loaded machine and a poll that never gives up would be a hang, so
 * this is bounded and its failure is the assertion's to report — a frame that
 * never arrives fails on the frames list, naming what was missing.
 */
async function heard(fake: Fake, type: string): Promise<void> {
  for (let turn = 0; turn < 50; turn += 1) {
    if (fake.heard.frames.some((frame) => frame.type === type)) {
      return;
    }
    await new Promise<void>((done) => {
      setTimeout(done, 2);
    });
  }
}

/** Everything a case needs to drive `converse` against one scripted server. */
function talkingTo(
  fake: Fake,
  surface: Surface,
): Parameters<typeof converse>[0] {
  return {
    door: {
      base: fake.base,
      fetch,
      open: openingSocket,
      session: LISTENING_AS,
    },
    handle: 'someone',
    password: 'a password',
    agent: 'close_reader',
    surface,
  };
}

describe('the whole stack, over a real socket, against a scripted Plowshare', () => {
  it('discovers Personal as its runnable default and carries it into every conversation request', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const personal = 'personal:736f6d656f6e65';
    fake.script('project.list', {
      code: 'OK',
      payload: [
        {
          name: personal,
          kind: 'personal',
          workspace: '/personal',
          machine: null,
          lent: [],
          exclusions: [],
          members: ['someone'],
        },
      ],
    });
    const prompt = scripted(['Make a plan']);
    await converse(talkingTo(fake, prompt));
    expect(
      fake.heard.frames.find((frame) => frame.type === 'agent.list')?.payload,
    ).toMatchObject({ project: personal });
    expect(
      fake.heard.frames.find((frame) => frame.type === 'conversation.open')
        ?.payload,
    ).toMatchObject({ project: personal });
  });
  it('runs all direct memory verbs and conversation search over WS without an agent turn', async () => {
    const fake = await scriptedPlowshare([]);
    const retrieval = (
      JSON.parse(
        await readFile(
          new URL(
            '../../../test-support/contracts/ws-retrieval-fixtures.json',
            import.meta.url,
          ),
          'utf8',
        ),
      ) as { replies: Record<string, unknown> }
    ).replies;
    const commands: readonly [string, string, Record<string, unknown>][] = [
      ['/memory index', 'memory.index', {}],
      ['/memory read mem_1', 'memory.read', { memory: 'mem_1' }],
      [
        '/memory recall build instructions',
        'memory.recall',
        { question: 'build instructions' },
      ],
      [
        '/memory write {"proposal":{"summary":"build","scope":"repo","body":"pnpm build","formedBy":"person","formedWhere":""}}',
        'memory.write',
        {
          proposal: {
            summary: 'build',
            scope: 'repo',
            body: 'pnpm build',
            formedBy: 'person',
            formedWhere: '',
          },
        },
      ],
      [
        '/memory navigate build instructions',
        'memory.navigate',
        { question: 'build instructions' },
      ],
      ['/memory digest', 'memory.digest', {}],
      [
        '/memory curate {"project":"repo"}',
        'agent.curate',
        { project: 'repo' },
      ],
      ['/memory proposals', 'proposal.list', {}],
      [
        '/memory resolve {"proposal":"prp_1","accept":false,"by":"person"}',
        'proposal.resolve',
        { proposal: 'prp_1', accept: false, by: 'person' },
      ],
      ['/memory reconsider', 'proposal.reconsider', {}],
      [
        '/memory invalidate {"memory":"mem_1","reason":"stale","by":"person"}',
        'memory.invalidate',
        { memory: 'mem_1', reason: 'stale', by: 'person' },
      ],
      ['/memory reembed', 'memory.reembed', {}],
      [
        '/search build instructions',
        'conversation.search',
        { q: 'build instructions' },
      ],
      ['/job status job_digest', 'job.status', { job: 'job_digest' }],
      ['/job cancel job_digest', 'job.cancel', { job: 'job_digest' }],
    ];
    for (const [, type] of commands) {
      fake.script(
        type,
        type === 'memory.digest' || type === 'agent.curate'
          ? {
              code: 'ACCEPTED',
              payload: { id: 'job_digest', agent: 'maintenance' },
            }
          : type.startsWith('job.')
            ? {
                code: 'OK',
                payload: {
                  id: 'job_digest',
                  state: 'RUNNING',
                  cancelRequested: true,
                },
              }
            : { code: 'OK', payload: retrieval[type] },
      );
    }
    const prompt = scripted(commands.map(([line]) => line));
    await converse(talkingTo(fake, prompt));
    const types = commands.map(([, type]) => type);
    expect(
      fake.heard.frames.filter((frame) => types.includes(frame.type)),
    ).toEqual(commands.map(([, type, payload]) => ({ type, payload })));
    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'agent.run',
    );
    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'conversation.open',
    );
    expect(fake.heard.paths).toEqual([
      '/v1/auth/login',
      '/v1/auth/refresh',
      '/v1/auth/ticket',
    ]);
    const output = prompt.said.join('\n');
    expect(output).toContain('memory.navigate: incomplete');
    expect(output).toContain('memory.digest: accepted; /job status job_digest');
    expect(output).toContain('job.cancel: cancelling');
    expect(output).toContain('"unsearchable": 2');
  });

  it('shows direct operation refusals and validates input without spending an agent turn', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('memory.read', {
      code: 'NOT_FOUND',
      said: 'No such memory in this archive.',
    });
    const prompt = scripted([
      '/memory read mem_missing',
      '/memory curate',
      '/memory resolve {"proposal":"p","by":"person"}',
      '/memory index {"project":" "}',
    ]);
    await converse(talkingTo(fake, prompt));
    expect(prompt.said.join('\n')).toContain('No such memory in this archive.');
    expect(prompt.said.join('\n')).toContain(
      'proposal.resolve needs accept:true or accept:false',
    );
    expect(
      fake.heard.frames.filter(
        (frame) =>
          frame.type.startsWith('memory.') ||
          frame.type.startsWith('proposal.'),
      ),
    ).toEqual([{ type: 'memory.read', payload: { memory: 'mem_missing' } }]);
    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'agent.run',
    );
  });

  it('runs both retrieval paths over the signed-in socket without selecting an agent or opening a conversation', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('conversation.search', {
      code: 'OK',
      payload: {
        hits: [],
        total: 0,
        offset: 0,
        limit: 10,
        reach: { searched: 3, ejected: 1, recordedOnly: 0 },
        retrieval: {
          requestedMode: 'hybrid',
          effectiveMode: 'hybrid',
          totalMeaning: 'indexed candidates',
          snapshot: null,
          truncated: false,
          complete: false,
          fallback: 'indexing pending',
          coverage: null,
          generation: null,
          queryEmbeddingCalls: 1,
          provenance: 'fixture',
        },
      },
    });
    fake.script('memory.navigate', {
      code: 'OK',
      payload: {
        level: 'fold_summary',
        ids: ['dig_source'],
        text: 'surviving historical summary',
        complete: true,
        modelCalls: 2,
        retrieval: {
          tier: 'global',
          globalFallback: false,
          seedMode: 'tree',
          queryEmbeddingCalls: 1,
          coverage: null,
          fallback: null,
          generation: null,
        },
      },
    });
    const prompt = scripted([
      '/conversation search old decision',
      '/memory navigate old reason',
    ]);
    await converse({
      door: {
        base: fake.base,
        fetch,
        open: openingSocket,
        session: LISTENING_AS,
      },
      handle: 'someone',
      password: 'a password',
      surface: prompt,
    });
    const frames = fake.heard.frames;
    expect(
      frames.filter((frame) => frame.type === 'conversation.search'),
    ).toEqual([
      {
        type: 'conversation.search',
        payload: { q: 'old decision', mode: 'hybrid', offset: 0, limit: 10 },
      },
    ]);
    expect(frames.filter((frame) => frame.type === 'memory.navigate')).toEqual([
      { type: 'memory.navigate', payload: { question: 'old reason' } },
    ]);
    expect(frames.map((frame) => frame.type)).not.toContain('agent.run');
    expect(frames.map((frame) => frame.type)).not.toContain(
      'conversation.open',
    );
    expect(prompt.said.join('\n')).toContain('dig_source');
    expect(prompt.said.join('\n')).toContain('indexing pending');
  });

  it('puts who is answered, where, and how full it is on a surface that has a status line', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['how many modules?']);
    const standings: (Standing | undefined)[] = [];
    await converse(
      talkingTo(fake, { ...prompt, status: (next) => standings.push(next) }),
    );
    const server = fake.base.replace('http://', '');

    // Before anybody speaks there is no conversation to measure, so the
    // roster's model and nothing else; after the turn, the measured load.
    expect(standings[0]).toEqual({
      triplet: `close_reader:${server}:reasoning`,
    });
    expect(standings.at(-1)).toEqual({
      triplet: `close_reader:${server}:reasoning`,
      load: 'peak 16.2K/120K 14%',
      filled: 16_234 / 120_000,
      pace: [
        { kind: 'tools', text: '1' },
        { kind: 'thinking', text: 'think —' },
        { kind: 'responding', text: 'out 1.2K' },
        { kind: 'waiting', text: 'ttft 1.8s' },
        { kind: 'speed', text: '38 tok/s' },
      ],
    });
    expect(
      fake.heard.frames.find((frame) => frame.type === 'conversation.context')
        ?.payload,
    ).toEqual({ conversation: 'c-1', agent: 'close_reader' });
  });

  it('does not ask how full a conversation is for a surface with nowhere to say it', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    await converse(talkingTo(fake, scripted(['how many modules?'])));

    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'conversation.context',
    );
  });

  it('signs in, opens, speaks twice and renders both answers', async () => {
    const fake = await scriptedPlowshare(['events-first', 'answer-first']);
    const prompt = scripted(['how many modules?', 'and what did you run?']);
    await converse(talkingTo(fake, prompt));
    const scrollback = prompt.said.join('\n');

    // The three HTTP calls, in the order openSocket owns.
    expect(fake.heard.paths).toEqual([
      '/v1/auth/login',
      '/v1/auth/refresh',
      '/v1/auth/ticket',
    ]);
    // REFRESH BEFORE TICKET, read off the wire rather than off a spy: the
    // ticket presented `access-2`, which only the refresh's Set-Cookie
    // could have produced. `access-1` here would be the bug the spec says
    // a TUI author gets wrong, and it would loop against a real server.
    expect(fake.heard.bearing.get('/v1/auth/ticket')).toBe('Bearer access-2');
    expect(fake.heard.cookied.get('/v1/auth/refresh')).toBe(
      'ps_refresh=refresh-1',
    );
    expect(fake.heard.tickets).toEqual(['tkt-1']);

    // The frames, in the order a turn is made of — the quiet inbox count
    // first, then the roster, asked once at sign-in before anything could
    // be opened. And a read of how far the log reaches after each turn:
    // this fake pushes nothing about the log, which is what a turn whose
    // push was lost looks like, and that turn's end reads the reach instead.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.open',
      'conversation.follow',
      'agent.run',
      'job.status',
      'conversation.trajectory',
      'agent.run',
      'job.status',
      'conversation.trajectory',
    ]);
    expect(fake.heard.frames[5]?.payload).toEqual({
      agent: 'close_reader',
      conversation: 'c-1',
      task: 'how many modules?',
      // The run names the session this client is listening on. Without it
      // the server publishes every event to a session nobody holds and
      // drops them, and this fake now refuses the run the same way.
      session: LISTENING_AS,
    });

    // And nothing was dropped for want of a session, which is the state a
    // client that forgot one would be in: a finished run it never hears about.
    expect(fake.heard.unrouted).toEqual([]);

    // The rendering, which is what a person actually sees. The heading
    // keeps its level, the strong mark is gone, and the fence is verbatim.
    expect(scrollback).toContain('## What I did');
    expect(scrollback).toContain('I read `pom.xml` and found three modules.');
    expect(scrollback).toContain('mvn -q test');
    expect(scrollback).not.toContain('**three**');
    expect(scrollback).toContain('answered');
    expect(scrollback).toContain('2 steps, 2 model calls');
  });

  it('renders a turn whose events outran its answer exactly like one that did not', async () => {
    const fake = await scriptedPlowshare(['events-first', 'answer-first']);
    const prompt = scripted([...ASKED]);
    await converse(talkingTo(fake, prompt));

    // The premise: the server really did send the two turns in
    // opposite orders. Asserted so that a server which quietly stopped
    // interleaving would fail here, rather than silently weakening the
    // comparison below into a comparison of two identical cases.
    const first = fake.heard.wrote.indexOf('accepted job-1');
    const second = fake.heard.wrote.indexOf('accepted job-2');
    expect(fake.heard.wrote.slice(0, first)).toContain('event job-1');
    expect(fake.heard.wrote.slice(second + 1)).toContain('event job-2');

    // AND THE CLAIM THE NAME MAKES, which this case used to leave
    // unasserted: the two turns put the same lines on the screen, in
    // the same order. Every layer's tolerance of the interleaving is
    // spent on making this true, and it is the property of this client
    // a person would actually notice the loss of.
    const [before, after] = turnsIn(prompt.said, ASKED);
    expect(before).toEqual(after);

    // And not vacuously: what they agree on is the whole turn, the
    // four progress lines included, rather than two empty lists.
    expect(before?.slice(0, 4)).toEqual([
      'close_reader started',
      '1 step, 1 model call',
      'close_reader called plowshare_memory_write',
      'this run answered',
    ]);
    expect(before?.length).toBe(6);
  });

  it('reports the progress of a run while it is running, in order', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['once more']);
    await converse(talkingTo(fake, prompt));

    // IN ORDER, which is what the name says and what two unordered
    // `toContain`s did not check. A client that announced the tool call
    // before the run had started, or that said the same line twice, passed
    // the old form of this case.
    expect(prompt.said.slice(0, 8)).toEqual([
      'talking to close_reader, which is an agent rather than a bot — an agent is' +
        ' written to be consumed, by a harness, by another agent or over MCP, and' +
        ' its answer may be a structure rather than a sentence',
      'new conversation — nothing is kept until you speak',
      // THE PERSON'S OWN LINE, WHICH IS NEW AND BELONGS HERE. A surface
      // that redraws clears the composer on submit, so the transcript has
      // to hold what was typed or it is one half of a conversation.
      // Before, readline's echo stood in for this — an echo a piped stdin
      // never had, which is why old transcripts read as answers with no
      // questions.
      'once more',
      'conversation c-1',
      'close_reader started',
      '1 step, 1 model call',
      'close_reader called plowshare_memory_write',
      'this run answered',
    ]);
  });

  /**
   * <b>The defect the whole port exists for, held where it can be seen.</b>
   *
   * <p>`0 steps, 1 model call` used to go through the same method an answer
   * did, so it landed in the transcript between two things a person had read
   * — and read as something the agent had said. In the flat line stream the
   * two were <i>indistinguishable</i>, which is why no earlier case could
   * have caught this: there was nothing to assert against. `shown` and
   * `states` are what make the difference expressible.
   */
  it('puts run lifecycle in the working state and never in the transcript', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['once more']);
    await converse(talkingTo(fake, prompt));

    const transcript = prompt.shown.map((entry) => toTerminal(entry.body));
    expect(
      transcript.some((text) => text.includes('1 step, 1 model call')),
    ).toBe(false);
    expect(
      prompt.states.some((state) => state?.said === '1 step, 1 model call'),
    ).toBe(true);
    // And the tool call, which is the other lifecycle line and travels the
    // same way. Named separately so a fix that moved only one of them fails.
    expect(
      transcript.some((text) => text.includes('called plowshare_memory_write')),
    ).toBe(false);
    expect(
      prompt.states.some(
        (state) => state?.said === 'close_reader called plowshare_memory_write',
      ),
    ).toBe(true);
  });

  it('shows what the person said as an entry in their own voice', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['once more']);
    await converse(talkingTo(fake, prompt));

    const spoken = prompt.shown.filter((entry) => entry.voice === 'person');
    expect(spoken.map((entry) => toTerminal(entry.body))).toEqual([
      'once more',
    ]);
  });

  it('clears the working state when the run ends, so no spinner outlives it', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['once more']);
    await converse(talkingTo(fake, prompt));

    // The LAST word on the subject is that nothing is running. A state left
    // standing is a spinner that never stops, and the paths that leave one
    // behind are exactly the unhappy ones — which is why this is asserted
    // on the tail rather than on whether `undefined` appears anywhere.
    expect(prompt.states.at(-1)).toBeUndefined();
  });

  it('clears the working state at the end of each turn, not just at the end', async () => {
    const fake = await scriptedPlowshare(['answer-first', 'events-first']);
    const prompt = scripted([...ASKED]);
    await converse(talkingTo(fake, prompt));

    // ONE CLEAR PER TURN, PLUS THE ONE ON THE WAY OUT. The weaker form
    // of this only looked at `states.at(-1)`, which passes while a
    // spinner sits under the prompt between turns saying "this run
    // answered" and counting upwards -- seen doing exactly that,
    // driving the real client against a live server for three minutes.
    expect(
      prompt.states.filter((state) => state === undefined).length,
    ).toBeGreaterThanOrEqual(3);
  });

  it('clears the working state even when the socket drops mid-run', async () => {
    const fake = await scriptedPlowshare(['drops-mid-run']);
    const prompt = scripted(['once more']);
    await converse(talkingTo(fake, prompt));

    expect(prompt.states.length).toBeGreaterThan(0);
    expect(prompt.states.at(-1)).toBeUndefined();
  });

  it("carries what a run cost as the answer's note, not as a line of its own", async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['once more']);
    await converse(talkingTo(fake, prompt));

    // One entry, not two: a surface that knows the price belongs to the
    // answer can put it where it likes, and scrolling past one cannot
    // lose the other.
    const answers = prompt.shown.filter((entry) => entry.voice === 'bot');
    expect(answers).toHaveLength(1);
    expect(answers[0]?.note).toContain('this run answered');
  });

  it('says it has lost sight when the beats stop, and does not call it a failure', async () => {
    const fake = await scriptedPlowshare(['beats-then-quiet']);
    const prompt = scripted(['how many modules?']);
    // 150ms rather than the real minute: the case is about what happens
    // when the silence passes, not about sitting through one.
    const talking = { ...talkingTo(fake, prompt), silence: 150 };
    const spoken = converse(talking);
    await new Promise((settle) => setTimeout(settle, 600));
    await fake.stop();
    await spoken.catch(() => undefined);

    const said = prompt.shown
      .filter((entry) => entry.voice === 'trouble')
      .map((entry) => toTerminal(entry.body))
      .join('\n');
    expect(said).toContain('lost sight of it');
    // THE HALF THAT MATTERS. A run is server-side and goes on living
    // whether or not anybody is listening, so what stopped is the
    // watching and not necessarily the work. A client that said
    // "failed" here would be reporting a run that is very probably
    // still going as a run that died.
    expect(said.toLowerCase()).not.toContain('failed');
  });

  it('says nothing about silence when the server never beat at all', async () => {
    // A client pointed at a server too old to send heartbeats must not
    // accuse every run of having died. `beaten` is what holds that.
    //
    // THE SCRIPT HAS TO GO QUIET WITHOUT ENDING, which a first version of
    // this case got wrong: it used `answer-first`, whose run FINISHES, so
    // the watchdog was cancelled long before it could fire and the guard
    // was never reached. Removing `!beaten` from `main.ts` was mutated in
    // and this test still passed.
    const fake = await scriptedPlowshare(['quiet-from-the-start']);
    const prompt = scripted(['how many modules?']);
    const talking = { ...talkingTo(fake, prompt), silence: 150 };
    const spoken = converse(talking);
    await new Promise((settle) => setTimeout(settle, 600));
    await fake.stop();
    await spoken.catch(() => undefined);

    const said = prompt.shown
      .filter((entry) => entry.voice === 'trouble')
      .map((entry) => toTerminal(entry.body))
      .join('\n');
    expect(said).not.toContain('lost sight');
  });

  it('shows the outcome and never the deltas it assembled on the way', async () => {
    // THE ONE PROPERTY THIS WHOLE FEATURE MUST NOT BREAK. Deltas are
    // droppable -- the server drops them freely so that a stream of tokens
    // can never evict an ENDED -- so a client that stitched its own answer
    // out of them would show a truncated one, silently, and only when a
    // queue happened to be full. What stands in the scrollback is
    // Outcome.text from the final model call, every time.
    //
    // The fake sends `## What I di`, which is the real answer's opening with
    // its last characters missing. A client assembling deltas shows that; a
    // client showing the outcome shows the whole thing.
    const fake = await scriptedPlowshare(['streams-with-a-hole']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));

    const answers = prompt.shown.filter((entry) => entry.voice === 'bot');
    expect(answers).toHaveLength(1);
    const shown = toTerminal(answers[0]?.body ?? []);
    expect(shown).toContain('## What I did');
    expect(shown).not.toBe('## What I di');
    // And the thinking never reaches the transcript at all. It is not
    // stored, not archived, and not part of an answer.
    expect(
      prompt.shown.map((entry) => toTerminal(entry.body)).join('\n'),
    ).not.toContain('Let me count them.');
  });

  it('puts the deltas in the run state, where they are a preview', async () => {
    const fake = await scriptedPlowshare(['streams-with-a-hole']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));

    // Both halves reached the live region, in their own parts.
    const previews = prompt.states.filter((state) => state?.live !== undefined);
    expect(
      previews.some(
        (state) =>
          state?.live?.part === 'thinking' &&
          state.live.text.includes('Let me count them.'),
      ),
    ).toBe(true);
    expect(
      previews.some(
        (state) =>
          state?.live?.part === 'answer' &&
          state.live.text.includes('## What I di'),
      ),
    ).toBe(true);
  });

  it("keeps another run's tokens out of this run's preview", async () => {
    // The gate `onPush` applies to events, applied to deltas for the same
    // reason: several jobs share one socket, and another run's tokens in
    // this run's status region is another run's business on this screen.
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));

    expect(prompt.states.every((state) => state?.live === undefined)).toBe(
      true,
    );
  });

  it('takes a new password when the server insists, and gets on with it', async () => {
    // THE BUG THIS WHOLE PATH EXISTS FOR, HELD. Every account this server
    // seeds is flagged, so the FIRST run of this client against a new
    // deployment hit it -- and what it did was print one sentence and
    // return. There was no way through that did not involve curl. Measured
    // on 2026-09-12 by hitting it.
    const fake = await withAFlaggedAccount(['answer-first']);
    const prompt = scripted([
      'a-real-new-password',
      'a-real-new-password',
      'how many modules?',
    ]);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.passwords).toEqual(['a-real-new-password']);
    // AND IT SIGNED IN AGAIN, because the server revokes the token the
    // change was made with. Two logins: the flagged one and the real one.
    expect(
      fake.heard.paths.filter((path) => path === '/v1/auth/login'),
    ).toHaveLength(2);
    // And then it was an ordinary session: the turn went through.
    expect(prompt.shown.some((entry) => entry.voice === 'bot')).toBe(true);
  });

  it('asks twice and refuses to change anything when the two differ', async () => {
    // There is no password recovery in this system -- structural rather
    // than an omission -- so one mistyped character is an account nobody
    // can reach. The second ask is the whole defence.
    const fake = await withAFlaggedAccount(['answer-first']);
    const prompt = scripted([
      'first-try',
      'a-different-typo',
      'second-try-agreed',
      'second-try-agreed',
      'how many modules?',
    ]);
    await converse(talkingTo(fake, prompt));

    // ONLY the agreed one was ever sent. A client that sent the first and
    // then corrected it would have set a password nobody knows.
    expect(fake.heard.passwords).toEqual(['second-try-agreed']);
    expect(
      prompt.shown.some(
        (entry) =>
          entry.voice === 'trouble' &&
          toTerminal(entry.body).includes('did not match'),
      ),
    ).toBe(true);
  });

  it('says which rule a refused password broke, and asks again', async () => {
    // A 400 here is not a failure of the attempt -- the server named a
    // rule -- so the loop goes round rather than ending the session. A
    // client that treated it as fatal would leave somebody locked out by
    // one unlucky guess.
    const fake = await withAFlaggedAccount(['answer-first']);
    const prompt = scripted([
      'password',
      'password',
      'something-nobody-guesses',
      'something-nobody-guesses',
      'how many modules?',
    ]);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.passwords).toEqual([
      'password',
      'something-nobody-guesses',
    ]);
    expect(
      prompt.shown.some(
        (entry) =>
          entry.voice === 'trouble' &&
          toTerminal(entry.body).includes('will not accept'),
      ),
    ).toBe(true);
    expect(prompt.shown.some((entry) => entry.voice === 'bot')).toBe(true);
  });

  it('changes nothing when the input ends at the password prompt', async () => {
    // Ctrl-D, or a surface that cannot take a password at all. Either way
    // the session ends without a half-finished account behind it.
    const fake = await withAFlaggedAccount(['answer-first']);
    const prompt = scripted([]);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.passwords).toEqual([]);
    expect(
      fake.heard.paths.filter((path) => path === '/v1/auth/login'),
    ).toHaveLength(1);
  });

  it('asks for the tokens when it is told to, and not otherwise', async () => {
    // THE FEATURE WAS BUILT ON BOTH SIDES AND SWITCHED OFF. `streamed` read
    // deltas, the terminal drew them, the server queued them separately and
    // dropped them freely -- and no frame ever subscribed, so none of it
    // ever ran. `screen.ts` even said "absent unless this client sent
    // job.stream", of a client that never did.
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['how many modules?']);
    await converse({ ...talkingTo(fake, prompt), tokens: true });

    expect(fake.heard.subscribed).toEqual([{ on: true }]);
    // Third frame of the session: after the quiet inbox count and the
    // roster, so sign-in's own ordering -- who answers, then what is
    // kept -- is unchanged.
    expect(fake.heard.frames[3]?.type).toBe('job.stream');
  });

  it('asks for nothing when nobody said to', async () => {
    // The default is OFF, the way round the protocol already works: a
    // listener that did not ask gets nothing. `run` is what has an opinion
    // about watching a model think, because `run` is the terminal.
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.subscribed).toEqual([]);
    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'job.stream',
    );
  });

  it('writes `on` rather than relying on the server defaulting it', async () => {
    // The server reads an absent `on` as true, which is right for a frame
    // somebody sent on purpose -- but a client leaning on that could not
    // turn the stream OFF without a second spelling, and the two would then
    // disagree about what an empty payload meant.
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted([]);
    await converse({ ...talkingTo(fake, prompt), tokens: true });

    expect(fake.heard.subscribed[0]).toEqual({ on: true });
  });

  it('carries on when a server too old to know the frame refuses it', async () => {
    // Asked separately from everything else precisely so this is survivable:
    // a server that refuses this answers every other frame exactly as
    // before, and a client that ended the session here would refuse to talk
    // to a server that works.
    const fake = await plowshareRefusingStream(['answer-first']);
    const prompt = scripted(['how many modules?']);
    await converse({ ...talkingTo(fake, prompt), tokens: true });

    // It said so, and then it had the conversation anyway.
    expect(prompt.shown.some((entry) => entry.voice === 'trouble')).toBe(true);
    expect(prompt.shown.some((entry) => entry.voice === 'bot')).toBe(true);
  });

  it('says so when the socket drops mid-run, instead of hanging', async () => {
    const fake = await scriptedPlowshare(['drops-mid-run']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));

    // The run was under way — the handle landed and two events arrived —
    // and then the wire went away with no ending behind it.
    expect(fake.heard.wrote).toContain('accepted job-1');
    expect(fake.heard.wrote).not.toContain('job.status answer');

    // WHAT THE PERSON AT THE TERMINAL GETS. A sentence, not a hang and not
    // a stack trace: `converse` returns, so the session is over rather
    // than sitting at a prompt whose socket is gone.
    const said = prompt.said.join('\n');
    expect(said).toContain('close_reader started');
    expect(said).toContain('the connection to the server dropped');
    expect(said).not.toContain('Error:');
    expect(said).not.toContain('    at ');
  });

  it('recovers an evicted early completion through WS status without repeating the submission', async () => {
    const fake = await scriptedPlowshare(['events-overflow']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));
    expect(prompt.shown.some((entry) => entry.voice === 'bot')).toBe(true);
    expect(
      fake.heard.frames.filter((frame) => frame.type === 'agent.run'),
    ).toHaveLength(1);
    expect(
      fake.heard.frames.filter((frame) => frame.type === 'job.status').length,
    ).toBeGreaterThanOrEqual(1);
    expect(prompt.said.join('\n')).not.toContain('foreign-');
    expect(fake.heard.paths).toEqual([
      '/v1/auth/login',
      '/v1/auth/refresh',
      '/v1/auth/ticket',
    ]);
  });

  it("keeps another run off this run's scrollback, handle or no handle", async () => {
    const fake = await scriptedPlowshare(['a-second-run-too']);
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    // The premise: the other job's events really were on this socket, one
    // either side of this turn's handle arriving.
    expect(
      fake.heard.wrote.filter((each) => each === 'event job-other'),
    ).toHaveLength(2);

    // NEITHER OF THEM IS ON THIS SCREEN. `session.ts` has always attached
    // by handle and `answering` drops what the handle says was never this
    // turn's — but `main.ts` printed every event that arrived while the
    // handle was unknown, and that window is the normal path rather than a
    // sliver. So a second terminal's run, or a delegated child's, wrote
    // its progress into somebody else's transcript.
    //
    // MEASURED BEFORE THE FIX, both lines and not just the first:
    //   conversation c-1
    //   other_reader started            <- this turn's handle unknown
    //   close_reader started
    //   1 step, 1 model call
    //   close_reader called plowshare_memory_write
    //   this run answered
    //   other_reader called other_tool  <- and STILL unknown, after the
    //                                      ACCEPTED had been written
    expect(said).not.toContain('other_reader');
    expect(said).not.toContain('other_tool');

    // And this run's own progress is still all there, which is the half a
    // fix that simply stopped printing before the handle would have lost.
    expect(prompt.said.slice(1, 8)).toEqual([
      'new conversation — nothing is kept until you speak',
      'how many modules?',
      'conversation c-1',
      'close_reader started',
      '1 step, 1 model call',
      'close_reader called plowshare_memory_write',
      'this run answered',
    ]);
  });

  /**
   * <b>A client that is opened and closed leaves nothing behind.</b>
   *
   * <p>This used to open a conversation before printing the first prompt, so
   * reading the banner and quitting -- or mistyping the agent name and trying
   * again -- left a row with no turns in it. Measured against a real server
   * rather than supposed: a run stopped before its first answer left
   * `cnv_3134E666E2D847AD` sitting in `GET /v1/conversations`, which is the
   * list a console opens on.
   *
   * <p>The assertion is about what went <i>out</i>, not about what came back,
   * because the property is that nothing was asked for. A test reading the
   * scrollback would pass on a client that opened a conversation and simply
   * declined to mention it.
   */
  it('opens nothing on the server when nobody speaks', async () => {
    const fake = await plowshare([]);
    const prompt = scripted([]);
    await converse({
      door: {
        base: fake.base,
        fetch,
        open: openingSocket,
        session: LISTENING_AS,
      },
      handle: 'trial',
      password: 'pw',
      agent: 'close_reader',
      surface: prompt,
    });

    // The socket still opened -- a client has to be listening before it can
    // be spoken through, and being a listener is not having a conversation.
    expect(fake.heard.sessions).toEqual([LISTENING_AS]);

    // And not one frame was sent but the quiet inbox count and the roster,
    // which opens nothing.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);

    // The banner is the affordance: a person can see nothing is kept yet.
    expect(prompt.said.join('\n')).toContain('nothing is kept until you speak');
    await fake.stop();
  });

  /**
   * <b>The two listings, typed at the prompt, against the shapes the real
   * server sends.</b>
   *
   * <p>The fixtures are `ProjectView` and `ConversationView` written out in
   * full, null title and all — see {@link EVERY_CONVERSATION}. A fake scripted
   * from what this client happens to read would agree with whatever it does,
   * which is the trap task 2 met one layer down.
   */
  it('lists the projects and the conversations when they are asked for', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['/projects', '/conversations']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    // The two frames, and nothing else on the wire but the quiet inbox
    // count and the sign-in roster.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'project.list',
      'conversation.list',
    ]);
    // An absent project is the global tier, which is what `RequestedHome`
    // reads an absent field as. A payload of nulls would say something this
    // client was never asked.
    expect(fake.heard.frames[3]?.payload).toEqual({});
    expect(fake.heard.frames[4]?.payload).toEqual({});

    // WHAT A PERSON SEES. The projects by the name they chose, and the
    // conversations by title with the id beside it.
    expect(said).toContain('plowshare');
    expect(said).toContain('notes');
    expect(said).toContain('how many modules are there');
    expect(said).toContain('cnv_9F1A2B3C4D5E6F70');

    // AND THE NULL TITLE, RENDERED AS THE FALLBACK IT IS. The row is a real
    // conversation with no name yet; its id is shown, said to be a stand-in
    // rather than left to read as the name.
    expect(said).toContain('cnv_3134E666E2D847AD');
    expect(said).toContain('not yet named');

    // Nothing about the workspaces or the exclusions, which are a map of
    // this server's disk and of where its secrets are fenced off.
    expect(said).not.toContain('/srv/plowshare');
    expect(said).not.toContain('/opt/plowshare');
  });

  /**
   * <b>Looking is not speaking.</b>
   *
   * <p>The previous commit made a conversation tentative: nothing exists on
   * the server until somebody says something. A listing that opened one to
   * show it would undo that for anybody who typed `/conversations` first —
   * and would add the row they were looking for to the list they were looking
   * at.
   */
  it('opens no conversation when the listings are all that is asked for', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['/conversations', '/projects']);
    await converse(talkingTo(fake, prompt));

    // The property is about what went OUT. A test reading the scrollback
    // would pass on a client that opened one and declined to mention it.
    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'conversation.open',
    );
    expect(prompt.said).not.toContain('conversation c-1');
  });

  /**
   * <b>A name nobody serves costs nothing, where it used to cost a
   * conversation.</b>
   *
   * <p>Measured against a real server: `PLOWSHARE_AGENT=no_such_bot` left
   * `cnv_31355ABEA50EA42E` behind with zero model calls, because the run was
   * validated <i>after</i> the conversation was opened. The server's refusal
   * was good — it listed what it serves — and by the time it arrived the junk
   * row existed.
   *
   * <p><b>The assertion is on what went out.</b> A client that opened a
   * conversation and merely declined to mention it would pass a test that read
   * the scrollback, which is the weaker check this file already refuses
   * elsewhere.
   */
  it('creates nothing at all for a name this server does not serve', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['how many modules?']);
    await converse({ ...talkingTo(fake, prompt), agent: 'no_such_bot' });

    // NOTHING BUT THE ROSTER AND THE QUIET INBOX COUNT. Not
    // `conversation.open`, not `agent.run`, and the line the person had
    // already typed was never sent either.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);

    // And what they get instead: the name they gave, and the names that
    // would have worked.
    const said = prompt.said.join('\n');
    expect(said).toContain('no_such_bot');
    expect(said).toContain('aristoxenus');
    expect(said).toContain('close_reader');
    // Never the definition this server read and refused: pointing somebody
    // at a name that does not run is a second wasted attempt.
    expect(said).not.toContain('hermippus');
  });

  /**
   * <b>The bot is the default, and `PLOWSHARE_AGENT` is no longer required.</b>
   *
   * <p>A deployment serving exactly one bot has already answered the question
   * of who a person talks to, so this client does not make them answer it
   * again in an environment variable.
   */
  it('talks to the one bot when no agent was named', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted(['how many modules?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    // WHO IT SPOKE TO, off the wire and not off the scrollback.
    const run = fake.heard.frames.find((frame) => frame.type === 'agent.run');
    expect((run?.payload as { agent?: string } | undefined)?.agent).toBe(
      'aristoxenus',
    );
    expect(prompt.said[0]).toContain('aristoxenus');
    // And nothing about structures: a bot IS the conversation, so the
    // sentence that warns an agent is not one has no business here.
    expect(prompt.said[0]).not.toContain('structure');
  });

  /**
   * <b>Talking to a bot continues the conversation it was already having.</b>
   *
   * <p>Spec §3, and the sentence that settles what "continues" means — Enzo:
   * <i>"it's not restored, it's viewed — you can't undo the append-only log...
   * you're just traversing the log."</i> So what is asserted is a <i>view</i>:
   * the turns the log holds now, in order, with the fold where it happened,
   * and nothing claiming that a session came back or that the folded turns can
   * be asked for.
   *
   * <p><b>The log has a seam in it on purpose</b> — see {@link STILL_GOING}.
   * A fake answering only unfolded turns would prove this client right about
   * the one case it cannot get wrong.
   */
  it('continues the conversation the bot was already having, seam and all', async () => {
    const fake = await scriptedPlowshare(
      ['answer-first'],
      EVERY_AGENT,
      STILL_GOING,
    );
    const prompt = scripted(['and now?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    // THE FRAMES, AND conversation.open IS NOT AMONG THEM. The quiet inbox
    // count first, then one read finds the conversation, it is followed before
    // its log is read — so nothing written in between goes unannounced — and
    // one read back from the log's end fills the screen.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.latest',
      'conversation.follow',
      'conversation.trajectory',
      'agent.run',
      'job.status',
      'conversation.trajectory',
    ]);
    expect(fake.heard.frames[3]?.payload).toEqual({ agent: 'aristoxenus' });
    expect(fake.heard.frames[4]?.payload).toEqual({ conversation: CONTINUING });
    expect(fake.heard.frames[5]?.payload).toEqual(tailOf(CONTINUING));
    expect(fake.heard.frames[6]?.payload).toEqual({
      agent: 'aristoxenus',
      conversation: CONTINUING,
      task: 'and now?',
      session: LISTENING_AS,
    });

    expect(said).toContain('continuing how many modules are there');
    expect(said).toContain(CONTINUING);
    expect(said).not.toContain('nothing is kept until you speak');

    // A PERSON'S TURNS, headed as theirs, in their own characters; the agent's read for marks.
    expect(said).toContain('turn 1, you said:');
    expect(said).toContain('what is in this repo');
    expect(said).toContain('The server, by far.');
    expect(said).not.toContain('The **server**, by far.');
    // A stopped turn says how, in the log's own answer; the failed attempt stays hidden.
    expect(said).toContain(
      'This run stopped at its turn cap without reaching an answer.',
    );
    expect(said).not.toContain('TURN_CAP:');

    // THE SEAM, WHERE IT FELL.
    expect(said).toContain('folded at turn 2');
    expect(said.indexOf('which module is biggest')).toBeLessThan(
      said.indexOf('folded at turn 2'),
    );
    expect(said.indexOf('folded at turn 2')).toBeLessThan(
      said.indexOf('how long has that been true'),
    );
    expect(said).toContain('four modules, the server the biggest');

    // THE HARNESS'S TURN, attributed to the run that spoke it — never "you said".
    expect(said).toContain('⚙ run orc_318408A44038F859: The orchestration');
    expect(said).toContain('Its work is left as it stopped.');
    expect(said).not.toContain('turn 4, you said');
    expect(said).toContain(
      'The build run got stuck; I have left it as it was.',
    );

    expect(said).not.toMatch(/restor|resum/i);
    expect(said).not.toMatch(/expand|unfold|show all/i);
  });

  /**
   * <b>A bot with no conversation yet opens one on the first message, and the
   * banner does not claim otherwise.</b>
   *
   * <p>The read that finds nothing creates nothing, which is the property
   * worth asserting on the wire: asking is free, and a person who opens this
   * client and changes their mind still leaves no row behind.
   */
  it('starts a new conversation when the bot has none, and says which happened', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    const prompt = scripted([]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    // The quiet inbox count, two reads and nothing opened, because
    // nobody spoke.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.latest',
    ]);
    const said = prompt.said.join('\n');
    expect(said).toContain('nothing is kept until you speak');
    expect(said).not.toContain('continuing');
  });

  /**
   * <b>Naming an agent asks nothing about a past conversation</b>, even
   * against a server that has one to offer.
   *
   * <p>Spec §2 and §4: an agent is a role and a unit of work, and its answer
   * may be a structure rather than a sentence. Joining somebody silently onto
   * an old work log is not continuity — it is a conversation they did not ask
   * to be in, with a history they may never have seen.
   */
  it('asks nothing about a past conversation when an agent was named', async () => {
    const fake = await scriptedPlowshare(
      ['answer-first'],
      EVERY_AGENT,
      STILL_GOING,
    );
    const prompt = scripted(['how many modules?']);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.open',
      'conversation.follow',
      'agent.run',
      'job.status',
      'conversation.trajectory',
    ]);
    expect(prompt.said.join('\n')).toContain('nothing is kept until you speak');
  });

  it('talks to the first bot served when there are two and no default', async () => {
    // RULING 15: no default names anybody, so this client no longer
    // refuses between several bots — it takes the first one `agent.list`
    // serves, in the server's own (name-sorted) order, which here is
    // aristoxenus ahead of hypatia.
    const fake = await scriptedPlowshare(
      ['answer-first'],
      [
        ...EVERY_AGENT,
        {
          name: 'hypatia',
          tools: ['memory_recall'],
          calls: [],
          scopes: [],
          served: true,
          withheld: [],
          bot: true,
          description: 'keeps the archive honest',
        },
      ],
    );
    const prompt = scripted(['how many modules?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    expect(fake.heard.frames.map((frame) => frame.type).slice(0, 4)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.latest',
    ]);
    const said = prompt.said.join('\n');
    expect(said).toContain('talking to aristoxenus, the first bot served here');
    expect(said).not.toContain('PLOWSHARE_AGENT');
  });

  /**
   * <b>Two deployments with no bot, and they must not say the same thing.</b>
   *
   * <p>A server whose only bot failed to parse reports the same "no bots" as
   * one that defines none, and only the first is a thing a person can fix.
   * `AgentView.disabled` carries the reason in `withheld`, so the refusal is
   * reachable here; what is <i>not</i> reachable is whether the refused file
   * said `bot: true`, because a file that failed to parse has no flag to read.
   * So the sentence says a definition could not be read and names it, and
   * claims nothing about which kind it was.
   */
  it('tells a deployment with no bots from one whose bot would not parse', async () => {
    // Both rosters lose the served agent from EVERY_AGENT — close_reader
    // would now answer on its own (ruling 15), and this case is about the
    // state where nothing is served at all.
    const refused = EVERY_AGENT[2];
    const bare = await scriptedPlowshare([], []);
    const broken = await scriptedPlowshare([], [refused]);
    const first = scripted([]);
    const second = scripted([]);
    await converse(withNoAgentNamed(talkingTo(bare, first)));
    await converse(withNoAgentNamed(talkingTo(broken, second)));

    expect(first.said.join('\n')).toContain('nothing is served here');
    expect(second.said.join('\n')).toContain('nothing is served here');
    expect(first.said).not.toEqual(second.said);
    expect(second.said.join('\n')).toContain('hermippus');
    expect(second.said.join('\n')).toContain('expected a boolean');
    expect(first.said.join('\n')).not.toContain('hermippus');
    // Neither opened anything, which is the half that used to cost a row.
    expect(bare.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    expect(broken.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
  });

  it('talks to the first agent served when the tier serves no bot', async () => {
    // Ruling 15's second half: a tier serving only an agent still serves
    // something, and this client talks to it rather than reporting none.
    const fake = await scriptedPlowshare(['answer-first'], [EVERY_AGENT[1]]);
    const prompt = scripted(['how many modules?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const said = prompt.said.join('\n');
    expect(said).toContain(
      'talking to close_reader, which is an agent rather than a bot',
    );
    expect(fake.heard.frames.map((frame) => frame.type)).toContain('agent.run');
  });

  it('answers /help, which used to be refused as an unknown command', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['/help']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    // ANSWERED HERE, so nothing went out for it but the quiet inbox count
    // and the roster.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    for (const command of [
      '/help',
      '/bots',
      '/agents',
      '/projects',
      '/conversations',
      '/inbox',
    ]) {
      expect(said).toContain(command);
    }
  });

  it('offers model-hidden skills to the human and sends explicit invocations unchanged over WS', async () => {
    const hidden = {
      command: '/skill:review',
      aliases: [],
      kind: 'skill',
      name: 'review',
      description: 'Review the change',
      argumentHint: 'Work to review',
      executor: 'interlocutor',
      mode: null,
      tier: 'PERSONAL',
      hash: 'hidden-review',
      agentVisible: false,
    };
    const workflow = {
      ...hidden,
      command: '/orchestration:research',
      kind: 'orchestration',
      name: 'research',
      description: 'Research the topic',
    };
    const selected = {
      ...(EVERY_AGENT[1] as Record<string, unknown>),
      commands: [hidden, workflow],
    };
    const other = {
      ...(EVERY_AGENT[0] as Record<string, unknown>),
      commands: [{ ...hidden, command: '/skill:other', name: 'other' }],
    };
    const fake = await scriptedPlowshare(['answer-first'], [selected, other]);
    const invocation =
      ' /skill:review --mode=SUMMARISED Review\nthis exact change ';
    const prompt = scripted(['/skills', '/commands', '/help', invocation]);
    await converse(talkingTo(fake, prompt));
    expect(prompt.commandOffers).toEqual(
      Array.from({ length: 3 }, () => [
        '/skill:review',
        '/orchestration:research',
      ]),
    );
    expect(prompt.said.join('\n')).toContain(
      'specify --mode=INHERITED|SUMMARISED|NEW|DIRECT',
    );
    expect(prompt.said.join('\n')).not.toContain('/skill:other');
    const skillListing = prompt.said
      .slice(
        prompt.said.indexOf('/skills') + 1,
        prompt.said.indexOf('/commands'),
      )
      .join('\n');
    expect(skillListing).toContain('/skill:review');
    expect(skillListing).not.toContain('/orchestration:research');
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'agent.run')
        .map((frame) => (frame.payload as { task: string }).task),
    ).toEqual([invocation]);
    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.start'),
    ).toBe(false);
  });

  it('refreshes completion and help from the selected agent without leaking another agent catalog', async () => {
    const command = (name: string) => ({
      command: `/skill:${name}`,
      aliases: [],
      kind: 'skill',
      name,
      description: name,
      argumentHint: 'Work',
      executor: 'interlocutor',
      mode: 'NEW',
      tier: 'PROJECT',
      hash: name,
      agentVisible: false,
    });
    const fake = await scriptedPlowshare([]);
    let reads = 0;
    fake.script('agent.list', () => ({
      code: 'OK',
      payload: [
        {
          name: 'close_reader',
          commands: [command(++reads === 1 ? 'before' : 'after')],
        },
        { name: 'other', commands: [command('other')] },
      ],
    }));
    const prompt = scripted(['/commands', '/help']);
    await converse(talkingTo(fake, prompt));
    expect(prompt.commandOffers).toEqual([['/skill:before'], ['/skill:after']]);
    const help = prompt.said.slice(prompt.said.indexOf('/help')).join('\n');
    expect(help).toContain('/skill:after');
    expect(help).not.toContain('/skill:before');
    expect(help).not.toContain('/skill:other');
    expect(
      fake.heard.frames.some((frame) =>
        ['agent.run', 'conversation.open'].includes(frame.type),
      ),
    ).toBe(false);
  });

  it('uses the selected project catalog after switching projects', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('agent.list', (payload) => {
      const project = (payload as { project: string }).project;
      return {
        code: 'OK',
        payload: [
          {
            name: 'close_reader',
            commands: [
              {
                command: `/skill:${project}`,
                aliases: [],
                kind: 'skill',
                name: project,
                description: project,
                argumentHint: 'Work',
                executor: 'interlocutor',
                mode: 'NEW',
                tier: 'PROJECT',
                hash: project,
                agentVisible: false,
              },
            ],
          },
        ],
      };
    });
    const prompt = scripted([
      '/commands',
      '/project notes',
      '/commands',
      '/help',
    ]);
    await converse({ ...talkingTo(fake, prompt), project: 'plowshare' });
    expect(prompt.commandOffers).toEqual([
      ['/skill:plowshare'],
      ['/skill:plowshare'],
      ['/skill:notes'],
      ['/skill:notes'],
    ]);
    const help = prompt.said.slice(prompt.said.indexOf('/help')).join('\n');
    expect(help).toContain('/skill:notes');
    expect(help).not.toContain('/skill:plowshare');
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'agent.list')
        .map((frame) => (frame.payload as { project: string }).project),
    ).toEqual(['plowshare', 'plowshare', 'notes', 'notes']);
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });

  it('clears completion when the selected agent becomes unavailable', async () => {
    const fake = await scriptedPlowshare([]);
    let reads = 0;
    fake.script('agent.list', () => ({
      code: 'OK',
      payload:
        ++reads === 1
          ? [
              {
                name: 'close_reader',
                commands: [
                  {
                    command: '/skill:review',
                    aliases: [],
                    kind: 'skill',
                    name: 'review',
                    description: 'Review',
                    argumentHint: 'Work',
                    executor: 'interlocutor',
                    mode: 'NEW',
                    tier: 'PROJECT',
                    hash: 'review',
                    agentVisible: false,
                  },
                ],
              },
            ]
          : [],
    }));
    const prompt = scripted(['/commands', '/help']);
    await converse(talkingTo(fake, prompt));
    expect(prompt.commandOffers).toEqual([['/skill:review'], []]);
    expect(prompt.said.join('\n')).toContain(
      'selected agent close_reader is unavailable',
    );
    expect(
      prompt.said.slice(prompt.said.indexOf('/help')).join('\n'),
    ).not.toContain('/skill:review');
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });

  it('continues one Daedalus conversation so a focused correction has its history', async () => {
    const fake = await scriptedPlowshare(
      ['answer-first', 'answer-first'],
      EVERY_AGENT,
      undefined,
      { latestByAgent: {} },
    );
    const prompt = scripted([
      '/diagnose cnv_target -- Why did it keep searching yesterday?',
      '/diagnose cnv_target -- Ignore that; focus on the later build failure.',
    ]);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.latest',
      'conversation.open',
      'agent.run',
      'job.status',
      'agent.run',
      'job.status',
    ]);
    expect(fake.heard.frames[3]?.payload).toEqual({ agent: 'daedalus' });
    expect(fake.heard.frames[4]?.payload).toEqual({});
    expect(fake.heard.frames[5]?.payload).toEqual({
      agent: 'daedalus',
      conversation: 'c-1',
      task: "Diagnose conversation cnv_target. The operator's current question is the controlling scope: Why did it keep searching yesterday? Read the persisted trajectory around that scope; do not substitute an earlier unrelated problem. This request supersedes any prior diagnostic scope in this Daedalus conversation.",
      session: LISTENING_AS,
    });
    expect(fake.heard.frames[7]?.payload).toEqual({
      agent: 'daedalus',
      conversation: 'c-1',
      task: "Diagnose conversation cnv_target. The operator's current question is the controlling scope: Ignore that; focus on the later build failure. Read the persisted trajectory around that scope; do not substitute an earlier unrelated problem. This request supersedes any prior diagnostic scope in this Daedalus conversation.",
      session: LISTENING_AS,
    });
    expect(
      fake.heard.frames.filter((frame) => frame.type === 'conversation.open'),
    ).toHaveLength(1);
    const said = prompt.said.join('\n');
    expect(said).toContain('Daedalus is diagnosing cnv_target');
    expect(said).toContain('diagnostic conversation c-1');
    expect(said).toContain('I read `pom.xml` and found three modules.');
  });

  it('uses the current conversation as the diagnosis target and leaves it current', async () => {
    const fake = await scriptedPlowshare(
      ['answer-first', 'answer-first'],
      EVERY_AGENT,
      STILL_GOING,
      { latestByAgent: { aristoxenus: STILL_GOING.conversation } },
    );
    const prompt = scripted(['/diagnose', 'and now?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const runs = fake.heard.frames.filter(
      (frame) => frame.type === 'agent.run',
    );
    expect(runs[0]?.payload).toEqual({
      agent: 'daedalus',
      conversation: 'c-1',
      task: 'Diagnose conversation cnv_9F1A2B3C4D5E6F70. Diagnose the run generally and find the earliest relevant divergence. Read the persisted trajectory around that scope; do not substitute an earlier unrelated problem. This request supersedes any prior diagnostic scope in this Daedalus conversation.',
      session: LISTENING_AS,
    });
    expect(runs[1]?.payload).toEqual({
      agent: 'aristoxenus',
      conversation: 'cnv_9F1A2B3C4D5E6F70',
      task: 'and now?',
      session: LISTENING_AS,
    });
    expect(prompt.said.join('\n')).toContain(
      'Daedalus is diagnosing cnv_9F1A2B3C4D5E6F70',
    );
  });

  it('does not open a diagnostic conversation when neither form names a target', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['/diagnose']);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    expect(prompt.said.join('\n')).toContain('/diagnose <conversation-id>');
  });

  /**
   * <b>The quiet inbox count really is quiet.</b> A socket signed in with no
   * account gets `BAD_REQUEST` from `InboxListHandler`, and this fake's
   * default "does not answer" fallback stands in for any such refusal here
   * — nothing scripts `inbox.list` for this case at all. Either way, the ask
   * this client sends right after connecting must never put a line on a
   * screen nobody has typed anything into yet.
   */
  it('says nothing when the quiet inbox count at connect is refused', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted([]);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    expect(prompt.shown.some((entry) => entry.voice === 'trouble')).toBe(false);
    expect(prompt.said.join('\n')).not.toContain('inbox.list');
    expect(prompt.said.join('\n')).not.toMatch(/unread/);
  });

  /**
   * <b>`/inbox` shows what is waiting and marks it read — and a refused mark
   * does not cost the session.</b> The read is real information (a person
   * saw these items) sent as a second frame after the first has already
   * reached the screen, so its own refusal is said and not swallowed; but it
   * must not be confused with the read itself failing, and it must not end
   * the loop the way an unguarded `await` on a rejected ask would.
   */
  it('shows the inbox and survives a refused mark-as-read', async () => {
    const fake = await scriptedPlowshare([]);
    fake.scriptInbox(
      [
        {
          id: 'inb_1',
          arrivedAt: '2026-09-13T09:02:00Z',
          ending: 'ANSWERED',
          answer: 'three PRs',
        },
      ],
      1,
    );
    fake.refuseInboxRead('could not mark inb_1 read');
    const prompt = scripted(['/inbox', '/help']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'inbox.list',
      'inbox.read',
    ]);
    expect(said).toContain('2026-09-13T09:02:00Z · ANSWERED');
    expect(said).toContain('three PRs');
    expect(said).toContain('could not mark inb_1 read');
    // AND THE SESSION SURVIVED: `/help` was answered rather than the
    // refusal ending the loop the way an unguarded rejection or an
    // uncaught throw would have — this line only appears if that answer
    // actually ran.
    expect(said).toContain('what scheduled runs left for you');
  });

  /**
   * <b>A question answered elsewhere leaves the inbox at once.</b> The server settles an
   * approval's notice when the approval is answered — in the dialog, by `/answer`, by anyone —
   * and pushes the new count, as it pushes a new notice's. The count drops with it, and the
   * next `/inbox` lists what the server still has, which no longer includes it.
   */
  it('drops a settled question from the count and the next /inbox', async () => {
    const fake = await scriptedPlowshare([]);
    fake.scriptInbox(
      [
        {
          id: 'inb_1',
          arrivedAt: '2026-09-29T09:02:00Z',
          ending: 'ANSWERED',
          answer:
            'Approve running make test in /repo on the server side? [apr_1]',
        },
      ],
      1,
    );
    const counts: number[] = [];
    const prompt = stepping([
      async () => {
        await until(() => counts.includes(1));
        fake.scriptInbox([], 0);
        fake.push(JSON.stringify({ kind: 'inbox.changed', unread: 0 }));
        await until(() => counts.at(-1) === 0);
        return '/inbox';
      },
    ]);
    await converse(
      talkingTo(fake, { ...prompt, unread: (count) => counts.push(count) }),
    );
    const said = prompt.said.join('\n');

    expect(counts).toEqual([1, 0]);
    expect(said).toContain('nothing unread in your inbox');
    expect(said).not.toContain('apr_1');
  });

  it('shows the bots and the agents as two listings, because they are two kinds', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['/bots', '/agents']);
    await converse(talkingTo(fake, prompt));

    // The quiet inbox count, then three `agent.list`s: the sign-in one
    // and one per command. The roster is a read and a definition can
    // change under a running client, so each command asks rather than
    // showing what sign-in saw.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'agent.list',
      'agent.list',
    ]);
    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'conversation.open',
    );

    // THE TWO LISTINGS, FOUND BY VOICE RATHER THAN BY POSITION.
    //
    // These were `said.at(-2)` and `said.at(-1)`, which worked only
    // while the flat stream ended with the two listings — and it no
    // longer does, because each command is echoed as the person's own
    // entry first. Asking for the client's last two entries says what
    // the case actually means and cannot be shifted by an echo again.
    const listings = prompt.shown.filter((entry) => entry.voice === 'client');
    const bots = toTerminal(listings.at(-2)?.body ?? []);
    const agents = toTerminal(listings.at(-1)?.body ?? []);
    expect(bots).toContain('aristoxenus');
    expect(bots).not.toContain('close_reader');
    expect(agents).toContain('close_reader');
    expect(agents).not.toContain('aristoxenus');

    // AND EACH ROW SAYS WHO IT IS, off the wire and not a bare name —
    // the one line where a bot is legible as a bot rather than as a
    // name with no context around it.
    expect(bots).toContain('aristoxenus — Aristoxenus of Tarentum');
    expect(agents).toContain('close_reader — Answers a question');

    // AND THE DEFINITION THIS SERVER COULD NOT READ IS IN BOTH, with
    // its reason. It has no `bot:` to report, so excluding it from
    // either list would be this client claiming to know which it was.
    expect(bots).toContain('hermippus');
    expect(agents).toContain('hermippus');
    expect(bots).toContain('expected a boolean');
  });

  it('refuses a command it does not know instead of asking an agent about it', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted(['/porjects']);
    await converse(talkingTo(fake, prompt));

    // NOT SENT AS AN UTTERANCE. A mistyped command costs a model call and
    // comes back as an agent's puzzled answer, which is the worse reply.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    expect(prompt.said.join('\n')).toContain('/porjects');
    expect(prompt.said.join('\n')).toContain('/help');
  });

  /**
   * <b>Ctrl-C reaches the server, where it used to reach only this process.</b>
   *
   * <p>Measured before this: Ctrl-C quit the client and the run carried on
   * server-side, spending a budget nobody was watching and filing an answer
   * nobody would read. `job.cancel` has been routed since the socket surface
   * was built and this client had never called it.
   */
  it('asks the run to stop on the first Ctrl-C, where it used to abandon it', async () => {
    const fake = await scriptedPlowshare(['stops-when-asked']);
    const prompt = scripted(['how many modules?'], {
      when: 'called plowshare_memory_write',
    });
    await converse(talkingTo(fake, prompt));

    // THE FRAME, AND THE HANDLE IT NAMES. Asserted on the wire rather than
    // in the scrollback: a client that said "asked it to stop" and sent
    // nothing would pass a test that read what a person was told.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.open',
      'conversation.follow',
      'agent.run',
      'job.cancel',
      'job.status',
      'conversation.trajectory',
    ]);
    expect(fake.heard.frames[6]?.payload).toEqual({ job: 'job-1' });

    const said = prompt.said.join('\n');
    expect(said).toContain('asked job-1 to stop');
    // And how it really ended, read back off `job.status` like any other
    // outcome — the cancel is not this client's evidence of anything.
    expect(said).toContain(
      'was asked to stop, and stopped at its next step boundary',
    );
  });

  /**
   * <b>The cancel that arrives while the run is already finishing.</b>
   *
   * <p>The case a lenient fake hides, and the mistake this branch has shipped
   * three times in other forms: a fake that honoured every cancel instantly
   * would prove this client right about the outcome it cannot get wrong.
   * `JobCancelHandler` is explicit — cancelling a finished job <i>"changes
   * nothing"</i> — so the run answers, and a person told it stopped would go
   * looking for an answer that is on their screen.
   *
   * <p><b>MEASURED ON THE FAKE, which is the half that would otherwise be
   * taken on trust.</b> The server's `ending` here was changed to `CANCELLED`
   * unconditionally — a fake that honours every cancel instantly — and this
   * case, and only this case, went red:
   *
   * <pre>
   *   × lets a run that was already finishing finish …
   *       expected 'talking to close_reader, which is an …'
   *       to contain 'this run answered'
   * </pre>
   *
   * <p>So the two moves really are two, and this one really does exercise the
   * outcome a lenient fake hides. The plant was removed.
   */
  it('lets a run that was already finishing finish, and never says it stopped', async () => {
    const fake = await scriptedPlowshare(['finishes-under-the-cancel']);
    const prompt = scripted(['how many modules?'], {
      when: 'called plowshare_memory_write',
    });
    await converse(talkingTo(fake, prompt));

    // THE PREMISE, off the server's own write order: the ending was written
    // before the cancel was answered. Without this the case below would
    // quietly become the easy one the day the fake changed.
    expect(fake.heard.frames.map((frame) => frame.type)).toContain(
      'job.cancel',
    );
    expect(fake.heard.wrote.lastIndexOf('event job-1')).toBeLessThan(
      fake.heard.wrote.indexOf('job.cancel answer'),
    );

    const said = prompt.said.join('\n');
    // The person was told what this client did, which is true.
    expect(said).toContain('asked job-1 to stop');
    // AND WHAT BECAME OF THE RUN, WHICH IS NOT WHAT THEY ASKED FOR. It
    // answered, and the answer is on the screen.
    expect(said).toContain('this run answered');
    expect(said).toContain('I read `pom.xml` and found three modules.');
    expect(said).not.toContain('stopped at its next step boundary');
  });

  /**
   * <b>A second Ctrl-C leaves, and does not wait on the network to do it.</b>
   *
   * <p>A step can be a model call that takes a minute, and the boundary a
   * cancel is honoured at is on the far side of it. The run here is answered
   * `OK` and goes on anyway, which is inside the contract — and a person who
   * wants out has to get out regardless.
   *
   * <p><b>Returning at all is the assertion.</b> A client that waited for the
   * ending would hang here exactly as the dropped-socket case hung before it
   * was fixed, and would fail as a five-second timeout rather than as a
   * comparison.
   */
  it('leaves on the second Ctrl-C, without waiting for the run to stop', async () => {
    const fake = await scriptedPlowshare(['goes-on-anyway']);
    const prompt = scripted(['how many modules?'], {
      when: 'called plowshare_memory_write',
      times: 2,
    });
    await converse(talkingTo(fake, prompt));
    // <b>The one place in this file that waits after `converse` returns</b>,
    // and the reason is the property being tested: leaving does not wait
    // for the server, so this client returns with a frame still in flight.
    // Every other case here asserts on frames whose answers it awaited.
    await heard(fake, 'job.cancel');

    // The cancel went out; no ending ever came, so no `job.status` was ever
    // asked and the turn never completed.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.open',
      'conversation.follow',
      'agent.run',
      'job.cancel',
    ]);
    expect(fake.heard.wrote).not.toContain('job.status answer');

    const said = prompt.said.join('\n');
    expect(said).toContain('asked job-1 to stop');
    // AND IT DOES NOT CLAIM THE RUN IS OVER. It was asked to stop, and it
    // stops when it reaches a boundary, which is after this person left.
    expect(said).toMatch(/left/);
    expect(said).not.toContain('this run answered');
  });

  /**
   * <b>Ctrl-C at a prompt with nothing running is not an exit without a
   * word</b>, which is what it was.
   */
  it('says what Ctrl-C did at an idle prompt, rather than exiting silently', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted([], { when: 'nothing is kept until you speak' });
    await converse(talkingTo(fake, prompt));

    // Nothing was cancelled, because there was nothing to cancel: a client
    // that sent `job.cancel` with no run would be naming a handle it does
    // not have.
    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    const said = prompt.said.join('\n');
    expect(said).toContain('nothing is running');
    expect(said).toContain('Ctrl-D');
  });

  /**
   * <b>Where Tab's names come from: the roster sign-in already has.</b>
   *
   * <p>Task 3 left the decision and this is it. `agent.list` is asked once at
   * sign-in and the names are handed to the prompt from that answer — no
   * frame of the completer's own. A completer that asked would put a round
   * trip under a keypress, and Tab is pressed at typing speed: a person
   * holding it down would be waiting on the network between characters, with
   * a prompt that stalls for a reason nothing on screen explains.
   *
   * <p>The cost, stated because it is real: a definition that arrives under a
   * running client (`agent.define` exists) is not completable until the next
   * one. That is the right way round — everything Tab offers is a name this
   * server really declared, which is the property the plan asks for, and
   * `/bots` re-asks and shows the fresh roster for anybody who needs it.
   */
  it('completes from the roster sign-in already has, without asking again', async () => {
    const fake = await scriptedPlowshare([]);
    const prompt = scripted([]);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
    ]);
    expect(prompt.offered).toEqual(['aristoxenus', 'close_reader']);
    // NEVER THE DEFINITION THIS SERVER READ AND REFUSED. It is a name the
    // server declared and a name that cannot answer, and a completion reads
    // as a name that works.
    expect(prompt.offered).not.toContain('hermippus');
  });
});

describe('a run that stops to ask a person before a command runs', () => {
  /** One `ApprovalFrames.View`, every component, as the server writes it. */
  const ASKED_VIEW = {
    id: 'apr_1',
    conversation: 'c-1',
    agent: 'close_reader',
    side: 'local',
    command: ['./gradlew', 'test', '--tests', 'Foo'],
    cwd: '/repo',
    reason: 'tests reach the network',
    state: 'asked',
    scope: null,
    prefix: null,
    defaultPrefix: ['./gradlew', 'test'],
    createdAt: '2026-09-15T10:00:00Z',
    answeredAt: null,
  };

  /**
   * `job.status`, with the first run ending `AWAITING` — its outcome text
   * ending in the approval id, as `TurnEnd` writes it — and every later one
   * answering. `JobView`'s components, as the default fake writes them.
   */
  const statusOf = (payload: unknown): Reply => {
    const awaiting =
      (payload as { job?: unknown } | undefined)?.job === 'job-1';
    return {
      code: 'OK',
      payload: {
        id: (payload as { job?: string }).job,
        state: 'DONE',
        outcome: {
          ending: awaiting ? 'AWAITING' : 'ANSWERED',
          answered: !awaiting,
          steps: 1,
          modelCalls: 1,
          text: awaiting
            ? 'waiting for a person to approve ./gradlew test --tests Foo [apr_1]'
            : 'All green.',
          pace: null,
        },
        limits: {
          modelCallsSpent: 1,
          maxModelCalls: 40,
          noBudget: false,
          maxTurns: 8,
          noTurnCap: false,
        },
      },
    };
  };

  it('asks, sends the prefix the person chose, and follows the turn the answer starts', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    fake.script('job.status', statusOf);
    fake.script('approval.list', {
      code: 'OK',
      payload: { approvals: [ASKED_VIEW] },
    });
    fake.script('approval.answer', () => {
      // THE CONTINUING TURN'S EVENTS, AFTER THE ANSWER THAT NAMES IT —
      // and its ending among them, which is what settles the wait.
      setImmediate(() => {
        fake.push(
          JSON.stringify({
            job: 'job-9',
            kind: 'started',
            agent: 'close_reader',
          }),
        );
        fake.push(
          JSON.stringify({
            job: 'job-9',
            kind: 'ended',
            agent: 'close_reader',
            ending: 'ANSWERED',
            steps: 1,
            modelCalls: 1,
          }),
        );
      });
      return {
        code: 'OK',
        payload: {
          id: 'apr_1',
          state: 'allowed',
          job: 'job-9',
          busy: false,
          note: null,
        },
      };
    });
    // A surface with no keys: `p`, one argument more, then enter.
    const prompt = scripted(['run the tests', 'p', '>', '']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.open',
      'conversation.follow',
      'agent.run',
      'job.status',
      'conversation.trajectory',
      'approval.list',
      'approval.answer',
      'job.status',
      'conversation.trajectory',
    ]);
    expect(fake.heard.frames[8]?.payload).toEqual({ conversation: 'c-1' });
    expect(fake.heard.frames[9]?.payload).toEqual({
      id: 'apr_1',
      decision: 'project',
      prefix: ['./gradlew', 'test', '--tests'],
    });
    expect(fake.heard.frames[10]?.payload).toEqual({ job: 'job-9' });
    expect(said).toContain(
      'this run stopped to ask you before running a command',
    );
    expect(said).toContain('./gradlew test --tests Foo');
    expect(said).toContain('because tests reach the network');
    expect(said).toContain(
      'allow any command starting: ./gradlew test --tests   (not: Foo)',
    );
    expect(said).toContain('All green.');
    // And nothing of the answer was left standing: the continued turn put
    // its working state down as a spoken one does.
    expect(prompt.states.at(-1)).toBeUndefined();
  });

  it('answers with a key where the surface has keys, and says a busy answer still stands', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    fake.script('job.status', statusOf);
    fake.script('approval.list', {
      code: 'OK',
      payload: { approvals: [ASKED_VIEW] },
    });
    fake.script('approval.answer', {
      code: 'OK',
      payload: {
        id: 'apr_1',
        state: 'allowed',
        job: null,
        busy: true,
        note: 'a turn is already running',
      },
    });
    const prompt = scripted(['run the tests']);
    const drawn: (readonly string[])[] = [];
    const keys = [
      { kind: 'text', text: 'x' },
      { kind: 'text', text: 'c' },
    ] as const;
    let pressed = 0;
    await converse(
      talkingTo(fake, {
        ...prompt,
        choosing: (lines: readonly string[]) => {
          drawn.push(lines);
          pressed += 1;
          return Promise.resolve(keys[pressed - 1]);
        },
      }),
    );
    const said = prompt.said.join('\n');

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'conversation.open',
      'conversation.follow',
      'agent.run',
      'job.status',
      'conversation.trajectory',
      'approval.list',
      'approval.answer',
    ]);
    expect(fake.heard.frames[9]?.payload).toEqual({
      id: 'apr_1',
      decision: 'conversation',
    });
    // The keys were drawn in the surface's own region, twice — the `x`
    // answered nothing — and never put in the transcript.
    expect(drawn).toHaveLength(2);
    expect(said).not.toContain('o once');
    expect(said).toContain('allow it for this conversation');
    expect(said).toContain('a turn is already running');
  });

  it('leaves a question open on escape and sends no answer', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    fake.script('job.status', statusOf);
    fake.script('approval.list', {
      code: 'OK',
      payload: { approvals: [ASKED_VIEW] },
    });
    const prompt = scripted(['run the tests', 'esc']);
    await converse(talkingTo(fake, prompt));

    expect(fake.heard.frames.map((frame) => frame.type)).not.toContain(
      'approval.answer',
    );
    expect(prompt.said.join('\n')).toContain('left unanswered');
  });

  it('revokes an approval, and says approvals need a project where there is none', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('approval.revoke', {
      code: 'OK',
      payload: { id: 'apr_1', revoked: true },
    });
    const prompt = scripted(['/approvals revoke apr_1', '/approvals']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    expect(fake.heard.frames.map((frame) => frame.type)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'approval.revoke',
    ]);
    expect(fake.heard.frames[3]?.payload).toEqual({ id: 'apr_1' });
    expect(said).toContain('apr_1 revoked');
    expect(said).toContain('approvals belong to a project');
  });
});

describe('where a person is, and how they move', () => {
  const made: string[] = [];
  afterEach(async () => {
    while (made.length > 0) {
      await rm(made.pop() ?? '', { recursive: true, force: true });
    }
  });

  /** A fresh directory, canonical — `os.tmpdir()` is under a symlink on macOS. */
  async function directory(): Promise<string> {
    const at = await realpath(await mkdtemp(join(tmpdir(), 'rooting-')));
    made.push(at);
    return at;
  }

  async function project(root: string, name: string): Promise<void> {
    await mkdir(join(root, '.plowshare'), { recursive: true });
    await writeFile(join(root, '.plowshare', 'project'), `${name}\n`);
  }

  const SOPHRON = {
    name: 'sophron',
    tools: [],
    calls: [],
    scopes: [],
    served: true,
    withheld: [],
    bot: true,
    description: 'a bot of the ledger',
    preferred: true,
  };

  function rootedTalk(
    fake: Fake,
    surface: Surface,
    here: string,
  ): Parameters<typeof converse>[0] {
    return {
      ...withNoAgentNamed(talkingTo(fake, surface)),
      here,
      machine: 'bench.local',
    };
  }

  it('/design-orchestration launches one granted caller turn in its rooted project', async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare(
      ['answer-first'],
      EVERY_AGENT,
      undefined,
      {
        rosters: {
          ledger: [
            SOPHRON,
            {
              ...SOPHRON,
              name: 'author',
              bot: false,
              preferred: false,
              orchestrations: ['design_orchestration'],
            },
          ],
        },
      },
    );
    fake.script('orchestration.definitions', {
      code: 'OK',
      payload: {
        definitions: [{ name: 'design_orchestration', served: true }],
      },
    });
    const surface = scripted([
      '/design-orchestration Read sources and ask before publication',
    ]);
    await converse(rootedTalk(fake, surface, top));
    const frames = fake.heard.frames.filter(
      (frame) => frame.type === 'agent.run',
    );
    expect(frames).toHaveLength(1);
    expect((frames[0]?.payload as Record<string, unknown>)['agent']).toBe(
      'author',
    );
    expect((frames[0]?.payload as Record<string, unknown>)['task']).toContain(
      'Start the granted design_orchestration',
    );
    expect((frames[0]?.payload as Record<string, unknown>)['task']).toContain(
      'Read sources and ask before publication',
    );
    expect(surface.said.join('\n')).toContain(
      'authoring caller author · conversation',
    );
    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.answer'),
    ).toBe(false);
  });

  it('/design-orchestration refuses a missing caller grant before opening or running', async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
    });
    fake.script('orchestration.definitions', {
      code: 'OK',
      payload: {
        definitions: [{ name: 'design_orchestration', served: true }],
      },
    });
    const surface = scripted(['/design-orchestration Read sources']);
    await converse(rootedTalk(fake, surface, top));
    expect(
      fake.heard.frames.some(
        (frame) =>
          frame.type === 'agent.run' || frame.type === 'conversation.open',
      ),
    ).toBe(false);
    expect(surface.said.join('\n')).toContain('granted design_orchestration');
  });

  it('roots the project it finds on the way up, and lends its files', async () => {
    const top = await directory();
    await project(top, 'ledger');
    await mkdir(join(top, 'src'), { recursive: true });
    await writeFile(join(top, 'src', 'a.ts'), 'export const a = 1\n');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
    });
    // THE PERSON STAYS AT THE PROMPT until the server has asked for a file:
    // a scripted surface that ended its input at once would release the
    // rooting before there was anything to ask it.
    let leave: () => void = () => undefined;
    const prompt = new Promise<undefined>((done) => {
      leave = () => done(undefined);
    });
    const surface = {
      ...scripted([]),
      asked: (): Promise<undefined> => prompt,
    };
    const going = converse(rootedTalk(fake, surface, join(top, 'src')));
    for (
      let turn = 0;
      turn < 200 && !surface.said.some((line) => line.startsWith('rooting '));
      turn += 1
    ) {
      await new Promise((done) => setTimeout(done, 5));
    }
    const asked = await fake.askFiles({
      id: 'f1',
      op: 'read',
      path: 'src/a.ts',
    });
    leave();
    await going;

    expect(fake.heard.files).toEqual([
      {
        session: LISTENING_AS,
        ticket: 'tkt-1',
        project: 'ledger',
        machine: 'bench.local',
        root: top,
      },
    ]);
    expect(asked['outcome']).toBe('ok');
    expect(
      fake.heard.frames.find((frame) => frame.type === 'agent.list')?.payload,
    ).toEqual({ project: 'ledger' });
    expect(surface.said.join('\n')).toContain(`rooting ledger at ${top}`);
    expect(surface.said.join('\n')).toContain("sophron, this tier's default");
  });

  it("/always writes the project's own file and allows what was waiting inside it", async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
    });
    const asked = (id: string, cwd: string) => ({
      id,
      conversation: 'cnv_conductor',
      askedIn: 'cnv_coder',
      agent: 'code_implementation',
      side: 'local',
      command: ['pytest', '-q'],
      cwd,
      state: 'asked',
      defaultPrefix: ['pytest', '-q'],
    });
    fake.script('approval.list', {
      code: 'OK',
      payload: {
        approvals: [
          asked('apr_in', join(top, 'tests')),
          asked('apr_out', '/somewhere/else'),
        ],
      },
    });
    fake.script('approval.answer', (payload) => ({
      code: 'OK',
      payload: {
        id: (payload as { id: string }).id,
        state: 'allowed',
        busy: false,
      },
    }));
    const surface = scripted(['/always']);
    await converse(rootedTalk(fake, surface, top));
    const file = join(top, '.plowshare', 'environment.yml');

    expect(await readFile(file, 'utf8')).toBe('local:\n  mode: open\n');
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'approval.answer')
        .map((frame) => frame.payload),
    ).toEqual([{ id: 'apr_in', decision: 'once' }]);
    expect(surface.said.join('\n')).toContain(
      'commands in ledger now run without asking',
    );
    expect(surface.said.join('\n')).toContain(
      '1 waiting approval allowed once',
    );

    const again = scripted(['/always off']);
    await converse(rootedTalk(fake, again, top));
    expect(await readFile(file, 'utf8')).toBe('local:\n  mode: ask\n');
  });

  it("/cap steps writes the project's own file and has the server apply it", async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
    });
    fake.script('orchestration.caps', {
      code: 'OK',
      payload: {
        project: 'ledger',
        applied: 1,
        steps: { value: 40, source: '.plowshare/environment.yml' },
        budget: { value: null, source: 'definition' },
        autoContinue: { value: 3, source: '.plowshare/environment.yml' },
      },
    });
    const surface = scripted(['/cap steps 40', '/always caps']);
    await converse(rootedTalk(fake, surface, top));
    const file = join(top, '.plowshare', 'environment.yml');

    expect(await readFile(file, 'utf8')).toBe(
      'caps:\n  auto-continue: 3\n  steps: 40\n',
    );
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.caps')
        .map((frame) => frame.payload),
    ).toEqual([{ project: 'ledger' }, { project: 'ledger' }]);
    expect(surface.said.join('\n')).toContain('now says steps: 40');
    expect(surface.said.join('\n')).toContain('applied to 1 live run');
  });

  it('says a /cap value out of range is out of range, and writes nothing', async () => {
    // Task 14's review: it was written, the file then did not parse, and the line blamed the
    // file — "does not parse; fix it first" — for a value the person had just typed.
    const top = await directory();
    await project(top, 'ledger');
    const file = join(top, '.plowshare', 'environment.yml');
    await writeFile(file, 'caps:\n  steps: 10\n');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
    });
    const surface = scripted(['/cap steps 0', '/cap budget 2000000']);
    await converse(rootedTalk(fake, surface, top));
    const said = surface.said.join('\n');

    expect(said).toContain(
      'steps is from 1 to 10000, and 0 is out of that range; nothing was written',
    );
    expect(said).toContain(
      'budget is from 1 to 1000000, and 2000000 is out of that range',
    );
    expect(said).not.toContain('does not parse');
    expect(await readFile(file, 'utf8')).toBe('caps:\n  steps: 10\n');
  });

  it('says a caps file it cannot write, and goes on', async () => {
    // Read-only: read and parsed, then refused at the write — which used to throw out of the
    // session from `/cap`, `/always caps` and the cap dialog's `a` alike.
    const top = await directory();
    await project(top, 'ledger');
    const file = join(top, '.plowshare', 'environment.yml');
    await writeFile(file, 'caps:\n  steps: 10\n');
    await chmod(file, 0o444);
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
    });
    const surface = scripted(['/always caps', '/cap steps 40']);
    await converse(rootedTalk(fake, surface, top));
    const said = surface.said.join('\n');

    expect(said.split('could not be written').length - 1).toBe(2);
    expect(await readFile(file, 'utf8')).toBe('caps:\n  steps: 10\n');
    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.caps'),
    ).toBe(false);
  });

  it('asks who answers only once the server says the claim has landed', async () => {
    // THE RACE A 101 HIDES. `open` fires when the upgrade answers, and the
    // server declares the claim after that; a roster asked on `open` could be
    // answered without this client's own definitions. Here the landing is
    // late enough that asking on `open` would ask first.
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
      landingAfter: 80,
    });
    const surface = scripted([]);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, top)));

    expect(fake.heard.landings).toEqual(['ready ledger', 'agent.list ledger']);
    expect(surface.said.join('\n')).toContain(`rooting ledger at ${top}`);
  });

  it('says there is no project, and sends nothing about the disk, when there is none', async () => {
    const nowhere = await directory();
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [],
    });
    const surface = scripted([]);
    await converse({
      ...rootedTalk(fake, surface, nowhere),
      agent: 'close_reader',
    });

    expect(fake.heard.files).toEqual([]);
    expect(surface.said).toContain(
      'no project here — /here roots this directory',
    );
  });

  it('offers the project the server already knows this directory as, and roots nothing', async () => {
    const known = await directory();
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [
        {
          name: 'ledger',
          workspace: known,
          machine: 'bench.local',
          lent: [],
          exclusions: [],
        },
      ],
    });
    const surface = scripted([]);
    await converse({
      ...rootedTalk(fake, surface, known),
      agent: 'close_reader',
    });

    expect(fake.heard.files).toEqual([]);
    expect(surface.said).toContain(
      `the server knows ${known} on this machine as ledger — /here ledger roots it`,
    );
  });

  it('makes a directory a project with /here, and writes the marker only once somebody answers', async () => {
    const here = await directory();
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [],
      rosters: { ledger: [SOPHRON] },
    });
    const surface = scripted(['/here ledger']);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, here)));

    expect(fake.heard.files.map((claim) => claim.project)).toEqual(['ledger']);
    expect(
      JSON.parse(await readFile(join(here, '.plowshare', 'project'), 'utf8')),
    ).toEqual({ version: 1, name: 'ledger' });
    expect(surface.said.join('\n')).toContain(
      'now in ledger, talking to sophron',
    );
  });

  it('refuses to move where nobody answers, and changes nothing it can take back', async () => {
    const here = await directory();
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [],
      rosters: { '': [EVERY_AGENT[0]], ledger: [] },
    });
    // WHAT IS STILL OPEN AFTER THE REFUSAL, read at the next prompt and not
    // after `converse` returns — its `finally` releases any rooting, so a
    // refusal that forgot to close the channel would look closed by then.
    const lines = ['/here ledger'];
    let openAfter = -1;
    const surface = {
      ...scripted([]),
      asked: async (): Promise<string | undefined> => {
        const next = lines.shift();
        if (next !== undefined) {
          return next;
        }
        for (
          let turn = 0;
          turn < 100 && fake.openFileChannels() > 0;
          turn += 1
        ) {
          await new Promise((done) => setTimeout(done, 5));
        }
        openAfter = fake.openFileChannels();
        return undefined;
      },
    };
    await converse(withNoAgentNamed({ ...rootedTalk(fake, surface, here) }));

    expect(surface.said).toContain(
      'ledger serves no bot and no agent, and nothing global answers for it.',
    );
    expect(surface.said.join('\n')).toContain(
      'staying in global resources with aristoxenus.',
    );
    await expect(
      readFile(join(here, '.plowshare', 'project'), 'utf8'),
    ).rejects.toThrow();
    // Asked, refused, released: the channel opened for the attempt is closed again.
    expect(fake.heard.files.map((claim) => claim.project)).toEqual(['ledger']);
    expect(openAfter).toBe(0);
  });

  it('says why the server would not root a project, and carries on talking', async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      refusingRoots: 'desk.local/srv/ledger/ledger already roots ledger',
    });
    const surface = scripted([]);
    await converse({
      ...withNoAgentNamed(rootedTalk(fake, surface, top)),
      tokens: true,
    });

    expect(surface.said.join('\n')).toContain(
      'desk.local/srv/ledger/ledger already roots ledger',
    );
    // Started twice, subscribed once: the retry in the global tier is still one session.
    expect(fake.heard.subscribed).toEqual([{ on: true }]);
    expect(surface.said.join('\n')).toContain('talking to aristoxenus');
    // In the global tier, not in ledger with no files: the move never committed.
    // And ledger's roster was never asked for: the root waits for the claim to
    // land, so the 1003 is heard before anybody asks who answers there.
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'agent.list')
        .map((frame) => frame.payload),
    ).toEqual([{}]);
    expect(surface.said.join('\n')).not.toContain('rooting ledger at');
  });

  it('does not commit a move whose rooting was lost while asking what the bot was saying', async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON] },
      losingRootOnLatest: 'desk.local/srv/ledger/ledger already roots ledger',
    });
    const surface = scripted([]);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, top)));

    expect(surface.said.join('\n')).not.toContain('rooting ledger at');
    expect(surface.said.join('\n')).toContain(
      'desk.local/srv/ledger/ledger already roots ledger',
    );
    // Retried in the global tier, as any root refused at start is.
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'agent.list')
        .map((frame) => frame.payload),
    ).toEqual([{ project: 'ledger' }, {}]);
  });

  it('puts back the files a person had when a new root cannot even be opened', async () => {
    // CONTROLLER RULING 3. The rooter releases the old claim before it opens
    // the new one, so an open that rejects leaves nothing held — and a
    // failed move must not silently take away the files somebody had.
    const top = await directory();
    await project(top, 'ledger');
    await project(join(top, 'annex'), 'annex');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: { ledger: [SOPHRON], annex: [SOPHRON] },
      unopenable: 'annex',
    });
    const surface = scripted(['/cd annex']);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, top)));

    expect(fake.heard.files.map((claim) => claim.project)).toEqual([
      'ledger',
      'annex',
      'ledger',
    ]);
    expect(surface.said.join('\n')).toContain(
      'the server would not let this client root annex',
    );
    expect(surface.said.join('\n')).not.toContain('now in annex');
    // The pair that rotated on the failed open is the one the restore presents.
    expect(fake.heard.refused).toEqual([]);
    // And the sentence about it carries no credential and no query.
    expect(surface.said.join('\n')).not.toContain('ticket=');
  });

  it('moves to a project whose files are elsewhere without rooting anything', async () => {
    const here = await directory();
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [
        {
          name: 'notes',
          workspace: '/srv/notes',
          machine: 'desk.local',
          lent: [],
          exclusions: [],
        },
      ],
      rosters: { notes: [SOPHRON] },
    });
    const surface = scripted(['/project notes']);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, here)));

    expect(fake.heard.files).toEqual([]);
    expect(surface.said.join('\n')).toContain(
      'notes is rooted on desk.local, not here',
    );
  });

  // THE TIER AND THE ROOTING PART COMPANY AFTER A REMOTE /project: the channel
  // to the rooted directory stays open, and the tier is somewhere else. Coming
  // back to that directory is a move back to its project, not "already there".
  const NOTES_ELSEWHERE = {
    name: 'notes',
    workspace: '/srv/notes',
    machine: 'desk.local',
    lent: [],
    exclusions: [],
  };

  it('moves the tier back with /here on the root it is still holding', async () => {
    const top = await directory();
    await project(top, 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [NOTES_ELSEWHERE],
      rosters: { ledger: [SOPHRON], notes: [SOPHRON] },
    });
    const surface = scripted(['/project notes', '/here']);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, top)));

    expect(surface.said.join('\n')).not.toContain('already in ledger');
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'agent.list')
        .map((frame) => frame.payload),
    ).toEqual([
      { project: 'ledger' },
      { project: 'notes' },
      { project: 'ledger' },
    ]);
    expect(surface.said.join('\n')).toContain(
      'now in ledger, talking to sophron',
    );
    // Moved back over the channel it already had: nothing was opened again.
    expect(fake.heard.files.map((claim) => claim.project)).toEqual(['ledger']);
  });

  it('moves the tier back with /cd into the root it is still holding', async () => {
    const top = await directory();
    await project(top, 'ledger');
    await mkdir(join(top, 'src'), { recursive: true });
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [NOTES_ELSEWHERE],
      rosters: { ledger: [SOPHRON], notes: [SOPHRON] },
    });
    const surface = scripted(['/project notes', '/cd src']);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, top)));

    expect(surface.said.join('\n')).not.toContain('still rooting ledger');
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'agent.list')
        .map((frame) => frame.payload),
    ).toEqual([
      { project: 'ledger' },
      { project: 'notes' },
      { project: 'ledger' },
    ]);
    expect(surface.said.join('\n')).toContain(
      'now in ledger, talking to sophron',
    );
    expect(fake.heard.files.map((claim) => claim.project)).toEqual(['ledger']);
  });

  it('moves with /cd, and roots the project it lands in', async () => {
    const top = await directory();
    await project(join(top, 'ledger'), 'ledger');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      projects: [],
      rosters: { ledger: [SOPHRON] },
    });
    const surface = scripted(['/cd ledger']);
    await converse(withNoAgentNamed(rootedTalk(fake, surface, top)));

    expect(fake.heard.files.map((claim) => claim.root)).toEqual([
      join(top, 'ledger'),
    ]);
  });
});

describe('scheduling out of one sentence, saved only on a yes', () => {
  const SENTENCE =
    '/schedule every weekday at 9am have the interlocutor summarise what changed';
  const PROPOSED = {
    cron: '0 9 * * 1-5',
    zone: 'Australia/Sydney',
    when: 'every weekday at 9am',
    agent: 'interlocutor',
    task: 'summarise what changed yesterday\nand keep it *short*',
    intoConversation: false,
    project: null,
    conversation: null,
    nextFires: [
      '2026-09-14T23:00:00Z',
      '2026-09-15T23:00:00Z',
      '2026-09-16T23:00:00Z',
    ],
    names: {
      schedule: 'summarise-a1',
      trigger: 'summarise-a1',
      event: 'summarise-a1',
    },
  };

  /** A server that reads the sentence into {@link PROPOSED} and saves what it is sent. */
  async function scheduling(): Promise<Fake> {
    const fake = await scriptedPlowshare([]);
    fake.script('schedule.read', { code: 'OK', payload: PROPOSED });
    fake.script('schedule.save', { code: 'OK', payload: { status: 'active' } });
    return fake;
  }

  function inSydney(
    fake: Fake,
    surface: Surface,
  ): Parameters<typeof converse>[0] {
    return { ...talkingTo(fake, surface), zone: 'Australia/Sydney' };
  }

  const typesOf = (fake: Fake): string[] =>
    fake.heard.frames.map((frame) => frame.type);
  const payloadOf = (fake: Fake, type: string): unknown =>
    fake.heard.frames.find((frame) => frame.type === type)?.payload;

  it('shows the whole proposal and saves a monitored file on a y', async () => {
    const fake = await scheduling();
    const prompt = scripted([SENTENCE, 'y']);
    await converse(inSydney(fake, prompt));
    const said = prompt.said.join('\n');

    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
      'schedule.save',
    ]);
    expect(payloadOf(fake, 'schedule.read')).toEqual({
      text: 'every weekday at 9am have the interlocutor summarise what changed',
      zone: 'Australia/Sydney',
    });
    expect(payloadOf(fake, 'schedule.save')).toEqual({
      name: 'summarise-a1',
      project: null,
      source: 'server',
      overwrite: false,
      definition: {
        version: 1,
        cron: PROPOSED.cron,
        zone: PROPOSED.zone,
        paused: false,
        action: {
          kind: 'agent',
          agent: 'interlocutor',
          name: null,
          input: PROPOSED.task,
          mode: null,
        },
        target: {
          kind: 'mailbox',
          project: null,
          conversation: null,
          to: null,
          route: null,
        },
        limits: { maxModelCalls: null, maxTurns: null, queueCap: 1 },
      },
    });
    expect(said).toContain(
      'every weekday at 9am → 0 9 * * 1-5 (Australia/Sydney)',
    );
    expect(said).toContain(
      'next: Tue 15 Sep 09:00 · Wed 16 Sep 09:00 · Thu 17 Sep 09:00',
    );
    expect(said).toContain(
      'agent: interlocutor · runs in: global · results: your inbox',
    );
    // VERBATIM: the asterisks survive, which a markdown pass would eat.
    expect(said).toContain('and keep it *short*');
    expect(said).toContain('save? [y/n]');
    expect(said).toContain('saved summarise-a1 · next Tue 15 Sep 09:00');
  });

  it('carries the project and the conversation a person is in', async () => {
    const fake = await scheduling();
    const prompt = scripted(['how many modules?', SENTENCE, 'n']);
    await converse({ ...inSydney(fake, prompt), project: 'plowshare' });

    expect(payloadOf(fake, 'schedule.read')).toEqual({
      text: 'every weekday at 9am have the interlocutor summarise what changed',
      zone: 'Australia/Sydney',
      project: 'plowshare',
      conversation: 'c-1',
    });
  });

  it('sends nothing after the reading when the answer is n', async () => {
    const fake = await scheduling();
    const prompt = scripted([SENTENCE, 'n']);
    await converse(inSydney(fake, prompt));

    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
    ]);
    expect(prompt.said).toContain('not saved');
  });

  it('does not run a command or an utterance typed in place of the answer', async () => {
    const fake = await scheduling();
    fake.script('schedule.list', { code: 'OK', payload: [] });
    const prompt = scripted([
      SENTENCE,
      '/schedule list',
      SENTENCE,
      'what did you run?',
    ]);
    await converse(inSydney(fake, prompt));
    const said = prompt.said.join('\n');

    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
      'schedule.read',
    ]);
    expect(said).toContain('type it again');
    expect(said).not.toContain('no schedules');
  });

  it('preserves refusal without replay or implicit rollback', async () => {
    const fake = await scheduling();
    fake.script('schedule.save', {
      code: 'BAD_REQUEST',
      said: 'The schedule workspace is offline',
    });
    const prompt = scripted([SENTENCE, 'yes']);
    await converse(inSydney(fake, prompt));
    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
      'schedule.save',
    ]);
    expect(prompt.said.join('\n')).toContain('not replayed');
    expect(prompt.said.join('\n')).toContain(
      'The schedule workspace is offline',
    );
  });

  it("shows a refused reading in the server's words and asks nothing", async () => {
    const fake = await scheduling();
    fake.script('schedule.read', {
      code: 'BAD_REQUEST',
      said: 'that sentence does not say when',
    });
    const prompt = scripted([SENTENCE, '/help']);
    await converse(inSydney(fake, prompt));
    const said = prompt.said.join('\n');

    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
    ]);
    expect(said).toContain('that sentence does not say when');
    expect(said).not.toContain('save? [y/n]');
    // The next line was a command of its own, and ran.
    expect(said).toContain('what scheduled runs left for you');
  });

  it('saves nothing and ends when the input ends at the question', async () => {
    const fake = await scheduling();
    const prompt = scripted([SENTENCE]);
    await converse(inSydney(fake, prompt));

    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
    ]);
    expect(prompt.said.join('\n')).toContain('save? [y/n]');
    expect(prompt.said.join('\n')).not.toContain('not saved');
  });

  it('shows a model that is away verbatim', async () => {
    const fake = await scheduling();
    fake.script('schedule.read', {
      code: 'MODEL_UNAVAILABLE',
      said: 'the model that reads schedules is not answering right now; nothing was saved',
    });
    const prompt = scripted([SENTENCE]);
    await converse(inSydney(fake, prompt));

    expect(prompt.said).toContain(
      'the model that reads schedules is not answering right now; nothing was saved',
    );
    expect(typesOf(fake)).toEqual([
      'inbox.list',
      'project.list',
      'agent.list',
      'schedule.read',
    ]);
  });

  describe('managing a schedule and the triggers listening to it', () => {
    async function withAPair(): Promise<Fake> {
      const fake = await scriptedPlowshare([]);
      fake.script('schedule.list', {
        code: 'OK',
        payload: [
          {
            name: 'daily',
            cron: '0 9 * * *',
            zone: 'UTC',
            emits: 'daily-event',
            paused: false,
            nextFireAt: '2026-09-14T09:00:00Z',
            definedBy: 'someone',
          },
        ],
      });
      fake.script('trigger.list', {
        code: 'OK',
        payload: [
          {
            name: 'daily-trigger',
            event: 'daily-event',
            project: null,
            conversation: null,
            agent: 'interlocutor',
            task: 'say hello',
            maxModelCalls: null,
            maxTurns: null,
            queueCap: 1,
            paused: false,
            definedBy: 'someone',
          },
          {
            name: 'unrelated',
            event: 'other',
            project: null,
            conversation: null,
            agent: 'interlocutor',
            task: 'other',
            maxModelCalls: null,
            maxTurns: null,
            queueCap: 1,
            paused: false,
            definedBy: 'someone',
          },
        ],
      });
      for (const type of [
        'schedule.pause',
        'trigger.pause',
        'schedule.forget',
        'trigger.forget',
      ]) {
        fake.script(type, { code: 'NO_CONTENT' });
      }
      fake.script('event.fire', {
        code: 'OK',
        payload: [
          {
            id: 'fir_1',
            event: 'daily-event',
            data: '{}',
            schedule: null,
            fireAt: null,
            trigger: 'daily-trigger',
            target: 'trigger:daily-trigger',
            status: 'QUEUED',
            supersededBy: null,
            reason: null,
            jobId: null,
            arrivedAt: '2026-09-14T09:00:00Z',
            startedAt: null,
            finishedAt: null,
          },
        ],
      });
      fake.script('schedule.files', { code: 'OK', payload: [] });
      fake.script('firing.list', { code: 'OK', payload: [] });
      return fake;
    }

    const managed = (fake: Fake): { type: string; payload: unknown }[] =>
      fake.heard.frames.filter(
        (frame) =>
          ![
            'inbox.list',
            'project.list',
            'agent.list',
            'schedule.list',
            'trigger.list',
            'schedule.files',
          ].includes(frame.type),
      );

    it('pauses the schedule and its trigger, and resumes both', async () => {
      const fake = await withAPair();
      const prompt = scripted([
        '/schedule pause daily',
        '/schedule resume daily',
      ]);
      await converse(talkingTo(fake, prompt));

      expect(managed(fake)).toEqual([
        {
          type: 'schedule.pause',
          payload: { schedule: 'daily', paused: true },
        },
        {
          type: 'trigger.pause',
          payload: { trigger: 'daily-trigger', paused: true },
        },
        {
          type: 'trigger.pause',
          payload: { trigger: 'daily-trigger', paused: false },
        },
        {
          type: 'schedule.pause',
          payload: { schedule: 'daily', paused: false },
        },
      ]);
      expect(prompt.said).toContain('paused daily');
      expect(prompt.said).toContain('resumed daily');
    });

    it('forgets the schedule first, so its clock stops, and then the trigger', async () => {
      const fake = await withAPair();
      const prompt = scripted(['/schedule forget daily']);
      await converse(talkingTo(fake, prompt));

      expect(managed(fake)).toEqual([
        { type: 'schedule.forget', payload: { schedule: 'daily' } },
        { type: 'trigger.forget', payload: { trigger: 'daily-trigger' } },
      ]);
      expect(prompt.said).toContain('forgot daily');
    });

    it("says the server's sentence for a schedule it does not have", async () => {
      const fake = await withAPair();
      fake.script('schedule.pause', {
        code: 'NOT_FOUND',
        said: 'there is no schedule nope',
      });
      const prompt = scripted(['/schedule pause nope']);
      await converse(talkingTo(fake, prompt));

      expect(managed(fake)).toEqual([
        { type: 'schedule.pause', payload: { schedule: 'nope', paused: true } },
      ]);
      expect(prompt.said).toContain('there is no schedule nope');
      expect(prompt.said).not.toContain('paused nope');
    });

    it('lists the schedules with their triggers, fires one by name, and lists firings', async () => {
      const fake = await withAPair();
      const prompt = scripted([
        '/schedule list',
        '/schedule',
        '/fire daily',
        '/fire nope',
        '/firings',
      ]);
      await converse(talkingTo(fake, prompt));
      const said = prompt.said.join('\n');

      expect(said).toContain('daily · 0 9 * * * (UTC) · next Mon 14 Sep 09:00');
      // Bare /schedule lists too: the listing appears twice.
      expect(said.split('daily · 0 9 * * * (UTC)').length - 1).toBe(2);
      expect(said).toContain('→ interlocutor: say hello');
      expect(said).toContain('triggers with no schedule');
      expect(managed(fake)).toEqual([
        { type: 'event.fire', payload: { event: 'daily-event' } },
        { type: 'firing.list', payload: { limit: 10 } },
      ]);
      expect(said).toContain('daily-trigger · QUEUED');
      expect(said).toContain('no schedule named nope');
      expect(said).toContain('no firings yet');
    });
  });
});

describe('the runs waiting on a person, answered from the prompt', () => {
  /** One `RunView` in the asking state, as `OrchestrationFrames.viewOf` writes it. */
  const askingRun = (
    id: string,
    definition: string,
  ): Record<string, unknown> => ({
    id,
    definition,
    tier: 'project',
    project: 'plowshare',
    state: 'asking',
    depth: 0,
    createdAt: '2026-09-25T01:43:00Z',
  });
  const scriptWaiting = (
    fake: Fake,
    runs: readonly Record<string, unknown>[],
  ): void => {
    fake.script('orchestration.list', {
      code: 'OK',
      payload: { orchestrations: runs },
    });
    fake.script('orchestration.status', (payload) => {
      const id = (payload as { id: string }).id;
      return {
        code: 'OK',
        payload: {
          orchestration: runs.find((run) => run['id'] === id),
          todos: [],
          messages: [
            {
              kind: 'question',
              author: 'conductor',
              text: `Which database for ${id}?`,
            },
          ],
          children: [],
        },
      };
    });
  };

  it('lists them with what they asked, and answers the one waiting without its id', async () => {
    const fake = await scriptedPlowshare([]);
    scriptWaiting(fake, [askingRun('orc_1', 'implement_specification')]);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_1', state: 'running' },
    });
    const prompt = scripted(['/answer', '/answer PostgreSQL, version 16']);
    const waited: (readonly Waiting[])[] = [];
    await converse({
      ...talkingTo(fake, { ...prompt, waiting: (runs) => waited.push(runs) }),
      waitingEvery: 60_000,
    });
    const said = prompt.said.join('\n');

    // Said once, with its question, by the check and not by the list.
    expect(said).toContain(
      'implement_specification (orc_1) is asking: Which database for orc_1?',
    );
    expect(said).toContain(`waiting for an answer:`);
    expect(waited.at(0)?.[0]).toMatchObject({
      id: 'orc_1',
      question: 'Which database for orc_1?',
    });
    expect(
      fake.heard.frames.find((frame) => frame.type === 'orchestration.list')
        ?.payload,
    ).toEqual({ state: 'asking', limit: 20 });
    expect(
      fake.heard.frames.find((frame) => frame.type === 'orchestration.answer')
        ?.payload,
    ).toEqual({ id: 'orc_1', answer: 'PostgreSQL, version 16' });
    expect(said).toContain('orc_1 answered — it is now running');
  });

  it('holds an answer that does not say which of two runs it is for, and sends nothing', async () => {
    const fake = await scriptedPlowshare([]);
    scriptWaiting(fake, [
      askingRun('orc_1', 'implement_specification'),
      askingRun('orc_2', 'code_implementation'),
    ]);
    const prompt = scripted(['/answer', '/answer yes']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    expect(said).toContain(
      'orc_2  code_implementation — Which database for orc_2?',
    );
    expect(said).toContain(
      'that answer was not sent — say which run it is for:',
    );
    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.answer'),
    ).toBe(false);
  });

  it('shows a command approval raised under a conductor, and answers it with a decision', async () => {
    // Measured 2026-09-25: a coder under an orchestration asked to run pytest; the
    // approval was written against the conductor's conversation, which no screen has
    // open, and nobody was shown it for an hour.
    const fake = await scriptedPlowshare([]);
    scriptWaiting(fake, []);
    fake.script('approval.list', {
      code: 'OK',
      payload: {
        approvals: [
          {
            id: 'apr_1',
            conversation: 'cnv_conductor',
            askedIn: 'cnv_coder',
            agent: 'code_implementation',
            side: 'local',
            command: ['pytest', '-q', 'tests/test_init_package.py'],
            cwd: '/Users/example/game_test',
            state: 'asked',
            defaultPrefix: ['pytest', '-q'],
          },
        ],
      },
    });
    fake.script('approval.answer', {
      code: 'OK',
      payload: { id: 'apr_1', state: 'allowed', busy: false },
    });
    const prompt = scripted(['/answer', '/answer once']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    expect(said).toContain(
      'code_implementation wants to run `pytest -q tests/test_init_package.py`' +
        ' in /Users/example/game_test (local)',
    );
    expect(
      fake.heard.frames.find((frame) => frame.type === 'approval.list')
        ?.payload,
    ).toEqual({ mine: true });
    expect(
      fake.heard.frames.find((frame) => frame.type === 'approval.answer')
        ?.payload,
    ).toEqual({ id: 'apr_1', decision: 'once' });
    expect(said).toContain('apr_1 allowed — the run carries on');
  });

  it('checks nothing in the background unless it was told how often', async () => {
    // `run` turns it on; a caller that never heard of it sends the frames
    // it always sent.
    const fake = await scriptedPlowshare([]);
    scriptWaiting(fake, [askingRun('orc_1', 'implement_specification')]);
    await converse(talkingTo(fake, scripted([])));

    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.list'),
    ).toBe(false);
  });

  /** One `RunView` a stall sweep has marked, as `OrchestrationFrames.viewOf` writes it —
   *  still `running`, since a stall sweep never moves the state, only the mark. */
  const stalledRun = (
    id: string,
    definition: string,
  ): Record<string, unknown> => ({
    id,
    definition,
    tier: 'project',
    project: 'plowshare',
    state: 'running',
    depth: 0,
    createdAt: '2026-09-25T01:43:00Z',
    stalledSince: '2026-09-25T02:03:00Z',
  });

  /** Like {@link scriptWaiting}, but `orchestration.list` answers `asking` and `running`
   *  differently, since a stalled run's own listing asks for the latter. */
  const scriptWaitingAndStalled = (
    fake: Fake,
    asking: readonly Record<string, unknown>[],
    stalled: readonly Record<string, unknown>[],
  ): void => {
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'running'
            ? stalled
            : asking,
      },
    }));
  };

  it('lists a stalled run beside the asking ones, and holds a bare answer meant for it', async () => {
    // Spec 2026-09-27 §4, task 6's own close: a stalled run is shown — a look or a
    // cancel, never something `/answer` can guess at — because the person who can
    // tell whether it is actually stuck is the only one this run was ever going to
    // reach, and it must not be silently answered by a person who meant something
    // else entirely.
    const fake = await scriptedPlowshare([]);
    scriptWaitingAndStalled(
      fake,
      [],
      [stalledRun('orc_9', 'implement_specification')],
    );
    const prompt = scripted(['/answer', '/answer yes']);
    const waited: (readonly Waiting[])[] = [];
    await converse({
      ...talkingTo(fake, { ...prompt, waiting: (runs) => waited.push(runs) }),
      waitingEvery: 60_000,
    });
    const said = prompt.said.join('\n');

    // Announced once, by the check, in the stalled wording rather than "is asking".
    expect(said).toContain(
      'implement_specification (orc_9) has done nothing for a while' +
        ' — /runs orc_9 to look, /cancel orc_9 to stop it',
    );
    expect(waited.at(0)?.[0]).toMatchObject({ id: 'orc_9', kind: 'stalled' });
    // Listed for a bare /answer, but never as the run a bare answer would reach.
    expect(said).toContain('orc_9  implement_specification — stalled');
    expect(said).toContain('that answer was not sent');
    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.answer'),
    ).toBe(false);
  });

  it('still answers the one asking run when a stalled run is also waiting', async () => {
    // The exclusion is of the stalled id from the bare-answer guess, not of the
    // listing from working at all: an asking run waiting alongside a stalled one is
    // still the one thing a bare answer reaches.
    const fake = await scriptedPlowshare([]);
    scriptWaitingAndStalled(
      fake,
      [askingRun('orc_1', 'implement_specification')],
      [stalledRun('orc_9', 'code_implementation')],
    );
    fake.script('orchestration.status', (payload) => {
      const id = (payload as { id: string }).id;
      return {
        code: 'OK',
        payload: {
          orchestration: {
            id,
            definition: 'implement_specification',
            tier: 'project',
            project: 'plowshare',
            state: 'asking',
            depth: 0,
            createdAt: '2026-09-25T01:43:00Z',
          },
          todos: [],
          messages: [
            { kind: 'question', author: 'conductor', text: 'Which database?' },
          ],
          children: [],
        },
      };
    });
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_1', state: 'running' },
    });
    const prompt = scripted(['/answer', '/answer PostgreSQL']);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });
    const said = prompt.said.join('\n');

    expect(
      fake.heard.frames.find((frame) => frame.type === 'orchestration.answer')
        ?.payload,
    ).toEqual({ id: 'orc_1', answer: 'PostgreSQL' });
    expect(said).toContain('orc_1 answered — it is now running');
  });

  /** A phase stopped at its turn cap, as the listing, its status and its record say — until
   *  `answeredBy` names who settled it elsewhere, when the listing leaves it out and its status
   *  carries the answer. */
  const scriptCapQuestion = (
    fake: Fake,
    answeredBy?: () => string | undefined,
    ids: readonly string[] = ['orc_2'],
  ): void => {
    const cappedOf = (id: string): Record<string, unknown> => ({
      ...askingRun(id, 'code_implementation'),
      parent: 'orc_1',
      depth: 1,
      pendingCap: 'turn_cap',
    });
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking' &&
          answeredBy?.() === undefined
            ? ids.map(cappedOf)
            : [],
      },
    }));
    fake.script('orchestration.status', (payload) => {
      const by = answeredBy?.();
      return {
        code: 'OK',
        payload: {
          orchestration: {
            ...cappedOf((payload as { id: string }).id),
            state: by === undefined ? 'asking' : 'running',
          },
          todos: [],
          messages: [
            {
              kind: 'question',
              author: 'harness',
              text: 'stopped at its turn cap',
            },
            ...(by === undefined
              ? []
              : [{ kind: 'answer', author: by, text: 'yes' }]),
          ],
          children: [],
        },
      };
    });
    fake.script(
      'orchestration.record',
      recordOf(() => [
        { ...recordRow(6, 'stage_moved', 'the root moved on'), run: 'orc_1' },
        {
          ...recordRow(
            7,
            'stage_moved',
            '03-character · spec: in_progress → done',
          ),
          run: 'orc_2',
        },
        {
          ...recordRow(8, 'stage_moved', 'the root moved again'),
          run: 'orc_1',
        },
      ]),
    );
  };
  const answered = (fake: Fake): unknown =>
    fake.heard.frames.find((frame) => frame.type === 'orchestration.answer')
      ?.payload;

  it("opens a dialog for a phase's cap question and answers yes with y", async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    const dialogs: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answered(fake) !== undefined);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async (lines) => {
          dialogs.push(lines);
          return 'continue';
        },
      }),
      waitingEvery: 60_000,
    });

    expect(dialogs).toHaveLength(1);
    expect(dialogs[0]?.[0]).toBe(
      'code_implementation (orc_2) stopped at its turn cap',
    );
    // The run's own latest milestone, not its tree's.
    expect(dialogs[0]?.[1]).toContain(
      '03-character · spec: in_progress → done',
    );
    expect(dialogs[0]?.[2]).toBe(
      'y continue · n stop · a always (auto-continue 3) · w watch · esc later',
    );
    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'yes' });
  });

  it('answers no with n', async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    const prompt = stepping([
      async () => {
        await until(() => answered(fake) !== undefined);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, capDialog: async () => 'stop' }),
      waitingEvery: 60_000,
    });

    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'no' });
  });

  it('opens a cap question as a list in the modal on later, and up to n then enter sends what n sent before', async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    // Up from later, past watch and always, to stop.
    const strokes: QuestionStroke[] = [
      { kind: 'up' },
      { kind: 'up' },
      { kind: 'up' },
      { kind: 'enter' },
    ];
    const shown: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answered(fake) !== undefined);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        questionDialog: async (lines) => {
          shown.push(lines);
          return strokes.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    // The count `a` writes, read before the list is drawn, as the keys line says it.
    expect(shown[0]).toContain('  a  always go on (auto-continue 3)');
    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'no' });
  });

  it('never lowers a higher auto-continue with a', async () => {
    // The final review: `a` wrote 3 over the 5 the person had set, so "always" went on less.
    const top = await realpath(await mkdtemp(join(tmpdir(), 'always-')));
    await mkdir(join(top, '.plowshare'), { recursive: true });
    await writeFile(join(top, '.plowshare', 'project'), 'ledger\n');
    const file = join(top, '.plowshare', 'environment.yml');
    await writeFile(file, 'caps:\n  auto-continue: 5\n');
    const fake = await scriptedPlowshare([], EVERY_AGENT, undefined, {
      rosters: {
        ledger: [
          {
            name: 'sophron',
            tools: [],
            calls: [],
            scopes: [],
            served: true,
            withheld: [],
            bot: true,
            description: 'a bot of the ledger',
            preferred: true,
          },
        ],
      },
    });
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    fake.script('orchestration.caps', {
      code: 'OK',
      payload: {
        project: 'ledger',
        applied: 0,
        steps: { value: null, source: 'definition' },
        budget: { value: null, source: 'definition' },
        autoContinue: { value: 5, source: '.plowshare/environment.yml' },
      },
    });
    const prompt = stepping([
      async () => {
        await until(() => answered(fake) !== undefined);
        return undefined;
      },
    ]);
    const shown: (readonly string[])[] = [];
    await converse({
      ...withNoAgentNamed(
        talkingTo(fake, {
          ...prompt,
          capDialog: async (lines) => {
            shown.push(lines);
            return 'always';
          },
        }),
      ),
      here: top,
      machine: 'bench.local',
      waitingEvery: 60_000,
    });

    // The label says what `a` writes (re-review), and it writes that.
    expect(shown[0]?.[2]).toBe(
      'y continue · n stop · a always (auto-continue 5) · w watch · esc later',
    );
    expect(await readFile(file, 'utf8')).toBe('caps:\n  auto-continue: 5\n');
    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'yes' });
    await rm(top, { recursive: true, force: true });
  });

  it('sends no yes for an a whose setting could not be made, and asks again', async () => {
    // Nothing is rooted here, so auto-continue cannot be written: a yes alone would be an
    // answer the person did not give.
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    const keys: CapKey[] = ['always', 'later'];
    let asked = 0;
    const prompt = stepping([
      async () => {
        await until(() => asked === 2);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => {
          asked += 1;
          return keys.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    expect(prompt.said.join('\n')).toContain(describeCapNeedsAProject());
    expect(answered(fake)).toBeUndefined();
    expect(asked).toBe(2);
  });

  it('puts the question up again first after w, and two questions one after the other', async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake, undefined, ['orc_2', 'orc_3']);
    fake.script('orchestration.answer', (payload) => ({
      code: 'OK',
      payload: { id: (payload as { id: string }).id, state: 'running' },
    }));
    const opened: string[] = [];
    const keys: CapKey[] = ['watch', 'continue', 'continue'];
    const views: (Viewed | undefined)[] = [];
    const prompt = stepping([
      async () => {
        await until(
          () =>
            fake.heard.frames.filter(
              (frame) => frame.type === 'orchestration.answer',
            ).length === 2,
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async (lines) => {
          opened.push(lines[0] ?? '');
          return keys.shift();
        },
        view: (viewed) => views.push(viewed),
        viewKey: async () => 'close',
      }),
      waitingEvery: 60_000,
    });

    // The viewer came up and went away between the first two.
    expect(views.some((viewed) => viewed !== undefined)).toBe(true);
    expect(views.at(-1)).toBeUndefined();
    expect(opened.map((line) => line.split(' ')[1])).toEqual([
      '(orc_2)',
      '(orc_2)',
      '(orc_3)',
    ]);
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.answer')
        .map((frame) => frame.payload),
    ).toEqual([
      { id: 'orc_2', answer: 'yes' },
      { id: 'orc_3', answer: 'yes' },
    ]);
  });

  it('opens no viewer for a w when a line left the prompt while the record was read', async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    const rows = recordOf(() => [
      {
        ...recordRow(7, 'stage_moved', 'spec: in_progress → done'),
        run: 'orc_2',
      },
    ]);
    const listed = (): boolean =>
      fake.heard.frames.some(
        (frame) =>
          frame.type === 'orchestration.list' &&
          (frame.payload as { state?: string }).state === undefined,
      );
    // `/runs` is still being handled when the viewer's reads have landed: its listing answers
    // only after the viewer's own read (every kind) and the status read after it.
    let statusesThen: number | undefined;
    const statuses = (): number =>
      fake.heard.frames.filter((frame) => frame.type === 'orchestration.status')
        .length;
    fake.script('orchestration.record', async (payload) => {
      if ((payload as { kinds?: unknown }).kinds === undefined) {
        await until(listed);
        statusesThen = statuses();
      }
      return rows(payload);
    });
    fake.script('orchestration.list', async (payload) => {
      if ((payload as { state?: string }).state === undefined) {
        await until(
          () => statusesThen !== undefined && statuses() > statusesThen,
        );
        await new Promise((done) => setTimeout(done, 50));
        return { code: 'OK', payload: { orchestrations: [] } };
      }
      return {
        code: 'OK',
        payload: {
          orchestrations: [
            {
              ...askingRun('orc_2', 'code_implementation'),
              parent: 'orc_1',
              depth: 1,
              pendingCap: 'turn_cap',
            },
          ],
        },
      };
    });
    const keys: CapKey[] = ['watch', 'later'];
    let opened = 0;
    const views: (Viewed | undefined)[] = [];
    let lineTaken: (line: string | undefined) => void = () => undefined;
    const prompt = stepping([
      () =>
        new Promise((settle) => {
          lineTaken = settle;
        }),
      async () => {
        await until(() => opened === 2);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => {
          opened += 1;
          const key = keys.shift();
          if (key === 'watch') {
            // The person pressed w, then sent a line before the viewer could open.
            setImmediate(() => lineTaken('/runs'));
          }
          return key;
        },
        view: (viewed) => views.push(viewed),
        viewKey: async () => 'close',
      }),
      waitingEvery: 60_000,
    });

    expect(views).toEqual([]);
    expect(opened).toBe(2);
  });

  it('leaves the question in the waiting list on esc, where /answer still reaches it', async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    let asked = 0;
    const prompt = stepping([
      async () => {
        await until(() => asked > 0);
        return '/answer';
      },
      async () => '/answer yes',
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => {
          asked += 1;
          return 'later';
        },
      }),
      waitingEvery: 60_000,
    });

    // Asked once: later is not a reason to ask again at the next check.
    expect(asked).toBe(1);
    expect(prompt.said.join('\n')).toContain('orc_2  code_implementation');
    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'yes' });
  });

  it('takes the dialog away when the parent answered first, saying who and what', async () => {
    const fake = await scriptedPlowshare([]);
    let by: string | undefined;
    scriptCapQuestion(fake, () => by);
    let closed = 0;
    let open: ((key: CapKey | undefined) => void) | undefined;
    const prompt = stepping([
      async () => {
        await until(() => open !== undefined);
        by = 'implement_specification';
        fake.push(
          JSON.stringify({
            kind: 'orchestration.changed',
            orchestration: 'orc_2',
            state: 'running',
          }),
        );
        await until(() =>
          prompt.said.some((line) =>
            line.includes('cap question was answered by'),
          ),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: (): Promise<CapKey | undefined> =>
          new Promise((settle) => {
            open = settle;
          }),
        closeDialog: () => {
          closed += 1;
          open?.(undefined);
        },
      }),
      waitingEvery: 60_000,
    });

    expect(closed).toBe(1);
    expect(prompt.said.join('\n')).toContain(
      "orc_2's cap question was answered by implement_specification: yes",
    );
    expect(answered(fake)).toBeUndefined();
  });

  it("says who answered first when the person's y came second", async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    const second =
      "Orchestration orc_2's question was already answered by implement_specification (`yes`);" +
      ' nothing changed.';
    fake.script('orchestration.answer', { code: 'BAD_REQUEST', said: second });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('already answered')),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, capDialog: async () => 'continue' }),
      waitingEvery: 60_000,
    });

    expect(prompt.said.join('\n')).toContain(second);
  });

  it('takes an open dialog down while a line is handled, and puts it up again at the prompt', async () => {
    // The dialog is modal only while the prompt waits: a line typed ahead of it is a turn or a
    // command, and a question over a turn would take the keys that stop it.
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    const opened: string[] = [];
    let open: ((key: CapKey | undefined) => void) | undefined;
    let closed = 0;
    const prompt = stepping([
      async () => {
        await until(() => opened.length === 1);
        return '/cap';
      },
      async () => {
        await until(() => answered(fake) !== undefined);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: (lines): Promise<CapKey | undefined> => {
          opened.push(lines[0] ?? '');
          return opened.length === 1
            ? new Promise((settle) => {
                open = settle;
              })
            : Promise.resolve('continue');
        },
        closeDialog: () => {
          closed += 1;
          open?.(undefined);
        },
      }),
      waitingEvery: 60_000,
    });

    expect(closed).toBe(1);
    expect(opened).toHaveLength(2);
    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'yes' });
  });

  it('prints the question and its keys on a plain surface, and takes a lone y', async () => {
    const fake = await scriptedPlowshare([]);
    scriptCapQuestion(fake);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) =>
            line.includes('an empty line decides later'),
          ),
        );
        return 'y';
      },
      async () => {
        await until(() => answered(fake) !== undefined);
        return undefined;
      },
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });
    const said = prompt.said.join('\n');

    expect(said).toContain(
      'code_implementation (orc_2) stopped at its turn cap',
    );
    expect(said).toContain('y continue · n stop · a always (auto-continue 3)');
    expect(answered(fake)).toEqual({ id: 'orc_2', answer: 'yes' });
    // A lone letter answering the question is not a turn.
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });

  it('decides later on a plain surface with an empty line, and speaks the next line as ever', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    scriptCapQuestion(fake);
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) =>
            line.includes('an empty line decides later'),
          ),
        );
        return '';
      },
      async () => 'y',
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });

    // Later: the y after it is the person speaking, not an answer to a question put away.
    expect(answered(fake)).toBeUndefined();
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      true,
    );
  });
});

describe('the question dialog, for every question the person can answer', () => {
  /** One `RunView` asking, as `OrchestrationFrames.viewOf` writes it — a root started by `sophron`. */
  const asking = (
    id: string,
    extra: Record<string, unknown> = {},
  ): Record<string, unknown> => ({
    id,
    definition: 'implement_specification',
    tier: 'project',
    project: 'plowshare',
    state: 'asking',
    depth: 0,
    createdAt: '2026-09-25T01:43:00Z',
    callerAgent: 'sophron',
    ...extra,
  });
  /**
   * The asking listing, each run's status with its question — and, once `answeredBy` names who
   * settled one elsewhere, that run left out and its answer on its status. `stalled` answers the
   * running listing; approvals answer theirs.
   */
  const scriptAsking = (
    fake: Fake,
    runs: readonly Record<string, unknown>[],
    answeredBy?: () => string | undefined,
    stalled: readonly Record<string, unknown>[] = [],
  ): void => {
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'running'
            ? stalled
            : (payload as { state?: string }).state === 'asking' &&
                answeredBy?.() === undefined
              ? runs
              : [],
      },
    }));
    fake.script('orchestration.status', (payload) => {
      const id = (payload as { id: string }).id;
      const by = answeredBy?.();
      return {
        code: 'OK',
        payload: {
          orchestration: {
            ...runs.find((run) => run['id'] === id),
            state: by === undefined ? 'asking' : 'running',
          },
          todos: [],
          messages: [
            {
              kind: 'question',
              author: 'conductor',
              text: `Which database for ${id}?`,
            },
            ...(by === undefined
              ? []
              : [{ kind: 'answer', author: by, text: 'PostgreSQL' }]),
          ],
          children: [],
        },
      };
    });
    fake.script(
      'orchestration.record',
      recordOf(() => []),
    );
  };
  const framesOf = (fake: Fake, type: string): unknown[] =>
    fake.heard.frames
      .filter((frame) => frame.type === type)
      .map((frame) => frame.payload);

  it("opens a root's question with its keys, and r puts /answer and its id in the composer", async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1')]);
    const dialogs: (readonly string[])[] = [];
    const keysGiven: (readonly DialogKey[] | undefined)[] = [];
    const filled: string[] = [];
    const prompt = stepping([
      async () => {
        await until(() => filled.length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async (lines, keys) => {
          dialogs.push(lines);
          keysGiven.push(keys);
          return 'reply';
        },
        prefill: (text) => filled.push(text),
      }),
      waitingEvery: 60_000,
    });

    expect(dialogs).toEqual([
      [
        'orc_1 (implement_specification) asks:',
        'Which database for orc_1?',
        'sophron, which started it, has it too; the first answer settles it.',
        'r reply · w watch · esc later',
      ],
    ]);
    expect(keysGiven).toEqual([['reply', 'watch', 'later']]);
    expect(filled).toEqual(['/answer orc_1 ']);
    // Nothing is sent for the person: the reply is theirs to type.
    expect(framesOf(fake, 'orchestration.answer')).toEqual([]);
  });

  it('opens a question with options in the modal, and sends the choices the keys made', async () => {
    const fake = await scriptedPlowshare([]);
    const structure = {
      lead: 'First:',
      questions: [
        {
          header: 'Store',
          question: 'Which database?',
          multi: false,
          options: [
            { label: 'Postgres', description: 'p' },
            { label: 'SQLite', description: 's' },
          ],
        },
      ],
    };
    let answered = false;
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking' && !answered
            ? [asking('orc_1')]
            : [],
      },
    }));
    fake.script('orchestration.status', () => ({
      code: 'OK',
      payload: {
        orchestration: {
          ...asking('orc_1'),
          state: answered ? 'running' : 'asking',
        },
        todos: [],
        messages: [
          {
            kind: 'question',
            author: 'conductor',
            text: 'First: …',
            structure,
          },
        ],
        children: [],
      },
    }));
    fake.script('orchestration.answer', () => {
      answered = true;
      return { code: 'OK', payload: { id: 'orc_1', state: 'running' } };
    });
    fake.script(
      'orchestration.record',
      recordOf(() => []),
    );
    const strokes: QuestionStroke[] = [{ kind: 'down' }, { kind: 'enter' }];
    const shown: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answered);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        questionDialog: async (lines) => {
          shown.push(lines);
          return strokes.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    expect(shown[0]?.[0]).toBe('orc_1 (implement_specification) asks:');
    expect(shown[0]).toContain('› 1. ( ) Postgres — p');
    expect(shown[1]).toContain('› 2. ( ) SQLite — s');
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', choices: [{ header: 'Store', chosen: ['SQLite'] }] },
    ]);
  });

  it('opens the modal again when the server refused the choices and the run still asks', async () => {
    // Final review: a refused send closed the modal for good, and the question was reachable
    // only as `/answer` in words.
    const fake = await scriptedPlowshare([]);
    const structure = {
      lead: '',
      questions: [
        {
          header: 'Store',
          question: 'Which database?',
          multi: false,
          options: [
            { label: 'Postgres', description: 'p' },
            { label: 'SQLite', description: 's' },
          ],
        },
      ],
    };
    let answered = false;
    let refused = 0;
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking' && !answered
            ? [asking('orc_1')]
            : [],
      },
    }));
    fake.script('orchestration.status', () => ({
      code: 'OK',
      payload: {
        orchestration: {
          ...asking('orc_1'),
          state: answered ? 'running' : 'asking',
        },
        todos: [],
        messages: [
          {
            kind: 'question',
            author: 'conductor',
            text: 'Which database?',
            structure,
          },
        ],
        children: [],
      },
    }));
    fake.script('orchestration.answer', () => {
      if (refused === 0) {
        refused += 1;
        return {
          code: 'BAD_REQUEST',
          said: "'Store' has no option 'SQLite'; its options are 'Postgres'.",
        };
      }
      answered = true;
      return { code: 'OK', payload: { id: 'orc_1', state: 'running' } };
    });
    fake.script(
      'orchestration.record',
      recordOf(() => []),
    );
    const strokes: QuestionStroke[] = [
      { kind: 'down' },
      { kind: 'enter' },
      { kind: 'enter' },
    ];
    const shown: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answered);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        questionDialog: async (lines) => {
          shown.push(lines);
          return strokes.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    expect(prompt.said.join('\n')).toContain("'Store' has no option 'SQLite'");
    // Opened again from the start, the arrows on the first option.
    expect(shown[2]).toContain('› 1. ( ) Postgres — p');
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', choices: [{ header: 'Store', chosen: ['SQLite'] }] },
      { id: 'orc_1', choices: [{ header: 'Store', chosen: ['Postgres'] }] },
    ]);
  });

  /** The Studio's install question, as `Studio.installQuestion` writes it: the draft beside the question. */
  const installing = (
    fake: Fake,
    answered: () => boolean,
    onAnswer: () => void,
  ): void => {
    const structure = {
      lead: "The draft triage at artifacts/triage.md passed the loader's trial.",
      questions: [
        {
          header: 'Install',
          question: 'Install triage into this project?',
          multi: false,
          options: [
            {
              label: 'Install',
              description: 'Write it into the project; a new orchestration',
              preview: '---',
            },
            { label: 'Leave it', description: 'Leave it as a draft' },
          ],
        },
      ],
      name: 'triage',
      path: 'artifacts/triage.md',
      text: '---\nname: triage\n---\nTriage the issue.\n',
      sha256: 'ab',
    };
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking' && !answered()
            ? [asking('orc_1')]
            : [],
      },
    }));
    fake.script('orchestration.status', () => ({
      code: 'OK',
      payload: {
        orchestration: {
          ...asking('orc_1'),
          state: answered() ? 'running' : 'asking',
        },
        todos: [],
        messages: [
          {
            kind: 'question',
            author: 'conductor',
            text: 'The draft triage …',
            structure,
          },
        ],
        children: [],
      },
    }));
    fake.script('orchestration.answer', () => {
      onAnswer();
      return { code: 'OK', payload: { id: 'orc_1', state: 'running' } };
    });
    fake.script(
      'orchestration.record',
      recordOf(() => []),
    );
  };

  it("opens an install question's whole draft on v, and esc puts the question back as it was", async () => {
    const fake = await scriptedPlowshare([]);
    let answered = false;
    installing(
      fake,
      () => answered,
      () => {
        answered = true;
      },
    );
    const strokes: QuestionStroke[] = [
      { kind: 'text', text: '2' },
      { kind: 'text', text: 'v' },
      { kind: 'enter' },
    ];
    const shown: (readonly string[])[] = [];
    const views: (Viewed | undefined)[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answered);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        questionDialog: async (lines) => {
          shown.push(lines);
          return strokes.shift();
        },
        view: (viewed) => views.push(viewed),
        viewKey: async () => 'close',
      }),
      waitingEvery: 60_000,
    });

    expect(shown[0]?.at(-1)).toContain('v view draft');
    const viewed = views[0];
    expect(viewed?.head.map(plainOf)).toEqual(['triage  artifacts/triage.md']);
    expect(viewed?.body.map(plainOf)).toEqual([
      '1 │ ---',
      '2 │ name: triage',
      '3 │ ---',
      '4 │ Triage the issue.',
    ]);
    expect(views.at(-1)).toBeUndefined();
    // Put back up as it was: the same choice, the arrows where they were.
    expect(shown).toHaveLength(3);
    expect(shown[2]).toEqual(shown[1]);
    expect(shown[2]).toContain('› 2. (•) Leave it — Leave it as a draft');
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', choices: [{ header: 'Install', chosen: ['Leave it'] }] },
    ]);
  });

  it('offers no v where there is no viewer to show the draft in, and v there changes nothing', async () => {
    const fake = await scriptedPlowshare([]);
    let answered = false;
    installing(
      fake,
      () => answered,
      () => {
        answered = true;
      },
    );
    const strokes: QuestionStroke[] = [
      { kind: 'text', text: 'v' },
      { kind: 'enter' },
    ];
    const shown: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answered);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        questionDialog: async (lines) => {
          shown.push(lines);
          return strokes.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    expect(shown[0]?.at(-1)).not.toContain('v view draft');
    expect(shown[1]).toEqual(shown[0]);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', choices: [{ header: 'Install', chosen: ['Install'] }] },
    ]);
  });

  it('fits a long question in words to the room its list leaves, the list and its hint included', async () => {
    // Final review: the question was fitted to the room as if one keys line followed it, and
    // the list put a row per option and a hint in that line's place — past the terminal.
    const fake = await scriptedPlowshare([]);
    const long = Array.from(
      { length: 40 },
      (_, at) => `Line ${at + 1} of what the run needs to know.`,
    ).join('\n');
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking'
            ? [asking('orc_1')]
            : [],
      },
    }));
    fake.script('orchestration.status', () => ({
      code: 'OK',
      payload: {
        orchestration: asking('orc_1'),
        todos: [],
        messages: [{ kind: 'question', author: 'conductor', text: long }],
        children: [],
      },
    }));
    fake.script(
      'orchestration.record',
      recordOf(() => []),
    );
    const shown: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => shown.length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        columns: () => 80,
        dialogRoom: () => 16,
        questionDialog: async (lines) => {
          shown.push(lines);
          return { kind: 'escape' };
        },
      }),
      waitingEvery: 60_000,
    });

    expect(shown[0]?.length).toBeLessThanOrEqual(16);
    expect(shown[0]?.[0]).toBe('orc_1 (implement_specification) asks:');
    expect(shown[0]).toContain('… /watch orc_1 for the rest');
    expect(shown[0]?.at(-1)).toBe(
      '↑↓ move · enter or a letter picks · esc later',
    );
  });

  it('opens a question in words as a list where the modal exists, and r still puts /answer in the composer', async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1')]);
    const shown: (readonly string[])[] = [];
    const filled: string[] = [];
    const prompt = stepping([
      async () => {
        await until(() => filled.length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'later',
        questionDialog: async (lines) => {
          shown.push(lines);
          return { kind: 'text', text: 'r' };
        },
        prefill: (text) => filled.push(text),
      }),
      waitingEvery: 60_000,
    });

    expect(shown[0]?.slice(-4)).toEqual([
      '  r  reply in words',
      '  w  watch the run first',
      '›    decide later',
      '↑↓ move · enter or a letter picks · esc later',
    ]);
    expect(filled).toEqual(['/answer orc_1 ']);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([]);
  });

  it("opens no question dialog for a phase's own question, an approval or a stalled run", async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(
      fake,
      [
        asking('orc_2', {
          parent: 'orc_1',
          depth: 1,
          callerAgent: 'implement_specification',
        }),
      ],
      undefined,
      [
        {
          ...asking('orc_9'),
          state: 'running',
          stalledSince: '2026-09-25T02:03:00Z',
        },
      ],
    );
    fake.script('approval.list', {
      code: 'OK',
      payload: {
        approvals: [
          {
            id: 'apr_1',
            conversation: 'cnv_conductor',
            askedIn: 'cnv_coder',
            agent: 'code_implementation',
            side: 'local',
            command: ['pytest'],
            cwd: '/tmp',
            state: 'asked',
            defaultPrefix: ['pytest'],
          },
        ],
      },
    });
    const waited: (readonly Waiting[])[] = [];
    let opened = 0;
    const prompt = stepping([
      async () => {
        await until(() => waited.some((runs) => runs.length === 2));
        // A second prompt, so a dialog queued by the check would have had its moment.
        return '/cap';
      },
      async () => undefined,
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        waiting: (runs) => waited.push(runs),
        capDialog: async () => {
          opened += 1;
          return 'later';
        },
      }),
      waitingEvery: 60_000,
    });

    expect(
      waited
        .at(-1)
        ?.map((run) => run.id)
        .sort(),
    ).toEqual(['apr_1', 'orc_9']);
    expect(opened).toBe(0);
  });

  it('asks a stuck phase to go on with y, through the answer /answer sends', async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [
      asking('orc_2', { parent: 'orc_1', depth: 1, pendingCap: 'stuck' }),
    ]);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    const dialogs: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => framesOf(fake, 'orchestration.answer').length > 0);
        await until(() =>
          prompt.said.some((line) => line.includes('orc_2 answered')),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async (lines, keys) => {
          dialogs.push([...lines, JSON.stringify(keys)]);
          return 'continue';
        },
      }),
      waitingEvery: 60_000,
    });

    expect(dialogs).toEqual([
      [
        'orc_2 (implement_specification) asks:',
        'Which database for orc_2?',
        'y go on · n stop · w watch · esc later',
        JSON.stringify(['continue', 'stop', 'watch', 'later']),
      ],
    ]);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_2', answer: 'go on' },
    ]);
    expect(prompt.said.join('\n')).toContain(
      'orc_2 answered — it is now running',
    );
  });

  it('accepts a product the person checked with y, through the answer /answer sends', async () => {
    // V77: the product check is the person's, and `y` accepts the product.
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1', { pendingCap: 'product_check' })]);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_1', state: 'running' },
    });
    const dialogs: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('orc_1 answered')),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async (lines, keys) => {
          dialogs.push([...lines, JSON.stringify(keys)]);
          return 'continue';
        },
      }),
      waitingEvery: 60_000,
    });

    expect(dialogs).toEqual([
      [
        'orc_1 (implement_specification) asks:',
        'Which database for orc_1?',
        'y accept · r reply · w watch · esc later',
        JSON.stringify(['continue', 'reply', 'watch', 'later']),
      ],
    ]);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', answer: 'accept' },
    ]);
  });

  it('puts /answer and the id in the composer on r for a verifier question, and sends nothing', async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1', { pendingCap: 'uncovered' })]);
    const filled: string[] = [];
    const prompt = stepping([
      async () => {
        await until(() => filled.length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => 'reply',
        prefill: (text) => filled.push(text),
      }),
      waitingEvery: 60_000,
    });

    expect(filled).toEqual(['/answer orc_1 ']);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([]);
  });

  it('stops a stuck phase with n, through the cancel /cancel sends', async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [
      asking('orc_2', { parent: 'orc_1', depth: 1, pendingCap: 'stuck' }),
    ]);
    fake.script('orchestration.cancel', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'cancelled' },
    });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('orc_2 cancelled')),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, capDialog: async () => 'stop' }),
      waitingEvery: 60_000,
    });

    expect(framesOf(fake, 'orchestration.cancel')).toEqual([{ id: 'orc_2' }]);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([]);
  });

  it('opens the viewer on w and puts the question up again after it', async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1')]);
    const keys: DialogKey[] = ['watch', 'later'];
    const views: (Viewed | undefined)[] = [];
    let opened = 0;
    const prompt = stepping([
      async () => {
        await until(() => opened === 2);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => {
          opened += 1;
          return keys.shift();
        },
        view: (viewed) => views.push(viewed),
        viewKey: async () => 'close',
      }),
      waitingEvery: 60_000,
    });

    expect(views.some((viewed) => viewed !== undefined)).toBe(true);
    expect(views.at(-1)).toBeUndefined();
    expect(opened).toBe(2);
  });

  it("leaves a root's question waiting on esc, where /answer still reaches it", async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1')]);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_1', state: 'running' },
    });
    let asked = 0;
    const prompt = stepping([
      async () => {
        await until(() => asked > 0);
        return '/answer PostgreSQL';
      },
      async () => {
        await until(() => framesOf(fake, 'orchestration.answer').length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: async () => {
          asked += 1;
          return 'later';
        },
      }),
      waitingEvery: 60_000,
    });

    expect(asked).toBe(1);
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', answer: 'PostgreSQL' },
    ]);
  });

  it('takes the dialog away when the bot answered first, saying who and what', async () => {
    const fake = await scriptedPlowshare([]);
    let by: string | undefined;
    scriptAsking(fake, [asking('orc_1')], () => by);
    let closed = 0;
    let open: ((key: DialogKey | undefined) => void) | undefined;
    const prompt = stepping([
      async () => {
        await until(() => open !== undefined);
        by = 'sophron';
        fake.push(
          JSON.stringify({
            kind: 'orchestration.changed',
            orchestration: 'orc_1',
            state: 'running',
          }),
        );
        await until(() =>
          prompt.said.some((line) => line.includes('question was answered by')),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        capDialog: (): Promise<DialogKey | undefined> =>
          new Promise((settle) => {
            open = settle;
          }),
        closeDialog: () => {
          closed += 1;
          open?.(undefined);
        },
      }),
      waitingEvery: 60_000,
    });

    expect(closed).toBe(1);
    expect(prompt.said.join('\n')).toContain(
      "orc_1's question was answered by sophron: PostgreSQL",
    );
    expect(framesOf(fake, 'orchestration.answer')).toEqual([]);
  });

  it("prints a root's question on a plain surface, and a lone r prints what to type", async () => {
    const fake = await scriptedPlowshare([]);
    scriptAsking(fake, [asking('orc_1')]);
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) =>
            line.includes('an empty line decides later'),
          ),
        );
        return 'r';
      },
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('to reply, type')),
        );
        return undefined;
      },
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });
    const said = prompt.said.join('\n');

    expect(said).toContain('orc_1 (implement_specification) asks:');
    expect(said).toContain(
      'sophron, which started it, has it too; the first answer settles it.',
    );
    expect(said).toContain('r reply · w watch · an empty line decides later');
    expect(said).toContain('to reply, type: /answer orc_1 <your answer>');
    // A lone letter answering the dialog is not a turn.
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });

  it('takes a lone y as accept for a verifier question on a plain surface', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    scriptAsking(fake, [asking('orc_1', { pendingCap: 'uncovered' })]);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_1', state: 'running' },
    });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('y accept · r reply')),
        );
        return 'y';
      },
      async () => {
        await until(() => framesOf(fake, 'orchestration.answer').length > 0);
        return undefined;
      },
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });
    const said = prompt.said.join('\n');

    expect(said).toContain(
      'y accept · r reply · w watch · an empty line decides later',
    );
    expect(said).not.toContain('has it too');
    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_1', answer: 'accept' },
    ]);
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });

  it('prints the reply command for a lone r to a verifier question on a plain surface', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    scriptAsking(fake, [asking('orc_1', { pendingCap: 'uncovered' })]);
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('y accept · r reply')),
        );
        return 'r';
      },
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('to reply, type')),
        );
        return undefined;
      },
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });

    expect(prompt.said.join('\n')).toContain(
      'to reply, type: /answer orc_1 <your answer>',
    );
    expect(framesOf(fake, 'orchestration.answer')).toEqual([]);
  });

  it('takes a lone y for a stuck phase on a plain surface', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    scriptAsking(fake, [
      asking('orc_2', { parent: 'orc_1', depth: 1, pendingCap: 'stuck' }),
    ]);
    fake.script('orchestration.answer', {
      code: 'OK',
      payload: { id: 'orc_2', state: 'running' },
    });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('y go on · n stop')),
        );
        return 'y';
      },
      async () => {
        await until(() => framesOf(fake, 'orchestration.answer').length > 0);
        return undefined;
      },
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });

    expect(framesOf(fake, 'orchestration.answer')).toEqual([
      { id: 'orc_2', answer: 'go on' },
    ]);
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });
});

describe("a command approval from one of the person's runs, in the dialog", () => {
  /** One asked `View`, as `ApprovalFrames.View.of` writes it, under a conductor no screen has open. */
  const view = (
    id: string,
    extra: Record<string, unknown> = {},
  ): Record<string, unknown> => ({
    id,
    conversation: 'cnv_conductor',
    askedIn: 'cnv_conductor',
    agent: 'code_implementation',
    side: 'local',
    command: ['pytest', '-q', 'tests'],
    cwd: '/repo',
    state: 'asked',
    defaultPrefix: ['pytest', '-q'],
    ...extra,
  });
  /** The account's asked approvals — none once `gone` says so — and nothing else waiting. */
  const scriptApprovals = (
    fake: Fake,
    rows: readonly Record<string, unknown>[],
    gone?: () => boolean,
  ): void => {
    fake.script('orchestration.list', {
      code: 'OK',
      payload: { orchestrations: [] },
    });
    fake.script(
      'orchestration.record',
      recordOf(() => []),
    );
    fake.script('approval.list', () => ({
      code: 'OK',
      payload: { approvals: gone?.() === true ? [] : rows },
    }));
    fake.script('approval.answer', (payload) => ({
      code: 'OK',
      payload: {
        id: (payload as { id: string }).id,
        state:
          (payload as { decision: string }).decision === 'deny'
            ? 'denied'
            : 'allowed',
        busy: false,
      },
    }));
  };
  const answers = (fake: Fake): unknown[] =>
    fake.heard.frames
      .filter((frame) => frame.type === 'approval.answer')
      .map((frame) => frame.payload);
  const text = (typed: string): Stroke => ({ kind: 'text', text: typed });

  for (const [key, decision] of [
    ['o', 'once'],
    ['c', 'conversation'],
    ['d', 'deny'],
  ] as const) {
    it(`opens a run's approval with the approval prompt's keys, and ${key} answers ${decision}`, async () => {
      const fake = await scriptedPlowshare([]);
      scriptApprovals(fake, [view('apr_1')]);
      const dialogs: (readonly string[])[] = [];
      const prompt = stepping([
        async () => {
          await until(() => answers(fake).length > 0);
          await until(() =>
            prompt.said.some(
              (line) =>
                line.includes('apr_1 ') && line.includes('the run carries on'),
            ),
          );
          return undefined;
        },
      ]);
      await converse({
        ...talkingTo(fake, {
          ...prompt,
          approvalDialog: async (lines) => {
            dialogs.push(lines);
            return text(key);
          },
        }),
        waitingEvery: 60_000,
      });

      expect(dialogs).toEqual([
        [
          'apr_1 (code_implementation) asks:',
          'a run asks to run a command on the local side:',
          '  pytest -q tests',
          '  in /repo',
          'o once · c for this conversation · p for this project · d deny · esc leave',
          'it for later',
        ],
      ]);
      expect(answers(fake)).toEqual([{ id: 'apr_1', decision }]);
    });
  }

  it('lets p pick the prefix as the prompt does, and sends it for the project', async () => {
    const fake = await scriptedPlowshare([]);
    scriptApprovals(fake, [view('apr_1')]);
    const strokes: Stroke[] = [text('p'), { kind: 'right' }, { kind: 'enter' }];
    const drawn: { lines: readonly string[]; again: boolean | undefined }[] =
      [];
    const prompt = stepping([
      async () => {
        await until(() => answers(fake).length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        approvalDialog: async (lines, again) => {
          drawn.push({ lines, again });
          return strokes.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    expect(drawn.map((each) => each.again)).toEqual([false, true, true]);
    expect(drawn[1]?.lines).toContain(
      'allow any command starting: pytest -q   (not: tests)',
    );
    expect(drawn[2]?.lines).toContain(
      'allow any command starting: pytest -q tests',
    );
    expect(answers(fake)).toEqual([
      { id: 'apr_1', decision: 'project', prefix: ['pytest', '-q', 'tests'] },
    ]);
  });

  it('leaves it waiting on esc, where /answer still reaches it', async () => {
    const fake = await scriptedPlowshare([]);
    scriptApprovals(fake, [view('apr_1')]);
    let asked = 0;
    const waited: (readonly Waiting[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => asked > 0);
        return '/answer apr_1 once';
      },
      async () => {
        await until(() => answers(fake).length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        waiting: (runs) => waited.push(runs),
        approvalDialog: async () => {
          asked += 1;
          return { kind: 'escape' };
        },
      }),
      waitingEvery: 60_000,
    });

    expect(asked).toBe(1);
    expect(waited.some((runs) => runs.some((run) => run.id === 'apr_1'))).toBe(
      true,
    );
    expect(answers(fake)).toEqual([{ id: 'apr_1', decision: 'once' }]);
  });

  it('takes one withdrawn meanwhile away, and says so', async () => {
    const fake = await scriptedPlowshare([]);
    let withdrawn = false;
    scriptApprovals(fake, [view('apr_1')], () => withdrawn);
    let closed = 0;
    let open: ((stroke: Stroke | undefined) => void) | undefined;
    const prompt = stepping([
      async () => {
        await until(() => open !== undefined);
        withdrawn = true;
        fake.push(
          JSON.stringify({
            kind: 'orchestration.changed',
            orchestration: 'orc_1',
            state: 'asking',
          }),
        );
        await until(() =>
          prompt.said.some((line) => line.includes('is no longer asked')),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        approvalDialog: (): Promise<Stroke | undefined> =>
          new Promise((settle) => {
            open = settle;
          }),
        closeDialog: () => {
          closed += 1;
          open?.(undefined);
        },
      }),
      waitingEvery: 60_000,
    });

    expect(closed).toBe(1);
    expect(prompt.said.join('\n')).toContain(
      'apr_1 is no longer asked — answered elsewhere, or withdrawn',
    );
    expect(answers(fake)).toEqual([]);
  });

  it("shows an acceptance set's commands, wrapped, and takes no p for it", async () => {
    const fake = await scriptedPlowshare([]);
    scriptApprovals(fake, [
      view('apr_set', {
        agent: 'implement_specification',
        command: [],
        defaultPrefix: [],
        judged: 'unsure',
        commands: [
          [
            'python',
            '-m',
            'pytest',
            '-q',
            'tests/test_main_loop.py::test_the_loop_ends_on_quit',
          ],
          ['python', '-m', 'rpg.main'],
        ],
      }),
    ]);
    const strokes: Stroke[] = [text('p'), text('o')];
    const drawn: (readonly string[])[] = [];
    const prompt = stepping([
      async () => {
        await until(() => answers(fake).length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, {
        ...prompt,
        columns: () => 44,
        approvalDialog: async (lines) => {
          drawn.push(lines);
          return strokes.shift();
        },
      }),
      waitingEvery: 60_000,
    });

    expect(drawn[0]).toEqual([
      'apr_set (implement_specification) asks:',
      'a run asks to run 2 acceptance commands',
      'on the local side, one answer for all of',
      'them:',
      '  python -m pytest -q',
      '  tests/test_main_loop.py::test_the_loop',
      '  _ends_on_quit',
      '  python -m rpg.main',
      '  in /repo',
      '  the command judge did not find them',
      '  clearly safe: unsure',
      'o once · c for this conversation · d',
      'deny · esc leave it for later',
    ]);
    expect(
      drawn[1],
      'p means nothing to a set, which is drawn again as it was',
    ).toEqual(drawn[0]);
    expect(answers(fake)).toEqual([{ id: 'apr_set', decision: 'once' }]);
    expect(prompt.said.join('\n')).toContain('allow all 2 once');
  });

  it('prints it on a plain surface with its keys, and a lone line answers it', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    scriptApprovals(fake, [view('apr_1')]);
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('p for this project')),
        );
        return 'p';
      },
      async () => {
        await until(() =>
          prompt.said.some((line) =>
            line.includes('allow any command starting'),
          ),
        );
        return '';
      },
      async () => {
        await until(() => answers(fake).length > 0);
        return undefined;
      },
    ]);
    await converse({ ...talkingTo(fake, prompt), waitingEvery: 60_000 });
    const said = prompt.said.join('\n');

    expect(said).toContain('apr_1 (code_implementation) asks:');
    expect(said).toContain('d deny · an empty');
    expect(said).toContain('line or esc leaves it for later');
    expect(answers(fake)).toEqual([
      { id: 'apr_1', decision: 'project', prefix: ['pytest', '-q'] },
    ]);
    // A lone letter answering the dialog is not a turn.
    expect(fake.heard.frames.some((frame) => frame.type === 'agent.run')).toBe(
      false,
    );
  });
});

describe("what the log gained with no stream of this client's showing it", () => {
  it("shows a delivered result live, as a harness line with the bot's reply after it", async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = stepping([
      async () => {
        delivered(
          going.log,
          5,
          'orc_4B1D000000000005',
          'Nothing was changed, and I said so.',
        );
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() =>
          prompt.said.some((line) => line.includes('Nothing was changed')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(chatReads(fake).at(-1)).toEqual({
      conversation: CONTINUING,
      after: 10,
      offset: 0,
      kinds: DRAWN,
      drawn: true,
    });
    expect(said).toContain('⚙ run orc_4B1D000000000005: The orchestration');
    expect(said.indexOf('⚙ run orc_4B1D000000000005')).toBeLessThan(
      said.indexOf('Nothing was changed'),
    );
    expect(said).not.toContain('turn 5, you said');
  });

  it('holds a push that lands during its own run until the run ends, and never re-shows what it streamed', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    speakingInto(fake, CONTINUING, going.log, () => {
      delivered(
        going.log,
        5,
        'orc_4B1D000000000005',
        'Nothing was changed, and I said so.',
      );
      fake.push(appendedTo(CONTINUING, going.log));
    });
    const prompt = scripted(['and now?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const ownEnding = prompt.said.findIndex((line) =>
      line.startsWith('this run answered'),
    );
    const harness = prompt.said.findIndex((line) =>
      line.includes('⚙ run orc_4B1D000000000005'),
    );
    expect(ownEnding).toBeGreaterThan(-1);
    expect(harness).toBeGreaterThan(ownEnding);
    const said = prompt.said.join('\n');
    expect(said).toContain('Nothing was changed, and I said so.');
    expect(said).not.toContain("the log's own answer to and now?");
  });

  it('shows turns 21 and 25, which the harness started, as they land — the measured case', async () => {
    const going: Going = {
      conversation: {
        id: 'cnv_31C0FFEE0000A021',
        project: null,
        maxModelCalls: null,
        modelCallsSpent: 40,
        maxTurns: 8,
        noTurnCap: false,
        noBudget: true,
        title: 'the build bot',
      },
      log: aLongConversation(20),
    };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    speakingInto(fake, 'cnv_31C0FFEE0000A021', going.log);
    const prompt = stepping([
      async () => {
        delivered(
          going.log,
          21,
          'orc_318408A44038F859',
          'Run 21 got stuck; I have told you why.',
        );
        fake.push(appendedTo('cnv_31C0FFEE0000A021', going.log));
        await until(() =>
          prompt.said.some((line) => line.includes('Run 21 got stuck')),
        );
        return 'twenty-two';
      },
      async () => 'twenty-three',
      async () => 'twenty-four',
      async () => {
        delivered(
          going.log,
          25,
          'orc_318408A44038F860',
          'Run 25 finished; the result is above.',
        );
        fake.push(appendedTo('cnv_31C0FFEE0000A021', going.log));
        await until(() =>
          prompt.said.some((line) => line.includes('Run 25 finished')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(said).toContain('⚙ run orc_318408A44038F859');
    expect(said).toContain('⚙ run orc_318408A44038F860');
    expect(said.indexOf('Run 21 got stuck')).toBeLessThan(
      said.indexOf('Run 25 finished'),
    );
    expect(said).not.toMatch(/turn 2[15], you said/);
    expect(said).not.toContain("the log's own answer");
  });

  /**
   * A push can say the log grew by nothing the chat draws — a harness turn still on its way,
   * one answer that asked for a tool and the tool's result. The read narrowed to what is drawn
   * comes back empty, draws nothing, and still counts the screen as shown through the push:
   * the next push reads after it, and the empty read is not taken for a reason to read again.
   */
  it('draws nothing for a push that added only tool traffic, and reads after it next time', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const reads = (): number =>
      fake.heard.frames.filter(
        (frame) => frame.type === 'conversation.trajectory',
      ).length;
    const prompt = stepping([
      async () => {
        going.log.push(logged(11, 5, 'answer', 'let me look', { asked: 1 }));
        going.log.push(logged(12, 5, 'tool_result', 'what the file held'));
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() => reads() === 2);
        // Long enough for a second read to have gone out, were one going to.
        await new Promise<void>((done) => {
          setTimeout(done, 40);
        });
        expect(reads()).toBe(2);
        going.log.push(
          logged(13, 5, 'answer', 'The file held what you thought.'),
        );
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() =>
          prompt.said.some((line) =>
            line.includes('The file held what you thought.'),
          ),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(chatReads(fake)).toEqual([
      tailOf(CONTINUING),
      {
        conversation: CONTINUING,
        after: 10,
        offset: 0,
        kinds: DRAWN,
        drawn: true,
      },
      {
        conversation: CONTINUING,
        after: 12,
        offset: 0,
        kinds: DRAWN,
        drawn: true,
      },
    ]);
    expect(said).not.toContain('let me look');
    expect(said).not.toContain('what the file held');
    expect(times(prompt.said, 'The file held what you thought.')).toBe(1);
  });

  it('draws a call pending as soon as it is asked, writes it once when its result is in, and before the answer', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted(['what is in here?']);
    runningLs(fake, going, prompt);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const traces = tracesIn(prompt.shown);
    expect(traces.filter((each) => each.includes('● run'))).toHaveLength(1);
    expect(traces.join('\n')).toMatch(/● run {8}ls +✓ 2 lines {3}53ms/u);
    const trace = prompt.shown.findIndex((entry) => entry.voice === 'trace');
    const answer = ownAnswerIn(prompt.shown);
    expect(trace).toBeGreaterThan(-1);
    expect(answer).toBeGreaterThan(trace);
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'conversation.trajectory')
        .map((frame) => frame.payload),
    ).toContainEqual({ conversation: CONTINUING, after: 10, offset: 0 });
  });

  it('reads the log once for a burst of pushes during its run, not once for each', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted(['what is in here?']);
    runningLs(fake, going, prompt, { burst: 30 });
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const traces = tracesIn(prompt.shown);
    expect(traces.filter((each) => each.includes('● run'))).toHaveLength(1);
    // The burst's thirty pushes, the result's and the answer's: a read for the first, one more
    // for whatever came while it was out, and one for each of the two after — not thirty-two.
    const tracer = fake.heard.frames.filter(
      (frame) =>
        frame.type === 'conversation.trajectory' && tracerRead(frame.payload),
    );
    expect(tracer.length).toBeLessThanOrEqual(6);
  });

  /**
   * Pushes are not durable. A call drawn pending whose result and answer land with no push
   * after them is read once more at the turn's end — it was on screen, so it is written, once,
   * above the answer, and never simply goes away.
   */
  it('writes a call it drew pending when the pushes for its result and the answer were lost', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted(['what is in here?']);
    runningLs(fake, going, prompt, { pushed: false });
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const traces = tracesIn(prompt.shown);
    expect(traces.filter((each) => each.includes('● run'))).toHaveLength(1);
    expect(traces.join('\n')).toMatch(/● run {8}ls +✓ 2 lines {3}53ms/u);
    const trace = prompt.shown.findIndex((entry) => entry.voice === 'trace');
    expect(trace).toBeGreaterThan(-1);
    expect(ownAnswerIn(prompt.shown)).toBeGreaterThan(trace);
    // Nothing still running once the turn's lines are written: the last state with calls in it
    // is followed by one without them.
    const withCalls = prompt.states.map(
      (state) => (state?.calls?.length ?? 0) > 0,
    );
    const lastWithCalls = withCalls.lastIndexOf(true);
    expect(lastWithCalls).toBeGreaterThan(-1);
    expect(
      prompt.states
        .slice(lastWithCalls + 1)
        .some((state) => state !== undefined && state.calls === undefined),
    ).toBe(true);
  });

  it("puts what the turn's model calls and tools took under its answer", async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted(['what is in here?']);
    runningLs(fake, going, prompt);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    const note = prompt.shown[ownAnswerIn(prompt.shown)]?.note ?? '';
    expect(note).toMatch(
      /^this run answered — .* · model \d+ms · 1 tool 53ms$/u,
    );
    // `describeCost` counts the model calls; the times after it do not count them again.
    expect(note.match(/model calls?/gu) ?? []).toHaveLength(1);
  });

  it('draws tool lines at the density ctrl-t chose, and says which it is', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted(['what is in here?']);
    let press: () => void = () => undefined;
    runningLs(fake, going, prompt, { before: () => press() });
    await converse(
      withNoAgentNamed(
        talkingTo(fake, {
          ...prompt,
          density: (listener: () => void) => {
            press = listener;
          },
        }),
      ),
    );

    expect(prompt.said).toContain(describeDensity('full'));
    const said = prompt.said.indexOf(describeDensity('full'));
    const trace = prompt.shown.findIndex((entry) => entry.voice === 'trace');
    expect(trace).toBeGreaterThan(-1);
    // Full: the output under the call, which compact leaves out for a call that did not fail.
    expect(tracesIn(prompt.shown).join('\n')).toMatch(/│ build\n {2}│ docs/u);
    expect(said).toBeGreaterThan(-1);
  });

  /**
   * `Delivery` waits for a busy conversation to drain, so a run's ending is spoken into it the
   * moment the person's own turn frees it — its push can land before this client has even
   * read how its own run ended. The turn the stream drew is the one the log held when its
   * `ended` landed; the harness turn after it is not taken for it.
   *
   * <p><b>All of it outruns the answer that names the run</b>, which the interleaving contract
   * allows: the `ended` is held, unattributed, and the harness push is read after it and
   * before the handle — so how far the log reached must be taken when `ended` arrives, not
   * when it is found to be this turn's.
   */
  it('draws a harness turn that lands just after its own run ended, and not its own turn again', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    fake.script('agent.run', (payload) => {
      const task = displayText(
        (payload as { task?: unknown } | undefined)?.task ?? '',
      );
      going.log.push(logged(11, 5, 'utterance', task));
      going.log.push(
        logged(12, 5, 'answer', `the log's own answer to ${task}`),
      );
      fake.push(appendedTo(CONTINUING, going.log));
      fake.push(event('job-own', 'started'));
      fake.push(
        event('job-own', 'ended', {
          ending: 'ANSWERED',
          steps: 1,
          modelCalls: 1,
        }),
      );
      delivered(
        going.log,
        6,
        'orc_4B1D000000000006',
        'Delivered the moment you were done.',
      );
      fake.push(appendedTo(CONTINUING, going.log));
      return { code: 'ACCEPTED', payload: { id: 'job-own' } };
    });
    const prompt = scripted(['and now?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    const ownEnding = prompt.said.findIndex((line) =>
      line.startsWith('this run answered'),
    );
    const harness = prompt.said.findIndex((line) =>
      line.includes('⚙ run orc_4B1D000000000006'),
    );
    expect(ownEnding).toBeGreaterThan(-1);
    expect(harness).toBeGreaterThan(ownEnding);
    expect(said).toContain('Delivered the moment you were done.');
    expect(said).not.toContain('turn 5, you said');
    expect(said).not.toContain("the log's own answer to and now?");
  });

  /**
   * Pushes are not durable, and the one for the person's own turn can be lost. The turn's end
   * then reads how far the log reaches and counts it as shown (spec §4, "from the turn's end"),
   * so the next push draws what came after it — never the turn the stream already drew.
   */
  it('draws only the harness turn on the next push when the push for its own turn was lost', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    fake.script('agent.run', (payload) => {
      const task = displayText(
        (payload as { task?: unknown } | undefined)?.task ?? '',
      );
      going.log.push(logged(11, 5, 'utterance', task));
      going.log.push(
        logged(12, 5, 'answer', `the log's own answer to ${task}`),
      );
      // No `conversation.appended` for this turn: its push was lost.
      setImmediate(() => {
        fake.push(event('job-own', 'started'));
        fake.push(
          event('job-own', 'ended', {
            ending: 'ANSWERED',
            steps: 1,
            modelCalls: 1,
          }),
        );
      });
      return { code: 'ACCEPTED', payload: { id: 'job-own' } };
    });
    const prompt = stepping([
      async () => 'and now?',
      async () => {
        delivered(
          going.log,
          6,
          'orc_4B1D000000000006',
          'Delivered after the push was lost.',
        );
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() =>
          prompt.said.some((line) =>
            line.includes('Delivered after the push was lost'),
          ),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(chatReads(fake)).toEqual([
      tailOf(CONTINUING),
      {
        conversation: CONTINUING,
        after: 10,
        offset: 0,
        limit: 1,
        kinds: DRAWN,
        drawn: true,
      },
      {
        conversation: CONTINUING,
        after: 12,
        offset: 0,
        kinds: DRAWN,
        drawn: true,
      },
    ]);
    expect(said.split('⚙ run orc_4B1D000000000006').length - 1).toBe(1);
    expect(said).not.toContain('turn 5, you said');
    expect(said).not.toContain("the log's own answer to and now?");
  });

  /**
   * A fill that began before the person's turn and whose read comes back after that turn has
   * settled would draw the turn the stream already drew. It is a different turn's screen by
   * then, so the fill draws nothing, and the settling's own catch-up fills it instead.
   */
  it('draws nothing from a fill that began before its own turn and answered after it', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    let reads = 0;
    let release: () => void = () => undefined;
    fake.script('conversation.trajectory', (asked) => {
      // The tracer's reads during the person's turn are not the chat's, and are not counted.
      if (tracerRead(asked)) {
        return paged(going.log, asked);
      }
      reads += 1;
      if (reads === 1) {
        return {
          code: 'INTERNAL_ERROR',
          said: 'the log could not be read just now',
        };
      }
      if (reads === 2) {
        // Held open across the whole of the person's turn, then read as the log is then.
        return new Promise<Reply>((done) => {
          release = () => {
            done(paged(going.log, asked));
          };
        });
      }
      return paged(going.log, asked);
    });
    speakingInto(fake, CONTINUING, going.log);
    const prompt = stepping([
      async () => {
        delivered(
          going.log,
          5,
          'orc_4B1D000000000005',
          'Nothing was changed, and I said so.',
        );
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() => reads === 2);
        // The turn's own ending, not the progress line that says it answered: the settling.
        background(
          until(() =>
            prompt.said.some((line) => line.startsWith('this run answered —')),
          ).then(() => {
            release();
          }),
        );
        return 'and now?';
      },
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('Nothing was changed')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(reads).toBe(3);
    expect(said.split('⚙ run orc_4B1D000000000005').length - 1).toBe(1);
    expect(times(prompt.said, 'what is in this repo')).toBe(1);
    expect(said).not.toContain('turn 6, you said');
    expect(said).not.toContain("the log's own answer to and now?");
  });

  /**
   * A catch-up queued behind one still reading, and reached only once the person's own turn is
   * streaming, would read how far the log reaches with that turn in it and draw it after the
   * turn settles. It draws nothing; the settling's catch-up draws what the pushes said.
   */
  it('draws nothing from a catch-up that was queued before its own turn and reached during it', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    let pushed: () => void = () => undefined;
    const ownPushed = new Promise<void>((done) => {
      pushed = done;
    });
    let answeredNow: () => void = () => undefined;
    const answered = new Promise<void>((done) => {
      answeredNow = done;
    });
    let reads = 0;
    fake.script('conversation.trajectory', (asked) => {
      // The tracer's reads during the person's turn are not the chat's, and are not counted.
      if (tracerRead(asked)) {
        return paged(going.log, asked);
      }
      reads += 1;
      if (reads === 2) {
        // While the first catch-up reads, a second push queues another behind it.
        delivered(going.log, 6, 'orc_4B1D000000000006', 'Second delivered.');
        fake.push(appendedTo(CONTINUING, going.log));
      }
      // The first catch-up answers once the turn's own push is out; the next once it has ended.
      const held =
        reads === 2 ? ownPushed : reads === 3 ? answered : Promise.resolve();
      return held.then(() => paged(going.log, asked));
    });
    fake.script('agent.run', (payload) => {
      const task = displayText(
        (payload as { task?: unknown } | undefined)?.task ?? '',
      );
      going.log.push(logged(15, 7, 'utterance', task));
      going.log.push(
        logged(16, 7, 'answer', `the log's own answer to ${task}`),
      );
      setImmediate(() => {
        fake.push(appendedTo(CONTINUING, going.log));
        pushed();
        setTimeout(() => {
          fake.push(event('job-own', 'started'));
          fake.push(
            event('job-own', 'ended', {
              ending: 'ANSWERED',
              steps: 1,
              modelCalls: 1,
            }),
          );
        }, 20);
      });
      return { code: 'ACCEPTED', payload: { id: 'job-own' } };
    });
    const prompt = stepping([
      async () => {
        delivered(going.log, 5, 'orc_4B1D000000000005', 'First delivered.');
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() => reads === 2);
        // Long enough for the second push to cross the socket and queue its catch-up
        // before the person speaks: nothing on the wire says it has.
        await new Promise<void>((done) => {
          setTimeout(done, 25);
        });
        // The turn's own ending, not the progress line that says it answered: the settling.
        background(
          until(() =>
            prompt.said.some((line) => line.startsWith('this run answered —')),
          ).then(() => {
            answeredNow();
          }),
        );
        return 'and now?';
      },
      async () => undefined,
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(said.split('⚙ run orc_4B1D000000000005').length - 1).toBe(1);
    expect(said.split('⚙ run orc_4B1D000000000006').length - 1).toBe(1);
    expect(said).not.toContain('turn 7, you said');
    expect(said).not.toContain("the log's own answer to and now?");
  });

  /**
   * A push that lands while the replay is being read is held — the replay covers it — and if
   * that replay is then refused, nothing will push for it again. So the refusal fills the
   * screen once more at once, rather than leaving what the push said unshown.
   */
  it('fills the screen at once when a push landed during a replay that was then refused', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    let reads = 0;
    fake.script('conversation.trajectory', (asked) => {
      reads += 1;
      if (reads === 1) {
        delivered(
          going.log,
          5,
          'orc_4B1D000000000005',
          'Nothing was changed, and I said so.',
        );
        fake.push(appendedTo(CONTINUING, going.log));
        return {
          code: 'INTERNAL_ERROR',
          said: 'the log could not be read just now',
        };
      }
      return paged(going.log, asked);
    });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('Nothing was changed')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(reads).toBe(2);
    expect(said.split('⚙ run orc_4B1D000000000005').length - 1).toBe(1);
    expect(times(prompt.said, 'what is in this repo')).toBe(1);
  });

  /**
   * A replay the server refused leaves nothing of the log on the screen. Holding every push
   * after that as though the screen were still filling would be the silence this whole
   * change exists to end, so the next push fills it — the bounded fill, from the top.
   */
  it('fills the screen on the next push when the replay read was refused', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    let reads = 0;
    fake.script('conversation.trajectory', (asked) => {
      reads += 1;
      return reads === 1
        ? { code: 'INTERNAL_ERROR', said: 'the log could not be read just now' }
        : paged(going.log, asked);
    });
    const prompt = stepping([
      async () => {
        delivered(
          going.log,
          5,
          'orc_4B1D000000000005',
          'Nothing was changed, and I said so.',
        );
        fake.push(appendedTo(CONTINUING, going.log));
        await until(() =>
          prompt.said.some((line) => line.includes('Nothing was changed')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(chatReads(fake)).toEqual([tailOf(CONTINUING), tailOf(CONTINUING)]);
    expect(said).toContain('the log could not be read just now');
    expect(said.indexOf('the log could not be read just now')).toBeLessThan(
      said.indexOf('what is in this repo'),
    );
    expect(said.split('⚙ run orc_4B1D000000000005').length - 1).toBe(1);
    expect(said.indexOf('⚙ run orc_4B1D000000000005')).toBeLessThan(
      said.indexOf('Nothing was changed'),
    );
    expect(said).not.toContain('turn 5, you said');
  });

  it('fills the screen when its own turn ends if the replay read was refused, leaving out the turn it streamed', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    let reads = 0;
    fake.script('conversation.trajectory', (asked) => {
      reads += 1;
      return reads === 1
        ? { code: 'INTERNAL_ERROR', said: 'the log could not be read just now' }
        : paged(going.log, asked);
    });
    speakingInto(fake, CONTINUING, going.log);
    const prompt = scripted(['and now?']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    const ownEnding = prompt.said.findIndex((line) =>
      line.startsWith('this run answered'),
    );
    expect(ownEnding).toBeGreaterThan(-1);
    expect(prompt.said.indexOf('what is in this repo')).toBeGreaterThan(
      ownEnding,
    );
    expect(said.split('⚙ run orc_318408A44038F859').length - 1).toBe(1);
    // The turn it streamed is on the screen once, as it streamed.
    expect(said).not.toContain('turn 5, you said');
    expect(said).not.toContain("the log's own answer to and now?");
  });

  /**
   * A push can land while the replay is being read — after the server read how far the log
   * reaches and before the answer arrives — while the screen is still filling and holds it.
   * Nothing will push again for it, so the moment the fill says how far it reached, what the
   * push said lies beyond that is caught up at once.
   */
  it('catches up at once on a push that landed while a long log filled the screen', async () => {
    const id = 'cnv_31C0FFEE0000A061';
    const going: Going = {
      conversation: {
        id,
        project: null,
        maxModelCalls: null,
        modelCallsSpent: 120,
        maxTurns: 8,
        noTurnCap: false,
        noBudget: true,
        title: 'the build bot',
      },
      log: aLongConversation(60),
    };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    let reads = 0;
    fake.script('conversation.trajectory', (asked) => {
      reads += 1;
      // The page is read as the log was; the push goes out before the page's answer.
      const page = paged(going.log, asked);
      if (reads === 1) {
        delivered(
          going.log,
          61,
          'orc_318408A44038F861',
          'Run 61 finished while the screen filled.',
        );
        fake.push(appendedTo(id, going.log));
      }
      return page;
    });
    const prompt = stepping([
      async () => {
        await until(() =>
          prompt.said.some((line) => line.includes('Run 61 finished')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said.join('\n');

    expect(chatReads(fake)).toEqual([
      tailOf(id),
      { conversation: id, after: 120, offset: 0, kinds: DRAWN, drawn: true },
    ]);
    expect(said.split('⚙ run orc_318408A44038F861').length - 1).toBe(1);
    expect(times(prompt.said, 'reply 60')).toBe(1);
    expect(said).not.toContain('turn 61, you said');
  });

  /**
   * `/diagnose` streams in Daedalus's conversation, not the one on screen. What reached the one
   * on screen meanwhile was streamed by nobody, so none of it is taken for the turn that ended.
   */
  it('shows what reached the conversation on screen while /diagnose streamed in another', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going, {
      latestByAgent: { aristoxenus: STILL_GOING.conversation },
    });
    fake.script('agent.run', () => {
      delivered(
        going.log,
        5,
        'orc_4B1D000000000005',
        'Delivered while Daedalus looked.',
      );
      setImmediate(() => {
        fake.push(appendedTo(CONTINUING, going.log));
        fake.push(event('job-diagnosis', 'started'));
        fake.push(
          event('job-diagnosis', 'ended', {
            ending: 'ANSWERED',
            steps: 1,
            modelCalls: 1,
          }),
        );
      });
      return { code: 'ACCEPTED', payload: { id: 'job-diagnosis' } };
    });
    const prompt = scripted(['/diagnose']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    expect(prompt.said.join('\n')).toContain(
      'Daedalus is diagnosing cnv_9F1A2B3C4D5E6F70',
    );
    const ownEnding = prompt.said.findIndex((line) =>
      line.startsWith('this run answered'),
    );
    const harness = prompt.said.findIndex((line) =>
      line.includes('⚙ run orc_4B1D000000000005'),
    );
    expect(ownEnding).toBeGreaterThan(-1);
    expect(harness).toBeGreaterThan(ownEnding);
    expect(prompt.said.join('\n')).toContain(
      'Delivered while Daedalus looked.',
    );
  });

  /**
   * A long log is replayed from ONE read back from its end: the last forty entries the chat
   * draws — never a tool result, never an answer that asked for one, and never page 0 read to
   * learn a total — turned into the order the conversation happened, the fold where it fell,
   * and a line offering what is earlier.
   */
  it('replays the last forty drawn entries of a long log from one read, seam and all', async () => {
    const id = 'cnv_31C0FFEE0000A025';
    const log = aLogOfPages(25);
    const going: Going = {
      conversation: {
        id,
        project: null,
        maxModelCalls: null,
        modelCallsSpent: 90,
        maxTurns: 8,
        noTurnCap: false,
        noBudget: true,
        title: 'the reading bot',
      },
      log,
    };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted([]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said;

    expect(log).toHaveLength(127);
    expect(chatReads(fake)).toEqual([tailOf(id)]);
    // Forty drawn entries back from the end: the seam, turns 7 to 25, and turn 6's reply.
    expect(said).toContain('25 turns in this log; the last 19 are below');
    expect(said.indexOf('— earlier entries: /earlier —')).toBeGreaterThan(-1);
    expect(said.indexOf('— earlier entries: /earlier —')).toBeLessThan(
      said.indexOf('reply 6'),
    );
    for (let turn = 1; turn <= 6; turn += 1) {
      expect(times(said, `turn ${turn}, you said:`)).toBe(0);
      expect(times(said, `question ${turn}`)).toBe(0);
    }
    for (let turn = 7; turn <= 25; turn += 1) {
      expect(times(said, `turn ${turn}, you said:`)).toBe(1);
      expect(times(said, `question ${turn}`)).toBe(1);
      expect(times(said, `reply ${turn}`)).toBe(1);
    }
    expect(said.join('\n')).not.toContain('let me look');
    expect(said.join('\n')).not.toContain('what the file held');
    const seam = said.findIndex((line) => line.startsWith('folded at turn 15'));
    expect(
      said.filter((line) => line.startsWith('folded at turn 15')),
    ).toHaveLength(1);
    expect(seam).toBeGreaterThan(said.indexOf('reply 15'));
    expect(seam).toBeLessThan(said.indexOf('question 16'));
  });

  /**
   * The tool calls between a question and its reply take no place in the tail: a conversation
   * whose every turn read files opens on twenty whole turns, as one that never did would.
   */
  it('opens on twenty whole turns though every turn asked for tools in between', async () => {
    const id = 'cnv_31C0FFEE0000A020';
    const log = aLogOfPages(25, false);
    const going: Going = {
      conversation: {
        id,
        project: null,
        maxModelCalls: null,
        modelCallsSpent: 90,
        maxTurns: 8,
        noTurnCap: false,
        noBudget: true,
        title: 'the reading bot',
      },
      log,
    };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted([]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said;

    // Three rows in four are tool traffic, and none of them reached the client.
    expect(log.filter((row) => row['kind'] === 'tool_result')).toHaveLength(38);
    expect(chatReads(fake)).toEqual([tailOf(id)]);
    expect(said).toContain('25 turns in this log; the last 20 are below');
    for (let turn = 6; turn <= 25; turn += 1) {
      expect(times(said, `turn ${turn}, you said:`)).toBe(1);
      expect(times(said, `question ${turn}`)).toBe(1);
      expect(times(said, `reply ${turn}`)).toBe(1);
    }
    expect(times(said, 'reply 5')).toBe(0);
    expect(said.join('\n')).not.toContain('let me look');
    expect(said.indexOf('— earlier entries: /earlier —')).toBeLessThan(
      said.indexOf('question 6'),
    );
  });

  /**
   * `/earlier` reads the forty drawn entries before the oldest on the screen and prints them
   * as a block labelled as earlier history, oldest first; once nothing is left it says so, and
   * asks nothing more. The bottom of the screen is untouched: a push after it still draws only
   * what the log gained.
   */
  it('loads the previous forty with /earlier, then the rest, then says it is the beginning', async () => {
    const id = 'cnv_31C0FFEE0000A045';
    const log = aLogOfPages(45);
    const going: Going = {
      conversation: {
        id,
        project: null,
        maxModelCalls: null,
        modelCallsSpent: 160,
        maxTurns: 8,
        noTurnCap: false,
        noBudget: true,
        title: 'the reading bot',
      },
      log,
    };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = stepping([
      async () => '/earlier',
      async () => '/earlier',
      async () => '/earlier',
      async () => {
        delivered(
          log,
          46,
          'orc_318408A44038F846',
          'Run 46 finished after all that.',
        );
        fake.push(appendedTo(id, log));
        await until(() =>
          prompt.said.some((line) => line.includes('Run 46 finished')),
        );
        return undefined;
      },
    ]);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));
    const said = prompt.said;

    // 227 rows before the run's ending was delivered, and two for it.
    expect(log).toHaveLength(229);
    expect(chatReads(fake)).toEqual([
      tailOf(id),
      { conversation: id, before: 128, limit: 40, kinds: DRAWN, drawn: true },
      { conversation: id, before: 30, limit: 40, kinds: DRAWN, drawn: true },
      { conversation: id, after: 227, offset: 0, kinds: DRAWN, drawn: true },
    ]);
    // Every turn once, over the three reads, and nothing the chat hides.
    for (let turn = 1; turn <= 45; turn += 1) {
      expect(times(said, `turn ${turn}, you said:`)).toBe(1);
      expect(times(said, `question ${turn}`)).toBe(1);
      expect(times(said, `reply ${turn}`)).toBe(1);
    }
    expect(said.join('\n')).not.toContain('let me look');
    expect(
      said.filter((line) => line.startsWith('folded at turn 15')),
    ).toHaveLength(1);
    // Each block introduced as earlier, printed below what was there, oldest first within it.
    const forty = said.indexOf('— 40 earlier entries —');
    const eleven = said.indexOf('— 11 earlier entries —');
    expect(forty).toBeGreaterThan(said.indexOf('reply 45'));
    expect(said.indexOf('reply 6')).toBeGreaterThan(forty);
    expect(said.indexOf('reply 6')).toBeLessThan(said.indexOf('question 25'));
    expect(eleven).toBeGreaterThan(said.indexOf('reply 25'));
    expect(said.indexOf('question 1')).toBeGreaterThan(eleven);
    expect(said.indexOf('question 1')).toBeLessThan(said.indexOf('question 6'));
    // The last block reaches the start and says so; the third /earlier asks nothing.
    expect(times(said, '— that is the beginning of this conversation —')).toBe(
      2,
    );
    expect(
      said.indexOf('— that is the beginning of this conversation —'),
    ).toBeGreaterThan(said.indexOf('question 6'));
    // The catch-up after it reads from the bottom of the screen, as though no /earlier had run.
    expect(said.join('\n').split('⚙ run orc_318408A44038F846').length - 1).toBe(
      1,
    );
    expect(said.indexOf('Run 46 finished after all that.')).toBeGreaterThan(
      eleven,
    );
  });

  it('says a short conversation is all on the screen, and a fresh screen has nothing to go back through', async () => {
    const going: Going = { ...STILL_GOING, log: [...STILL_GOING.log] };
    const fake = await scriptedPlowshare(['answer-first'], EVERY_AGENT, going);
    const prompt = scripted(['/earlier']);
    await converse(withNoAgentNamed(talkingTo(fake, prompt)));

    expect(prompt.said).not.toContain('— earlier entries: /earlier —');
    expect(prompt.said).toContain(
      '— that is the beginning of this conversation —',
    );
    expect(chatReads(fake)).toEqual([tailOf(CONTINUING)]);

    const none = await scriptedPlowshare(['answer-first']);
    const fresh = scripted(['/earlier']);
    await converse(withNoAgentNamed(talkingTo(none, fresh)));
    expect(fresh.said).toContain(
      'there is no conversation on the screen to go back through',
    );
    expect(none.heard.frames.map((frame) => frame.type)).not.toContain(
      'conversation.trajectory',
    );
  });
});

/** One `RecordView`, as `RecordView.of` writes it. */
const recordRow = (
  ordinal: number,
  kind: string,
  text: string,
  detail?: string,
): Record<string, unknown> => ({
  ordinal,
  at: '2026-09-28T09:00:00Z',
  run: 'orc_1',
  actor: 'conductor',
  kind,
  text,
  ...(detail === undefined ? {} : { detail }),
});

/** `orchestration.record` over the rows a case holds, paged the way `RecordStore` pages them. */
function recordOf(
  rows: () => readonly Record<string, unknown>[],
): (payload: unknown) => Reply {
  return (payload) => {
    const asked = payload as {
      after?: number;
      before?: number;
      tail?: boolean;
      limit?: number;
      kinds?: string[];
    };
    const all = rows().filter(
      (each) =>
        asked.kinds === undefined || asked.kinds.includes(String(each['kind'])),
    );
    const through = rows().reduce(
      (most, each) => Math.max(most, Number(each['ordinal'])),
      0,
    );
    const backwards = asked.tail === true || asked.before !== undefined;
    const below =
      asked.tail === true ? Number.MAX_SAFE_INTEGER : (asked.before ?? 0);
    const picked = backwards
      ? all.filter((each) => Number(each['ordinal']) < below).reverse()
      : all.filter((each) => Number(each['ordinal']) > (asked.after ?? 0));
    const limit = asked.limit ?? 100;
    const shown = picked.slice(0, limit);
    return {
      code: 'OK',
      payload: {
        root: 'orc_1',
        rows: shown,
        total: picked.length,
        limit,
        through,
        oldest:
          shown.length === 0
            ? null
            : Math.min(...shown.map((each) => Number(each['ordinal']))),
        more: backwards ? picked.length > shown.length : null,
      },
    };
  };
}

/** A live root run, as `OrchestrationFrames.viewOf` writes it. */
const liveRun = (state = 'running'): Record<string, unknown> => ({
  id: 'orc_1',
  definition: 'implement_specification',
  tier: 'project',
  project: 'plowshare',
  state,
  depth: 0,
  createdAt: '2026-09-28T09:00:00Z',
});

/** `orchestration.status` for that run, three stages along. */
const liveStatus = (state = 'running'): Reply => ({
  code: 'OK',
  payload: {
    orchestration: liveRun(state),
    todos: [
      { id: 't1', text: 'goal', status: 'done', stage: 'goal' },
      { id: 't2', text: 'code', status: 'in_progress', stage: 'code' },
      { id: 't3', text: 'review', status: 'pending', stage: 'review' },
    ],
    messages: [],
    children: [],
  },
});

describe('the runs panel, over a real socket', () => {
  it('appears with a live run, updates on a push, and goes when none is live', async () => {
    const fake = await scriptedPlowshare([]);
    let runs = [liveRun()];
    let rows = [
      recordRow(1, 'run_started', 'implement_specification started: build it'),
    ];
    fake.script('orchestration.list', () => ({
      code: 'OK',
      payload: { orchestrations: runs },
    }));
    fake.script('orchestration.status', () =>
      liveStatus(String(runs[0]?.['state'])),
    );
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    const panels: (Panel | undefined)[] = [];
    const said = (text: string): boolean =>
      panels.some(
        (panel) =>
          panel?.lines.some((line) => plainOf(line).includes(text)) === true,
      );
    const prompt = stepping([
      async () => {
        await until(() => said('build it'));
        rows = [
          ...rows,
          recordRow(2, 'tool_call', 'coder · run ./gradlew test', 'exit 1'),
        ];
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_1',
            through: 2,
          }),
        );
        await until(() =>
          said('now: conductor  ● coder · run ./gradlew test  ✗ exit 1'),
        );
        runs = [{ ...liveRun('finished'), endedAt: '2026-09-28T10:00:00Z' }];
        fake.push(
          JSON.stringify({
            kind: 'orchestration.changed',
            orchestration: 'orc_1',
            state: 'finished',
          }),
        );
        await until(() => panels.length > 0 && panels.at(-1) === undefined);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, panel: (panel) => panels.push(panel) }),
      waitingEvery: 60_000,
    });

    expect(
      panels.find((panel) => panel !== undefined)?.lines.map(plainOf),
    ).toContain('  goal ✓ code ● review ○');
    expect(panels.at(-1)).toBeUndefined();
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.record')
        .map((frame) => frame.payload),
    ).toContainEqual({
      root: 'orc_1',
      tail: true,
      limit: 1,
      kinds: ['tool_call'],
    });
  });

  it('shows a live root that twenty newer runs have pushed out of an ordinary listing', async () => {
    const fake = await scriptedPlowshare([]);
    // Newest first, as `OrchestrationStore.byCaller` answers, filtered by state and cut at the limit.
    const every: Record<string, unknown>[] = [
      ...Array.from({ length: 25 }, (_, at) => ({
        ...liveRun('finished'),
        id: `orc_${100 - at}`,
        createdAt: `2026-09-28T11:${String(59 - at).padStart(2, '0')}:00Z`,
      })),
      liveRun('asking'),
    ];
    fake.script('orchestration.list', (payload) => {
      const asked = payload as { state?: string; limit?: number };
      return {
        code: 'OK',
        payload: {
          orchestrations: every
            .filter(
              (run) =>
                asked.state === undefined || run['state'] === asked.state,
            )
            .slice(0, asked.limit ?? 50),
        },
      };
    });
    fake.script('orchestration.status', liveStatus('asking'));
    fake.script(
      'orchestration.record',
      recordOf(() => [recordRow(1, 'run_started', 'started: build it')]),
    );
    const panels: (Panel | undefined)[] = [];
    const prompt = stepping([
      async () => {
        await until(() => panels.length > 0);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, panel: (panel) => panels.push(panel) }),
      waitingEvery: 60_000,
    });

    expect(panels[0]?.settled[0]).toBe(
      'orc_1  implement_specification  asking',
    );
  });

  it('reads only its own tree on a line recorded, its status only for a milestone, and lists only for a run newly live', async () => {
    const fake = await scriptedPlowshare([]);
    // The waiting check asks for asking runs. Returning a running run there
    // causes an unrelated status read to race this panel's request counts.
    fake.script('orchestration.list', (payload) => {
      const { state } = payload as { state?: string };
      return {
        code: 'OK',
        payload: {
          orchestrations:
            state === undefined || state === 'running' ? [liveRun()] : [],
        },
      };
    });
    fake.script('orchestration.status', liveStatus());
    let rows = [recordRow(1, 'run_started', 'started: build it')];
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    const panels: (Panel | undefined)[] = [];
    const said = (text: string): boolean =>
      panels.some(
        (panel) =>
          panel?.lines.some((line) => plainOf(line).includes(text)) === true,
      );
    /** The panel's listings: the waiting check lists too, twenty at a time. */
    const listings = (): number =>
      fake.heard.frames.filter(
        (frame) =>
          frame.type === 'orchestration.list' &&
          (frame.payload as { limit?: number }).limit === 200,
      ).length;
    const statuses = (): number =>
      fake.heard.frames.filter((frame) => frame.type === 'orchestration.status')
        .length;
    let after: { listings: number; statuses: number } | undefined;
    let marked: number | undefined;
    const prompt = stepping([
      async () => {
        await until(() => said('build it'));
        const before = { listings: listings(), statuses: statuses() };
        rows = [
          ...rows,
          recordRow(2, 'tool_call', 'coder · run ./gradlew test', 'exit 1'),
        ];
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_1',
            through: 2,
          }),
        );
        // A tree it does not hold, and a run in no tree that has ended: nothing to read.
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_7',
            through: 9,
          }),
        );
        fake.push(
          JSON.stringify({
            kind: 'orchestration.changed',
            orchestration: 'orc_8',
            state: 'finished',
          }),
        );
        await until(() =>
          said('now: conductor  ● coder · run ./gradlew test  ✗ exit 1'),
        );
        await new Promise((settle) => setTimeout(settle, 30));
        after = {
          listings: listings() - before.listings,
          statuses: statuses() - before.statuses,
        };
        // A milestone is what can move a stage: that line, and only that one, reads the status.
        rows = [
          ...rows,
          recordRow(3, 'stage_moved', 'code: in_progress → done'),
        ];
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_1',
            through: 3,
          }),
        );
        await until(() => said('code: in_progress → done'));
        await new Promise((settle) => setTimeout(settle, 30));
        marked = statuses() - before.statuses;
        // A run in no tree that has gone live may be a new root: that is a listing.
        fake.push(
          JSON.stringify({
            kind: 'orchestration.changed',
            orchestration: 'orc_9',
            state: 'running',
          }),
        );
        await until(() => listings() === before.listings + 3);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, panel: (panel) => panels.push(panel) }),
      waitingEvery: 60_000,
    });

    // A tool line reads the record's two tails and no status.
    expect(after).toEqual({ listings: 0, statuses: 0 });
    expect(marked).toBe(1);
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.record')
        .every(
          (frame) => (frame.payload as { root?: string }).root === 'orc_1',
        ),
    ).toBe(true);
  });

  it("reads the record on a push and never on the check's clock, which only redraws", async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.list', {
      code: 'OK',
      payload: { orchestrations: [liveRun()] },
    });
    fake.script('orchestration.status', liveStatus());
    fake.script(
      'orchestration.record',
      recordOf(() => [recordRow(1, 'run_started', 'started: build it')]),
    );
    const panels: (Panel | undefined)[] = [];
    const prompt = stepping([
      async () => {
        // One drawing from the read at connect, and two more from the clock.
        await until(() => panels.length >= 3);
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, panel: (panel) => panels.push(panel) }),
      waitingEvery: 20,
    });

    // The tool line's tail and the milestones' tail, once each.
    expect(
      fake.heard.frames.filter(
        (frame) => frame.type === 'orchestration.record',
      ),
    ).toHaveLength(2);
    expect(
      panels.every(
        (panel) => panel?.settled.includes('  goal ✓ code ● review ○') === true,
      ),
    ).toBe(true);
  });

  it('shows the whole question an asking tree waits on, read alone when its last milestones do not hold it', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking'
            ? [liveRun('asking')]
            : [],
      },
    }));
    fake.script('orchestration.status', liveStatus('asking'));
    const question =
      "The spec's ## Acceptance section now includes commands that import modules, check for key" +
      ' classes and methods, and run the game. Does this satisfy the requirement?\n\nOr should it drive a session?';
    // Three milestones after the question: the panel's tail of three does not reach it.
    fake.script(
      'orchestration.record',
      recordOf(() => [
        recordRow(
          1,
          'run_started',
          'implement_specification started: build it',
        ),
        {
          ...recordRow(
            2,
            'question_asked',
            "asked: The spec's ## Acceptance section now…",
          ),
          body: question,
        },
        recordRow(3, 'stage_moved', 'a: pending → done'),
        recordRow(4, 'stage_moved', 'b: pending → done'),
        recordRow(5, 'stage_moved', 'c: pending → done'),
      ]),
    );
    const panels: (Panel | undefined)[] = [];
    const prompt = stepping([
      async () => {
        await until(() =>
          panels.some(
            (panel) =>
              panel?.settled.some((line) => line.includes('Or should it')) ===
              true,
          ),
        );
        return undefined;
      },
    ]);
    await converse({
      ...talkingTo(fake, { ...prompt, panel: (panel) => panels.push(panel) }),
      waitingEvery: 60_000,
    });

    const drawn = panels.find(
      (panel) =>
        panel?.settled.some((line) => line.includes('Or should it')) === true,
    );
    const lines = drawn?.settled ?? [];
    const at = lines.findIndex((line) => line.startsWith('  ? '));
    // Wrapped at eighty, the surface saying no width: the panel's padding and indent leave 75.
    expect(lines.slice(at, at + 5)).toEqual([
      "  ? The spec's ## Acceptance section now includes commands that import modules,",
      '    check for key classes and methods, and run the game. Does this satisfy the',
      '    requirement?',
      '    ',
      '    Or should it drive a session?',
    ]);
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.record')
        .map((frame) => frame.payload),
    ).toContainEqual({
      root: 'orc_1',
      tail: true,
      limit: 1,
      kinds: ['question_asked'],
    });
  });

  it('asks for nothing about a panel on a surface that has none', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.list', {
      code: 'OK',
      payload: { orchestrations: [liveRun()] },
    });
    await converse({ ...talkingTo(fake, scripted([])), waitingEvery: 60_000 });

    expect(
      fake.heard.frames.some((frame) => frame.type === 'orchestration.record'),
    ).toBe(false);
  });
});

describe('/watch, over a real socket', () => {
  const many = (): Record<string, unknown>[] =>
    Array.from({ length: 150 }, (_, at) =>
      at % 2 === 0
        ? recordRow(at + 1, 'tool_call', `line ${at + 1}`, 'ok')
        : recordRow(at + 1, 'stage_moved', `line ${at + 1}`),
    );

  it('opens on the tail, goes earlier, follows a push, narrows to milestones and goes back', async () => {
    const fake = await scriptedPlowshare([]);
    let rows = many();
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus());
    const views: (Viewed | undefined)[] = [];
    const shows = (text: string): boolean =>
      views.some(
        (view) =>
          view?.body.some((line) => plainOf(line).includes(text)) === true,
      );
    const keys: (() => Promise<ViewKey | undefined>)[] = [
      async () => 'earlier',
      async () => {
        await until(() => shows('line 1  ✓'));
        rows = [
          ...rows,
          recordRow(151, 'run_ended', 'implement_specification finished: done'),
        ];
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_1',
            through: 151,
          }),
        );
        await until(() => shows('implement_specification finished: done'));
        return 'tools';
      },
      // The keys do not wait on the filter's read, so the case does: once it has landed.
      async () => {
        await until(
          () => views.at(-1)?.foot.startsWith('milestones ·') === true,
        );
        return 'close';
      },
    ];
    const prompt = scripted(['/watch orc_1']);
    await converse(
      talkingTo(fake, {
        ...prompt,
        view: (viewed) => views.push(viewed),
        viewKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
      }),
    );
    const asked = fake.heard.frames
      .filter((frame) => frame.type === 'orchestration.record')
      .map((frame) => frame.payload);

    // Odd ordinals are tool lines ("line 149  ✓"), even ones stage moves ("◆ line 150").
    expect(asked).toContainEqual({ root: 'orc_1', tail: true, limit: 100 });
    expect(plainOf(views[0]?.body.at(-1) ?? [])).toMatch(/◆ line 150$/);
    expect(views[0]?.body.map(plainOf)).toContainEqual(
      expect.stringContaining('line 149  ✓') as unknown,
    );
    expect(
      views[0]?.body.some((line) => plainOf(line).endsWith('◆ line 50')),
    ).toBe(false);
    expect(asked).toContainEqual({ root: 'orc_1', before: 51, limit: 100 });
    expect(asked).toContainEqual({ root: 'orc_1', after: 150, limit: 100 });
    expect(asked).toContainEqual({
      root: 'orc_1',
      tail: true,
      limit: 100,
      kinds: expect.arrayContaining(['stage_moved', 'run_ended']) as unknown,
    });
    const narrowed = views.at(-2);
    expect(narrowed?.body.some((line) => plainOf(line).includes('✓'))).toBe(
      false,
    );
    expect(views.at(-1)).toBeUndefined();
  });

  it('prints the tail once on a surface with no viewer', async () => {
    const fake = await scriptedPlowshare([]);
    const rows = many();
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus());
    const prompt = scripted(['/watch orc_1']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n');

    expect(said).toContain('orc_1  implement_specification');
    expect(said).toContain('line 149  ✓');
    expect(said).toContain('◆ line 150');
    // No key to press there, so no line that says to press one.
    expect(said).not.toContain('earlier: e');
  });

  it("draws a row's whole text under it, and scrolls by the lines that takes", async () => {
    const fake = await scriptedPlowshare([]);
    const rows = [
      ...many(),
      {
        ...recordRow(151, 'question_asked', 'asked: Which database?'),
        body: 'Which database?\nPostgres, for the server\nor SQLite, for the tests',
      },
    ];
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus('asking'));
    const views: (Viewed | undefined)[] = [];
    const keys: (() => Promise<ViewKey | undefined>)[] = [
      async () => 'up',
      async () => 'close',
    ];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
        viewBody: () => 12,
        viewRoom: () => 11,
      }),
    );

    const first = views[0]?.body.map(plainOf) ?? [];
    // Twelve lines, however many are body: the question's line and its three, and eight rows above.
    expect(first).toHaveLength(12);
    expect(first.slice(-4)).toEqual([
      expect.stringMatching(/\? asked: Which database\?$/u),
      `${' '.repeat(22)}Which database?`,
      `${' '.repeat(22)}Postgres, for the server`,
      `${' '.repeat(22)}or SQLite, for the tests`,
    ]);
    expect(first[0]).toMatch(/line 143/u);
    // Up one: the cursor is on the row above, and the screen did not move — it was on it.
    const moved = views[1]?.body.map(plainOf) ?? [];
    expect(moved).toHaveLength(12);
    expect(moved.find((line) => line.startsWith('▸'))).toMatch(/◆ line 150$/u);
    expect(moved.slice(-3)).toEqual(first.slice(-3));
  });

  it("prints a row's whole text under it on a surface with no viewer, wrapped at eighty", async () => {
    const fake = await scriptedPlowshare([]);
    const long = Array.from({ length: 30 }, (_, at) => `word${at}`).join(' ');
    const rows = [
      recordRow(1, 'run_started', 'started: build it'),
      { ...recordRow(2, 'question_asked', 'asked: word0 word1…'), body: long },
    ];
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus('asking'));
    const prompt = scripted(['/watch orc_1']);
    await converse(talkingTo(fake, prompt));
    const said = prompt.said.join('\n').split('\n');

    const under = said.filter(
      (line) => line.startsWith(' '.repeat(22)) && line.includes('word'),
    );
    expect(under.length).toBeGreaterThan(1);
    for (const line of under) {
      expect(line.length).toBeLessThanOrEqual(79);
    }
    expect(under.map((line) => line.trim()).join(' ')).toBe(long);
  });

  it("labels the header's checklist with the phase children under their stage", async () => {
    const fake = await scriptedPlowshare([]);
    fake.script(
      'orchestration.record',
      recordOf(() => many()),
    );
    fake.script('orchestration.status', {
      code: 'OK',
      payload: {
        orchestration: liveRun(),
        todos: [
          { id: 't1', text: 'goal', status: 'done', stage: 'goal' },
          { id: 't2', text: 'phases', status: 'in_progress', stage: 'phases' },
          { id: 't3', parent: 't2', text: 'utils', status: 'done' },
          { id: 't4', parent: 't2', text: 'readme', status: 'pending' },
        ],
        messages: [],
        children: [],
      },
    });
    const prompt = scripted(['/watch orc_1']);
    await converse(talkingTo(fake, prompt));

    expect(prompt.said.join('\n')).toContain('phases: utils ✓ readme ○');
  });

  it('opens on the newest live root when none is named, with no panel to ask', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.list', (payload) => ({
      code: 'OK',
      payload: {
        orchestrations:
          (payload as { state?: string }).state === 'asking'
            ? [liveRun('asking')]
            : [],
      },
    }));
    fake.script(
      'orchestration.record',
      recordOf(() => many()),
    );
    fake.script('orchestration.status', liveStatus('asking'));
    const prompt = scripted(['/watch']);
    await converse(talkingTo(fake, prompt));

    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.record')
        .map((frame) => frame.payload),
    ).toContainEqual({ root: 'orc_1', tail: true, limit: 100 });
    expect(prompt.said.join('\n')).toContain('◆ line 150');
  });

  it('settles a tool line that was still running when a push says its outcome landed', async () => {
    const fake = await scriptedPlowshare([]);
    const settledAs = (detail?: string): Record<string, unknown>[] => [
      recordRow(1, 'stage_moved', 'code: pending → in_progress'),
      recordRow(2, 'tool_call', 'coder · run ./gradlew test', detail),
    ];
    let rows = settledAs();
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus());
    const views: (Viewed | undefined)[] = [];
    const shows = (text: string): boolean =>
      views.some(
        (view) =>
          view?.body.some((line) => plainOf(line).includes(text)) === true,
      );
    const keys: (() => Promise<ViewKey | undefined>)[] = [
      async () => {
        await until(() => shows('coder · run ./gradlew test  …'));
        rows = settledAs('exit 1');
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_1',
            through: 2,
          }),
        );
        await until(() => shows('coder · run ./gradlew test  ✗ exit 1'));
        return 'close';
      },
    ];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
      }),
    );

    // Read again from before the open line — the one row the server writes twice.
    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.record')
        .map((frame) => frame.payload),
    ).toContainEqual({ root: 'orc_1', after: 1, limit: 100 });
    expect(plainOf(views.at(-2)?.body.at(-1) ?? [])).toMatch(
      /run \.\/gradlew test {2}✗ exit 1$/u,
    );
    expect(
      views.at(-2)?.body.filter((line) => plainOf(line).includes('gradlew')),
    ).toHaveLength(1);
  });

  it('settles a line far above the end by the ordinal its push names', async () => {
    const fake = await scriptedPlowshare([]);
    // The conductor's delegation at 2, still open; the delegate's 150 lines after it.
    const delegate = Array.from({ length: 150 }, (_, at) =>
      recordRow(at + 3, 'tool_call', `coder · file_read /f${at + 3}`, 'ok'),
    );
    const delegating = (detail?: string): Record<string, unknown>[] => [
      recordRow(1, 'stage_moved', 'code: pending → in_progress'),
      recordRow(2, 'tool_call', 'conductor · agent_run coder', detail),
      ...delegate,
    ];
    let rows = delegating();
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus());
    const views: (Viewed | undefined)[] = [];
    const shows = (text: string): boolean =>
      views.some(
        (view) =>
          view?.body.some((line) => plainOf(line).includes(text)) === true,
      );
    const keys: (() => Promise<ViewKey | undefined>)[] = [
      // The tail is 53..152: the delegation is one earlier page up.
      async () => 'earlier',
      async () => {
        await until(() => shows('conductor  ◌ agent_run coder  …'));
        rows = delegating('ok');
        fake.push(
          JSON.stringify({
            kind: 'orchestration.recorded',
            root: 'orc_1',
            through: 152,
            settled: 2,
          }),
        );
        await until(() => shows('conductor  ● agent_run coder  ✓'));
        return 'close';
      },
    ];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
      }),
    );
    const asked = fake.heard.frames
      .filter((frame) => frame.type === 'orchestration.record')
      .map((frame) => frame.payload);

    // The one line, alone — not the 150 below it again.
    expect(asked).toContainEqual({
      root: 'orc_1',
      after: 1,
      limit: 1,
      kinds: ['tool_call'],
    });
    expect(asked).toContainEqual({ root: 'orc_1', after: 152, limit: 100 });
    expect(
      views.at(-2)?.body.filter((line) => plainOf(line).includes('agent_run')),
    ).toHaveLength(1);
  });

  it('holds a window of rows while scrolled up, reads nothing below what it let go, and reads the end on follow', async () => {
    const fake = await scriptedPlowshare([]);
    let rows = many();
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus());
    const views: (Viewed | undefined)[] = [];
    const shows = (text: string): boolean =>
      views.some(
        (view) =>
          view?.body.some((line) => plainOf(line).includes(text)) === true,
      );
    const asked = (): Record<string, unknown>[] =>
      fake.heard.frames
        .filter((frame) => frame.type === 'orchestration.record')
        .map((frame) => frame.payload as Record<string, unknown>);
    const grownTo = (through: number): void => {
      const from = rows.length + 1;
      rows = [
        ...rows,
        ...Array.from({ length: through - rows.length }, (_, at) =>
          recordRow(from + at, 'tool_call', `line ${from + at}`, 'ok'),
        ),
      ];
      fake.push(
        JSON.stringify({
          kind: 'orchestration.recorded',
          root: 'orc_1',
          through,
        }),
      );
    };
    const end = 150 + MOST_HELD;
    const RECORD_PAGE = 100;
    const keys: (() => Promise<ViewKey | undefined>)[] = [
      // A person who pressed ↑ once and left the viewer there, while the tree ran on.
      async () => 'up',
      async () => {
        grownTo(end);
        // Page by page from where it stood; the last page takes it past what it holds.
        await until(() =>
          asked().some((each) => each['after'] === end - RECORD_PAGE),
        );
        await new Promise((settle) => setTimeout(settle, 50));
        // One more line, pushed to a viewer that let the end go: nothing is read for it.
        grownTo(end + 1);
        await new Promise((settle) => setTimeout(settle, 50));
        return 'follow';
      },
      async () => {
        await until(() => shows(`line ${end + 1}  ✓`));
        return 'close';
      },
    ];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
      }),
    );

    // Read from where it stood once, page by page, and nothing past what it let go — not for
    // the last line pushed either; then the end afresh on follow, the tail it opened with.
    expect(
      asked().filter((each) => each['after'] === end - RECORD_PAGE),
    ).toHaveLength(1);
    expect(
      asked().filter(
        (each) => typeof each['after'] === 'number' && each['after'] >= end,
      ),
    ).toEqual([]);
    expect(asked().filter((each) => each['tail'] === true)).toHaveLength(2);
    expect(views.at(-1)).toBeUndefined();
  });

  it('words only the body lines the surface says it has room for', async () => {
    const fake = await scriptedPlowshare([]);
    const rows = many();
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus());
    const views: (Viewed | undefined)[] = [];
    const framed: Viewed[] = [];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: async () => 'close',
        viewBody: (frame) => {
          framed.push(frame);
          return 12;
        },
      }),
    );

    expect(views[0]?.body).toHaveLength(12);
    expect(plainOf(views[0]?.body[0] ?? [])).toMatch(/line 139 {2}✓$/u);
    expect(plainOf(views[0]?.body.at(-1) ?? [])).toMatch(/◆ line 150$/u);
    // Asked of the frame, before any row was worded.
    expect(framed[0]?.body).toEqual([]);
    expect(plainOf(framed[0]?.head[0] ?? [])).toContain('orc_1');
  });

  it('reads a line its run ended before settling as unknown, not as going on for ever', async () => {
    const fake = await scriptedPlowshare([]);
    const rows = [recordRow(1, 'tool_call', 'conductor · run ./gradlew test')];
    fake.script(
      'orchestration.record',
      recordOf(() => rows),
    );
    fake.script('orchestration.status', liveStatus('failed'));
    const prompt = scripted(['/watch orc_1']);
    await converse(talkingTo(fake, prompt));

    expect(prompt.said.join('\n')).toContain(
      'conductor  ● run ./gradlew test  unknown',
    );
  });

  it('leaves on Esc while a read it asked for has not answered, and says why a read was refused', async () => {
    const fake = await scriptedPlowshare([]);
    const rows = many();
    const tail = recordOf(() => rows);
    fake.script('orchestration.record', (payload) => {
      const asked = payload as { before?: number; kinds?: string[] };
      // An earlier page that never comes, and a filter the server will not read.
      if (asked.before !== undefined) {
        return new Promise<Reply>(() => undefined);
      }
      return asked.kinds === undefined
        ? tail(payload)
        : {
            code: 'BAD_REQUEST',
            said: 'orchestration.record has an unknown kind',
          };
    });
    fake.script('orchestration.status', liveStatus());
    const views: (Viewed | undefined)[] = [];
    const keys: (() => Promise<ViewKey | undefined>)[] = [
      async () => 'tools',
      async () => {
        await until(() => views.some((view) => view?.said !== undefined));
        return 'earlier';
      },
      async () => 'close',
    ];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
      }),
    );

    // The refused filter said so once, in the viewer, and changed nothing.
    const said = views.filter((view) => view?.said !== undefined);
    expect(said.map((view) => view?.said)).toEqual([
      'orchestration.record has an unknown kind',
    ]);
    expect(said[0]?.foot).toContain('t milestones only');
    // Esc went through with the earlier page still unanswered.
    expect(views.at(-1)).toBeUndefined();
  });

  it('says a refused listing was refused, not that there are no runs', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.list', {
      code: 'BAD_REQUEST',
      said: 'orchestration.list needs an account',
    });
    const prompt = scripted(['/watch']);
    await converse(talkingTo(fake, prompt));

    expect(prompt.said.join('\n')).toContain(
      'orchestration.list needs an account',
    );
    expect(prompt.said.join('\n')).not.toContain('nothing to watch');
  });

  /** `orchestration.status` for a root whose conductor's conversation is `conversation`. */
  const conducted = (conversation: string): Reply => {
    const status = liveStatus();
    return {
      ...status,
      payload: {
        ...(status.payload as Record<string, unknown>),
        orchestration: { ...liveRun(), conductorConversation: conversation },
      },
    };
  };
  /** A delegate's failed tool line under a stage move: Enter opens the root's conductor log. */
  const delegated = (): Record<string, unknown>[] => [
    recordRow(1, 'stage_moved', 'code: pending → in_progress'),
    {
      ...recordRow(2, 'tool_call', 'coder · run ./gradlew test', 'exit 1'),
      actor: 'coder',
    },
  ];

  it("opens the bottom row's run in the explorer on Enter, and is back where it was when that closes", async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.record', recordOf(delegated));
    fake.script('orchestration.status', conducted('cnv_root'));
    fake.script('conversation.trajectory', (payload) =>
      paged(
        [
          logged(1, 1, 'utterance', 'build it'),
          logged(2, 1, 'answer', 'on it'),
        ],
        payload,
      ),
    );
    const views: (Viewed | undefined)[] = [];
    const screens: (readonly Tinted[] | undefined)[] = [];
    const viewKeys: ViewKey[] = ['open', 'close'];
    await converse(
      talkingTo(fake, {
        ...scripted(['/watch orc_1']),
        view: (viewed) => views.push(viewed),
        viewKey: async () => viewKeys.shift(),
        explore: (lines) => screens.push(lines),
        exploreKey: async () => {
          await until(() => screens.at(-1) !== undefined);
          return 'close';
        },
        exploreSize: () => ({ rows: 30, columns: 140 }),
      }),
    );

    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'conversation.trajectory')
        .map((frame) => frame.payload),
    ).toContainEqual({ conversation: 'cnv_root', tail: true, limit: 100 });
    expect(
      screens.some(
        (lines) =>
          lines?.some((line) => plainOf(line).includes('build it')) === true,
      ),
    ).toBe(true);
    // Put away while the explorer was up, and drawn again as it was once it closed.
    const away = views.indexOf(undefined);
    expect(away).toBeGreaterThan(0);
    expect(plainOf(views[away + 1]?.body.at(-1) ?? [])).toContain(
      'run ./gradlew test  ✗ exit 1',
    );
    expect(views[away + 1]?.said).toBeUndefined();
    expect(views.at(-1)).toBeUndefined();
  });

  it("says so in the viewer when the row's run has no log to open, or its log was refused", async () => {
    for (const [status, trajectory, expected] of [
      [liveStatus(), undefined, describeNothingToOpen()],
      [
        conducted('cnv_root'),
        { code: 'BAD_REQUEST', said: 'no' },
        describeTrajectoryUnreadable('cnv_root'),
      ],
    ] as const) {
      const fake = await scriptedPlowshare([]);
      fake.script('orchestration.record', recordOf(delegated));
      fake.script('orchestration.status', status);
      if (trajectory !== undefined) {
        fake.script('conversation.trajectory', trajectory);
      }
      const views: (Viewed | undefined)[] = [];
      const viewKeys: ViewKey[] = ['open', 'close'];
      await converse(
        talkingTo(fake, {
          ...scripted(['/watch orc_1']),
          view: (viewed) => views.push(viewed),
          viewKey: async () => viewKeys.shift(),
          explore: () => undefined,
          exploreKey: async () => 'close',
          exploreSize: () => ({ rows: 30, columns: 140 }),
        }),
      );

      expect(
        views.map((view) => view?.said).filter((said) => said !== undefined),
      ).toEqual([expected]);
    }
  });

  it('says there is nothing to watch for an account with no runs', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.list', {
      code: 'OK',
      payload: { orchestrations: [] },
    });
    const prompt = scripted(['/watch']);
    await converse(talkingTo(fake, prompt));

    expect(prompt.said.join('\n')).toContain('nothing to watch');
  });
});

describe('/trajectory and /log, over a real socket', () => {
  const ROOT: LogRow[] = [
    logged(1, 1, 'utterance', 'ask the reviewer'),
    {
      ...logged(2, 1, 'answer', ''),
      toolCalls: [
        {
          id: 'c0',
          name: 'agent_run',
          arguments: '{"agent":"code_reviewer"}',
          length: 25,
          cut: false,
          salient: 'code_reviewer',
          opened: { conversation: 'cnv_child', agent: 'code_reviewer' },
        },
      ],
    },
    {
      ...logged(3, 1, 'tool_result', 'looks fine'),
      toolCallId: 'c0',
      outcome: 'ok',
      tookMillis: 48_000,
    },
    logged(4, 1, 'answer', 'The reviewer is happy.'),
  ];
  const CHILD: LogRow[] = [
    logged(1, 1, 'utterance', 'review the tokenizer fix'),
    {
      ...logged(2, 1, 'answer', ''),
      toolCalls: [
        {
          id: 'k0',
          name: 'file_read',
          arguments: '{"path":"src/T.java"}',
          length: 20,
          cut: false,
          salient: 'src/T.java',
          opened: null,
        },
      ],
    },
    {
      ...logged(3, 1, 'tool_result', 'class T {}'),
      toolCallId: 'k0',
      outcome: 'ok',
      tookMillis: 12,
    },
    logged(4, 1, 'answer', 'Looks fine.'),
  ];

  it('opens on the tail, descends into the child, comes back to the call, and closes', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('conversation.trajectory', (payload) =>
      paged(
        (payload as { conversation?: string }).conversation === 'cnv_child'
          ? CHILD
          : ROOT,
        payload,
      ),
    );
    const screens: (readonly Tinted[] | undefined)[] = [];
    const shows = (text: string): boolean =>
      screens.some(
        (lines) => lines?.some((line) => plainOf(line).includes(text)) === true,
      );
    const keys: (() => Promise<ExploreKey | undefined>)[] = [
      async () => 'up',
      async () => 'descend',
      async () => {
        await until(() => shows('cnv_root › code_reviewer'));
        return 'ascend';
      },
      async () => {
        await until(
          () =>
            screens
              .at(-1)
              ?.some((line) => /▸.*agent_run/u.test(plainOf(line))) === true,
        );
        return 'view';
      },
      async () => 'close',
    ];
    const prompt = scripted(['/trajectory cnv_root']);
    await converse(
      talkingTo(fake, {
        ...prompt,
        explore: (lines) => screens.push(lines),
        exploreKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
        exploreSize: () => ({ rows: 30, columns: 140 }),
      }),
    );
    const asked = fake.heard.frames
      .filter((frame) => frame.type === 'conversation.trajectory')
      .map((frame) => frame.payload);
    expect(asked).toContainEqual({
      conversation: 'cnv_root',
      tail: true,
      limit: 100,
    });
    expect(asked).toContainEqual({
      conversation: 'cnv_child',
      tail: true,
      limit: 100,
    });
    expect(shows('review the tokenizer fix')).toBe(true);
    // Going back up follows the root again without holding the explorer open for the answer,
    // so the frame can reach the server a moment after the session has ended.
    const follows = (): unknown[] =>
      fake.heard.frames
        .filter((frame) => frame.type === 'conversation.follow')
        .map((frame) => frame.payload);
    await until(() => follows().length >= 2);
    expect(follows()).toEqual(
      expect.arrayContaining([
        { conversation: 'cnv_child' },
        { conversation: 'cnv_root' },
      ]),
    );
    expect(shows(' · log')).toBe(true);
    expect(screens.at(-1)).toBeUndefined();
  });

  /** The payloads of every `conversation.follow` heard, in order. */
  const followsOf = (fake: Fake): unknown[] =>
    fake.heard.frames
      .filter((frame) => frame.type === 'conversation.follow')
      .map((frame) => frame.payload);

  it("follows the chat's conversation again when it closes from inside a delegation", async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    fake.script('conversation.trajectory', (payload) =>
      paged(
        (payload as { conversation?: string }).conversation === 'cnv_child'
          ? CHILD
          : ROOT,
        payload,
      ),
    );
    const screens: (readonly Tinted[] | undefined)[] = [];
    const shows = (text: string): boolean =>
      screens.some(
        (lines) => lines?.some((line) => plainOf(line).includes(text)) === true,
      );
    const keys: (() => Promise<ExploreKey | undefined>)[] = [
      async () => 'up',
      async () => 'descend',
      async () => {
        await until(() => shows('close_reader › code_reviewer'));
        return 'close';
      },
    ];
    const prompt = scripted(['how many modules?', '/trajectory']);
    await converse(
      talkingTo(fake, {
        ...prompt,
        explore: (lines) => screens.push(lines),
        exploreKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
        exploreSize: () => ({ rows: 30, columns: 140 }),
      }),
    );

    // Bare, it opens on the chat's own conversation, which is already followed.
    expect(followsOf(fake)).toEqual([
      { conversation: 'c-1' },
      { conversation: 'cnv_child' },
      { conversation: 'c-1' },
    ]);
    expect(screens.at(-1)).toBeUndefined();
  });

  it('never follows a delegation whose read lands after it closed', async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    let release: () => void = () => undefined;
    const held = new Promise<void>((done) => {
      release = done;
    });
    fake.script('conversation.trajectory', async (payload) => {
      if ((payload as { conversation?: string }).conversation !== 'cnv_child') {
        return paged(ROOT, payload);
      }
      await held;
      return paged(CHILD, payload);
    });
    const keys: (() => Promise<ExploreKey | undefined>)[] = [
      async () => 'up',
      async () => 'descend',
      async () => {
        setTimeout(release, 20);
        return 'close';
      },
    ];
    const prompt = scripted(['how many modules?', '/trajectory']);
    await converse(
      talkingTo(fake, {
        ...prompt,
        explore: () => undefined,
        exploreKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
        exploreSize: () => ({ rows: 30, columns: 140 }),
      }),
    );

    expect(
      fake.heard.frames
        .filter((frame) => frame.type === 'conversation.trajectory')
        .map((frame) => frame.payload),
    ).toContainEqual({ conversation: 'cnv_child', tail: true, limit: 100 });
    expect(followsOf(fake)).toEqual([{ conversation: 'c-1' }]);
  });

  it("follows a conversation other than the chat's while it is open, and the chat's again after", async () => {
    const fake = await scriptedPlowshare(['answer-first']);
    fake.script('conversation.trajectory', (payload) => paged(ROOT, payload));
    const prompt = scripted(['how many modules?', '/trajectory cnv_root']);
    await converse(
      talkingTo(fake, {
        ...prompt,
        explore: () => undefined,
        exploreKey: async () => 'close',
        exploreSize: () => ({ rows: 30, columns: 140 }),
      }),
    );

    expect(followsOf(fake)).toEqual([
      { conversation: 'c-1' },
      { conversation: 'cnv_root' },
      { conversation: 'c-1' },
    ]);
  });

  it('draws again, fitted, when the terminal is resized', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('conversation.trajectory', (payload) => paged(ROOT, payload));
    const screens: (readonly Tinted[] | undefined)[] = [];
    let size = { rows: 30, columns: 140 };
    let resized: () => void = () => undefined;
    let unsubscribed = false;
    const keys: (() => Promise<ExploreKey | undefined>)[] = [
      async () => {
        size = { rows: 8, columns: 60 };
        resized();
        return 'close';
      },
    ];
    const prompt = scripted(['/trajectory cnv_root']);
    await converse(
      talkingTo(fake, {
        ...prompt,
        explore: (lines) => screens.push(lines),
        exploreKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
        exploreSize: () => size,
        onResize: (listener) => {
          resized = listener;
          return () => {
            unsubscribed = true;
          };
        },
      }),
    );

    expect(screens[0]?.length).toBeGreaterThan(8);
    expect(screens.at(-2)?.length).toBeLessThanOrEqual(7);
    expect(unsubscribed).toBe(true);
  });

  it('says a log it could not read could not be read, naming it', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('conversation.trajectory', {
      code: 'NOT_FOUND',
      said: 'no such conversation',
    });
    const prompt = scripted(['/trajectory cnv_gone']);
    await converse(talkingTo(fake, prompt));
    expect(prompt.said.join('\n')).toContain(
      describeTrajectoryUnreadable('cnv_gone'),
    );
  });

  it('prints the rows on a surface with no keys', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('conversation.trajectory', (payload) => paged(ROOT, payload));
    const prompt = scripted(['/log cnv_root']);
    await converse(talkingTo(fake, prompt));
    expect(prompt.said.join('\n')).toMatch(/#3 +t1 +tool_result/u);
  });
});

describe("the explorer's reads, landing after the level they were for has gone", () => {
  const entry = (
    ordinal: number,
    kind: string,
    text: string,
    extra: Partial<LogEntry> = {},
  ): LogEntry => ({
    ordinal,
    turnOrdinal: 1,
    kind,
    state: 'stands',
    text,
    ...extra,
  });
  const door = (
    ordinal: number,
    id: string,
    conversation: string,
    agent: string,
  ): LogEntry =>
    entry(ordinal, 'answer', '', {
      calls: [
        {
          id,
          name: 'agent_run',
          arguments: '{}',
          length: 2,
          cut: false,
          salient: agent,
          opened: { conversation, agent },
        },
      ],
    });
  const pageOf = (entries: readonly LogEntry[], more = false): BackPage => ({
    entries,
    through: entries.at(-1)?.ordinal ?? 0,
    more,
    ...(entries.length === 0 ? {} : { oldest: entries[0]?.ordinal as number }),
  });
  const ROOT = [
    entry(1, 'utterance', 'ask the reviewer'),
    door(2, 'c0', 'cnv_child', 'code_reviewer'),
    entry(3, 'tool_result', 'fine', { toolCallId: 'c0', outcome: 'ok' }),
    entry(4, 'answer', 'The reviewer is happy.'),
  ];
  const CHILD = [
    entry(10, 'utterance', 'review it'),
    door(11, 'k0', 'cnv_grand', 'test_runner'),
    entry(12, 'tool_result', 'passing', { toolCallId: 'k0', outcome: 'ok' }),
    entry(13, 'answer', 'Looks fine.'),
  ];

  /** Runs the explorer on `cnv_root` over `reads`, pressing `keys`; returns every screen drawn. */
  async function exploring(
    reads: Reads,
    keys: (() => Promise<ExploreKey | undefined>)[],
    screens: (readonly Tinted[] | undefined)[],
  ): Promise<void> {
    const surface = {
      explore: (lines: readonly Tinted[] | undefined) => screens.push(lines),
      exploreKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
      exploreSize: () => ({ rows: 30, columns: 140 }),
    } as unknown as Surface;
    await explore(
      {
        surface,
        reads,
        appended: () => () => undefined,
        zone: 'UTC',
        print: () => undefined,
      },
      { conversation: 'cnv_root', label: 'plowshare', view: 'trajectory' },
    );
  }
  const text = (screen: readonly Tinted[] | undefined): string =>
    (screen ?? []).map(plainOf).join('\n');

  it("never merges a child's earlier page into the parent that ← put back on screen", async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((done) => {
      release = done;
    });
    let landed = false;
    const reads: Reads = {
      tail: async (conversation) =>
        conversation === 'cnv_child' ? pageOf(CHILD, true) : pageOf(ROOT),
      before: async (conversation) => {
        await held;
        landed = true;
        return conversation === 'cnv_child'
          ? pageOf([entry(5, 'utterance', 'CHILD OLDER ROW')])
          : pageOf([]);
      },
      after: async () => [],
      follow: async () => undefined,
    };
    const screens: (readonly Tinted[] | undefined)[] = [];
    await exploring(
      reads,
      [
        async () => 'up',
        async () => 'descend',
        async () => {
          await until(() =>
            text(screens.at(-1)).includes('plowshare › code_reviewer'),
          );
          return 'top';
        },
        // ↑ at the top asked for the child's earlier page; ← goes up before it lands.
        async () => 'ascend',
        async () => {
          release();
          await until(() => landed);
          return 'down';
        },
        async () => {
          await until(() => screens.length > 0);
          return 'close';
        },
      ],
      screens,
    );

    expect(landed).toBe(true);
    expect(
      screens.some((screen) => text(screen).includes('CHILD OLDER ROW')),
    ).toBe(false);
    expect(text(screens.at(-2))).toMatch(/^ plowshare · /u);
  });

  it('drops a descent whose origin level is no longer on screen', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((done) => {
      release = done;
    });
    // Read for the door's step count; the descent's own read, queued behind it, finds its
    // origin gone and is never made — or, made, is dropped when it lands.
    let grandReads = 0;
    const reads: Reads = {
      tail: async (conversation) => {
        if (conversation === 'cnv_grand') {
          await held;
          grandReads += 1;
          return pageOf([entry(20, 'utterance', 'GRANDCHILD ROW')]);
        }
        return conversation === 'cnv_child' ? pageOf(CHILD) : pageOf(ROOT);
      },
      before: async () => pageOf([]),
      after: async () => [],
      follow: async () => undefined,
    };
    const screens: (readonly Tinted[] | undefined)[] = [];
    await exploring(
      reads,
      [
        async () => 'up',
        async () => 'descend',
        async () => {
          await until(() =>
            text(screens.at(-1)).includes('plowshare › code_reviewer'),
          );
          return 'up';
        },
        // → from the child's door, then ← before the grandchild's log lands.
        async () => 'descend',
        async () => 'ascend',
        async () => {
          release();
          await until(() => grandReads >= 1);
          return 'down';
        },
        async () => {
          await new Promise((done) => {
            setTimeout(done, 10);
          });
          return 'close';
        },
      ],
      screens,
    );

    expect(grandReads).toBeGreaterThanOrEqual(1);
    expect(
      screens.some((screen) => text(screen).includes('GRANDCHILD ROW')),
    ).toBe(false);
    expect(
      screens.some((screen) => text(screen).includes('› test_runner')),
    ).toBe(false);
    expect(text(screens.at(-2))).toMatch(/^ plowshare · /u);
  });
});

describe('/board and /swarm over the signed-in socket', () => {
  it('inspects both views without opening a chat or issuing mutation frames', async () => {
    const fake = await scriptedPlowshare([]),
      fixture = demoBoard();
    fake.script('board.topics', {
      code: 'OK',
      payload: { topics: fixture.topics.value, more: false, offset: 0 },
    });
    fake.script('board.messages', {
      code: 'OK',
      payload: fixture.details['demo-board']!.value,
    });
    fake.script('swarm.status', { code: 'OK', payload: fixture.swarm.value });
    fake.script('conversation.trajectory', {
      code: 'OK',
      payload: { entries: [], through: 0, more: false },
    });
    const screens: (readonly Tinted[] | undefined)[] = [];
    const shows = (text: string) =>
      screens.at(-1)?.some((line) => plainOf(line).includes(text)) === true;
    const keys: (() => Promise<ExploreKey>)[] = [
      async () => {
        await until(() => shows('49/120 model calls'));
        return 'close';
      },
      async () => {
        await until(() => shows('spark 1/3 slots'));
        return 'close';
      },
    ];
    await converse(
      talkingTo(fake, {
        ...scripted(['/board Plowshare', '/swarm']),
        explore: (lines) => {
          screens.push(lines);
        },
        exploreKey: () => keys.shift()?.() ?? Promise.resolve(undefined),
        exploreSize: () => ({ rows: 30, columns: 140 }),
      }),
    );
    expect(fake.heard.frames).toContainEqual(
      expect.objectContaining({
        type: 'board.topics',
        payload: { project: 'Plowshare', offset: 0, limit: 200 },
      }),
    );
    expect(fake.heard.frames).toContainEqual(
      expect.objectContaining({ type: 'swarm.status', payload: {} }),
    );
    expect(
      fake.heard.frames.some((frame) =>
        [
          'board.open',
          'board.read',
          'board.post',
          'inbox.read',
          'agent.run',
          'conversation.open',
        ].includes(frame.type),
      ),
    ).toBe(false);
    expect(screens.at(-1)).toBeUndefined();
  });
});

describe('automatic session recovery', () => {
  it('reconnects and reconciles a known accepted job without submitting it again', async () => {
    const fake = await scriptedPlowshare(['drops-mid-run']),
      prompt = scripted(['A bounded question']);
    await converse({
      ...talkingTo(fake, prompt),
      reconnect: { attempts: 2, delayMs: 1 },
    });
    expect(
      fake.heard.frames.filter((row) => row.type === 'agent.run'),
    ).toHaveLength(1);
    expect(
      fake.heard.frames.some(
        (row) =>
          row.type === 'job.status' &&
          (row.payload as { job: string }).job === 'job-1',
      ),
    ).toBe(true);
    expect(prompt.said.join('\n')).toContain('Reconnected.');
    expect(prompt.said.join('\n')).toContain('Recovered job job-1');
  });
  it('retains an unacknowledged submission as uncertain and blocks another turn in that conversation', async () => {
    const fake = await scriptedPlowshare(['drops-before-handle']),
      prompt = scripted(['First intent', 'Do not duplicate']);
    await converse({
      ...talkingTo(fake, prompt),
      reconnect: { attempts: 2, delayMs: 1 },
    });
    expect(
      fake.heard.frames.filter((row) => row.type === 'agent.run'),
    ).toHaveLength(1);
    expect(fake.heard.frames.some((row) => row.type === 'job.status')).toBe(
      false,
    );
    expect(prompt.said.join('\n')).toContain('active or uncertain work');
  });
  it('withholds an unreadable acceptance even when the socket stays connected', async () => {
    const fake = await scriptedPlowshare([]),
      prompt = scripted(['First intent', 'Do not duplicate']);
    fake.script('agent.run', { code: 'ACCEPTED', payload: { id: 37 } });
    await converse({
      ...talkingTo(fake, prompt),
      reconnect: { attempts: 2, delayMs: 1 },
    });
    expect(
      fake.heard.frames.filter((row) => row.type === 'agent.run'),
    ).toHaveLength(1);
    expect(prompt.said.join('\n')).toContain('Unreadable agent.run');
    expect(prompt.said.join('\n')).toContain('active or uncertain work');
  });
  it('rejects an unreadable complete roster before creating a conversation or submitting work', async () => {
    const fake = await scriptedPlowshare([]),
      prompt = scripted(['Question']);
    fake.script('agent.list', {
      code: 'OK',
      payload: [{ name: 'broken', tools: 37 }],
    });
    await expect(converse(talkingTo(fake, prompt))).rejects.toThrow(
      /Unreadable agent.list/,
    );
    expect(
      fake.heard.frames.some(
        (row) => row.type === 'conversation.open' || row.type === 'agent.run',
      ),
    ).toBe(false);
  });
});

describe('server project administration', () => {
  it('creates a restricted DISJOINT project once without opening a conversation', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('project.create', {
      code: 'OK',
      payload: {
        name: 'Integration',
        workspace: '/srv/integration',
        lent: [],
        exclusions: [],
        machine: null,
        members: ['fixture'],
        type: 'DISJOINT',
        readOnly: false,
        writePaths: ['generated', 'reports'],
      },
    });
    fake.script('admin.status', {
      code: 'OK',
      payload: { handle: 'fixture', serverAdmin: true },
    });
    const prompt = scripted([
      '/admin status',
      '/project create {"name":"Integration","workspace":"/srv/integration","type":"DISJOINT","writePaths":["generated","reports"]}',
    ]);
    await converse(talkingTo(fake, prompt));
    expect(
      fake.heard.frames.filter((row) => row.type === 'project.create'),
    ).toEqual([
      expect.objectContaining({
        payload: {
          name: 'Integration',
          workspace: '/srv/integration',
          type: 'DISJOINT',
          writePaths: ['generated', 'reports'],
        },
      }),
    ]);
    expect(
      fake.heard.frames.some(
        (row) => row.type === 'conversation.open' || row.type === 'agent.run',
      ),
    ).toBe(false);
    expect(prompt.said.join('\n')).toContain('project.create: completed');
  });
});

describe('client-only DISJOINT discovery', () => {
  it('discovers a formal root manifest and serves it without marking or exporting it', async () => {
    const root = await realpath(await mkdtemp(join(tmpdir(), 'tui-disjoint-')));
    const key =
      'client:scope:' + Buffer.from('Integration').toString('base64url');
    const fake = await scriptedPlowshare([]),
      prompt = scripted(['/projects']);
    fake.script('project.list', { code: 'OK', payload: [] });
    fake.script('project.attach', {
      code: 'OK',
      payload: {
        name: key,
        displayName: 'Integration',
        workspace: root,
        machine: 'test',
        lent: [],
        exclusions: [],
        members: [],
        type: 'DISJOINT',
      },
    });
    try {
      const manifest = JSON.stringify({
        version: 1,
        name: 'Integration',
        routing: {
          sendTo: ['notifications'],
          routeFiles: ['routes/internal.json'],
        },
        integration: { enabled: true },
      });
      await writeFile(join(root, 'plowshare'), manifest);
      await converse({ ...talkingTo(fake, prompt), here: root });
      expect(
        fake.heard.frames.filter((row) => row.type === 'project.attach'),
      ).toHaveLength(1);
      expect(
        fake.heard.frames.some(
          (row) => row.type === 'union.enable' || row.type === 'project.define',
        ),
      ).toBe(false);
      expect(await readFile(join(root, 'plowshare'), 'utf8')).toBe(manifest);
      await expect(
        readFile(join(root, '.plowshare/project')),
      ).rejects.toMatchObject({ code: 'ENOENT' });
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
});

describe('server account administration', () => {
  it('runs account, session and audit commands over WS without starting agent work', async () => {
    const fake = await scriptedPlowshare([]);
    const fixtures = outcomes(
      readFileSync(
        new URL(
          '../../../test-support/contracts/ws-conversation-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    );
    const commands = [
      'admin accounts',
      'admin account create {"handle":"member"}',
      'admin account update {"handle":"member","enabled":false}',
      'admin account reset {"handle":"member"}',
      'admin sessions {"handle":"member"}',
      'admin session revoke {"handle":"member"}',
      'admin audit {"limit":25}',
    ];
    for (const type of [
      'admin.accounts',
      'admin.account.create',
      'admin.account.update',
      'admin.account.reset',
      'admin.sessions',
      'admin.session.revoke',
      'admin.audit',
    ])
      fake.script(type, fixtures[type] ?? { code: 'NOT_FOUND' });
    const prompt = scripted(commands.map((command) => '/' + command));
    await converse(talkingTo(fake, prompt));
    for (const type of [
      'admin.accounts',
      'admin.account.create',
      'admin.account.update',
      'admin.account.reset',
      'admin.sessions',
      'admin.session.revoke',
      'admin.audit',
    ])
      expect(fake.heard.frames.filter((row) => row.type === type)).toHaveLength(
        1,
      );
    expect(
      fake.heard.frames.some(
        (row) => row.type === 'conversation.open' || row.type === 'agent.run',
      ),
    ).toBe(false);
    expect(prompt.said.join('\n')).toContain('admin.account.create: completed');
  });
});

describe('project access management', () => {
  it('sends manager commands through the TUI with the selected project', async () => {
    const fake = await scriptedPlowshare([]);
    const access = {
      code: 'OK',
      payload: {
        project: 'ledger',
        role: 'MANAGER',
        permissions: ['read', 'work', 'manage'],
        members: [{ handle: 'member', role: 'VIEWER' }],
        history: [],
      },
    };
    fake.script('project.access', access);
    fake.script('project.member.role', access);
    const prompt = scripted([
      '/project access',
      '/project member-role {"handle":"member","role":"VIEWER"}',
    ]);
    await converse({ ...talkingTo(fake, prompt), project: 'ledger' });
    for (const type of ['project.access', 'project.member.role']) {
      const frames = fake.heard.frames.filter((row) => row.type === type);
      expect(frames).toHaveLength(1);
      expect(frames[0]).toMatchObject({ payload: { project: 'ledger' } });
    }
    expect(
      fake.heard.frames.find((row) => row.type === 'project.member.role'),
    ).toMatchObject({ payload: { role: 'VIEWER' } });
    expect(prompt.said.join('\n')).toContain('project.access: completed');
  });
});

describe('service account administration', () => {
  it('manages machine identities and scoped credentials over WS without starting an agent', async () => {
    const fake = await scriptedPlowshare([]);
    const fixtures = outcomes(
      readFileSync(
        new URL(
          '../../../test-support/contracts/ws-conversation-fixtures.json',
          import.meta.url,
        ),
        'utf8',
      ),
    );
    const cases = [
      ['admin.service.accounts', {}],
      ['admin.service.account.create', { handle: 'ha-integration' }],
      [
        'admin.service.account.update',
        { handle: 'ha-integration', enabled: false },
      ],
      ['admin.service.tokens', { handle: 'ha-integration' }],
      [
        'admin.service.token.create',
        {
          handle: 'ha-integration',
          name: 'production',
          scopes: [{ project: 'automation', role: 'CONTRIBUTOR' }],
        },
      ],
      [
        'admin.service.token.rotate',
        {
          handle: 'ha-integration',
          id: '00000000-0000-0000-0000-000000000001',
        },
      ],
      [
        'admin.service.token.revoke',
        {
          handle: 'ha-integration',
          id: '00000000-0000-0000-0000-000000000001',
        },
      ],
    ] as const;
    for (const [type] of cases)
      fake.script(type, fixtures[type] ?? { code: 'NOT_FOUND' });
    const prompt = scripted(
      cases.map(
        ([type, payload]) =>
          '/' + type.replaceAll('.', ' ') + ' ' + JSON.stringify(payload),
      ),
    );
    await converse(talkingTo(fake, prompt));
    for (const [type] of cases)
      expect(fake.heard.frames.filter((row) => row.type === type)).toHaveLength(
        1,
      );
    expect(
      fake.heard.frames.some(
        (row) => row.type === 'conversation.open' || row.type === 'agent.run',
      ),
    ).toBe(false);
    expect(prompt.said.join('\n')).toContain('pss_fixture-only');
  });
});

describe('failed run recovery', () => {
  it('dispatches resume and retry over WS without opening another conversation or job', async () => {
    const fake = await scriptedPlowshare([]);
    fake.script('orchestration.resume', {
      code: 'OK',
      payload: { id: 'orc_failed', state: 'running' },
    });
    let reads = 0;
    fake.script('orchestration.status', () => ({
      code: 'OK',
      payload: {
        todos: [],
        messages: [],
        children: [],
        orchestration: {
          ...liveRun('failed'),
          id: 'orc_failed',
          endedAt:
            ++reads <= 2 ? '2026-10-04T00:00:00Z' : '2026-10-04T00:00:01Z',
        },
      },
    }));
    const prompt = scripted([
      '/resume orc_failed',
      '/retry orc_failed',
      '/retry orc_failed',
    ]);
    await converse(talkingTo(fake, prompt));
    const requests = fake.heard.frames.filter(
      (frame) => frame.type === 'orchestration.resume',
    );
    expect(requests).toHaveLength(3);
    const requestKey: unknown = expect.stringMatching(/^[0-9a-f-]{36}$/);
    const keys = requests.map((frame) => {
      expect(frame).toMatchObject({
        payload: {
          id: 'orc_failed',
          requestId: requestKey,
        },
      });
      const payload = frame.payload;
      if (
        typeof payload !== 'object' ||
        payload === null ||
        !('requestId' in payload)
      )
        throw new Error('missing resume request key');
      return payload.requestId;
    });
    expect(keys[0]).toBe(keys[1]);
    expect(keys[0]).not.toBe(keys[2]);
    expect(
      fake.heard.frames.some(
        (frame) =>
          frame.type === 'conversation.open' || frame.type === 'agent.run',
      ),
    ).toBe(false);
    expect(prompt.said.join('\n')).toContain('resumed');
  });
});

import type { Block } from './markdown.ts';
import type { Tinted } from './tints.ts';
import type { Call } from './trajectory.ts';

/**
 * What is on the screen, described without saying what a screen is.
 *
 * <h2>The seam this file is, and the one it was extended from</h2>
 *
 * <p>`markdown.ts` parses to {@link Block}s — plain data, no units — and two
 * emitters render them: `toDom` for the console, `toTerminal` for a terminal.
 * Neither emitter parses and neither knows the other exists, so a grammar bug
 * is fixed once and a rendering decision is taken where it belongs. That split
 * is the best-working seam in this client, and <b>this file is that same split
 * widened from the prose to the whole screen</b>.
 *
 * <p>So what lives here is every <i>decision</i>: which entries exist, in what
 * order, who said each one, what is played down, what the composer is holding,
 * whether something is running and since when. What does not live here is
 * every part of <i>drawing</i> — which is what the client design's §2 meant by
 * "layout is the part that does not port", and it was right about that even
 * where it was wrong about full-screen.
 *
 * <h2>No colours, no widths, no borders, no spinner frames</h2>
 *
 * <p>Each of those would be this file guessing which surface it was on. A
 * {@link Voice} says <i>who spoke</i>; cyan says how one particular surface
 * draws that, and the console is free to disagree about the colour without
 * disagreeing about the speaker. The same goes for the rest: a border is a
 * terminal's answer to "these belong together", a width is a terminal's answer
 * to "the window is this big", and a window has different answers to both.
 *
 * <h2>Runtime-neutral, like everything else in this directory</h2>
 *
 * <p>No `node:` import, no `document`, no React, no Ink. `logic/tsconfig.json`
 * keeps `types: []` and a DOM-free `lib`, and `src/neutrality.test.ts` reads
 * the source text — which is the guard of record here, since @types/node
 * arriving for `view/` stopped the compiler refusing an explicit `node:`
 * specifier in this directory.
 */

/**
 * Who an entry came from.
 *
 * <p><b>Four and not two</b>, because "the client said it" and "something went
 * wrong" are different things a reader needs to tell apart at a glance, and
 * both are different from the bot. `client` is this program talking about
 * itself — a listing, the help, the banner. `trouble` is a refusal or a fault.
 * `trace` is a run's tool calls and reasoning, drawn as lines of their own —
 * spec 2026-09-29 §4.
 */
export type Voice = 'person' | 'bot' | 'client' | 'trouble' | 'trace';

/** One thing that was said, which is never revised once it is on the screen. */
export interface Entry {
  /**
   * Monotonic within a session, and the key a surface renders by.
   *
   * <b>Not a clock reading.</b> Two entries can land in the same millisecond
   * — a refusal and the line explaining it, most obviously — and a React key
   * that collides drops a row silently.
   */
  readonly at: number;

  readonly voice: Voice;

  /** Already parsed, so both emitters take it as it stands. */
  readonly body: readonly Block[];

  /**
   * A line under the body, played down wherever "played down" means.
   *
   * <p>What a turn cost, mostly. It belongs to the entry rather than being an
   * entry of its own so that a surface can put it where it likes — under the
   * text in a terminal, in a hover in a window — and so that scrolling past
   * an answer does not mean scrolling past its price separately.
   */
  readonly note?: string;

  /** A trace entry's tool and reasoning lines, drawn as they are, with no gutter mark. */
  readonly lines?: readonly Tinted[];
}

/** A trace entry: tool and reasoning lines, written into the scrollback once they are settled. */
export function traced(at: number, lines: readonly Tinted[]): Entry {
  return { at, voice: 'trace', body: [], lines };
}

/** A run in flight. Absent from a {@link Screen} when nothing is running. */
export interface Working {
  /** What is running, as this server named it when the run was submitted. */
  readonly job: string;

  /**
   * `Date.now()` when the run started.
   *
   * <b>A timestamp and not `"4s"`.</b> A terminal recomputes elapsed every
   * hundred milliseconds and a pipe never shows it at all; a formatted string
   * here would put one surface's refresh rate into data the other reads.
   * {@link describeElapsed} in `wording.ts` turns it into the sentence, once,
   * for whoever wants one.
   */
  readonly since: number;

  /**
   * What the model is producing right now, when anybody asked to see it.
   *
   * <p>Absent unless this client sent `job.stream` and the server has sent
   * something since. See {@link Live}: it is a preview, it may have holes in
   * it, and it never becomes the answer.
   */
  readonly live?: Live;

  /**
   * The last lifecycle line, or nothing before the first one arrives.
   *
   * <p><b>This field is the point of the whole type.</b> These lines used to
   * be appended to the transcript, which made `0 steps, 1 model call` read as
   * something the bot had said. A run's progress is a state that gets
   * replaced, not speech that accumulates.
   */
  readonly said?: string;

  /**
   * What the model is doing now, or nothing before anything has said.
   *
   * <p>Beside {@link said} and not instead of it: `said` is the sentence a
   * surface with no animation prints, and this is the state one with an
   * animation draws.
   */
  readonly phase?: Phase;

  /** Calls asked for and not yet answered, oldest first — spec 2026-09-29 §4. */
  readonly calls?: readonly Call[];

  /** When each of {@link calls} was first seen pending, by id; a call absent here counts from {@link since}. */
  readonly callsSince?: Readonly<Record<string, number>>;
}

/** What the person is part-way through typing. */
/**
 * What a model is producing right now, as far as this client has seen it.
 *
 * <h3>It is a preview and never becomes the answer</h3>
 *
 * <p><b>Deltas are droppable by design</b> — the server's token queue drops
 * freely, and that is the property that stops a stream of them evicting the
 * event that says a run ended. So this text may have holes in it, and a client
 * that promoted it to a transcript entry would be showing an answer that
 * silently disagrees with the outcome.
 *
 * <p>What stands in the scrollback when a turn ends is `Outcome.text` from the
 * final model call, every time. This is what is on screen <i>while</i> nobody
 * knows it yet.
 */
export interface Live {
  /** Which stream this is. A model reasons and then answers. */
  readonly part: 'thinking' | 'answer';

  /**
   * The tail of what has arrived, not all of it.
   *
   * <p>Bounded, because reasoning runs to thousands of characters — measured,
   * 6 571 against 1 965 of answer on one call — and a status region shows the
   * most recent output rather than a transcript of the model's mind.
   */
  readonly text: string;
}

export interface Composer {
  readonly typed: string;

  /** The cursor, counted in characters from the start of {@link typed}. */
  readonly at: number;

  /** What Tab last offered, when it had more than one answer. */
  readonly offering: readonly string[];
}

/** The whole of it. */
export interface Screen {
  /** Who is being talked to. A surface decides whether to draw it at all. */
  readonly title: string;

  readonly entries: readonly Entry[];

  /** Absent when nothing is running, which is how a surface knows. */
  readonly working?: Working;

  readonly composer: Composer;
}

/**
 * One entry, with `note` omitted rather than undefined when there is none.
 *
 * <p><b>The conditional spread is the whole reason this is a function.</b>
 * `{ at, voice, body, note }` with an undefined `note` is a <i>present</i> key
 * under `exactOptionalPropertyTypes`, and a renderer asking `'note' in entry`
 * would then draw an empty note line under every entry that never had one. The
 * type would not have caught it; the shape of the object is the thing that is
 * wrong.
 */
export function entered(
  at: number,
  voice: Voice,
  body: readonly Block[],
  note?: string,
): Entry {
  return { at, voice, body, ...(note === undefined ? {} : { note }) };
}

/**
 * How much of a live stream to keep on screen.
 *
 * <p>Two lines of a wide terminal, roughly. A status region is a glance rather
 * than a transcript, and the thing a person wants from it is what the model is
 * saying <i>now</i>.
 */
export const LIVE_TAIL = 240;

/**
 * The live text with one more delta on the end of it, kept to {@link LIVE_TAIL}.
 *
 * <p><b>A change of part starts again rather than appending.</b> Thinking and
 * answering are two streams and running them together would produce a sentence
 * neither of them said — and in practice the moment the answer starts is the
 * moment the reasoning stops being what somebody wants to look at.
 */
export function appended(
  live: Live | undefined,
  part: Live['part'],
  text: string,
): Live {
  const kept =
    live !== undefined && live.part === part ? live.text + text : text;
  return { part, text: kept.slice(-LIVE_TAIL) };
}

/**
 * A screen with nothing said, nothing running and an empty composer.
 *
 * <p>`working` is omitted rather than set, for {@link entered}'s reason.
 */
/**
 * What a model call is doing, as far as this client can see it.
 *
 * <ul>
 * <li>`processing` — a call went out and nothing has come back: queued, or the
 *     endpoint reading the prompt
 * <li>`thinking` — reasoning is arriving
 * <li>`responding` — the answer is arriving
 * <li>`tool` — the model asked for a tool and it is running
 * </ul>
 *
 * <p><b>Seen, not reported.</b> The server sends no phase. A `model_call` event
 * and the first delta of each kind are what the phases are read off, and deltas
 * are droppable — so a call whose thinking was all dropped may go straight from
 * processing to responding. That is a gap in the picture and not a wrong one.
 */
export type Phase =
  | { readonly kind: 'processing' }
  | { readonly kind: 'thinking' }
  | { readonly kind: 'responding' }
  | { readonly kind: 'tool'; readonly tool: string };

/** What moves a phase: an event about the run, or a delta of either kind. */
export type Moved =
  | { readonly kind: 'call' }
  | { readonly kind: 'tool'; readonly tool: string }
  | { readonly kind: 'delta'; readonly part: Live['part'] }
  | { readonly kind: 'other' };

/**
 * The phase after `moved`.
 *
 * <p><b>A delta never moves a run back to processing, and nothing but a call
 * does.</b> A heartbeat, a start, an event this build does not know — each
 * leaves the phase where it was, because none of them says the model is doing
 * something different.
 */
export function phaseAfter(
  was: Phase | undefined,
  moved: Moved,
): Phase | undefined {
  switch (moved.kind) {
    case 'call':
      return { kind: 'processing' };
    case 'tool':
      return { kind: 'tool', tool: moved.tool };
    case 'delta':
      return { kind: moved.part === 'thinking' ? 'thinking' : 'responding' };
    case 'other':
      return was;
  }
}

export function blank(title: string): Screen {
  return { title, entries: [], composer: { typed: '', at: 0, offering: [] } };
}

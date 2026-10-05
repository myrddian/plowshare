import type { Stroke } from '../logic/approval.ts';
import type { Look } from './look.ts';
import type { Entry, Working } from '../logic/screen.ts';
import type { Panel, Viewed, ViewKey } from '../logic/record.ts';
import type { Waiting } from '../logic/session.ts';
import type { ExploreSize, Standing } from '../logic/wording.ts';
import type { ExploreKey } from '../logic/explorer.ts';
import type { Tinted } from '../logic/tints.ts';
import type { DialogKey } from '../logic/caps.ts';
import type { QuestionStroke } from '../logic/questions.ts';

/**
 * What a surface can be told, which is the contract both of them implement.
 *
 * <h2>This is `Prompt` with `say` split in two, and the split is the fix</h2>
 *
 * <p>The old interface had one output method, so everything went through it:
 * an answer, a refusal, a listing, and a run's lifecycle. Which meant
 * `0 steps, 1 model call` landed in the transcript, between two things a person
 * had actually read, and <b>read as something the bot had said</b>.
 *
 * <p>{@link Surface.show} appends and is never revised. {@link Surface.working}
 * <i>replaces</i>. A run's progress is a state, and giving it its own method is
 * what lets a terminal put it in a region of its own and a pipe write it once.
 * No other difference between the two matters as much as that one.
 *
 * <h2>The other four methods keep `Prompt`'s exact shapes</h2>
 *
 * <p>Deliberately, so that porting `converse` is a rename at those call sites
 * rather than a redesign — the orchestration is 500 lines with 1700 lines of
 * tests behind it, and the way to keep those tests meaningful is to not move
 * what they are about.
 *
 * <p><b>{@link Surface.asked} still resolves `undefined` at end of input</b>,
 * which is the one case where "nothing yet" and "nothing ever" have to be
 * represented rather than waited through: a loop awaiting a promise nobody will
 * settle is a session that has to be killed.
 */
export interface Surface {
  /** Append one entry to the transcript. It is never revised. */
  show(entry: Entry): void;

  /**
   * What is running, or `undefined` for nothing.
   *
   * <p><b>Replaced, not appended.</b> See the header — this is the whole
   * difference from {@link Surface.show}, and the reason run lifecycle stops
   * reading as speech.
   *
   * <p>Callers must clear it on <i>every</i> path out of a run — answered,
   * refused, cancelled, dropped. A state left standing is a spinner that
   * never stops.
   */
  working(state: Working | undefined): void;

  /** How many user-inbox items are unread; 0 hides it. */
  unread(count: number): void;

  /**
   * The runs waiting on a person, as the background check last found them;
   * none hides it.
   *
   * <p><b>The whole list and not a count</b>, because a surface with a menu
   * offers them under `/answer ` — which run is which is the point of the
   * menu. A surface without one only counts them.
   */
  waiting(runs: readonly Waiting[]): void;

  /**
   * The names Tab may finish, which are the ones the server declared.
   *
   * <p>Handed over rather than fetched: `agent.list` is already asked at
   * sign-in, and a completer that fetched its own roster would put a round
   * trip under a keypress.
   *
   * @param details a few words about each name, for a surface that shows them
   *     beside the name — the `@` menu does; Tab does not
   */
  completing(
    names: readonly string[],
    details?: Readonly<Record<string, string>>,
    commands?: readonly { readonly name: string; readonly detail: string }[],
  ): void;

  /**
   * What Ctrl-C does, which is the caller's to decide and not a surface's.
   *
   * <p>With no listener, a press ends the input — the old behaviour, kept for
   * the one moment it is still honest: before there is a connection to cancel
   * through, there is nothing to stop, so the key leaves.
   */
  onInterrupt(listener: () => void): void;

  /**
   * The next line somebody enters, or `undefined` once input has ended.
   *
   * @param quietly whether to take the line without showing it. <b>For a
   *     password and nothing else.</b> A terminal that echoed one would put
   *     it on the screen and into the scrollback, where the whole point of
   *     asking for it in the client rather than in an environment variable
   *     was to keep it out of both
   */
  asked(quietly?: boolean): Promise<string | undefined>;

  /**
   * The next key that means something to a question answered a key at a time,
   * with `lines` — the keys and where the answer stands — drawn in a region
   * that is replaced, like {@link Surface.working}, and cleared once the key
   * arrives. `undefined` once input has ended.
   *
   * <p><b>Optional, and whether a surface has it decides how a question is
   * answered.</b> A run asking before a command runs is answered with `o`,
   * `c`, `p`, `d` and the arrows; a surface that reads whole lines has no
   * arrows, so `converse` asks it for lines instead and reads each with
   * `approval.strokesOf`.
   */
  choosing?(lines: readonly string[]): Promise<Stroke | undefined>;

  /**
   * A question for one of the person's runs — a cap, a stuck run, a root's own question — as a
   * modal over the composer: `lines` drawn in its place, and the next key among `keys` (a cap's
   * — `y`, `n`, `a`, `w` — unless told), or esc for later. `undefined` once input ends or
   * {@link Surface.closeDialog} takes it away.
   *
   * <p><b>Optional, and whether a surface has it decides how the question is put</b>: a surface
   * without it is printed the question and its keys, and the next line answers (spec 2026-09-29
   * §2).
   */
  capDialog?(
    lines: readonly string[],
    keys?: readonly DialogKey[],
  ): Promise<DialogKey | undefined>;

  /**
   * A command approval for one of the person's runs — one command, or an acceptance set — as the
   * same modal over the composer, `lines` drawn in its place: the next key that means something
   * to the approval prompt ({@link Surface.choosing}'s strokes — `o`, `c`, `p`, `d`, the arrows,
   * enter, esc), redrawn with the next `lines` a key at a time. `undefined` once input ends or
   * {@link Surface.closeDialog} takes it away.
   *
   * @param again the same question redrawn after one of its own keys: up at once, with none of
   *     the grace a fresh one gives typing that outran it
   *
   * <p><b>Optional</b>: a surface without it is printed the question and its keys, and the next
   * line answers it, as `approval.strokesOf` reads a line.
   */
  approvalDialog?(
    lines: readonly string[],
    again?: boolean,
  ): Promise<Stroke | undefined>;

  /**
   * A question with options from one of the person's runs (spec 2026-09-29-orchestration-studio
   * §2.5), as the same modal over the composer: the next key that means something to it —
   * `questions.ts`'s strokes — redrawn with the next `lines` a key at a time. `undefined` once
   * input ends or {@link Surface.closeDialog} takes it away.
   *
   * @param again the same question redrawn after one of its own keys: up at once, with none of
   *     the grace a fresh one gives typing that outran it
   *
   * <p><b>Optional</b>: a surface without it is shown the question in words, as today, and it is
   * answered in words with `/answer`, which the server still takes.
   */
  questionDialog?(
    lines: readonly string[],
    again?: boolean,
  ): Promise<QuestionStroke | undefined>;

  /** Takes an open dialog away unanswered: its question was settled elsewhere, or a line
   *  arrived that is not its answer. */
  closeDialog?(): void;

  /**
   * Puts `text` in the composer, the cursor after it, for the person to finish and send — a
   * question dialog's `r`, which leaves `/answer <id> ` there. Optional: a surface that reads
   * lines is printed the command to type instead.
   */
  prefill?(text: string): void;

  /**
   * How many rows a dialog's lines may take, its edge aside, so a long question is cut to what
   * the terminal has room for. A surface without it has room for the whole question.
   */
  dialogRoom?(): number;

  /** Puts the surface down. Outstanding asks answer `undefined`. */
  close(): void;

  /**
   * Listen for the person asking for the next tool-line density (Ctrl-T) — spec 2026-09-29 §4.
   * Optional: a surface with no keys never asks.
   */
  density?(listener: () => void): void;

  /** How many columns a line has to fit in, for the tool lines worded to fit. 80 when absent. */
  columns?(): number;

  /**
   * Draw from now on in `look`. What is already in the scrollback keeps the
   * colours it was written in. Optional: a surface with no colours ignores it.
   */
  restyle?(look: Look): void;

  /**
   * Who is being talked to and how full their context is, or `undefined`
   * before anybody is.
   *
   * <p><b>Optional, and whether a surface has it decides whether it is
   * asked.</b> The load costs a `conversation.context` round trip after each
   * turn, and a surface with no status line would be paying for a number it
   * throws away.
   */
  status?(standing: Standing | undefined): void;

  /**
   * The runs going now — spec 2026-09-28, the orchestration record §4 — or `undefined` when none
   * is, which hides it. <b>Replaced, like {@link Surface.working}</b>.
   *
   * <p>Optional, and whether a surface has it decides whether it is asked: the reads behind a
   * panel are a listing and a status per live run, and a surface with nowhere to draw one would
   * be paying for lines it throws away.
   */
  panel?(panel: Panel | undefined): void;

  /**
   * The `/watch` viewer, replaced on every call; `undefined` puts it away. While it is up it is
   * drawn in place of the status region and the composer, and it takes the keys.
   *
   * <p><b>Not the alternate screen.</b> It fills the region that is redrawn; the scrollback above
   * it stays the terminal's. What is shown meanwhile is held, and written when it goes away —
   * the chat is not to be read one row at a time above a viewer.
   *
   * <p>Optional, with {@link Surface.viewKey}: a surface without both prints `/watch` once.
   */
  view?(viewed: Viewed | undefined): void;

  /** The next key the viewer means something by, while it is up; `undefined` once input ends. */
  viewKey?(): Promise<ViewKey | undefined>;

  /**
   * How many of the record's rows the viewer shows at once, as it is drawn now — how far up a
   * key can scroll before it only empties the screen. A surface without it scrolls a row at a
   * time to the oldest row held.
   */
  viewRoom?(): number;

  /**
   * How many body lines the viewer has room for when it draws `viewed` — whatever its body holds,
   * since the room is what its header, keys and refusal leave. Asked before the body is worded, so
   * only what will be on screen is; a surface without it is handed the whole body.
   */
  viewBody?(viewed: Viewed): number;

  /**
   * Draw the explorer's lines in place of the status region and the composer, replaced on every
   * call; `undefined` takes it down — spec 2026-09-29 §5. Held like {@link Surface.view}: what is
   * shown meanwhile is written when it goes away.
   *
   * @param typing whether a search is being typed, when printable keys are the query
   */
  explore?(lines: readonly Tinted[] | undefined, typing?: boolean): void;
  /** The next key pressed in the explorer, or undefined when the surface ends. */
  exploreKey?(): Promise<ExploreKey | undefined>;
  /** How big the explorer may be. */
  exploreSize?(): ExploreSize;

  /**
   * Listen for the surface changing size, so what was fitted to it can be fitted again. Returns
   * the unsubscribe. Optional: a surface with no size never changes it.
   */
  onResize?(listener: () => void): () => void;
}

/** A {@link Surface} that keeps what it was told, for tests to read back. */
export interface Recording extends Surface {
  readonly shown: readonly Entry[];

  /** Every state, in order, `undefined`s included. */
  readonly states: readonly (Working | undefined)[];

  /** Every list of waiting runs it was handed, in order. */
  readonly waited: readonly (readonly Waiting[])[];

  /** Hands a line to whoever is asking, or queues it for the next ask. */
  answer(line: string | undefined): void;

  /** A Ctrl-C, or the key named: `'ctrl-t'` asks for the next tool-line density. */
  press(key?: 'ctrl-c' | 'ctrl-t'): void;
}

/**
 * A surface that records instead of drawing.
 *
 * <p><b>Deliberately not a stub.</b> `asked` queues in both directions, exactly
 * as the real surfaces do, because the defect this exists to catch is a turn
 * read before its line arrived — and a double that only answered asks already
 * waiting would pass that every time. This client has been bitten three times
 * by fakes that accepted whatever they were given; a double that is laxer than
 * the thing it stands in for tests the caller against its own assumptions.
 */
export function recording(): Recording {
  const shown: Entry[] = [];
  const states: (Working | undefined)[] = [];
  const waited: (readonly Waiting[])[] = [];
  /** Lines answered faster than they were asked for. */
  const typed: (string | undefined)[] = [];
  /** Asks made before there was a line to answer them with. */
  const waiting: ((line: string | undefined) => void)[] = [];
  let interrupted: (() => void) | undefined;
  let densityAsked: (() => void) | undefined;
  let ended = false;

  const close = (): void => {
    ended = true;
    while (waiting.length > 0) {
      waiting.shift()?.(undefined);
    }
  };

  return {
    shown,
    states,
    waited,

    show(entry: Entry): void {
      shown.push(entry);
    },

    working(state: Working | undefined): void {
      states.push(state);
    },

    unread(): void {
      // Nothing here draws a hint line to prefix.
    },

    waiting(runs: readonly Waiting[]): void {
      waited.push(runs);
    },

    completing(): void {
      // Nothing here has a keyboard to complete against.
    },

    onInterrupt(listener: () => void): void {
      interrupted = listener;
    },

    density(listener: () => void): void {
      densityAsked = listener;
    },

    columns(): number {
      return 80;
    },

    asked(): Promise<string | undefined> {
      if (ended) {
        return Promise.resolve(undefined);
      }
      if (typed.length > 0) {
        return Promise.resolve(typed.shift());
      }
      return new Promise((settle) => {
        waiting.push(settle);
      });
    },

    close,

    answer(line: string | undefined): void {
      const asker = waiting.shift();
      if (asker === undefined) {
        typed.push(line);
      } else {
        asker(line);
      }
    },

    press(key: 'ctrl-c' | 'ctrl-t' = 'ctrl-c'): void {
      if (key === 'ctrl-t') {
        densityAsked?.();
        return;
      }
      const listener = interrupted;
      if (listener === undefined) {
        close();
        return;
      }
      listener();
    },
  };
}

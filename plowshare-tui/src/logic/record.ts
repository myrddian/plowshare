import { LIVE_STATES } from 'plowshare-client-ts/operations/activity';
export {
  ORCHESTRATION_RECORDED,
  LIVE_STATES,
  recordedOf,
  LIVE_LISTING,
  listingLive,
  liveRunsOf,
} from 'plowshare-client-ts/operations/activity';
export type { RecordedPush } from 'plowshare-client-ts/operations/activity';
import type { Run, RunStage } from './session.ts';
import { QUESTION_ASKED } from 'plowshare-client-ts/operations/records';
import type {
  Recorded,
  RecordPage,
} from 'plowshare-client-ts/operations/records';
export {
  ORCHESTRATION_RECORD,
  TOOL_CALL,
  QUESTION_ASKED,
  RECORD_TAIL,
  readingRecordTail,
  readingRecordEarlier,
  readingRecordAfter,
  readingToolLine,
  recordPageOf,
  readingQuestion,
} from 'plowshare-client-ts/operations/records';
export type {
  Recorded,
  RecordPage,
} from 'plowshare-client-ts/operations/records';
import type { Tinted } from './tints.ts';
import { outcomeClass } from './trajectory.ts';

/*
 * THE ORCHESTRATION RECORD, AS THIS CLIENT READS IT. Spec 2026-09-28, the orchestration record.
 *
 * The harness keeps one human-readable story per run tree — milestones, and a line per tool
 * call — and pushes `orchestration.recorded {root, through}` as it grows. This module is the
 * wire (the asks, the readers) and the two views' state (the panel's trees, the viewer's
 * window); the words are `wording.ts`'s, and the drawing the surfaces'.
 */

/** Every kind but the tool lines, in `RecordKind`'s order. */
export const MILESTONE_KINDS: readonly string[] = [
  'run_started',
  'run_ended',
  'run_resumed',
  'stage_moved',
  'phase_started',
  'phase_ended',
  'check_ran',
  'approval_asked',
  'approval_answered',
  'question_asked',
  'question_answered',
  'stalled',
  'call_failure',
  'delegated',
  'delegate_returned',
  'acceptance_ran',
  'cap_continued',
  'concern',
];

/** How many milestones the panel shows per tree. */
export const PANEL_MILESTONES = 3;

/**
 * How far back from the end a tool line still waiting on its outcome is re-read. A line settled
 * further up — a long delegation's `agent_run` — is named by its push (`settled`) and read alone.
 */
export const OPEN_WINDOW = 50;

/**
 * The most rows a viewer holds. A tree's record is a line per tool call and a viewer left open on
 * a busy one would otherwise grow for as long as it is open. Past this, following the end, the
 * oldest are let go and `e` reads them back; scrolled up, it keeps this many around the screen —
 * see {@link held} — and following again reads the end afresh.
 */
export const MOST_HELD = 2000;

/** How far a page key scrolls the viewer. */
export const PAGE_LINES = 10;

/** A live phase under a root: a child run and its stages. */
export interface Phase {
  readonly run: Run;
  readonly stages: readonly RunStage[];
}

/** One live tree as the panel and the viewer's header show it. */
export interface Tree {
  readonly run: Run;
  readonly stages: readonly RunStage[];
  readonly phases: readonly Phase[];
  /** The latest tool line. */
  readonly activity?: Recorded;
  readonly milestones: readonly Recorded[];
  /** The latest question asked in the tree, while its root or a phase is asking; drawn whole. */
  readonly question?: Recorded;
}

/** The panel: its lines for a surface that redraws, and what settles for one that cannot. */
export interface Panel {
  readonly lines: readonly Tinted[];
  /** Without elapsed times and without the activity line — what a pipe prints on a change. */
  readonly settled: readonly string[];
}

/** What a key means to the viewer. */
export type ViewKey =
  | 'up'
  | 'down'
  | 'pageUp'
  | 'pageDown'
  | 'follow'
  | 'earlier'
  | 'tools'
  | 'close'
  | 'nextFailure'
  | 'prevFailure'
  | 'nextMark'
  | 'prevMark'
  | 'open';

/** The viewer's state: what it holds, how far up it is scrolled, and what it reads. */
export interface Watch {
  readonly root: string;
  readonly rows: readonly Recorded[];
  readonly through: number;
  readonly more: boolean;
  /** Whether tool activity is read beside the milestones. */
  readonly tools: boolean;
  /** How many of the newest rows are scrolled out below; 0 follows the end. */
  readonly back: number;
  /**
   * The cursor: the ordinal of the row the keys move, jump to and open. Absent, it is the bottom
   * row on screen — the last, while the viewer follows the end. An ordinal and not an index, so
   * it stays on its row when an earlier page lands above or a push below.
   */
  readonly at?: number;
  /**
   * Whether rows after the last it holds were let go: a push came while it was scrolled up and
   * it already held {@link MOST_HELD}. Nothing is read below them — it would leave a gap — and
   * following the end again reads the end afresh (`then: 'latest'`). Absent when nothing was.
   */
  readonly cut?: boolean;
}

/** The viewer as lines: a header, the body (bottom-aligned by the surface), a footer of keys. */
export interface Viewed {
  readonly head: readonly Tinted[];
  readonly body: readonly Tinted[];
  readonly foot: string;
  /** Why the last key's read did nothing — the server's refusal — until the next key. */
  readonly said?: string;
}

/** Two readings as one, by ordinal, `fresh`'s copy of a row winning — a settled tool line. */
export function merged(
  rows: readonly Recorded[],
  fresh: readonly Recorded[],
): Recorded[] {
  const byOrdinal = new Map<number, Recorded>();
  for (const each of rows) {
    byOrdinal.set(each.ordinal, each);
  }
  for (const each of fresh) {
    byOrdinal.set(each.ordinal, each);
  }
  return [...byOrdinal.values()].sort((a, b) => a.ordinal - b.ordinal);
}

/**
 * The `after` to re-read from on a push: before the oldest tool line near the end still waiting
 * on its outcome — the one row the server writes twice — or `through` when none is.
 */
export function followFrom(rows: readonly Recorded[], through: number): number {
  const open = rows
    .filter(
      (each) =>
        each.tool &&
        each.detail === undefined &&
        each.ordinal > through - OPEN_WINDOW,
    )
    .map((each) => each.ordinal);
  return open.length === 0 ? through : Math.min(...open) - 1;
}

/**
 * Which of the settled lines a push named the viewer has to read alone: the ones it holds still
 * open, and at or before `from` — anything after it the push's forward read brings anyway.
 */
export function settledToRead(
  watch: Watch,
  settled: Iterable<number>,
  from: number,
): number[] {
  const open = new Set(
    watch.rows
      .filter((row) => row.tool && row.detail === undefined)
      .map((row) => row.ordinal),
  );
  return [...new Set(settled)]
    .filter((ordinal) => ordinal <= from && open.has(ordinal))
    .sort((a, b) => a - b);
}

/** A tool line read again, in its place; a row the viewer does not hold is not put in. */
export function watchSettled(watch: Watch, row: Recorded): Watch {
  return watch.rows.some((each) => each.ordinal === row.ordinal)
    ? {
        ...watch,
        rows: watch.rows.map((each) =>
          each.ordinal === row.ordinal ? row : each,
        ),
      }
    : watch;
}

/**
 * Which tool lines will never be told their outcome: those of a run that has ended — the root,
 * once it is no longer live, or a phase that is not among its live ones. A line left open by a
 * server that restarted under the call, or a run stopped mid-call, would otherwise read `…` for
 * ever. Nothing is judged without the tree, whose status is what says a run ended.
 */
export function outcomeLost(
  tree: Tree | undefined,
): (row: Recorded) => boolean {
  if (tree === undefined) {
    return () => false;
  }
  const rootLive = LIVE_STATES.includes(tree.run.state);
  const live = new Set(
    tree.phases
      .filter((phase) => LIVE_STATES.includes(phase.run.state))
      .map((phase) => phase.run.id),
  );
  if (rootLive) {
    live.add(tree.run.id);
  }
  return (row) =>
    row.tool && row.detail === undefined && (!rootLive || !live.has(row.run));
}

/** Whether a tree waits on a person's answer: its root, or one of its live phases, is asking. */
export function treeAsking(tree: Pick<Tree, 'run' | 'phases'>): boolean {
  return (
    tree.run.state === 'asking' ||
    tree.phases.some((phase) => phase.run.state === 'asking')
  );
}

/** The latest question among `rows`, or nothing when none of them is one. */
export function latestQuestion(
  rows: readonly Recorded[],
): Recorded | undefined {
  return rows.filter((each) => each.kind === QUESTION_ASKED).at(-1);
}

/** The live roots of a listing, in its order (newest first). */
export function liveRoots(runs: readonly Run[]): Run[] {
  return runs.filter(
    (run) => run.parent === undefined && LIVE_STATES.includes(run.state),
  );
}

/**
 * What an `orchestration.changed` costs the panel: the one tree the run is in — its root or one
 * of its live phases — read again; a listing, for a run in no tree that is live now, which may be
 * a new root (or a new phase, which the push cannot tell apart); nothing for any other run.
 */
export function panelReads(
  changed: { readonly id: string; readonly state: string },
  trees: readonly Tree[],
): { readonly root: string } | 'list' | undefined {
  const tree = trees.find(
    (each) =>
      each.run.id === changed.id ||
      each.phases.some((phase) => phase.run.id === changed.id),
  );
  if (tree !== undefined) {
    return { root: tree.run.id };
  }
  return LIVE_STATES.includes(changed.state) ? 'list' : undefined;
}

/** A viewer opened on a page. */
export function watching(page: RecordPage, tools: boolean): Watch {
  return held({
    root: page.root,
    rows: page.rows,
    through: page.through,
    more: page.more,
    tools,
    back: 0,
  });
}

/** Whether the viewer follows the end: scrolled to the bottom, with no cursor of its own. */
export function followingEnd(watch: Watch): boolean {
  return watch.back === 0 && watch.at === undefined;
}

/**
 * At most {@link MOST_HELD} rows, and `more` when any before them were let go, since they are
 * still there to be read.
 *
 * <p><b>Following the end, the oldest go.</b> <b>Scrolled up, the ones furthest from the
 * screen</b>: it keeps half of them above the higher of its bottom row and the cursor — every
 * screen is far shorter than that — and the rest below; so the oldest go while the screen is near
 * the end, and the newest when it is far up, which {@link Watch.cut} then says. Until
 * 2026-09-30 a viewer scrolled up, or with a cursor, held every row pushed while it was open.
 */
function held(watch: Watch): Watch {
  const count = watch.rows.length;
  const over = count - MOST_HELD;
  if (over <= 0) {
    return watch;
  }
  if (followingEnd(watch)) {
    return { ...watch, rows: watch.rows.slice(over), more: true };
  }
  const bottom = count - 1 - watch.back;
  const highest = Math.min(bottom, cursorIndex(watch));
  const start = Math.max(0, Math.min(highest - MOST_HELD / 2, over));
  const end = start + MOST_HELD;
  const below = count - end;
  return {
    ...watch,
    rows: watch.rows.slice(start, end),
    more: watch.more || start > 0,
    back: Math.max(0, watch.back - below),
    ...(below > 0 || watch.cut === true ? { cut: true } : {}),
  };
}

/** Following the end again: scrolled to the bottom, the cursor let go — and still cut short when
 *  it was, until the end is read afresh. */
function following(watch: Watch): Watch {
  const { root, rows, through, more, tools, cut } = watch;
  return held({
    root,
    rows,
    through,
    more,
    tools,
    back: 0,
    ...(cut === true ? { cut } : {}),
  });
}

/** Where the cursor is among the rows: its ordinal's row, or the bottom row on screen. */
function cursorIndex(watch: Watch): number {
  const at = watch.at;
  const last = watch.rows.length - 1;
  if (at === undefined) {
    return Math.max(0, Math.min(last, last - watch.back));
  }
  const found = watch.rows.findIndex((each) => each.ordinal >= at);
  return found === -1 ? last : found;
}

/** The row the cursor is on — what Enter opens — or nothing when the viewer holds none. */
export function watchCursor(watch: Watch): Recorded | undefined {
  return watch.rows[cursorIndex(watch)];
}

/** The kinds a viewer reads: every kind with tool activity, the milestones without it. */
export function kindsOf(watch: {
  readonly tools: boolean;
}): readonly string[] | undefined {
  return watch.tools ? undefined : MILESTONE_KINDS;
}

/** The oldest ordinal a viewer holds, which an earlier read goes back from. */
export function oldestOf(watch: Watch): number | undefined {
  return watch.rows[0]?.ordinal;
}

/** The last index before `below` whose row is `wanted`, or -1: `findLastIndex` is not ES2022. */
function lastBefore(
  rows: readonly Recorded[],
  below: number,
  wanted: (row: Recorded) => boolean,
): number {
  for (let at = Math.min(below, rows.length) - 1; at >= 0; at -= 1) {
    const row = rows[at];
    if (row !== undefined && wanted(row)) {
      return at;
    }
  }
  return -1;
}

/**
 * What a key does to the viewer, and what has to happen next — `latest` when it follows the end
 * again after rows below were let go ({@link Watch.cut}). `room` is how many lines the viewer
 * shows its rows in, and `height` how many of them a row is drawn in — one, but for a row with a
 * body under it (`wording.ts`'s `watchRowHeight`); left out, every row is one line.
 *
 * <p><b>The keys move a cursor</b> ({@link Watch.at}), and the screen scrolls only as far as keeps
 * it on screen: ↑↓ and the page keys move it by a row or a page; `x`/`X` to the next or previous
 * failed tool line and `[`/`]` to the next or previous milestone, centred when it was off screen;
 * Enter opens its row. Past the last row, a move or a jump follows the end again.
 *
 * <p><b>Scrolled up until the oldest row it holds is on screen, it stops there</b> — further would
 * only empty the bottom of the screen — and a move up to the oldest row, or a jump that finds
 * nothing above it, asks for earlier rows when there are more.
 */
export function watchKeyed(
  watch: Watch,
  key: ViewKey,
  room = 1,
  height?: (row: Recorded) => number,
): {
  readonly watch: Watch;
  readonly then: 'draw' | 'earlier' | 'refilter' | 'close' | 'open' | 'latest';
} {
  const keyed = watchMoved(watch, key, room, height);
  // BACK AT THE END OF A VIEWER CUT SHORT: what lay between what it holds and the end was let
  // go, so the end is read afresh — the tail it opened with.
  return keyed.then === 'draw' &&
    keyed.watch.cut === true &&
    followingEnd(keyed.watch)
    ? { watch: keyed.watch, then: 'latest' }
    : keyed;
}

function watchMoved(
  watch: Watch,
  key: ViewKey,
  room: number,
  height?: (row: Recorded) => number,
): {
  readonly watch: Watch;
  readonly then: 'draw' | 'earlier' | 'refilter' | 'close' | 'open';
} {
  const count = watch.rows.length;
  const span = Math.max(1, room);
  const cursor = cursorIndex(watch);
  /** How many lines row `index` is drawn in. */
  const tall = (index: number): number => {
    const row = watch.rows[index];
    return row === undefined || height === undefined
      ? 1
      : Math.max(1, height(row));
  };
  /** The top row on screen when row `bottom` is at its foot: as many above it as fit. */
  const topOf = (bottom: number): number => {
    let used = tall(bottom);
    let top = bottom;
    while (top > 0 && used + tall(top - 1) <= span) {
      top -= 1;
      used += tall(top);
    }
    return top;
  };
  /** The bottom row on screen when row `top` heads `lines` of it: as many below it as fit. */
  const bottomOf = (top: number, lines: number): number => {
    let used = tall(top);
    let bottom = top;
    while (bottom < count - 1 && used + tall(bottom + 1) <= lines) {
      bottom += 1;
      used += tall(bottom);
    }
    return bottom;
  };
  // As far up as it goes: the oldest row at the top of the screen.
  const highestBack =
    count === 0 ? 0 : Math.max(0, count - 1 - bottomOf(0, span));
  /** The cursor on row `index`, the screen scrolled to show it — centred, for a jump. */
  const place = (index: number, centred: boolean): Watch => {
    const row = watch.rows[index];
    if (index >= count - 1 || row === undefined) {
      return following(watch);
    }
    const highest = count - 1 - watch.back;
    const lowest = topOf(Math.max(0, highest));
    const back =
      index >= lowest && index <= highest
        ? watch.back
        : centred
          ? count - 1 - bottomOf(index, Math.floor((span - 1) / 2) + 1)
          : index > highest
            ? count - 1 - index
            : count - 1 - bottomOf(index, span);
    return {
      ...watch,
      at: row.ordinal,
      back: Math.max(0, Math.min(highestBack, back)),
    };
  };
  const moved = (
    by: number,
  ): { readonly watch: Watch; readonly then: 'draw' | 'earlier' } => {
    if (count === 0) {
      return { watch, then: by < 0 && watch.more ? 'earlier' : 'draw' };
    }
    const index = Math.max(0, Math.min(count - 1, cursor + by));
    return {
      watch: place(index, false),
      then: by < 0 && index === 0 && watch.more ? 'earlier' : 'draw',
    };
  };
  /** The nearest row above the cursor that is `wanted`; none held, the earlier rows are read. */
  const above = (
    wanted: (row: Recorded) => boolean,
  ): { readonly watch: Watch; readonly then: 'draw' | 'earlier' } => {
    const index = lastBefore(watch.rows, cursor, wanted);
    return index === -1
      ? { watch, then: watch.more ? 'earlier' : 'draw' }
      : { watch: place(index, true), then: 'draw' };
  };
  /** The nearest row below the cursor that is `wanted`; none, the end. */
  const below = (
    wanted: (row: Recorded) => boolean,
  ): { readonly watch: Watch; readonly then: 'draw' } => {
    const index = watch.rows.findIndex(
      (each, at) => at > cursor && wanted(each),
    );
    return {
      watch: index === -1 ? following(watch) : place(index, true),
      then: 'draw',
    };
  };
  const failing = (each: Recorded): boolean =>
    each.tool &&
    each.detail !== undefined &&
    outcomeClass(each.detail) === 'fail';
  const marking = (each: Recorded): boolean => !each.tool;
  switch (key) {
    case 'up':
      return moved(-1);
    case 'down':
      return moved(1);
    case 'pageUp':
      return moved(-PAGE_LINES);
    case 'pageDown':
      return moved(PAGE_LINES);
    case 'follow':
      return { watch: following(watch), then: 'draw' };
    case 'earlier':
      return { watch, then: watch.more ? 'earlier' : 'draw' };
    case 'tools':
      return {
        watch: { ...following(watch), tools: !watch.tools },
        then: 'refilter',
      };
    case 'close':
      return { watch, then: 'close' };
    case 'prevFailure':
      return above(failing);
    case 'nextFailure':
      return below(failing);
    case 'prevMark':
      return above(marking);
    case 'nextMark':
      return below(marking);
    case 'open':
      return { watch, then: 'open' };
  }
}

/**
 * An earlier page, put above what the viewer holds — and, scrolled up, as many of the newest let
 * go as it brought, past {@link MOST_HELD}. Following the end, nothing is: the next push lets the
 * oldest go, as it would have anyway.
 */
export function watchEarlier(watch: Watch, page: RecordPage): Watch {
  const read = {
    ...watch,
    rows: merged(page.rows, watch.rows),
    more: page.more,
  };
  return followingEnd(read) ? read : held(read);
}

/**
 * What a push brought, put below — keeping a scrolled-up viewer, or one with a cursor, where it
 * was. A viewer {@link Watch.cut} short takes only the rows it holds (a tool line settled): one
 * below them would leave a gap where the rows it let go were.
 */
export function watchGrew(watch: Watch, page: RecordPage): Watch {
  const newest = watch.rows.at(-1)?.ordinal ?? 0;
  const through = Math.max(watch.through, page.through);
  if (watch.cut === true) {
    const fresh = page.rows.filter((each) => each.ordinal <= newest);
    return fresh.length === 0
      ? { ...watch, through }
      : { ...watch, rows: merged(watch.rows, fresh), through };
  }
  const rows = merged(watch.rows, page.rows);
  const below = rows.filter((each) => each.ordinal > newest).length;
  return held({
    ...watch,
    rows,
    through,
    back: followingEnd(watch) ? 0 : watch.back + below,
  });
}

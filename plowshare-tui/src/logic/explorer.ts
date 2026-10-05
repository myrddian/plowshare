import type { BackPage, Entry } from './session.ts';
import {
  callStanding,
  outcomeClass,
  stepKey,
  stepsOf,
  turnsOf,
  type Step,
  type Turn,
} from './trajectory.ts';

/**
 * The explorer over one conversation's log, and down into the logs of the agents it handed work
 * to: spec 2026-09-29 §5-6. Two views of one read — `trajectory` (the run as it unfolded) and
 * `log` (every row, flat) — under one cursor. The cursor is held as a row's KEY, never an index,
 * so an earlier page, a grown log, a fold or a switch of view never moves what is selected.
 */

export type ExploreView = 'trajectory' | 'log';
export type Pane = 'payload' | 'result' | 'text' | 'timing';

export type Row =
  | { readonly kind: 'step'; readonly step: Step }
  | { readonly kind: 'entry'; readonly entry: Entry }
  | { readonly kind: 'turn'; readonly turn: Turn };

export interface Level {
  readonly conversation: string;
  /** What the breadcrumb calls it: the agent that ran in it. */
  readonly label: string;
  /** Ascending by ordinal, no ordinal twice. */
  readonly entries: readonly Entry[];
  readonly through: number;
  /** How many rows the log holds, when the server said. */
  readonly total?: number;
  readonly oldest?: number;
  readonly more: boolean;
  /** The key of the row under the cursor; absent means the last row. */
  readonly at?: string;
  readonly follow: boolean;
}

export type Note =
  | 'noFailureBelow'
  | 'noFailureAbove'
  | 'noMatch'
  | 'notADoor'
  | 'noLink'
  | 'atTop';

export interface Explorer {
  /** The root first, the level on screen last. */
  readonly levels: readonly Level[];
  readonly view: ExploreView;
  readonly folded: boolean;
  readonly focus: 'list' | 'inspector';
  readonly pane: Pane;
  readonly scroll: number;
  readonly search?: { readonly query: string; readonly typing: boolean };
  /** Why the last key did nothing, until the next key. */
  readonly note?: Note;
}

export type ExploreKey =
  | 'up'
  | 'down'
  | 'pageUp'
  | 'pageDown'
  | 'top'
  | 'bottom'
  | 'prevTurn'
  | 'nextTurn'
  | 'nextFailure'
  | 'prevFailure'
  | 'inspect'
  | 'tab'
  | 'descend'
  | 'ascend'
  | 'search'
  | 'nextMatch'
  | 'prevMatch'
  | 'refresh'
  | 'more'
  | 'fold'
  | 'view'
  | 'follow'
  | 'close'
  | 'escape'
  | 'enter'
  | 'erase'
  | { readonly typed: string };

export type Then = 'draw' | 'earlier' | 'descend' | 'ascend' | 'close';

export type Land = 'end' | 'failure' | { readonly agent: string };

export function rowKey(row: Row): string {
  switch (row.kind) {
    case 'step':
      return stepKey(row.step);
    case 'entry':
      return `#${row.entry.ordinal}`;
    case 'turn':
      return `t${row.turn.ordinal}`;
  }
}

export function turnOfRow(row: Row): number {
  switch (row.kind) {
    case 'step':
      return row.step.turn;
    case 'entry':
      return row.entry.turnOrdinal;
    case 'turn':
      return row.turn.ordinal;
  }
}

/** Every ordinal a row stands for, so a switch of view can find the same place. */
function ordinalsOf(row: Row): number[] {
  switch (row.kind) {
    case 'step':
      return row.step.kind === 'call' && row.step.result !== undefined
        ? [row.step.ordinal, row.step.result.ordinal]
        : [row.step.ordinal];
    case 'entry':
      return [row.entry.ordinal];
    case 'turn':
      return row.turn.steps.map((step) => step.ordinal);
  }
}

const FAILING_KINDS: ReadonlySet<string> = new Set([
  'attempt_failed',
  'refusal',
]);

export function isFailure(row: Row): boolean {
  switch (row.kind) {
    case 'step':
      return row.step.kind === 'call'
        ? callStanding(row.step) === 'fail'
        : FAILING_KINDS.has(row.step.entry.kind);
    case 'entry':
      return (
        FAILING_KINDS.has(row.entry.kind) ||
        (row.entry.kind === 'tool_result' &&
          outcomeClass(row.entry.outcome) === 'fail')
      );
    case 'turn':
      return row.turn.failed > 0;
  }
}

/** The text a search looks through, lower-cased. */
function rowText(row: Row): string {
  switch (row.kind) {
    case 'step': {
      const step = row.step;
      const own =
        step.kind === 'call'
          ? [
              step.tool,
              step.salient ?? '',
              step.result?.text ?? '',
              step.opened?.agent ?? '',
            ]
          : [step.entry.text ?? ''];
      return own.join('\n').toLowerCase();
    }
    case 'entry':
      return `${row.entry.kind}\n${row.entry.text ?? ''}`.toLowerCase();
    case 'turn':
      return row.turn.steps
        .map((step) => rowText({ kind: 'step', step }))
        .join('\n');
  }
}

export function panesOf(row: Row | undefined): Pane[] {
  return row?.kind === 'step' && row.step.kind === 'call'
    ? ['result', 'payload', 'timing']
    : ['text', 'timing'];
}

export function current(ex: Explorer): Level {
  const level = ex.levels.at(-1);
  if (level === undefined) {
    throw new RangeError('an explorer always has a level');
  }
  return level;
}

function rowsOfLevel(level: Level, view: ExploreView, folded: boolean): Row[] {
  if (view === 'log') {
    return level.entries.map((entry) => ({ kind: 'entry', entry }));
  }
  const steps = stepsOf(level.entries);
  return folded
    ? turnsOf(steps).map((turn) => ({ kind: 'turn', turn }))
    : steps.map((step) => ({ kind: 'step', step }));
}

export function rowsOf(ex: Explorer): Row[] {
  return rowsOfLevel(current(ex), ex.view, ex.folded);
}

function indexIn(rows: readonly Row[], at: string | undefined): number {
  if (at === undefined) {
    return rows.length - 1;
  }
  const found = rows.findIndex((row) => rowKey(row) === at);
  return found === -1 ? rows.length - 1 : found;
}

export function cursorOf(ex: Explorer): number {
  return Math.max(0, indexIn(rowsOf(ex), current(ex).at));
}

export function selectedRow(ex: Explorer): Row | undefined {
  return rowsOf(ex)[cursorOf(ex)];
}

function merged(entries: readonly Entry[], fresh: readonly Entry[]): Entry[] {
  const byOrdinal = new Map<number, Entry>();
  for (const entry of [...entries, ...fresh]) {
    byOrdinal.set(entry.ordinal, entry);
  }
  return [...byOrdinal.values()].sort((a, b) => a.ordinal - b.ordinal);
}

function landed(
  entries: readonly Entry[],
  view: ExploreView,
  land: Land | undefined,
): string | undefined {
  if (land === undefined || land === 'end') {
    return undefined;
  }
  const rows = rowsOfLevel(
    {
      conversation: '',
      label: '',
      entries,
      through: 0,
      more: false,
      follow: false,
    },
    view,
    false,
  );
  // A failure is landed on only when it is in the latest turn: an old one, long since dealt
  // with, is not why a person opens the log now, and the last row is.
  const latest = rows.reduce(
    (most, row) => Math.max(most, turnOfRow(row)),
    Number.NEGATIVE_INFINITY,
  );
  const wanted =
    land === 'failure'
      ? rows.filter((row) => isFailure(row) && turnOfRow(row) === latest)
      : rows.filter(
          (row) =>
            row.kind === 'step' &&
            row.step.kind === 'call' &&
            row.step.opened?.agent === land.agent,
        );
  const row = wanted.at(-1);
  return row === undefined ? undefined : rowKey(row);
}

export function levelOf(
  conversation: string,
  label: string,
  page: BackPage,
  view: ExploreView,
  land?: Land,
): Level {
  const entries = merged([], page.entries);
  const at = landed(entries, view, land);
  return {
    conversation,
    label,
    entries,
    through: page.through,
    ...(page.total === undefined ? {} : { total: page.total }),
    ...(page.oldest === undefined ? {} : { oldest: page.oldest }),
    more: page.more,
    ...(at === undefined ? {} : { at }),
    follow: at === undefined,
  };
}

export function explorerOpened(level: Level, view: ExploreView): Explorer {
  return {
    levels: [level],
    view,
    folded: false,
    focus: 'list',
    pane: 'result',
    scroll: 0,
  };
}

function withLevel(ex: Explorer, level: Level): Explorer {
  return { ...ex, levels: [...ex.levels.slice(0, -1), level] };
}

/** The cursor on row `index` (clamped); moving anywhere but the last row stops following. */
function movedTo(ex: Explorer, index: number): Explorer {
  const rows = rowsOf(ex);
  const row = rows[Math.max(0, Math.min(rows.length - 1, index))];
  const { at: _was, ...level } = current(ex);
  const { note: _note, ...rest } = ex;
  return {
    ...withLevel(rest, {
      ...level,
      ...(row === undefined ? {} : { at: rowKey(row) }),
      follow: false,
    }),
    scroll: 0,
    pane: panesOf(row)[0] ?? 'text',
  };
}

function noted(ex: Explorer, note: Note): Explorer {
  return { ...ex, note };
}

export function matchesOf(ex: Explorer): number[] {
  const query = ex.search?.query.trim().toLowerCase() ?? '';
  if (query === '') {
    return [];
  }
  return rowsOf(ex).flatMap((row, at) =>
    rowText(row).includes(query) ? [at] : [],
  );
}

/**
 * The explorer after `key`, and what the loop is to do next. `room` is how many rows the list
 * shows; `scrollLimit` is how far the inspector can scroll before its last line is at the bottom,
 * which only the wording knows — left out, the inspector's scroll is not bounded.
 */
export function explorerKeyed(
  ex: Explorer,
  key: ExploreKey,
  room: number,
  scrollLimit = Number.POSITIVE_INFINITY,
): { explorer: Explorer; then: Then } {
  const draw = (explorer: Explorer): { explorer: Explorer; then: Then } => ({
    explorer,
    then: 'draw',
  });
  const { note: _cleared, ...quiet } = ex;
  const plain: Explorer = quiet;
  const rows = rowsOf(plain);
  const at = cursorOf(plain);
  const level = current(plain);
  const page = Math.max(1, room);

  if (plain.search?.typing === true) {
    if (typeof key === 'object') {
      return draw({
        ...plain,
        search: { query: plain.search.query + key.typed, typing: true },
      });
    }
    if (key === 'erase') {
      return draw({
        ...plain,
        search: {
          query: [...Array.from(plain.search.query)].slice(0, -1).join(''),
          typing: true,
        },
      });
    }
    if (key === 'escape') {
      const { search: _gone, ...rest } = plain;
      return draw(rest);
    }
    if (key === 'enter') {
      const searched: Explorer = {
        ...plain,
        search: { query: plain.search.query, typing: false },
      };
      const matches = matchesOf(searched);
      const nearest =
        [...matches].reverse().find((index) => index <= at) ?? matches[0];
      return draw(
        nearest === undefined
          ? noted(searched, 'noMatch')
          : movedTo(searched, nearest),
      );
    }
    return draw(plain);
  }
  if (typeof key === 'object') {
    return draw(plain);
  }
  if (key === 'escape' && plain.search !== undefined) {
    const { search: _gone, ...rest } = plain;
    return draw(rest);
  }

  if (plain.focus === 'inspector') {
    switch (key) {
      case 'up':
        return draw({ ...plain, scroll: Math.max(0, plain.scroll - 1) });
      case 'down':
        return draw({
          ...plain,
          scroll: Math.max(0, Math.min(scrollLimit, plain.scroll + 1)),
        });
      case 'pageUp':
        return draw({ ...plain, scroll: Math.max(0, plain.scroll - page) });
      case 'pageDown':
        return draw({
          ...plain,
          scroll: Math.max(0, Math.min(scrollLimit, plain.scroll + page)),
        });
      case 'escape':
        return draw({ ...plain, focus: 'list', scroll: 0 });
      default:
        break;
    }
  }

  switch (key) {
    case 'up':
    case 'pageUp': {
      const by = key === 'up' ? 1 : page;
      if (at === 0 && level.more) {
        return { explorer: plain, then: 'earlier' };
      }
      return draw(movedTo(plain, at - by));
    }
    case 'down':
      return draw(movedTo(plain, at + 1));
    case 'pageDown':
      return draw(movedTo(plain, at + page));
    case 'top':
      return {
        explorer: movedTo(plain, 0),
        then: level.more ? 'earlier' : 'draw',
      };
    case 'bottom':
    case 'follow': {
      const { at: _at, ...rest } = level;
      return draw({
        ...withLevel(plain, { ...rest, follow: true }),
        scroll: 0,
      });
    }
    case 'prevTurn': {
      const turn = rows[at] === undefined ? 0 : turnOfRow(rows[at]);
      const first = rows.findIndex((row) => turnOfRow(row) === turn);
      const target =
        at > first
          ? first
          : rows.findIndex((row) => turnOfRow(row) === turn - 1);
      if (target === -1) {
        return { explorer: plain, then: level.more ? 'earlier' : 'draw' };
      }
      return draw(movedTo(plain, target));
    }
    case 'nextTurn': {
      const turn = rows[at] === undefined ? 0 : turnOfRow(rows[at]);
      const target = rows.findIndex((row) => turnOfRow(row) > turn);
      return draw(target === -1 ? plain : movedTo(plain, target));
    }
    case 'nextFailure': {
      const target = rows.findIndex(
        (row, index) => index > at && isFailure(row),
      );
      return draw(
        target === -1 ? noted(plain, 'noFailureBelow') : movedTo(plain, target),
      );
    }
    case 'prevFailure': {
      const target = rows.reduce(
        (found, row, index) => (index < at && isFailure(row) ? index : found),
        -1,
      );
      return draw(
        target === -1 ? noted(plain, 'noFailureAbove') : movedTo(plain, target),
      );
    }
    case 'inspect':
      return draw({ ...plain, focus: 'inspector', scroll: 0 });
    case 'tab': {
      if (plain.focus === 'list') {
        return draw({
          ...plain,
          focus: 'inspector',
          pane: panesOf(rows[at])[0] ?? 'text',
          scroll: 0,
        });
      }
      const panes = panesOf(rows[at]);
      const next = panes.indexOf(plain.pane) + 1;
      return draw(
        next >= panes.length
          ? { ...plain, focus: 'list', scroll: 0 }
          : { ...plain, pane: panes[next] ?? 'text', scroll: 0 },
      );
    }
    case 'descend': {
      const row = rows[at];
      const call =
        row?.kind === 'step' && row.step.kind === 'call' ? row.step : undefined;
      if (call?.opened !== undefined) {
        return { explorer: plain, then: 'descend' };
      }
      return draw(
        noted(plain, call?.tool === 'agent_run' ? 'noLink' : 'notADoor'),
      );
    }
    case 'ascend':
      return plain.levels.length > 1
        ? { explorer: plain, then: 'ascend' }
        : draw(noted(plain, 'atTop'));
    case 'search':
      return draw({ ...plain, search: { query: '', typing: true } });
    case 'nextMatch':
    case 'prevMatch': {
      const matches = matchesOf(plain);
      const target =
        key === 'nextMatch'
          ? matches.find((index) => index > at)
          : [...matches].reverse().find((index) => index < at);
      return draw(
        target === undefined ? noted(plain, 'noMatch') : movedTo(plain, target),
      );
    }
    case 'fold': {
      if (plain.view === 'log') {
        return draw(plain);
      }
      const turn = rows[at] === undefined ? undefined : turnOfRow(rows[at]);
      const flipped: Explorer = { ...plain, folded: !plain.folded };
      const target = rowsOf(flipped).findIndex(
        (row) => turnOfRow(row) === turn,
      );
      return draw(target === -1 ? flipped : movedTo(flipped, target));
    }
    case 'view': {
      const here = rows[at] === undefined ? [] : ordinalsOf(rows[at]);
      const switched: Explorer = {
        ...plain,
        view: plain.view === 'log' ? 'trajectory' : 'log',
        folded: false,
      };
      const target = rowsOf(switched).findIndex((row) =>
        ordinalsOf(row).some((each) => here.includes(each)),
      );
      return draw(target === -1 ? switched : movedTo(switched, target));
    }
    case 'escape':
    case 'close':
      return { explorer: plain, then: 'close' };
    case 'enter':
      return draw({ ...plain, focus: 'inspector', scroll: 0 });
    case 'refresh':
    case 'more':
    case 'erase':
      return draw(plain);
  }
}

/**
 * An earlier page of `conversation`'s log, merged into the level that shows it — not the level on
 * screen, which may be another by the time the read lands: ↑ at the top then a quick ← would
 * otherwise put a child's older rows into its parent. A level no longer held takes nothing.
 */
export function explorerEarlier(
  ex: Explorer,
  conversation: string,
  page: BackPage,
): Explorer {
  if (!ex.levels.some((level) => level.conversation === conversation)) {
    return ex;
  }
  return {
    ...ex,
    levels: ex.levels.map((level) =>
      level.conversation !== conversation
        ? level
        : {
            ...level,
            entries: merged(level.entries, page.entries),
            ...(page.oldest === undefined ? {} : { oldest: page.oldest }),
            more: page.more,
            ...(page.total === undefined ? {} : { total: page.total }),
          },
    ),
  };
}

export function explorerGrew(
  ex: Explorer,
  conversation: string,
  fresh: readonly Entry[],
  through: number,
): Explorer {
  if (!ex.levels.some((level) => level.conversation === conversation)) {
    return ex;
  }
  return {
    ...ex,
    levels: ex.levels.map((level) => {
      if (level.conversation !== conversation) {
        return level;
      }
      const grown: Level = {
        ...level,
        entries: merged(level.entries, fresh),
        through: Math.max(level.through, through),
        // Every fresh row past `through` is a row the total did not count yet: `after`
        // hands back only rows past it, so a row counted twice would need a read overlap.
        ...(level.total === undefined
          ? {}
          : {
              total:
                level.total +
                fresh.filter((each) => each.ordinal > level.through).length,
            }),
      };
      if (!level.follow) {
        return grown;
      }
      const { at: _at, ...following } = grown;
      return following;
    }),
  };
}

/** Where a descent was asked from: the level on screen then, and how deep it was. */
export interface Origin {
  readonly conversation: string;
  readonly depth: number;
}

export function originOf(ex: Explorer): Origin {
  return { conversation: current(ex).conversation, depth: ex.levels.length };
}

/** Whether the level on screen is still the one `origin` names. */
export function isAt(ex: Explorer, origin: Origin): boolean {
  return (
    ex.levels.length === origin.depth &&
    current(ex).conversation === origin.conversation
  );
}

/**
 * The explorer one level down, onto `level`. Given the `from` it was asked from, a descent whose
 * origin is no longer on screen — ← or another → landed first — is dropped: the explorer as it is.
 */
export function explorerDescended(
  ex: Explorer,
  level: Level,
  from?: Origin,
): Explorer {
  if (from !== undefined && !isAt(ex, from)) {
    return ex;
  }
  const { note: _note, search: _search, ...rest } = ex;
  return {
    ...rest,
    levels: [...ex.levels, level],
    folded: false,
    focus: 'list',
    scroll: 0,
    pane: 'result',
  };
}

export function explorerAscended(ex: Explorer): Explorer {
  if (ex.levels.length <= 1) {
    return ex;
  }
  const { note: _note, search: _search, ...rest } = ex;
  return { ...rest, levels: ex.levels.slice(0, -1), focus: 'list', scroll: 0 };
}

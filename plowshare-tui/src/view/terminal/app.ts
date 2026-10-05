import { Box, Static, Text, useInput, useStdout } from 'ink';
import { createElement as el, useEffect, useState } from 'react';
import type { Key } from 'ink';
import type { ReactElement, ReactNode } from 'react';

import { menuFor } from '../../logic/menu.ts';
import type { Menu, Vocabulary } from '../../logic/menu.ts';
import type { Entry, Live, Screen, Voice } from '../../logic/screen.ts';
import {
  describeElapsed,
  describeMoreRunning,
  describePendingLine,
  describePhase,
} from '../../logic/wording.ts';
import type { PacePart, Standing } from '../../logic/wording.ts';
import type { Phase } from '../../logic/screen.ts';
import { bar, fit, PACE_GAP, SEPARATOR, TOOL_MARK } from './meter.ts';
import { inkColour } from '../look.ts';
import type { Look } from '../look.ts';
import type { Colour, Palette } from '../theme/tokens.ts';
import { toTerminal } from '../scrollback.ts';
import type { Composing } from './composer.ts';
import type { Viewed } from '../../logic/record.ts';
import { tint } from '../../logic/tints.ts';
import type { Tinted } from '../../logic/tints.ts';
import { tintedOf } from './tinted.ts';

/**
 * The component tree: a header, a transcript, a status region and a composer.
 *
 * <h2>Not the alternate screen, and the difference is the whole point</h2>
 *
 * <p>A true full-screen program takes `CSI ?1049h`: the terminal's own
 * scrollback disappears, the wheel stops meaning anything, scrolling becomes
 * ours forever, and a pipe collects a screen instead of a transcript.
 * <b>Nothing here does that.</b> Completed entries go through {@link Static},
 * which writes each one <i>once</i> as ordinary lines and never touches it
 * again; the terminal scrolls them away itself, exactly as it did when this
 * client was line-oriented. Only the status and the composer are redrawn.
 *
 * <p>Measured before it was built and again after: forty entries into a
 * twenty-row terminal, each written exactly once, no `?1049h`, and DEC mode
 * 2026 in use so a frame cannot tear while the transcript grows.
 * `scrolling.test.ts` holds all three against a real pty, because they are
 * properties of the byte stream and a component-tree assertion cannot see them.
 *
 * <p><b>So `<Static>` is load-bearing and not a convenience.</b> Replacing it
 * with a mapped `Box` would redraw the world every frame — which looks
 * identical on a screen, and is the difference between a scrollback that is the
 * terminal's and one that is ours.
 *
 * <h2>Colour is decided here, and only here</h2>
 *
 * <p>`logic/screen.ts` carries a {@link Voice} and no colour, so the console
 * can draw a person's turn however a page should and still agree with this file
 * about who spoke. {@link INK} is the whole of the translation.
 */

/**
 * The colour each voice is drawn in. The one place a voice becomes a colour.
 *
 * <p>From the {@link Look}'s palette rather than the terminal's named colours:
 * `gray` and dim were both measured invisible on Solarized, which is the bug
 * `look.ts` exists to close.
 */
function voiced(voice: Voice, palette: Palette): Colour {
  switch (voice) {
    // A person scanning back for what THEY asked needs it to catch the eye.
    case 'person':
      return palette.person;
    case 'bot':
      return palette.bot;
    // This client talking about itself: listings, banners, the help.
    case 'client':
      return palette.muted;
    case 'trouble':
      return palette.trouble;
    // Tool lines carry their own tints; this is only what a voice needs to say.
    case 'trace':
      return palette.muted;
  }
}

/** The gutter each voice is marked with, so colour is never the only signal. */
const MARK: Record<Voice, string> = {
  person: '›',
  bot: '⏺',
  client: '·',
  trouble: '✗',
  trace: '',
};

/**
 * The wordmark, in half blocks: three rows, drawn once at the top of the
 * scrollback. `plow` faint and `share` bright, which is the whole of its design.
 */
const WORDMARK: readonly (readonly [string, string])[] = [
  ['█▀▀█ █    █▀▀█ █ █ █ ', '█▀▀▀ █  █ █▀▀█ █▀▀█ █▀▀▀'],
  ['█▀▀▀ █    █  █ █ █ █ ', '▀▀▀█ █▀▀█ █▀▀█ █▀▀▄ █▀▀ '],
  ['▀    ▀▀▀▀ ▀▀▀▀ ▀▀▀▀▀ ', '▀▀▀▀ ▀  ▀ ▀  ▀ ▀  ▀ ▀▀▀▀'],
];

/** The item the transcript opens with, before anything was said. */
const BANNER = { banner: true } as const;

type Item = Entry | typeof BANNER;

/**
 * What {@link Static} is handed: the banner and every entry, of which the first `written` — the
 * banner among them — were written already and are holes, and `entries` the rest.
 *
 * <p><b>Holes, because `Static` counts.</b> It writes `items.slice(index)` and then moves `index`
 * to `items.length`, so the array must keep its length as entries are let go — and nothing it
 * reads is before `index`. So what was written costs nothing to hold, however long the session;
 * before 2026-09-30 every entry a session ever showed was held for as long as it was open. The
 * terminal keeps the scrollback, and `/earlier` reads the log again.
 */
function transcriptOf(
  written: number,
  entries: readonly Entry[],
): readonly Item[] {
  if (written === 0) {
    return [BANNER, ...entries];
  }
  const items: Item[] = [];
  items.length = written;
  items.push(...entries);
  return items;
}

/**
 * `Static`, narrowed to the item types it is ever given here.
 *
 * <p>`Static` is generic over its items, and `createElement` cannot infer that
 * parameter the way JSX would. Naming the type here is the whole of the
 * workaround: an {@link Entry}, or the one banner that heads the scrollback.
 */
const Transcript = Static as (props: {
  readonly items: readonly Item[];
  readonly children: (item: Item, index: number) => ReactNode;
}) => ReactElement;

/** Spinner frames. Four at 120ms reads as motion without becoming a strobe. */
const TURNING = ['⠋', '⠙', '⠹', '⠸'] as const;

/** How often the status region recomputes its elapsed time. */
const TICK = 120;

/**
 * Each phase's animation: its frames, and how many ticks each frame holds.
 *
 * <p><b>Four shapes, so a phase reads before its word does.</b> A slow pulse
 * while the prompt is read, because nothing is arriving; the braille turn while
 * the model thinks; a faster rising bar while the answer arrives; a blinking
 * gear while a tool runs. Colour says the same thing a second way, for a person
 * who reads colour first — and the shape still says it on a surface with none.
 */
const ANIMATIONS: Readonly<
  Record<
    Phase['kind'],
    { readonly frames: readonly string[]; readonly hold: number }
  >
> = {
  processing: { frames: ['·', '•', '●', '•'], hold: 3 },
  thinking: { frames: TURNING, hold: 1 },
  responding: { frames: ['▁', '▃', '▅', '▇', '▅', '▃'], hold: 1 },
  tool: { frames: ['⚙', '⚙', '⚙', ' '], hold: 2 },
};

/** The frame of `phase`'s animation at tick `frame`; the plain turn before any phase. */
export function frameOf(phase: Phase | undefined, frame: number): string {
  if (phase === undefined) {
    return TURNING[frame % TURNING.length] ?? '';
  }
  const { frames, hold } = ANIMATIONS[phase.kind];
  return frames[Math.floor(frame / hold) % frames.length] ?? '';
}

/** Which palette colour a phase — and every number of a pace that belongs to one — is drawn in. */
function phaseColour(
  kind: Phase['kind'],
  palette: Palette | undefined,
): Colour | undefined {
  switch (kind) {
    case 'processing':
      return palette?.busy;
    case 'thinking':
      return palette?.accent;
    case 'responding':
      return palette?.success;
    case 'tool':
      return palette?.mdLink;
  }
}

/** A pace number's phase, or none for the speed, which belongs to no one phase. */
const PACED: Readonly<Record<PacePart['kind'], Phase['kind'] | undefined>> = {
  tools: 'tool',
  thinking: 'thinking',
  responding: 'responding',
  waiting: 'processing',
  speed: undefined,
};

/** The palette, when there is colour to draw it with. */
const paletteOf = (options: {
  colour: boolean;
  look: Look;
}): Palette | undefined => (options.colour ? options.look.palette : undefined);

/** A foreground, when there is one. Never `color: undefined` — see `exactOptionalPropertyTypes`. */
const ink = (colour: Colour | undefined): { color?: string } => {
  const spelt = inkColour(colour);
  return spelt === undefined ? {} : { color: spelt };
};

/** A panel's fill, or nothing when the theme leaves the background to the terminal. */
const panelled = (colour: Colour): { backgroundColor?: string } => {
  const spelt = inkColour(colour);
  return spelt === undefined ? {} : { backgroundColor: spelt };
};

function bannerOf(palette: Palette | undefined): ReactElement {
  return el(
    Box,
    { key: 'banner', flexDirection: 'column', marginBottom: 1, paddingLeft: 1 },
    ...WORDMARK.map(([faint, bright], row) =>
      el(
        Text,
        { key: String(row) },
        el(Text, ink(palette?.wordmarkFaint), faint),
        el(Text, { ...ink(palette?.wordmarkBright), bold: true }, bright),
      ),
    ),
  );
}

function entryOf(
  entry: Entry,
  look: Look | undefined,
  columns: number,
): ReactElement {
  // NO GUTTER AND NO MARGIN: consecutive trace entries read as one block under the question, and
  // the answer after them keeps its own margin.
  if (entry.voice === 'trace') {
    return el(
      Box,
      { key: String(entry.at), flexDirection: 'column' },
      ...(entry.lines ?? []).map((line, at) =>
        tintedOf(line, look?.palette, `trace-${entry.at}-${at}`),
      ),
    );
  }
  const palette = look?.palette;
  const person = entry.voice === 'person' && palette !== undefined;
  // The gutter is two columns, and a person's panel pads one more each side.
  const body = toTerminal(
    entry.body,
    look ?? false,
    columns - (person ? 4 : 2),
  );
  const colour =
    palette === undefined ? undefined : voiced(entry.voice, palette);
  // Only the quiet voices tint their words. An answer and a question are the
  // things being read and keep the terminal's own foreground; their mark
  // says who spoke.
  const words =
    entry.voice === 'client' || entry.voice === 'trouble' ? ink(colour) : {};
  return el(
    Box,
    {
      key: String(entry.at),
      flexDirection: 'column',
      marginBottom: 1,
      // The panel spans the row. Static writes each entry once, so this is
      // the width at the moment it was said, which is all a scrollback has.
      ...(person
        ? { ...panelled(palette.panel), paddingX: 1, width: columns }
        : {}),
    },
    el(
      Box,
      { flexDirection: 'row' },
      el(Text, { ...ink(colour), bold: true }, `${MARK[entry.voice]} `),
      // `flexGrow` with no explicit width: Yoga wraps the body against
      // whatever the terminal is, and a resize reflows it.
      el(Box, { flexGrow: 1 }, el(Text, words, body)),
    ),
    entry.note === undefined
      ? null
      : el(Text, ink(palette?.muted), `  ${entry.note}`),
  );
}

/**
 * What the model is producing, as it produces it.
 *
 * <p><b>Above the status line and below the transcript</b>: it is neither a
 * thing that was said nor a fact about the run. Thinking is italic and
 * answering is not; both are muted, because neither is the answer yet.
 */
function previewOf(live: Live, palette: Palette | undefined): ReactElement {
  const style =
    palette === undefined
      ? {}
      : {
          ...ink(palette.muted),
          ...(live.part === 'thinking' ? { italic: true } : {}),
        };
  return el(
    Box,
    { flexDirection: 'row', paddingLeft: 2 },
    el(Text, style, live.text),
  );
}

/** The most calls drawn pending under the working line; the rest are counted. */
const PENDING_SHOWN = 4;

/** The most rows the runs panel takes, however tall the terminal; `/watch` is for the rest. */
export const PANEL_ROWS = 12;

/**
 * The panel's lines fitted to `room` rows: all of them when they fit, else as many as fit under
 * a last line that says how many did not — nothing at all when there is no room.
 *
 * <p><b>Bounded because Ink stops being incremental at the terminal's height.</b> Once the redrawn
 * region reaches the rows there are, Ink 7 clears the whole terminal and writes every
 * {@link Static} entry again, each frame — the scrollback this file exists to leave alone. Three
 * live trees with phases already make a panel as tall as a small terminal.
 */
export function panelFitted(
  lines: readonly Tinted[],
  room: number,
): readonly Tinted[] {
  const most = Math.min(PANEL_ROWS, room);
  if (lines.length <= most) {
    return lines;
  }
  if (most < 1) {
    return [];
  }
  const shown = lines.slice(0, most - 1);
  return [
    ...shown,
    [tint(`… ${lines.length - shown.length} more lines`, 'muted')],
  ];
}

/**
 * How many rows `text` takes wrapped at `width` columns — never fewer than Ink's wrap makes of it,
 * and at most one more per row. Ink wraps a `Text` at spaces, carrying a word to the next row whole
 * (so counting characters alone comes out short) and, not trimming, carrying the space before it
 * too; a word wider than the row is broken. Counted high where the two could differ, because a row
 * too many costs the panel a line and a row too few costs the scrollback its being written once.
 */
export function rowsOf(text: string, width: number): number {
  const room = Math.max(1, width);
  return text.split('\n').reduce((sum, line) => {
    let rows = 1;
    let used = 0;
    for (const word of line.split(' ')) {
      const needed = used === 0 ? word.length : used + 1 + word.length;
      if (needed <= room) {
        used = needed;
        continue;
      }
      // A new row, which starts with the space this word came after.
      const carried = 1 + word.length;
      rows += Math.ceil(carried / room);
      used = carried % room === 0 ? room : carried % room;
    }
    return sum + rows;
  }, 0);
}

/**
 * The runs panel, in the redrawn region between what is running and the composer — the one place
 * that is redrawn beside a scrollback that is written once. Each line's colours are its roles'.
 * Each line is one row: a narrow terminal truncates, never wraps.
 */
function panelOf(
  lines: readonly Tinted[],
  palette: Palette | undefined,
): ReactElement {
  return el(
    Box,
    { flexDirection: 'column', paddingLeft: 1, marginBottom: 1 },
    ...lines.map((line, at) => tintedOf(line, palette, `panel-${at}`)),
  );
}

/**
 * The viewer's lines fitted to a terminal `rows` tall and `columns` wide: the region one row
 * shorter than the terminal — {@link panelFitted}'s reason — with the keys at its foot, the tree's
 * header in at most half of what is above them (its first line, the run, kept however little
 * there is), and as much of the body's end as the rest holds: the body is bottom-aligned, newest
 * at the bottom. The header and body lines are one row each, drawn truncated; the keys wrap,
 * since a narrow terminal would otherwise cut off the one that says how to leave — unless that
 * leaves no room for the run and a line of its record, when they are cut to one row after all.
 */
export function viewerFitted(
  viewed: Viewed,
  rows: number,
  columns: number,
): {
  readonly head: readonly Tinted[];
  readonly body: readonly Tinted[];
  readonly foot: string;
  readonly footRows: number;
  /** How many body lines fit, whether or not there are that many. */
  readonly room: number;
  readonly said?: string;
} {
  // A refusal takes one row above the keys, truncated.
  const saying = viewed.said === undefined ? 0 : 1;
  const wrapped = rowsOf(viewed.foot, columns - 1);
  const footRows = rows - 1 - saying - wrapped >= 2 ? wrapped : 1;
  const above = Math.max(0, rows - 1 - saying - footRows);
  const headRoom = Math.min(
    viewed.head.length,
    Math.max(1, Math.floor(above / 2)),
    above,
  );
  const head =
    viewed.head.length <= headRoom || headRoom <= 1
      ? viewed.head.slice(0, headRoom)
      : panelFitted(viewed.head, headRoom);
  const bodyRoom = above - head.length;
  return {
    head,
    body: bodyRoom <= 0 ? [] : viewed.body.slice(-bodyRoom),
    foot: viewed.foot,
    footRows,
    room: Math.max(0, bodyRoom),
    ...(viewed.said === undefined ? {} : { said: viewed.said }),
  };
}

/**
 * How many rows a dialog's lines may take in a terminal `rows` tall: the region less one row, since
 * Ink redraws the world at a region as tall as the terminal, less the box's edge top and bottom and
 * a row of margin. Between turns nothing else of the region is drawn but the panel, which is fitted
 * to what the dialog leaves. At least one.
 */
export function dialogRoom(rows: number): number {
  return Math.max(1, rows - 4);
}

/**
 * How many of the record's rows the viewer shows at once — its body's room less the line above
 * them (the way to earlier rows, or the beginning) — which is how far up scrolling can go before
 * it only empties the bottom of the screen. At least one.
 */
export function viewerRoom(
  viewed: Viewed,
  rows: number,
  columns: number,
): number {
  return Math.max(1, viewerFitted(viewed, rows, columns).room - 1);
}

/** One row, whatever the line holds: a newline in it would be a row the fitting never counted. */
const oneRow = (line: string): string => line.replace(/\s*\n\s*/gu, ' ');

/**
 * The `/watch` viewer, as tall as the terminal allows ({@link viewerFitted}): the tree's header and
 * the record in their roles' colours — a tinted line never holds a newline — and its keys muted.
 */
function viewerOf(
  viewed: Viewed,
  palette: Palette | undefined,
  rows: number,
  columns: number,
): ReactElement {
  const fitted = viewerFitted(viewed, rows, columns);
  return el(
    Box,
    { flexDirection: 'column', paddingLeft: 1 },
    ...fitted.head.map((line, at) => tintedOf(line, palette, `head-${at}`)),
    ...fitted.body.map((line, at) => tintedOf(line, palette, `body-${at}`)),
    fitted.said === undefined
      ? null
      : el(
          Text,
          { ...ink(palette?.trouble), wrap: 'truncate-end' },
          oneRow(`✗ ${fitted.said}`),
        ),
    el(
      Text,
      {
        ...ink(palette?.muted),
        wrap: fitted.footRows === 1 ? 'truncate-end' : 'wrap',
      },
      oneRow(fitted.foot),
    ),
  );
}

export interface App {
  /** What is on the screen: its entries only those not yet written, when {@link written} says so. */
  readonly screen: Screen;

  /**
   * How many of the transcript's items — the banner, then the entries — were written already
   * and are no longer in {@link screen}. 0, or absent, and the screen holds every entry.
   */
  readonly written?: number;

  /**
   * What is half-typed, held by the caller rather than by React.
   *
   * <h3>It was `useState` here, and that was a measured bug</h3>
   *
   * <p>`useInput`'s callback closes over the state of the render it was
   * registered in. Two keys pressed inside one render cycle therefore both
   * read the <i>same</i> starting composer, and the second overwrites the
   * first — so typing at speed silently loses characters, and a Tab lands
   * against a line the person had already moved past. Driven through a pty
   * against a live server it showed up as
   * `Name one thing…` arriving as `/boe one thing…`, and as Tab completing
   * nothing at all.
   *
   * <p>Holding it outside, beside the {@link Screen} that is already out
   * there, removes the race rather than narrowing it: there is one composer,
   * the keypress handler is a pure call on it, and no render boundary sits
   * between reading it and writing it back.
   */
  readonly composing: Composing;

  /**
   * Whether to draw the composer's contents as dots.
   *
   * <p><b>Drawn over, not withheld.</b> The characters are still in the
   * composer — they have to be, or backspace and the cursor would have
   * nothing to work on — and this is only the drawing. That is the honest
   * place for it: a surface that stripped them would be lying to its own
   * editor, and every key would have to be special-cased.
   */
  readonly quietly: boolean;

  readonly colour: boolean;

  /** How many user-inbox items are unread. 0 says nothing about it. */
  readonly unread: number;

  /** How many runs are waiting on a person, in words. Absent says nothing about it. */
  readonly waiting?: string;

  /** Who is being talked to and how full it is. Absent draws no status line. */
  readonly standing?: Standing;

  /** What to draw with when {@link colour} is on. */
  readonly look: Look;

  /** What the command menu may offer. Without one there is no menu. */
  readonly vocabulary?: Vocabulary;

  /**
   * A question being answered a key at a time: its keys and where the answer
   * stands, drawn above the composer and replaced as keys arrive. Absent draws
   * nothing. See `Surface.choosing`.
   */
  readonly choosing?: readonly string[];

  /** The runs panel's lines, drawn above the composer. Absent draws nothing. */
  readonly panel?: readonly Tinted[];

  /**
   * A question's dialog, drawn in place of the composer while it is up — its first line the run,
   * its last the keys. See `Surface.capDialog`.
   */
  readonly dialog?: readonly string[];

  /** A panel exists and Ctrl-O hid it — said in the hint so it can be brought back. */
  readonly panelHidden?: boolean;

  /**
   * The `/watch` viewer. While present it is drawn in place of the status region and the
   * composer; the transcript above stays mounted, so nothing is written twice.
   */
  readonly viewing?: Viewed;

  /**
   * The explorer's lines — spec 2026-09-29 §5. While present they are drawn in place of the
   * status region and the composer, as {@link App.viewing} is; the lines are already fitted to
   * fewer rows than the terminal has.
   */
  readonly exploring?: readonly Tinted[];

  /** A key was pressed. What it means is the caller's to decide. */
  readonly onKey: (input: string, key: Key) => void;
}

/** How many menu rows show at once; the rest scroll. */
const MENU_ROWS = 8;

/**
 * The command menu, as rows drawn inside the composer panel above the line.
 *
 * <p><b>Not an overlay, and it cannot be one.</b> OpenCode floats its menu over
 * the transcript with an absolute position and a z-index; here the transcript
 * is `Static` — written to the terminal once and not ours to draw over. But
 * everything below it is redrawn every frame, so rows drawn just above the line
 * rise out of the composer as the menu opens and vanish when it closes, which
 * reads the same and leaves the scrollback untouched.
 */
function menuOf(
  menu: Menu,
  picked: number,
  palette: Palette | undefined,
  columns: number,
): ReactElement {
  const count = menu.choices.length;
  const chosen = Math.min(picked, count - 1);
  const first = Math.max(
    0,
    Math.min(chosen - MENU_ROWS + 1, count - MENU_ROWS),
  );
  const shown = menu.choices.slice(first, first + MENU_ROWS);
  const nameWidth =
    Math.max(...shown.map((offer) => offer.name.length)) +
    (menu.kind === 'mention' ? 1 : 0);
  // The panel's edge and padding take four columns, and a row pads one each side.
  const room = Math.max(10, columns - 6);
  return el(
    Box,
    { flexDirection: 'column', marginBottom: 1 },
    ...shown.map((offer, row) => {
      const at = first + row;
      const selected = at === chosen;
      const label = (
        menu.kind === 'mention' ? `@${offer.name}` : offer.name
      ).padEnd(nameWidth);
      const detail = offer.detail === undefined ? '' : `  ${offer.detail}`;
      const fits =
        (label + detail).length > room
          ? `${(label + detail).slice(0, room - 1)}…`
          : label + detail;
      const name = fits.slice(0, label.length);
      const rest = fits.slice(label.length);
      if (palette === undefined) {
        return el(Text, { key: offer.name, inverse: selected }, ` ${fits} `);
      }
      const background = selected ? inkColour(palette.accent) : undefined;
      const onSelected = selected ? inkColour(palette.panel) : undefined;
      return el(
        Box,
        {
          key: offer.name,
          paddingX: 1,
          ...(background === undefined ? {} : { backgroundColor: background }),
        },
        el(
          Text,
          {
            bold: selected,
            ...(onSelected === undefined
              ? ink(palette.text)
              : { color: onSelected }),
          },
          name,
        ),
        el(
          Text,
          onSelected === undefined ? ink(palette.muted) : { color: onSelected },
          rest,
        ),
      );
    }),
    count > MENU_ROWS
      ? el(Text, ink(palette?.muted), ` ${chosen + 1} of ${count}`)
      : null,
  );
}

export function app(options: App): ReactElement {
  return el(Session, options);
}

function Session(options: App): ReactElement {
  const { screen, colour, composing, unread, waiting } = options;
  const transcript = transcriptOf(options.written ?? 0, screen.entries);
  const palette = paletteOf(options);
  const look = colour ? options.look : undefined;
  const columns = useStdout().stdout.columns ?? 80;
  const rows = useStdout().stdout.rows ?? 24;
  // The one piece of state that IS the view's: which spinner frame is up.
  // Nothing outside can race it, because nothing outside drives it.
  const [frame, setFrame] = useState(0);
  const working = screen.working;

  // The clock, and it runs ONLY while something is running. A timer left
  // going between turns would redraw an unchanging screen forever.
  useEffect(() => {
    if (working === undefined) {
      return undefined;
    }
    const beat = setInterval(() => setFrame((was) => was + 1), TICK);
    return () => clearInterval(beat);
  }, [working === undefined]);

  // One line, and that is the point: this component turns a keypress into a
  // call and decides nothing about it.
  useInput(options.onKey);

  if (options.exploring !== undefined) {
    // THE TRANSCRIPT STAYS MOUNTED, for the viewer's reason below. The lines were fitted to
    // the terminal when they were worded; one that has shrunk since is redrawn before the next
    // wording, so they are cut to the rows there are now rather than clearing every frame.
    return el(
      Box,
      { flexDirection: 'column' },
      el(Transcript, {
        items: transcript,
        children: (item: Item) =>
          'banner' in item ? bannerOf(palette) : entryOf(item, look, columns),
      }),
      el(
        Box,
        { flexDirection: 'column' },
        ...options.exploring
          .slice(0, Math.max(1, rows - 1))
          .map((line, at) => tintedOf(line, palette, `explore-${at}`)),
      ),
    );
  }

  if (options.viewing !== undefined) {
    // THE TRANSCRIPT STAYS MOUNTED. `<Static>` remembers what it has written by being there;
    // unmounting it for the viewer and mounting it again would write the whole scrollback
    // a second time.
    return el(
      Box,
      { flexDirection: 'column' },
      el(Transcript, {
        items: transcript,
        children: (item: Item) =>
          'banner' in item ? bannerOf(palette) : entryOf(item, look, columns),
      }),
      viewerOf(options.viewing, palette, rows, columns),
    );
  }

  // DOTS OF THE SAME LENGTH, so a person can see that their typing landed
  // without any of it being on the screen or in the scrollback.
  const typed = options.quietly
    ? '•'.repeat(composing.typed.length)
    : composing.typed;
  const menu =
    options.vocabulary === undefined ||
    options.quietly ||
    composing.closed === true
      ? undefined
      : menuFor(composing.typed, composing.at, options.vocabulary);
  // THE PANEL'S KEY IS SAID ONLY WHILE THERE IS A PANEL, shown or hidden: a key that does
  // nothing most of the time would be one more thing on the line to read past.
  const runsHint =
    options.panelHidden === true
      ? 'ctrl-o shows runs · '
      : options.panel !== undefined
        ? 'ctrl-o hides runs · '
        : '';
  const hint = options.quietly
    ? 'nothing you type here is shown or kept'
    : menu !== undefined
      ? '↑↓ choose · tab picks · enter sends · esc closes'
      : composing.offering.length > 0
        ? composing.offering.join('  ')
        : `${runsHint}${waiting === undefined ? '' : `${waiting} · `}` +
          `${unread > 0 ? `${unread} unread · /inbox · ` : ''}tab completes · ctrl-c stops a run · ctrl-d leaves`;
  const muted = ink(palette?.muted);
  // WHAT THE REST OF THE REDRAWN REGION TAKES, so the panel has only what is left — less one row,
  // since Ink redraws the world at a region as tall as the terminal, and one for its margin.
  const offered =
    menu === undefined
      ? 0
      : Math.min(menu.choices.length, MENU_ROWS) +
        (menu.choices.length > MENU_ROWS ? 1 : 0) +
        1;
  const composer =
    palette === undefined
      ? 2 +
        rowsOf(`❯ ${typed} `, columns - 4) +
        (options.standing === undefined ? 0 : 1) +
        rowsOf(`  ${hint}`, columns)
      : rowsOf(`❯ ${typed} `, columns - 4) +
        (options.standing === undefined ? 0 : 1) +
        rowsOf(hint, columns - 4);
  // The working line and a question's keys wrap like anything else: a tool's name is the
  // server's and can be as long as it likes, and so can the command a key would allow.
  const going =
    working === undefined
      ? 0
      : rowsOf(
          `${frameOf(working.phase, frame)} ` +
            `${working.phase === undefined ? (working.said ?? 'working') : describePhase(working.phase)}` +
            ` · ${describeElapsed(Date.now() - working.since)} · ctrl-c to stop`,
          columns - 1,
        );
  const asked = (options.choosing ?? []).reduce(
    (sum, line) => sum + rowsOf(line, columns - 1),
    0,
  );
  // CALLS STILL RUNNING, JUST ABOVE THE WORKING LINE: at most four and a count, so the redrawn
  // region stays shorter than the terminal however many a model asks for at once. Each is fitted
  // and never wraps, so it is one row.
  const calls = working?.calls ?? [];
  const since = working?.since ?? Date.now();
  const pendingLines: Tinted[] = [
    ...calls
      .slice(0, PENDING_SHOWN)
      .map((call) =>
        describePendingLine(
          call,
          Date.now() - (working?.callsSince?.[call.id] ?? since),
          columns - 1,
        ),
      ),
    ...(calls.length > PENDING_SHOWN
      ? [describeMoreRunning(calls.length - PENDING_SHOWN)]
      : []),
  ];
  // THE DIALOG STANDS IN FOR THE COMPOSER, IN ITS ROWS TOO: its edge top and bottom, and its
  // lines wrapped inside the edge and the padding — a milestone's text is as long as the record
  // made it, and the keys must not be cut off.
  const dialogRows =
    options.dialog === undefined
      ? 0
      : 2 +
        options.dialog.reduce(
          (sum, line) => sum + rowsOf(line, columns - 4),
          0,
        );
  const elsewhere =
    (working?.live === undefined ? 0 : rowsOf(working.live.text, columns - 2)) +
    pendingLines.length +
    going +
    asked +
    offered +
    (options.dialog === undefined ? composer : dialogRows);
  const panel =
    options.panel === undefined
      ? undefined
      : panelFitted(options.panel, rows - elsewhere - 2);
  return el(
    Box,
    { flexDirection: 'column' },
    // WRITTEN ONCE, NEVER TOUCHED AGAIN. See the header: this is what keeps
    // the scrollback the terminal's rather than ours. The banner is the
    // first item, so it too is written once and scrolls away like the rest.
    el(Transcript, {
      items: transcript,
      children: (item: Item) =>
        'banner' in item ? bannerOf(palette) : entryOf(item, look, columns),
    }),
    working?.live === undefined ? null : previewOf(working.live, palette),
    pendingLines.length === 0
      ? null
      : el(
          Box,
          { flexDirection: 'column' },
          ...pendingLines.map((line, at) =>
            tintedOf(line, palette, `pending-${at}`),
          ),
        ),
    // ONE PARAGRAPH, NOT A ROW OF THREE. Side by side, each piece wrapped in its own shrunk
    // column, and how many rows that came to was Yoga's to say — a long tool name made four
    // out of what `going` counted as three. Nested, it wraps as the one line it reads as.
    working === undefined
      ? null
      : el(
          Box,
          { paddingLeft: 1 },
          el(
            Text,
            null,
            el(
              Text,
              ink(
                working.phase === undefined
                  ? palette?.busy
                  : phaseColour(working.phase.kind, palette),
              ),
              `${frameOf(working.phase, frame)} `,
            ),
            working.phase === undefined
              ? el(Text, muted, working.said ?? 'working')
              : el(
                  Text,
                  ink(phaseColour(working.phase.kind, palette)),
                  describePhase(working.phase),
                ),
            el(
              Text,
              muted,
              ` · ${describeElapsed(Date.now() - working.since)} · ctrl-c to stop`,
            ),
          ),
        ),
    // A QUESTION'S KEYS, IN THE REDRAWN REGION AND NOT THE TRANSCRIPT.
    // Every ← would otherwise append another copy of the prefix line to a
    // scrollback that is written once; the question itself is already in
    // the transcript, and the answer is put there when it is given.
    options.choosing === undefined
      ? null
      : el(
          Box,
          { flexDirection: 'column', paddingLeft: 1 },
          ...options.choosing.map((line, at) =>
            el(
              Text,
              {
                key: `choosing-${at}`,
                ...ink(palette?.accent),
                bold: at === 0,
              },
              line,
            ),
          ),
        ),
    panel === undefined || panel.length === 0 ? null : panelOf(panel, palette),
    ...(options.dialog === undefined
      ? composerOf({
          composing,
          typed,
          menu,
          palette,
          columns,
          quietly: options.quietly,
          standing: options.standing,
          hint,
        })
      : [dialogOf(options.dialog, palette)]),
  );
}

/**
 * Draw only composer data. A render-local helper would close over the entire screen;
 * React's development stack traces can retain that closure after its entries were written.
 */
function composerOf(view: {
  composing: Composing;
  typed: string;
  menu: Menu | undefined;
  palette: Palette | undefined;
  columns: number;
  quietly: boolean;
  standing: Standing | undefined;
  hint: string;
}): (ReactElement | null)[] {
  const { composing, typed, menu, palette, columns, quietly, standing, hint } =
    view;
  const muted = ink(palette?.muted);
  return [
    // THE COMPOSER IS A PANEL WITH AN ACCENT EDGE, IN NAMED COLOURS.
    //
    // Its border was once `gray` — SGR 90, "bright black" — which on
    // Solarized is the BACKGROUND colour, so the box was invisible until a
    // selection inverted it. The fix then was to name no colour at all.
    // The fix now is a palette colour emitted as 24-bit or 256-colour,
    // neither of which a theme can remap onto its background. Without
    // colour it stays the plain rounded box, drawn in the foreground.
    el(
      Box,
      palette === undefined
        ? { borderStyle: 'round', paddingX: 1, flexDirection: 'column' }
        : {
            flexDirection: 'column',
            ...panelled(palette.panel),
            borderStyle: 'bold',
            borderTop: false,
            borderRight: false,
            borderBottom: false,
            ...(inkColour(palette.accent) === undefined
              ? {}
              : { borderLeftColor: inkColour(palette.accent) as string }),
            paddingX: 1,
          },
      menu === undefined
        ? null
        : menuOf(menu, composing.picked ?? 0, palette, columns),
      el(
        Box,
        { flexDirection: 'row' },
        el(Text, { ...ink(palette?.accent), bold: true }, '❯ '),
        // The cursor is drawn rather than placed. Ink owns the real one
        // and moves it to the end of what it painted, which is not
        // where the person is when they have pressed Left.
        el(Text, null, typed.slice(0, composing.at)),
        el(
          Text,
          { inverse: true },
          typed.slice(composing.at, composing.at + 1) || ' ',
        ),
        typed === '' && !quietly
          ? el(Text, muted, 'Ask anything…')
          : el(Text, null, typed.slice(composing.at + 1)),
      ),
      palette === undefined ? null : standingOf(standing, palette, columns - 4),
      palette === undefined ? null : el(Text, muted, hint),
    ),
    palette === undefined ? standingOf(standing, undefined, columns - 2) : null,
    palette === undefined ? el(Text, null, `  ${hint}`) : null,
  ];
}

/**
 * A cap question, in a box where the composer was: the run in bold, its last milestone, the keys
 * muted. <b>A box and not the composer's left edge</b>, so it reads as something asking rather
 * than as somewhere to type.
 */
function dialogOf(
  lines: readonly string[],
  palette: Palette | undefined,
): ReactElement {
  const edge = inkColour(palette?.accent);
  return el(
    Box,
    {
      key: 'dialog',
      borderStyle: 'round',
      paddingX: 1,
      flexDirection: 'column',
      ...(edge === undefined ? {} : { borderColor: edge }),
    },
    ...lines.map((line, at) =>
      el(
        Text,
        {
          key: `dialog-${at}`,
          ...(at === 0 ? { bold: true } : {}),
          ...(at === lines.length - 1 ? ink(palette?.muted) : {}),
        },
        line,
      ),
    ),
  );
}

/**
 * The status line: `who:server:model │ ▕██▌░░░░░░░▏ 16.2K/120K 14% │ ⚙ 3  think 412 …`.
 *
 * <p><b>The load is coloured by pressure and only by pressure</b>, bar and
 * number alike: muted while there is room, `warning` from 70%, `trouble` from
 * 90%. A line that is always bright is a line nobody reads.
 *
 * <p><b>Each number of the pace takes its phase's colour</b> — the tool count
 * the tool's, thinking tokens thinking's — so the line and the working line
 * above it speak one colour language. Labels stay muted.
 *
 * <p>Fitted to `room` and never wrapped; see {@link fit} for what goes first.
 */
function standingOf(
  standing: Standing | undefined,
  palette: Palette | undefined,
  room: number,
): ReactElement | null {
  if (standing === undefined) {
    return null;
  }
  const muted = ink(palette?.muted);
  const loaded =
    standing.pressure === 'full'
      ? { ...ink(palette?.trouble), bold: true }
      : standing.pressure === 'filling'
        ? ink(palette?.warning)
        : muted;
  const shown = fit(standing, room);
  const drawn =
    standing.filled === undefined || !shown.bar
      ? undefined
      : bar(standing.filled);
  const pace = shown.pace.flatMap((part, at) => {
    const phase = PACED[part.kind];
    const coloured =
      phase === undefined
        ? ink(palette?.text)
        : ink(phaseColour(phase, palette));
    const [label, ...value] = part.text.split(' ');
    const pieces =
      part.kind === 'tools'
        ? [
            el(
              Text,
              { key: `${part.kind}-mark`, ...coloured },
              `${TOOL_MARK}${part.text}`,
            ),
          ]
        : part.kind === 'speed'
          ? [
              el(Text, { key: `${part.kind}-value`, ...coloured }, label ?? ''),
              el(
                Text,
                { key: `${part.kind}-label`, ...muted },
                ` ${value.join(' ')}`,
              ),
            ]
          : [
              el(
                Text,
                { key: `${part.kind}-label`, ...muted },
                `${label ?? ''} `,
              ),
              el(
                Text,
                { key: `${part.kind}-value`, ...coloured },
                value.join(' '),
              ),
            ];
    return at === 0
      ? pieces
      : [el(Text, { key: `${part.kind}-gap` }, PACE_GAP), ...pieces];
  });
  return el(
    Box,
    {
      flexDirection: 'row',
      ...(palette === undefined ? { paddingLeft: 2 } : {}),
    },
    el(Text, muted, shown.triplet),
    standing.load === undefined ? null : el(Text, muted, SEPARATOR),
    drawn === undefined ? null : el(Text, muted, '▕'),
    drawn === undefined ? null : el(Text, loaded, drawn.fill),
    drawn === undefined ? null : el(Text, muted, `${drawn.track}▏ `),
    standing.load === undefined ? null : el(Text, loaded, standing.load),
    pace.length === 0 ? null : el(Text, muted, SEPARATOR),
    ...pace,
    standing.sync === undefined
      ? null
      : el(Text, ink(palette?.warning), `${SEPARATOR}${standing.sync}`),
  );
}

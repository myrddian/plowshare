import type { PacePart, Standing } from '../../logic/wording.ts';

/**
 * The status line's shapes: the bar, and what of the line fits.
 *
 * <p>Here and not in `wording.ts`, whose rule is that a glyph is the view's:
 * `16.2K/120K 14%` is a sentence, and `██▌░░░` is a drawing of it.
 */

/** How many cells the bar is. */
export const BAR_CELLS = 10;

/** A cell an eighth full through seven eighths full; a whole cell is {@link FULL}. */
const EIGHTHS = ['', '▏', '▎', '▍', '▌', '▋', '▊', '▉'] as const;
const FULL = '█';
const EMPTY = '░';

/** What stands between two parts of the line. */
export const SEPARATOR = ' │ ';

/** What stands between two numbers of a pace. */
export const PACE_GAP = '  ';

/** What marks the tool count, which carries no word of its own. */
export const TOOL_MARK = '⚙ ';

/**
 * `filled` as a bar: the filled part and the empty track, split so a view can
 * colour them apart. An eighth-block at the edge so a small change moves it.
 */
export function bar(
  filled: number,
  cells = BAR_CELLS,
): { readonly fill: string; readonly track: string } {
  const eighths = Math.round(Math.max(0, Math.min(1, filled)) * cells * 8);
  const whole = Math.floor(eighths / 8);
  const part = eighths % 8;
  const fill = FULL.repeat(whole) + EIGHTHS[part];
  return { fill, track: EMPTY.repeat(cells - whole - (part > 0 ? 1 : 0)) };
}

/** The line as it will be drawn: what survived the width. */
export interface Fitted {
  readonly triplet: string;
  readonly bar: boolean;
  readonly pace: readonly PacePart[];
}

/**
 * What of `standing` fits `columns`, never wrapping.
 *
 * <p>Dropped in order: how long and how fast (`waiting`, `speed`), then the rest
 * of the pace, then the bar. The load is never dropped — it is the one number
 * the line is for — and the triplet is cut from its model end last, since the
 * name of who answers is the part a person reads first.
 */
export function fit(standing: Standing, columns: number): Fitted {
  const tries: Fitted[] = [];
  const pace = standing.pace ?? [];
  const hasBar = standing.filled !== undefined;
  const brief = pace.filter(
    (part) => part.kind !== 'waiting' && part.kind !== 'speed',
  );
  for (const shown of [pace, brief, []]) {
    tries.push({ triplet: standing.triplet, bar: hasBar, pace: shown });
  }
  if (hasBar) {
    tries.push({ triplet: standing.triplet, bar: false, pace: [] });
  }
  const reserved =
    standing.sync === undefined ? 0 : SEPARATOR.length + standing.sync.length;
  for (const attempt of tries) {
    if (widthOf(attempt, standing.load) + reserved <= columns) {
      return attempt;
    }
  }
  const last = tries.at(-1) as Fitted;
  const room =
    columns - reserved - widthOf({ ...last, triplet: '' }, standing.load);
  return { ...last, triplet: cut(standing.triplet, room) };
}

/** How many columns a fitted line takes. Every glyph used here is one column. */
export function widthOf(line: Fitted, load: string | undefined): number {
  let width = line.triplet.length;
  if (load !== undefined) {
    width += SEPARATOR.length + (line.bar ? BAR_CELLS + 3 : 0) + load.length;
  }
  if (line.pace.length > 0) {
    width +=
      SEPARATOR.length +
      line.pace
        .map(
          (part) =>
            part.text.length + (part.kind === 'tools' ? TOOL_MARK.length : 0),
        )
        .reduce((sum, each) => sum + each, 0) +
      PACE_GAP.length * (line.pace.length - 1);
  }
  return width;
}

/** `text` in `room` columns, cut at the end with an ellipsis; at least one column. */
function cut(text: string, room: number): string {
  if (text.length <= room) {
    return text;
  }
  return room <= 1 ? '…' : `${text.slice(0, room - 1)}…`;
}

import { cleaned } from './clean.ts';
import type { ViewKey, Viewed } from './record.ts';
import type { Draft } from './session.ts';
import { tint, type Tinted } from './tints.ts';
import { NARROWEST_BODY, PANEL_PADDING } from './wording.ts';
import { DEFAULT_COLUMNS, wrapText } from './wrap.ts';

/**
 * An install question's draft, read whole — spec 2026-09-29-orchestration-studio §8. The question's
 * preview is cut at 4096 characters; this is every line the person would install, numbered, in the
 * viewer `/watch` uses, opened with `v` from the question and closed back to it with esc.
 *
 * <p><b>A fold, as `questions.ts` is.</b> What a key does is a pure function of the state, so the
 * scrolling is answered by a test that never opens a terminal; the view only draws what
 * {@link describeDraftView} says and hands its keys to {@link draftViewOn}.
 *
 * <p><b>Scrolled by rows, not lines.</b> `top` is the first row on screen after wrapping, so a
 * line too long for the terminal is read a row at a time like any other.
 */
export interface DraftView {
  readonly draft: Draft;
  /** The first row shown. */
  readonly top: number;
  /** Esc was pressed: the view puts the viewer away and the question back up. */
  readonly closed: boolean;
}

/** The draft from its first line. */
export function draftViewAbout(draft: Draft): DraftView {
  return { draft, top: 0, closed: false };
}

/**
 * The view after one key: ↑↓ a row, the page keys `room` rows — what is on screen at once —
 * never above the first row nor past the last one at the foot of the screen; esc closes it. A
 * key that means nothing here leaves it as it was.
 */
export function draftViewOn(
  state: DraftView,
  key: ViewKey,
  room = 1,
  columns = DEFAULT_COLUMNS,
): DraftView {
  const span = Math.max(1, room);
  const moved = (by: number): DraftView => {
    const top = Math.max(
      0,
      Math.min(rowsOf(state.draft, columns).length - span, state.top + by),
    );
    return top === state.top ? state : { ...state, top };
  };
  switch (key) {
    case 'up':
      return moved(-1);
    case 'down':
      return moved(1);
    case 'pageUp':
      return moved(-span);
    case 'pageDown':
      return moved(span);
    case 'close':
      return { ...state, closed: true };
    default:
      return state;
  }
}

/**
 * The viewer's frame: the name and path above; the text below, each line numbered to the right
 * of a gutter as wide as the last number and wrapped to `columns` beside it, the `rows` from `top`;
 * and the keys. A surface that cannot say how many rows it has passes none and is given them all.
 */
export function describeDraftView(
  state: DraftView,
  columns = DEFAULT_COLUMNS,
  rows = Number.POSITIVE_INFINITY,
): Viewed {
  const all = rowsOf(state.draft, columns);
  const top = Math.max(0, Math.min(state.top, all.length - Math.max(0, rows)));
  return {
    head: [
      [
        tint(state.draft.name, 'strong'),
        tint(`  ${state.draft.path}`, 'muted'),
      ],
    ],
    body: all.slice(top, top + Math.max(0, rows)),
    foot: '↑↓ scroll · pgup/pgdn page · esc back',
  };
}

/**
 * Every row of the draft: its lines cleaned of what would move the terminal, a number beside the
 * first row of each and a blank gutter beside the rest. A file's last line break ends its last
 * line; it is not one more, empty line.
 */
function rowsOf(draft: Draft, columns: number): Tinted[] {
  const lines = cleaned(draft.text).replace(/\n$/u, '').split('\n');
  const digits = String(lines.length).length;
  const width = Math.max(
    NARROWEST_BODY,
    columns - PANEL_PADDING - digits - ' │ '.length,
  );
  return lines.flatMap((line, at) =>
    wrapText(line, width).map((row, index): Tinted => [
      tint(
        `${index === 0 ? String(at + 1).padStart(digits) : ' '.repeat(digits)} │ `,
        'muted',
      ),
      tint(row),
    ]),
  );
}

import { describe, expect, it } from 'vitest';

import { describeDraftView, draftViewAbout, draftViewOn } from './draftview.ts';
import type { DraftView } from './draftview.ts';
import type { ViewKey } from './record.ts';
import type { Draft } from './session.ts';
import { plainOf } from './tints.ts';

/**
 * An install question's draft read whole, in the viewer `/watch` uses — without a terminal. What
 * the person is shown is the text they would install, every line of it, numbered.
 */

const TWELVE: Draft = {
  name: 'triage',
  path: 'artifacts/triage.md',
  text: `${Array.from({ length: 12 }, (_, at) => `line ${at + 1}`).join('\n')}\n`,
};

const bodyOf = (state: DraftView, columns: number, rows: number): string[] =>
  describeDraftView(state, columns, rows).body.map(plainOf);

const pressed = (
  state: DraftView,
  room: number,
  ...keys: ViewKey[]
): DraftView => keys.reduce((at, key) => draftViewOn(at, key, room, 80), state);

describe("viewing an install question's draft", () => {
  it('heads it with the name and path, numbers each line to the right, and says its keys', () => {
    const viewed = describeDraftView(draftViewAbout(TWELVE), 80, 3);
    expect(viewed.head.map(plainOf)).toEqual(['triage  artifacts/triage.md']);
    // A FILE'S LAST LINE BREAK ENDS ITS LAST LINE; it is not a thirteenth, empty one.
    expect(bodyOf(draftViewAbout(TWELVE), 80, 20)).toHaveLength(12);
    expect(viewed.body.map(plainOf)).toEqual([
      ' 1 │ line 1',
      ' 2 │ line 2',
      ' 3 │ line 3',
    ]);
    expect(viewed.foot).toBe('↑↓ scroll · pgup/pgdn page · esc back');
  });

  it('wraps a long line under its number, the rows after it unnumbered', () => {
    const long: Draft = {
      name: 'n',
      path: 'p',
      text: `short\n${'word '.repeat(12).trim()}`,
    };
    // 30 columns less the viewer's edge and the gutter leave 25 for the text.
    expect(bodyOf(draftViewAbout(long), 30, 10)).toEqual([
      '1 │ short',
      '2 │ word word word word word',
      '  │ word word word word word',
      '  │ word word',
    ]);
  });

  it('scrolls a row at a time, and stops at the top and where the last line is at the foot', () => {
    const start = draftViewAbout(TWELVE);
    expect(pressed(start, 5, 'up')).toEqual(start);
    const down = pressed(start, 5, 'down', 'down');
    expect(bodyOf(down, 80, 5)[0]).toBe(' 3 │ line 3');
    const bottom = pressed(start, 5, ...Array<ViewKey>(20).fill('down'));
    expect(bottom.top).toBe(7);
    expect(bodyOf(bottom, 80, 5).at(-1)).toBe('12 │ line 12');
    expect(pressed(bottom, 5, 'down')).toEqual(bottom);
  });

  it('pages by the room there is, clamped at both ends', () => {
    const start = draftViewAbout(TWELVE);
    expect(pressed(start, 5, 'pageDown').top).toBe(5);
    expect(pressed(start, 5, 'pageDown', 'pageDown').top).toBe(7);
    expect(pressed(start, 5, 'pageDown', 'pageDown', 'pageUp').top).toBe(2);
    expect(pressed(start, 5, 'pageDown', 'pageUp', 'pageUp').top).toBe(0);
    // A draft shorter than the room does not scroll at all.
    expect(pressed(start, 20, 'pageDown', 'down').top).toBe(0);
  });

  it('closes on esc, and a key that means nothing here changes nothing', () => {
    const start = draftViewAbout(TWELVE);
    expect(start.closed).toBe(false);
    expect(pressed(start, 5, 'down', 'close')).toEqual({
      ...pressed(start, 5, 'down'),
      closed: true,
    });
    expect(pressed(start, 5, 'tools', 'follow', 'open', 'earlier')).toEqual(
      start,
    );
  });
});

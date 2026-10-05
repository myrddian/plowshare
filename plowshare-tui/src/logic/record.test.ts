import { describe, expect, it } from 'vitest';

import {
  MILESTONE_KINDS,
  MOST_HELD,
  ORCHESTRATION_RECORD,
  RECORD_TAIL,
  followFrom,
  latestQuestion,
  liveRoots,
  liveRunsOf,
  listingLive,
  merged,
  outcomeLost,
  panelReads,
  readingQuestion,
  readingRecordAfter,
  treeAsking,
  readingRecordEarlier,
  readingRecordTail,
  readingToolLine,
  recordPageOf,
  recordedOf,
  settledToRead,
  watchEarlier,
  watchCursor,
  watchGrew,
  watchKeyed,
  watchSettled,
  watching,
} from './record.ts';
import type { Recorded, RecordPage, Tree, Watch } from './record.ts';
import type { Run } from './session.ts';

const row = (
  ordinal: number,
  kind = 'stage_moved',
  detail?: string,
): Recorded => ({
  ordinal,
  at: '2026-09-28T09:00:00Z',
  run: 'orc_1',
  actor: 'conductor',
  kind,
  text: `line ${ordinal}`,
  tool: kind === 'tool_call',
  ...(detail === undefined ? {} : { detail }),
});

const page = (
  rows: readonly Recorded[],
  more = false,
  through = 0,
): RecordPage => ({ root: 'orc_1', rows, through, more });

describe('the record on the wire', () => {
  it('asks for the tail, an earlier page and what came after, narrowed only when asked', () => {
    expect(readingRecordTail('orc_1')).toEqual({
      type: ORCHESTRATION_RECORD,
      payload: { root: 'orc_1', tail: true, limit: 100 },
    });
    expect(readingRecordTail('orc_1', ['tool_call'], 1)).toEqual({
      type: ORCHESTRATION_RECORD,
      payload: { root: 'orc_1', tail: true, limit: 1, kinds: ['tool_call'] },
    });
    expect(readingRecordEarlier('orc_1', 51, MILESTONE_KINDS).payload).toEqual({
      root: 'orc_1',
      before: 51,
      limit: 100,
      kinds: [...MILESTONE_KINDS],
    });
    expect(readingRecordAfter('orc_1', 150).payload).toEqual({
      root: 'orc_1',
      after: 150,
      limit: 100,
    });
  });

  it('reads a backwards page oldest first, and a refusal as nothing', () => {
    const answer = {
      code: 'OK',
      payload: {
        root: 'orc_1',
        total: 2,
        limit: 2,
        through: 9,
        oldest: 8,
        more: true,
        rows: [
          {
            ordinal: 9,
            at: 'a',
            run: 'orc_1',
            actor: 'coder',
            kind: 'tool_call',
            text: 'coder · run ./gradlew test',
            detail: 'exit 1',
          },
          {
            ordinal: 8,
            at: 'a',
            run: 'orc_1',
            actor: 'conductor',
            kind: 'stage_moved',
            text: 'code: pending → in_progress',
            detail: null,
          },
        ],
      },
    };
    const read = recordPageOf(answer);
    expect(read?.rows.map((each) => each.ordinal)).toEqual([8, 9]);
    expect(read?.rows[1]).toMatchObject({ tool: true, detail: 'exit 1' });
    expect(read?.rows[0]?.detail).toBeUndefined();
    expect(read?.through).toBe(9);
    expect(read?.more).toBe(true);
    expect(recordPageOf({ code: 'BAD_REQUEST', said: 'no' })).toBeUndefined();
  });

  it("reads a row's whole text as its body when it has one, and none when it is absent or empty", () => {
    const answer = {
      code: 'OK',
      payload: {
        root: 'orc_1',
        through: 3,
        more: false,
        rows: [
          {
            ordinal: 1,
            at: 'a',
            run: 'orc_1',
            actor: 'conductor',
            kind: 'question_asked',
            text: 'asked: Which database?',
            detail: null,
            body: 'Which database?\nPostgres or SQLite',
          },
          {
            ordinal: 2,
            at: 'a',
            run: 'orc_1',
            actor: 'conductor',
            kind: 'question_answered',
            text: 'answered by enzo: PostgreSQL',
            detail: null,
          },
          {
            ordinal: 3,
            at: 'a',
            run: 'orc_1',
            actor: 'conductor',
            kind: 'stalled',
            text: 'quiet',
            body: '',
          },
        ],
      },
    };
    const read = recordPageOf(answer);
    expect(read?.rows[0]).toMatchObject({
      text: 'asked: Which database?',
      body: 'Which database?\nPostgres or SQLite',
    });
    expect(read?.rows[1] !== undefined && 'body' in read.rows[1]).toBe(false);
    expect(read?.rows[2] !== undefined && 'body' in read.rows[2]).toBe(false);
  });

  it('reads the recorded push and nothing else', () => {
    expect(
      recordedOf({ kind: 'orchestration.recorded', root: 'orc_1', through: 4 }),
    ).toEqual({ root: 'orc_1', through: 4 });
    expect(
      recordedOf({
        kind: 'orchestration.changed',
        orchestration: 'orc_1',
        state: 'x',
      }),
    ).toBeUndefined();
    expect(
      recordedOf({ kind: 'orchestration.recorded', root: 'orc_1' }),
    ).toBeUndefined();
  });

  it('reads the line a settle names, and a push without one as the record growing', () => {
    expect(
      recordedOf({
        kind: 'orchestration.recorded',
        root: 'orc_1',
        through: 400,
        settled: 2,
      }),
    ).toEqual({ root: 'orc_1', through: 400, settled: 2 });
    expect(
      recordedOf({
        kind: 'orchestration.recorded',
        root: 'orc_1',
        through: 400,
        settled: 'x',
      }),
    ).toEqual({ root: 'orc_1', through: 400 });
    expect(readingToolLine('orc_1', 2)).toEqual({
      type: ORCHESTRATION_RECORD,
      payload: { root: 'orc_1', after: 1, limit: 1, kinds: ['tool_call'] },
    });
  });

  it('merges by ordinal, the fresher row winning', () => {
    expect(
      merged(
        [row(1), row(2, 'tool_call')],
        [row(2, 'tool_call', 'ok'), row(3)],
      ).map((each) => `${each.ordinal}${each.detail ?? ''}`),
    ).toEqual(['1', '2ok', '3']);
  });

  it('re-reads from the oldest tool line still waiting on its outcome, near the end', () => {
    expect(followFrom([row(1), row(2, 'tool_call'), row(3)], 3)).toBe(1);
    expect(followFrom([row(1), row(2, 'tool_call', 'ok'), row(3)], 3)).toBe(3);
    expect(followFrom([row(1, 'tool_call')], 200)).toBe(200);
  });

  it('finds the live roots, newest first as listed', () => {
    const run = (id: string, state: string, parent?: string): Run => ({
      id,
      definition: 'd',
      tier: 'project',
      state,
      depth: parent === undefined ? 0 : 1,
      createdAt: '',
      ...(parent === undefined ? {} : { parent }),
    });
    expect(
      liveRoots([
        run('orc_3', 'running'),
        run('orc_2', 'waiting', 'orc_1'),
        run('orc_1', 'asking'),
        run('orc_0', 'finished'),
      ]).map((each) => each.id),
    ).toEqual(['orc_3', 'orc_1']);
  });
});

describe('what the panel reads', () => {
  const run = (
    id: string,
    state: string,
    createdAt: string,
    parent?: string,
  ): Run => ({
    id,
    definition: 'd',
    tier: 'project',
    state,
    depth: parent === undefined ? 0 : 1,
    createdAt,
    ...(parent === undefined ? {} : { parent }),
  });
  const listed = (...runs: Run[]) => ({
    code: 'OK',
    payload: { orchestrations: runs },
  });

  it('lists each live state on its own, as many as the server gives', () => {
    expect(listingLive().map((ask) => ask.payload)).toEqual([
      { state: 'running', limit: 200 },
      { state: 'asking', limit: 200 },
      { state: 'waiting', limit: 200 },
    ]);
    expect(
      listingLive().every((ask) => ask.type === 'orchestration.list'),
    ).toBe(true);
  });

  it('reads the three listings as one, newest first, and a refusal of any as nothing', () => {
    const old = run('orc_1', 'asking', '2026-09-28T08:00:00Z');
    const young = run('orc_3', 'running', '2026-09-28T10:00:00Z');
    const phase = run('orc_2', 'waiting', '2026-09-28T09:00:00Z', 'orc_1');
    expect(
      liveRunsOf([listed(young), listed(old), listed(phase)])?.map(
        (each) => each.id,
      ),
    ).toEqual(['orc_3', 'orc_2', 'orc_1']);
    expect(
      liveRunsOf([listed(young), { code: 'NOT_FOUND', said: 'no' }, listed()]),
    ).toBeUndefined();
  });

  it('knows a tree is asking by its root or a live phase, and finds the question it asked last', () => {
    const tree = (root: string, phase: string): Tree => ({
      run: run('orc_1', root, ''),
      stages: [],
      milestones: [],
      phases: [{ run: run('orc_2', phase, '', 'orc_1'), stages: [] }],
    });
    expect(treeAsking(tree('asking', 'running'))).toBe(true);
    expect(treeAsking(tree('waiting', 'asking'))).toBe(true);
    expect(treeAsking(tree('running', 'waiting'))).toBe(false);
    expect(
      latestQuestion([
        row(1, 'question_asked'),
        row(2, 'question_answered'),
        row(3, 'question_asked'),
        row(4),
      ])?.ordinal,
    ).toBe(3);
    expect(latestQuestion([row(1), row(2, 'stalled')])).toBeUndefined();
    expect(readingQuestion('orc_1')).toEqual({
      type: ORCHESTRATION_RECORD,
      payload: {
        root: 'orc_1',
        tail: true,
        limit: 1,
        kinds: ['question_asked'],
      },
    });
  });

  it('reads one tree again for a run in it, lists for a run newly live, and nothing otherwise', () => {
    const trees: Tree[] = [
      {
        run: run('orc_1', 'running', ''),
        stages: [],
        phases: [{ run: run('orc_2', 'running', '', 'orc_1'), stages: [] }],
        milestones: [],
      },
    ];
    expect(panelReads({ id: 'orc_1', state: 'finished' }, trees)).toEqual({
      root: 'orc_1',
    });
    expect(panelReads({ id: 'orc_2', state: 'finished' }, trees)).toEqual({
      root: 'orc_1',
    });
    expect(panelReads({ id: 'orc_9', state: 'running' }, trees)).toBe('list');
    expect(
      panelReads({ id: 'orc_9', state: 'finished' }, trees),
    ).toBeUndefined();
  });
});

describe("the viewer's state", () => {
  const rows = [row(1), row(2), row(3)];

  it('scrolls within what it holds, and asks for earlier at the top when there is more', () => {
    const open = watching(page(rows, true, 3), true);
    // One row of room: the cursor is the one row on screen, and moving it scrolls.
    expect(watchKeyed(open, 'up')).toMatchObject({
      then: 'draw',
      watch: { back: 1, at: 2 },
    });
    expect(watchKeyed({ ...open, back: 1 }, 'up')).toMatchObject({
      then: 'earlier',
      watch: { back: 2 },
    });
    expect(watchKeyed({ ...open, back: 2 }, 'pageUp').watch.back).toBe(2);
    expect(watchKeyed({ ...open, back: 2 }, 'down').watch.back).toBe(1);
    expect(
      watchKeyed({ ...open, back: 2, at: 1 }, 'follow').watch,
    ).toMatchObject({ back: 0 });
    expect(
      watchKeyed({ ...open, back: 2, at: 1 }, 'follow').watch.at,
    ).toBeUndefined();
    expect(watchKeyed(open, 'earlier').then).toBe('earlier');
    expect(watchKeyed({ ...open, more: false }, 'earlier').then).toBe('draw');
    expect(watchKeyed(open, 'tools')).toMatchObject({
      then: 'refilter',
      watch: { tools: false, back: 0 },
    });
    expect(watchKeyed(open, 'close').then).toBe('close');
  });

  it('jumps the bottom row to a failure or a milestone, and opens the row at the bottom', () => {
    const rows = [
      row(1),
      row(2, 'tool_call', 'exit 1'),
      row(3, 'tool_call', 'ok'),
      row(4, 'tool_call', 'ok'),
    ];
    const open = watching(page(rows, false, 4), true);
    expect(watchKeyed(open, 'prevFailure').watch.back).toBe(2);
    expect(watchKeyed({ ...open, back: 3 }, 'nextFailure').watch.back).toBe(2);
    expect(watchKeyed(open, 'nextFailure').watch.back).toBe(0);
    expect(watchKeyed(open, 'prevMark').watch.back).toBe(3);
    expect(watchKeyed(open, 'open').then).toBe('open');
  });

  it('stops where the oldest row it holds is on screen, and asks for earlier there', () => {
    // Three rows, two of them fit: the cursor moves up within the screen, then scrolls it one
    // line to the top, where further up changes nothing and asks for earlier rows.
    const open = watching(page(rows, true, 3), true);
    expect(watchKeyed(open, 'up', 2)).toMatchObject({
      then: 'draw',
      watch: { back: 0, at: 2 },
    });
    expect(watchKeyed({ ...open, at: 2 }, 'up', 2)).toMatchObject({
      then: 'earlier',
      watch: { back: 1, at: 1 },
    });
    expect(watchKeyed({ ...open, back: 1, at: 1 }, 'up', 2)).toMatchObject({
      then: 'earlier',
      watch: { back: 1, at: 1 },
    });
    expect(
      watchKeyed({ ...open, back: 1, at: 1, more: false }, 'pageUp', 2),
    ).toMatchObject({ then: 'draw', watch: { back: 1, at: 1 } });
    // Everything fits: the cursor moves, nothing scrolls.
    expect(watchKeyed({ ...open, more: false }, 'up', 10).watch).toMatchObject({
      back: 0,
      at: 2,
    });
  });

  it('moves a cursor of its own at a real height, jumping to failures and milestones, and following past the last', () => {
    const few = [
      row(1),
      row(2, 'tool_call', 'exit 1'),
      row(3, 'tool_call', 'ok'),
      row(4, 'tool_call', 'ok'),
    ];
    const open = watching(page(few, false, 4), true);
    expect(watchCursor(open)?.ordinal).toBe(4);
    // All four fit in 19 rows: the cursor moves and the screen does not.
    const failed = watchKeyed(open, 'prevFailure', 19);
    expect(failed).toMatchObject({ then: 'draw', watch: { at: 2, back: 0 } });
    expect(watchCursor(failed.watch)?.ordinal).toBe(2);
    expect(watchKeyed(failed.watch, 'open', 19)).toMatchObject({
      then: 'open',
      watch: { at: 2 },
    });
    expect(watchKeyed(open, 'prevMark', 19).watch.at).toBe(1);
    expect(watchKeyed({ ...open, at: 1 }, 'nextFailure', 19).watch.at).toBe(2);
    // Nothing further down: back to following the end.
    const past = watchKeyed(failed.watch, 'nextFailure', 19);
    expect(past.watch.back).toBe(0);
    expect(past.watch.at).toBeUndefined();
    expect(watchKeyed(failed.watch, 'nextMark', 19).watch.at).toBeUndefined();
    expect(watchKeyed(open, 'up', 19).watch).toMatchObject({ at: 3, back: 0 });
    expect(watchKeyed({ ...open, at: 3 }, 'down', 19).watch.at).toBeUndefined();
    // No failure above and nothing more to read: nothing moves.
    expect(watchKeyed(failed.watch, 'prevFailure', 19)).toMatchObject({
      then: 'draw',
      watch: { at: 2 },
    });
  });

  it('scrolls a failure far up onto the screen, and asks for earlier rows when there is none above', () => {
    const many = Array.from({ length: 100 }, (_, at) =>
      row(51 + at, 'tool_call', at + 51 === 60 ? 'exit 1' : 'ok'),
    );
    const open = watching(page(many, true, 150), true);
    const failed = watchKeyed(open, 'prevFailure', 19);
    expect(failed.then).toBe('draw');
    expect(failed.watch.at).toBe(60);
    // On screen: between the top and the bottom rows drawn.
    const index = failed.watch.rows.findIndex((each) => each.ordinal === 60);
    expect(index).toBeGreaterThanOrEqual(
      failed.watch.rows.length - failed.watch.back - 19,
    );
    expect(index).toBeLessThanOrEqual(
      failed.watch.rows.length - 1 - failed.watch.back,
    );
    // None above it that is held, and more before: read them, and stay where it is.
    expect(watchKeyed(failed.watch, 'prevFailure', 19)).toMatchObject({
      then: 'earlier',
      watch: { at: 60 },
    });
    expect(watchKeyed(open, 'prevMark', 19)).toMatchObject({
      then: 'earlier',
      watch: { back: 0 },
    });
    // The earlier page read, the one in it is found.
    const earlier = watchEarlier(
      failed.watch,
      page(
        Array.from({ length: 50 }, (_, at) =>
          row(1 + at, 'tool_call', at + 1 === 20 ? 'exit 2' : 'ok'),
        ),
        false,
        150,
      ),
    );
    expect(earlier.at).toBe(60);
    expect(watchKeyed(earlier, 'prevFailure', 19).watch.at).toBe(20);
    // A page up from the end moves the cursor within the screen; a push keeps it and the screen.
    const paged = watchKeyed(open, 'pageUp', 19).watch;
    expect(paged).toMatchObject({ at: 140, back: 0 });
    expect(watchGrew(paged, page([row(151)], false, 151))).toMatchObject({
      at: 140,
      back: 1,
    });
  });

  it('counts a row with a body as the lines it is drawn in when it scrolls', () => {
    // Row 3 is drawn in four lines — its line and three of body — and every other in one.
    const rows = [row(1), row(2), row(3), row(4), row(5)];
    const tall = (each: Recorded): number => (each.ordinal === 3 ? 4 : 1);
    const open = watching(page(rows, false, 5), true);
    // Five lines of room: at the end, rows 4 and 5 are on screen and row 3 (four lines) does
    // not fit above them. Moving up onto it scrolls one row, so that rows 3 and 4 fill it.
    expect(watchKeyed({ ...open, at: 4 }, 'up', 5, tall).watch).toMatchObject({
      at: 3,
      back: 1,
    });
    // Seven lines hold rows 1–4 (1 + 1 + 4 + 1): with row 1 on screen, it scrolls no higher
    // than one row up from the end, however far up it was left.
    expect(
      watchKeyed({ ...open, at: 2, back: 3 }, 'up', 7, tall).watch,
    ).toMatchObject({ at: 1, back: 1 });
    // A jump to row 3 from the end, centred: it lands on the screen whole.
    const marked = [
      row(1, 'tool_call', 'ok'),
      row(2, 'tool_call', 'ok'),
      row(3),
      row(4, 'tool_call', 'ok'),
      row(5, 'tool_call', 'ok'),
      row(6, 'tool_call', 'ok'),
      row(7, 'tool_call', 'ok'),
    ];
    const jumped = watchKeyed(
      watching(page(marked, false, 7), true),
      'prevMark',
      5,
      tall,
    ).watch;
    expect(jumped.at).toBe(3);
    const index = 2;
    const bottom = marked.length - 1 - jumped.back;
    const used = marked
      .slice(index, bottom + 1)
      .reduce((sum, each) => sum + tall(each), 0);
    expect(bottom).toBeGreaterThanOrEqual(index);
    expect(used).toBeLessThanOrEqual(5);
    // Without heights, every row is one line: what it always was.
    expect(watchKeyed({ ...open, at: 4 }, 'up', 5).watch).toMatchObject({
      at: 3,
      back: 0,
    });
  });

  it('puts an earlier page above and keeps its place when a push adds below', () => {
    const open = {
      ...watching(page([row(3), row(4)], true, 4), true),
      back: 1,
    };
    const earlier = watchEarlier(open, page([row(1), row(2)], false, 4));
    expect(earlier.rows.map((each) => each.ordinal)).toEqual([1, 2, 3, 4]);
    expect(earlier.more).toBe(false);
    const grown = watchGrew(earlier, page([row(5), row(6)], false, 6));
    expect(grown.rows.map((each) => each.ordinal)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(grown.back).toBe(3);
    expect(grown.through).toBe(6);
    expect(
      watchGrew({ ...earlier, back: 0 }, page([row(5)], false, 5)).back,
    ).toBe(0);
  });

  it('reads alone only the settled lines it holds open above where the push reads from', () => {
    const open = watching(
      page(
        [row(2, 'tool_call'), row(3, 'tool_call', 'ok'), row(300, 'tool_call')],
        false,
        300,
      ),
      true,
    );
    // 2 is open and above; 3 is settled already; 300 the forward read brings; 7 it does not hold.
    expect(settledToRead(open, [300, 3, 2, 7, 2], 299)).toEqual([2]);
    const settled = watchSettled(open, row(2, 'tool_call', 'ok'));
    expect(settled.rows.map((each) => each.detail ?? '…')).toEqual([
      'ok',
      'ok',
      '…',
    ]);
    expect(watchSettled(open, row(9, 'tool_call', 'ok'))).toBe(open);
  });

  it('holds at most MOST_HELD rows while it follows, letting the oldest go', () => {
    const rows = (from: number, count: number): Recorded[] =>
      Array.from({ length: count }, (_, at) => row(from + at));
    const full = watching(page(rows(1, 100), false, 100), true);
    const grown = watchGrew(
      { ...full, rows: rows(1, MOST_HELD) },
      page(rows(MOST_HELD + 1, 50), false, MOST_HELD + 50),
    );
    expect(grown.rows).toHaveLength(MOST_HELD);
    expect(grown.rows[0]?.ordinal).toBe(51);
    expect(grown.more).toBe(true);
    expect(grown.cut).toBeUndefined();
  });

  it('holds at most MOST_HELD rows scrolled up too, around the screen: the oldest go first, then the newest', () => {
    const rows = (from: number, count: number): Recorded[] =>
      Array.from({ length: count }, (_, at) => row(from + at));
    const full = watching(page(rows(1, 100), false, 100), true);
    // Near the end: the rows it lets go are the oldest, far above the screen.
    const near = watchGrew(
      { ...full, rows: rows(1, MOST_HELD), back: 5 },
      page(rows(MOST_HELD + 1, 50), false, MOST_HELD + 50),
    );
    expect(near.rows).toHaveLength(MOST_HELD);
    expect(near.rows[0]?.ordinal).toBe(51);
    expect(near.back).toBe(55);
    expect(near.more).toBe(true);
    expect(near.cut).toBeUndefined();
    // Far up, the cursor on row 100: what a push brings is let go below it, and said to be.
    const far = watchGrew(
      { ...full, rows: rows(1, MOST_HELD), at: 100, back: MOST_HELD - 100 },
      page(rows(MOST_HELD + 1, 50), false, MOST_HELD + 50),
    );
    expect(far.rows).toHaveLength(MOST_HELD);
    expect(far.rows[0]?.ordinal).toBe(1);
    expect(far.rows.at(-1)?.ordinal).toBe(MOST_HELD);
    expect(far.cut).toBe(true);
    expect(far.through).toBe(MOST_HELD + 50);
    // The screen stays where it was: the same row at its foot, the cursor on its row.
    expect(far.rows[far.rows.length - 1 - far.back]?.ordinal).toBe(100);
    expect(watchCursor(far)?.ordinal).toBe(100);
    // A push to a viewer cut short brings nothing below what it holds: there would be a gap.
    const later = watchGrew(
      far,
      page([row(MOST_HELD + 51)], false, MOST_HELD + 51),
    );
    expect(later.rows).toBe(far.rows);
    expect(later.back).toBe(far.back);
    expect(later.through).toBe(MOST_HELD + 51);
    // A settled line it holds is still put in its place.
    const settledAbove = watchGrew(
      far,
      page([row(99, 'tool_call', 'ok')], false, MOST_HELD + 51),
    );
    expect(settledAbove.rows.find((each) => each.ordinal === 99)?.detail).toBe(
      'ok',
    );
    // Following again reads the end afresh, since what lay between was let go.
    expect(watchKeyed(far, 'follow')).toMatchObject({
      then: 'latest',
      watch: { back: 0 },
    });
    expect(watchKeyed(far, 'nextFailure').then).toBe('latest');
    expect(
      watchKeyed({ ...far, at: MOST_HELD - 1, back: 1 }, 'down', 1).then,
    ).toBe('latest');
    // An earlier page read far up lets the newest go too.
    const top = {
      ...full,
      rows: rows(1001, MOST_HELD),
      more: true,
      at: 1001,
      back: MOST_HELD - 1,
    };
    const earlier = watchEarlier(top, page(rows(901, 100), true, 3000));
    expect(earlier.rows).toHaveLength(MOST_HELD);
    expect(earlier.rows[0]?.ordinal).toBe(901);
    expect(earlier.cut).toBe(true);
    expect(earlier.rows[earlier.rows.length - 1 - earlier.back]?.ordinal).toBe(
      1001,
    );
  });

  it('holds a window, not the record, through twenty thousand rows pushed with their settles', () => {
    // THE ELEVEN HOURS, IN MINIATURE. A tree's record as the harness writes it — a tool line
    // open, its outcome written three rows later, a milestone every fifty with a body of a few
    // KB — pushed ten rows at a time, and read the way `main.ts`'s `/watch` reads a push.
    const TOTAL = 20_000;
    const server: Recorded[] = [];
    const bodied = (ordinal: number): Recorded => ({
      ...row(ordinal, 'question_asked'),
      body: `b${ordinal} `.repeat(600),
    });
    const readAfter = (after: number): RecordPage =>
      page(
        server.filter((each) => each.ordinal > after).slice(0, RECORD_TAIL),
        false,
        server.length,
      );
    const push = (state: Watch, settled: readonly number[]): Watch => {
      let next = state;
      let after = followFrom(next.rows, next.through);
      const alone = settledToRead(next, settled, after);
      if (next.cut !== true) {
        for (;;) {
          const fresh = readAfter(after);
          next = watchGrew(next, fresh);
          const last = fresh.rows.at(-1)?.ordinal;
          if (fresh.rows.length < RECORD_TAIL || last === undefined) {
            break;
          }
          after = last;
        }
      }
      for (const ordinal of alone) {
        next = watchSettled(next, server[ordinal - 1] as Recorded);
      }
      return next;
    };
    const grow = (state: Watch): Watch => {
      let next = state;
      while (server.length < TOTAL) {
        const settled: number[] = [];
        for (let at = 0; at < 10; at += 1) {
          const ordinal = server.length + 1;
          server.push(
            ordinal % 50 === 0 ? bodied(ordinal) : row(ordinal, 'tool_call'),
          );
          const settling = server[ordinal - 4];
          if (settling?.tool === true && settling.detail === undefined) {
            server[ordinal - 4] = {
              ...settling,
              detail: ordinal % 7 === 0 ? 'exit 1' : 'ok',
            };
            settled.push(ordinal - 3);
          }
        }
        next = push(next, settled);
        expect(next.rows.length).toBeLessThanOrEqual(MOST_HELD);
      }
      return next;
    };

    server.push(
      ...Array.from({ length: 100 }, (_, at) => row(at + 1, 'tool_call', 'ok')),
    );
    const followed = grow(watching(readAfter(0), true));
    expect(followed.rows).toHaveLength(MOST_HELD);
    expect(followed.rows.at(-1)?.ordinal).toBe(TOTAL);
    // Everything but the last three lines, whose outcomes are not written yet, is settled.
    expect(
      followed.rows
        .filter((each) => each.tool && each.detail === undefined)
        .map((each) => each.ordinal),
    ).toEqual(
      [TOTAL - 2, TOTAL - 1, TOTAL].filter((ordinal) => ordinal % 50 !== 0),
    );

    server.length = 0;
    server.push(
      ...Array.from({ length: 100 }, (_, at) => row(at + 1, 'tool_call', 'ok')),
    );
    // A person who pressed ↑ once and walked away: the cursor set, the screen where it was.
    const cursor = watchKeyed(watching(readAfter(0), true), 'up', 20).watch;
    const left = grow(cursor);
    expect(left.rows.length).toBeLessThanOrEqual(MOST_HELD);
    expect(watchCursor(left)?.ordinal).toBe(cursor.at);
    expect(left.cut).toBe(true);
    expect(watchKeyed(left, 'follow').then).toBe('latest');
  });

  it('reads a tool line as never to be settled once its run has ended, and not before', () => {
    const run = (id: string, state: string): Run => ({
      id,
      definition: 'd',
      tier: 'project',
      state,
      depth: 0,
      createdAt: '2026-09-28T09:00:00Z',
    });
    const tree = (state: string, phases: string[]): Tree => ({
      run: run('orc_1', state),
      stages: [],
      milestones: [],
      phases: phases.map((id) => ({ run: run(id, 'running'), stages: [] })),
    });
    const openIn = (runId: string): Recorded => ({
      ...row(4, 'tool_call'),
      run: runId,
    });
    expect(outcomeLost(tree('running', ['orc_2']))(openIn('orc_1'))).toBe(
      false,
    );
    expect(outcomeLost(tree('running', ['orc_2']))(openIn('orc_2'))).toBe(
      false,
    );
    // A phase no longer live, and a root that has ended: nothing will settle them.
    expect(outcomeLost(tree('running', []))(openIn('orc_2'))).toBe(true);
    expect(outcomeLost(tree('failed', ['orc_2']))(openIn('orc_1'))).toBe(true);
    expect(
      outcomeLost(tree('failed', []))({ ...openIn('orc_1'), detail: 'ok' }),
    ).toBe(false);
    expect(outcomeLost(tree('failed', []))(row(4))).toBe(false);
    expect(outcomeLost(undefined)(openIn('orc_1'))).toBe(false);
  });
});

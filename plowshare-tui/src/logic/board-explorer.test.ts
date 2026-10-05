import { describe, expect, it } from 'vitest';
import { demoBoard } from './board-demo.ts';
import {
  detailOf,
  listingBoard,
  readingBoard,
  readingSwarm,
  swarmOf,
  topicsOf,
} from './board.ts';
import {
  boardCursor,
  boardDescended,
  boardKeyed,
  boardLevel,
  boardOpened,
  boardRows,
  boardSelected,
  type BoardBrowser,
} from './board-explorer.ts';
import {
  boardInspector,
  boardScrollLimit,
  describeBoard,
} from './board-wording.ts';
import { typed } from './session.ts';
import { plainOf } from './tints.ts';
import { COMMANDS, describeCommand, describeArguments } from './wording.ts';

function browser(): BoardBrowser {
  const fixture = demoBoard(),
    b = boardOpened('board');
  b.topics = fixture.topics.value!;
  b.swarm = fixture.swarm.value!;
  b.activity = fixture.activity!;
  for (const [id, detail] of Object.entries(fixture.details))
    b.details[id] = detail.value!;
  return b;
}
const keyed = (b: BoardBrowser, key: Parameters<typeof boardKeyed>[1]) =>
  boardKeyed(b, key, 16, 200).browser;
const words = (b: BoardBrowser, columns = 120) =>
  describeBoard(b, { rows: 24, columns }).map(plainOf).join('\n');

describe('board reads and entry commands', () => {
  it('selects an explicit project, the current project, or the account', () => {
    expect(typed('/board')).toEqual({ kind: 'board', view: 'board' });
    expect(typed('/swarm', 'Plowshare')).toEqual({
      kind: 'board',
      view: 'swarm',
      project: 'Plowshare',
    });
    expect(typed('/board Other project', 'Plowshare')).toEqual({
      kind: 'board',
      view: 'board',
      project: 'Other project',
    });
    expect(typed('/boards')).toEqual({ kind: 'unknown', named: '/boards' });
    for (const command of ['/board', '/swarm']) {
      expect(COMMANDS).toContain(command);
      expect(describeCommand(command)).toContain('inspect');
      expect(describeArguments(command)).toContain('[project]');
    }
  });
  it('uses only observational server frames and validates every nested response', () => {
    expect(listingBoard('Plowshare', 200)).toEqual({
      type: 'board.topics',
      payload: { project: 'Plowshare', offset: 200, limit: 200 },
    });
    expect(listingBoard().payload).not.toHaveProperty('project');
    expect(readingBoard('demo-board')).toEqual({
      type: 'board.messages',
      payload: { topic: 'demo-board' },
    });
    expect(readingSwarm()).toEqual({ type: 'swarm.status', payload: {} });
    const fixture = demoBoard();
    expect(
      topicsOf({ topics: fixture.topics.value, more: false, offset: 0 }).topics,
    ).toHaveLength(2);
    expect(
      detailOf(fixture.details['demo-board']!.value).messages,
    ).toHaveLength(3);
    expect(swarmOf(fixture.swarm.value).ready).toHaveLength(1);
    expect(() =>
      topicsOf({ topics: [{ topic: { id: 'bad' } }], more: false, offset: 0 }),
    ).toThrow();
    expect(() =>
      detailOf({
        ...fixture.details['demo-board']!.value,
        seats: [{ state: 'running' }],
      }),
    ).toThrow();
    expect(() =>
      swarmOf({ ...fixture.swarm.value, ready: [{ member: 'bad' }] }),
    ).toThrow();
  });
});

describe('board and swarm navigation', () => {
  it('keeps root trees, project/state filters, and a stable cursor through reordering', () => {
    let b = browser();
    expect(boardRows(b).map((r) => r.id)).toEqual([
      'topic:demo-board',
      'topic:demo-child',
    ]);
    b = keyed(b, 'down');
    b.topics.unshift({
      ...b.topics[0]!,
      topic: {
        ...b.topics[0]!.topic,
        id: 'new',
        openedAt: '2027-01-01T00:00:00Z',
      },
    });
    expect(boardSelected(b)?.id).toBe('topic:demo-child');
    expect(boardCursor(b)).toBe(2);
    b.project = 'Elsewhere';
    expect(boardRows(b)).toEqual([]);
    delete b.project;
    b.topics[0]!.topic.state = 'closed';
    expect(boardRows(keyed(b, 'fold'))).toHaveLength(2);
    expect(boardRows(keyed(keyed(b, 'fold'), 'fold'))).toHaveLength(1);
  });
  it('opens topic seats, documents, child requests and their complete decisions', () => {
    let b = boardDescended(browser(), 'demo-board');
    expect(boardRows(b).map((r) => r.kind)).toEqual([
      'seat',
      'seat',
      'seat',
      'child',
      'message',
      'message',
      'message',
    ]);
    b = keyed(b, 'down');
    expect(boardSelected(b)?.kind).toBe('seat');
    expect(boardKeyed(b, 'descend', 16, 200).then).toBe('trajectory');
    b = keyed(b, 'bottom');
    const text = boardInspector(b, 80).map(plainOf).join('\n');
    expect(text).toContain('Approved');
    expect(text).toContain('Investigate independently');
    expect(text).toContain('demo-child');
    expect(boardKeyed(b, 'descend', 16, 200).then).toBe('trajectory');
    expect(boardLevel(keyed(b, 'ascend')).topic).toBeUndefined();
  });
  it('searches the full document body and clears the query without closing', () => {
    let b = keyed(boardDescended(browser(), 'demo-board'), 'search');
    b = keyed(b, { typed: 'stable identities' });
    expect(boardRows(b)).toHaveLength(1);
    expect(boardSelected(b)?.kind).toBe('message');
    b = keyed(b, 'enter');
    expect(b.typing).toBe(false);
    b = keyed(b, 'search');
    expect(boardKeyed(b, 'escape', 16, 200).then).toBe('draw');
    expect(keyed(b, 'escape').query).toBe('');
  });
  it('lists members rather than topics, with actions and direct trajectories', () => {
    let b = keyed(browser(), 'view');
    expect(b.view).toBe('swarm');
    expect(boardRows(b).map((r) => r.kind)).toEqual(['seat', 'seat', 'seat']);
    expect(words({ ...b, project: 'Other' })).toContain('No matching members.');
    expect(words(b)).toContain('spark 1/3 slots');
    const first = boardSelected(b)!;
    expect(first.kind === 'seat' && first.seat.seat.occupant).toBe(
      'researcher',
    );
    expect(boardKeyed(b, 'inspect', 16, 200).then).toBe('trajectory');
    expect(boardInspector(b, 80).map(plainOf).join('\n')).toContain(
      'search · offline conflict strategies · ok',
    );
    b = keyed(b, 'down');
    const selected = boardSelected(b)!;
    b.swarm!.seats[1]!.position = 9;
    expect(boardSelected(b)?.id).toBe(selected.id);
    expect(boardInspector(b, 80).map(plainOf).join('\n')).toContain(
      'Model: reasoning',
    );
    expect(keyed(b, 'follow').live).toBe(false);
    b.swarm!.more = true;
    expect(words(b, 200)).toContain('first 200 active topics');
  });
  it('provides a scrolling inspector on narrow terminals and fits the reserved region', () => {
    let b = boardDescended(browser(), 'demo-board');
    b = keyed(b, 'bottom');
    const message = b.details['demo-board']!.messages[2]!;
    message.body =
      Array.from({ length: 100 }, (_, i) => `Full paragraph ${i}`).join('\n') +
      '\nEND OF DOCUMENT';
    b = keyed(b, 'tab');
    const size = { rows: 24, columns: 80 };
    const limit = boardScrollLimit(b, size);
    b = boardKeyed(b, 'bottom', 16, limit).browser;
    expect(b.scroll).toBe(limit);
    expect(words(b, 80)).toContain('END OF DOCUMENT');
    for (const columns of [40, 80, 120, 160]) {
      const lines = describeBoard(b, { rows: 24, columns });
      expect(lines).toHaveLength(23);
      expect(
        lines.every((l) => [...Array.from(plainOf(l))].length <= columns - 1),
      ).toBe(true);
    }
  });
  it('sanitizes terminal controls in account data and refusal text', () => {
    const b = boardDescended(browser(), 'demo-board');
    b.details['demo-board']!.seats[0]!.seat.occupant =
      '\u001b[2Jbad\u001b]52;c;clipboard\u0007';
    b.error = '\u001b[31mrefused';
    expect(words(b)).not.toContain('\u001b');
    expect(words(b)).not.toContain('\u0007');
    expect(words(b)).toContain('refused');
  });
});

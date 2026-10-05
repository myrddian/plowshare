import { demoActivityPage } from 'plowshare-client-ts/operations/board-demo';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { demoBoard } from '../logic/board-demo.ts';
import type { ExploreKey } from '../logic/explorer.ts';
import type { Ask } from '../logic/session.ts';
import type { Answer } from 'plowshare-client-ts/operations/response';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import { plainOf, type Tinted } from '../logic/tints.ts';
import { inspectBoard } from './board.ts';
import { recording } from './surface.ts';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}
const tick = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};
function setup(readOverride?: (ask: Ask) => Promise<Answer>) {
  const fixture = demoBoard(),
    frames: Ask[] = [],
    screens: (readonly Tinted[] | undefined)[] = [];
  const queue: ExploreKey[] = [];
  let waiting: ((key: ExploreKey) => void) | undefined;
  let resized: (() => void) | undefined;
  let columns = 120;
  const surface = {
    ...recording(),
    explore: (lines: readonly Tinted[] | undefined) => {
      screens.push(lines);
    },
    exploreKey: () =>
      queue.length
        ? Promise.resolve(queue.shift()!)
        : new Promise<ExploreKey>((resolve) => {
            waiting = resolve;
          }),
    exploreSize: () => ({ rows: 24, columns }),
    onResize: (listener: () => void) => {
      resized = listener;
      return () => {
        resized = undefined;
      };
    },
  };
  const read = async (ask: Ask): Promise<Answer> => {
    frames.push(ask);
    if (readOverride !== undefined) return readOverride(ask);
    if (ask.type === 'board.topics')
      return {
        code: 'OK',
        payload: { topics: fixture.topics.value, more: false, offset: 0 },
      };
    if (ask.type === 'board.messages')
      return {
        code: 'OK',
        payload: fixture.details[String(ask.payload['topic'])]?.value,
      };
    if (ask.type === 'conversation.trajectory') {
      const activity =
        fixture.activity?.[String(ask.payload['conversation'])]?.value;
      return {
        code: 'OK',
        payload: demoActivityPage(activity),
      };
    }
    if (ask.type === 'swarm.status')
      return { code: 'OK', payload: fixture.swarm.value };
    throw new Error(`Unexpected mutation: ${ask.type}`);
  };
  const trajectory = vi.fn(
    async (_conversation: string, _label: string) => undefined,
  );
  const printed: string[] = [];
  return {
    fixture,
    frames,
    screens,
    trajectory,
    surface,
    context: {
      surface,
      read: async (ask: Ask) => {
        const answer = await read(ask);
        return {
          code: answer.code,
          ...(answer.said === undefined ? {} : { said: answer.said }),
          ...(answer.payload === undefined
            ? {}
            : { payload: decodeReply(ask.type, answer.payload) }),
        };
      },
      trajectory,
      print: (lines: readonly string[]) => {
        printed.push(...lines);
      },
    },
    printed,
    key: async (key: ExploreKey) => {
      if (waiting !== undefined) {
        const resolve = waiting;
        waiting = undefined;
        resolve(key);
      } else queue.push(key);
      await tick();
    },
    resize: (width: number) => {
      columns = width;
      resized?.();
    },
    text: () => screens.at(-1)?.map(plainOf).join('\n') ?? '',
  };
}
afterEach(() => vi.useRealTimers());

describe('the live board inspector', () => {
  it('closes immediately during the initial read and fences its late reply', async () => {
    const late = deferred<Answer>(),
      h = setup(() => late.promise);
    const viewing = inspectBoard(h.context, 'board');
    expect(h.text()).toContain('Esc stays available');
    await h.key('escape');
    await viewing;
    expect(h.screens.at(-1)).toBeUndefined();
    const draws = h.screens.length;
    late.resolve({
      code: 'OK',
      payload: { topics: h.fixture.topics.value, more: false, offset: 0 },
    });
    await tick();
    expect(h.screens).toHaveLength(draws);
    expect(h.frames.map((a) => a.type)).toEqual(['board.topics']);
  });
  it('fences late detail replies, stops periodic reads on close, and unregisters resize', async () => {
    vi.useFakeTimers();
    const late = deferred<Answer>();
    const h = setup(async (ask) =>
      ask.type === 'board.messages'
        ? late.promise
        : {
            code: 'OK',
            payload: {
              topics: demoBoard().topics.value,
              more: false,
              offset: 0,
            },
          },
    );
    const viewing = inspectBoard(h.context, 'board');
    await tick();
    expect(h.frames).toHaveLength(2);
    await h.key('close');
    await viewing;
    const draws = h.screens.length;
    late.resolve({
      code: 'OK',
      payload: h.fixture.details['demo-board']!.value,
    });
    await tick();
    await vi.advanceTimersByTimeAsync(12_000);
    h.resize(80);
    expect(h.screens).toHaveLength(draws);
    expect(h.frames).toHaveLength(2);
  });
  it('refreshes while live, pauses, retains stale snapshots, and recovers', async () => {
    vi.useFakeTimers();
    let refused = false;
    const h = setup(async (ask) =>
      refused
        ? { code: 'NOT_FOUND', said: 'board disconnected' }
        : {
            code: 'OK',
            payload:
              ask.type === 'board.topics'
                ? { topics: demoBoard().topics.value, more: false, offset: 0 }
                : demoBoard().details['demo-board']!.value,
          },
    );
    const viewing = inspectBoard(h.context, 'board');
    await tick();
    const initial = h.frames.length;
    refused = true;
    await vi.advanceTimersByTimeAsync(4000);
    expect(h.text()).toContain('STALE · board disconnected');
    expect(h.text()).toContain('How should device sync work?');
    await h.key('follow');
    const paused = h.frames.length;
    await vi.advanceTimersByTimeAsync(8000);
    expect(h.frames).toHaveLength(paused);
    expect(paused).toBeGreaterThan(initial);
    refused = false;
    await h.key('refresh');
    expect(h.text()).not.toContain('STALE');
    await h.key('escape');
    await viewing;
  });
  it('loads more pages, preserves the loaded prefix on refresh, and keeps identity', async () => {
    const offsets: number[] = [];
    const fixture = demoBoard();
    const h = setup(async (ask) => {
      if (ask.type === 'board.messages')
        return {
          code: 'OK',
          payload: fixture.details[String(ask.payload['topic'])]?.value,
        };
      if (ask.type !== 'board.topics') throw new Error('Expected board.topics');
      const offset = ask.payload.offset ?? 0;
      offsets.push(offset);
      return {
        code: 'OK',
        payload: {
          topics: [fixture.topics.value![offset]!],
          offset,
          more: offset === 0,
        },
      };
    });
    const viewing = inspectBoard(h.context, 'board', 'Plowshare');
    await tick();
    expect(h.text()).toContain('1 topics');
    await h.key('more');
    expect(h.text()).toContain('2 topics');
    await h.key('down');
    expect(h.text()).toContain('Compare conflict strategies');
    await h.key('refresh');
    expect(offsets.slice(-2)).toEqual([0, 1]);
    expect(
      h.frames.filter((a) => a.type === 'board.messages').at(-1)?.payload[
        'topic'
      ],
    ).toBe('demo-child');
    expect(
      h.frames.every(
        (a) =>
          a.type !== 'board.topics' || a.payload['project'] === 'Plowshare',
      ),
    ).toBe(true);
    await h.key('close');
    await viewing;
  });
  it('opens seat trajectories, returns to the board, switches to swarm and redraws on resize', async () => {
    const h = setup();
    const viewing = inspectBoard(h.context, 'board');
    await tick();
    await h.key('descend');
    expect(h.text()).toContain('researcher');
    await h.key('descend');
    expect(h.trajectory).toHaveBeenCalledWith(
      'demo-swarm-researcher',
      'researcher',
    );
    expect(h.text()).toContain(
      'Board · Your account › How should device sync work?',
    );
    await h.key('ascend');
    await h.key('view');
    expect(h.text()).toContain('Swarm · Your account');
    expect(h.text()).toContain('spark 1/3 slots');
    expect(h.frames.some((a) => a.type === 'swarm.status')).toBe(true);
    h.resize(80);
    expect(
      h.screens.at(-1)?.every((l) => [...Array.from(plainOf(l))].length <= 79),
    ).toBe(true);
    await h.key('close');
    await viewing;
  });
  it('opens member trajectories directly from Swarm and only reads activity tails', async () => {
    const h = setup();
    const viewing = inspectBoard(h.context, 'swarm');
    await tick();
    expect(h.text()).toContain('search · offline conflict strategies');
    expect(h.frames.some((a) => a.type.startsWith('board.'))).toBe(false);
    await h.key('inspect');
    expect(h.trajectory).toHaveBeenCalledWith(
      'demo-swarm-researcher',
      'researcher',
    );
    await h.key('down');
    await h.key('descend');
    expect(h.trajectory).toHaveBeenCalledWith(
      'demo-swarm-spec_writer',
      'spec_writer',
    );
    await h.key('close');
    await viewing;
  });
  it('keeps a refused trajectory visible after the board refreshes', async () => {
    const h = setup();
    h.trajectory.mockRejectedValue(new Error('seat conversation unavailable'));
    const viewing = inspectBoard(h.context, 'board');
    await tick();
    await h.key('descend');
    await h.key('descend');
    expect(h.text()).toContain(
      'Trajectory unavailable · seat conversation unavailable',
    );
    await h.key('refresh');
    expect(h.text()).not.toContain('Trajectory unavailable');
    await h.key('close');
    await viewing;
  });
  it('drops a superseded view response before loading its detail', async () => {
    const late = deferred<Answer>(),
      fixture = demoBoard();
    const h = setup(async (ask) =>
      ask.type === 'board.topics'
        ? late.promise
        : {
            code: 'OK',
            payload:
              ask.type === 'swarm.status'
                ? fixture.swarm.value
                : fixture.details['demo-board']!.value,
          },
    );
    const viewing = inspectBoard(h.context, 'board');
    await h.key('view');
    late.resolve({
      code: 'OK',
      payload: { topics: fixture.topics.value, more: false, offset: 0 },
    });
    await tick();
    expect(h.frames.map((a) => a.type)).toEqual([
      'board.topics',
      'swarm.status',
      'conversation.trajectory',
      'conversation.trajectory',
      'conversation.trajectory',
    ]);
    expect(h.text()).toContain('Swarm · Your account');
    await h.key('close');
    await viewing;
  });
  it('prints a refusal on a surface without keys without leaving a timer', async () => {
    const h = setup(async () => ({
      code: 'NOT_FOUND',
      said: 'old server has no board inspector',
    }));
    await inspectBoard({ ...h.context, surface: recording() }, 'board');
    expect(h.printed.join('\n')).toContain('old server has no board inspector');
    expect(h.frames).toHaveLength(1);
  });
});

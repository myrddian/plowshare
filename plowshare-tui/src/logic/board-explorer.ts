import {
  memberAction,
  memberKey,
  swarmMembers,
  type SwarmActivity,
} from './swarm.ts';
import type { Reading } from './board.ts';
import type {
  BoardMessage,
  BoardView,
  SeatView,
  SwarmStatus,
  TopicDetail,
  TopicSummary,
} from './board.ts';
import type { ExploreKey } from './explorer.ts';
import { cleaned } from './clean.ts';

export interface BoardBrowser {
  view: BoardView;
  project?: string;
  topics: TopicSummary[];
  more: boolean;
  pages: number;
  swarm?: SwarmStatus;
  activity: Record<string, Reading<SwarmActivity>>;
  details: Record<string, TopicDetail>;
  levels: { topic?: string; at?: string }[];
  focus: 'list' | 'inspector';
  scroll: number;
  query: string;
  typing: boolean;
  filter: 'all' | 'active' | 'closed';
  live: boolean;
  loading: boolean;
  error?: string;
  note?: string;
  updated?: string;
}
export type BoardRow =
  | { kind: 'topic'; id: string; topic: TopicSummary }
  | { kind: 'child'; id: string; topic: string; title: string }
  | {
      kind: 'seat';
      id: string;
      seat: SeatView;
      topic?: TopicSummary;
      activity?: Reading<SwarmActivity>;
    }
  | { kind: 'message'; id: string; message: BoardMessage };

export function boardOpened(view: BoardView, project?: string): BoardBrowser {
  return {
    view,
    ...(project === undefined ? {} : { project }),
    topics: [],
    more: false,
    pages: 1,
    details: {},
    activity: {},
    levels: [{}],
    focus: 'list',
    scroll: 0,
    query: '',
    typing: false,
    filter: 'all',
    live: true,
    loading: false,
  };
}
export const boardLevel = (b: BoardBrowser) => b.levels[b.levels.length - 1]!;
export function boardRows(b: BoardBrowser): BoardRow[] {
  const level = boardLevel(b),
    all = b.view === 'board' ? b.topics : (b.swarm?.topics ?? []);
  let rows: BoardRow[] = [];
  if (b.view === 'swarm') {
    rows = (b.swarm ? swarmMembers(b.swarm, b.project, b.activity) : []).map(
      (member) => ({
        kind: 'seat',
        id: `seat:${memberKey(member.seat)}`,
        ...member,
      }),
    );
  } else if (level.topic === undefined) {
    const topics = all.filter(
      (r) =>
        (b.project === undefined || r.topic.project === b.project) &&
        (b.view === 'swarm' ||
          b.filter === 'all' ||
          (b.filter === 'closed'
            ? r.topic.state === 'closed'
            : r.topic.state !== 'closed')),
    );
    const ids = new Set(topics.map((r) => r.topic.id)),
      seen = new Set<string>();
    const visit = (parent: string | null): void => {
      for (const r of topics
        .filter(
          (r) =>
            (r.topic.parent && ids.has(r.topic.parent)
              ? r.topic.parent
              : null) === parent,
        )
        .sort(
          (a, z) =>
            Number(a.topic.state === 'closed') -
              Number(z.topic.state === 'closed') ||
            z.topic.openedAt.localeCompare(a.topic.openedAt),
        )) {
        if (seen.has(r.topic.id)) continue;
        seen.add(r.topic.id);
        rows.push({ kind: 'topic', id: `topic:${r.topic.id}`, topic: r });
        visit(r.topic.id);
      }
    };
    visit(null);
    for (const r of topics)
      if (!seen.has(r.topic.id))
        rows.push({ kind: 'topic', id: `topic:${r.topic.id}`, topic: r });
  } else {
    const detail = b.details[level.topic];
    if (detail !== undefined) {
      rows.push(
        ...detail.seats.map((seat): BoardRow => ({
          kind: 'seat',
          id: `seat:${seat.seat.topic}:${seat.seat.occupant}`,
          seat,
        })),
      );
      const children = new Map(
        all
          .filter((r) => r.topic.parent === level.topic)
          .map((r) => [r.topic.id, r.topic.title]),
      );
      for (const d of detail.decisions)
        if (d.child !== null)
          children.set(
            d.child,
            detail.messages.find((m) => m.id === d.request)?.title ?? d.child,
          );
      rows.push(
        ...[...children].map(([topic, title]): BoardRow => ({
          kind: 'child',
          id: `topic:${topic}`,
          topic,
          title,
        })),
      );
      rows.push(
        ...detail.messages.map((message): BoardRow => ({
          kind: 'message',
          id: `message:${message.id}`,
          message,
        })),
      );
    }
  }
  const query = b.query.toLowerCase();
  return query === ''
    ? rows
    : rows.filter((r) => cleaned(rowWords(r)).toLowerCase().includes(query));
}
export function rowWords(row: BoardRow): string {
  switch (row.kind) {
    case 'topic':
      return `${row.topic.topic.title} ${row.topic.topic.label} ${row.topic.topic.project} ${row.topic.topic.state}`;
    case 'child':
      return row.title;
    case 'seat':
      return `${row.seat.seat.occupant} ${row.seat.state} ${row.seat.reason ?? ''} ${row.seat.seat.failedEnding ?? ''} ${row.topic?.topic.title ?? ''} ${row.topic?.topic.project ?? ''} ${row.activity ? memberAction(row) : ''}`;
    case 'message':
      return `${row.message.author} ${row.message.kind} ${row.message.title ?? ''} ${row.message.body}`;
  }
}
export function boardCursor(b: BoardBrowser): number {
  const rows = boardRows(b),
    index = rows.findIndex((r) => r.id === boardLevel(b).at);
  return index < 0 ? 0 : index;
}
export const boardSelected = (b: BoardBrowser) => boardRows(b)[boardCursor(b)];
export function boardTarget(b: BoardBrowser): string | undefined {
  const row = boardSelected(b);
  if (row?.kind === 'topic') return row.topic.topic.id;
  if (row?.kind === 'child') return row.topic;
  if (row?.kind === 'seat') return row.seat.seat.topic;
  return boardLevel(b).topic;
}
export function boardDescended(b: BoardBrowser, topic: string): BoardBrowser {
  return {
    ...b,
    levels: [...b.levels, { topic }],
    focus: 'list',
    scroll: 0,
    query: '',
    typing: false,
  };
}
export type BoardEffect =
  'draw' | 'close' | 'refresh' | 'more' | 'descend' | 'trajectory';
export function boardKeyed(
  b: BoardBrowser,
  key: ExploreKey,
  room: number,
  scrollLimit: number,
): { browser: BoardBrowser; then: BoardEffect } {
  const draw = (browser = b, then: BoardEffect = 'draw') => ({ browser, then });
  if (b.typing) {
    if (key === 'close') return draw(b, 'close');
    if (key === 'escape')
      return draw({ ...b, typing: false, query: '', scroll: 0 });
    if (key === 'enter') return draw({ ...b, typing: false });
    if (key === 'erase')
      return draw({
        ...b,
        query: [...Array.from(b.query)].slice(0, -1).join(''),
        scroll: 0,
      });
    if (typeof key === 'object')
      return draw({
        ...b,
        query: b.query + cleaned(key.typed).replace(/\n/gu, ''),
        scroll: 0,
      });
    return draw();
  }
  if (typeof key === 'object') return draw();
  if (key === 'close' || key === 'escape') return draw(b, 'close');
  if (key === 'search')
    return draw({ ...b, query: '', typing: true, scroll: 0 });
  if (key === 'refresh') return draw(b, 'refresh');
  if (key === 'more') return draw(b, 'more');
  if (key === 'follow')
    return draw({ ...b, live: !b.live }, b.live ? 'draw' : 'refresh');
  if (key === 'view')
    return draw(
      {
        ...b,
        view: b.view === 'board' ? 'swarm' : 'board',
        levels: [{}],
        query: '',
        scroll: 0,
        focus: 'list',
      },
      'refresh',
    );
  if (key === 'fold' && boardLevel(b).topic === undefined)
    return draw({
      ...b,
      filter:
        b.filter === 'all'
          ? 'active'
          : b.filter === 'active'
            ? 'closed'
            : 'all',
      scroll: 0,
    });
  if (
    key === 'inspect' &&
    b.view === 'swarm' &&
    boardSelected(b)?.kind === 'seat'
  )
    return draw(b, 'trajectory');
  if (key === 'tab' || key === 'inspect')
    return draw({ ...b, focus: b.focus === 'list' ? 'inspector' : 'list' });
  if (key === 'ascend')
    return draw(
      b.levels.length > 1
        ? {
            ...b,
            levels: b.levels.slice(0, -1),
            focus: 'list',
            scroll: 0,
            query: '',
          }
        : { ...b, focus: 'list' },
    );
  if (key === 'descend') {
    const row = boardSelected(b);
    return draw(
      b,
      row?.kind === 'seat' || row?.kind === 'message'
        ? 'trajectory'
        : row
          ? 'descend'
          : 'draw',
    );
  }
  if (['up', 'down', 'pageUp', 'pageDown', 'top', 'bottom'].includes(key)) {
    const distance =
      key === 'up' ? -1 : key === 'down' ? 1 : key === 'pageUp' ? -room : room;
    if (b.focus === 'inspector')
      return draw({
        ...b,
        scroll: Math.max(
          0,
          Math.min(
            scrollLimit,
            key === 'top'
              ? 0
              : key === 'bottom'
                ? scrollLimit
                : b.scroll + distance,
          ),
        ),
      });
    const rows = boardRows(b),
      at = Math.max(
        0,
        Math.min(
          rows.length - 1,
          key === 'top'
            ? 0
            : key === 'bottom'
              ? rows.length - 1
              : boardCursor(b) + distance,
        ),
      );
    const levels = [...b.levels];
    levels[levels.length - 1] = {
      ...boardLevel(b),
      ...(rows[at] === undefined ? {} : { at: rows[at].id }),
    };
    return draw({ ...b, levels, scroll: 0 });
  }
  return draw();
}

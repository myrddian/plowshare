import { memberAction, memberActions } from './swarm.ts';
import type { TopicDetail } from './board.ts';
import {
  boardCursor,
  boardRows,
  boardSelected,
  boardTarget,
  type BoardBrowser,
  type BoardRow,
} from './board-explorer.ts';
import { cleaned } from './clean.ts';
import {
  fitTinted,
  padTinted,
  selectedLine,
  tint,
  type Role,
  type Tinted,
} from './tints.ts';
import { wrapText } from './wrap.ts';
import type { ExploreSize } from './wording.ts';

const one = (v: string) => cleaned(v).replace(/\n/gu, ' ');
const line = (text: string, role: Role = 'text'): Tinted => [
  tint(one(text), role),
];
const stateRole = (state: string): Role =>
  ['failed', 'blocked', 'held'].includes(state)
    ? 'fail'
    : ['running', 'passed'].includes(state)
      ? 'ok'
      : ['ready', 'owed'].includes(state)
        ? 'waiting'
        : 'muted';
export function boardRoom(size: ExploreSize): number {
  return Math.max(1, size.rows - 8);
}
function widths(size: ExploreSize) {
  const width = Math.max(1, size.columns - 1),
    wide = width >= 110;
  const left = wide ? Math.min(46, Math.floor(width * 0.4)) : width;
  return { width, wide, left, right: wide ? width - left - 3 : width };
}
function summary(r: BoardRow): Tinted {
  switch (r.kind) {
    case 'topic':
      return [
        tint(
          `${'  '.repeat(Math.min(3, r.topic.topic.depth))}${r.topic.topic.parent ? '↳ ' : '◆ '}`,
          'rail',
        ),
        tint(one(r.topic.topic.title), 'strong'),
        tint(` · ${one(r.topic.topic.state)}`, stateRole(r.topic.topic.state)),
      ];
    case 'child':
      return line(`↳ ${r.title}`, 'milestone');
    case 'seat':
      return [
        tint(`${one(r.seat.seat.occupant)} · `, 'actor'),
        tint(one(r.seat.state), stateRole(r.seat.state)),
        tint(
          r.seat.position === null ? '' : ` · queue ${r.seat.position}`,
          'waiting',
        ),
        tint(r.activity ? ` · ${one(memberAction(r))}` : '', 'muted'),
      ];
    case 'message':
      return [
        tint(
          `${r.message.alert ? '⚑ ' : ''}${one(r.message.kind).toUpperCase()} `,
          'milestone',
        ),
        tint(
          one(`${r.message.author} · ${r.message.title ?? r.message.body}`),
          'text',
        ),
      ];
  }
}
const body = (text: string, width: number, role: Role = 'text'): Tinted[] =>
  wrapText(cleaned(text), width).map((t) => [tint(t, role)]);
function topicBody(d: TopicDetail, width: number): Tinted[] {
  const lines = [
    line(d.topic.title, 'strong'),
    line(`${d.topic.project} · ${d.topic.label} · ${d.topic.state}`, 'accent'),
    line(`${d.topic.id} · opened by ${d.topic.opener}`, 'muted'),
    line(
      `${d.root.potSpent ?? 0}/${d.root.potTotal ?? 0} model calls spent · ${d.root.reserve ?? 0} closing reserve${d.topic.parent ? ' · shared root budget' : ''}`,
      'waiting',
    ),
    line(''),
    line('Seats', 'strong'),
  ];
  for (const s of d.seats)
    lines.push(
      ...body(
        `${s.seat.occupant} · ${s.state}${s.position ? ` · queue ${s.position}` : ''}${s.reason ? ` · ${s.reason}` : ''}`,
        width,
        stateRole(s.state),
      ),
    );
  if (d.topic.resolution !== null)
    lines.push(
      line(''),
      line('Resolution', 'ok'),
      ...body(d.topic.resolution, width),
    );
  const opening = d.messages[0];
  if (opening !== undefined)
    lines.push(
      line(''),
      line(`Opening · ${opening.author}`, 'strong'),
      ...body(opening.body, width),
    );
  lines.push(
    line(''),
    line(
      `${d.messages.length} messages · → open topic for seats, documents and decisions`,
      'muted',
    ),
  );
  return lines;
}
export function boardInspector(b: BoardBrowser, width: number): Tinted[] {
  const row = boardSelected(b),
    id = boardTarget(b),
    d = id === undefined ? undefined : b.details[id];
  if (row === undefined)
    return [
      line(
        b.loading
          ? `Loading ${b.view}…`
          : b.error
            ? `${b.view === 'swarm' ? 'Swarm' : 'Board'} unavailable · r retry`
            : b.view === 'swarm'
              ? 'No matching members.'
              : 'No matching topics or rows.',
        'muted',
      ),
    ];
  if (row.kind === 'message') {
    const m = row.message,
      decision = d?.decisions.find((c) => c.request === m.id);
    const lines = [
      line(
        `${m.kind.toUpperCase()}${m.alert ? ' · ⚑ alert' : ''}`,
        'milestone',
      ),
      line(`${m.author} · ${m.postedAt}`, 'muted'),
    ];
    if (m.title !== null) lines.push(...body(m.title, width, 'strong'));
    if (m.replyTo !== null)
      lines.push(...body(`Reply to ${m.replyTo}`, width, 'muted'));
    lines.push(line(''), ...body(m.body, width));
    if (m.mentions.length)
      lines.push(
        line(''),
        ...body(
          `Mentions ${m.mentions.map((s) => '@' + s).join(' ')}`,
          width,
          'accent',
        ),
      );
    if (decision !== undefined)
      lines.push(
        line(''),
        line(
          decision.approved ? 'Approved' : 'Refused',
          decision.approved ? 'ok' : 'fail',
        ),
        ...body(decision.reason, width),
        ...(decision.child === null
          ? []
          : body(
              `Child topic: ${decision.child} · select its ↳ row to inspect`,
              width,
              'milestone',
            )),
      );
    else if (m.kind === 'request')
      lines.push(line(''), line('Awaiting a decision', 'waiting'));
    if (m.conversation !== null)
      lines.push(
        line(''),
        line('→ author trajectory · ← back to topics', 'muted'),
      );
    return lines;
  }
  if (row.kind === 'seat') {
    const s = row.seat,
      ready = b.swarm?.ready.find(
        (r) => r.topic === s.seat.topic && r.member === s.seat.occupant,
      );
    return [
      line(s.seat.occupant, 'strong'),
      line(s.state, stateRole(s.state)),
      ...(row.topic
        ? body(
            `${row.topic.topic.project} · ${row.topic.topic.title}`,
            width,
            'accent',
          )
        : []),
      ...body(
        `${s.seat.conversation}${s.job ? ` · job ${s.job}` : ''}`,
        width,
        'muted',
      ),
      ...(s.position === null
        ? []
        : body(
            `Queue ${s.position} · waited ${Math.floor((s.waitedMillis ?? 0) / 1000)}s${s.overdue ? ' · overdue' : ''}`,
            width,
            'waiting',
          )),
      ...(ready
        ? [
            ...body(`Model: ${ready.specifier}`, width, 'waiting'),
            ...body(
              'Queue positions show arrival order; fair sharing may serve a later arrival first.',
              width,
              'muted',
            ),
          ]
        : []),
      ...(s.reason === null ? [] : body(s.reason, width, 'waiting')),
      ...(s.seat.failedEnding === null
        ? []
        : body(`Failed: ${s.seat.failedEnding}`, width, 'fail')),
      line(
        `Silent wakes ${s.seat.silentWakes} · alerts used ${s.seat.alertsUsed}`,
        'muted',
      ),
      ...body(
        `Agent has read through ${s.seat.seenThrough ?? 'no messages'}`,
        width,
        'muted',
      ),
      line(''),
      line('→ inspect live seat trajectory', 'accent'),
      ...(b.view === 'swarm'
        ? [
            line(''),
            line('Recent recorded actions', 'strong'),
            ...(row.activity?.error
              ? body(`STALE · ${row.activity.error}`, width, 'fail')
              : []),
            ...(memberActions(row.activity?.value).length
              ? memberActions(row.activity?.value).flatMap((a) =>
                  body(`${a.ordinal} · ${a.text}`, width),
                )
              : body(memberAction(row), width, 'muted')),
          ]
        : []),
    ];
  }
  return d === undefined
    ? [
        line(
          b.loading ? 'Loading topic…' : 'Topic unavailable · r retry',
          'muted',
        ),
      ]
    : topicBody(d, width);
}
export function boardScrollLimit(b: BoardBrowser, size: ExploreSize): number {
  return Math.max(
    0,
    boardInspector(b, widths(size).right).length - boardRoom(size),
  );
}
export function describeBoard(b: BoardBrowser, size: ExploreSize): Tinted[] {
  const { width, wide, left, right } = widths(size),
    room = boardRoom(size),
    rows = boardRows(b),
    at = boardCursor(b);
  const breadcrumb = [
    b.project ?? 'Your account',
    ...b.levels.flatMap((l) =>
      l.topic === undefined ? [] : [b.details[l.topic]?.topic.title ?? l.topic],
    ),
  ].join(' › ');
  const pools =
    b.swarm?.pools
      .map((p) => `${p.pool} ${p.used}/${p.slots} slots`)
      .join(' · ') ?? '';
  const header = [
    line(`${b.view === 'board' ? 'Board' : 'Swarm'} · ${breadcrumb}`, 'strong'),
    line(
      b.view === 'board'
        ? `${b.topics.length} topics · ${b.filter} · ${b.more ? 'm load more · ' : ''}${b.live ? 'live' : 'paused'}`
        : `${pools || 'No swarm pools'} · ${b.swarm?.seats.length ?? 0} members · ${b.swarm?.ready.length ?? 0} queued${b.swarm?.more ? ' · first 200 active topics' : ''} · shared pools · ${b.live ? 'live' : 'paused'}`,
      'waiting',
    ),
    line(
      b.typing || b.query
        ? `/${b.query}${b.typing ? '▏ · enter apply · esc clear' : ' · / search again'}`
        : b.error
          ? `STALE · ${b.error}`
          : b.note
            ? b.note
            : b.loading
              ? 'Updating… · Esc stays available'
              : `Inspection only · ${b.updated ? 'updated ' + b.updated : 'waiting for data'}`,
      b.error || b.note ? 'fail' : 'muted',
    ),
    line('─'.repeat(width), 'rail'),
  ];
  const start = Math.max(
    0,
    Math.min(at - Math.floor(room / 2), rows.length - room),
  );
  const list = rows
    .slice(start, start + room)
    .map((r, i) =>
      (start + i === at ? selectedLine : (v: Tinted) => v)(
        fitTinted(summary(r), left),
      ),
    );
  if (!list.length)
    list.push(
      line(
        b.loading
          ? 'Loading…'
          : b.error
            ? 'Read failed · r retry'
            : b.view === 'swarm'
              ? 'No matching members. / search'
              : 'No matching topics. / search · t state filter',
        'muted',
      ),
    );
  const inspector = boardInspector(b, right).slice(
    Math.min(b.scroll, boardScrollLimit(b, size)),
    Math.min(b.scroll, boardScrollLimit(b, size)) + room,
  );
  const content: Tinted[] = [];
  for (let i = 0; i < room; i++) {
    content.push(
      wide
        ? [
            ...padTinted(fitTinted(list[i] ?? [], left), left),
            tint(' │ ', 'rail'),
            ...fitTinted(inspector[i] ?? [], right),
          ]
        : fitTinted((b.focus === 'list' ? list : inspector)[i] ?? [], width),
    );
  }
  return [
    ...header.map((l) => fitTinted(l, width)),
    ...content,
    line('─'.repeat(width), 'rail'),
    fitTinted(
      line(
        b.view === 'swarm'
          ? '↑↓ members · Tab inspect actions · Enter/→ trajectory · / search'
          : '↑↓ move · Tab/Enter inspect · → open/trajectory · ← back · / search',
        'muted',
      ),
      width,
    ),
    fitTinted(
      line(
        b.view === 'swarm'
          ? 'v board · r refresh · f live/pause · Esc/q close'
          : 'v swarm · t states · m more · r refresh · f live/pause · Esc/q close',
        'muted',
      ),
      width,
    ),
  ];
}

import { isList } from '../binding/values.ts';
import type { Reading, SeatView, SwarmStatus, TopicSummary } from './board.ts';
import { backPageOf, type Ask, type Entry } from './client-views.ts';
import { cleaned } from './clean.ts';
import { stepsOf, type Step } from './trajectory.ts';

export interface SwarmActivity {
  entries: readonly Entry[];
  through: number;
}
export interface SwarmMember {
  seat: SeatView;
  topic?: TopicSummary;
  activity?: Reading<SwarmActivity>;
}
export const SWARM_PAGE = 40;
export const memberKey = (seat: SeatView) =>
  `${seat.seat.topic}:${seat.seat.occupant}`;
export function swarmMembers(
  snapshot: SwarmStatus,
  project?: string,
  activity: Record<string, Reading<SwarmActivity>> = {},
): SwarmMember[] {
  const topics = new Map(snapshot.topics.map((t) => [t.topic.id, t]));
  const rank = [
    'running',
    'ready',
    'owed',
    'blocked',
    'held',
    'failed',
    'idle',
    'silent',
    'passed',
    'closed',
  ];
  return snapshot.seats
    .flatMap((seat) => {
      const topic = topics.get(seat.seat.topic),
        reading = activity[seat.seat.conversation];
      if (project !== undefined && topic?.topic.project !== project) return [];
      return [
        {
          seat,
          ...(topic === undefined ? {} : { topic }),
          ...(reading === undefined ? {} : { activity: reading }),
        },
      ];
    })
    .sort((a, b) => {
      const score = (state: string) =>
        rank.indexOf(state) < 0 ? rank.length : rank.indexOf(state);
      return (
        score(a.seat.state) - score(b.seat.state) ||
        a.seat.seat.topic.localeCompare(b.seat.seat.topic) ||
        a.seat.seat.occupant.localeCompare(b.seat.seat.occupant)
      );
    });
}
/** Small observational tails, with every row validated before they are shown as activity. */
export const SWARM_ACTIVITY = 'conversation.trajectory';
export function readingSwarmActivity(conversation: string): Ask {
  return {
    type: SWARM_ACTIVITY,
    payload: { conversation, tail: true, limit: 20 },
  };
}
export function swarmActivityOf(payload: unknown): SwarmActivity {
  const raw = payload as { entries?: unknown[]; through?: unknown } | null;
  if (
    !raw ||
    !isList(raw.entries) ||
    !Number.isSafeInteger(raw.through) ||
    Number(raw.through) < 0
  )
    throw new Error('The server returned incomplete member activity.');
  for (const item of raw.entries) {
    const row = item as Record<string, unknown> | null;
    if (
      !row ||
      typeof row !== 'object' ||
      !Number.isSafeInteger(row['ordinal']) ||
      Number(row['ordinal']) < 1 ||
      !Number.isSafeInteger(row['turnOrdinal']) ||
      Number(row['turnOrdinal']) < 0 ||
      typeof row['kind'] !== 'string' ||
      row['kind'] === '' ||
      // EntryView exposes retention coordinates; state is derived by backPageOf.
      (row['state'] !== undefined && typeof row['state'] !== 'string') ||
      (row['toolCalls'] != null && !isList(row['toolCalls']))
    )
      throw new Error('The server returned unreadable member activity.');
  }
  if (
    new Set(
      raw.entries.map((item) => (item as Record<string, unknown>)['ordinal']),
    ).size !== raw.entries.length
  )
    throw new Error('The server returned duplicate member activity.');
  const page = backPageOf({ code: 'OK', payload });
  if (
    !page ||
    page.entries.length !== raw.entries.length ||
    page.entries.some((entry) => {
      const row = raw.entries!.find(
        (item) =>
          (item as Record<string, unknown>)['ordinal'] === entry.ordinal,
      ) as Record<string, unknown>;
      return (
        entry.ordinal > Number(raw.through) ||
        (isList(row['toolCalls']) &&
          (entry.calls?.length ?? 0) !== row['toolCalls'].length)
      );
    })
  )
    throw new Error('The server returned unreadable member activity.');
  return { entries: page.entries, through: page.through };
}
export function memberActions(
  activity?: SwarmActivity,
): { ordinal: number; text: string }[] {
  const text = (s: string) =>
    [...Array.from(cleaned(s).replace(/\s+/gu, ' ').trim())]
      .slice(0, 240)
      .join('');
  const words = (step: Step): string => {
    if (step.kind === 'call')
      return `${text(step.tool)}${step.salient ? ' · ' + text(step.salient) : ''} · ${step.pending ? 'awaiting result' : step.unanswered ? 'no recorded result' : text(step.result?.outcome ?? 'result recorded')}`;
    const kind =
      step.kind === 'reasoning'
        ? 'Reasoning'
        : step.kind === 'answer'
          ? 'Answer'
          : step.kind === 'person'
            ? 'Wake'
            : 'Note';
    return `${kind} · ${text(step.entry.text ?? '')}`;
  };
  const ordinal = (step: Step) =>
    step.kind === 'call'
      ? (step.result?.ordinal ?? step.ordinal)
      : step.ordinal;
  return stepsOf(activity?.entries ?? [])
    .filter((s) => s.kind !== 'fold')
    .sort(
      (a, b) =>
        ordinal(b) - ordinal(a) ||
        Number(b.kind === 'call') - Number(a.kind === 'call'),
    )
    .slice(0, 6)
    .map((step) => ({ ordinal: ordinal(step), text: words(step) }));
}
export function memberAction(member: SwarmMember): string {
  const latest = memberActions(member.activity?.value)[0];
  if (latest) return latest.text;
  if (member.activity?.error) return 'Activity unavailable';
  if (member.activity?.loading || member.activity === undefined)
    return 'Loading recorded activity…';
  return 'No recorded actions yet';
}

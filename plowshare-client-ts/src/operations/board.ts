import type { SwarmActivity } from './swarm.ts'
import type { Ask } from './client-views.ts'

export type BoardView = 'board' | 'swarm';
export interface Topic {
  id: string; project: string; parent: string | null; root: string; depth: number;
  title: string; label: string; account: string; openerKind: string; opener: string;
  originConversation: string | null; state: string; resolution: string | null;
  potTotal: number | null; potSpent: number | null; reserve: number | null;
  openedAt: string; closedAt: string | null;
}
export interface TopicSummary { topic: Topic; messages: number; documents: number }
export interface BoardMessage {
  id: string; topic: string; replyTo: string | null; authorKind: string; author: string;
  conversation: string | null; entry: number | null; kind: string; title: string | null;
  body: string; alert: boolean; mentions: string[]; postedAt: string;
}
export interface SeatView {
  seat: { topic: string; occupant: string; conversation: string; passed: boolean;
    failedEnding: string | null; silentWakes: number; seenThrough: string | null; alertsUsed: number };
  state: string; job: string | null; reason: string | null; position: number | null;
  waitedMillis: number | null; overdue: boolean;
}
export interface TopicDetail { topic: Topic; root: Topic; messages: BoardMessage[]; seats: SeatView[];
  decisions: { request: string; approved: boolean; reason: string; child: string | null }[] }
export interface SwarmStatus {
  pools: { pool: string; slots: number; used: number }[];
  ready: { topic: string; member: string; specifier: string; position: number; waitedMillis: number; overdue: boolean }[];
  topics: TopicSummary[]; seats: SeatView[]; more: boolean;
}
export interface Reading<T> { value?: T; loading?: boolean; error?: string; updatedAt?: string }
export interface BoardInspection {
  view?: BoardView; project?: string; selected?: string;
  memberLimit?: number; activity?: Record<string, Reading<SwarmActivity>>;
  topics: Reading<TopicSummary[]> & { more?: boolean };
  details: Record<string, Reading<TopicDetail>>; swarm: Reading<SwarmStatus>;
}
export const emptyBoard = (): BoardInspection => ({ topics: {}, details: {}, swarm: {} });

const obj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const str = (v: unknown): v is string => typeof v === 'string';
const num = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;
const nullable = (test: (v: unknown) => boolean) => (v: unknown) => v === null || test(v);
const fields = (v: unknown, names: string[], test: (v: unknown) => boolean) => obj(v) && names.every(k => test(v[k]));
const array = (v: unknown, test: (v: unknown) => boolean) => Array.isArray(v) && v.every(test);
const topic = (v: unknown): v is Topic => fields(v, ['id','project','root','title','label','account','openerKind','opener','state','openedAt'], str)
  && fields(v, ['parent','originConversation','resolution','closedAt'], nullable(str)) && fields(v, ['potTotal','potSpent','reserve'], nullable(num)) && obj(v) && num(v['depth']);
const summary = (v: unknown): v is TopicSummary => obj(v) && topic(v['topic']) && num(v['messages']) && num(v['documents']);
const seat = (v: unknown): v is SeatView => obj(v) && fields(v['seat'], ['topic','occupant','conversation'], str)
  && fields(v['seat'], ['failedEnding','seenThrough'], nullable(str)) && fields(v['seat'], ['silentWakes','alertsUsed'], num)
  && obj(v['seat']) && typeof v['seat']['passed'] === 'boolean' && str(v['state']) && fields(v, ['job','reason'], nullable(str))
  && fields(v, ['position','waitedMillis'], nullable(num)) && typeof v['overdue'] === 'boolean';
const message = (v: unknown): v is BoardMessage => fields(v, ['id','topic','authorKind','author','kind','body','postedAt'], str)
  && fields(v, ['replyTo','conversation','title'], nullable(str)) && obj(v) && nullable(num)(v['entry']) && typeof v['alert'] === 'boolean' && array(v['mentions'], str);
const decision = (v: unknown) => obj(v) && fields(v, ['request','reason'], str) && typeof v['approved'] === 'boolean' && nullable(str)(v['child']);
export function topicsOf(v: unknown): { topics: TopicSummary[]; more: boolean; offset: number } {
  if (!obj(v) || !array(v['topics'], summary) || typeof v['more'] !== 'boolean' || !num(v['offset'])) throw new Error('The server returned an incomplete topic list.');
  return v as unknown as { topics: TopicSummary[]; more: boolean; offset: number };
}
export function detailOf(v: unknown): TopicDetail {
  if (!obj(v) || !topic(v['topic']) || !topic(v['root']) || !array(v['seats'], seat) || !array(v['messages'], message) || !array(v['decisions'], decision)) throw new Error('The server returned incomplete topic detail.');
  const detail = v as unknown as TopicDetail;
  if (detail.topic.root !== detail.root.id || detail.seats.some(s => s.seat.topic !== detail.topic.id) || detail.messages.some(m => m.topic !== detail.topic.id)) throw new Error('The server returned mismatched topic detail.');
  return detail;
}
export function swarmOf(v: unknown): SwarmStatus {
  if (!obj(v) || !array(v['pools'], p => obj(p) && str(p['pool']) && num(p['slots']) && num(p['used']))
    || !array(v['ready'], r => fields(r, ['topic','member','specifier'], str) && fields(r, ['position','waitedMillis'], num) && obj(r) && typeof r['overdue'] === 'boolean')
    || !array(v['topics'], summary) || !array(v['seats'], seat) || typeof v['more'] !== 'boolean') throw new Error('The server returned an incomplete swarm snapshot.');
  return v as unknown as SwarmStatus;
}


export const BOARD_TOPICS = 'board.topics'
export const BOARD_MESSAGES = 'board.messages'
export const SWARM_STATUS = 'swarm.status'

export function listingBoard(project?: string, offset = 0): Ask {
    return { type: BOARD_TOPICS, payload: { ...(project === undefined ? {} : { project }), offset, limit: 200 } }
}
export function readingBoard(topic: string): Ask {
    return { type: BOARD_MESSAGES, payload: { topic } }
}
export function readingSwarm(): Ask {
    return { type: SWARM_STATUS, payload: {} }
}

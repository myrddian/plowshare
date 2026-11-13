import { object, text } from './config.ts';
import { uuid } from './state.ts';
import type { MessageTask, MessageHeader } from './contracts.ts';

export type RefusalCode =
  'NOT_ACCESSIBLE' | 'REQUEST_CONFLICT' | 'COMMAND_REFUSED' | 'INVALID_REQUEST';
export type ReplyChunk = MessageTask['replies'][number] & {
  readonly offset: string;
  readonly complete: boolean;
};
export type TaskPage = Omit<MessageTask, 'replies' | 'cursor'> & {
  readonly replies: readonly ReplyChunk[];
};
export interface TaskSnapshot {
  readonly requestId: string;
  readonly sourceEvent: string;
  readonly client: string;
  readonly task: TaskPage | null;
  readonly refusal: RefusalCode | null;
  readonly next: string | null;
  readonly resume: string | null;
}
function exact(
  value: unknown,
  keys: readonly string[],
): Record<string, unknown> {
  const row = object(value, keys);
  if (
    Object.keys(row).length !== keys.length ||
    keys.some((key) => !(key in row))
  )
    throw new Error('Incomplete messaging snapshot');
  return row;
}
function narrative(value: unknown, maximum: number): string {
  if (
    typeof value !== 'string' ||
    value.includes('\0') ||
    /[\uD800-\uDFFF]/u.test(value) ||
    Buffer.byteLength(value) > maximum
  )
    throw new Error('Invalid messaging text');
  return value;
}
function instant(value: unknown): string {
  const result = text(value, 64);
  if (
    !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)$/.test(
      result,
    ) ||
    !Number.isFinite(Date.parse(result))
  )
    throw new Error('Invalid messaging timestamp');
  return result;
}
function ending(value: unknown): string | null {
  if (value === null) return null;
  const result = text(value, 64);
  if (
    ![
      'ANSWERED',
      'TURN_CAP',
      'CALL_BUDGET',
      'CANCELLED',
      'STUCK',
      'UNAVAILABLE',
      'SUB_AGENT_FAILED',
      'SESSION_GONE',
      'AWAITING',
    ].includes(result)
  )
    throw new Error('Invalid messaging ending');
  return result;
}
function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid messaging flag');
  return value;
}
export function revision(value: unknown, allowZero = false): string {
  const result = text(value, 19);
  if (
    !/^(0|[1-9][0-9]*)$/.test(result) ||
    BigInt(result) > 9223372036854775807n ||
    (!allowZero && result === '0')
  )
    throw new Error('Invalid messaging revision');
  return result;
}
/** Parse once, rejecting duplicate keys before any DTO leaves this JSON boundary. */
function parsed(source: string): unknown {
  if (Buffer.byteLength(source) > 5 * 1024 * 1024)
    throw new Error('Messaging snapshot exceeds bound');
  const value: unknown = JSON.parse(source);
  const stack: (Set<string> | null)[] = [];
  for (let index = 0; index < source.length; index++) {
    const character = source[index];
    if (character === '{') stack.push(new Set());
    else if (character === '[') stack.push(null);
    else if (character === '}' || character === ']') stack.pop();
    else if (character === '"') {
      const start = index++;
      for (; index < source.length; index++) {
        if (source[index] === '\\') index++;
        else if (source[index] === '"') break;
      }
      let next = index + 1;
      while (/\s/.test(source[next] ?? '') && next < source.length) next++;
      const keys = stack.at(-1);
      if (source[next] === ':' && keys instanceof Set) {
        const key: unknown = JSON.parse(source.slice(start, index + 1));
        if (typeof key !== 'string' || keys.has(key))
          throw new Error('Duplicate messaging field');
        keys.add(key);
      }
    }
  }
  return value;
}
export function taskSnapshot(source: string): TaskSnapshot {
  const root = exact(parsed(source), [
    'schema',
    'requestId',
    'sourceEvent',
    'client',
    'revision',
    'task',
    'refusal',
    'next',
    'resume',
  ]);
  if (root.schema !== 'plowshare-message-task/1')
    throw new Error('Unsupported messaging snapshot');
  const correlation = {
    requestId: uuid(root.requestId),
    sourceEvent: uuid(root.sourceEvent),
    client: clientAlias(root.client),
  };
  if (root.task === null) {
    if (root.revision !== null || root.next !== null || root.resume !== null)
      throw new Error('Invalid negative messaging snapshot');
    return {
      ...correlation,
      task: null,
      refusal: refusalCode(root.refusal),
      next: null,
      resume: null,
    };
  }
  if (root.refusal !== null) throw new Error('Conflicting messaging outcome');
  const next = root.next === null ? null : text(root.next, 4096);
  const resume = root.resume === null ? null : text(root.resume, 4096);
  if ((next === null) === (resume === null))
    throw new Error('Invalid messaging page cursor');
  const row = exact(root.task, [
    'id',
    'context',
    'requestId',
    'agent',
    'message',
    'state',
    'ending',
    'replies',
    'createdAt',
  ]);
  if (!Array.isArray(row.replies) || row.replies.length > 2)
    throw new Error('Invalid messaging reply list');
  const replies = row.replies.map((value: unknown) => {
    const reply = exact(value, [
      'id',
      'body',
      'offset',
      'complete',
      'finalReply',
      'generated',
      'ending',
      'postedAt',
    ]);
    const body = narrative(reply.body, 65536);
    if (Array.from(body).length > 16384)
      throw new Error('Oversized messaging reply chunk');
    return {
      id: text(reply.id, 1024),
      body,
      offset: revision(reply.offset, true),
      complete: flag(reply.complete),
      finalReply: flag(reply.finalReply),
      generated: flag(reply.generated),
      ending: ending(reply.ending),
      postedAt: instant(reply.postedAt),
    };
  });
  if (
    new Set(replies.map((reply) => reply.id + '\n' + reply.offset)).size !==
    replies.length
  )
    throw new Error('Duplicate messaging chunk');
  const header = messageHeader({
    id: row.id,
    requestId: row.requestId,
    context: row.context,
    agent: row.agent,
    message: row.message,
    state: row.state,
    ending: row.ending,
    createdAt: row.createdAt,
    revision: root.revision,
  });
  return {
    ...correlation,
    next,
    resume,
    refusal: null,
    task: { ...header, replies },
  };
}

/** A valid server-owned negative snapshot proves refusal, never permission to replay. */
export class MessagingRefusal extends Error {
  constructor(readonly code: RefusalCode) {
    super('Messaging command was refused: ' + code);
  }
}
function refusalCode(value: unknown): RefusalCode {
  if (
    value !== 'NOT_ACCESSIBLE' &&
    value !== 'REQUEST_CONFLICT' &&
    value !== 'COMMAND_REFUSED' &&
    value !== 'INVALID_REQUEST'
  )
    throw new Error('Invalid messaging refusal');
  return value;
}

function clientAlias(value: unknown): string {
  const alias = text(value, 64);
  if (!/^[a-zA-Z0-9_.-]+$/.test(alias))
    throw new Error('Invalid messaging client alias');
  return alias;
}

/** Validate persisted authoritative headers using the same invariants as live snapshots. */
export function messageHeader(value: unknown): MessageHeader {
  const row = exact(value, [
    'id',
    'requestId',
    'context',
    'agent',
    'message',
    'state',
    'ending',
    'createdAt',
    'revision',
  ]);
  const state = row.state;
  if (
    state !== 'SUBMITTED' &&
    state !== 'WORKING' &&
    state !== 'INPUT_REQUIRED' &&
    state !== 'COMPLETED' &&
    state !== 'FAILED' &&
    state !== 'CANCELED'
  )
    throw new Error('Invalid messaging state');
  const outcome = ending(row.ending);
  if (state === 'COMPLETED' && outcome !== 'ANSWERED')
    throw new Error('Completed messaging task lacks an answer');
  return {
    id: uuid(row.id),
    requestId: uuid(row.requestId),
    context: uuid(row.context),
    agent: text(row.agent, 256),
    message: text(row.message, 1024),
    state,
    ending: outcome,
    createdAt: instant(row.createdAt),
    revision: revision(row.revision),
  };
}
export function retainedReply(value: unknown): MessageTask['replies'][number] {
  const row = exact(value, [
    'id',
    'body',
    'finalReply',
    'generated',
    'ending',
    'postedAt',
  ]);
  return {
    id: text(row.id, 1024),
    body: narrative(row.body, 1_048_576),
    finalReply: flag(row.finalReply),
    generated: flag(row.generated),
    ending: ending(row.ending),
    postedAt: instant(row.postedAt),
  };
}

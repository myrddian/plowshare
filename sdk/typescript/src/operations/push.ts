import { CURRENT_VERSION } from '../binding/envelope.ts';
import { isObject, isOneOf } from '../binding/values.ts';
import { decodeReply } from './schema.ts';
import type { UsageReport } from './usage.ts';

import {
  jobNotification,
  type JobNotification,
} from '../binding/job-notification.ts';
export { jobNotification } from '../binding/job-notification.ts';
export type {
  JobEvent,
  JobDelta,
  JobNotification,
} from '../binding/job-notification.ts';
export type AccountEvent =
  | { readonly kind: 'inbox.changed'; readonly unread: number }
  | {
      readonly kind: 'information.changed';
      readonly sequence: number;
      readonly revision: string;
      readonly generation: number;
    }
  | {
      readonly kind: 'orchestration.changed';
      readonly orchestration: string;
      readonly state:
        | 'running'
        | 'asking'
        | 'waiting'
        | 'finished'
        | 'failed'
        | 'capped'
        | 'cancelled';
    }
  | {
      readonly kind: 'orchestration.resumed';
      readonly orchestration: string;
      readonly requestId: string;
    }
  | {
      readonly kind: 'orchestration.recorded';
      readonly root: string;
      readonly through: number;
      readonly settled?: number;
    }
  | { readonly kind: 'todos.changed'; readonly conversation: string }
  | {
      readonly kind: 'conversation.appended';
      readonly conversation: string;
      readonly through: number;
    };
export type ServerPush =
  | JobNotification
  | AccountEvent
  | {
      readonly type: 'usage.updated';
      readonly subscription: string;
      readonly revision: number;
      readonly report: UsageReport;
    }
  | {
      readonly type: 'usage.closed';
      readonly subscription: string;
      readonly code: 'BAD_REQUEST';
    };
const whole = (v: unknown): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const identity = (v: unknown, max = 1024): v is string =>
  typeof v === 'string' &&
  !!v.trim() &&
  v.length <= max &&
  !Array.from(v).some(
    (c) =>
      c.charCodeAt(0) < 32 ||
      (c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159) ||
      c === '\u2028' ||
      c === '\u2029',
  );
const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
/** The public SDK accepts the current server contract, including complete job counters. */
export function decodeServerPush(value: unknown): ServerPush {
  const fail = (): never => {
    throw new Error(
      'Unreadable server notification; reconcile through durable reads.',
    );
  };
  if (!isObject(value)) return fail();
  if ('protocol_version' in value) {
    if (
      value.protocol_version !== CURRENT_VERSION ||
      value.id !== null ||
      !isObject(value.payload)
    )
      return fail();
    const row = value.payload;
    if (!uuid(row.subscription)) return fail();
    if (value.type === 'usage.closed' && row.code === 'BAD_REQUEST')
      return {
        type: 'usage.closed',
        subscription: row.subscription,
        code: 'BAD_REQUEST',
      };
    if (
      value.type === 'usage.updated' &&
      whole(row.revision) &&
      row.revision >= 1
    ) {
      if (
        !isObject(row.report) ||
        !isObject(row.report.filters) ||
        !isOneOf(row.report.filters.type, [
          'usage.conversation',
          'usage.project',
          'usage.agent',
          'usage.run',
          'usage.orchestration',
          'usage.models',
          'usage.pools',
        ])
      )
        return fail();
      return {
        type: 'usage.updated',
        subscription: row.subscription,
        revision: row.revision,
        report: decodeReply(row.report.filters.type, row.report),
      };
    }
    return fail();
  }
  if ('job' in value) {
    const event = jobNotification(value);
    if (
      !event ||
      (!('part' in event) &&
        (!identity(event.agent, 256) ||
          !whole(event.steps) ||
          !whole(event.modelCalls)))
    )
      return fail();
    if (
      !('part' in event) &&
      ((event.kind === 'tool_called' && !identity(event.tool, 256)) ||
        (event.kind === 'ended' &&
          (typeof event.ending !== 'string' ||
            !/^[A-Z_]{1,64}$/.test(event.ending))))
    )
      return fail();
    return event;
  }
  switch (value.kind) {
    case 'inbox.changed':
      return whole(value.unread)
        ? { kind: value.kind, unread: value.unread }
        : fail();
    case 'information.changed':
      return whole(value.sequence) &&
        value.sequence >= 1 &&
        uuid(value.revision) &&
        whole(value.generation) &&
        value.generation >= 1
        ? {
            kind: value.kind,
            sequence: value.sequence,
            revision: value.revision,
            generation: value.generation,
          }
        : fail();
    case 'orchestration.changed':
      return identity(value.orchestration) &&
        isOneOf(value.state, [
          'running',
          'asking',
          'waiting',
          'finished',
          'failed',
          'capped',
          'cancelled',
        ])
        ? {
            kind: value.kind,
            orchestration: value.orchestration,
            state: value.state,
          }
        : fail();
    case 'orchestration.resumed':
      return identity(value.orchestration) && uuid(value.requestId)
        ? {
            kind: value.kind,
            orchestration: value.orchestration,
            requestId: value.requestId,
          }
        : fail();
    case 'orchestration.recorded':
      return identity(value.root) &&
        whole(value.through) &&
        (!('settled' in value) || whole(value.settled))
        ? {
            kind: value.kind,
            root: value.root,
            through: value.through,
            ...(whole(value.settled) ? { settled: value.settled } : {}),
          }
        : fail();
    case 'todos.changed':
      return identity(value.conversation)
        ? { kind: value.kind, conversation: value.conversation }
        : fail();
    case 'conversation.appended':
      return identity(value.conversation) && whole(value.through)
        ? {
            kind: value.kind,
            conversation: value.conversation,
            through: value.through,
          }
        : fail();
    default:
      return fail();
  }
}

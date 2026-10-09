import { validRelayText } from '../binding/relay-text.ts';
import { validateRelayEvent } from './relay.ts';
import type { RelayEvent } from './relay.ts';

/** Topic ports are project-scoped. Published TEXT may contain an application-owned JSON protocol. */
export interface RelayPublishRequest {
  readonly requestId: string;
  readonly project: string;
  readonly topic: string;
  readonly text: string;
  readonly occurredAt: string;
  readonly correlationId?: string | null;
  readonly parentTopic?: string | null;
  readonly parentEventId?: string | null;
}
export interface RelayConsumeRequest {
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly consumerId: string;
  readonly start: 'OLDEST_RETAINED' | 'LATEST';
  readonly limit?: number;
  readonly waitMs?: number;
}
export interface RelayAckRequest {
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly consumerId: string;
  readonly batchId: string;
  readonly fence: string;
  readonly expiredThrough?: string | null;
}
export interface RelayBatch {
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly consumerId: string;
  readonly status: 'DATA' | 'EMPTY' | 'GAP' | 'BUSY';
  readonly batchId: string | null;
  readonly fence: string | null;
  readonly through: string;
  readonly expiresAt: string | null;
  readonly expiredThrough: string | null;
  readonly events: readonly RelayEvent[];
}
export interface RelayPortPayloads {
  'relay.publish': RelayPublishRequest;
  'relay.consume': RelayConsumeRequest;
  'relay.ack': RelayAckRequest;
}
export interface RelayPortReplies {
  'relay.publish': {
    readonly requestId: string;
    readonly project: string;
    readonly topic: string;
    readonly position: string;
    readonly publishedAt: string;
  };
  'relay.consume': RelayBatch;
  'relay.ack': {
    readonly project: string;
    readonly topic: string;
    readonly group: string;
    readonly batchId: string;
    readonly through: string;
    readonly gap: boolean;
  };
}
/** Application DTOs for an external detector; these are encoded inside the topic's TEXT. */
export interface FilterReviewRequest {
  readonly version: 1;
  readonly requestId: string;
  readonly sourceHash: string;
  readonly role: 'system' | 'user' | 'assistant' | 'tool';
  readonly message: string;
}
export interface FilterReviewResponse {
  readonly version: 1;
  readonly requestId: string;
  readonly sourceHash: string;
  readonly accepted: boolean;
  readonly message: string | null;
  readonly reason: string;
}

const name = (v: unknown, max = 160): boolean =>
  typeof v === 'string' &&
  v.length <= max &&
  /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/.test(v);
const identity = (v: unknown): boolean =>
  typeof v === 'string' &&
  v.length <= 256 &&
  v.trim().length > 0 &&
  v === v.trim() &&
  !Array.from(v).some((character) => {
    const code = character.codePointAt(0) ?? 0;
    return (
      code < 32 ||
      (code >= 127 && code <= 159) ||
      code === 0x2028 ||
      code === 0x2029
    );
  });
const uuid = (v: unknown): boolean =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(v);
const decimal = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^(?:0|[1-9][0-9]{0,18})$/.test(v) &&
  BigInt(v) <= 9223372036854775807n;
const instant = (v: unknown): boolean =>
  typeof v === 'string' &&
  /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) &&
  Number.isFinite(Date.parse(v));
export const isRelayPort = (type: string): boolean =>
  ['relay.publish', 'relay.consume', 'relay.ack'].includes(type);
export function relayPortProblem(
  type: string,
  p: Record<string, unknown>,
): string | undefined {
  if (!isRelayPort(type)) return;
  if (!identity(p['project']) || !name(p['topic']))
    return 'Invalid Relay port scope';
  if (type === 'relay.publish') {
    if (
      !uuid(p['requestId']) ||
      !instant(p['occurredAt']) ||
      !validRelayText(p['text'])
    )
      return 'Invalid Relay publication';
    if (p['correlationId'] != null && !identity(p['correlationId']))
      return 'Invalid Relay correlation';
    if (
      (p['parentTopic'] != null) !== (p['parentEventId'] != null) ||
      (p['parentTopic'] != null &&
        (!name(p['parentTopic']) || !identity(p['parentEventId'])))
    )
      return 'Invalid Relay parent';
  } else {
    if (!name(p['group'], 140) || !uuid(p['consumerId']))
      return 'Invalid Relay consumer';
    if (type === 'relay.consume') {
      if (p['start'] !== 'LATEST' && p['start'] !== 'OLDEST_RETAINED')
        return 'Invalid Relay start';
      for (const [key, min, max] of [
        ['limit', 1, 100],
        ['waitMs', 0, 30000],
      ] as const) {
        const value = p[key];
        if (
          value !== undefined &&
          (typeof value !== 'number' ||
            !Number.isInteger(value) ||
            value < min ||
            value > max)
        )
          return 'Invalid Relay consumption bound';
      }
    } else if (
      !uuid(p['batchId']) ||
      !decimal(p['fence']) ||
      p['fence'] === '0' ||
      (p['expiredThrough'] != null && !decimal(p['expiredThrough']))
    )
      return 'Invalid Relay acknowledgement';
  }
  return;
}
export function validateRelayPortReply(type: string, value: unknown): void {
  if (!isRelayPort(type)) return;
  const fail = (): never => {
    throw new Error('Invalid Relay port response');
  };
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail();
  const p = value as Record<string, unknown>;
  if (!identity(p['project']) || !name(p['topic'])) fail();
  if (type === 'relay.publish') {
    if (
      !uuid(p['requestId']) ||
      !decimal(p['position']) ||
      p['position'] === '0' ||
      !instant(p['publishedAt'])
    )
      fail();
  } else {
    if (!name(p['group'], 140) || !decimal(p['through'])) fail();
    if (type === 'relay.ack') {
      if (!uuid(p['batchId']) || typeof p['gap'] !== 'boolean') fail();
      return;
    }
    if (
      !uuid(p['consumerId']) ||
      !Array.isArray(p['events']) ||
      p['events'].length > 100
    )
      fail();
    const status = p['status'];
    const events = p['events'] as readonly RelayEvent[];
    if (status === 'DATA' || status === 'GAP') {
      if (
        !uuid(p['batchId']) ||
        !decimal(p['fence']) ||
        p['fence'] === '0' ||
        !instant(p['expiresAt'])
      )
        fail();
    } else if (
      (status !== 'EMPTY' && status !== 'BUSY') ||
      p['batchId'] !== null ||
      p['fence'] !== null ||
      p['expiresAt'] !== null
    )
      fail();
    if (
      (status === 'DATA') !== events.length > 0 ||
      (status === 'GAP'
        ? !decimal(p['expiredThrough'])
        : p['expiredThrough'] !== null)
    )
      fail();
    let previous = 0n;
    for (const event of events) {
      validateRelayEvent(event, event.payload.kind);
      if (
        !decimal(event.position) ||
        BigInt(event.position) <= previous ||
        BigInt(event.position) > BigInt(p['through'] as string)
      )
        fail();
      previous = BigInt(event.position);
    }
  }
}

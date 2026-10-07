import {
  isRelayPort,
  relayPortProblem,
  validateRelayPortReply,
} from './relay-ports.ts';
import type { RelayPortPayloads, RelayPortReplies } from './relay-ports.ts';
/** Read-only broker records; positions are decimal strings, never JavaScript numbers. */
export type RelayScope =
  | { readonly project: string; readonly system?: false }
  | { readonly system: true; readonly project?: null };
export type RelayControlAction =
  | 'ACKNOWLEDGE_GAP'
  | 'RECONCILE'
  | 'ABANDON'
  | 'REMOVE_SUBSCRIPTION'
  | 'REMOVE_TOPIC';
/** Generations and branch fences bind administration to an inspected snapshot. */
export interface RelayOperateRequest {
  readonly requestId: string;
  readonly project: string;
  readonly topic: string;
  readonly topicGeneration: string;
  readonly action: RelayControlAction;
  readonly reason: string;
  readonly subscriber?: string | null;
  readonly subscriptionGeneration?: string | null;
  readonly deliveryId?: string | null;
  readonly fence?: string | null;
  readonly expiredThrough?: string | null;
  readonly expectedState?: string | null;
}
export interface RelayControlResult {
  readonly requestId: string;
  readonly project: string;
  readonly topic: string;
  readonly action: RelayControlAction;
  readonly subscriber: string | null;
  readonly deliveryId: string | null;
  readonly status:
    | 'GAP_ACKNOWLEDGED'
    | 'ACCEPTED'
    | 'FAILED'
    | 'UNCERTAIN'
    | 'ABANDONED'
    | 'ABANDONED_UNCERTAIN'
    | 'REMOVED';
  readonly seenThrough: string | null;
  readonly completedAt: string;
}
export interface RelayPayloads extends RelayPortPayloads {
  'relay.operate': RelayOperateRequest;
  'relay.process': { readonly project: string; readonly limit?: number };
  'relay.topics': RelayScope & { readonly limit?: number };
  'relay.log': RelayScope & {
    readonly topic: string;
    readonly after?: string;
    readonly limit?: number;
  };
}
export interface RelayTopic {
  readonly name: string;
  readonly generation?: string | null;
  readonly kind:
    'EMPTY' | 'TEXT' | 'SCHEDULE_DUE' | 'LIFECYCLE' | 'WAKE_REQUESTED';
  readonly retentionSeconds: string;
  readonly maxRecords: string | null;
  readonly through: string;
  readonly expiredThrough: string;
}
/** Runtime effect ancestry. Depth -1 marks unavailable legacy history and grants no budget. */
export interface RelayCausation {
  readonly rootId: string;
  readonly parentId: string | null;
  readonly depth: number;
}

export interface RelayEvent {
  readonly position: string;
  readonly eventId: string;
  readonly publisher: string;
  readonly occurredAt: string;
  readonly publishedAt: string;
  readonly correlationId: string | null;
  readonly causationId: string | null;
  /** Older peers omit this field; absence never proves independent work. */
  readonly causation?: RelayCausation | null;
  readonly payload: {
    readonly kind:
      'EMPTY' | 'TEXT' | 'SCHEDULE_DUE' | 'LIFECYCLE' | 'WAKE_REQUESTED';
    readonly text: string | null;
    readonly schedule: string | null;
    readonly emits: string | null;
    readonly fireAt: string | null;
    readonly lifecycle?: {
      readonly source: string;
      readonly subject: string;
      readonly state: string;
      readonly context: string | null;
      readonly related: string | null;
    } | null;
    readonly wake?: {
      readonly firing: string;
      readonly target: string;
      readonly type: 'MESSAGE' | 'BOARD';
    } | null;
  };
}
export interface RelayBranch {
  readonly id: string;
  readonly position: string;
  readonly subscriber: string;
  readonly name: string;
  readonly receiver: string;
  readonly state:
    | 'READY'
    | 'CLAIMED'
    | 'DISPATCHING'
    | 'ACCEPTED'
    | 'FAILED'
    | 'UNCERTAIN'
    | 'ABANDONED'
    | 'ABANDONED_UNCERTAIN';
  readonly fence: string;
  readonly updatedAt: string;
  readonly failure: string | null;
  readonly receiptNamespace: string | null;
  readonly receiptId: string | null;
  readonly conversation: string | null;
  readonly conversationProject: string | null;
  readonly routingHash: string;
  readonly handlerHash: string | null;
}
export interface RelayReplies extends RelayPortReplies {
  'relay.operate': RelayControlResult;
  'relay.process': {
    readonly project: string;
    readonly admitted: number;
    readonly dispatched: number;
    readonly gaps: readonly {
      readonly topic: string;
      readonly subscriber: string;
      readonly expiredThrough: string;
    }[];
  };
  'relay.topics': {
    readonly scope: {
      readonly project: string | null;
      readonly system: boolean;
    };
    readonly topics: readonly RelayTopic[];
  };
  'relay.log': {
    readonly scope: {
      readonly project: string | null;
      readonly system: boolean;
    };
    readonly topic: RelayTopic;
    readonly after: string;
    readonly next: string;
    readonly gapThrough: string | null;
    readonly events: readonly RelayEvent[];
    readonly subscribers: readonly {
      readonly name: string;
      readonly generation?: string | null;
      readonly seenThrough: string;
      readonly seenAt: string;
      readonly gapThrough: string | null;
    }[];
    readonly branches: readonly RelayBranch[];
    readonly recoveries?: readonly {
      readonly subscriber: string;
      readonly expiredThrough: string;
      readonly pendingInbox: string;
      readonly recoveredAt: string;
    }[];
  };
}
const decimal = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9][0-9]{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n;
const hasControls = (value: string): boolean => {
  for (const character of value) {
    const code = character.codePointAt(0)!;
    if (
      code < 32 ||
      (code >= 127 && code <= 159) ||
      code === 0x2028 ||
      code === 0x2029
    )
      return true;
  }
  return false;
};
const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value);
const validName = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length <= 160 &&
  /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/.test(value);
const actions: readonly string[] = [
  'ACKNOWLEDGE_GAP',
  'RECONCILE',
  'ABANDON',
  'REMOVE_SUBSCRIPTION',
  'REMOVE_TOPIC',
];
export function relayPayloadProblem(
  type: string,
  payload: Record<string, unknown>,
): string | undefined {
  if (!type.startsWith('relay.')) return;
  if (isRelayPort(type)) return relayPortProblem(type, payload);
  if (type === 'relay.operate') {
    const action = payload['action'];
    const requiredIdentity = (key: string): boolean =>
      typeof payload[key] === 'string' &&
      payload[key].length <= 256 &&
      !!payload[key].trim() &&
      payload[key] === payload[key].trim() &&
      !hasControls(payload[key]);
    if (
      !uuid(payload['requestId']) ||
      !uuid(payload['topicGeneration']) ||
      !validName(payload['topic']) ||
      !requiredIdentity('project') ||
      !requiredIdentity('reason') ||
      typeof action !== 'string' ||
      !actions.includes(action)
    )
      return 'Invalid Relay control';
    if (
      action === 'REMOVE_TOPIC'
        ? payload['subscriber'] != null ||
          payload['subscriptionGeneration'] != null
        : !validName(payload['subscriber']) ||
          !payload['subscriber'].startsWith('relay.') ||
          !uuid(payload['subscriptionGeneration'])
    )
      return 'Invalid Relay subscription control';
    if (action === 'RECONCILE' || action === 'ABANDON') {
      if (
        !uuid(payload['deliveryId']) ||
        !decimal(payload['fence']) ||
        !(
          action === 'ABANDON'
            ? ['READY', 'UNCERTAIN']
            : ['UNCERTAIN', 'ABANDONED_UNCERTAIN']
        ).includes(String(payload['expectedState']))
      )
        return 'Invalid Relay branch control';
    } else if (
      ['deliveryId', 'fence', 'expectedState'].some(
        (key) => payload[key] != null,
      )
    )
      return 'Unexpected Relay branch control';
    if (
      action === 'ACKNOWLEDGE_GAP'
        ? !decimal(payload['expiredThrough']) ||
          payload['expiredThrough'] === '0'
        : payload['expiredThrough'] != null
    )
      return 'Invalid Relay gap control';
    return;
  }
  if (payload['system'] !== undefined && typeof payload['system'] !== 'boolean')
    return 'Invalid Relay system scope';
  if (
    payload['project'] !== undefined &&
    payload['project'] !== null &&
    (typeof payload['project'] !== 'string' ||
      !payload['project'].trim() ||
      payload['project'].length > 256 ||
      payload['project'] !== payload['project'].trim() ||
      hasControls(payload['project']))
  )
    return 'Invalid Relay project';
  if ((payload['system'] === true) === (typeof payload['project'] === 'string'))
    return 'Relay needs exactly one project or system scope';
  if (
    type === 'relay.log' &&
    (typeof payload['topic'] !== 'string' ||
      !/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/.test(payload['topic']) ||
      payload['topic'].length > 160)
  )
    return 'Invalid Relay topic';
  const maximum = type === 'relay.process' ? 32 : 100;
  if (payload['after'] !== undefined && !decimal(payload['after']))
    return 'Invalid Relay position';
  if (
    payload['limit'] !== undefined &&
    (typeof payload['limit'] !== 'number' ||
      !Number.isInteger(payload['limit']) ||
      payload['limit'] < 1 ||
      payload['limit'] > maximum)
  )
    return `Relay limit must be 1..${maximum}`;
  return;
}
export function validateRelayReply(type: string, value: unknown): void {
  if (!type.startsWith('relay.')) return;
  if (isRelayPort(type)) {
    validateRelayPortReply(type, value);
    return;
  }
  // This receives recursively decoded DTOs. Semantic checks keep impossible offsets from reaching viewers.
  const fail = (): never => {
    throw new Error('Invalid Relay log response');
  };
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail();
  const row = value as Record<string, unknown>;
  const position = (value: unknown): bigint => {
    if (!decimal(value)) return fail();
    return BigInt(value);
  };
  const identity = (value: unknown, maximum = 256): void => {
    if (
      typeof value !== 'string' ||
      !value.trim() ||
      value !== value.trim() ||
      value.length > maximum ||
      hasControls(value)
    )
      fail();
  };
  const name = (value: unknown): void => {
    if (
      typeof value !== 'string' ||
      value.length > 160 ||
      !/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/.test(value)
    )
      fail();
  };
  const instant = (value: unknown): void => {
    if (
      typeof value !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(
        value,
      ) ||
      !Number.isFinite(Date.parse(value))
    )
      fail();
  };
  if (type === 'relay.operate') {
    identity(row['project']);
    name(row['topic']);
    instant(row['completedAt']);
    if (
      !uuid(row['requestId']) ||
      typeof row['action'] !== 'string' ||
      !actions.includes(row['action'])
    )
      fail();
    const action = row['action'];
    if (
      action === 'REMOVE_TOPIC'
        ? row['subscriber'] !== null
        : !validName(row['subscriber']) ||
          !row['subscriber'].startsWith('relay.')
    )
      fail();
    if (
      action === 'RECONCILE' || action === 'ABANDON'
        ? !uuid(row['deliveryId'])
        : row['deliveryId'] !== null
    )
      fail();
    const statuses =
      action === 'ACKNOWLEDGE_GAP'
        ? ['GAP_ACKNOWLEDGED']
        : action === 'RECONCILE'
          ? ['ACCEPTED', 'FAILED', 'UNCERTAIN', 'ABANDONED_UNCERTAIN']
          : action === 'ABANDON'
            ? ['ABANDONED', 'ABANDONED_UNCERTAIN']
            : ['REMOVED'];
    if (
      !statuses.includes(String(row['status'])) ||
      (action === 'ACKNOWLEDGE_GAP'
        ? !decimal(row['seenThrough'])
        : row['seenThrough'] !== null)
    )
      fail();
    return;
  }
  if (type === 'relay.process') {
    identity(row['project']);
    for (const field of ['admitted', 'dispatched']) {
      const count = row[field];
      if (
        typeof count !== 'number' ||
        !Number.isInteger(count) ||
        count < 0 ||
        count > 32
      )
        fail();
    }
    const gaps = row['gaps'];
    if (!Array.isArray(gaps) || gaps.length > 1024) return fail();
    for (const gap of gaps) {
      if (gap === null || typeof gap !== 'object' || Array.isArray(gap)) fail();
      const fields = gap as Record<string, unknown>;
      name(fields['topic']);
      identity(fields['subscriber']);
      position(fields['expiredThrough']);
    }
    return;
  }
  const scope = row['scope'];
  if (scope === null || typeof scope !== 'object' || Array.isArray(scope))
    fail();
  const scoped = scope as Record<string, unknown>;
  if ((scoped['system'] === true) !== (scoped['project'] === null)) fail();
  if (scoped['project'] !== null) identity(scoped['project']);
  const topic = (value: unknown): void => {
    if (value === null || typeof value !== 'object' || Array.isArray(value))
      fail();
    const fields = value as Record<string, unknown>;
    name(fields['name']);
    if (
      ![
        'EMPTY',
        'TEXT',
        'SCHEDULE_DUE',
        'LIFECYCLE',
        'WAKE_REQUESTED',
      ].includes(String(fields['kind']))
    )
      fail();
    if (fields['generation'] != null && !uuid(fields['generation'])) fail();
    for (const key of ['retentionSeconds', 'through', 'expiredThrough'])
      if (!decimal(fields[key])) fail();
    if (
      fields['maxRecords'] !== null &&
      (!decimal(fields['maxRecords']) || fields['maxRecords'] === '0')
    )
      fail();
    if (
      position(fields['expiredThrough']) > position(fields['through']) ||
      fields['retentionSeconds'] === '0'
    )
      fail();
  };
  if (type === 'relay.topics') {
    const items = row['topics'];
    if (!Array.isArray(items)) return fail();
    if (items.length > 100) fail();
    for (const item of items) topic(item);
  } else {
    topic(row['topic']);
    const metadata = row['topic'] as Record<string, unknown>;
    const through = position(metadata['through']);
    const expired = position(metadata['expiredThrough']);
    const after = position(row['after']);
    if (
      after > through ||
      position(row['next']) > through ||
      position(row['next']) < after
    )
      fail();
    if (
      row['gapThrough'] !==
      (after < expired ? metadata['expiredThrough'] : null)
    )
      fail();
    const recoveries = row['recoveries'];
    if (recoveries !== undefined) {
      if (
        !Array.isArray(recoveries) ||
        recoveries.length > 1000 ||
        (scoped['system'] !== true && recoveries.length > 0)
      )
        return fail();
      for (const recovery of recoveries) {
        if (
          recovery === null ||
          typeof recovery !== 'object' ||
          Array.isArray(recovery)
        )
          return fail();
        const fields = recovery as Record<string, unknown>;
        identity(fields['subscriber']);
        if (
          !decimal(fields['expiredThrough']) ||
          fields['expiredThrough'] === '0' ||
          position(fields['expiredThrough']) > expired ||
          !decimal(fields['pendingInbox'])
        )
          fail();
        instant(fields['recoveredAt']);
      }
    }
    let previous = after > expired ? after : expired;
    for (const key of ['after', 'next']) if (!decimal(row[key])) fail();
    if (row['gapThrough'] !== null && !decimal(row['gapThrough'])) fail();
    for (const key of ['events', 'subscribers', 'branches']) {
      const items = row[key];
      if (!Array.isArray(items)) return fail();
      if (items.length > (key === 'subscribers' ? 1000 : 100)) fail();
      for (const item of items) {
        if (item === null || typeof item !== 'object' || Array.isArray(item))
          fail();
        const fields = item as Record<string, unknown>;
        if (
          !decimal(fields[key === 'subscribers' ? 'seenThrough' : 'position'])
        )
          fail();
        if (key === 'events') {
          const at = validateRelayEvent(fields, metadata['kind']);
          if (at <= previous || at > through) fail();
          previous = at;
        }
        if (key === 'subscribers') {
          identity(fields['name']);
          if (fields['generation'] != null && !uuid(fields['generation']))
            fail();
          instant(fields['seenAt']);
          const seen = position(fields['seenThrough']);
          if (
            seen > through ||
            fields['gapThrough'] !==
              (seen < expired ? metadata['expiredThrough'] : null)
          )
            fail();
        }
        if (key === 'branches') {
          identity(fields['subscriber']);
          identity(fields['name']);
          identity(fields['receiver']);
          instant(fields['updatedAt']);
          if (
            typeof fields['id'] !== 'string' ||
            !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(fields['id'])
          )
            fail();
          for (const optional of [
            'failure',
            'receiptNamespace',
            'receiptId',
            'conversation',
            'conversationProject',
          ])
            if (fields[optional] !== null) identity(fields[optional]);
          if (
            (fields['conversation'] === null) !==
            (fields['conversationProject'] === null)
          )
            fail();
          if (
            !decimal(fields['fence']) ||
            position(fields['position']) > through
          )
            fail();
          if (
            (fields['receiptNamespace'] === null) !==
            (fields['receiptId'] === null)
          )
            fail();
          if (
            typeof fields['routingHash'] !== 'string' ||
            !/^[0-9a-f]{64}$/.test(fields['routingHash'])
          )
            fail();
          if (
            fields['handlerHash'] !== null &&
            (typeof fields['handlerHash'] !== 'string' ||
              !/^[0-9a-f]{64}$/.test(fields['handlerHash']))
          )
            fail();
        }
      }
    }
    if (position(row['next']) !== previous) fail();
  }
}

/** Validates a topic event independently of inspection-page metadata. */
export function validateRelayEvent(value: unknown, kind: unknown): bigint {
  const fail = (): never => {
    throw new Error('Invalid Relay log response');
  };
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail();
  const position = (value: unknown): bigint => {
    if (!decimal(value)) return fail();
    return BigInt(value);
  };
  const identity = (value: unknown, maximum = 256): void => {
    if (
      typeof value !== 'string' ||
      !value.trim() ||
      value !== value.trim() ||
      value.length > maximum ||
      hasControls(value)
    )
      fail();
  };
  const instant = (value: unknown): void => {
    if (
      typeof value !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(
        value,
      ) ||
      !Number.isFinite(Date.parse(value))
    )
      fail();
  };
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    return fail();
  const fields = value as Record<string, unknown>;
  if (
    !['EMPTY', 'TEXT', 'SCHEDULE_DUE', 'LIFECYCLE', 'WAKE_REQUESTED'].includes(
      String(kind),
    )
  )
    return fail();
  identity(fields['eventId']);
  identity(fields['publisher']);
  instant(fields['occurredAt']);
  instant(fields['publishedAt']);
  for (const optional of ['correlationId', 'causationId'])
    if (fields[optional] !== null) identity(fields[optional]);
  const ancestry = fields['causation'];
  if (ancestry != null) {
    if (typeof ancestry !== 'object' || Array.isArray(ancestry)) return fail();
    const cause = ancestry as Record<string, unknown>;
    identity(cause['rootId']);
    const depth = cause['depth'];
    if (
      typeof depth !== 'number' ||
      !Number.isInteger(depth) ||
      depth < -1 ||
      depth > 32
    )
      return fail();
    if (depth <= 0) {
      if (cause['parentId'] !== null) return fail();
    } else identity(cause['parentId']);
  }
  const at = position(fields['position']);

  const payload = fields['payload'];
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload))
    fail();
  const body = payload as Record<string, unknown>;
  if (body['kind'] !== kind) fail();
  if (
    body['kind'] === 'TEXT'
      ? typeof body['text'] !== 'string'
      : body['text'] !== null
  )
    fail();
  for (const part of ['schedule', 'emits', 'fireAt'])
    if (
      body['kind'] === 'SCHEDULE_DUE'
        ? typeof body[part] !== 'string'
        : body[part] !== null
    )
      fail();
  if (
    body['kind'] === 'TEXT' &&
    (typeof body['text'] !== 'string' ||
      !body['text'].trim() ||
      body['text'].length > 65536 ||
      body['text'].includes('\0'))
  )
    fail();
  for (const part of ['lifecycle', 'wake']) {
    const data = body[part];
    const expected =
      body['kind'] === (part === 'lifecycle' ? 'LIFECYCLE' : 'WAKE_REQUESTED');
    if (!expected) {
      if (data != null) fail();
      continue;
    }
    if (data === null || typeof data !== 'object' || Array.isArray(data))
      return fail();
    const details = data as Record<string, unknown>;
    if (part === 'lifecycle') {
      if (!validName(details['source'])) fail();
      identity(details['subject'], 1024);
      identity(details['state']);
      for (const reference of ['context', 'related'])
        if (details[reference] !== null) identity(details[reference], 1024);
    } else {
      identity(details['firing'], 1024);
      identity(details['target'], 1024);
      if (
        typeof details['firing'] !== 'string' ||
        !details['firing'].startsWith('fir_') ||
        typeof details['target'] !== 'string' ||
        !details['target'].startsWith('conversation:') ||
        details['target'].length <= 13 ||
        !['MESSAGE', 'BOARD'].includes(String(details['type']))
      )
        fail();
    }
  }
  if (body['kind'] === 'SCHEDULE_DUE') {
    identity(body['schedule'], 1024);
    identity(body['emits'], 1024);
    instant(body['fireAt']);
  }

  return at;
}

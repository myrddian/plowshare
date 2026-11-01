/** Explicit binary HTTP DTOs; WebSocket consume/log operations carry descriptors only. */
export const MAX_RELAY_BLOB_BYTES = 50 * 1024 * 1024;

export interface RelayBlobDescriptor {
  readonly sha256: string;
  readonly byteLength: number;
  readonly mediaType: string;
}

export interface RelayBlobPublish {
  readonly requestId: string;
  readonly project: string;
  readonly topic: string;
  readonly occurredAt: string;
  readonly mediaType: string;
  readonly correlationId?: string;
  readonly parentTopic?: string;
  readonly parentEventId?: string;
}

export interface RelayBlobPublished {
  readonly publication: {
    readonly requestId: string;
    readonly project: string;
    readonly topic: string;
    readonly position: string;
    readonly publishedAt: string;
  };
  readonly blob: RelayBlobDescriptor;
}

export interface RelayBlobRead {
  readonly project: string;
  readonly topic: string;
  readonly group: string;
  readonly eventId: string;
}

const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const NAME = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;
const MIME =
  /^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$/;
function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid Relay binary object');
  return value as Record<string, unknown>;
}
function identity(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    value.length > 256 ||
    value.trim() !== value ||
    Array.from(value).some((c) => {
      const n = c.charCodeAt(0);
      return n < 32 || (n >= 127 && n <= 159) || n === 0x2028 || n === 0x2029;
    })
  )
    throw new Error('Invalid Relay binary identity');
  return value;
}
function name(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max || !NAME.test(value))
    throw new Error('Invalid Relay binary topic/group');
  return value;
}
function uuid(value: unknown): string {
  if (typeof value !== 'string' || !UUID.test(value))
    throw new Error('Invalid Relay binary UUID');
  return value;
}
function media(value: unknown): string {
  if (typeof value !== 'string' || value.length > 255 || !MIME.test(value))
    throw new Error('Invalid Relay binary media type');
  return value;
}
function instant(value: unknown): string {
  if (typeof value !== 'string')
    throw new Error('Invalid Relay binary timestamp');
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|([+-])(\d{2}):(\d{2}))$/.exec(
      value,
    );
  if (match === null) throw new Error('Invalid Relay binary timestamp');
  const year = Number(match[1]),
    month = Number(match[2]),
    day = Number(match[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  const parsed = Date.parse(value);
  if (
    year < 1 ||
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > (days[month - 1] ?? 0) ||
    Number(match[4]) > 23 ||
    Number(match[5]) > 59 ||
    Number(match[6]) > 59 ||
    (match[7] !== undefined &&
      (Number(match[8]) > 18 ||
        Number(match[9]) > 59 ||
        (Number(match[8]) === 18 && Number(match[9]) !== 0))) ||
    !Number.isFinite(parsed) ||
    parsed < -62135596800000 ||
    parsed > 253402300799999
  )
    throw new Error('Invalid Relay binary timestamp');
  return value;
}
export function decodeRelayBlobDescriptor(value: unknown): RelayBlobDescriptor {
  const row = object(value),
    hash = row['sha256'],
    size = row['byteLength'];
  if (
    typeof hash !== 'string' ||
    !/^[0-9a-f]{64}$/.test(hash) ||
    typeof size !== 'number' ||
    !Number.isInteger(size) ||
    size < 1 ||
    size > MAX_RELAY_BLOB_BYTES
  )
    throw new Error('Invalid Relay blob descriptor');
  return { sha256: hash, byteLength: size, mediaType: media(row['mediaType']) };
}
export function decodeRelayBlobPublish(value: unknown): RelayBlobPublish {
  const row = object(value),
    allowed = [
      'requestId',
      'project',
      'topic',
      'occurredAt',
      'mediaType',
      'correlationId',
      'parentTopic',
      'parentEventId',
    ];
  if (Object.keys(row).some((k) => !allowed.includes(k)))
    throw new Error('Unknown Relay binary publication field');
  const parent = row['parentTopic'],
    event = row['parentEventId'],
    correlation = row['correlationId'];
  if ((parent === undefined) !== (event === undefined))
    throw new Error('Incomplete Relay binary ancestry');
  return {
    requestId: uuid(row['requestId']),
    project: identity(row['project']),
    topic: name(row['topic'], 1024),
    occurredAt: instant(row['occurredAt']),
    mediaType: media(row['mediaType']),
    ...(correlation === undefined
      ? {}
      : { correlationId: identity(correlation) }),
    ...(parent === undefined
      ? {}
      : { parentTopic: name(parent, 1024), parentEventId: identity(event) }),
  };
}
export function decodeRelayBlobRead(value: unknown): RelayBlobRead {
  const row = object(value);
  if (
    Object.keys(row).some(
      (k) => !['project', 'topic', 'group', 'eventId'].includes(k),
    )
  )
    throw new Error('Unknown Relay binary read field');
  return {
    project: identity(row['project']),
    topic: name(row['topic'], 1024),
    group: name(row['group'], 140),
    eventId: uuid(row['eventId']),
  };
}
export function decodeRelayBlobPublished(value: unknown): RelayBlobPublished {
  const row = object(value),
    publication = object(row['publication']),
    position = publication['position'];
  if (
    typeof position !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(position) ||
    BigInt(position) > 9223372036854775807n
  )
    throw new Error('Invalid Relay binary position');
  return {
    publication: {
      requestId: uuid(publication['requestId']),
      project: identity(publication['project']),
      topic: name(publication['topic'], 1024),
      position,
      publishedAt: instant(publication['publishedAt']),
    },
    blob: decodeRelayBlobDescriptor(row['blob']),
  };
}

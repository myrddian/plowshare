import { isAbsolute } from 'node:path';
import type { Configuration, NotificationRoute, Route } from './contracts.ts';

export function object(
  value: unknown,
  fields: readonly string[],
): Record<string, unknown> {
  if (
    value === null ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.getPrototypeOf(value) !== Object.prototype
  )
    throw new Error('Expected a configuration object');
  // The prototype and key checks complete before values leave this JSON boundary.
  const row = value as Record<string, unknown>;
  if (Object.keys(row).some((key) => !fields.includes(key)))
    throw new Error('Unknown configuration field');
  return row;
}
export function text(value: unknown, max = 1024): string {
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length > max ||
    value !== value.trim() ||
    Array.from(value).some((character) => {
      const code = character.codePointAt(0) ?? 0;
      return (
        code < 32 ||
        (code >= 127 && code <= 159) ||
        code === 0x2028 ||
        code === 0x2029
      );
    })
  )
    throw new Error('Invalid bounded identity');
  return value;
}
export function snowflake(value: unknown): string {
  const id = text(value, 20);
  if (!/^[1-9][0-9]{0,19}$/.test(id) || BigInt(id) > 18446744073709551615n)
    throw new Error('Expected a Discord ID as a decimal string');
  return id;
}
export function integer(value: unknown, min: number, max: number): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < min ||
    value > max
  )
    throw new Error('Invalid configuration limit');
  return value;
}
function array(value: unknown, max: number): unknown[] {
  if (!Array.isArray(value) || value.length > max)
    throw new Error('Invalid configuration list');
  return value;
}
function environment(value: unknown): string {
  const name = text(value, 100);
  if (!/^[A-Z][A-Z0-9_]*$/.test(name))
    throw new Error('Expected a token environment variable name');
  return name;
}
function topicName(value: unknown): string {
  const name = text(value, 160);
  if (!/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/.test(name))
    throw new Error('Invalid Relay topic or group');
  return name;
}
export function route(value: unknown): Route {
  const raw = object(value, [
    'guildId',
    'channelId',
    'project',
    'bot',
    'requireMention',
  ]);
  if (typeof raw.requireMention !== 'boolean')
    throw new Error('Each channel must explicitly set requireMention');
  return {
    guildId: snowflake(raw.guildId),
    channelId: snowflake(raw.channelId),
    project: text(raw.project),
    bot: text(raw.bot, 256),
    requireMention: raw.requireMention,
  };
}
export function notification(value: unknown): NotificationRoute {
  const raw = object(value, [
    'channelId',
    'project',
    'topic',
    'group',
    'start',
  ]);
  if (raw.start !== 'LATEST' && raw.start !== 'OLDEST_RETAINED')
    throw new Error('Choose an explicit Relay start policy');
  return {
    channelId: snowflake(raw.channelId),
    project: text(raw.project),
    topic: topicName(raw.topic),
    group: topicName(raw.group),
    start: raw.start,
  };
}
export function decodeConfig(value: unknown): Configuration {
  const raw = object(value, [
    'origin',
    'serviceTokenEnvironment',
    'discordTokenEnvironment',
    'stateDirectory',
    'allowedUserIds',
    'routes',
    'notifications',
    'pollMs',
    'requestTimeoutMs',
    'maxRecords',
  ]);
  if (Object.values(raw).some((field) => field === null))
    throw new Error('Null configuration fields are not supported');
  const origin = text(raw.origin, 2048);
  const url = new URL(origin);
  if (
    !['https:', 'http:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  )
    throw new Error(
      'Configure an HTTP(S) server origin without credentials or a path',
    );
  const stateDirectory = text(raw.stateDirectory, 4096);
  if (!isAbsolute(stateDirectory))
    throw new Error('Configure an absolute private state directory');
  const allowedUserIds = array(raw.allowedUserIds, 1000).map(snowflake);
  const routes = array(raw.routes, 100).map(route);
  if (!allowedUserIds.length || !routes.length)
    throw new Error(
      'At least one allowed Discord user and channel route are required',
    );
  if (
    new Set(allowedUserIds).size !== allowedUserIds.length ||
    new Set(routes.map((r) => r.channelId)).size !== routes.length
  )
    throw new Error('Duplicate Discord user or channel route');
  const notifications =
    raw.notifications === undefined
      ? []
      : array(raw.notifications, 100).map(notification);
  for (const item of notifications) {
    if (
      !routes.some(
        (r) => r.channelId === item.channelId && r.project === item.project,
      )
    )
      throw new Error(
        'Notification destinations must be mapped channels in the same project',
      );
  }
  if (
    new Set(notifications.map((n) => `${n.project}\n${n.topic}\n${n.group}`))
      .size !== notifications.length
  )
    throw new Error('Duplicate Relay consumer route');
  const serviceTokenEnvironment = environment(raw.serviceTokenEnvironment);
  const discordTokenEnvironment = environment(raw.discordTokenEnvironment);
  if (serviceTokenEnvironment === discordTokenEnvironment)
    throw new Error(
      'Discord and Plowshare credentials must use separate environment variables',
    );
  return {
    origin: url.origin,
    serviceTokenEnvironment,
    discordTokenEnvironment,
    stateDirectory,
    allowedUserIds,
    routes,
    notifications,
    pollMs: integer(raw.pollMs ?? 2000, 250, 60000),
    requestTimeoutMs: integer(raw.requestTimeoutMs ?? 30000, 1000, 60000),
    maxRecords: integer(raw.maxRecords ?? 500, 1, 1000),
  };
}

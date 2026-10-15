import { randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import {
  atomicPrivateWrite,
  privateDirectory,
  privateFile,
} from 'plowshare-client-node/connections';
import { object, text, snowflake, notification } from './config.ts';
import type {
  Configuration,
  Destination,
  Output,
  RecordEntry,
  State,
  StateRepository,
} from './contracts.ts';

function list(value: unknown, max: number): unknown[] {
  if (!Array.isArray(value) || value.length > max)
    throw new Error('Invalid retained state list');
  return value;
}
function flag(value: unknown): boolean {
  if (typeof value !== 'boolean')
    throw new Error('Invalid retained state flag');
  return value;
}
export function uuid(value: unknown): string {
  const id = text(value, 36);
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(id))
    throw new Error('Invalid retained UUID');
  return id;
}
function optional(
  value: unknown,
  decode: (value: unknown) => string,
): string | null {
  return value === null ? null : decode(value);
}
function destination(value: unknown): Destination {
  const raw = object(value, ['guildId', 'channelId', 'parentId']);
  return {
    guildId: snowflake(raw.guildId),
    channelId: snowflake(raw.channelId),
    parentId: optional(raw.parentId, snowflake),
  };
}
function output(value: unknown): Output {
  const raw = object(value, ['key', 'text', 'state', 'messageId']);
  if (
    raw.state !== 'READY' &&
    raw.state !== 'SENDING' &&
    raw.state !== 'SENT' &&
    raw.state !== 'UNKNOWN'
  )
    throw new Error('Invalid retained output phase');
  if (
    typeof raw.text !== 'string' ||
    !raw.text.trim() ||
    raw.text.length > 2000
  )
    throw new Error('Invalid retained output text');
  const messageId = optional(raw.messageId, snowflake);
  if ((raw.state === 'SENT') !== (messageId !== null))
    throw new Error('Invalid output receipt');
  return {
    key: text(raw.key, 512),
    text: raw.text,
    state: raw.state === 'SENDING' ? 'UNKNOWN' : raw.state,
    messageId,
  };
}
function record(value: unknown): RecordEntry {
  const raw = object(value, [
    'key',
    'kind',
    'userId',
    'destination',
    'project',
    'bot',
    'client',
    'contextKey',
    'requestId',
    'replyTo',
    'notification',
    'admission',
    'taskId',
    'contextId',
    'finished',
    'seenReplies',
    'outputs',
  ]);
  if (
    raw.kind !== 'ASK' &&
    raw.kind !== 'STATUS' &&
    raw.kind !== 'CANCEL' &&
    raw.kind !== 'NOTICE'
  )
    throw new Error('Invalid retained record kind');
  if (
    raw.admission !== 'ADMITTING' &&
    raw.admission !== 'ACCEPTED' &&
    raw.admission !== 'UNKNOWN' &&
    raw.admission !== 'REFUSED' &&
    raw.admission !== 'NOT_SUBMITTED'
  )
    throw new Error('Invalid retained admission');
  const client = text(raw.client, 64);
  if (!/^[a-zA-Z0-9_.-]+$/.test(client))
    throw new Error('Invalid retained external client');
  const taskId = optional(raw.taskId, uuid);
  const contextId = optional(raw.contextId, uuid);
  const userId = optional(raw.userId, snowflake);
  const notice =
    raw.notification === null ? null : notification(raw.notification);
  if (
    raw.kind === 'NOTICE'
      ? userId !== null || notice === null
      : userId === null || notice !== null
  )
    throw new Error('Invalid retained sender');
  if (
    raw.kind === 'ASK' &&
    raw.admission === 'ACCEPTED' &&
    (taskId === null || contextId === null)
  )
    throw new Error('Missing accepted task receipt');
  if ((raw.kind === 'CANCEL' || raw.kind === 'STATUS') && taskId === null)
    throw new Error('Missing control target');
  const outputs = list(raw.outputs, 100).map(output);
  const seenReplies = list(raw.seenReplies, 201).map((v) => text(v, 1024));
  if (
    new Set(outputs.map((o) => o.key)).size !== outputs.length ||
    new Set(seenReplies).size !== seenReplies.length
  )
    throw new Error('Duplicate retained reply');
  return {
    key: text(raw.key, 128),
    kind: raw.kind,
    userId,
    destination: destination(raw.destination),
    project: text(raw.project),
    bot: text(raw.bot, 256),
    client,
    contextKey: text(raw.contextKey, 64),
    requestId: uuid(raw.requestId),
    replyTo: optional(raw.replyTo, snowflake),
    notification: notice,
    admission: raw.admission === 'ADMITTING' ? 'UNKNOWN' : raw.admission,
    taskId,
    contextId,
    finished: flag(raw.finished),
    seenReplies,
    outputs,
  };
}
/** Interrupted admissions/sends stay UNKNOWN. Loading state never repeats a mutation. */
export function decodeState(
  value: unknown,
  config: Configuration,
  principal: string,
  discordBotId: string,
): State {
  const raw = object(value, [
    'version',
    'origin',
    'principal',
    'discordBotId',
    'consumerId',
    'contexts',
    'records',
    'heldNotifications',
  ]);
  if (
    raw.version !== 1 ||
    raw.origin !== config.origin ||
    raw.principal !== principal ||
    raw.discordBotId !== discordBotId
  )
    throw new Error(
      'State belongs to another server, service principal or Discord bot',
    );
  const records = list(raw.records, config.maxRecords).map(record);
  const contexts = list(raw.contexts, config.maxRecords).map((v) => {
    const item = object(v, ['key', 'contextId']);
    return { key: text(item.key, 64), contextId: uuid(item.contextId) };
  });
  const heldNotifications = list(raw.heldNotifications, 100).map<
    State['heldNotifications'][number]
  >((v) => {
    const item = object(v, ['key', 'reason']);
    const reason = item.reason;
    if (
      reason !== 'GAP' &&
      reason !== 'ACK_UNKNOWN' &&
      reason !== 'DELIVERY_UNKNOWN'
    )
      throw new Error('Invalid notification hold');
    return { key: text(item.key, 64), reason };
  });
  if (
    new Set(records.map((r) => r.key)).size !== records.length ||
    new Set(contexts.map((c) => c.key)).size !== contexts.length ||
    new Set(heldNotifications.map((h) => h.key)).size !==
      heldNotifications.length
  )
    throw new Error('Duplicate retained identity');
  return {
    version: 1,
    origin: config.origin,
    principal,
    discordBotId: snowflake(discordBotId),
    consumerId: uuid(raw.consumerId),
    contexts,
    records,
    heldNotifications,
  };
}
/** One process owns this directory until clean shutdown. Stale locks require operator inspection. */
export class FileState implements StateRepository {
  private constructor(
    readonly state: State,
    private readonly file: string,
    private readonly lock: string,
  ) {}

  static async open(
    config: Configuration,
    principal: string,
    discordBotId: string,
  ): Promise<FileState> {
    await privateDirectory(config.stateDirectory);
    const lock = join(config.stateDirectory, 'adapter.lock');
    await mkdir(lock, { mode: 0o700 });
    try {
      const file = join(config.stateDirectory, 'state.json');
      await privateFile(file);
      let state: State;
      try {
        const raw: unknown = JSON.parse(await readFile(file, 'utf8'));
        state = decodeState(raw, config, principal, discordBotId);
      } catch (error) {
        if (!(
          error instanceof Error &&
          'code' in error &&
          error.code === 'ENOENT'
        ))
          throw error;
        state = {
          version: 1,
          origin: config.origin,
          principal,
          discordBotId,
          consumerId: randomUUID(),
          contexts: [],
          records: [],
          heldNotifications: [],
        };
      }
      const repository = new FileState(state, file, lock);
      await repository.save();
      return repository;
    } catch (error) {
      await rm(lock, { recursive: true });
      throw error;
    }
  }
  async save(): Promise<void> {
    await atomicPrivateWrite(this.file, this.state);
    // Persist the renamed directory entry, in addition to the SDK's file fsync.
    const directory = await open(join(this.file, '..'), 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
  async close(): Promise<void> {
    await rm(this.lock, { recursive: true });
  }
}

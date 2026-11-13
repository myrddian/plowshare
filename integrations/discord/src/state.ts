import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import {
  atomicPrivateWrite,
  privateDirectory,
  privateFile,
} from 'plowshare-client-node/connections';
import { messageHeader, retainedReply } from './messaging-codec.ts';
import { object, text, snowflake, notification } from './config.ts';
import type {
  Configuration,
  Destination,
  Output,
  RecordEntry,
  State,
  StateRepository,
  MessageTraversal,
} from './contracts.ts';

export function messagingIdentity(config: Configuration): string {
  return createHash('sha256')
    .update(
      JSON.stringify(
        [...config.messaging].sort((a, b) =>
          a.project + '\n' + a.bot < b.project + '\n' + b.bot
            ? -1
            : a.project + '\n' + a.bot > b.project + '\n' + b.bot
              ? 1
              : 0,
        ),
      ),
    )
    .digest('hex');
}
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
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(id))
    throw new Error('Invalid retained UUID');
  return id;
}
function optional(
  value: unknown,
  decode: (value: unknown) => string,
): string | null {
  return value === null ? null : decode(value);
}
function destination(value: unknown, legacy: boolean): Destination {
  const raw = object(value, [
    'kind',
    'guildId',
    'channelId',
    'parentId',
    'recipientId',
  ]);
  if (raw.kind === 'dm') {
    if (legacy)
      throw new Error('Legacy journals cannot contain DM destinations');
    object(value, ['kind', 'channelId', 'recipientId']);
    return {
      kind: 'dm',
      channelId: snowflake(raw.channelId),
      recipientId: snowflake(raw.recipientId),
    };
  }
  object(value, ['kind', 'guildId', 'channelId', 'parentId']);
  if (raw.kind !== 'guild' && !(legacy && raw.kind === undefined))
    throw new Error('Invalid retained destination kind');
  return {
    kind: 'guild',
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
function record(
  value: unknown,
  legacy: boolean,
  current: boolean,
): RecordEntry {
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
    'lastRevision',
    'nativeHeader',
    'replyCursor',
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
  if (
    current &&
    (raw.lastRevision === undefined || raw.replyCursor === undefined)
  )
    throw new Error('Incomplete retained messaging checkpoint');
  const client = text(raw.client, 64);
  if (!/^[a-zA-Z0-9_.-]+$/.test(client))
    throw new Error('Invalid retained external client');
  const taskId = optional(raw.taskId, uuid);
  const contextId = optional(raw.contextId, uuid);
  const userId = optional(raw.userId, snowflake);
  const target = destination(raw.destination, legacy);
  if (
    target.kind === 'dm' &&
    (raw.kind === 'NOTICE' || target.recipientId !== userId)
  )
    throw new Error('Retained DM does not belong to its sender');
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
  const nativeHeader =
    raw.nativeHeader === undefined || raw.nativeHeader === null
      ? null
      : messageHeader(raw.nativeHeader);
  if (
    nativeHeader !== null &&
    (nativeHeader.id !== taskId ||
      nativeHeader.context !== contextId ||
      nativeHeader.agent !== raw.bot ||
      nativeHeader.revision !== raw.lastRevision ||
      (raw.kind === 'ASK' && nativeHeader.requestId !== raw.requestId))
  )
    throw new Error(
      'Retained native identity differs from its delivery record',
    );
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
    destination: target,
    project: text(raw.project),
    bot: text(raw.bot, 256),
    client,
    contextKey: text(raw.contextKey, 64),
    requestId: uuid(raw.requestId),
    nativeHeader,
    lastRevision:
      raw.lastRevision === undefined ? '0' : decimal(raw.lastRevision),
    replyCursor:
      raw.replyCursor === undefined || raw.replyCursor === null
        ? null
        : text(raw.replyCursor, 4096),
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
    'messaging',
    'relayGenerations',
    'messageTraversals',
    'origin',
    'principal',
    'discordBotId',
    'consumerId',
    'contexts',
    'records',
    'heldNotifications',
  ]);
  if (
    (raw.version !== 1 && raw.version !== 2 && raw.version !== 3) ||
    raw.origin !== config.origin ||
    raw.principal !== principal ||
    raw.discordBotId !== discordBotId
  )
    throw new Error(
      'State belongs to another server, service principal or Discord bot',
    );
  const identity = messagingIdentity(config);
  if (raw.version === 3 && raw.messaging !== identity)
    throw new Error(
      'Messaging binding changed; explicitly reconcile retained task scopes before migration',
    );
  if (
    raw.version !== 3 &&
    !config.legacyStateMigration &&
    (list(raw.records, config.maxRecords).length > 0 ||
      list(raw.contexts, config.maxRecords).length > 0)
  )
    throw new Error(
      'Legacy Discord state requires verified server channel mapping before migration',
    );
  const relayGenerations =
    raw.version === 3
      ? list(raw.relayGenerations, 200).map((value) => {
          const item = object(value, ['project', 'topic', 'generation']);
          return {
            project: text(item.project),
            topic: text(item.topic, 160),
            generation: uuid(item.generation),
          };
        })
      : [];
  if (
    new Set(relayGenerations.map((item) => item.project + '\n' + item.topic))
      .size !== relayGenerations.length
  )
    throw new Error('Duplicate messaging incarnation pin');
  const messageTraversals =
    raw.messageTraversals === undefined
      ? []
      : list(raw.messageTraversals, 200).map((value): MessageTraversal => {
          const row = object(value, [
            'project',
            'agent',
            'requests',
            'replies',
            'client',
            'header',
            'parts',
            'next',
            'resume',
            'terminalFollowed',
          ]);
          if (Object.keys(row).length !== 10)
            throw new Error('Incomplete retained messaging traversal');
          const project = text(row.project),
            agent = text(row.agent, 256),
            requests = text(row.requests, 160),
            replies = text(row.replies, 160),
            client = text(row.client, 64);
          if (
            !/^[a-zA-Z0-9_.-]+$/.test(client) ||
            !config.messaging.some(
              (binding) =>
                binding.project === project &&
                binding.bot === agent &&
                binding.requests === requests &&
                binding.replies === replies,
            )
          )
            throw new Error('Foreign retained messaging traversal scope');
          const header = messageHeader(row.header);
          if (header.agent !== agent)
            throw new Error('Foreign retained messaging traversal agent');
          const parts = list(row.parts, 200).map((value) => {
            const part = object(value, ['reply', 'complete']);
            if (Object.keys(part).length !== 2)
              throw new Error('Incomplete retained reply assembly');
            return {
              reply: retainedReply(part.reply),
              complete: flag(part.complete),
            };
          });
          const next = optional(row.next, (value) => text(value, 4096)),
            resume = optional(row.resume, (value) => text(value, 4096));
          if (
            new Set(parts.map((part) => part.reply.id)).size !== parts.length ||
            (next === null &&
              (resume === null || parts.some((part) => !part.complete)))
          )
            throw new Error('Invalid retained messaging traversal checkpoint');
          return {
            project,
            agent,
            requests,
            replies,
            client,
            header,
            parts,
            next,
            resume,
            terminalFollowed: flag(row.terminalFollowed),
          };
        });
  if (
    new Set(
      messageTraversals.map((row) =>
        [row.project, row.agent, row.client, row.header.id].join('\n'),
      ),
    ).size !== messageTraversals.length ||
    messageTraversals.reduce(
      (sum, row) =>
        sum +
        row.parts.reduce(
          (bytes, part) => bytes + Buffer.byteLength(part.reply.body),
          0,
        ),
      0,
    ) >
      50 * 1024 * 1024
  )
    throw new Error('Retained messaging traversal capacity exceeded');
  const records = list(raw.records, config.maxRecords).map((value) =>
    record(value, raw.version === 1, raw.version === 3),
  );
  for (const traversal of messageTraversals) {
    const owner = records.find(
      (record) =>
        record.project === traversal.project &&
        record.bot === traversal.agent &&
        record.client === traversal.client &&
        (record.taskId === traversal.header.id ||
          (record.kind === 'ASK' &&
            record.requestId === traversal.header.requestId)),
    );
    const native = owner?.nativeHeader;
    if (
      native !== undefined &&
      native !== null &&
      (native.message !== traversal.header.message ||
        native.createdAt !== traversal.header.createdAt ||
        native.context !== traversal.header.context ||
        native.requestId !== traversal.header.requestId ||
        BigInt(native.revision) > BigInt(traversal.header.revision))
    )
      throw new Error(
        'Messaging traversal differs from its retained native identity',
      );
    if (owner === undefined)
      throw new Error('Messaging traversal has no owned delivery record');
    if (
      ![traversal.requests, traversal.replies].every((topic) =>
        relayGenerations.some(
          (pin) => pin.project === traversal.project && pin.topic === topic,
        ),
      )
    )
      throw new Error('Messaging traversal lacks retained topic incarnations');
  }
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
    version: 3,
    messaging: identity,
    relayGenerations,
    messageTraversals,
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
  private closed = false;
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
          version: 3,
          messaging: messagingIdentity(config),
          relayGenerations: [],
          messageTraversals: [],
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
    if (this.closed) throw new Error('Runtime state repository is closed');
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
    // A later configuration/runtime owner may already hold the same lock path.
    // Repeated cleanup by this closed owner must never release that owner's lock.
    if (this.closed) return;
    this.closed = true;
    await rm(this.lock, { recursive: true });
  }
}

function decimal(value: unknown): string {
  const result = text(value, 19);
  if (
    !/^(0|[1-9][0-9]*)$/.test(result) ||
    BigInt(result) > 9223372036854775807n
  )
    throw new Error('Invalid retained task revision');
  return result;
}

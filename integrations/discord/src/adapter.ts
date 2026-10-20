import { createHash, randomUUID } from 'node:crypto';
import { ConnectionFault, Refusal } from 'plowshare-client-node';
import type {
  Configuration,
  DiscordDelivery,
  DiscordInput,
  Destination,
  Gateway,
  IncomingTask,
  NotificationRoute,
  RecordEntry,
  StateRepository,
} from './contracts.ts';
import { chunks } from './render.ts';
import { snowflake } from './config.ts';
import { uuid } from './state.ts';

export function digest(...parts: readonly string[]): string {
  return createHash('sha256').update(JSON.stringify(parts)).digest('hex');
}
function noticeKey(route: NotificationRoute): string {
  // Broker ownership and uncertainty survive a destination edit. A new channel cannot
  // turn a retained uncertain attempt into a new event eligible for another send.
  return digest(route.project, route.topic, route.group);
}
function terminal(task: IncomingTask): boolean {
  return ['COMPLETED', 'FAILED', 'CANCELED'].includes(task.state);
}
function outcome(error: unknown): RecordEntry['admission'] {
  if (error instanceof Refusal) return 'REFUSED';
  if (error instanceof ConnectionFault && error.delivery === 'NOT_SUBMITTED')
    return 'NOT_SUBMITTED';
  return 'UNKNOWN';
}
function destinationRoute(
  config: Configuration,
  destination: Destination,
):
  | {
      readonly project: string;
      readonly bot: string;
      readonly requireMention: boolean;
    }
  | undefined {
  if (destination.kind === 'dm')
    return config.dm.mode === 'service'
      ? {
          project: config.dm.project,
          bot: config.dm.bot,
          requireMention: false,
        }
      : undefined;
  return (
    config.routes.find(
      (r) =>
        r.guildId === destination.guildId &&
        r.channelId === destination.channelId,
    ) ??
    config.routes.find(
      (r) =>
        r.guildId === destination.guildId &&
        r.channelId === destination.parentId,
    )
  );
}
/** Fixed server-side destinations: Discord text never supplies a project or definition name. */
export function selectedRoute(
  config: Configuration,
  input: DiscordInput,
): ReturnType<typeof destinationRoute> {
  if (
    input.bot ||
    input.webhook ||
    !config.allowedUserIds.includes(input.userId) ||
    (input.kind === 'dm' && input.recipientId !== input.userId)
  )
    return undefined;
  return destinationRoute(config, input);
}

/** Owns adapter receipts only. The server owns every admitted task and execution lifecycle.
 * Callers serialize accept/poll; no asynchronous callback may mutate state concurrently. */
export class DiscordAdapter {
  constructor(
    private readonly config: Configuration,
    private readonly repository: StateRepository,
    private readonly gateway: Gateway,
    private readonly discord: DiscordDelivery,
  ) {}

  private current(record: RecordEntry): boolean {
    const route = destinationRoute(this.config, record.destination);
    if (
      route === undefined ||
      route.project !== record.project ||
      route.bot !== record.bot
    )
      return false;
    if (record.kind === 'NOTICE') {
      const notification = record.notification;
      return (
        notification !== null &&
        this.config.notifications.some(
          (n) =>
            noticeKey(n) === noticeKey(notification) &&
            n.channelId === notification.channelId,
        )
      );
    }
    return (
      record.userId !== null &&
      (record.destination.kind !== 'dm' ||
        record.destination.recipientId === record.userId) &&
      this.config.allowedUserIds.includes(record.userId)
    );
  }
  private add(record: RecordEntry): void {
    if (this.repository.state.records.length >= this.config.maxRecords)
      throw new Error(
        'Discord receipt capacity reached; archive settled state deliberately',
      );
    this.repository.state.records.push(record);
  }
  private prepare(record: RecordEntry, key: string, body: string): void {
    if (record.seenReplies.includes(key)) return;
    const parts = chunks(body);
    if (record.outputs.length + parts.length > 100)
      throw new Error('Discord output capacity reached');
    record.outputs.push(
      ...parts.map((part, index) => ({
        key: `${key}:${index}`,
        text: part,
        state: 'READY' as const,
        messageId: null,
      })),
    );
    record.seenReplies.push(key);
  }
  private check(task: IncomingTask, record: RecordEntry): void {
    uuid(task.id);
    uuid(task.context);
    if (
      task.agent !== record.bot ||
      (record.taskId !== null && task.id !== record.taskId) ||
      (record.contextId !== null && task.context !== record.contextId)
    )
      throw new Error('Foreign incoming task receipt');
  }
  private reference(record: RecordEntry) {
    if (record.taskId === null)
      throw new Error(
        'Incoming task ID is unavailable; cannot reconcile by request UUID',
      );
    return {
      project: record.project,
      client: record.client,
      id: record.taskId,
    };
  }

  async accept(input: DiscordInput): Promise<void> {
    const route = selectedRoute(this.config, input);
    if (route === undefined) return;
    const botId = this.repository.state.discordBotId;
    const mentions = [`<@${botId}>`, `<@!${botId}>`];
    if (
      route.requireMention &&
      !mentions.some((m) => input.content.includes(m))
    )
      return;
    const body = mentions
      .reduce(
        (result, mention) => result.replaceAll(mention, ''),
        input.content,
      )
      .trim();
    if (!body || Buffer.byteLength(body) > 65536) return;
    const key = `discord:${input.messageId}`;
    if (this.repository.state.records.some((r) => r.key === key)) return;
    // Preserve existing guild keys; DM conversations occupy an independent namespace.
    const contextKey =
      input.kind === 'guild'
        ? digest(
            input.guildId,
            input.channelId,
            input.parentId ?? '',
            input.userId,
            route.project,
            route.bot,
          )
        : digest(
            'dm',
            input.channelId,
            input.recipientId,
            route.project,
            route.bot,
          );
    const command = /^!plowshare (status|cancel)(?: ([0-9a-f-]{36}))?$/i.exec(
      body,
    );
    if (/^!plowshare/i.test(body) && command === null) return;
    const kind =
      command?.[1]?.toLowerCase() === 'status'
        ? 'STATUS'
        : command?.[1]?.toLowerCase() === 'cancel'
          ? 'CANCEL'
          : 'ASK';
    const target =
      kind === 'ASK'
        ? undefined
        : this.repository.state.records
            .toReversed()
            .find(
              (r) =>
                r.kind === 'ASK' &&
                r.contextKey === contextKey &&
                r.taskId !== null &&
                (command?.[2] === undefined || r.taskId === command[2]),
            );
    // A control request never looks up a foreign task ID, even under the shared service principal.
    if (kind !== 'ASK' && target === undefined) return;
    const record: RecordEntry = {
      key,
      kind,
      userId: input.userId,
      destination:
        input.kind === 'guild'
          ? {
              kind: 'guild',
              guildId: input.guildId,
              channelId: input.channelId,
              parentId: input.parentId,
            }
          : {
              kind: 'dm',
              channelId: input.channelId,
              recipientId: input.recipientId,
            },
      project: route.project,
      bot: route.bot,
      client: `discord_${digest(botId, input.kind === 'guild' ? input.guildId : 'dm', input.userId).slice(0, 32)}`,
      contextKey,
      requestId: randomUUID(),
      replyTo: input.messageId,
      notification: null,
      admission: 'ADMITTING',
      taskId: target?.taskId ?? null,
      contextId:
        target?.contextId ??
        this.repository.state.contexts.find((c) => c.key === contextKey)
          ?.contextId ??
        null,
      finished: false,
      seenReplies: [],
      outputs: [],
    };
    const blocked = this.repository.state.records.some(
      (r) =>
        r.contextKey === contextKey &&
        (r.kind === 'ASK' || r.kind === 'CANCEL') &&
        r.admission === 'UNKNOWN' &&
        !r.finished,
    );
    this.add(record);
    if (blocked && kind !== 'STATUS') {
      record.admission = 'REFUSED';
      record.finished = true;
      this.prepare(
        record,
        'blocked',
        'This conversation has an unresolved submission or cancellation. No new work was submitted. An operator must inspect the retained receipt.',
      );
      await this.repository.save();
      return;
    }
    // Save admission intent before the SDK call. A crash here must never repeat the effect.
    await this.repository.save();
    let task: IncomingTask;
    try {
      if (kind === 'ASK') {
        task = await this.gateway.receive({
          project: record.project,
          agent: record.bot,
          client: record.client,
          requestId: record.requestId,
          context: record.contextId,
          body,
        });
      } else if (kind === 'STATUS')
        task = await this.gateway.status(this.reference(record));
      else task = await this.gateway.cancel(this.reference(record));
      this.check(task, record);
    } catch (error) {
      record.admission = kind === 'STATUS' ? 'NOT_SUBMITTED' : outcome(error);
      record.finished = record.admission !== 'UNKNOWN';
      this.prepare(
        record,
        'admission',
        record.admission === 'UNKNOWN'
          ? 'Delivery to Plowshare is uncertain. The adapter will not submit it again. An operator must inspect the retained receipt.'
          : 'Plowshare did not accept this request. Check the configured access and connection.',
      );
      await this.repository.save();
      return;
    }
    record.admission = 'ACCEPTED';
    record.taskId = task.id;
    record.contextId = task.context;
    if (kind === 'ASK') {
      if (!this.repository.state.contexts.some((c) => c.key === contextKey))
        this.repository.state.contexts.push({
          key: contextKey,
          contextId: task.context,
        });
      this.prepare(record, 'accepted', `Plowshare accepted task ${task.id}.`);
      this.observe(record, task);
    } else {
      record.finished = true;
      this.prepare(
        record,
        'control',
        `Task ${task.id}: ${task.state}${task.ending === null ? '' : ` (${task.ending})`}.`,
      );
    }
    await this.repository.save();
  }
  private observe(record: RecordEntry, task: IncomingTask): void {
    this.check(task, record);
    for (const reply of task.replies)
      this.prepare(record, reply.id, reply.body);
    if (task.state === 'INPUT_REQUIRED')
      this.prepare(
        record,
        'approval',
        'This task is waiting for approval in Plowshare. Discord chat does not approve it.',
      );
    record.finished = terminal(task);
    if (record.finished && !task.replies.some((r) => r.finalReply))
      this.prepare(
        record,
        'terminal',
        `Task ${task.id}: ${task.state}${task.ending === null ? '' : ` (${task.ending})`}.`,
      );
  }
  private async deliver(record: RecordEntry): Promise<void> {
    if (
      !this.current(record) ||
      record.outputs.some((o) => o.state === 'UNKNOWN')
    )
      return;
    for (const output of record.outputs) {
      if (output.state !== 'READY') continue;
      output.state = 'SENDING';
      await this.repository.save();
      let id: string;
      try {
        id = await this.discord.send(
          record.destination,
          output.text,
          record.replyTo,
          digest(record.key, output.key).slice(0, 24),
        );
        snowflake(id);
      } catch {
        output.state = 'UNKNOWN';
        await this.repository.save();
        return;
      }
      output.messageId = id;
      output.state = 'SENT';
      await this.repository.save();
    }
  }
  async poll(): Promise<void> {
    for (const record of this.repository.state.records) {
      if (!this.current(record)) continue;
      if (
        record.kind === 'ASK' &&
        record.admission === 'ACCEPTED' &&
        !record.finished
      ) {
        this.observe(record, await this.gateway.status(this.reference(record)));
        await this.repository.save();
      } else if (record.admission === 'UNKNOWN' && !record.finished) {
        const needsNotice = !record.seenReplies.includes('admission');
        this.prepare(
          record,
          'admission',
          'A previous request has uncertain delivery. It has not been repeated. An operator must inspect its retained receipt.',
        );
        if (record.kind === 'CANCEL' && record.taskId !== null) {
          const task = await this.gateway.status(this.reference(record));
          this.check(task, record);
          if (terminal(task)) {
            record.finished = true;
            this.prepare(
              record,
              'resolved',
              `Task ${task.id}: ${task.state}. Cancellation was not repeated.`,
            );
          }
        }
        if (needsNotice || record.finished) await this.repository.save();
      }
      await this.deliver(record);
    }
    for (const route of this.config.notifications)
      await this.notifications(route);
  }
  private async hold(
    key: string,
    reason: 'GAP' | 'ACK_UNKNOWN' | 'DELIVERY_UNKNOWN',
  ): Promise<void> {
    if (!this.repository.state.heldNotifications.some((h) => h.key === key))
      this.repository.state.heldNotifications.push({ key, reason });
    await this.repository.save();
  }
  private async notifications(route: NotificationRoute): Promise<void> {
    const key = noticeKey(route);
    if (this.repository.state.heldNotifications.some((h) => h.key === key))
      return;
    const batch = await this.gateway.consume({
      project: route.project,
      topic: route.topic,
      group: route.group,
      consumerId: this.repository.state.consumerId,
      start: route.start,
      limit: 1,
      waitMs: 0,
    });
    if (batch.status === 'GAP') {
      await this.hold(key, 'GAP');
      return;
    }
    if (batch.status !== 'DATA') return;
    if (
      batch.batchId === null ||
      batch.fence === null ||
      batch.expiresAt === null
    )
      throw new Error('Missing Relay batch fence');
    const channel = this.config.routes.find(
      (r) => r.channelId === route.channelId && r.project === route.project,
    );
    if (channel === undefined)
      throw new Error('Notification route is unavailable');
    for (const event of batch.events) {
      const eventKey = `relay:${digest(key, event.eventId)}`;
      let record = this.repository.state.records.find(
        (r) => r.key === eventKey,
      );
      if (record === undefined) {
        record = {
          key: eventKey,
          kind: 'NOTICE',
          userId: null,
          destination: {
            kind: 'guild',
            guildId: channel.guildId,
            channelId: channel.channelId,
            parentId: null,
          },
          project: route.project,
          bot: channel.bot,
          client: 'discord_notifications',
          contextKey: key,
          requestId: randomUUID(),
          replyTo: null,
          notification: route,
          admission: 'ACCEPTED',
          taskId: null,
          contextId: null,
          finished: true,
          seenReplies: [],
          outputs: [],
        };
        this.add(record);
        // These are selected observations; forwarding never starts an agent or trusts event text.
        const lifecycle = event.payload.lifecycle;
        const body =
          event.payload.kind === 'TEXT'
            ? event.payload.text
            : lifecycle === undefined || lifecycle === null
              ? `Relay ${route.topic}: ${event.payload.kind} (${event.eventId}).`
              : `${lifecycle.source}: ${lifecycle.subject} — ${lifecycle.state}`;
        this.prepare(
          record,
          event.eventId,
          body ?? 'Relay published an empty notification.',
        );
        await this.repository.save();
      }
      await this.deliver(record);
      if (record.outputs.some((o) => o.state === 'UNKNOWN')) {
        await this.hold(key, 'DELIVERY_UNKNOWN');
        return;
      }
      if (!record.outputs.every((o) => o.state === 'SENT')) return;
    }
    // Do not acknowledge after a long/rate-limited Discord send exhausts the server lease.
    if (Date.parse(batch.expiresAt) <= Date.now() + 1000) return;
    await this.hold(key, 'ACK_UNKNOWN');
    await this.gateway.acknowledge({
      project: route.project,
      topic: route.topic,
      group: route.group,
      consumerId: this.repository.state.consumerId,
      batchId: batch.batchId,
      fence: batch.fence,
    });
    const index = this.repository.state.heldNotifications.findIndex(
      (h) => h.key === key,
    );
    this.repository.state.heldNotifications.splice(index, 1);
    await this.repository.save();
  }
}

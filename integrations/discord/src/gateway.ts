import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { ConnectionFault, requirePayload } from 'plowshare-client-node';
import type {
  Plowshare,
  RelayAckRequest,
  RelayConsumeRequest,
} from 'plowshare-client-node';
import type {
  Gateway,
  MessageTask,
  BotTarget,
  Submission,
  TaskReference,
  MessagingBinding,
  StateRepository,
  MessageTraversal,
} from './contracts.ts';
import { taskSnapshot, MessagingRefusal } from './messaging-codec.ts';
import type { TaskSnapshot } from './messaging-codec.ts';
import { uuid } from './state.ts';

/** SDK-only composition. Topic mappings and retained incarnations belong to the adapter owner. */
export class SdkGateway implements Gateway {
  constructor(
    private readonly client: Plowshare,
    private readonly bindings: readonly MessagingBinding[] = [],
    private readonly repository?: StateRepository,
    private readonly timeoutMs = 30_000,
  ) {}
  async identity(): Promise<string> {
    const result = requirePayload(
      await this.client.request('admin.status', {}),
    );
    if (!/^@service\/[0-9a-f-]{36}$/i.test(result.handle) || result.serverAdmin)
      throw new Error(
        'The Discord adapter requires a scoped service principal',
      );
    uuid(result.handle.slice('@service/'.length));
    return result.handle;
  }
  async catalog(route: BotTarget): Promise<void> {
    const agents = requirePayload(
      await this.client.request('agent.list', { project: route.project }),
    );
    if (!agents.some((agent) => agent.name === route.bot && agent.served))
      throw new Error('Configured receiving bot is not served');
    this.binding(route.project, route.bot);
  }
  private binding(project: string, agent: string): MessagingBinding {
    const result = this.bindings.find(
      (binding) => binding.project === project && binding.bot === agent,
    );
    if (result === undefined)
      throw new Error('No explicit messaging topic binding for receiving bot');
    return result;
  }
  private async log(
    project: string,
    topic: string,
    after: string,
    limit = 100,
  ) {
    const result = requirePayload(
      await this.client.request(
        'relay.log',
        { project, topic, after, limit },
        { nonBlocking: true },
      ),
    );
    if (
      result.scope.system ||
      result.scope.project !== project ||
      result.topic.name !== topic ||
      result.after !== after ||
      result.topic.kind !== 'TEXT' ||
      result.topic.generation == null
    )
      throw new Error('Foreign or unversioned messaging topic log');
    uuid(result.topic.generation);
    if (this.repository === undefined)
      throw new Error('Messaging requires an owned durable adapter repository');
    const old = this.repository.state.relayGenerations.find(
      (item) => item.project === project && item.topic === topic,
    );
    if (old !== undefined && old.generation !== result.topic.generation)
      throw new Error(
        'Messaging topic incarnation changed; reconcile retained work before rebinding',
      );
    if (old === undefined) {
      if (this.repository.state.relayGenerations.length >= 200)
        throw new Error('Messaging topic binding capacity reached');
      this.repository.state.relayGenerations.push({
        project,
        topic,
        generation: result.topic.generation,
      });
      await this.repository.save();
    }
    return result;
  }
  private async command(
    binding: MessagingBinding,
    client: string,
    operation: string,
    encoded: string,
  ): Promise<TaskSnapshot> {
    // A fresh head excludes stale responses; a lost mutation receipt is recovered with inspect,
    // never by republishing this command. Both incarnation pins are durable before submission.
    await this.log(binding.project, binding.requests, '0', 1);
    const initial = await this.log(binding.project, binding.replies, '0', 1);
    const head = initial.topic.through;
    requirePayload(
      await this.client.request(
        'relay.publish',
        {
          requestId: operation,
          project: binding.project,
          topic: binding.requests,
          text: encoded,
          occurredAt: new Date().toISOString(),
        },
        { nonBlocking: true },
      ),
    );
    let after = head;
    const deadline = Date.now() + this.timeoutMs;
    try {
      for (let pages = 0; pages < 128 && Date.now() < deadline; pages++) {
        const result = await this.log(binding.project, binding.replies, after);
        if (result.gapThrough !== null)
          throw new Error('Messaging response log has a retention gap');
        for (const event of result.events) {
          if (event.publisher !== 'messaging:ingress') continue;
          if (event.payload.kind !== 'TEXT' || event.payload.text === null)
            throw new Error('Invalid messaging response carrier');
          const snapshot = taskSnapshot(event.payload.text);
          if (
            snapshot.requestId !== operation ||
            snapshot.sourceEvent !== operation ||
            snapshot.client !== client
          )
            continue;
          if (snapshot.task === null) {
            if (snapshot.refusal === null)
              throw new Error('Missing messaging outcome');
            throw new MessagingRefusal(snapshot.refusal);
          }
          if (snapshot.task.agent !== binding.bot)
            throw new Error('Foreign receiving agent');
          return snapshot;
        }
        if (result.next === after) await pause(100);
        after = result.next;
      }
      throw new Error('Messaging receipt not observed within bounded wait');
    } catch (error) {
      // Broker acceptance already happened. Even a later read refusal is not a definite refusal
      // of the native admission/cancellation, and must not permit the caller to repeat it.
      if (error instanceof MessagingRefusal) throw error;
      throw new ConnectionFault(
        'RESPONSE_LOST',
        'Messaging outcome requires read-only inspection; command was not replayed',
        { cause: error },
      );
    }
  }
  private traversal(
    binding: MessagingBinding,
    client: string,
    id: string | null,
    requestId?: string,
  ): MessageTraversal | undefined {
    return this.repository?.state.messageTraversals.find(
      (row) =>
        row.project === binding.project &&
        row.agent === binding.bot &&
        row.requests === binding.requests &&
        row.replies === binding.replies &&
        row.client === client &&
        (id === null
          ? row.header.requestId === requestId
          : row.header.id === id),
    );
  }
  private requireOwner(
    binding: MessagingBinding,
    client: string,
    id: string | null,
    requestId?: string,
  ): void {
    const retained = this.traversal(binding, client, id, requestId);
    if (
      !this.repository?.state.records.some(
        (record) =>
          record.project === binding.project &&
          record.bot === binding.bot &&
          record.client === client &&
          (id === null
            ? record.kind === 'ASK' && record.requestId === requestId
            : record.taskId === id ||
              (record.kind === 'ASK' &&
                retained !== undefined &&
                record.requestId === retained.header.requestId)),
      )
    )
      throw new Error('Messaging command requires an owned delivery record');
  }
  private async retain(traversal: MessageTraversal): Promise<void> {
    if (this.repository === undefined)
      throw new Error('Messaging requires an owned durable adapter repository');
    const owner = this.repository.state.records.find(
      (record) =>
        record.project === traversal.project &&
        record.bot === traversal.agent &&
        record.client === traversal.client &&
        (record.taskId === traversal.header.id ||
          (record.kind === 'ASK' &&
            record.requestId === traversal.header.requestId)),
    );
    if (owner === undefined)
      throw new Error('Messaging traversal requires an owned delivery record');
    const native = owner.nativeHeader;
    if (
      native !== undefined &&
      native !== null &&
      (native.context !== traversal.header.context ||
        native.requestId !== traversal.header.requestId ||
        native.message !== traversal.header.message ||
        native.createdAt !== traversal.header.createdAt ||
        BigInt(native.revision) > BigInt(traversal.header.revision))
    )
      throw new Error(
        'Messaging task differs from its retained native identity',
      );
    const rows = this.repository.state.messageTraversals;
    const old = rows.findIndex(
      (row) =>
        row.project === traversal.project &&
        row.agent === traversal.agent &&
        row.client === traversal.client &&
        row.header.id === traversal.header.id,
    );
    const previous = rows[old];
    if (
      previous !== undefined &&
      (previous.requests !== traversal.requests ||
        previous.replies !== traversal.replies ||
        previous.header.context !== traversal.header.context ||
        previous.header.requestId !== traversal.header.requestId ||
        previous.header.message !== traversal.header.message ||
        previous.header.createdAt !== traversal.header.createdAt)
    )
      throw new Error('Retained traversal ownership changed');
    const retained = rows.filter((_, index) => index !== old);
    retained.push(traversal);
    const bytes = retained.reduce(
      (sum, row) =>
        sum +
        row.parts.reduce(
          (size, part) => size + Buffer.byteLength(part.reply.body),
          0,
        ),
      0,
    );
    if (retained.length > 200 || bytes > 50 * 1024 * 1024)
      throw new Error(
        'Messaging retained traversal capacity reached; checkpoint was not advanced',
      );
    rows.splice(0, rows.length, ...retained);
    await this.repository.save();
  }
  private async assembled(
    binding: MessagingBinding,
    client: string,
    first?: TaskSnapshot,
    existing?: MessageTraversal,
  ): Promise<MessageTask> {
    let retained = existing;
    let page = first;
    const deadline = Date.now() + this.timeoutMs;
    for (let pages = 0; pages < 256 && Date.now() < deadline; pages++) {
      if (page !== undefined) {
        const task = page.task;
        if (task === null) throw new Error('A positive task page is required');
        const previous = retained?.header;
        if (
          task.agent !== binding.bot ||
          (previous !== undefined &&
            (task.id !== previous.id ||
              task.context !== previous.context ||
              task.requestId !== previous.requestId ||
              task.agent !== previous.agent ||
              task.message !== previous.message ||
              task.createdAt !== previous.createdAt))
        )
          throw new Error('Conflicting messaging history identity');
        if (
          previous !== undefined &&
          task.revision === previous.revision &&
          (task.state !== previous.state || task.ending !== previous.ending)
        )
          throw new Error('Conflicting equal messaging revision');
        const { replies: chunks, ...incoming } = task;
        const header =
          previous !== undefined &&
          BigInt(previous.revision) > BigInt(incoming.revision)
            ? previous
            : incoming;
        const parts = [...(retained?.parts ?? [])];
        for (const chunk of chunks) {
          const index = parts.findIndex((part) => part.reply.id === chunk.id);
          const prior = index < 0 ? undefined : parts[index];
          const offset = Number(chunk.offset);
          let body = chunk.body;
          if (prior === undefined) {
            if (offset !== 0 || parts.length >= 200)
              throw new Error(
                'Messaging history backlog requires explicit recovery',
              );
          } else {
            if (
              prior.reply.finalReply !== chunk.finalReply ||
              prior.reply.generated !== chunk.generated ||
              prior.reply.ending !== chunk.ending ||
              prior.reply.postedAt !== chunk.postedAt
            )
              throw new Error('Conflicting immutable reply metadata');
            const points = Array.from(prior.reply.body),
              added = Array.from(chunk.body);
            if (offset < points.length) {
              if (
                !Number.isSafeInteger(offset) ||
                offset + added.length > points.length ||
                points.slice(offset, offset + added.length).join('') !==
                  chunk.body ||
                (chunk.complete && offset + added.length !== points.length)
              )
                throw new Error('Conflicting duplicate messaging chunk');
              continue;
            }
            if (prior.complete || offset !== points.length)
              throw new Error(
                'Messaging reply chunks conflict or skip content',
              );
            body = prior.reply.body + chunk.body;
          }
          if (Buffer.byteLength(body) > 1_048_576)
            throw new Error(
              'Assembled messaging reply exceeds supported bound',
            );
          const part = {
            reply: {
              id: chunk.id,
              body,
              finalReply: chunk.finalReply,
              generated: chunk.generated,
              ending: chunk.ending,
              postedAt: chunk.postedAt,
            },
            complete: chunk.complete,
          };
          if (index < 0) parts.push(part);
          else parts[index] = part;
        }
        if (
          page.next === null &&
          (page.resume === null || parts.some((part) => !part.complete))
        )
          throw new Error('Incomplete messaging history');
        retained = {
          project: binding.project,
          agent: binding.bot,
          requests: binding.requests,
          replies: binding.replies,
          client,
          header,
          parts,
          next: page.next,
          resume: page.next === null ? page.resume : (retained?.resume ?? null),
          terminalFollowed: retained?.terminalFollowed ?? false,
        };
        await this.retain(retained);
      }
      if (retained === undefined)
        throw new Error('Missing retained messaging traversal');
      if (retained.next === null) {
        const terminal = ['COMPLETED', 'FAILED', 'CANCELED'].includes(
          retained.header.state,
        );
        if (terminal && !retained.terminalFollowed) {
          // Current headers can advance beyond a captured reply head. One fresh FOLLOW after
          // observing terminal state captures the terminal write's reply transaction as well.
          retained = {
            ...retained,
            next: retained.resume,
            terminalFollowed: true,
          };
          await this.retain(retained);
        } else
          return {
            ...retained.header,
            replies: retained.parts.map((part) => part.reply),
            cursor: retained.resume,
          };
      }
      const operation = randomUUID();
      page = await this.command(
        binding,
        client,
        operation,
        JSON.stringify({
          schema: 'plowshare-message-inspect/1',
          requestId: operation,
          client,
          task: retained.header.id,
          originalRequest: null,
          cursor: retained.next,
        }),
      );
    }
    throw new Error(
      'Messaging history exceeded bounded wait; fixed-head progress remains retained',
    );
  }
  private async completeMutation(
    binding: MessagingBinding,
    client: string,
    page: TaskSnapshot,
  ): Promise<MessageTask> {
    try {
      return await this.assembled(binding, client, page);
    } catch (error) {
      throw new ConnectionFault(
        'RESPONSE_LOST',
        'Admitted messaging command requires read-only inspection; partial history did not permit replay',
        { cause: error },
      );
    }
  }
  async receive(request: Submission): Promise<MessageTask> {
    uuid(request.requestId);
    if (
      !/^[a-zA-Z0-9_.-]{1,64}$/.test(request.client) ||
      !request.body.trim() ||
      request.body.includes('\0') ||
      /[\uD800-\uDFFF]/u.test(request.body) ||
      Buffer.byteLength(request.body) > 65536
    )
      throw new Error('Invalid messaging admission input');
    if (request.context !== null) uuid(request.context);
    const binding = this.binding(request.project, request.agent);
    this.requireOwner(binding, request.client, null, request.requestId);
    const page = await this.command(
      binding,
      request.client,
      request.requestId,
      JSON.stringify({
        schema: 'plowshare-message-request/1',
        requestId: request.requestId,
        client: request.client,
        context: request.context,
        body: request.body,
        command: null,
      }),
    );
    if (
      page.task === null ||
      page.task.requestId !== request.requestId ||
      (request.context !== null && page.task.context !== request.context)
    )
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'Foreign admitted messaging identity',
      );
    const task = await this.completeMutation(binding, request.client, page);
    if (task.requestId !== request.requestId)
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'Foreign original messaging request',
      );
    if (request.context !== null && task.context !== request.context)
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'Foreign messaging context',
      );
    return task;
  }
  async status(reference: TaskReference): Promise<MessageTask> {
    if (reference.id === null && reference.requestId === undefined)
      throw new Error(
        'Inspection needs a retained task or original request UUID',
      );
    const operation = randomUUID();
    const binding = this.binding(reference.project, reference.agent);
    this.requireOwner(
      binding,
      reference.client,
      reference.id,
      reference.requestId,
    );
    const retained = this.traversal(
      binding,
      reference.client,
      reference.id,
      reference.requestId,
    );
    if (retained !== undefined)
      return await this.assembled(
        binding,
        reference.client,
        undefined,
        retained,
      );
    const page = await this.command(
      binding,
      reference.client,
      operation,
      JSON.stringify({
        schema: 'plowshare-message-inspect/1',
        requestId: operation,
        client: reference.client,
        task: reference.id,
        originalRequest: reference.id === null ? reference.requestId : null,
        cursor: reference.cursor ?? null,
      }),
    );
    if (
      page.task === null ||
      (reference.id === null && page.task.requestId !== reference.requestId) ||
      (reference.id !== null && page.task.id !== reference.id)
    )
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'Foreign inspected messaging identity',
      );
    const task = await this.assembled(binding, reference.client, page);
    if (reference.id === null && task.requestId !== reference.requestId)
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'Foreign original messaging request',
      );
    if (reference.id !== null && task.id !== reference.id)
      throw new ConnectionFault('INVALID_ENVELOPE', 'Foreign messaging task');
    return task;
  }
  async cancel(reference: TaskReference): Promise<MessageTask> {
    if (reference.id === null || reference.requestId === undefined)
      throw new Error(
        'Cancellation requires a retained task and operation UUID',
      );
    const binding = this.binding(reference.project, reference.agent);
    this.requireOwner(
      binding,
      reference.client,
      reference.id,
      reference.requestId,
    );
    const page = await this.command(
      binding,
      reference.client,
      reference.requestId,
      JSON.stringify({
        schema: 'plowshare-message-cancel/1',
        requestId: reference.requestId,
        client: reference.client,
        task: reference.id,
      }),
    );
    if (page.task === null || page.task.id !== reference.id)
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'Foreign canceled messaging identity',
      );
    const task = await this.completeMutation(binding, reference.client, page);
    if (task.id !== reference.id)
      throw new ConnectionFault('INVALID_ENVELOPE', 'Foreign messaging task');
    return task;
  }
  async consume(request: RelayConsumeRequest) {
    return requirePayload(await this.client.request('relay.consume', request));
  }
  async acknowledge(request: RelayAckRequest): Promise<void> {
    requirePayload(await this.client.request('relay.ack', request));
  }
}

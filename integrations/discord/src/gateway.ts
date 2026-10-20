import { requirePayload } from 'plowshare-client-node';
import type { Plowshare } from 'plowshare-client-node';
import type {
  Gateway,
  IncomingTask,
  BotTarget,
  Submission,
  TaskReference,
} from './contracts.ts';
import { uuid } from './state.ts';
import type {
  RelayAckRequest,
  RelayConsumeRequest,
} from 'plowshare-client-node';

/** Adapter coordinates must be UUIDs, stricter than the SDK's general string views. */
function checkedTask(task: IncomingTask): IncomingTask {
  uuid(task.id);
  uuid(task.context);
  if (
    ![
      'SUBMITTED',
      'WORKING',
      'INPUT_REQUIRED',
      'COMPLETED',
      'FAILED',
      'CANCELED',
    ].includes(task.state) ||
    task.replies.length > 200
  )
    throw new Error('Invalid incoming task lifecycle');
  return task;
}

/** Composition supplies the socket. All domain work uses the existing typed WS operations. */
export class SdkGateway implements Gateway {
  constructor(private readonly client: Plowshare) {}
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
    const result = requirePayload(
      await this.client.request('incoming.catalog', {
        project: route.project,
        agent: route.bot,
      }),
    );
    if (!result.served || result.name !== route.bot)
      throw new Error('Configured receiving bot is not served');
  }
  async receive(request: Submission) {
    const task = checkedTask(
      requirePayload(await this.client.request('incoming.receive', request)),
    );
    if (
      task.agent !== request.agent ||
      (request.context != null && task.context !== request.context) ||
      JSON.stringify(task.source) !== JSON.stringify(request.source ?? null)
    )
      throw new Error('Foreign incoming receipt');
    return task;
  }
  async status(reference: TaskReference) {
    const task = checkedTask(
      requirePayload(await this.client.request('incoming.status', reference)),
    );
    if (task.id !== reference.id) throw new Error('Foreign incoming task');
    return task;
  }
  async cancel(reference: TaskReference) {
    const task = checkedTask(
      requirePayload(await this.client.request('incoming.cancel', reference)),
    );
    if (task.id !== reference.id) throw new Error('Foreign incoming task');
    return task;
  }
  async consume(request: RelayConsumeRequest) {
    // Public SDK dispatch binds project/topic/group/consumer and the requested limit.
    return requirePayload(await this.client.request('relay.consume', request));
  }
  async acknowledge(request: RelayAckRequest): Promise<void> {
    // SDK dispatch also checks batch ownership and refuses unexpected gap receipts.
    requirePayload(await this.client.request('relay.ack', request));
  }
}

import type { RequestSignal } from '../binding/acting.ts';
import { ConnectionFault } from '../binding/connection.ts';
import type { GoalPage, GoalStatus } from './replies.ts';
import { decodeRequest } from './schema.ts';
import type { checkedTransport } from './transport.ts';

export type GoalControl = 'pause' | 'resume' | 'cancel';

export interface GoalLimits {
  readonly maxActivations?: number;
  readonly maxModelCalls?: number;
}

export function goalControls(status: GoalStatus): readonly GoalControl[] {
  if (!status.readable) return [];
  switch (status.state) {
    case 'READY':
    case 'RUNNING':
    case 'IDLE':
    case 'WAITING':
      return ['pause', 'cancel'];
    case 'PAUSED':
      return ['resume', 'cancel'];
    case 'BLOCKED':
      return status.reason === 'AGENT_BLOCKED'
        ? ['resume', 'cancel']
        : ['cancel'];
    case 'PAUSING':
      return ['cancel'];
    default:
      return [];
  }
}

type ReceiptState = 'submitting' | 'pending' | 'missing';
type Target = Pick<GoalStatus, 'project' | 'goal' | 'conversation'>;
interface Intent {
  readonly requestId: string;
  readonly version: number;
  state: ReceiptState;
}

const readOptions = { timeoutMs: 2000 };

/** One authenticated client's Goal Agent controls and unsettled request identities.
 * Reads can reconcile a control. They never resubmit it. */
export class GoalAgentClient {
  private readonly intents = new Map<string, Intent>();

  private readonly operations: ReturnType<typeof checkedTransport>;
  private readonly requestId: () => string;

  constructor(
    operations: ReturnType<typeof checkedTransport>,
    requestId: () => string,
  ) {
    this.operations = operations;
    this.requestId = requestId;
  }

  private key(target: Target): string {
    return JSON.stringify([target.project, target.goal, target.conversation]);
  }

  reset(): void {
    this.intents.clear();
  }

  pending(target: Target): ReceiptState | undefined {
    return this.intents.get(this.key(target))?.state;
  }

  forget(status: GoalStatus): void {
    const key = this.key(status);
    if (!status.readable || this.intents.get(key)?.state !== 'missing')
      throw new Error(
        'Inspect the current status and check for a missing receipt before clearing.',
      );
    this.intents.delete(key);
  }

  async list(
    project: string,
    after?: string,
    signal?: RequestSignal,
  ): Promise<GoalPage> {
    const reply = await this.operations.ask(
      'goal.list',
      { project, limit: 25, ...(after === undefined ? {} : { after }) },
      { ...readOptions, ...(signal === undefined ? {} : { signal }) },
    );
    if (!reply.payload) throw new Error(reply.said ?? reply.code);
    return reply.payload;
  }

  async inspect(
    project: string,
    goal: string,
    signal?: RequestSignal,
  ): Promise<GoalStatus> {
    const reply = await this.operations.ask(
      'goal.status',
      { project, goal },
      { ...readOptions, ...(signal === undefined ? {} : { signal }) },
    );
    if (!reply.payload) throw new Error(reply.said ?? reply.code);
    if (signal?.aborted) throw new Error('Goal status read was cancelled.');
    const status = reply.payload;
    const key = this.key(status);
    const intent = this.intents.get(key);
    if (!status.readable || !intent || intent.state === 'submitting')
      return status;

    intent.state = 'pending';
    const receipt = await this.operations
      .ask(
        'conversation.goal.get',
        { conversation: status.conversation, requestId: intent.requestId },
        { ...readOptions, ...(signal === undefined ? {} : { signal }) },
      )
      .catch(() => undefined);
    if (signal?.aborted) throw new Error('Goal status read was cancelled.');
    if (this.intents.get(key) !== intent) return status;
    if (
      receipt?.code === 'OK' &&
      receipt.payload?.goal === status.goal &&
      receipt.payload.conversation === status.conversation &&
      receipt.payload.version === intent.version + 1 &&
      status.version >= receipt.payload.version
    ) {
      this.intents.delete(key);
    } else if (receipt?.code === 'NOT_FOUND') {
      intent.state = 'missing';
    }
    return status;
  }

  async change(
    status: GoalStatus,
    action: GoalControl,
    limits: GoalLimits = {},
  ): Promise<void> {
    const key = this.key(status);
    if (this.intents.has(key))
      throw new Error('Refresh the goal status to check the pending control.');
    if (!goalControls(status).includes(action))
      throw new Error('That control is unavailable in the current state.');

    const requestId = this.requestId();
    const type = `conversation.goal.${action}` as const;
    const payload = {
      goal: status.goal,
      conversation: status.conversation,
      version: status.version,
      requestId,
      ...(action === 'resume' ? limits : {}),
    };
    // Validate locally before retaining a request as potentially submitted.
    decodeRequest(type, payload);
    const intent: Intent = {
      requestId,
      version: status.version,
      state: 'submitting',
    };
    this.intents.set(key, intent);
    try {
      const reply = await this.operations.ask(type, payload, {
        ...readOptions,
        nonBlocking: true,
      });
      if (this.intents.get(key) !== intent) return;
      if (
        reply.code === 'OK' &&
        reply.payload?.goal === status.goal &&
        reply.payload.conversation === status.conversation &&
        reply.payload.version === status.version + 1
      ) {
        this.intents.delete(key);
        return;
      }
      if (reply.code === 'CONFLICT' || reply.code === 'CAPACITY_EXHAUSTED') {
        this.intents.delete(key);
        throw new Error(reply.said ?? reply.code);
      }
    } catch (failure) {
      if (this.intents.get(key) !== intent) throw failure;
      if (
        failure instanceof ConnectionFault &&
        failure.delivery === 'NOT_SUBMITTED'
      ) {
        this.intents.delete(key);
        throw failure;
      }
    }
    if (this.intents.get(key) === intent) intent.state = 'pending';
  }
}

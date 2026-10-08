import { decodeServerPush, type ServerPush } from './push.ts';
import { connect, ConnectionFault } from '../binding/connection.ts';
import type {
  Connection,
  ConnectOptions as WireConnectOptions,
} from '../binding/connection.ts';
import type { Code } from '../binding/codes.ts';
import { dispatch, request } from './direct.ts';
import type { Operation, Payloads, Request } from './direct.ts';
import { decodePayload, decodeReply, isOperation } from './schema.ts';
import { OPERATION_SCHEMAS } from './operation-schemas.ts';
import type { Replies } from './replies.ts';

export { ConnectionFault } from '../binding/connection.ts';
export type { ServerPush, JobNotification } from './push.ts';
export interface ConnectOptions extends Omit<WireConnectOptions, 'onPush'> {
  readonly onPush?: (push: ServerPush) => void;
  /** Malformed hints are dropped, reported here, and reconciled through durable operations. */
  readonly onPushFault?: (fault: ConnectionFault) => void;
}
export type { Payloads } from './direct.ts';
export type { Replies } from './replies.ts';
export type { JobView, JobOutcome } from '../binding/job-view.ts';
export type {
  ExternalMessage,
  ExternalPart,
  ExternalResult,
  ExternalTask,
  IncomingSource,
  IncomingCommand,
  AgentCard,
  IntegrationRequest,
  IntegrationResult,
} from './external.ts';
export type { SwarmSelection, SwarmType, SwarmTypes } from './swarm-types.ts';
export type { OutgoingWork } from './outgoing.ts';
export type {
  RelayPublishRequest,
  RelayConsumeRequest,
  RelayAckRequest,
  RelayBatch,
  FilterReviewRequest,
  FilterReviewResponse,
} from './relay-ports.ts';
export { JobLifecycle } from '../jobs/lifecycle.ts';
export {
  ToolProvider,
  toolDeploymentConfig,
  validateToolArguments,
  decodeToolCall,
  toolResultRequestId,
} from './relay-tools.ts';
export type {
  ToolArguments,
  ToolBinding,
  ToolCall,
  ToolDeclaration,
  ToolHost,
  ToolJournal,
  ToolParameter,
  ToolReceipt,
  ToolResult,
  ToolScalar,
  ToolState,
  RegisteredTool,
} from './relay-tools.ts';
export const OPERATIONS: readonly Operation[] = Object.freeze(
  Object.keys(OPERATION_SCHEMAS.inputs).filter(isOperation).sort(),
);

/** Refusals carry checked status/detail, never an unvalidated server payload. */
export interface RefusedReply {
  readonly kind: 'refused';
  readonly outcome: { readonly code: Code; readonly said?: string };
}
/** An operation's success payload is decoded before it reaches an SDK consumer. */
export type Reply<K extends Operation = Operation> =
  | RefusedReply
  | {
      readonly kind: 'success';
      readonly outcome: {
        readonly code: Code;
        readonly said?: string;
        readonly payload: Replies[K];
      };
    };
export class Refusal extends Error {
  readonly reply: RefusedReply;
  constructor(reply: RefusedReply) {
    super(reply.outcome.said ?? reply.outcome.code);
    this.reply = reply;
  }
}
export function requirePayload<K extends Operation>(
  reply: Reply<K>,
): Replies[K] {
  if (reply.kind === 'refused') throw new Refusal(reply);
  return reply.outcome.payload;
}

/**
 * Public SDK over the shared transport. Requests are validated before sending;
 * malformed replies fail with uncertain completion and are never replayed.
 * Raw envelope diagnostics remain in the transport, outside application APIs.
 */
export class Plowshare {
  private readonly connection: Connection;
  readonly session: string | undefined;
  constructor(options: ConnectOptions & { readonly session?: string }) {
    this.connection = connect({
      ...options,
      onPush: (value) => {
        let push: ServerPush;
        try {
          push = decodeServerPush(value);
        } catch (cause) {
          options.onPushFault?.(
            new ConnectionFault(
              'INVALID_ENVELOPE',
              'Unreadable server notification; reconcile through durable reads.',
              { cause },
            ),
          );
          return;
        }
        options.onPush?.(push);
      },
    });
    this.session = options.session;
  }
  async request<K extends Operation>(
    type: K,
    payload: Payloads[K],
  ): Promise<Reply<K>> {
    const checked = decodePayload(type, payload);
    // The generic API preserves pairing; the mapped request union requires a
    // narrow compiler assertion when passed to the existing lifecycle decoder.
    const result = await dispatch(
      this.connection,
      request(type, checked) as Request,
    );
    if (result.kind === 'invalid-response')
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'unreadable operation result; completion is unknown; no mutation was replayed',
      );
    const { code, said } = result.outcome;
    if (result.kind === 'refused')
      return {
        kind: 'refused',
        outcome: { code, ...(said === undefined ? {} : { said }) },
      };
    let decoded: Replies[K];
    try {
      decoded = decodeReply(type, result.outcome.payload);
    } catch (cause) {
      throw new ConnectionFault(
        'INVALID_ENVELOPE',
        'unreadable operation result; completion is unknown; no mutation was replayed',
        { cause },
      );
    }
    return {
      kind: 'success',
      outcome: {
        code,
        ...(said === undefined ? {} : { said }),
        payload: decoded,
      },
    };
  }
  jobStatus(job: string): Promise<Reply<'job.status'>> {
    return this.request('job.status', { job });
  }
  cancelJob(job: string): Promise<Reply<'job.cancel'>> {
    return this.request('job.cancel', { job });
  }
  openConversation(
    payload: Payloads['conversation.open'] = {},
  ): Promise<Reply<'conversation.open'>> {
    return this.request('conversation.open', payload);
  }
  runAgent(payload: Payloads['agent.run']): Promise<Reply<'agent.run'>> {
    return this.request('agent.run', {
      ...(this.session === undefined ? {} : { session: this.session }),
      ...payload,
    });
  }
  sendOutgoing(
    payload: Payloads['outgoing.send'],
  ): Promise<Reply<'outgoing.send'>> {
    return this.request('outgoing.send', payload);
  }
  outgoingStatus(id: string): Promise<Reply<'outgoing.status'>> {
    return this.request('outgoing.status', { id });
  }
  cancelOutgoing(id: string): Promise<Reply<'outgoing.cancel'>> {
    return this.request('outgoing.cancel', { id });
  }
  /** Explicit ingress. Keep the request UUID to reconcile uncertain publication. */
  publishRelay(
    payload: Payloads['relay.publish'],
  ): Promise<Reply<'relay.publish'>> {
    return this.request('relay.publish', payload);
  }
  /** Bounded topic listening. Reading never acknowledges the consumer group's batch. */
  consumeRelay(
    payload: Payloads['relay.consume'],
  ): Promise<Reply<'relay.consume'>> {
    return this.request('relay.consume', payload);
  }
  /** Acknowledge the complete issued batch explicitly after handling its records. */
  acknowledgeRelay(
    payload: Payloads['relay.ack'],
  ): Promise<Reply<'relay.ack'>> {
    return this.request('relay.ack', payload);
  }
  close(): void {
    this.connection.close();
  }
}

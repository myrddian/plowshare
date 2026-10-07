import WebSocket from 'ws';
import { randomUUID } from 'node:crypto';
import {
  Plowshare,
  ConnectionFault,
  type ServerPush,
} from 'plowshare-client-ts';
export {
  Plowshare,
  ConnectionFault,
  Refusal,
  requirePayload,
  OPERATIONS,
  JobLifecycle,
} from 'plowshare-client-ts';
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
  ServerPush,
  Reply,
  Payloads,
  Replies,
  OutgoingWork,
  JobView,
  JobOutcome,
  RelayPublishRequest,
  RelayConsumeRequest,
  RelayAckRequest,
  RelayBatch,
  FilterReviewRequest,
  FilterReviewResponse,
} from 'plowshare-client-ts';

export interface ClientOptions {
  readonly origin: string;
  readonly token: string;
  readonly session?: string;
  readonly timeoutMs?: number;
  readonly onPushFault?: (fault: ConnectionFault) => void;
  readonly onPush?: (push: ServerPush) => void;
}
/** Header-authenticated SDK connection; no credential persistence, HTTP bootstrap or reconnect. */
export async function connectPlowshare(
  options: ClientOptions,
): Promise<Plowshare> {
  const origin = new URL(options.origin);
  const timeout = options.timeoutMs ?? 30_000;
  const session = options.session ?? randomUUID();
  if (
    !['http:', 'https:'].includes(origin.protocol) ||
    origin.username ||
    origin.password ||
    origin.pathname !== '/' ||
    origin.search ||
    origin.hash
  )
    throw new Error(
      'an HTTP(S) origin without credentials, path, query or fragment is required',
    );
  if (
    !options.token.trim() ||
    !session.trim() ||
    !Number.isFinite(timeout) ||
    timeout <= 0
  )
    throw new Error('token, session and a positive timeout are required');
  origin.protocol = origin.protocol === 'https:' ? 'wss:' : 'ws:';
  origin.pathname = '/v1/events';
  origin.searchParams.set('session', session);
  const socket = new WebSocket(origin, {
    headers: { Authorization: `Bearer ${options.token}` },
    followRedirects: false,
    handshakeTimeout: timeout,
    maxPayload: 1_048_576,
  });
  await new Promise<void>((resolve, reject) => {
    const failed = (): void => {
      socket.terminate();
      reject(
        new ConnectionFault(
          'NOT_SUBMITTED',
          'Plowshare WebSocket upgrade failed; no application request was submitted',
        ),
      );
    };
    socket.once('error', failed);
    socket.once('close', failed);
    socket.once('open', () => {
      socket.off('error', failed);
      socket.off('close', failed);
      resolve();
    });
  });
  // Keep late transport errors from becoming unhandled EventEmitter exceptions; close strands asks.
  socket.on('error', () => {});
  return new Plowshare({
    socket,
    session,
    deadline: {
      milliseconds: timeout,
      schedule: (expired, milliseconds) => {
        const timer = setTimeout(expired, milliseconds);
        return () => clearTimeout(timer);
      },
    },
    ...(options.onPushFault === undefined
      ? {}
      : { onPushFault: options.onPushFault }),
    ...(options.onPush === undefined ? {} : { onPush: options.onPush }),
  });
}

export {
  nodeToolHost,
  ToolProvider,
  toolDeploymentConfig,
  validateToolArguments,
  decodeToolCall,
  toolResultRequestId,
} from './tools.ts';
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
} from './tools.ts';

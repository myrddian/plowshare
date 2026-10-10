import { succeeded } from '../../sdk/typescript/src/binding/codes.ts';
import { checkedTransport } from '../../sdk/typescript/src/operations/transport.ts';
import { openEventStream, type EventStream } from './events';
import { request, ApiError } from './api';
import {
  decodeRequest,
  decodeReply,
  decodeContract,
} from '../../sdk/typescript/src/operations/schema.ts';
import type { Replies } from '../../sdk/typescript/src/operations/replies.ts';
import type {
  Payloads,
  Operation,
} from '../../sdk/typescript/src/operations/direct.ts';
import { isObject, isList } from '../../sdk/typescript/src/binding/values.ts';
import type {
  Setting,
  ProjectView,
  ContextView,
  RecallResponse,
  DocumentSearchResponse,
  TocEntry,
} from './screens/wire';
import type { JobView, StartedJob } from './repl/wire';
import { CONSOLE_SCHEMAS } from './transport-schemas';
export interface ConsoleReplies {
  'job.status': JobView;
  'job.list': readonly JobView[];
  'job.cancel': JobView;
  'job.limits': JobView;
  'agent.run': StartedJob;
  'conversation.resume': StartedJob;
  'project.list': readonly ProjectView[];
  'project.workspace': ProjectView;
  'conversation.context': ContextView;
  'memory.recall': RecallResponse;
  'memory.index': readonly TocEntry[];
  'document.search': DocumentSearchResponse;
}
type CheckedReplies = Omit<Replies, keyof ConsoleReplies> & ConsoleReplies;

type ConversationPath = `/v1/conversations/${string}`;
type ReadPath =
  | '/v1/config'
  | `/v1/projects${string}`
  | `/v1/agents${string}`
  | `/v1/conversations${string}`
  | `/v1/jobs${string}`
  | `/v1/proposals${string}`
  | `/v1/memories/${string}`;
type ReadReply<P extends ReadPath> = P extends '/v1/config'
  ? readonly Setting[]
  : P extends `${ConversationPath}/turns`
    ? CheckedReplies['conversation.turns']
    : P extends `${ConversationPath}/compactions`
      ? CheckedReplies['conversation.compactions']
      : P extends `${ConversationPath}/projection`
        ? CheckedReplies['conversation.projection']
        : P extends `${ConversationPath}/context`
          ? CheckedReplies['conversation.context']
          : P extends
                | `${ConversationPath}/chat${string}`
                | `${ConversationPath}/trajectory${string}`
            ? CheckedReplies['conversation.trajectory']
            : P extends `/v1/conversations${string}`
              ? CheckedReplies['conversation.list']
              : P extends `/v1/projects${string}`
                ? CheckedReplies['project.list']
                : P extends `/v1/agents${string}`
                  ? CheckedReplies['agent.list']
                  : P extends '/v1/jobs'
                    ? CheckedReplies['job.list']
                    : P extends `/v1/jobs/${string}`
                      ? CheckedReplies['job.status']
                      : P extends `/v1/proposals${string}`
                        ? CheckedReplies['proposal.list']
                        : P extends `/v1/memories/index${string}`
                          ? CheckedReplies['memory.index']
                          : CheckedReplies['memory.read'];
type WritePath =
  | '/v1/conversations'
  | `/v1/conversations/${string}/resume`
  | `/v1/agents/${string}/runs`
  | `/v1/jobs/${string}/limits`
  | `/v1/jobs/${string}/cancel`
  | `/v1/projects/${string}/workspace`
  | `/v1/proposals/reconsider${string}`
  | `/v1/proposals/${string}/resolve`
  | `/v1/memories/${string}/invalidate`
  | '/v1/memories/recall'
  | '/v1/memories/navigate'
  | '/v1/memories/digest'
  | '/v1/documents/search';
type WriteOperation<P extends WritePath> = P extends '/v1/conversations'
  ? 'conversation.open'
  : P extends `/v1/conversations/${string}/resume`
    ? 'conversation.resume'
    : P extends `/v1/agents/${string}/runs`
      ? 'agent.run'
      : P extends `/v1/jobs/${string}/limits`
        ? 'job.limits'
        : P extends `/v1/jobs/${string}/cancel`
          ? 'job.cancel'
          : P extends `/v1/projects/${string}/workspace`
            ? 'project.workspace'
            : P extends `/v1/proposals/reconsider${string}`
              ? 'proposal.reconsider'
              : P extends `/v1/proposals/${string}/resolve`
                ? 'proposal.resolve'
                : P extends `/v1/memories/${string}/invalidate`
                  ? 'memory.invalidate'
                  : P extends '/v1/memories/recall'
                    ? 'memory.recall'
                    : P extends '/v1/memories/navigate'
                      ? 'memory.navigate'
                      : P extends '/v1/memories/digest'
                        ? 'memory.digest'
                        : 'document.search';
type WriteBody<P extends WritePath> = Omit<
  Payloads[WriteOperation<P>],
  | 'job'
  | 'agent'
  | 'project'
  | 'proposal'
  | 'memory'
  | 'conversation'
  | 'request'
> &
  (P extends `/v1/agents/${string}/runs`
    ? { task: string; conversation?: string | null }
    : P extends '/v1/conversations' | `/v1/memories/${string}`
      ? { project?: string | null }
      : object);
/** Historical route names are UI keys only. Supported capabilities travel over
 * the tab's WebSocket, and every reply is decoded before reaching a screen.
 * Config and multipart uploads remain explicit HTTP boundaries. No mutation is
 * replayed after a timeout, disconnect or unreadable response. */
export interface Transport {
  get<P extends ReadPath>(path: P): Promise<ReadReply<P>>;
  post<P extends WritePath>(
    path: P,
    payload?: WriteBody<P>,
  ): Promise<CheckedReplies[WriteOperation<P>]>;
  put(
    path: `/v1/conversations/${string}/lifecycle`,
    payload: Pick<Payloads['conversation.lifecycle'], 'lifecycle'>,
  ): Promise<CheckedReplies['conversation.lifecycle']>;
}

const REFUSALS: Readonly<Record<string, number>> = {
  BAD_REQUEST: 400,
  VALIDATION_FAILED: 400,
  NOT_FOUND: 404,
  CONFLICT: 409,
  INTERNAL_ERROR: 500,
};
/** The stream is borrowed; its lifecycle and reconnect reporting belong to the
 * shell. Pending requests belong to one generation and are never replayed. */
export function socketTransport(stream: () => EventStream): Transport {
  async function invoke(
    type: string,
    input: unknown,
  ): Promise<CheckedReplies[Operation]> {
    const checked = decodeRequest(type, input);
    const active = stream();
    await ready(active);
    const outcome = await checkedTransport(active).ask(
      checked.type,
      checked.payload,
    );
    if (!succeeded(outcome.code))
      throw new ApiError(
        outcome.said ?? `${type} was refused (${outcome.code})`,
        REFUSALS[outcome.code] ?? 500,
        outcome.said ?? null,
      );
    const decoded = decodeReply(checked.type, outcome.payload);
    const schema = CONSOLE_SCHEMAS.results[checked.type];
    return (
      schema === undefined
        ? decoded
        : decodeContract(
            schema,
            CONSOLE_SCHEMAS.$defs,
            decoded,
            checked.type,
            false,
          )
    ) as CheckedReplies[Operation];
  }
  function route(
    path: string,
    body: unknown,
    verb: 'get' | 'post' | 'put',
  ): { type: Operation; payload: unknown } {
    if (!path.startsWith('/v1/') || path.includes('#'))
      throw new Error('Invalid console route');
    if (path.length > 8192 || path.includes('\0') || path.split('?').length > 2)
      throw new Error('Invalid console route');
    const [pathname, query] = path.split('?');
    const segments = (pathname ?? '')
      .split('/')
      .slice(2)
      .map(decodeURIComponent);
    const params = new URLSearchParams(query);
    for (const key of params.keys())
      if (
        !['project', 'lifecycle', 'offset', 'limit'].includes(key) ||
        params.getAll(key).length !== 1
      )
        throw new Error('Invalid console route query');
    if (body !== undefined && !isObject(body))
      throw new Error('Invalid console request body');
    const values = isObject(body) ? body : {};
    const project = params.has('project')
      ? { project: params.get('project') }
      : {};
    const lifecycle = params.has('lifecycle')
      ? { lifecycle: params.get('lifecycle') }
      : {};
    const window = {
      ...(params.has('offset') ? { offset: Number(params.get('offset')) } : {}),
      ...(params.has('limit') ? { limit: Number(params.get('limit')) } : {}),
    };
    const [resource, id, action] = segments;
    if (resource === 'projects')
      return {
        type: verb === 'get' ? 'project.list' : 'project.workspace',
        payload: verb === 'get' ? {} : { ...values, project: id },
      };
    if (resource === 'agents')
      return verb === 'get'
        ? { type: 'agent.list', payload: project }
        : { type: 'agent.run', payload: { ...values, agent: id } };
    if (resource === 'jobs')
      return {
        type:
          verb === 'get'
            ? id
              ? 'job.status'
              : 'job.list'
            : action === 'limits'
              ? 'job.limits'
              : 'job.cancel',
        payload: id ? { ...values, job: id } : {},
      };
    if (resource === 'conversations') {
      if (!id)
        return {
          type: verb === 'get' ? 'conversation.list' : 'conversation.open',
          payload: verb === 'get' ? { ...project, ...lifecycle } : values,
        };
      switch (action) {
        case 'resume':
          return {
            type: 'conversation.resume',
            payload: { ...values, conversation: id },
          };
        case 'lifecycle':
          return {
            type: 'conversation.lifecycle',
            payload: { ...values, conversation: id },
          };
        case 'turns':
          return { type: 'conversation.turns', payload: { conversation: id } };
        case 'compactions':
          return {
            type: 'conversation.compactions',
            payload: { conversation: id },
          };
        case 'projection':
          return {
            type: 'conversation.projection',
            payload: { conversation: id },
          };
        case 'context':
          return {
            type: 'conversation.context',
            payload: { conversation: id },
          };
        case 'chat':
          return {
            type: 'conversation.chat',
            payload: { ...window, conversation: id },
          };
        case 'trajectory':
          return {
            type: 'conversation.trajectory',
            payload: { ...window, conversation: id },
          };
        default:
          throw new Error('Unknown conversation route');
      }
    }
    if (resource === 'proposals')
      return id === 'reconsider'
        ? { type: 'proposal.reconsider', payload: project }
        : action === 'resolve'
          ? { type: 'proposal.resolve', payload: { ...values, proposal: id } }
          : { type: 'proposal.list', payload: project };
    if (resource === 'memories') {
      switch (id) {
        case 'index':
          return { type: 'memory.index', payload: project };
        case 'recall':
          return { type: 'memory.recall', payload: values };
        case 'navigate':
          return { type: 'memory.navigate', payload: values };
        case 'digest':
          return { type: 'memory.digest', payload: values };
        default:
          return {
            type: action === 'invalidate' ? 'memory.invalidate' : 'memory.read',
            payload: { ...values, memory: id },
          };
      }
    }
    if (resource === 'documents' && id === 'search')
      return { type: 'document.search', payload: values };
    throw new Error('Unknown console route');
  }
  async function read(
    path: string,
  ): Promise<CheckedReplies[Operation] | readonly Setting[]> {
    if (path === '/v1/config') {
      const response = await request(path);
      if (!response.ok)
        throw new ApiError(
          `Configuration answered ${response.status}`,
          response.status,
        );
      const value: unknown = await response.json();
      if (!isList(value) || value.length > 10000)
        throw new Error('Invalid configuration listing');
      return value.map(decodeSetting);
    }
    const mapped = route(path, undefined, 'get');
    return invoke(mapped.type, mapped.payload);
  }
  // Conditional route types describe a closed mapping. Assertions are confined
  // here, after the operation-specific decoder has checked the entire result.
  return {
    get: async <P extends ReadPath>(path: P) =>
      (await read(path)) as ReadReply<P>,
    post: async <P extends WritePath>(path: P, payload?: WriteBody<P>) => {
      const mapped = route(path, payload, 'post');
      return (await invoke(
        mapped.type,
        mapped.payload,
      )) as CheckedReplies[WriteOperation<P>];
    },
    put: async (path, payload) => {
      const mapped = route(path, payload, 'put');
      return (await invoke(
        mapped.type,
        mapped.payload,
      )) as CheckedReplies['conversation.lifecycle'];
    },
  };
}
let defaultStream: EventStream | undefined;
/** Standalone screens share a tab session. The shell supplies its existing
 * multiplexed stream instead, avoiding a second listener or durable runtime. */
export const consoleTransport = socketTransport(() => {
  defaultStream ??= openEventStream({
    session: crypto.randomUUID(),
    onEvent: () => {},
  });
  return defaultStream;
});

/** Runtime configuration is an HTTP exception with its own checked DTO. */
export function decodeSetting(value: unknown): Setting {
  if (
    !isObject(value) ||
    typeof value['key'] !== 'string' ||
    !value['key'].trim() ||
    value['key'].length > 256 ||
    typeof value['pinned'] !== 'boolean'
  )
    throw new Error('Invalid configuration entry');
  for (const key of ['value', 'updatedAt', 'updatedBy'])
    if (
      value[key] != null &&
      (typeof value[key] !== 'string' ||
        value[key].length > 65536 ||
        value[key].includes('\0'))
    )
      throw new Error('Invalid configuration field');
  return {
    key: value['key'],
    pinned: value['pinned'],
    value: value['value'] as string | null | undefined,
    updatedAt: value['updatedAt'] as string | null | undefined,
    updatedBy: value['updatedBy'] as string | null | undefined,
  };
}
async function ready(stream: EventStream): Promise<void> {
  if (stream.status().state === 'open') return;
  if (stream.status().state !== 'connecting')
    throw new Error(
      'Reconnect before submitting this request. Nothing was sent.',
    );
  await new Promise<void>((resolve, reject) => {
    const deadline = Date.now() + 15000;
    const poll = setInterval(() => {
      const state = stream.status().state;
      if (state === 'open') {
        clearInterval(poll);
        resolve();
      } else if (state !== 'connecting' || Date.now() >= deadline) {
        clearInterval(poll);
        reject(
          new Error('The event connection did not open. Nothing was sent.'),
        );
      }
    }, 25);
  });
}

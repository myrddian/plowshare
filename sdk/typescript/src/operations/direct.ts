import { decodeReply } from './schema.ts';
import { problem } from './payload-validation.ts';
export { problem } from './payload-validation.ts';
import { decodeRequest } from './schema.ts';
import { isList, displayText } from '../binding/values.ts';
import {
  OUTGOING_OPERATIONS,
  isOutgoingOperation,
  outgoingReply,
} from './outgoing.ts';
import {
  MESSAGING_OPERATIONS,
  isMessagingOperation,
  messagingReply,
} from './messaging.ts';
import { USAGE_FRAMES, usageReply } from './usage.ts';
import {
  INFORMATION_FRAMES,
  informationReply,
  isInformationFrame,
} from './information-replies.ts';
import { acceptedJobOf, jobStatusOf } from '../binding/job-view.ts';
import { recordReply } from './records.ts';
import {
  ADMINISTRATIVE_OPERATIONS,
  administrativeReply,
  isAdministrativeOperation,
} from './administrative-replies.ts';
import {
  CONVERSATION_OPERATIONS,
  conversationReply,
  isConversationOperation,
} from './conversation-replies.ts';
import {
  INSPECTION_OPERATIONS,
  inspectionReply,
  isInspectionOperation,
} from './inspection-replies.ts';
import { answeredOf } from './views.ts';
import {
  RETRIEVAL_OPERATIONS,
  isRetrievalOperation,
  retrievalReply,
} from './retrieval.ts';
import type { BoundPayloads } from './administration.ts';
import type { ExtendedPayloads } from './catalog.ts';
import type { Outcome } from '../binding/envelope.ts';

/** Omitted or null means global. Never infer scope from a socket session. */
export interface Tier {
  readonly project?: string | null;
}
export interface MemoryProposal {
  readonly summary: string;
  readonly scope: string;
  readonly body: string;
  readonly formedBy: string;
  readonly formedWhere: string;
}

/** Payload names mirror the WS handlers, including memory/proposal and search q. */
export interface Payloads extends ExtendedPayloads, BoundPayloads {
  'memory.index': Tier;
  'memory.read': { readonly memory: string };
  'memory.recall': Tier & {
    readonly question: string;
    readonly limit?: number;
  };
  'memory.write': Tier & { readonly proposal: MemoryProposal };
  'memory.navigate': Tier & { readonly question: string };
  'memory.digest': Tier;
  'agent.curate': { readonly project: string; readonly maxModelCalls?: number };
  'proposal.list': Tier;
  'proposal.resolve': {
    readonly proposal: string;
    readonly accept: boolean;
    readonly by: string;
    readonly reason?: string;
  };
  'proposal.reconsider': Tier;
  'memory.invalidate': {
    readonly memory: string;
    readonly reason: string;
    readonly by: string;
  };
  'memory.reembed': Tier;
  'conversation.search': Tier & {
    readonly q: string;
    readonly mode?: 'lexical' | 'semantic' | 'hybrid';
    readonly snapshot?: string;
    readonly offset?: number;
    readonly limit?: number;
  };
  'job.status': { readonly job: string };
  'job.cancel': { readonly job: string };
}
export type Operation = keyof Payloads;
export type Request<T extends Operation = Operation> = {
  [K in T]: { readonly type: K; readonly payload: Payloads[K] };
}[T];

export function request<T extends Operation>(
  type: T,
  payload: Payloads[T],
): Request<T> {
  // The mapped union retains the pairing when requests enter a command queue.
  return { type, payload };
}

export const MEMORY_OPERATIONS = {
  index: 'memory.index',
  read: 'memory.read',
  recall: 'memory.recall',
  write: 'memory.write',
  navigate: 'memory.navigate',
  digest: 'memory.digest',
  curate: 'agent.curate',
  proposals: 'proposal.list',
  resolve: 'proposal.resolve',
  reconsider: 'proposal.reconsider',
  invalidate: 'memory.invalidate',
  reembed: 'memory.reembed',
} as const satisfies Record<string, Operation>;

export type Parsed =
  | { readonly kind: 'request'; readonly request: Request }
  | { readonly kind: 'usage'; readonly said: string }
  | { readonly kind: 'unhandled' };

export const MEMORY_USAGE =
  'memory index|read|recall|write|navigate|digest|curate|proposals|resolve|reconsider|invalidate|reembed [JSON payload]. read takes an id; recall/navigate take question text. JSON project:null selects global.';

function fields(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !isList(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

/** Adapter-neutral command grammar. The TUI strips its leading slash at the edge. */
export function parseDirect(line: string, project?: string): Parsed {
  const match = /^(memory|search|job)(?:\s+(.*))?$/s.exec(line.trim());
  if (match === null) return { kind: 'unhandled' };
  const family = match[1],
    rest = (match[2] ?? '').trim();
  let type: Operation;
  let argument: string;
  if (family === 'search') {
    type = 'conversation.search';
    argument = rest;
  } else {
    const parts = /^(\S+)(?:\s+(.*))?$/s.exec(rest);
    const verb = parts?.[1] ?? (family === 'memory' ? 'index' : '');
    argument = (parts?.[2] ?? '').trim();
    if (family === 'job') {
      if (verb !== 'status' && verb !== 'cancel')
        return { kind: 'usage', said: 'job status|cancel <job-id>' };
      type = verb === 'status' ? 'job.status' : 'job.cancel';
    } else {
      if (!Object.hasOwn(MEMORY_OPERATIONS, verb))
        return { kind: 'usage', said: MEMORY_USAGE };
      type = MEMORY_OPERATIONS[verb as keyof typeof MEMORY_OPERATIONS];
    }
  }
  let payload: Record<string, unknown>;
  if (argument.startsWith('{')) {
    try {
      const parsed = fields(JSON.parse(argument));
      if (parsed === undefined)
        return { kind: 'usage', said: 'payload must be a JSON object' };
      payload = parsed;
    } catch {
      return { kind: 'usage', said: 'payload must be a valid JSON object' };
    }
  } else if (type === 'memory.read') payload = { memory: argument };
  else if (type === 'memory.recall' || type === 'memory.navigate')
    payload = { question: argument };
  else if (type === 'conversation.search') payload = { q: argument };
  else if (type === 'job.status' || type === 'job.cancel')
    payload = { job: argument };
  else if (argument === '') payload = {};
  else
    return {
      kind: 'usage',
      said: `${type} takes a JSON payload; ${MEMORY_USAGE}`,
    };
  const tiered: readonly Operation[] = [
    'memory.index',
    'memory.recall',
    'memory.write',
    'memory.navigate',
    'memory.digest',
    'agent.curate',
    'proposal.list',
    'proposal.reconsider',
    'memory.reembed',
    'conversation.search',
  ];
  if (tiered.includes(type) && !('project' in payload) && project !== undefined)
    payload = { ...payload, project };
  const why = problem(type, payload);
  if (why !== undefined) return { kind: 'usage', said: why };
  try {
    return { kind: 'request', request: decodeRequest(type, payload) };
  } catch (failure) {
    return {
      kind: 'usage',
      said:
        failure instanceof Error ? failure.message : 'invalid operation input',
    };
  }
}

export const JOB_SUBMISSIONS: readonly Operation[] = [
  'memory.digest',
  'agent.curate',
  'agent.run',
  'conversation.resume',
  'document.ask',
];

export const VALIDATED_OPERATIONS: readonly Operation[] = [
  ...OUTGOING_OPERATIONS,
  ...MESSAGING_OPERATIONS,
  ...USAGE_FRAMES.filter(
    (type) => type !== 'usage.subscribe' && type !== 'usage.unsubscribe',
  ),
  ...INFORMATION_FRAMES,
  ...RETRIEVAL_OPERATIONS,
  ...ADMINISTRATIVE_OPERATIONS,
  ...CONVERSATION_OPERATIONS,
  ...INSPECTION_OPERATIONS,
  ...JOB_SUBMISSIONS,
  'job.status',
  'job.cancel',
  'relay.operate',
  'relay.publish',
  'relay.consume',
  'relay.ack',
  'relay.process',
  'relay.topics',
  'relay.log',
  'orchestration.record',
  'orchestration.start',
  'orchestration.receipt',
];

export const WAIT_OPERATIONS: readonly Operation[] = [
  ...JOB_SUBMISSIONS,
  'information.ask',
  'job.status',
  'job.cancel',
  'approval.answer',
  'orchestration.start',
  'orchestration.status',
  'orchestration.receipt',
  'orchestration.resume',
];

export interface Transport {
  ask(type: string, payload: unknown): Promise<Outcome>;
}
export type Result = {
  readonly kind:
    | 'completed'
    | 'incomplete'
    | 'accepted'
    | 'running'
    | 'cancelling'
    | 'refused'
    | 'invalid-response';
  readonly outcome: Outcome;
  readonly job?: string;
  readonly orchestration?: string;
  readonly conversation?: string;
};

/** One WS request only. No retry, mutation replay, HTTP fallback, agent run, or hidden wait. */
export async function dispatch(
  transport: Transport,
  asked: Request,
): Promise<Result> {
  return resultOf(asked, await transport.ask(asked.type, asked.payload));
}

/** The same checked boundary for frontends that already own request lifetime and WS I/O. */
export function resultOf(asked: Request, outcome: Outcome): Result {
  const body = fields(outcome.payload);
  if (asked.type === 'relay.operate') {
    if (outcome.code !== 'OK') return { kind: 'refused', outcome };
    try {
      const input = asked.payload;
      const reply = decodeReply('relay.operate', outcome.payload);
      return {
        kind:
          input.requestId === reply.requestId &&
          input.project === reply.project &&
          input.topic === reply.topic &&
          input.action === reply.action &&
          (input.subscriber ?? null) === reply.subscriber &&
          (input.deliveryId ?? null) === reply.deliveryId
            ? 'completed'
            : 'invalid-response',
        outcome,
      };
    } catch {
      return { kind: 'invalid-response', outcome };
    }
  }
  if (
    asked.type === 'relay.publish' ||
    asked.type === 'relay.consume' ||
    asked.type === 'relay.ack'
  ) {
    if (outcome.code !== 'OK') return { kind: 'refused', outcome };
    try {
      const reply = decodeReply(asked.type, outcome.payload);
      let matches =
        reply.project === asked.payload.project &&
        reply.topic === asked.payload.topic;
      if (asked.type === 'relay.publish')
        matches &&=
          decodeReply('relay.publish', outcome.payload).requestId ===
          asked.payload.requestId;
      if (asked.type === 'relay.consume') {
        const batch = decodeReply('relay.consume', outcome.payload);
        matches &&=
          batch.group === asked.payload.group &&
          batch.consumerId === asked.payload.consumerId &&
          batch.events.length <= (asked.payload.limit ?? 100);
      }
      if (asked.type === 'relay.ack') {
        const ack = decodeReply('relay.ack', outcome.payload);
        matches &&=
          ack.group === asked.payload.group &&
          ack.batchId === asked.payload.batchId &&
          ack.gap === (asked.payload.expiredThrough != null);
      }
      return { kind: matches ? 'completed' : 'invalid-response', outcome };
    } catch {
      return { kind: 'invalid-response', outcome };
    }
  }
  if (asked.type === 'relay.process') {
    if (outcome.code !== 'OK') return { kind: 'refused', outcome };
    try {
      const reply = decodeReply('relay.process', outcome.payload);
      return {
        kind:
          reply.project === asked.payload.project &&
          reply.admitted <= (asked.payload.limit ?? 32) &&
          reply.dispatched <= (asked.payload.limit ?? 32)
            ? 'completed'
            : 'invalid-response',
        outcome,
      };
    } catch {
      return { kind: 'invalid-response', outcome };
    }
  }
  if (asked.type === 'relay.topics' || asked.type === 'relay.log') {
    if (outcome.code !== 'OK') return { kind: 'refused', outcome };
    try {
      const decoded = decodeReply(asked.type, outcome.payload);
      if (
        decoded.scope.project !== (asked.payload.project ?? null) ||
        decoded.scope.system !== (asked.payload.system ?? false)
      )
        return { kind: 'invalid-response', outcome };
      if (asked.type === 'relay.log') {
        const page = decodeReply('relay.log', outcome.payload);
        if (
          page.topic.name !== asked.payload.topic ||
          page.after !== (asked.payload.after ?? '0') ||
          page.events.length > (asked.payload.limit ?? 100) ||
          page.branches.length > (asked.payload.limit ?? 100)
        )
          return { kind: 'invalid-response', outcome };
      } else if (
        decodeReply('relay.topics', outcome.payload).topics.length >
        (asked.payload.limit ?? 100)
      ) {
        return { kind: 'invalid-response', outcome };
      }
      return { kind: 'completed', outcome };
    } catch {
      return { kind: 'invalid-response', outcome };
    }
  }
  if (isOutgoingOperation(asked.type)) {
    const expected = asked.type === 'outgoing.send' ? 'ACCEPTED' : 'OK';
    if (outcome.code !== expected)
      return {
        kind: ['OK', 'CREATED', 'ACCEPTED', 'NO_CONTENT'].includes(outcome.code)
          ? 'invalid-response'
          : 'refused',
        outcome,
      };
    if (!outgoingReply(asked.type, outcome))
      return { kind: 'invalid-response', outcome };
    if (
      asked.type === 'outgoing.send' &&
      (body?.['requestId'] !== asked.payload.requestId ||
        body['peer'] !== asked.payload.peer)
    )
      return { kind: 'invalid-response', outcome };
    if (
      (asked.type === 'outgoing.status' || asked.type === 'outgoing.cancel') &&
      body?.['id'] !== asked.payload.id
    )
      return { kind: 'invalid-response', outcome };
    if (asked.type === 'outgoing.send') return { kind: 'accepted', outcome };
    if (asked.type === 'outgoing.peers') return { kind: 'completed', outcome };
    const state = body?.['state'];
    return {
      kind:
        state === 'COMPLETED' || state === 'CANCELED'
          ? 'completed'
          : state === 'FAILED' || state === 'REJECTED'
            ? 'refused'
            : ['QUEUED', 'DISPATCHED', 'WORKING'].includes(String(state))
              ? 'running'
              : 'incomplete',
      outcome,
    };
  }
  if (isMessagingOperation(asked.type)) {
    if (outcome.code !== 'OK') return { kind: 'refused', outcome };
    if (!messagingReply(asked.type, outcome))
      return { kind: 'invalid-response', outcome };
    const expected =
      'instance' in asked.payload
        ? asked.payload.instance
        : 'message' in asked.payload
          ? asked.payload.message
          : undefined;
    if (
      expected &&
      asked.type !== 'message.deliveries' &&
      body?.[asked.type.startsWith('message.instance') ? 'id' : 'message'] !==
        expected
    )
      return { kind: 'invalid-response', outcome };
    return { kind: 'completed', outcome };
  }
  if ((USAGE_FRAMES as readonly string[]).includes(asked.type)) {
    if (outcome.code !== 'OK') return { kind: 'refused', outcome };
    return usageReply(asked.type, outcome.payload)
      ? { kind: 'completed', outcome }
      : { kind: 'invalid-response', outcome };
  }
  if (
    asked.type === 'orchestration.start' ||
    asked.type === 'orchestration.receipt'
  ) {
    const expected = asked.type === 'orchestration.start' ? 'ACCEPTED' : 'OK';
    if (outcome.code !== expected)
      return {
        kind: ['OK', 'ACCEPTED'].includes(outcome.code)
          ? 'invalid-response'
          : 'refused',
        outcome,
      };
    if (
      typeof body?.['id'] !== 'string' ||
      !body['id'].startsWith('orc_') ||
      body['requestId'] !== asked.payload.requestId ||
      ![
        'running',
        'waiting',
        'asking',
        'finished',
        'cancelled',
        'failed',
        'capped',
      ].includes(String(body['state']))
    )
      return { kind: 'invalid-response', outcome };
    return {
      kind: asked.type === 'orchestration.start' ? 'accepted' : 'completed',
      outcome,
      orchestration: body['id'],
    };
  }
  if (isInformationFrame(asked.type)) {
    if (outcome.code !== 'OK' && outcome.code !== 'ACCEPTED')
      return { kind: 'refused', outcome };
    if (informationReply(asked.type, outcome) === undefined)
      return { kind: 'invalid-response', outcome };
    const payload = fields(asked.payload)!;
    if (asked.type === 'information.await' && isList(payload['sources'])) {
      const identity = (row: Record<string, unknown>): string =>
        typeof row['acquisition'] === 'string'
          ? 'acquisition:' + row['acquisition']
          : 'revision:' + displayText(row['revision']);
      const expected = new Set(
        payload['sources'].map((row) =>
          identity(row as Record<string, unknown>),
        ),
      );
      const outcomes = body?.['outcomes'] as Record<string, unknown>[];
      if (
        expected.size !== outcomes.length ||
        outcomes.some((row) => !expected.has(identity(row)))
      )
        return { kind: 'invalid-response', outcome };
    }
    if (asked.type === 'information.symbols') {
      const symbols = body?.['symbols'] as readonly Record<string, unknown>[];
      if (
        body?.['query'] !== payload['query'] ||
        (typeof payload['revision'] === 'string' &&
          symbols.some((row) => row['revision'] !== payload['revision']))
      )
        return { kind: 'invalid-response', outcome };
    }
    const identity =
      asked.type === 'information.status'
        ? (payload['acquisition'] ?? payload['revision'])
        : asked.type === 'information.evidence.read'
          ? payload['evidence']
          : payload['revision'];
    const returned = [
      'information.read',
      'information.ask',
      'information.outline',
    ].includes(asked.type)
      ? body?.['revision']
      : body?.['id'];
    if (
      [
        'information.read',
        'information.ask',
        'information.outline',
        'information.status',
        'information.evidence.read',
      ].includes(asked.type) &&
      typeof identity === 'string' &&
      returned !== identity
    )
      return { kind: 'invalid-response', outcome };
    return outcome.code === 'ACCEPTED'
      ? {
          kind: 'accepted',
          outcome,
          ...(asked.type === 'information.ask'
            ? { job: body!['job'] as string }
            : {}),
        }
      : { kind: 'completed', outcome };
  }
  if (outcome.code === 'ACCEPTED') {
    if (!JOB_SUBMISSIONS.includes(asked.type))
      return { kind: 'invalid-response', outcome };
    const job = acceptedJobOf(outcome)?.id;
    const conversation = body?.['conversation'];
    if (
      asked.type === 'agent.run' &&
      ((asked.payload.newConversation === true &&
        (typeof conversation !== 'string' ||
          !conversation.startsWith('cnv_'))) ||
        (conversation != null &&
          (typeof conversation !== 'string' ||
            (asked.payload.conversation != null &&
              conversation !== asked.payload.conversation))))
    )
      return { kind: 'invalid-response', outcome, ...(job ? { job } : {}) };
    return job !== undefined
      ? {
          kind: 'accepted',
          outcome,
          job,
          ...(typeof conversation === 'string' ? { conversation } : {}),
        }
      : { kind: 'invalid-response', outcome };
  }
  if (
    outcome.code !== 'OK' &&
    outcome.code !== 'CREATED' &&
    outcome.code !== 'NO_CONTENT'
  )
    return { kind: 'refused', outcome };
  if (JOB_SUBMISSIONS.includes(asked.type))
    return { kind: 'invalid-response', outcome };
  if (
    (asked.type === 'web.search' || asked.type === 'web.fetch') &&
    typeof body?.['refusal'] === 'string' &&
    body['refusal'].trim() !== ''
  )
    return { kind: 'refused', outcome };
  if (
    isRetrievalOperation(asked.type) &&
    (outcome.code !== 'OK' ||
      retrievalReply(asked.type, outcome.payload) === undefined)
  )
    return { kind: 'invalid-response', outcome };
  if (
    asked.type === 'orchestration.record' &&
    (outcome.code !== 'OK' || recordReply(outcome.payload) === undefined)
  )
    return { kind: 'invalid-response', outcome };
  if (
    isInspectionOperation(asked.type) &&
    inspectionReply(asked.type, outcome) === undefined
  )
    return { kind: 'invalid-response', outcome };
  if (
    asked.type === 'job.limits' &&
    jobStatusOf(outcome, asked.payload.job) === undefined
  )
    return { kind: 'invalid-response', outcome };
  if (
    isConversationOperation(asked.type) &&
    conversationReply(asked.type, outcome) === undefined
  )
    return { kind: 'invalid-response', outcome };
  if (
    (asked.type === 'application.files' ||
      asked.type === 'application.file.read' ||
      asked.type === 'application.file.save') &&
    (body?.['project'] !== asked.payload['project'] ||
      body?.['path'] !== (asked.payload['path'] ?? ''))
  )
    return { kind: 'invalid-response', outcome };
  if (
    asked.type === 'conversation.lifecycle' &&
    body?.['id'] !== asked.payload.conversation
  )
    return { kind: 'invalid-response', outcome };
  if (
    isAdministrativeOperation(asked.type) &&
    administrativeReply(asked.type, outcome) === undefined
  )
    return { kind: 'invalid-response', outcome };
  if (
    asked.type === 'orchestration.status' &&
    fields(body?.['orchestration'])?.['id'] !== asked.payload.id
  )
    return { kind: 'invalid-response', outcome };
  if (
    (asked.type === 'orchestration.answer' ||
      asked.type === 'orchestration.cancel' ||
      asked.type === 'orchestration.resume' ||
      asked.type === 'approval.revoke') &&
    body?.['id'] !== asked.payload.id
  )
    return { kind: 'invalid-response', outcome };
  if (asked.type === 'approval.answer') {
    const decision = answeredOf(outcome, asked.payload.id);
    if (decision === undefined) return { kind: 'invalid-response', outcome };
    if (decision.job !== undefined)
      return { kind: 'accepted', outcome, job: decision.job };
    // The approval decision stands even when the conversation was busy or
    // its conductor continued it without returning a job handle.
    return { kind: 'completed', outcome };
  }
  if (asked.type === 'orchestration.resume') {
    return { kind: 'completed', outcome, orchestration: asked.payload.id };
  }
  if (asked.type === 'memory.navigate') {
    if (typeof body?.['complete'] !== 'boolean')
      return { kind: 'invalid-response', outcome };
    return { kind: body['complete'] ? 'completed' : 'incomplete', outcome };
  }
  if (asked.type === 'job.status' || asked.type === 'job.cancel') {
    const status = jobStatusOf(outcome, asked.payload.job);
    if (status === undefined) return { kind: 'invalid-response', outcome };
    if (status.outcome != null)
      return { kind: 'completed', outcome, job: asked.payload.job };
    if (status.state !== 'RUNNING')
      return { kind: 'invalid-response', outcome };
    return {
      kind: asked.type === 'job.cancel' ? 'cancelling' : 'running',
      outcome,
      job: asked.payload.job,
    };
  }
  return { kind: 'completed', outcome };
}

/** Explicit waiting reads durable status only. The platform owns pacing and interruption. */
export async function waitForJob(
  transport: Transport,
  job: string,
  pause: () => Promise<void>,
): Promise<Result> {
  for (;;) {
    const result = await dispatch(transport, request('job.status', { job }));
    if (result.kind !== 'running') return result;
    await pause();
  }
}

/** Wait reads status only. Asking returns its question; unknown states never imply completion. */
export async function waitForOrchestration(
  transport: Transport,
  id: string,
  pause: () => Promise<void>,
  initial?: Result,
): Promise<Result> {
  let first = initial;
  for (;;) {
    const result =
      first ??
      (await dispatch(transport, request('orchestration.status', { id })));
    first = undefined;
    if (result.kind !== 'completed') return { ...result, orchestration: id };
    const state = fields(fields(result.outcome.payload)?.['orchestration'])?.[
      'state'
    ];
    switch (state) {
      case 'running':
      case 'waiting':
        await pause();
        break;
      case 'finished':
        return { ...result, orchestration: id };
      case 'asking':
      case 'cancelled':
      case 'capped':
        return { ...result, kind: 'incomplete', orchestration: id };
      case 'failed':
        return { ...result, kind: 'refused', orchestration: id };
      default:
        return { ...result, kind: 'invalid-response', orchestration: id };
    }
  }
}

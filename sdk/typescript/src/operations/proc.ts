import type { OperationTransport } from './response.ts';
import { decodePayload, decodeReply } from './schema.ts';

/** All retained non-archived origins. An absent project selects all authorized projects. */
export interface ProcQuery {
  readonly visibility?: 'mine' | 'server';
  readonly project?: string;
  readonly conversation?: string;
  readonly root?: string;
  readonly causation?: string;
  readonly active?: boolean;
  readonly after?: string;
  readonly limit?: number;
}
export interface ProcConversation {
  readonly id: string;
  readonly root: string;
  readonly parent: string | null;
  readonly origin: string;
  readonly lifecycle: string;
  readonly project: string | null;
  readonly owner: string | null;
  readonly application: string | null;
  readonly actor: string | null;
  readonly actingSubject: string | null;
  readonly agent: string | null;
  readonly principal: string;
  readonly cause: string | null;
  readonly resource: string | null;
  readonly revision: string | null;
  readonly causationRoot: string | null;
  readonly causationParent: string | null;
  readonly causationDepth: number | null;
  readonly createdAt: string;
  readonly lastTurnAt: string | null;
  readonly state: string;
  readonly evidence: string;
  readonly latestJob: string | null;
  readonly ending: string | null;
  readonly children: number;
  readonly calls: number;
  readonly attempts: number;
  readonly activeCalls: number;
  readonly repeatedCalls: number;
}
export interface ProcSelection {
  readonly visibility: 'mine' | 'server';
  readonly project: string | null;
  readonly conversation: string | null;
  readonly root: string | null;
  readonly causation: string | null;
  readonly active: boolean | null;
  readonly after: string | null;
  readonly limit: number;
}
export interface ProcConversations {
  readonly snapshotAt: string;
  readonly selection: ProcSelection;
  readonly conversations: readonly ProcConversation[];
  readonly total: number;
  readonly cursor: string | null;
  readonly coverage: readonly string[];
}
export interface ProcQueue {
  readonly id: string;
  readonly unit: string;
  readonly stage: string;
  readonly project: string | null;
  readonly owner: string | null;
  readonly conversation: string | null;
  readonly revision: string | null;
  readonly resource: string | null;
  readonly total: number;
  readonly completed: number;
  readonly pending: number;
  readonly running: number | null;
  readonly blocked: number;
  readonly failed: number;
  readonly skipped: number;
  readonly oldestPendingAt: string | null;
  readonly leaseUntil: string | null;
  readonly coverage: string;
}
export interface ProcQueues {
  readonly snapshotAt: string;
  readonly selection: ProcSelection;
  readonly queues: readonly ProcQueue[];
  readonly summaries: readonly ProcQueue[];
  readonly pools: readonly {
    readonly name: string;
    readonly lane: string;
    readonly queued: number;
    readonly running: number;
    readonly capacity: number;
  }[];
  readonly cursor: string | null;
  readonly coverage: readonly string[];
}
export interface ProcAttempt {
  readonly id: string;
  readonly number: number;
  readonly state: string;
  readonly startedAt: string;
  readonly endedAt: string | null;
  readonly httpStatus: number | null;
  readonly finishReason: string | null;
  readonly inputTokens: string | null;
  readonly outputTokens: string | null;
  readonly costKind: string;
  readonly costAmount: string | null;
  readonly currency: string | null;
}
export interface ProcCall {
  readonly id: string;
  readonly conversation: string | null;
  readonly root: string | null;
  readonly run: string | null;
  readonly owner: string | null;
  readonly principal: string | null;
  readonly project: string | null;
  readonly agent: string | null;
  readonly operation: string;
  readonly pool: string;
  readonly model: string;
  readonly lane: string;
  readonly route: string;
  readonly runtime: string;
  readonly state: string;
  readonly createdAt: string;
  readonly endedAt: string | null;
  readonly attempts: number;
  readonly observations: readonly ProcAttempt[];
}
export interface ProcCalls {
  readonly snapshotAt: string;
  readonly selection: ProcSelection;
  readonly calls: readonly ProcCall[];
  readonly cursor: string | null;
  readonly coverage: readonly string[];
}
export interface ProcTool {
  readonly id: string;
  readonly conversation: string;
  readonly job: string | null;
  readonly name: string;
  readonly state: string;
  readonly recordedAt: string | null;
}
export interface ProcInspection {
  readonly conversations: ProcConversations;
  readonly queues: ProcQueues;
  readonly calls: ProcCalls;
  readonly tools: readonly ProcTool[];
  readonly toolsTruncated: boolean;
  readonly signals: readonly string[];
}
export interface ProcTools {
  readonly snapshotAt: string;
  readonly selection: ProcSelection;
  readonly tools: readonly ProcTool[];
  readonly cursor: string | null;
  readonly coverage: readonly string[];
}
export interface ProcPayloads {
  'proc.list': ProcQuery;
  'proc.inspect': ProcQuery;
  'proc.calls': ProcQuery;
  'proc.queues': ProcQuery;
  'proc.tools': ProcQuery;
}
export interface ProcReplies {
  'proc.list': ProcConversations;
  'proc.inspect': ProcInspection;
  'proc.calls': ProcCalls;
  'proc.queues': ProcQueues;
  'proc.tools': ProcTools;
}
export type ProcOperation = keyof ProcPayloads;
export type ProcRequest = {
  [K in ProcOperation]: { readonly type: K; readonly payload: ProcPayloads[K] };
}[ProcOperation];

/** Typed, validated reads on the caller's existing authenticated socket. */
export class ProcClient {
  private readonly transport: OperationTransport;
  constructor(transport: OperationTransport) {
    this.transport = transport;
  }
  async read<K extends ProcOperation>(
    type: K,
    query: ProcQuery = {},
  ): Promise<ProcReplies[K]> {
    const response = await this.transport.ask(type, decodePayload(type, query));
    if (response.code !== 'OK')
      throw new Error(response.said ?? 'PROC audit unavailable');
    return decodeReply(type, response.payload);
  }
}

/** Bounded client-domain validation supplements generated field/type checks. */
export function procProblem(
  type: string,
  value: Readonly<Record<string, unknown>>,
): string | undefined {
  if (!type.startsWith('proc.')) return undefined;
  if (value['active'] !== undefined && typeof value['active'] !== 'boolean')
    return 'PROC active must be boolean';
  if (
    value['visibility'] !== undefined &&
    value['visibility'] !== 'mine' &&
    value['visibility'] !== 'server'
  )
    return 'Invalid PROC visibility';
  for (const key of ['project', 'conversation', 'root', 'causation', 'after']) {
    const field = value[key];
    if (
      field !== undefined &&
      (typeof field !== 'string' ||
        !field.trim() ||
        field !== field.trim() ||
        field.length > 256 ||
        /\p{Cc}/u.test(field))
    )
      return 'Invalid PROC selector';
  }
  const limit = value['limit'];
  if (
    limit !== undefined &&
    (typeof limit !== 'number' ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 100)
  )
    return 'PROC limit must be 1–100';
  if (
    type === 'proc.inspect' &&
    !value['conversation'] &&
    !value['root'] &&
    !value['causation']
  )
    return 'PROC inspect needs a conversation, root or causation selector';
  return undefined;
}

/** Called after generated DTO decoding: measurements must still satisfy their domain bounds. */
export function validateProcReply(type: string, value: unknown): void {
  if (!type.startsWith('proc.')) return;
  // The generated operation decoder has validated and copied every nested field before this call.
  const reply = value as ProcReplies[ProcOperation];
  const count = (n: number) => {
    if (!Number.isSafeInteger(n) || n < 0)
      throw new Error('Invalid PROC counter');
  };
  const time = (s: string | null) => {
    if (
      s !== null &&
      (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(s) ||
        !Number.isFinite(Date.parse(s)))
    )
      throw new Error('Invalid PROC timestamp');
  };
  const queue = (q: ProcQueue) => {
    for (const n of [
      q.total,
      q.completed,
      q.pending,
      q.blocked,
      q.failed,
      q.skipped,
    ])
      count(n);
    if (q.running !== null) count(q.running);
    if (
      q.completed > q.total ||
      q.pending > q.total ||
      (q.running !== null && q.running > q.total)
    )
      throw new Error('Invalid PROC progress');
    time(q.oldestPendingAt);
    time(q.leaseUntil);
  };
  if ('signals' in reply) {
    validateProcReply('proc.list', reply.conversations);
    validateProcReply('proc.queues', reply.queues);
    validateProcReply('proc.calls', reply.calls);
    if (reply.tools.length > 100 || reply.signals.length > 200)
      throw new Error('Unbounded PROC inspection');
    for (const tool of reply.tools) time(tool.recordedAt);
    return;
  }
  time(reply.snapshotAt);
  const page =
    'conversations' in reply
      ? reply.conversations
      : 'queues' in reply
        ? reply.queues
        : 'calls' in reply
          ? reply.calls
          : reply.tools;
  if (page.length > 100) throw new Error('Unbounded PROC page');
  if ('conversations' in reply) {
    count(reply.total);
    for (const c of reply.conversations) {
      for (const n of [
        c.children,
        c.calls,
        c.attempts,
        c.activeCalls,
        c.repeatedCalls,
      ])
        count(n);
      if (c.causationDepth !== null) count(c.causationDepth);
      time(c.createdAt);
      time(c.lastTurnAt);
    }
  } else if ('queues' in reply) {
    if (reply.summaries.length > 100 || reply.pools.length > 200)
      throw new Error('Unbounded PROC queue summary');
    reply.queues.forEach(queue);
    reply.summaries.forEach(queue);
    for (const p of reply.pools) {
      count(p.queued);
      count(p.running);
      count(p.capacity);
    }
  } else if ('tools' in reply) {
    for (const tool of reply.tools) time(tool.recordedAt);
  } else
    for (const c of reply.calls) {
      count(c.attempts);
      time(c.createdAt);
      time(c.endedAt);
      if (c.observations.length > 100 || c.observations.length > c.attempts)
        throw new Error('Invalid PROC attempts');
      let previous = 0;
      for (const a of c.observations) {
        count(a.number);
        if (a.number <= previous) throw new Error('Invalid PROC attempt order');
        previous = a.number;
        time(a.startedAt);
        time(a.endedAt);
        for (const tokens of [a.inputTokens, a.outputTokens])
          if (tokens !== null && !/^(?:0|[1-9][0-9]*)$/.test(tokens))
            throw new Error('Invalid PROC token count');
        if (
          a.httpStatus !== null &&
          (!Number.isInteger(a.httpStatus) ||
            a.httpStatus < 100 ||
            a.httpStatus > 599)
        )
          throw new Error('Invalid PROC status');
      }
    }
}

/** Content-free terminal presentation; state and evidence are displayed together. */
export function procText(value: ProcReplies[ProcOperation]): string {
  if ('signals' in value)
    return [
      procText(value.conversations),
      'QUEUES',
      procText(value.queues),
      'CALLS',
      procText(value.calls),
      'TOOLS',
      ...value.tools.map((t) => `${t.name} · ${t.state} · ${t.conversation}`),
      ...(value.toolsTruncated ? ['Tool page truncated.'] : []),
      'REPETITION SIGNALS (not proof of a loop)',
      ...value.signals,
    ].join('\n');
  const lines = [`PROC · snapshot ${value.snapshotAt}`];
  if ('conversations' in value) {
    lines.push(`${value.total} authorized non-archived conversations`);
    for (const c of value.conversations)
      lines.push(
        `${c.id} · ${c.agent ?? c.origin} · ${c.state}${c.ending ? ` / ${c.ending}` : ''}`,
        `  ${c.project ?? 'No project'} · admission owner ${c.owner ?? 'Unknown'} · principal ${c.principal}`,
        ...(c.application
          ? [
              `  Application ${c.application} · authenticating actor ${c.actor ?? 'Unknown'} · acting subject ${c.actingSubject ?? 'Unknown'}`,
            ]
          : []),
        `  root ${c.root} · parent ${c.parent ?? 'None'} · cause ${c.cause ?? 'Unknown'}`,
        ...(c.resource
          ? [`  Source ${c.resource} · revision ${c.revision ?? 'Unknown'}`]
          : []),
        `  ${c.calls} calls / ${c.attempts} attempts · ${c.children} children · ${c.evidence}`,
      );
  } else if ('queues' in value) {
    lines.push('QUEUE TOTALS · entire authorized selection');
    for (const q of value.summaries)
      lines.push(
        `${q.stage}: ${q.completed}/${q.total} ${q.unit} completed · ${q.pending} pending · ${q.running ?? 'Unknown'} running · ${q.blocked} blocked · ${q.failed} failed/cancelled`,
      );
    for (const p of value.pools)
      lines.push(
        `${p.name} / ${p.lane}: ${p.queued} queued · ${p.running}/${p.capacity} running (local executor estimate)`,
      );
    lines.push('ITERATION / RESOURCE DETAIL');
    for (const q of value.queues)
      lines.push(
        `${q.resource ?? q.revision ?? q.id} · ${q.stage}: ${q.completed}/${q.total} ${q.unit} completed · ${q.pending} pending · ${q.running ?? 'Unknown'} running · ${q.blocked} blocked · ${q.failed} failed/cancelled`,
        `  ${q.coverage}`,
      );
  } else if ('tools' in value) {
    for (const t of value.tools)
      lines.push(`${t.id} · ${t.name} · ${t.state} · ${t.conversation}`);
  } else {
    for (const c of value.calls) {
      lines.push(
        `${c.id} · ${c.agent ?? 'No agent'} / ${c.operation} · ${c.state}`,
        `  ${c.principal ?? 'Unknown'} → ${c.pool} / ${c.model} (${c.lane}) · ${c.attempts} attempts`,
        `  Admission owner ${c.owner ?? 'Unknown'} · conversation ${c.conversation ?? 'Unbound'} · root ${c.root ?? 'Unknown'}`,
        `  Run ${c.run ?? 'Unknown'} · runtime ${c.runtime} · ${c.createdAt} → ${c.endedAt ?? 'No terminal receipt'}`,
      );
      for (const a of c.observations)
        lines.push(
          `  #${a.number} ${a.state} · input ${a.inputTokens ?? 'Unknown'} / output ${a.outputTokens ?? 'Unknown'} · ${a.costKind}`,
        );
      if (c.attempts > c.observations.length)
        lines.push('  Attempt observations truncated.');
    }
  }
  if (value.cursor) lines.push(`Next page: after=${value.cursor}`);
  lines.push(...value.coverage);
  return lines.join('\n');
}

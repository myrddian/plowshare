import { USAGE_FRAMES, usageReply } from './usage.ts'
import { INFORMATION_FRAMES, informationReply, isInformationFrame } from './information-replies.ts'
import { acceptedJobOf, jobStatusOf } from '../binding/job-view.ts'
import { recordReply } from './records.ts'
import { ADMINISTRATIVE_OPERATIONS, administrativeReply, isAdministrativeOperation } from './administrative-replies.ts'
import { CONVERSATION_OPERATIONS, conversationReply, isConversationOperation } from './conversation-replies.ts'
import { INSPECTION_OPERATIONS, inspectionReply, isInspectionOperation } from './inspection-replies.ts'
import { answeredOf } from './views.ts'
import { RETRIEVAL_OPERATIONS, isRetrievalOperation, retrievalReply } from './retrieval.ts'
import type { BoundPayloads } from './administration.ts'
import type { ExtendedPayloads } from './catalog.ts'
import type { Outcome } from '../binding/envelope.ts'

/** Omitted or null means global. Never infer scope from a socket session. */
export interface Tier { readonly project?: string | null }
export interface MemoryProposal {
    readonly summary: string
    readonly scope: string
    readonly body: string
    readonly formedBy: string
    readonly formedWhere: string
}

/** Payload names mirror the WS handlers, including memory/proposal and search q. */
export interface Payloads extends ExtendedPayloads, BoundPayloads {
    'memory.index': Tier
    'memory.read': { readonly memory: string }
    'memory.recall': Tier & { readonly question: string; readonly limit?: number }
    'memory.write': Tier & { readonly proposal: MemoryProposal }
    'memory.navigate': Tier & { readonly question: string }
    'memory.digest': Tier
    'agent.curate': { readonly project: string; readonly maxModelCalls?: number }
    'proposal.list': Tier
    'proposal.resolve': { readonly proposal: string; readonly accept: boolean; readonly by: string; readonly reason?: string }
    'proposal.reconsider': Tier
    'memory.invalidate': { readonly memory: string; readonly reason: string; readonly by: string }
    'memory.reembed': Tier
    'conversation.search': Tier & { readonly q: string; readonly mode?: 'lexical' | 'semantic' | 'hybrid'; readonly snapshot?: string; readonly offset?: number; readonly limit?: number }
    'job.status': { readonly job: string }
    'job.cancel': { readonly job: string }
}
export type Operation = keyof Payloads
export type Request<T extends Operation = Operation> = {
    [K in T]: { readonly type: K; readonly payload: Payloads[K] }
}[T]

export function request<T extends Operation>(type: T, payload: Payloads[T]): Request<T> {
    // The mapped union retains the pairing when requests enter a command queue.
    return { type, payload } as Request<T>
}

export const MEMORY_OPERATIONS = {
    index: 'memory.index', read: 'memory.read', recall: 'memory.recall', write: 'memory.write',
    navigate: 'memory.navigate', digest: 'memory.digest', curate: 'agent.curate',
    proposals: 'proposal.list', resolve: 'proposal.resolve', reconsider: 'proposal.reconsider',
    invalidate: 'memory.invalidate', reembed: 'memory.reembed',
} as const satisfies Record<string, Operation>

export type Parsed = { readonly kind: 'request'; readonly request: Request }
    | { readonly kind: 'usage'; readonly said: string }
    | { readonly kind: 'unhandled' }

export const MEMORY_USAGE = 'memory index|read|recall|write|navigate|digest|curate|proposals|resolve|reconsider|invalidate|reembed [JSON payload]. read takes an id; recall/navigate take question text. JSON project:null selects global.'

function fields(value: unknown): Record<string, unknown> | undefined {
    return typeof value === 'object' && value !== null && !Array.isArray(value)
        ? value as Record<string, unknown> : undefined
}

/** Input validation protects dispatch, but leaves archive validation and defaults to the server. */
export function problem(type: Operation, payload: Record<string, unknown>): string | undefined {
    if ('project' in payload && payload['project'] !== null
        && (typeof payload['project'] !== 'string' || payload['project'].trim() === '')) {
        return 'project must be a nonblank name or null for global'
    }
    const required: Partial<Record<Operation, readonly string[]>> = {
        'memory.read': ['memory'], 'memory.recall': ['question'], 'memory.navigate': ['question'],
        'agent.curate': ['project'], 'proposal.resolve': ['proposal', 'by'],
        'memory.invalidate': ['memory', 'reason', 'by'], 'conversation.search': ['q'],
        'job.status': ['job'], 'job.cancel': ['job'],
    }
    for (const key of required[type] ?? []) {
        if (typeof payload[key] !== 'string' || payload[key].trim() === '') return `${type} needs ${key}`
    }
    if (type === 'proposal.resolve' && typeof payload['accept'] !== 'boolean') return 'proposal.resolve needs accept:true or accept:false'
    if (type === 'proposal.resolve' && 'reason' in payload && typeof payload['reason'] !== 'string') return 'reason must be text'
    if (type === 'memory.write') {
        if ('verdict' in payload) return 'memory.write judgement belongs to the server; do not supply verdict'
        const proposal = fields(payload['proposal'])
        if (proposal === undefined) return 'memory.write needs a proposal object'
        for (const key of ['summary', 'scope', 'body', 'formedBy', 'formedWhere']) {
            if (typeof proposal[key] !== 'string') return `proposal needs ${key} as text`
        }
    }
    for (const key of ['limit', 'offset', 'maxModelCalls']) {
        const value = payload[key]
        if (key in payload && (typeof value !== 'number' || !Number.isSafeInteger(value)
            || value < (key === 'offset' ? 0 : 1))) return `${key} must be ${key === 'offset' ? 'a nonnegative' : 'a positive'} integer`
    }
    return undefined
}

/** Adapter-neutral command grammar. The TUI strips its leading slash at the edge. */
export function parseDirect(line: string, project?: string): Parsed {
    const match = /^(memory|search|job)(?:\s+(.*))?$/s.exec(line.trim())
    if (match === null) return { kind: 'unhandled' }
    const family = match[1], rest = (match[2] ?? '').trim()
    let type: Operation
    let argument: string
    if (family === 'search') {
        type = 'conversation.search'; argument = rest
    } else {
        const parts = /^(\S+)(?:\s+(.*))?$/s.exec(rest)
        const verb = parts?.[1] ?? (family === 'memory' ? 'index' : '')
        argument = (parts?.[2] ?? '').trim()
        if (family === 'job') {
            if (verb !== 'status' && verb !== 'cancel') return { kind: 'usage', said: 'job status|cancel <job-id>' }
            type = verb === 'status' ? 'job.status' : 'job.cancel'
        } else {
            if (!Object.hasOwn(MEMORY_OPERATIONS, verb)) return { kind: 'usage', said: MEMORY_USAGE }
            type = MEMORY_OPERATIONS[verb as keyof typeof MEMORY_OPERATIONS]
        }
    }
    let payload: Record<string, unknown>
    if (argument.startsWith('{')) {
        try {
            const parsed = fields(JSON.parse(argument))
            if (parsed === undefined) return { kind: 'usage', said: 'payload must be a JSON object' }
            payload = parsed
        } catch { return { kind: 'usage', said: 'payload must be a valid JSON object' } }
    } else if (type === 'memory.read') payload = { memory: argument }
    else if (type === 'memory.recall' || type === 'memory.navigate') payload = { question: argument }
    else if (type === 'conversation.search') payload = { q: argument }
    else if (type === 'job.status' || type === 'job.cancel') payload = { job: argument }
    else if (argument === '') payload = {}
    else return { kind: 'usage', said: `${type} takes a JSON payload; ${MEMORY_USAGE}` }
    const tiered: readonly Operation[] = ['memory.index', 'memory.recall', 'memory.write', 'memory.navigate',
        'memory.digest', 'agent.curate', 'proposal.list', 'proposal.reconsider', 'memory.reembed', 'conversation.search']
    if (tiered.includes(type) && !('project' in payload) && project !== undefined) payload = { ...payload, project }
    const why = problem(type, payload)
    return why === undefined ? { kind: 'request', request: { type, payload } as Request }
        : { kind: 'usage', said: why }
}

export const JOB_SUBMISSIONS: readonly Operation[] = ['memory.digest', 'agent.curate', 'agent.run', 'conversation.resume', 'document.ask']

export const VALIDATED_OPERATIONS: readonly Operation[] = [...USAGE_FRAMES.filter(type=>type!=='usage.subscribe'&&type!=='usage.unsubscribe'), ...INFORMATION_FRAMES, ...RETRIEVAL_OPERATIONS, ...ADMINISTRATIVE_OPERATIONS, ...CONVERSATION_OPERATIONS, ...INSPECTION_OPERATIONS, ...JOB_SUBMISSIONS, 'job.status', 'job.cancel', 'orchestration.record', 'orchestration.start', 'orchestration.receipt']

export const WAIT_OPERATIONS: readonly Operation[] = [...JOB_SUBMISSIONS, 'information.ask', 'job.status', 'job.cancel', 'approval.answer', 'orchestration.start', 'orchestration.status', 'orchestration.receipt']

export interface Transport { ask(type: string, payload: unknown): Promise<Outcome> }
export type Result = {
    readonly kind: 'completed' | 'incomplete' | 'accepted' | 'running' | 'cancelling' | 'refused' | 'invalid-response'
    readonly outcome: Outcome
    readonly job?: string
    readonly orchestration?: string
    readonly conversation?: string
}

/** One WS request only. No retry, mutation replay, HTTP fallback, agent run, or hidden wait. */
export async function dispatch(transport: Transport, asked: Request): Promise<Result> {
    return resultOf(asked, await transport.ask(asked.type, asked.payload))
}

/** The same checked boundary for frontends that already own request lifetime and WS I/O. */
export function resultOf(asked: Request, outcome: Outcome): Result {
    const body = fields(outcome.payload)
    if ((USAGE_FRAMES as readonly string[]).includes(asked.type)) {
        if (outcome.code !== 'OK') return {kind:'refused',outcome}
        return usageReply(asked.type,outcome.payload) ? {kind:'completed',outcome} : {kind:'invalid-response',outcome}
    }
    if (asked.type === 'orchestration.start' || asked.type === 'orchestration.receipt') {
        const expected = asked.type === 'orchestration.start' ? 'ACCEPTED' : 'OK'
        if (outcome.code !== expected) return { kind: ['OK', 'ACCEPTED'].includes(outcome.code) ? 'invalid-response' : 'refused', outcome }
        if (typeof body?.['id'] !== 'string' || !body['id'].startsWith('orc_')
            || body['requestId'] !== asked.payload.requestId || !['running', 'waiting', 'asking', 'finished', 'cancelled', 'failed', 'capped'].includes(String(body['state']))) return { kind: 'invalid-response', outcome }
        return { kind: asked.type === 'orchestration.start' ? 'accepted' : 'completed', outcome, orchestration: body['id'] }
    }
    if (isInformationFrame(asked.type)) {
        if (outcome.code !== 'OK' && outcome.code !== 'ACCEPTED') return { kind: 'refused', outcome }
        if (informationReply(asked.type, outcome) === undefined) return { kind: 'invalid-response', outcome }
        const payload = fields(asked.payload)!
        const identity = asked.type === 'information.status' ? payload['acquisition'] ?? payload['revision'] : asked.type === 'information.evidence.read' ? payload['evidence'] : payload['revision']
        const returned = ['information.read','information.ask'].includes(asked.type) ? body?.['revision'] : body?.['id']
        if (['information.read','information.ask','information.status','information.evidence.read'].includes(asked.type) && typeof identity === 'string' && returned !== identity) return { kind: 'invalid-response', outcome }
        return outcome.code === 'ACCEPTED' ? { kind: 'accepted', outcome, ...(asked.type === 'information.ask' ? { job: body!['job'] as string } : {}) } : { kind: 'completed', outcome }
    }
    if (outcome.code === 'ACCEPTED') {
        if (!JOB_SUBMISSIONS.includes(asked.type)) return { kind: 'invalid-response', outcome }
        const job = acceptedJobOf(outcome)?.id
        const conversation = body?.['conversation']
        if (asked.type === 'agent.run' && (asked.payload.newConversation === true
            && (typeof conversation !== 'string' || !conversation.startsWith('cnv_'))
            || conversation != null && (typeof conversation !== 'string' || asked.payload.conversation != null && conversation !== asked.payload.conversation))) return { kind: 'invalid-response', outcome, ...(job ? { job } : {}) }
        return job !== undefined
            ? { kind: 'accepted', outcome, job, ...(typeof conversation === 'string' ? { conversation } : {}) } : { kind: 'invalid-response', outcome }
    }
    if (outcome.code !== 'OK' && outcome.code !== 'CREATED' && outcome.code !== 'NO_CONTENT') return { kind: 'refused', outcome }
    if (JOB_SUBMISSIONS.includes(asked.type)) return { kind: 'invalid-response', outcome }
    if ((asked.type === 'web.search' || asked.type === 'web.fetch')
        && typeof body?.['refusal'] === 'string' && body['refusal'].trim() !== '') return { kind: 'refused', outcome }
    if (isRetrievalOperation(asked.type)
        && (outcome.code !== 'OK' || retrievalReply(asked.type, outcome.payload) === undefined)) return { kind: 'invalid-response', outcome }
    if (asked.type === 'orchestration.record' && (outcome.code !== 'OK' || recordReply(outcome.payload) === undefined)) return { kind: 'invalid-response', outcome }
    if (isInspectionOperation(asked.type) && inspectionReply(asked.type, outcome) === undefined) return { kind: 'invalid-response', outcome }
    if (asked.type === 'job.limits' && jobStatusOf(outcome, asked.payload.job) === undefined) return { kind: 'invalid-response', outcome }
    if (isConversationOperation(asked.type) && conversationReply(asked.type, outcome) === undefined) return { kind: 'invalid-response', outcome }
    if (asked.type === 'conversation.lifecycle' && body?.['id'] !== asked.payload.conversation) return { kind: 'invalid-response', outcome }
    if (isAdministrativeOperation(asked.type) && administrativeReply(asked.type, outcome) === undefined) return { kind: 'invalid-response', outcome }
    if (asked.type === 'orchestration.status' && fields(body?.['orchestration'])?.['id'] !== asked.payload.id) return { kind: 'invalid-response', outcome }
    if ((asked.type === 'orchestration.answer' || asked.type === 'orchestration.cancel' || asked.type === 'approval.revoke')
        && body?.['id'] !== asked.payload.id) return { kind: 'invalid-response', outcome }
    if (asked.type === 'approval.answer') {
        const decision = answeredOf(outcome, asked.payload.id)
        if (decision === undefined) return { kind: 'invalid-response', outcome }
        if (decision.job !== undefined) return { kind: 'accepted', outcome, job: decision.job }
        // The approval decision stands even when the conversation was busy or
        // its conductor continued it without returning a job handle.
        return { kind: 'completed', outcome }
    }
    if (asked.type === 'memory.navigate') {
        if (typeof body?.['complete'] !== 'boolean') return { kind: 'invalid-response', outcome }
        return { kind: body['complete'] ? 'completed' : 'incomplete', outcome }
    }
    if (asked.type === 'job.status' || asked.type === 'job.cancel') {
        const status = jobStatusOf(outcome, asked.payload.job)
        if (status === undefined) return { kind: 'invalid-response', outcome }
        if (status.outcome != null) return { kind: 'completed', outcome, job: asked.payload.job }
        if (status.state !== 'RUNNING') return { kind: 'invalid-response', outcome }
        return { kind: asked.type === 'job.cancel' ? 'cancelling' : 'running', outcome, job: asked.payload.job }
    }
    return { kind: 'completed', outcome }
}

/** Explicit waiting reads durable status only. The platform owns pacing and interruption. */
export async function waitForJob(transport: Transport, job: string, pause: () => Promise<void>): Promise<Result> {
    for (;;) {
        const result = await dispatch(transport, request('job.status', { job }))
        if (result.kind !== 'running') return result
        await pause()
    }
}

/** Wait reads status only. Asking returns its question; unknown states never imply completion. */
export async function waitForOrchestration(transport: Transport, id: string, pause: () => Promise<void>, initial?: Result): Promise<Result> {
    let first = initial
    for (;;) {
        const result = first ?? await dispatch(transport, request('orchestration.status', { id }))
        first = undefined
        if (result.kind !== 'completed') return { ...result, orchestration: id }
        const state = fields(fields(result.outcome.payload)?.['orchestration'])?.['state']
        switch (state) {
            case 'running': case 'waiting': await pause(); break
            case 'finished': return { ...result, orchestration: id }
            case 'asking': case 'cancelled': case 'capped': return { ...result, kind: 'incomplete', orchestration: id }
            case 'failed': return { ...result, kind: 'refused', orchestration: id }
            default: return { ...result, kind: 'invalid-response', orchestration: id }
        }
    }
}

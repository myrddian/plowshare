import type { JsonValue } from './administration.ts'

/** Durable receipt; remote outputs are opaque and unknown is distinct from completion. */
export interface OutgoingWork {
    readonly id: string
    readonly requestId: string
    readonly peer: string
    readonly project: string | null
    readonly conversation: string | null
    readonly message: Readonly<Record<string, JsonValue>>
    readonly state: 'QUEUED' | 'DISPATCHED' | 'WORKING' | 'INPUT_REQUIRED' | 'AUTH_REQUIRED' | 'COMPLETED' | 'FAILED' | 'CANCELED' | 'REJECTED' | 'UNKNOWN'
    readonly cancelRequested: boolean
    readonly remoteTask: string | null
    readonly remoteContext: string | null
    readonly result: Readonly<Record<string, JsonValue>> | null
    readonly error: string | null
    readonly revision: number
    readonly createdAt: string
}


import type { Outcome } from '../binding/envelope.ts'
import { object, record, named, nullable, bool, count, json, list } from './wire-checks.ts'
export const OUTGOING_OPERATIONS = ['outgoing.send', 'outgoing.status', 'outgoing.cancel', 'outgoing.peers'] as const
export type OutgoingOperation = typeof OUTGOING_OPERATIONS[number]
export const isOutgoingOperation = (type: string): type is OutgoingOperation => (OUTGOING_OPERATIONS as readonly string[]).includes(type)
const uuid = (value: unknown): boolean => typeof value === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value)
const work = record({ id: uuid, requestId: uuid, peer: named, project: nullable(named), conversation: nullable(named),
    message: value => object(value) && json(value), state: value => ['QUEUED','DISPATCHED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED','COMPLETED','FAILED','CANCELED','REJECTED','UNKNOWN'].includes(String(value)),
    cancelRequested: bool, remoteTask: nullable(named), remoteContext: nullable(named), result: value => value === null || object(value) && json(value),
    error: nullable(named), revision: count, createdAt: value => typeof value === 'string' && Number.isFinite(Date.parse(value)) })
/** Checks retain the entire peer result and every future field. */
export function outgoingReply(type: OutgoingOperation, outcome: Outcome): Outcome | undefined {
    if (outcome.code !== (type === 'outgoing.send' ? 'ACCEPTED' : 'OK')) return undefined
    if (type !== 'outgoing.peers') return work(outcome.payload) ? outcome : undefined
    if (!record({ peers: list(named) })(outcome.payload)) return undefined
    const payload = outcome.payload as { peers: string[]; details?: unknown }
    if (payload.details !== undefined && (!list(record({ peer: named, agentCard: value => value === null || object(value) && json(value) }))(payload.details)
        || (payload.details as { peer: string }[]).some(detail => !payload.peers.includes(detail.peer)))) return undefined
    return outcome
}

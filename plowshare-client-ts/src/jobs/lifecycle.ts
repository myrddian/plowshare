import { jobStatusOf } from '../binding/job-view.ts'
import type { Outcome } from '../binding/envelope.ts'

export type JobState = 'starting' | 'running' | 'cancelling' | 'awaiting-outcome' | 'unknown' | 'finished' | 'refused'
export interface Ticket {
    readonly key: number
    readonly generation: number
    readonly conversation?: string
}
export interface TrackedJob {
    readonly ticket: Ticket
    readonly handle?: string
    readonly state: JobState
    readonly needsReconcile: boolean
    readonly outcome?: Readonly<Record<string, unknown>>
}
interface RecordJob {
    ticket: Ticket
    handle?: string
    state: JobState
    needsReconcile: boolean
    cancelRequested: boolean
    outcome?: Record<string, unknown>
}
export interface Delivery {
    readonly ticket: Ticket
    readonly event: Readonly<Record<string, unknown>>
}
export interface Accepted {
    readonly events: readonly Readonly<Record<string, unknown>>[]
    readonly needsReconcile: boolean
}
const fields = (value: unknown): Record<string, unknown> =>
    typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {}
const active = (state: JobState) => state !== 'finished' && state !== 'refused'

/**
 * Job identity and connection lifetime, independent of any selected view.
 * No I/O, scheduling or mutation replay. Consumers reconcile through WS
 * job.status and trajectory reads; unknown submissions without a handle
 * cannot safely be resubmitted or polled automatically.
 */
export class JobLifecycle {
    private epoch = 0
    private issued = 0
    private readonly records = new Map<number, RecordJob>()
    private readonly handles = new Map<string, RecordJob>()
    private readonly early = new Map<string, Readonly<Record<string, unknown>>[]>()
    private readonly limits: { handles: number; events: number }

    constructor(limits = { handles: 100, events: 500 }) {
        if (!Number.isSafeInteger(limits.handles) || limits.handles < 1
                || !Number.isSafeInteger(limits.events) || limits.events < 1) {
            throw new Error('Early-event limits must be positive integers')
        }
        this.limits = { ...limits }
    }

    get generation(): number { return this.epoch }
    current(generation: number): boolean { return generation === this.epoch }

    /** Invalidate old callbacks. Retaining a view's jobs never cancels them. */
    reset(retain = false): void {
        this.epoch++
        this.early.clear()
        if (!retain) { this.records.clear(); this.handles.clear(); return }
        for (const record of this.records.values()) {
            if (active(record.state)) { record.state = 'unknown'; record.needsReconcile = true }
        }
    }

    begin(conversation?: string): Ticket {
        const ticket: Ticket = { key: ++this.issued, generation: this.epoch,
            ...(conversation === undefined ? {} : { conversation }) }
        this.records.set(ticket.key, { ticket, state: 'starting', needsReconcile: false, cancelRequested: false })
        return ticket
    }

    private record(ticket: Ticket): RecordJob | undefined {
        const record = this.records.get(ticket.key)
        return record?.ticket === ticket ? record : undefined
    }

    snapshot(ticket: Ticket): TrackedJob | undefined {
        const record = this.record(ticket)
        if (!record) return undefined
        return { ticket, state: record.state, needsReconcile: record.needsReconcile,
            ...(record.handle === undefined ? {} : { handle: record.handle }),
            ...(record.outcome === undefined ? {} : { outcome: { ...record.outcome } }) }
    }

    /** A handle comes from ACCEPTED or approval.answer, never a selected chat. */
    accept(ticket: Ticket, handle: string): Accepted {
        const record = this.record(ticket)
        if (!record || !this.current(ticket.generation) || record.state !== 'starting') {
            throw new Error('The submission belongs to an obsolete connection')
        }
        if (!handle.trim() || this.handles.has(handle)) throw new Error('The submission has no unique job handle')
        record.handle = handle; record.state = 'running'; this.handles.set(handle, record)
        const events = this.early.get(handle) ?? []
        this.early.delete(handle)
        this.clearIdleBuffer()
        return { events, needsReconcile: record.needsReconcile }
    }

    /** Route only by the server job id; buffer while submissions await handles. */
    push(value: unknown, generation = this.epoch): Delivery | undefined {
        if (!this.current(generation)) return undefined
        const event = fields(value)
        const handle = event['job']
        if (typeof handle !== 'string' || !handle.trim()) return undefined
        const record = this.handles.get(handle)
        if (record) {
            if (!active(record.state)) return undefined
            if (event['kind'] === 'ended') { record.state = 'awaiting-outcome'; record.needsReconcile = true }
            return { ticket: record.ticket, event }
        }
        const waiting = [...this.records.values()].filter(row => row.state === 'starting')
        if (!waiting.length) return undefined
        if (!this.early.has(handle) && this.early.size >= this.limits.handles) {
            const oldest = this.early.keys().next().value
            if (oldest !== undefined) this.early.delete(oldest)
            for (const pending of waiting) pending.needsReconcile = true
        }
        const held = this.early.get(handle) ?? []
        if (held.length >= this.limits.events) {
            held.shift()
            for (const pending of waiting) pending.needsReconcile = true
        }
        held.push({ ...event }); this.early.set(handle, held)
        return undefined
    }

    /** An acknowledgement of cancellation is not a terminal outcome. */
    cancelling(ticket: Ticket): void {
        const record = this.record(ticket)
        if (record && active(record.state)) {
            record.cancelRequested = true
            if (record.state !== 'unknown' && record.state !== 'awaiting-outcome') record.state = 'cancelling'
        }
    }

    /** Only a successful authoritative status read with an outcome finishes a job. */
    observe(ticket: Ticket, answer: Outcome, generation = this.epoch): TrackedJob | undefined {
        const record = this.record(ticket)
        if (!record || !this.current(generation) || !record.handle || !active(record.state)) return this.snapshot(ticket)
        const payload = jobStatusOf(answer, record.handle)
        if (payload === undefined) {
            record.needsReconcile = true; return this.snapshot(ticket)
        }
        const outcome = payload.outcome
        if (outcome != null) {
            record.state = 'finished'; record.outcome = { ...outcome }; record.needsReconcile = false
        } else if (payload.state === 'RUNNING') {
            record.cancelRequested ||= payload['cancelRequested'] === true
            record.state = record.cancelRequested ? 'cancelling' : 'running'
            record.needsReconcile = false
        } else record.needsReconcile = true
        return this.snapshot(ticket)
    }

    failed(ticket: Ticket, certainRefusal: boolean): void {
        const record = this.record(ticket)
        if (!record || record.handle !== undefined || !active(record.state) || !this.current(ticket.generation)) return
        record.state = certainRefusal ? 'refused' : 'unknown'
        record.needsReconcile = !certainRefusal
        this.clearIdleBuffer()
    }

    /** Detach a local observer; no server-side cancellation is implied. */
    forget(ticket: Ticket): void {
        const record = this.record(ticket)
        if (!record) return
        this.records.delete(ticket.key)
        if (record.handle !== undefined) this.handles.delete(record.handle)
        this.clearIdleBuffer()
    }

    private clearIdleBuffer(): void {
        if (![...this.records.values()].some(record => record.state === 'starting')) this.early.clear()
    }
}

import type { Outcome } from '../binding/envelope.ts'
import type { Request } from './direct.ts'

/** Persistent commands; their socket lifetime belongs to the frontend. */
export const OBSERVER_OPERATIONS = {
    'job watch': 'job.stream',
    'conversation follow': 'conversation.follow',
} as const

export function followCommand(line: string): (Request<'conversation.follow'> & { payload: { readonly conversation: string } }) | undefined {
    const match = /^conversation\s+follow(?:\s+(.*))?$/s.exec(line.trim())
    if (match === null) return undefined
    const conversation = (match[1] ?? '').trim()
    if (!conversation || /\s/.test(conversation) || conversation.startsWith('{'))
        throw new Error('conversation follow needs one conversation id')
    return { type: 'conversation.follow', payload: { conversation } }
}

function fields(value: unknown): Record<string, unknown> | undefined {
    return typeof value === 'object' && value !== null && !Array.isArray(value)
        ? value as Record<string, unknown> : undefined
}

/** Match actual identities. Deltas/events are progress, never a durable answer. */
export function observedJob(push: unknown, job: string): boolean {
    const value = fields(push)
    return value?.['job'] === job && (typeof value['kind'] === 'string'
        || (['ANSWER', 'THINKING'].includes(String(value['part'])) && typeof value['text'] === 'string'))
}

/** The server announces a high-water mark, not message content. */
export function observedConversation(push: unknown, conversation: string): boolean {
    const value = fields(push)
    return value?.['kind'] === 'conversation.appended' && value['conversation'] === conversation
        && typeof value['through'] === 'number' && Number.isSafeInteger(value['through']) && value['through'] >= 0
}

export function observationAccepted(outcome: Outcome): boolean { return outcome.code === 'OK' }

/** Bound early progress until an accepted submission identifies its job.
 * Dropping progress is allowed; the durable status supplies the full answer. */
export function jobProgress(emit: (push: unknown) => void, maximum = 64): {
    receive(push: unknown): void
    identify(job: string): void
    close(): void
} {
    let id: string | undefined, open = true
    const early: unknown[] = []
    return {
        receive(push) {
            if (!open) return
            if (id !== undefined) { if (observedJob(push, id)) emit(push); return }
            const value = fields(push)
            if (typeof value?.['job'] !== 'string') return
            if (early.length === maximum) early.shift()
            early.push(push)
        },
        identify(job) {
            if (!open) return
            id = job
            for (const push of early) if (observedJob(push, job)) emit(push)
            early.length = 0
        },
        close() { open = false; early.length = 0 },
    }
}

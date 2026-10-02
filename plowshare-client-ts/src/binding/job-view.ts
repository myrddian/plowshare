import type { Outcome } from './envelope.ts'

/** Wire DTOs mirror server/api/JobView.java and agents/Pace.java. */
export interface JobPace {
    readonly toolCalls: number
    readonly completionTokens: number | null
    readonly reasoningTokens: number | null
    readonly firstTokenMillis: number | null
    readonly tokensPerSecond: number | null
    readonly reasoningEstimated: boolean
}
export interface JobLimits {
    readonly maxTurns: number | null
    readonly noTurnCap: boolean
    readonly maxModelCalls: number | null
    readonly noBudget: boolean
    readonly modelCallsSpent: number
}
export interface JobOutcome {
    readonly ending: string
    readonly answered: boolean
    readonly resumable: boolean
    readonly text: string
    readonly steps: number
    readonly modelCalls: number
    readonly detail: string | null
    readonly pace: JobPace | null
}
export interface JobView {
    readonly id: string
    readonly agent: string
    readonly state: string
    readonly cancelRequested: boolean
    readonly conversation: string | null
    readonly outcome: JobOutcome | null
    readonly limits: JobLimits | null
}
/** Older/minimal replies may omit optional metadata, but never identity/state
 * or the outcome's answered flag. Unknown fields survive the checked boundary. */
export type ObservedOutcome = Pick<JobOutcome, 'ending' | 'answered'>
    & Partial<Omit<JobOutcome, 'ending' | 'answered' | 'pace'>>
    & { readonly pace?: Partial<JobPace> | null } & Readonly<Record<string, unknown>>
export type ObservedJob = Pick<JobView, 'id' | 'state'>
    & Partial<Omit<JobView, 'id' | 'state' | 'outcome' | 'limits'>>
    & { readonly outcome?: ObservedOutcome | null; readonly limits?: Partial<JobLimits> | null }
    & Readonly<Record<string, unknown>>

const object = (value: unknown): Record<string, unknown> | undefined =>
    typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : undefined
const named = (value: unknown): value is string => typeof value === 'string' && value.trim() !== ''
const whole = (value: unknown): boolean => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const optional = (row: Record<string, unknown>, keys: readonly string[], accepts: (value: unknown) => boolean, nullable = false): boolean =>
    keys.every(key => !(key in row) || (nullable && row[key] === null) || accepts(row[key]))
const bool = (value: unknown): boolean => typeof value === 'boolean'
const text = (value: unknown): boolean => typeof value === 'string'

export function jobOutcome(value: unknown): ObservedOutcome | undefined {
    const row = object(value)
    if (!row || !named(row['ending']) || !bool(row['answered'])
        || !optional(row, ['text'], text) || !optional(row, ['detail'], text, true)
        || !optional(row, ['resumable'], bool) || !optional(row, ['steps', 'modelCalls'], whole)) return undefined
    if (row['ending'] === 'ANSWERED' && row['answered'] !== true) return undefined
    if (row['pace'] != null) {
        const pace = object(row['pace'])
        if (!pace || !optional(pace, ['toolCalls'], whole)
            || !optional(pace, ['completionTokens', 'reasoningTokens', 'firstTokenMillis'], whole, true)
            || !optional(pace, ['reasoningEstimated'], bool)
            || !optional(pace, ['tokensPerSecond'], value => typeof value === 'number' && Number.isFinite(value) && value >= 0, true)) return undefined
    }
    return { ...row } as ObservedOutcome
}

/** Require the requested identity even for terminal replies. State alone and
 * event pushes cannot establish completion; only a readable outcome can. */
export function jobView(value: unknown, expected?: string): ObservedJob | undefined {
    const row = object(value)
    if (!row || !named(row['id']) || (expected !== undefined && row['id'] !== expected) || !named(row['state'])
        || !optional(row, ['agent'], text) || !optional(row, ['conversation'], text, true)
        || !optional(row, ['cancelRequested'], bool)) return undefined
    const outcome = row['outcome'] == null ? row['outcome'] : jobOutcome(row['outcome'])
    if (row['outcome'] != null && outcome === undefined) return undefined
    if (row['limits'] != null) {
        const limits = object(row['limits'])
        if (!limits || !optional(limits, ['maxTurns', 'maxModelCalls'], whole, true)
            || !optional(limits, ['modelCallsSpent'], whole) || !optional(limits, ['noTurnCap', 'noBudget'], bool)) return undefined
        for (const [flag, ceiling] of [['noTurnCap', 'maxTurns'], ['noBudget', 'maxModelCalls']] as const) {
            if (limits[flag] === true && limits[ceiling] != null) return undefined
        }
    }
    return { ...row, ...(outcome === undefined ? {} : { outcome }) } as ObservedJob
}

export function jobStatusOf(answer: Outcome | { readonly code: string; readonly payload?: unknown }, expected?: string): ObservedJob | undefined {
    return answer.code === 'OK' ? jobView(answer.payload, expected) : undefined
}

export function acceptedJobOf(answer: { readonly code: string; readonly payload?: unknown }): { readonly id: string; readonly agent?: string } | undefined {
    const row = object(answer.payload)
    return answer.code === 'ACCEPTED' && row && named(row['id']) && optional(row, ['agent'], text)
        ? row as { readonly id: string; readonly agent?: string } : undefined
}

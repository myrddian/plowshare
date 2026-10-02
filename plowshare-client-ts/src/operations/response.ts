// Shared, platform-free response field readers.
export const OK = 'OK'

export interface Answer {
    readonly code: string
    readonly said?: string
    readonly payload?: unknown
}

/** Every field of an object-shaped value, or nothing at all. */
export function fieldsOf(value: unknown): Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value)
        ? value as Record<string, unknown>
        : {}
}

/** A string field, or undefined for absent, null, or anything else. */
export function textAt(fields: Record<string, unknown>, name: string): string | undefined {
    const found = fields[name]
    return typeof found === 'string' ? found : undefined
}

/** A number field, or undefined. `NaN` is not a count and does not survive. */
export function countAt(fields: Record<string, unknown>, name: string): number | undefined {
    const found = fields[name]
    return typeof found === 'number' && Number.isFinite(found) ? found : undefined
}


/** The payload of an answer that succeeded with the code asked of it. */
export function bodyOf(answer: Answer, code: string): Record<string, unknown> | undefined {
    return answer.code === code ? fieldsOf(answer.payload) : undefined
}

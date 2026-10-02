/** Internal validation primitives. Checks never transform a server value. */
export type Check = (value: unknown) => boolean
export const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
export const text: Check = value => typeof value === 'string'
export const named: Check = value => text(value) && (value as string).trim() !== ''
export const bool: Check = value => typeof value === 'boolean'
export const integer: Check = value => typeof value === 'number' && Number.isSafeInteger(value)
export const count: Check = value => integer(value) && (value as number) >= 0
export const positive: Check = value => count(value) && (value as number) > 0
export const finite: Check = value => typeof value === 'number' && Number.isFinite(value)
export const nullable = (check: Check): Check => value => value === null || check(value)
export const list = (check: Check): Check => value => Array.isArray(value) && value.every(check)
export const record = (shape: Readonly<Record<string, Check>>, invariant?: (row: Record<string, unknown>) => boolean): Check => value =>
    object(value) && Object.entries(shape).every(([key, check]) => check(value[key])) && (invariant?.(value) ?? true)
export const strings = list(text)
export const json: Check = value => value === null || text(value) || bool(value)
    || (typeof value === 'number' && Number.isFinite(value)) || (Array.isArray(value) && value.every(json))
    || (object(value) && Object.values(value).every(json))
export const noContent: Check = value => value === undefined || value === null

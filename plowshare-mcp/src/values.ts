export type Data = Record<string, unknown>
export function record(value: unknown): Data {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('unreadable server result; this is not an empty finding')
    return value as Data
}
export function array(value: unknown): unknown[] {
    if (!Array.isArray(value)) throw new Error('unreadable server list; this is not an empty finding')
    return value
}
export function text(value: unknown): string {
    if (typeof value !== 'string') throw new Error('unreadable server text; this is not an empty finding')
    return value
}
export function number(value: unknown): number {
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('unreadable server number; this is not a zero finding')
    return value
}
const breaks = /\r\n|[\n\r\v\f\u0085\u2028\u2029]/g
export function line(value: unknown): string { return value == null ? '' : text(value).replace(breaks, ' ').trim() }
export function quote(value: unknown, trim = true): string {
    const body = text(value)
    return (trim ? body.trim() : body).split(breaks).map(line => '> ' + line).join('\n')
}
export function tier(project: string | null): string { return project === null ? 'global' : `project '${line(project)}'` }
export const separator = '\n\n----------------------------------------\n\n'
export function optional(args: Data, name: string, trim = false): string | null {
    const value = args[name]
    if (value == null) return null
    const result = typeof value === 'string' ? value : String(value)
    return (trim ? result.trim() : result).length === 0 ? null : trim ? result.trim() : result
}
export function required(args: Data, name: string, short = false): string {
    const value = optional(args, name, short)
    if (value === null || (!short && value.trim() === '')) throw new Error(`'${name}' is required${short ? '' : ' and must not be empty'}`)
    return value
}
export function integer(args: Data, name: string, style: 'ordinary' | 'document' = 'ordinary'): number | undefined {
    const raw = args[name]
    if (raw == null) return undefined
    if (typeof raw === 'number' && Number.isFinite(raw)) return Math.max(-2147483648, Math.min(2147483647, Math.trunc(raw)))
    const value = String(raw).trim()
    if (/^[+-]?\d+$/.test(value) && Number(value) >= -2147483648 && Number(value) <= 2147483647) return Number(value)
    throw new Error(style === 'document' ? `'${name}' is '${raw}', which is not a number` : `'${name}' should be a whole number, not ${raw}`)
}
export function project(args: Data, area = 'global archive'): string | null {
    const value = optional(args, 'project')
    if (value !== null && value.trim() === '') throw new Error(`'project' was sent empty. Omit it entirely for the ${area} — an empty project is not the global tier.`)
    return value
}
export function paths(args: Data, name: string, must = false): string[] {
    const raw = args[name]
    if (raw != null && !Array.isArray(raw)) throw new Error(`'${name}' must be a list of paths, written ["/one", "/two"], and this one was sent as a single value. Nothing was written.`)
    const values = (raw ?? []) as unknown[]
    if (values.some(value => value == null || String(value).trim() === '')) throw new Error(`'${name}' holds an empty entry. Every entry is a path on the server's disk. Nothing was written.`)
    if (must && values.length === 0) throw new Error(`'${name}' is required and must name at least one directory, written ["/one", "/two"]. Nothing was written.`)
    return values.map(String)
}

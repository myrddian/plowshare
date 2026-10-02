import { OK, type Answer, bodyOf, countAt, fieldsOf, textAt } from './response.ts'
import type { Ask } from './session.ts'

/**
 * A union project's frames and the `/sync` verbs. Spec
 * 2026-09-14-a-project-can-be-a-union §4, §6.4 and §11.1.
 */

export const UNION_STATUS = 'union.status'
export const UNION_ENABLE = 'union.enable'
export const UNION_BEGIN = 'union.begin'
export const UNION_READY = 'union.ready'
export const UNION_ABORT = 'union.abort'
export const UNION_DISABLE = 'union.disable'
export const UNION_HIDDEN = 'union.hidden'
export const UNION_CONFLICT_OPEN = 'union.conflict.open'
export const UNION_CONFLICT_LIST = 'union.conflict.list'
export const UNION_CONFLICT_RESOLVE = 'union.conflict.resolve'

/** Mutations reached through the rooted Node sync workflow, never raw CLI JSON. */
export const SYNC_OPERATIONS = {
    enable: 'union.enable', begin: 'union.begin', ready: 'union.ready', abort: 'union.abort',
    disable: 'union.disable', hidden: 'union.hidden', open: 'union.conflict.open', resolve: 'union.conflict.resolve',
} as const

export function unionAsk(type: string, project: string, extra: Record<string, unknown> = {}): Ask {
    return { type, payload: { project, ...extra } }
}

export interface UnionStatus {
    readonly eligible: boolean
    readonly enabled: boolean
    readonly state?: string
    readonly syncHidden: readonly string[]
    readonly maxFileBytes: number
    readonly openConflicts: number
    readonly url?: string
}

export function unionStatusOf(answer: Answer): UnionStatus | undefined {
    const body = bodyOf(answer, OK)
    if (body === undefined || typeof body['eligible'] !== 'boolean' || typeof body['enabled'] !== 'boolean') {
        return undefined
    }
    const hidden = Array.isArray(body['syncHidden']) ? body['syncHidden'].map(String) : []
    const state = textAt(body, 'state')
    const url = textAt(body, 'url')
    return {
        eligible: body['eligible'],
        enabled: body['enabled'],
        ...(state === undefined ? {} : { state }),
        syncHidden: hidden,
        maxFileBytes: countAt(body, 'maxFileBytes') ?? 0,
        openConflicts: countAt(body, 'openConflicts') ?? 0,
        ...(url === undefined ? {} : { url }),
    }
}

export interface ConflictRow {
    readonly n: number
    readonly path: string
    readonly baseBlob?: string
    readonly oursBlob?: string
    readonly theirsBlob?: string
    readonly theirsAuthor: string
    readonly runId?: string
}

export function conflictsOf(answer: Answer): readonly ConflictRow[] | undefined {
    const body = bodyOf(answer, OK)
    const rows = body?.['conflicts']
    if (!Array.isArray(rows)) {
        return undefined
    }
    const read: ConflictRow[] = []
    for (const raw of rows) {
        const fields = fieldsOf(raw)
        const n = countAt(fields, 'n')
        const path = textAt(fields, 'path')
        const theirsAuthor = textAt(fields, 'theirsAuthor')
        if (n === undefined || path === undefined || theirsAuthor === undefined) {
            return undefined
        }
        const optional = (name: 'baseBlob' | 'oursBlob' | 'theirsBlob' | 'runId') => {
            const value = textAt(fields, name)
            return value === undefined ? {} : { [name]: value }
        }
        read.push({ n, path, theirsAuthor, ...optional('baseBlob'), ...optional('oursBlob'),
            ...optional('theirsBlob'), ...optional('runId') })
    }
    return read
}

export function openedOf(answer: Answer): number | undefined {
    const body = bodyOf(answer, OK)
    return body === undefined ? undefined : countAt(body, 'n')
}

export type SyncHow = 'mine' | 'theirs' | 'merge' | 'done'

export type SyncAction =
    | { readonly kind: 'status' }
    | { readonly kind: 'on' }
    | { readonly kind: 'off' }
    | { readonly kind: 'conflicts' }
    | { readonly kind: 'hidden'; readonly paths: readonly string[] }
    | { readonly kind: 'resolve'; readonly path: string; readonly how: SyncHow;
        readonly expected?: ConflictRow; readonly localHash?: string; readonly text?: string }

/** Identity of the exact conflict a person reviewed, independent of JSON field order. */
export function conflictIdentity(row: ConflictRow): string {
    return JSON.stringify([row.n, row.path, row.baseBlob, row.oursBlob, row.theirsBlob,
        row.theirsAuthor, row.runId])
}

const HOWS: readonly string[] = ['mine', 'theirs', 'merge', 'done']

export function syncAction(argument: string): SyncAction | undefined {
    const text = argument.trim()
    if (text === '') {
        return { kind: 'status' }
    }
    if (text === 'on' || text === 'off' || text === 'conflicts') {
        return { kind: text }
    }
    const [verb = '', ...rest] = text.split(/\s+/)
    if (verb === 'hidden' && rest.length > 0) {
        return { kind: 'hidden', paths: rest }
    }
    if (verb === 'resolve') {
        const how = rest.at(-1)
        const path = text.slice(verb.length).trim().replace(/\s+\S+$/, '')
        if (rest.length >= 2 && how !== undefined && HOWS.includes(how) && path !== '') {
            return { kind: 'resolve', path, how: how as SyncHow }
        }
    }
    return undefined
}

export function syncNotice(openConflicts: number): string | undefined {
    if (openConflicts <= 0) {
        return undefined
    }
    return `⚠ ${openConflicts} sync conflict${openConflicts === 1 ? '' : 's'}`
}

/** `/sync` with no argument, or `/sync` after a conflict listing: where things stand. */
export function describeSync(status: UnionStatus): string[] {
    if (!status.eligible) {
        return ['only a project rooted on this machine can sync — /here roots this directory']
    }
    if (!status.enabled) {
        return ["this project's files are only on this machine — /sync on keeps a copy on the server"]
    }
    const lines = [`syncing with the server (${(status.state ?? 'OFFLINE').toLowerCase()})`]
    if (status.syncHidden.length > 0) {
        lines.push(`  hidden paths synced: ${status.syncHidden.join(', ')}`)
    }
    if (status.openConflicts > 0) {
        lines.push(`  ${status.openConflicts} open conflict${status.openConflicts === 1 ? '' : 's'} — /sync conflicts`)
    }
    return lines
}

/** `/sync conflicts`: the open conflicts, and how to resolve each. */
export function describeConflicts(rows: readonly ConflictRow[]): string[] {
    if (rows.length === 0) {
        return ['no open sync conflicts']
    }
    return rows.flatMap((row) => [
        `${row.path} — your version kept; ${row.theirsAuthor}'s${row.runId === undefined ? '' : ` (${row.runId})`} set aside`,
        `  /sync resolve ${row.path} mine | theirs | merge`,
    ])
}

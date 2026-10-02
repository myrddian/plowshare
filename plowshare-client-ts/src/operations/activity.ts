// Pure views shared by terminal and desktop clients.
import type { Answer } from './response.ts'
import type { Ask } from './session.ts'
import type { Run } from './inspection.ts'
import { ORCHESTRATION_LIST } from './session.ts'
import { runsOf } from './inspection.ts'


/** The bare push that says a tree's record grew — `RecordKeeper.RECORDED`. */
export const ORCHESTRATION_RECORDED = 'orchestration.recorded'


/** The states a run is live in. */
export const LIVE_STATES: readonly string[] = ['running', 'asking', 'waiting']


/** An `orchestration.recorded` push. */
export interface RecordedPush {
    readonly root: string
    /** The record's highest ordinal. */
    readonly through: number
    /** The tool line whose outcome was just written, when that is what the push is for. */
    readonly settled?: number
}


/** An `orchestration.recorded` push, or nothing for any other. A `settled` that is not a whole
 *  number is left out, and the push is still the record growing. */
export function recordedOf(push: unknown): RecordedPush | undefined {
    if (typeof push !== 'object' || push === null) {
        return undefined
    }
    const p = push as { kind?: unknown, root?: unknown, through?: unknown, settled?: unknown }
    if (p.kind !== ORCHESTRATION_RECORDED || typeof p.root !== 'string' || typeof p.through !== 'number') {
        return undefined
    }
    return {
        root: p.root,
        through: p.through,
        ...(Number.isInteger(p.settled) ? { settled: p.settled as number } : {}),
    }
}


/**
 * How many runs one live listing asks for: the server's most. A state's listing counts every run
 * of the account in it, a tree's live phases among them, so a small page would drop the oldest
 * root first — and the oldest live root is often the one waiting on a person.
 */
export const LIVE_LISTING = 200


/**
 * The listings the live runs are read from, one per live state and filtered on the server.
 *
 * <p><b>Not {@link import('./session.ts').listingRuns}</b>, which is the newest twenty of every
 * state: a root that has been going a while falls out of it behind its own finished phases and
 * whatever else ran since, while it is still live.
 */
export function listingLive(): Ask[] {
    return LIVE_STATES.map((state) => ({ type: ORCHESTRATION_LIST, payload: { state, limit: LIVE_LISTING } }))
}


/**
 * The runs {@link listingLive}'s answers found, newest first and each once — or nothing when any
 * was refused, since a state missing from the read would read as its runs having ended.
 */
export function liveRunsOf(answers: readonly Answer[]): Run[] | undefined {
    const byId = new Map<string, Run>()
    for (const answer of answers) {
        const runs = runsOf(answer)
        if (runs === undefined) {
            return undefined
        }
        for (const run of runs) {
            byId.set(run.id, run)
        }
    }
    return [...byId.values()].sort((a, b) =>
        a.createdAt === b.createdAt ? b.id.localeCompare(a.id) : b.createdAt.localeCompare(a.createdAt))
}
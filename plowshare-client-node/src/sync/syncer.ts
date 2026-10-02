import { rm } from 'node:fs/promises'
import type { Answering } from 'plowshare-client-ts/binding/channel'
import { DELETE, EDIT, MOVE, REPLY_OK, WRITE } from 'plowshare-client-ts/binding/files'
import { OK, type Answer } from 'plowshare-client-ts/operations/response'
import {
    type ConflictRow, type SyncAction, UNION_ABORT, UNION_BEGIN, UNION_CONFLICT_LIST, UNION_CONFLICT_OPEN,
    UNION_CONFLICT_RESOLVE, UNION_DISABLE, UNION_ENABLE, UNION_HIDDEN, UNION_READY, UNION_STATUS,
    type UnionStatus, conflictsOf, openedOf, syncNotice, unionAsk, unionStatusOf,
} from 'plowshare-client-ts/operations/union'
import { conflictIdentity, describeConflicts, describeSync } from 'plowshare-client-ts/operations/union'
import { type ConflictPreview, dropConflictRef, finishMerge, inspectConflict, localVersion, stageMerge, takeTheirs, writeMerge } from './conflicts.ts'
import { MIN_GIT, installedGit, shadowGit, shadowPaths, supported } from './git.ts'
import {
    type Fence, type OpenConflict, type Shadow, commitDirty, initShadow, prepare, push, reconcile, shadowExists,
} from './shadow.ts'

/**
 * One rooted project's union lifecycle on this machine. Spec §4, §5.3, §6.4.
 * Every operation runs one at a time: a tick never interleaves with a connect.
 */

export interface Asker {
    ask(type: string, payload?: unknown): Promise<Answer>
}

export interface SyncerOptions {
    readonly claim: { readonly project: string; readonly machine: string; readonly root: string }
    readonly handle: string
    readonly base: string
    readonly asker: Asker
    readonly bearer: () => Promise<string>
    readonly tell: (trouble: boolean, lines: readonly string[]) => void
    readonly standing: (notice: string | undefined) => void
    readonly every?: number
    readonly gitVersion?: () => Promise<readonly [number, number] | undefined>
    /** Tests point the hub at a local bare repository. */
    readonly remoteUrl?: (url: string) => string
    /** Headless actions must fail instead of only displaying a refusal. */
    readonly strict?: boolean
    readonly signal?: AbortSignal
}

export interface Syncer {
    connect(): Promise<void>
    changed(): void
    tick(): Promise<void>
    leave(): Promise<void>
    /** Withdraw timers immediately; no late tick can restart synchronization. */
    stop(): void
    run(action: SyncAction): Promise<void>
    inspect(path: string): Promise<ConflictPreview>
}

const UNAVAILABLE = `sync unavailable: git ${MIN_GIT.join('.')}+ required`
const RETRYING = 'sync failed — retrying'

/** Every op that changes what is on disk, and so what the shadow commit would see. */
const CHANGING: readonly string[] = [WRITE, EDIT, DELETE, MOVE]

export function noticingWrites(answer: Answering & { close?(): void }, wrote: () => void): Answering & { close?(): void } {
    const watching: Answering & { close?(): void } = async (request) => {
        const reply = await answer(request)
        if (CHANGING.includes(request.op) && reply.outcome === REPLY_OK) {
            wrote()
        }
        return reply
    }
    if (answer.close !== undefined) watching.close = () => answer.close?.()
    return watching
}

export function syncer(options: SyncerOptions): Syncer {
    const { claim, asker } = options
    const paths = shadowPaths(claim.root)
    const shadow: Shadow = { paths, git: shadowGit(paths, options.bearer, options.signal), author: `${options.handle}@${claim.machine}` }
    let live = false
    /** The hub's rules as the last status said them; set before the first commit. */
    let fence: Fence = { hidden: [], maxFileBytes: 0 }
    /** Set by `leave`: a tick already queued behind it must not reconnect. */
    let left = false
    let timer: ReturnType<typeof setInterval> | undefined
    let debounce: ReturnType<typeof setTimeout> | undefined
    let queue: Promise<unknown> = Promise.resolve()

    const serial = <T>(work: () => Promise<T>): Promise<T> => {
        const next = queue.then(work, work)
        queue = next.catch(() => undefined)
        return next
    }
    const ask = (type: string, extra: Record<string, unknown> = {}) => {
        const frame = unionAsk(type, claim.project, extra)
        return asker.ask(frame.type, frame.payload)
    }
    const trouble = (error: unknown) => options.tell(true, [`sync: ${error instanceof Error ? error.message : String(error)}`])
    const url = (status: UnionStatus) => {
        const path = status.url ?? `/v1/sync/${encodeURIComponent(claim.project)}.git`
        const full = `${options.base.replace(/\/$/, '')}${path}`
        return options.remoteUrl === undefined ? full : options.remoteUrl(full)
    }
    const status = async () => {
        const answer = await ask(UNION_STATUS)
        const known = unionStatusOf(answer)
        if (known === undefined && options.strict) throw new Error('the server did not return a usable union status')
        return known
    }
    const gitOk = async () => {
        if (supported(await (options.gitVersion ?? installedGit)())) {
            return true
        }
        options.standing(UNAVAILABLE)
        if (options.strict) throw new Error(UNAVAILABLE)
        return false
    }
    const refreshStanding = async () => {
        options.standing(syncNotice((await status())?.openConflicts ?? 0))
    }
    const openConflict: OpenConflict = async (found) => {
        const answer = await ask(UNION_CONFLICT_OPEN, { ...found })
        const n = openedOf(answer)
        if (n === undefined) {
            throw new Error(answer.said ?? 'the server did not record a sync conflict')
        }
        return n
    }
    const ticking = () => {
        timer ??= setInterval(() => { void api.tick() }, options.every ?? 60_000)
        timer.unref?.()
    }
    const ready = async (known: UnionStatus) => {
        if (!(await shadowExists(paths))) {
            await initShadow(shadow, url(known))
        }
        fence = { hidden: known.syncHidden, maxFileBytes: known.maxFileBytes }
        const skipped = await prepare(shadow, known.syncHidden, known.maxFileBytes)
        if (skipped.length > 0) {
            options.tell(false, [`sync leaves out ${skipped.length} path${skipped.length === 1 ? '' : 's'}: ${skipped.join(', ')}`])
        }
        const done = await reconcile(shadow, openConflict, fence)
        if (left) throw new Error('sync was stopped')
        const answered = await ask(UNION_READY, { commit: done.commit })
        if (answered.code !== OK) {
            throw new Error(answered.said ?? 'the server did not accept the sync')
        }
        if (left) throw new Error('sync was stopped')
        live = true
        ticking()
        await refreshStanding()
    }
    const pushChanges = async () => {
        if (!live) {
            return
        }
        const committed = await commitDirty(shadow, `sync from ${shadow.author}`, fence)
        if (committed !== undefined && await push(shadow, ['refs/heads/main']) === 'moved') {
            await reconcile(shadow, openConflict, fence)
            await refreshStanding()
        }
    }
    const conflictRow = async (path: string): Promise<ConflictRow | undefined> =>
        conflictsOf(await ask(UNION_CONFLICT_LIST))?.find((row) => row.path === path)

    /**
     * The body of `connect`, unqueued, so `tick` can run it from inside the
     * queue. A failure once git is known to be usable keeps the timer running
     * and says so on the status line: the next tick tries again, rather than
     * the session silently never syncing. A project that is not a union, or a
     * git too old, is not a failure and is not retried.
     */
    const connectOnce = async () => {
        if (left) return
        let retryable = false
        let begun = false
        try {
            const known = await status()
            if (known === undefined || !known.enabled) {
                // Not (or no longer) a union: nothing to retry.
                clearInterval(timer)
                timer = undefined
                live = false
                options.standing(undefined)
                return
            }
            if (!(await gitOk())) {
                clearInterval(timer)
                timer = undefined
                return
            }
            retryable = true
            const beginning = await ask(UNION_BEGIN)
            if (beginning.code !== OK) {
                throw new Error(beginning.said ?? 'the server would not start a sync')
            }
            begun = true
            await ready(known)
        } catch (error) {
            if (begun) {
                // Let go of the SYNCING claim now, so mirror writes are not held off until it
                // expires; the next tick's begin claims it again.
                await ask(UNION_ABORT).catch(() => undefined)
            }
            trouble(error)
            if (retryable && !left) {
                options.standing(RETRYING)
                ticking()
            }
            if (options.strict) throw error
        }
    }

    const api: Syncer = {
        inspect: (path) => serial(async () => {
            if (left) throw new Error('sync was stopped')
            const row = await conflictRow(path)
            if (!row) throw new Error('The conflict is no longer open. Refresh the list.')
            return inspectConflict(shadow, row)
        }),
        connect: () => serial(connectOnce),
        changed() {
            if (left) return
            clearTimeout(debounce)
            debounce = setTimeout(() => { void serial(pushChanges).catch(trouble) }, 2_000)
            debounce.unref?.()
        },
        tick: () => serial(async () => {
            if (left) {
                return
            }
            // Reconcile even when this machine has no edits: agents can have advanced the hub.
            await connectOnce()
        }).catch(trouble),
        leave: () => serial(async () => {
            left = true
            clearInterval(timer)
            clearTimeout(debounce)
            timer = undefined
            if (live) {
                await pushChanges().catch(trouble)
            }
            live = false
        }),
        stop() {
            left = true
            live = false
            clearInterval(timer); clearTimeout(debounce)
            timer = undefined
        },
        run: (action) => serial(async () => {
            if (left) throw new Error('sync was stopped')
            let begun = false
            try {
                switch (action.kind) {
                    case 'status': {
                        const known = await status()
                        options.tell(false, known === undefined ? ['the server did not say'] : describeSync(known))
                        return
                    }
                    case 'on': {
                        const known = await status()
                        if (known === undefined || !known.eligible) {
                            options.tell(true, ['only a project rooted on this machine can sync — /here roots this directory'])
                            if (options.strict) throw new Error('only a rooted project can sync')
                            return
                        }
                        if (!(await gitOk())) {
                            return
                        }
                        const enabled = await ask(UNION_ENABLE)
                        if (enabled.code !== OK) {
                            throw new Error(enabled.said ?? 'the server would not make this project a union')
                        }
                        begun = true
                        await ready(known)
                        options.tell(false, [`${claim.project} now keeps a copy on the server; agents can work on it while this machine is off`])
                        return
                    }
                    case 'off': {
                        const answered = await ask(UNION_DISABLE)
                        if (answered.code !== OK) {
                            throw new Error(answered.said ?? 'the server would not stop the sync')
                        }
                        clearInterval(timer)
                        clearTimeout(debounce)
                        timer = undefined
                        live = false
                        await rm(paths.gitDir, { recursive: true, force: true })
                        options.standing(undefined)
                        options.tell(false, [`${claim.project} is no longer synced; the server's copy is gone and your files are untouched`])
                        return
                    }
                    case 'hidden': {
                        const answered = await ask(UNION_HIDDEN, { paths: action.paths })
                        if (answered.code !== OK) {
                            throw new Error(answered.said ?? 'the server refused those paths')
                        }
                        options.tell(false, [`hidden paths synced (replaces the previous list): ${action.paths.join(', ')}`])
                        return
                    }
                    case 'conflicts': {
                        const rows = conflictsOf(await ask(UNION_CONFLICT_LIST))
                        if (rows === undefined && options.strict) throw new Error('the server did not return a usable conflict listing')
                        options.tell(false, describeConflicts(rows ?? []))
                        return
                    }
                    case 'resolve': {
                        const row = await conflictRow(action.path)
                        if (row === undefined) {
                            options.tell(true, [`no open sync conflict for ${action.path}`])
                            if (options.strict) throw new Error('no open sync conflict for the named path')
                            return
                        }
                        if (action.expected && conflictIdentity(row) !== conflictIdentity(action.expected)) {
                            throw new Error('The conflict changed. Inspect it again before resolving it.')
                        }
                        if (action.localHash && (await localVersion(shadow, row)).hash !== action.localHash) {
                            throw new Error('Your file changed. Inspect it again before resolving it.')
                        }
                        if (action.how === 'merge') {
                            const staged = await stageMerge(shadow, row)
                            options.tell(false, [`edit ${staged}, then /sync resolve ${row.path} done`])
                            return
                        }
                        if (action.how === 'theirs') {
                            await takeTheirs(shadow, row)
                        } else if (action.how === 'done') {
                            if (action.text !== undefined) await writeMerge(shadow, row, action.text)
                            await finishMerge(shadow, row)
                        }
                        const resolution = action.how === 'done' ? 'merged' : action.how
                        const answered = await ask(UNION_CONFLICT_RESOLVE, { n: row.n, resolution })
                        if (answered.code !== OK) {
                            throw new Error(answered.said ?? 'the server did not close the conflict')
                        }
                        await dropConflictRef(shadow, row)
                        await pushChanges()
                        await refreshStanding()
                        return
                    }
                }
            } catch (error) {
                if (begun) await ask(UNION_ABORT).catch(() => undefined)
                trouble(error)
                if (options.strict) throw error
            }
        }),
    }
    return api
}

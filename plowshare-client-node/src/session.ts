import { Credentials, credentialDirectory, savedOrLogin } from './credentials.ts'
import { randomUUID } from 'node:crypto'
import { hostname } from 'node:os'
import { realpath, stat } from 'node:fs/promises'
import { isAbsolute } from 'node:path'
import { openFiles, openSocket, refresh, signIn, MustChangePassword } from 'plowshare-client-ts/binding/auth'
import type { Claim, Tokens } from 'plowshare-client-ts/binding/auth'
import type { Connection, Socket } from 'plowshare-client-ts/binding/connection'
import { enforcing } from './enforcer.ts'
import { rooter } from './rooter.ts'
import { noticingWrites } from './sync/syncer.ts'

export interface SessionOptions {
    readonly credentials?: Credentials
    readonly onPush?: (push: unknown) => void
    readonly onClose?: () => void
    readonly onPresenceLost?: () => void
    readonly onWrite?: () => void
}

export interface Session extends Connection {
    readonly session: string
    readonly handle: string
    runSession(): string | null
    root(project: string, path: string): Promise<{ previous?: Claim; current: Claim }>
    bearer(): Promise<string>
    release(): Promise<void>
}

/** Validate explicit local roots before authentication or claiming a project. */
export async function canonicalRoot(path: string): Promise<string> {
    if (!isAbsolute(path)) throw new Error("'path' must be an absolute directory on this machine. Nothing was rooted.")
    try {
        const directory = await realpath(path)
        if (!(await stat(directory)).isDirectory()) throw new Error()
        return directory
    } catch { throw new Error("'path' must name an existing directory on this machine. Nothing was rooted.") }
}

export function socketAt(url: string, signal: AbortSignal): Promise<Socket> {
    signal.throwIfAborted()
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(url)
        const cleanup = (): void => {
            signal.removeEventListener('abort', aborted)
            socket.removeEventListener('open', opened)
            socket.removeEventListener('error', failed)
            socket.removeEventListener('close', failed)
        }
        const aborted = (): void => { cleanup(); socket.close(); reject(new Error('connection interrupted')) }
        const failed = (): void => { cleanup(); socket.close(); reject(new Error('could not open socket')) }
        const opened = (): void => { cleanup(); resolve(socket) }
        signal.addEventListener('abort', aborted, { once: true })
        socket.addEventListener('open', opened, { once: true })
        socket.addEventListener('error', failed, { once: true })
        socket.addEventListener('close', failed, { once: true })
    })
}

/** Auth is HTTP bootstrap; durable operations and explicit file presence use WS.
 * Token rotations are retained before a later ticket/upgrade can fail. No replay. */
export async function authenticate(base: string, handle: string, password: string,
        signal: AbortSignal, diagnostic: (text: string) => void, options: SessionOptions = {}): Promise<Session> {
    let eventsLost = false, presenceLost = false
    const door = {
        base, session: randomUUID(),
        fetch: (url: string, sent: Parameters<typeof fetch>[1]) => fetch(url, { ...sent, signal, redirect: 'error' }),
        open: (url: string) => socketAt(url, signal),
        ...(options.onPush === undefined ? {} : { onPush: options.onPush }),
        onClose: () => { eventsLost = true; options.onClose?.() },
    }
    const store = options.credentials
    const authDoor = { ...door, ...(store === undefined ? {} : { renew: () => store.renew(door) }) }
    const signed = store === undefined ? await signIn(door, handle, password) : await savedOrLogin(door, store, handle, password)
    if (signed.mustChangePassword) throw new MustChangePassword()
    const resolvedHandle = store === undefined ? handle : (await store.session()).handle
    const opened = await openSocket(authDoor, signed.tokens)
    let tokens: Tokens = opened.tokens
    let renewing: Promise<unknown> = Promise.resolve()
    const renewed = <T>(work: () => Promise<T>): Promise<T> => {
        const next = renewing.then(work, work)
        renewing = next.catch(() => undefined)
        return next
    }
    const presence = rooter({
        open: claim => renewed(async () => {
            const opened = await openFiles(authDoor, tokens, claim, renewed => { tokens = renewed })
            return opened.socket
        }),
        answering: root => noticingWrites(enforcing(root, false), () => options.onWrite?.()), requireReady: true,
        onLost: () => { presenceLost = true; diagnostic('local file presence was lost; root explicitly again before starting another agent run'); options.onPresenceLost?.() },
    })
    const close = (): void => { void presence.release(); opened.connection.close() }
    signal.addEventListener('abort', close, { once: true })
    if (signal.aborted) { close(); signal.throwIfAborted() }
    return {
        session: door.session,
        handle: resolvedHandle,
        ask: (type, payload) => {
            signal.throwIfAborted()
            if (eventsLost) return Promise.reject(new Error('the event connection was lost; completion is unknown; restart the client; no request was replayed'))
            return opened.connection.ask(type, payload)
        },
        runSession: () => {
            if (presenceLost) throw new Error('local file presence was lost; root explicitly again; the run was not submitted')
            return presence.current() === undefined ? null : door.session
        },
        root: async (project, path) => {
            if (!project.trim()) throw new Error("'project' must be a nonblank name. Nothing was rooted.")
            signal.throwIfAborted()
            const directory = await canonicalRoot(path)
            const previous = presence.current()
            presenceLost = false
            try { await presence.root({ project, root: directory, machine: hostname() }) }
            catch { throw new Error(`could not open a file channel for '${project}'; this client is now serving no files at all; root explicitly again once the server is reachable`) }
            const current = presence.current()
            if (current === undefined) throw new Error('file presence was lost while rooting; nothing is being served')
            return { ...(previous === undefined ? {} : { previous }), current }
        },
        bearer: () => renewed(async () => {
            signal.throwIfAborted()
            tokens = await (authDoor.renew?.() ?? refresh(door, tokens))
            return tokens.access
        }),
        release: () => presence.release(),
        close: () => { signal.removeEventListener('abort', close); close() },
    }
}

/** Saved credentials are shared by the operator's local clients, keyed by origin. */
export function authenticateConfigured(base: string, env: Readonly<Record<string, string | undefined>>,
        signal: AbortSignal, diagnostic: (text: string) => void, options: SessionOptions = {}): Promise<Session> {
    // Explicit environment credentials remain ephemeral for automation and existing callers.
    const handle = env['PLOWSHARE_HANDLE'] ?? '', password = env['PLOWSHARE_PASSWORD'] ?? ''
    if (handle || password) {
        if (!handle.trim() || !password) throw new Error('Provide both PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD, or use a saved login.')
        return authenticate(base, handle, password, signal, diagnostic, options)
    }
    return authenticate(base, '', '', signal, diagnostic, { ...options, credentials: new Credentials(base, credentialDirectory(env), signal) })
}

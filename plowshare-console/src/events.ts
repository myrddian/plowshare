/**
 * The listener socket: `/v1/events`, and a reconnect the operator can see.
 *
 * **No headers, and that is the reason the whole auth design is a cookie.**
 * `new WebSocket(url)` takes a URL and an optional subprotocol list and nothing
 * else -- there is no third argument, and a browser will not let a page set one
 * header on an upgrade. The CLI sends `Authorization: Bearer` through okhttp and
 * cannot; this module sends `ps_access` because the browser attaches it to the
 * upgrade the same way it attaches it to a `fetch`. `AuthFilter` reads either.
 *
 * The server half of that is measured -- `AuthFilterTest` dials a real socket
 * against a real Tomcat and observes an upgrade with a valid `ps_access` cookie
 * reaching the handler and one without it refused 401 before the handler is
 * entered. The browser half, that the browser attaches the cookie to an upgrade
 * without being asked, is NOT measured by anything in this repository: jsdom's
 * WebSocket is not a browser's, and this module's tests hand it a double. It is
 * on the slice's manual-run list for that reason.
 */

/** The path `EventChannelHandler` is registered at. */
export const EVENTS_PATH = '/v1/events'

/** The query parameter the handler reads the session id from. */
export const SESSION_PARAM = 'session'

/** The one protocol version this build speaks, per `Envelope.CURRENT_VERSION`. */
export const PROTOCOL_VERSION = 'plowshare-v1'

/** What a request frame is answered with: the server's `Outcome`, as JSON. */
export interface FrameOutcome {
    readonly code: string
    readonly said?: string
    readonly payload?: unknown
}

/** What the socket is doing, in the words a person should be shown. */
export type StreamState = 'connecting' | 'open' | 'reconnecting' | 'closed'

/** The state, plus what a screen needs to render a reconnect honestly. */
export interface StreamStatus {
    readonly state: StreamState
    /** Consecutive failed connection attempts; 0 while open. */
    readonly attempt: number
    /** Milliseconds until the next attempt, or null when none is scheduled. */
    readonly retryInMs: number | null
}

/** A handle on a running stream. */
export interface EventStream {
    /** The current status, for a caller that would rather ask than listen. */
    status(): StreamStatus
    /** Stop, and stay stopped. Idempotent; no reconnect follows. */
    close(): void
    /**
     * Send a request frame on this socket and wait for its reply. The console's first
     * frame-speaking call: INBOX uses it, and screens move to it one at a time.
     */
    ask(type: string, payload?: unknown): Promise<FrameOutcome>
}

export interface EventStreamOptions {
    /** The session id to attach as a listener under. */
    readonly session: string
    /** One decoded frame. */
    readonly onEvent: (event: unknown) => void
    /** Every state change, including each scheduled retry. */
    readonly onStatus?: (status: StreamStatus) => void
    /** A frame that was not JSON. Defaults to a `console.warn`. */
    readonly onMalformed?: (frame: string, problem: unknown) => void
    /** Builds the socket. Exists so a test can hand over a double. */
    readonly open?: (url: string) => WebSocket
    /** First retry delay. Doubles per attempt. */
    readonly baseDelayMs?: number
    /** The ceiling the doubling stops at. */
    readonly maxDelayMs?: number
    /** The window to read `location` from; the real one by default. */
    readonly scope?: Window
    /**
     * How long {@link EventStream.ask} waits for its reply before it gives up.
     *
     * A reply the server wrote and the socket lost, or one a handler never
     * returned, would otherwise leave a button disabled until the tab closed.
     * Giving up rejects the ask and forgets its id, so a reply arriving after
     * that is ignored as one nothing is waiting on. The server keeps no record
     * of the id either way: a timed-out frame may still have been acted on.
     */
    readonly askTimeoutMs?: number
}

/** How long an ask waits, when nothing says otherwise. */
export const ASK_TIMEOUT_MS = 30_000

/**
 * The socket URL for a session, derived from the page's own origin.
 *
 * `wss:` when the page is `https:` and `ws:` otherwise, which is the pairing a
 * browser enforces anyway -- a secure page may not open an insecure socket. The
 * host comes from `location.host` rather than being configured, so the socket
 * goes exactly where the page came from: the jar in production, and the Vite
 * dev server in development, where the proxy forwards it. See `vite.config.ts`
 * for what that proxy has to do to the `Origin` header, and why.
 */
export function eventUrl(session: string, scope: Window = window): string {
    const scheme = scope.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const query = new URLSearchParams({ [SESSION_PARAM]: session })
    return `${scheme}//${scope.location.host}${EVENTS_PATH}?${query.toString()}`
}

/**
 * Open the listener socket, and keep it open.
 *
 * **The reconnect is visible on purpose.** The event stream is droppable by
 * design -- `JobStore` behind `GET /v1/jobs/{id}` is the contractual record and
 * this socket is not a log -- so a gap here is not an error, but a gap the
 * person watching cannot see is a screen quietly telling them nothing is
 * happening. Every attempt is reported through {@link EventStreamOptions.onStatus}
 * with the delay attached, so a screen can say "reconnecting, next try in 8s"
 * rather than going still.
 *
 * The backoff doubles from {@link EventStreamOptions.baseDelayMs} to a ceiling
 * and then stays there; there is no attempt limit and no jitter. No limit
 * because the server being down is the ordinary case this is for -- it is
 * restarted constantly during development, and a stream that gave up would have
 * to be re-opened by a reload. No jitter because there is one client: jitter
 * exists to de-synchronise a fleet, and here it would only make the retry
 * schedule unpredictable to the person reading it off the screen.
 *
 * **One case where retrying forever is the wrong-looking answer, stated
 * plainly:** the server's token store is in memory, so a restart invalidates
 * the cookie this socket rides on. Every subsequent upgrade is then refused 401
 * and this will retry at the ceiling until the tab is closed. That is why the
 * status is surfaced rather than swallowed -- the remedy is the new bootstrap
 * URL the restarted server printed, and nothing this module can do gets there
 * on its own.
 */
export function openEventStream(options: EventStreamOptions): EventStream {
    const scope = options.scope ?? window
    const openSocket = options.open ?? ((url: string) => new WebSocket(url))
    const baseDelayMs = options.baseDelayMs ?? 500
    const maxDelayMs = options.maxDelayMs ?? 15_000
    const askTimeoutMs = options.askTimeoutMs ?? ASK_TIMEOUT_MS
    const url = eventUrl(options.session, scope)

    let state: StreamState = 'connecting'
    let attempt = 0
    let retryInMs: number | null = null
    let socket: WebSocket | null = null
    let timer: ReturnType<typeof setTimeout> | null = null
    let stopped = false
    let issued = 0
    const waiting = new Map<string, { answered: (o: FrameOutcome) => void, failed: (e: Error) => void }>()
    const failWaiting = (why: string): void => {
        for (const each of waiting.values()) {
            each.failed(new Error(why))
        }
        waiting.clear()
    }

    const status = (): StreamStatus => ({ state, attempt, retryInMs })
    const announce = (): void => options.onStatus?.(status())

    const malformed = options.onMalformed
        ?? ((frame: string, problem: unknown): void => {
            // The frame itself is deliberately not logged: this socket carries
            // job events, which name files and models and whatever an agent was
            // asked to do. Its length is enough to tell a truncation from a
            // protocol mismatch.
            console.warn(`dropped a ${frame.length}-character frame that was not JSON`, problem)
        })

    /**
     * Schedule the next attempt, doubling until the ceiling.
     *
     * `attempt` is incremented before the delay is computed, so the first retry
     * waits `baseDelayMs` rather than nothing.
     */
    const scheduleRetry = (): void => {
        attempt += 1
        retryInMs = Math.min(baseDelayMs * 2 ** (attempt - 1), maxDelayMs)
        state = 'reconnecting'
        announce()
        timer = setTimeout(connect, retryInMs)
    }

    /**
     * One connection attempt.
     *
     * `settled` is per-attempt and not per-stream: a browser fires `error` and
     * then `close` for a refused upgrade, and both would otherwise schedule a
     * retry, halving the backoff and doubling the traffic.
     */
    function connect(): void {
        if (stopped) {
            return
        }
        timer = null
        let settled = false
        const failed = (): void => {
            if (settled || stopped) {
                return
            }
            settled = true
            socket = null
            failWaiting('the socket closed before it answered')
            scheduleRetry()
        }

        socket = openSocket(url)
        socket.onopen = (): void => {
            attempt = 0
            retryInMs = null
            state = 'open'
            announce()
        }
        socket.onmessage = (message: MessageEvent): void => {
            const frame = String(message.data)
            let decoded: unknown
            try {
                decoded = JSON.parse(frame)
            } catch (problem) {
                // Not fatal, and not a reconnect. A frame this client cannot
                // read is one event lost, and the socket behind it is still
                // carrying the rest.
                malformed(frame, problem)
                return
            }
            if (typeof decoded === 'object' && decoded !== null && 'protocol_version' in decoded) {
                const reply = decoded as { id?: unknown, payload?: unknown }
                if (typeof reply.id !== 'string') { options.onEvent(decoded); return }
                const id = reply.id
                const pending = waiting.get(id)
                if (pending !== undefined) {
                    waiting.delete(id)
                    pending.answered((reply.payload ?? { code: 'INTERNAL_ERROR' }) as FrameOutcome)
                }
                return
            }
            options.onEvent(decoded)
        }
        socket.onerror = failed
        socket.onclose = failed
    }

    connect()

    return {
        status,
        close(): void {
            if (stopped) {
                return
            }
            stopped = true
            if (timer !== null) {
                clearTimeout(timer)
                timer = null
            }
            retryInMs = null
            state = 'closed'
            // The handlers are dropped before `close()` so that the close this
            // call causes cannot come back through `failed` and schedule a
            // reconnect for a stream that was deliberately stopped.
            if (socket !== null) {
                socket.onopen = null
                socket.onmessage = null
                socket.onerror = null
                socket.onclose = null
                socket.close()
                socket = null
            }
            failWaiting('this stream was closed')
            announce()
        },
        ask(type: string, payload?: unknown): Promise<FrameOutcome> {
            if (socket === null || state !== 'open') {
                return Promise.reject(new Error(`the event socket is not open; "${type}" was not sent`))
            }
            issued += 1
            const id = `console-${issued}`
            const live = socket
            return new Promise<FrameOutcome>((answered, failed) => {
                const deadline = setTimeout(() => {
                    if (waiting.delete(id)) {
                        failed(new Error(`"${type}" was not answered within ${askTimeoutMs}ms; it may`
                            + ' still have been acted on'))
                    }
                }, askTimeoutMs)
                waiting.set(id, {
                    answered: (outcome) => { clearTimeout(deadline); answered(outcome) },
                    failed: (problem) => { clearTimeout(deadline); failed(problem) },
                })
                try {
                    live.send(JSON.stringify({ id, type, protocol_version: PROTOCOL_VERSION, payload: payload ?? {} }))
                } catch (trouble) {
                    // Nothing left the tab, so nothing will answer this id.
                    waiting.delete(id)
                    clearTimeout(deadline)
                    failed(trouble instanceof Error ? trouble : new Error(String(trouble)))
                }
            })
        },
    }
}

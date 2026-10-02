import { CURRENT_VERSION, asking, isAnswer, outcomeIn } from './envelope.ts'
import type { Outcome } from './envelope.ts'

/**
 * One socket, many questions, and the correlation that keeps their answers
 * apart.
 *
 * <h2>The socket is injected, and its type is declared here rather than
 * imported</h2>
 *
 * <p>The plan says the WebSocket is injected rather than reached for, which is
 * what makes this testable with a fake and is the same reasoning the client
 * design applies to `fetch`. The consequence, settled as a ruling before this
 * task ran: <b>an injected dependency does not need the platform's global type
 * at all — it needs a minimal structural interface describing only what is
 * actually called.</b> See {@link Socket}, which is four members.
 *
 * <p>That is stronger than naming `WebSocket` in three ways. It keeps
 * `src/binding/tsconfig.json`'s `types: []` intact, so the compiler stays the
 * first line of defence and a `node:` import in this directory remains a
 * TS2307 rather than something `@types/node` would quietly bless. It makes the
 * fake in `connection.test.ts` trivial, because a fake only has to implement
 * what the interface names. And it writes down the surface actually depended
 * on, which a platform type does not.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>No reconnection, no backoff, no queueing of asks made before the socket is
 * open: `connect` is handed a socket and speaks on it. Composition — sign in,
 * refresh, ticket, open, hand the socket here — is task 5's `openSocket`, and
 * putting any of it here would make this module know about auth, which spec §3
 * is explicit that the protocol does not.
 */

/**
 * A message arriving from the socket.
 *
 * One field, because one field is what is read. A browser's `MessageEvent` and
 * Node's global `WebSocket` both carry a great deal more; none of it is used
 * here, and naming it would be a claim this module cannot keep.
 */
export interface Arrival {
    readonly data: unknown
}

/**
 * <b>The whole of the socket surface this module depends on.</b>
 *
 * <p>Four members: {@link send}, {@link close}, and the two subscriptions. A
 * real `WebSocket` — the browser's, or Node's global since v22 — satisfies this
 * structurally, and so does the seventy-line fake in the test. What is
 * deliberately <b>omitted</b> is everything this module never calls:
 * `readyState`, `bufferedAmount`, `binaryType`, `extensions`, `protocol`,
 * `url`, the `on*` handler properties, `dispatchEvent`, `removeEventListener`,
 * and the `error` and `open` events.
 *
 * <p>Two of those omissions are worth stating rather than merely noting.
 * <b>`readyState` is absent because this module never asks a socket what it is
 * doing</b> — it learns that the connection is gone by being told (`close`),
 * which is the only account that is true at the moment it is read; a
 * `readyState` checked before a `send` is stale by the time the frame leaves.
 * <b>`error` is absent because a WebSocket error is always followed by a
 * close</b>, and it is the close that has to fail the outstanding asks; a
 * client that reacted to both would fail them twice.
 *
 * <p>`removeEventListener` is absent because a `Connection` lives exactly as
 * long as its socket: there is no path here that unsubscribes and keeps using
 * it. Cost if that changes, or if one of the other omissions turns out to be
 * needed: one member added to this interface and to the fake, found the first
 * time the real socket is driven.
 */
export interface Socket {
    /** One frame, as text. This client sends no binary. */
    send(frame: string): void

    /** Hangs up. Called by {@link Connection.close}. */
    close(): void

    addEventListener(type: 'message', listener: (event: Arrival) => void): void
    addEventListener(type: 'close', listener: (event: unknown) => void): void
}

/** What {@link connect} needs, and what it will accept on top of that. */
export interface ConnectOptions {
    /** The socket, already open. See {@link Socket}. */
    readonly socket: Socket

    /**
     * Every frame that is not an answer to something this client asked.
     *
     * A bare `JobEvent` today — `{ job, kind, agent?, tool?, ending?, … }` —
     * handed on as `unknown` rather than typed here, because giving it a shape
     * would put a piece of the job model in the wire module, and interpreting
     * an event is `logic/`'s work (task 6's `followed`).
     */
    readonly onPush?: (push: unknown) => void

    /**
     * The socket went away, and every ask outstanding has already been failed.
     *
     * <h2>Why an outstanding ask is not enough, which is what a whole-plan
     * review corrected</h2>
     *
     * <p>The `close` listener below has always stranded the waiting map, so a
     * caller sitting in `await ask(…)` learns about a close by that promise
     * rejecting. The assumption underneath is that a caller who cares is always
     * inside an ask — and for this protocol that is exactly false. `agent.run`
     * answers `ACCEPTED` in milliseconds and the run itself is <b>minutes</b>,
     * every one of them spent with no frame outstanding at all, waiting on a
     * push. A close in that window fails nothing, because nothing is waiting,
     * and the caller waits for an event that can no longer arrive.
     *
     * <p>So this is the signal for the other half of a caller's life. A proxy's
     * idle cutoff, a laptop sleeping, a deploy, a restart: all of them land
     * here, and none of them land on an ask.
     *
     * <h2>After the strand, and once</h2>
     *
     * <p>It is called <b>after</b> `strand`, which is a claim about state
     * rather than about scheduling, and the difference is worth spelling out
     * because a reader will assume the other one. By the time this runs every
     * outstanding ask has been rejected and the connection is already closed,
     * so a listener that tries to ask something is refused by `ask`'s own guard
     * instead of left waiting on a dead socket. What it is <b>not</b> is
     * ordered ahead of those callers resuming: a rejection resumes its awaiter
     * in a microtask and this runs synchronously inside the close listener, so
     * a listener here runs <i>before</i> any `await ask(…)` continuation. That
     * order is pinned in `connection.test.ts` rather than described.
     *
     * <p>And once, because it is a `close` subscription and a socket closes
     * once — there is no path here that re-opens one.
     *
     * <p><b>{@link Connection.close} does not call it.</b> A caller that hung up
     * knows it hung up; telling it so would turn every ordinary end of a
     * session into a report that the connection was lost.
     */
    readonly onClose?: () => void
}

/** One socket's worth of question-and-answer. */
export interface Connection {
    /**
     * Sends a frame and resolves with the server's answer to <b>that</b> frame.
     *
     * @param type a dotted frame type, from the server's `FrameTypes`
     * @param payload the frame's own data; `{}` when omitted
     * @returns the {@link Outcome}, whatever its code — a `CONFLICT` resolves
     *     like an `OK` does, because a refusal is an answer. The promise is
     *     rejected only when there is <b>no</b> answer to give: the socket
     *     closed, or a frame arrived that this build cannot read as one
     */
    ask(type: string, payload?: unknown): Promise<Outcome>

    /** Hangs up, failing every ask still outstanding. */
    close(): void
}

/** One outstanding question. */
interface Waiting {
    readonly answered: (outcome: Outcome) => void
    readonly failed: (trouble: Error) => void
}

/** Delivery state for recovery without replaying a mutation. */
export class ConnectionFault extends Error {
    readonly code: 'NOT_SUBMITTED' | 'RESPONSE_LOST' | 'INVALID_ENVELOPE'
    constructor(code: ConnectionFault['code'], message: string) { super(message); this.code = code }
}

/**
 * Speaks frames on an already-open socket.
 *
 * <h2>Correlation is by `id`, and the id means nothing to anybody else</h2>
 *
 * <p>Spec §3.1: `id` is client-generated and correlates a response to its
 * request. The server keeps no record of it — `EventChannelHandler` says so in
 * as many words — so it need only be unique among the frames <i>this
 * connection</i> has outstanding, which a counter gives without reaching for a
 * `crypto` this project has no types for and no need of. Nothing reads it but
 * the map below.
 */
export function connect(options: ConnectOptions): Connection {
    const socket = options.socket
    const onPush = options.onPush
    const onClose = options.onClose
    const waiting = new Map<string, Waiting>()
    let issued = 0
    let open = true

    socket.addEventListener('message', (event) => {
        arrived(event.data)
    })

    // The server closes a connection it cannot queue an answer for, rather than
    // dropping the answer, and its reasoning names this listener's job: a
    // dropped response is "an id that is never resolved and a caller that never
    // fails", where a close "fails every outstanding id at once". This is the
    // client half of that. Without it the caller waits forever, which is the
    // exact outcome the server chose to close in order to avoid.
    socket.addEventListener('close', () => {
        strand('the socket closed before this frame was answered')
        // After the strand and never before it: see `ConnectOptions.onClose`.
        // Every ask is rejected and the connection is shut by the time a
        // listener runs, so nothing it does here can start a new wait on a
        // socket that is gone.
        onClose?.()
    })

    /** One frame off the wire: an answer to correlate, or a push to hand on. */
    function arrived(data: unknown): void {
        if (typeof data !== 'string') {
            // Binary. This client sends none and the server sends none; a frame
            // that is neither an answer nor a push correlates with nothing, so
            // there is nothing it could fail and nothing to hand a listener.
            return
        }
        let frame: unknown
        try {
            frame = JSON.parse(data)
        } catch {
            // Unreadable text carries no id either. Dropping it is the only
            // honest move — failing some ask that happens to be outstanding
            // would blame a question this frame was never an answer to.
            return
        }
        if (!isAnswer(frame)) {
            onPush?.(frame)
            return
        }
        answer(frame)
    }

    /** An enveloped frame: check the handshake, find the asker, hand it over. */
    function answer(frame: Record<string, unknown>): void {
        const id = frame['id']
        const asker = typeof id === 'string' ? waiting.get(id) : undefined
        if (asker === undefined) {
            // An answer to a frame this connection is no longer waiting on —
            // one already failed by a close, or an id it never issued. There is
            // nobody to tell.
            return
        }
        waiting.delete(id as string)
        const version = frame['protocol_version']
        if (version !== CURRENT_VERSION) {
            // §3.2: the envelope is a handshake, so this is refused rather than
            // read as data. Per frame, and not by tearing the connection down,
            // because that is how the server refuses in the other direction —
            // binding throws out of `Envelope`'s constructor for that one frame.
            asker.failed(new ConnectionFault('INVALID_ENVELOPE',
                `this build speaks protocol_version "${CURRENT_VERSION}"; a frame naming`
                + ` "${String(version)}" is proposing a different handshake and is refused`))
            return
        }
        try {
            asker.answered(outcomeIn(frame['payload']))
        } catch (trouble) {
            asker.failed(new ConnectionFault('INVALID_ENVELOPE', trouble instanceof Error ? trouble.message : 'response envelope is unreadable'))
        }
    }

    /** Fails everything outstanding, once, with the same reason. */
    function strand(why: string): void {
        open = false
        const stranded = [...waiting.values()]
        waiting.clear()
        for (const asker of stranded) {
            asker.failed(new ConnectionFault('RESPONSE_LOST', why))
        }
    }

    return {
        ask(type: string, payload?: unknown): Promise<Outcome> {
            if (!open) {
                return Promise.reject(new ConnectionFault('NOT_SUBMITTED',
                    `this connection is closed; "${type}" was not sent`))
            }
            issued += 1
            const id = String(issued)
            return new Promise<Outcome>((answered, failed) => {
                waiting.set(id, { answered, failed })
                try {
                    socket.send(JSON.stringify(asking(id, type, payload)))
                } catch (trouble) {
                    // Nothing left the process, so nothing will answer this id.
                    waiting.delete(id)
                    failed(new ConnectionFault('NOT_SUBMITTED', 'socket refused the frame before submission'))
                }
            })
        },

        close(): void {
            strand('this connection was closed before this frame was answered')
            socket.close()
        },
    }
}

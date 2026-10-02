/**
 * The other direction of a socket: the server asks and this client answers.
 *
 * <p>`connection.ts` is a client asking a server. The file channel is the reverse —
 * `FileChannelHandler.ask` sends a `FileRequest` and waits thirty seconds for a
 * `FileReply` with the same id — so it gets its own small loop rather than a mode
 * on `connect`. No envelope and no protocol version: the file channel predates the
 * envelope and the server reads bare `FileReply` JSON.
 *
 * <p>What answers is injected, which keeps this file neutral: the disk is
 * `view/files/enforcer.ts`'s, and a browser client would inject something else.
 */
import type { Socket } from './connection.ts'
import { REPLY_UNAVAILABLE, requestIn } from './files.ts'
import type { FileReply, FileRequest } from './files.ts'

export type Answering = (request: FileRequest) => Promise<FileReply>

/** How a socket closed, as far as the event says. A refused claim is 1003 and a reason. */
export interface Closing {
    readonly code?: number
    readonly reason?: string
}

export interface Serving {
    close(): void
}

export interface ServeOptions {
    readonly socket: Socket
    readonly answer: Answering
    readonly onClose?: (closing: Closing) => void
    /**
     * The server's word that the claim on the upgrade is in force — the one
     * frame on this socket that is not a request, sent only to a client that
     * asked with `ready=1` (`FileChannelHandler.READY_PARAM`). Heard at most
     * once. `project` is what the session roots now, absent when the server
     * could not read the claim as a place and so roots nothing for it.
     *
     * <p>Why it exists: `open` fires when the 101 arrives, and the server
     * declares the claim after sending it. A client that asked who answers on
     * `open` could be answered before its own definitions counted.
     */
    readonly onReady?: (project: string | undefined) => void
}

export function serve(options: ServeOptions): Serving {
    const { socket, answer } = options
    let open = true
    let ready = false
    socket.addEventListener('message', (event) => {
        const landed = readyIn(event.data)
        if (landed !== undefined) {
            if (!ready) {
                ready = true
                options.onReady?.(landed.project)
            }
            return
        }
        const request = requestIn(event.data)
        if (request === undefined) {
            // Nothing to answer: a reply needs the id the server is waiting under,
            // and the server's own deadline is what ends that wait.
            return
        }
        void answering(request)
    })
    socket.addEventListener('close', (event) => {
        open = false
        options.onClose?.(closingIn(event))
    })

    async function answering(request: FileRequest): Promise<void> {
        let reply: FileReply
        try {
            reply = await answer(request)
        } catch (trouble) {
            // UNAVAILABLE AND NOT SILENCE. A request left unanswered costs a run
            // thirty seconds and then a sentence about a wedged client; this one
            // arrives now and says what broke.
            reply = {
                id: request.id,
                outcome: REPLY_UNAVAILABLE,
                sentence: 'this client failed while answering: '
                    + (trouble instanceof Error ? trouble.message : String(trouble)),
            }
        }
        if (!open) {
            return
        }
        try {
            socket.send(JSON.stringify(reply))
        } catch {
            // Closing underneath; the close listener has the rest of the story.
        }
    }

    return {
        close(): void {
            open = false
            socket.close()
        },
    }
}

/**
 * `{"ready":true,"project":"<name>"}` read off a frame, or nothing for any other.
 *
 * <p>Told apart from a request by shape: a request always carries an `id` and
 * this never does. `requestIn` drops a frame with no id anyway, so a ready frame
 * reaching it would have been harmless; this is what makes it heard.
 */
export function readyIn(data: unknown): { readonly project?: string } | undefined {
    if (typeof data !== 'string') {
        return undefined
    }
    let parsed: unknown
    try {
        parsed = JSON.parse(data)
    } catch {
        return undefined
    }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        return undefined
    }
    const fields = parsed as Record<string, unknown>
    if (fields['ready'] !== true || 'id' in fields) {
        return undefined
    }
    const project = fields['project']
    return typeof project === 'string' && project !== '' ? { project } : {}
}

/** `code` and a non-empty `reason` off whatever the platform's close event is. */
export function closingIn(event: unknown): Closing {
    if (typeof event !== 'object' || event === null) {
        return {}
    }
    const fields = event as Record<string, unknown>
    const code = fields['code']
    const reason = fields['reason']
    return {
        ...(typeof code === 'number' ? { code } : {}),
        ...(typeof reason === 'string' && reason !== '' ? { reason } : {}),
    }
}

import { describe, expect, it } from 'vitest'
import { closingIn, serve } from './channel.ts'
import type { Closing } from './channel.ts'
import type { Arrival, Socket } from './connection.ts'
import type { FileReply, FileRequest } from './files.ts'

class FakeSocket implements Socket {
    readonly sent: string[] = []
    closed = false
    private messaged: ((event: Arrival) => void) | undefined
    private parted: ((event: unknown) => void) | undefined
    send(frame: string): void {
        this.sent.push(frame)
    }
    close(): void {
        this.closed = true
    }
    addEventListener(type: 'message', listener: (event: Arrival) => void): void
    addEventListener(type: 'close', listener: (event: unknown) => void): void
    addEventListener(type: string, listener: (event: Arrival) => void): void {
        if (type === 'message') {
            this.messaged = listener
        } else if (type === 'close') {
            // The implementation signature above types every listener as
            // `(event: Arrival) => void`, because that is the only shape one
            // signature can name for both overloads (Arrival is assignable to
            // unknown, so the 'close' overload is still compatible with it).
            // The 'close' branch's real listener takes `unknown`, which this
            // one cast makes exact again for `part` below.
            this.parted = listener as unknown as (event: unknown) => void
        }
    }
    deliver(data: unknown): void {
        this.messaged?.({ data })
    }
    part(event: unknown): void {
        this.parted?.(event)
    }
}

async function settle(): Promise<void> {
    for (let turn = 0; turn < 5; turn += 1) {
        await Promise.resolve()
    }
}

describe('a client serving its files', () => {
    it('answers each request with the reply its enforcer gave, under the same id', async () => {
        const socket = new FakeSocket()
        const asked: FileRequest[] = []
        serve({
            socket,
            answer: (request) => {
                asked.push(request)
                return Promise.resolve({ id: request.id, outcome: 'ok', paths: ['/srv/x'] })
            },
        })

        socket.deliver(JSON.stringify({ id: 'r1', op: 'roots' }))
        await settle()

        expect(asked).toEqual([{ id: 'r1', op: 'roots' }])
        expect(JSON.parse(socket.sent[0] ?? '{}')).toEqual(
            { id: 'r1', outcome: 'ok', paths: ['/srv/x'] })
    })

    it('answers unavailable, not silence, when its enforcer throws', async () => {
        const socket = new FakeSocket()
        serve({ socket, answer: () => Promise.reject(new Error('disk on fire')) })

        socket.deliver(JSON.stringify({ id: 'r2', op: 'read', path: 'a' }))
        await settle()

        const reply = JSON.parse(socket.sent[0] ?? '{}') as FileReply
        expect(reply.outcome).toBe('unavailable')
        expect(reply.sentence).toContain('disk on fire')
    })

    it('drops a frame with no id rather than inventing one to answer', async () => {
        const socket = new FakeSocket()
        serve({ socket, answer: () => Promise.reject(new Error('never asked')) })

        socket.deliver('{"op":"roots"}')
        socket.deliver('nonsense')
        await settle()

        expect(socket.sent).toEqual([])
    })

    it('hears the claim land, once, and answers nothing for it', async () => {
        const socket = new FakeSocket()
        const landed: (string | undefined)[] = []
        serve({
            socket,
            answer: () => Promise.reject(new Error('never asked')),
            onReady: (project) => landed.push(project),
        })

        socket.deliver('{"ready":true,"project":"ledger"}')
        socket.deliver('{"ready":true}')
        await settle()

        expect(landed).toEqual(['ledger'])
        expect(socket.sent).toEqual([])
    })

    it('says how the server closed it, which is where a refused claim is explained', () => {
        const socket = new FakeSocket()
        const heard: Closing[] = []
        serve({ socket, answer: () => Promise.reject(new Error('x')), onClose: (c) => heard.push(c) })

        socket.part({ code: 1003, reason: 'bench.local/srv/ledger/ledger already roots it' })

        expect(heard).toEqual([{ code: 1003, reason: 'bench.local/srv/ledger/ledger already roots it' }])
    })

    it('reads nothing into a close event that carries nothing', () => {
        expect(closingIn(undefined)).toEqual({})
        expect(closingIn({ code: 1000, reason: '' })).toEqual({ code: 1000 })
    })
})

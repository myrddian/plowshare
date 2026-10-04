import { describe, expect, it } from 'vitest'
import { connect } from './connection.ts'
import type { Arrival, Connection, Socket } from './connection.ts'
import { CURRENT_VERSION } from './envelope.ts'
import type { Envelope } from './envelope.ts'

/**
 * The wire, driven against a fake socket.
 *
 * <b>The fake below implements the whole of `Socket`, and that is the point of
 * `Socket` being ours.</b> It is four members, so this class is four members;
 * had the binding named the platform's `WebSocket` instead, a fake would have
 * had to satisfy `binaryType`, `bufferedAmount`, `extensions`, `protocol`,
 * `readyState`, four `on*` properties, `dispatchEvent`, `removeEventListener`
 * and the rest — none of which this module calls, and every one of which would
 * have had to be invented here to get a test to compile. See `connection.ts`
 * for why the interface is declared rather than imported; this file is the half
 * of that argument you can run.
 */

/** A socket that records what was sent and hands frames back on demand. */
class FakeSocket implements Socket {
    readonly sent: string[] = []
    closed = false
    private messaged: ((event: Arrival) => void) | undefined
    private parted: ((event: Arrival) => void) | undefined

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
            this.parted = listener
        }
    }

    /** One frame arriving from the server, as text, the way the wire does it. */
    deliver(frame: unknown): void {
        this.messaged?.({ data: typeof frame === 'string' ? frame : JSON.stringify(frame) })
    }

    /** The server closing the socket — §3.4's answer to a client that will not drain. */
    part(): void {
        this.parted?.({ data: null })
    }

    /** The envelope this client sent, parsed, in the order it sent them. */
    frame(at: number): Envelope {
        return JSON.parse(this.sent[at] ?? '{}') as Envelope
    }
}

/** A response frame: the envelope of §3.1 carrying the outcome of §3.3. */
function answering(id: string, type: string, outcome: Record<string, unknown>): unknown {
    return { id, type, protocol_version: CURRENT_VERSION, payload: outcome }
}

/** A bare `JobEvent`, which is what `/v1/events` publishes: no envelope at all. */
function event(job: string, kind: string): unknown {
    return { job, kind, agent: 'scribe' }
}

/** Long enough for a resolution that has already happened to have happened. */
async function settle(): Promise<void> {
    await Promise.resolve()
    await Promise.resolve()
}

/** Whether a promise has settled yet, without waiting on one that has not. */
function watching<T>(promise: Promise<T>): { settled: boolean } {
    const seen = { settled: false }
    promise.then(() => { seen.settled = true }, () => { seen.settled = true })
    return seen
}

function opened(): { socket: FakeSocket; connection: Connection; pushes: unknown[] } {
    const socket = new FakeSocket()
    const pushes: unknown[] = []
    const connection = connect({ socket, onPush: (push) => { pushes.push(push) } })
    return { socket, connection, pushes }
}

describe('what this client puts on the wire', () => {
    it('sends an envelope naming the version, the type and an id of its own', () => {
        const { socket, connection } = opened()

        void connection.ask('conversation.open', { project: 'plowshare' })

        const frame = socket.frame(0)
        expect(frame.type).toBe('conversation.open')
        expect(frame.protocol_version).toBe('plowshare-v1')
        expect(frame.payload).toEqual({ project: 'plowshare' })
        expect(typeof frame.id).toBe('string')
        expect(frame.id.length).toBeGreaterThan(0)
    })

    it('gives every frame on one socket an id of its own', () => {
        const { socket, connection } = opened()

        void connection.ask('job.status', { job: 'a' })
        void connection.ask('job.status', { job: 'b' })

        expect(socket.frame(0).id).not.toBe(socket.frame(1).id)
    })
})

describe('an answer is correlated to the question that asked it', () => {
    it('answers each of two frames in flight with its own outcome', async () => {
        const { socket, connection } = opened()

        const first = connection.ask('conversation.open', {})
        const second = connection.ask('job.status', { job: 'j-1' })

        // Answered in the reverse of the order they were asked, because nothing
        // on this socket promises an order and §3.5 says so.
        socket.deliver(answering(socket.frame(1).id, 'job.status',
            { code: 'OK', payload: { ending: 'answered' } }))
        socket.deliver(answering(socket.frame(0).id, 'conversation.open',
            { code: 'CREATED', payload: { conversation: 'c-1' } }))

        expect(await first).toEqual({ code: 'CREATED', payload: { conversation: 'c-1' } })
        expect(await second).toEqual({ code: 'OK', payload: { ending: 'answered' } })
    })

    it('leaves an ask waiting when an answer names an id it never sent', async () => {
        const { socket, connection } = opened()
        const answer = watching(connection.ask('job.status', { job: 'j-1' }))

        socket.deliver(answering('an-id-this-client-never-issued', 'job.status', { code: 'OK' }))
        await settle()

        expect(answer.settled).toBe(false)
    })
})

describe('responses are enveloped and pushes are not', () => {
    it('hands a bare JobEvent to the listener and leaves the ask outstanding', async () => {
        const { socket, connection, pushes } = opened()
        const answer = watching(connection.ask('agent.run', { agent: 'scribe' }))

        socket.deliver(event('j-1', 'started'))
        await settle()

        expect(pushes).toEqual([{ job: 'j-1', kind: 'started', agent: 'scribe' }])
        expect(answer.settled).toBe(false)
    })

    it('reads a push as a push even when it carries an id of its own', async () => {
        // The discrimination is `protocol_version` and NOT `id`, and this is the
        // case that tells the two rules apart — without it, a client that keyed
        // off `id` would pass every other case in this file. A bare frame has no
        // envelope for §3.1's "server pushes carry none" to be true of, so a
        // `JobEvent` that grows an `id` field would become an answer to a request
        // nobody made. `isAnswer`'s comment is the other half of this case.
        const { socket, connection, pushes } = opened()
        const answer = watching(connection.ask('agent.run', { agent: 'scribe' }))
        const id = socket.frame(0).id

        socket.deliver({ id, job: 'j-1', kind: 'started' })
        await settle()

        expect(pushes).toEqual([{ id, job: 'j-1', kind: 'started' }])
        expect(answer.settled).toBe(false)
    })

    // §3.5, and the reason this is two cases rather than one: a client that
    // works on the order its own fake happens to emit is a client that fails on
    // the other one, in production, on the day a handler publishes before it
    // returns instead of after.
    it('tolerates the event arriving before the answer its request caused', async () => {
        const { socket, connection, pushes } = opened()
        const answer = connection.ask('agent.run', { agent: 'scribe' })
        const id = socket.frame(0).id

        socket.deliver(event('j-1', 'started'))
        socket.deliver(answering(id, 'agent.run', { code: 'ACCEPTED', payload: { job: 'j-1' } }))

        expect(await answer).toEqual({ code: 'ACCEPTED', payload: { job: 'j-1' } })
        expect(pushes).toHaveLength(1)
    })

    it('tolerates the event arriving after the answer its request caused', async () => {
        const { socket, connection, pushes } = opened()
        const answer = connection.ask('agent.run', { agent: 'scribe' })
        const id = socket.frame(0).id

        socket.deliver(answering(id, 'agent.run', { code: 'ACCEPTED', payload: { job: 'j-1' } }))
        socket.deliver(event('j-1', 'started'))

        expect(await answer).toEqual({ code: 'ACCEPTED', payload: { job: 'j-1' } })
        expect(pushes).toHaveLength(1)
    })
})

describe('the envelope is a handshake', () => {
    it('refuses an answer naming a version this build does not speak', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('job.status', { job: 'j-1' })

        socket.deliver({
            id: socket.frame(0).id,
            type: 'job.status',
            protocol_version: 'plowshare-v2',
            payload: { code: 'OK' },
        })

        await expect(answer).rejects.toThrow(/plowshare-v1/)
        await expect(answer).rejects.toThrow(/plowshare-v2/)
    })

    it('refuses an answer whose code is not in the vocabulary', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('job.status', { job: 'j-1' })

        socket.deliver(answering(socket.frame(0).id, 'job.status', { code: 'IM_A_TEAPOT' }))

        await expect(answer).rejects.toThrow(/IM_A_TEAPOT/)
    })

    it('ignores payload fields it does not know, because the payload is data', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('job.status', { job: 'j-1' })

        socket.deliver(answering(socket.frame(0).id, 'job.status',
            { code: 'OK', seq: 7, mac: 'nope' }))

        expect(await answer).toEqual({ code: 'OK' })
    })
})

describe('said is absent when the server said nothing', () => {
    it('carries no said key at all through the round trip', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('job.status', { job: 'j-1' })

        socket.deliver(answering(socket.frame(0).id, 'job.status', { code: 'OK' }))
        const outcome = await answer

        // Not `''`, and not a key holding undefined: a client's own sentence for
        // a code is correct only when the server truly said nothing, and
        // `'said' in outcome` is the question the client design's §5.2 asks.
        expect('said' in outcome).toBe(false)
        expect(Object.keys(outcome)).toEqual(['code'])
    })

    it('keeps the sentence the server did send, rather than guessing by code', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('conversation.open', {})

        socket.deliver(answering(socket.frame(0).id, 'conversation.open',
            { code: 'CONFLICT', said: 'that conversation is already open elsewhere' }))

        expect(await answer).toEqual({
            code: 'CONFLICT',
            said: 'that conversation is already open elsewhere',
        })
    })

    it('drops a said sent as null rather than reading it as a sentence', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('job.status', { job: 'j-1' })

        socket.deliver(answering(socket.frame(0).id, 'job.status', { code: 'OK', said: null }))

        expect('said' in await answer).toBe(false)
    })
})

describe('the successes are not collapsed', () => {
    it('answers ACCEPTED as ACCEPTED, which is a handle to poll and not a result', async () => {
        const { socket, connection } = opened()
        const answer = connection.ask('agent.run', { agent: 'scribe' })

        socket.deliver(answering(socket.frame(0).id, 'agent.run',
            { code: 'ACCEPTED', payload: { job: 'j-1' } }))
        const outcome = await answer

        expect(outcome.code).toBe('ACCEPTED')
        expect(outcome.code).not.toBe('OK')
    })

    it('keeps CREATED and NO_CONTENT apart from OK as well', async () => {
        const { socket, connection } = opened()
        const created = connection.ask('agent.define', {})
        const emptied = connection.ask('memory.invalidate', {})

        socket.deliver(answering(socket.frame(0).id, 'agent.define', { code: 'CREATED' }))
        socket.deliver(answering(socket.frame(1).id, 'memory.invalidate', { code: 'NO_CONTENT' }))

        expect((await created).code).toBe('CREATED')
        expect((await emptied).code).toBe('NO_CONTENT')
    })
})

describe('a closed socket fails what it can no longer answer', () => {
    // The server closes rather than drops when it cannot queue a response
    // (EventChannelHandler: a dropped answer is "an id that is never resolved
    // and a caller that never fails"). A client that does not fail its
    // outstanding asks on that close is that caller, so this is the other half
    // of the same contract.
    it('fails every outstanding ask when the server closes the socket', async () => {
        const { socket, connection } = opened()
        const first = connection.ask('job.status', { job: 'a' })
        const second = connection.ask('job.status', { job: 'b' })

        socket.part()

        await expect(first).rejects.toThrow(/closed/)
        await expect(second).rejects.toThrow(/closed/)
    })

    it('refuses to ask over a connection this client has closed', async () => {
        const { socket, connection } = opened()

        connection.close()

        expect(socket.closed).toBe(true)
        await expect(connection.ask('job.status', {})).rejects.toThrow(/closed/)
    })

    // AND THE HALF THAT AN OUTSTANDING ASK CANNOT COVER, which a whole-plan
    // review found missing. `agent.run` answers in milliseconds and the run is
    // minutes, every one of them with no frame outstanding at all — so a close
    // in that window fails nothing, because nothing is waiting. `onClose` is
    // the signal for a caller that is following pushes rather than awaiting an
    // answer, and `view/main.ts`'s follow-wait is the caller in question.
    it('tells a listener the socket went away, even with nothing outstanding', () => {
        const socket = new FakeSocket()
        const closes: number[] = []
        connect({ socket, onClose: () => { closes.push(1) } })

        socket.part()

        expect(closes).toHaveLength(1)
    })

    it('has already failed every ask by the time it says the socket went away', async () => {
        const socket = new FakeSocket()
        const seen: { refused?: string } = {}
        const outstanding: Promise<unknown>[] = []
        const connection = connect({
            socket,
            onClose: () => {
                // What a listener finds when it gets here, which is the whole
                // of what "after the strand" is a claim about: the connection
                // is shut, so nothing this listener does can start a new wait
                // on a socket that is gone. Reorder the two lines in
                // `connection.ts` and this ask resolves into a map nobody will
                // ever answer.
                outstanding.push(connection.ask('job.status', {})
                    .catch((trouble: Error) => { seen.refused = trouble.message }))
            },
        })
        const asked = connection.ask('job.status', { job: 'a' })

        socket.part()
        await settle()
        await Promise.all(outstanding)

        await expect(asked).rejects.toThrow(/socket closed/)
        expect(seen.refused).toMatch(/this connection is closed/)
    })

    it('says nothing to a listener when this client is the one hanging up', () => {
        const socket = new FakeSocket()
        const closes: number[] = []
        const connection = connect({ socket, onClose: () => { closes.push(1) } })

        connection.close()

        // A caller that hung up knows it hung up. Reporting it would turn
        // every ordinary end of a session into a lost connection — and
        // `main.ts`'s `finally` closes this way on every clean exit.
        expect(closes).toEqual([])
    })
})

describe('what cannot be read is not mistaken for an answer', () => {
    it('drops text that is not JSON rather than failing an unrelated ask', async () => {
        const { socket, connection, pushes } = opened()
        const answer = watching(connection.ask('job.status', { job: 'j-1' }))

        socket.deliver('not json at all')
        await settle()

        expect(answer.settled).toBe(false)
        expect(pushes).toEqual([])
    })
})


it('delivers revisioned enveloped notifications without consuming an outstanding reply', async () => {
    const socket = new FakeSocket(), pushes: unknown[] = [];
    const connection = connect({socket, onPush: frame => pushes.push(frame)});
    const pending = connection.ask('usage.models', {}), asked = socket.frame(0);
    const update = {id:null,type:'usage.updated',protocol_version:CURRENT_VERSION,payload:{subscription:'sub',revision:2}};
    socket.deliver(update);
    socket.deliver({...update,protocol_version:'unrecognized'});
    socket.deliver({...update,type:'usage.closed'});
    expect(pushes).toEqual([update,{...update,type:'usage.closed'}]);
    socket.deliver(answering(asked.id,asked.type,{code:'OK',payload:{report:'reply'}}));
    expect(await pending).toEqual({code:'OK',payload:{report:'reply'}});
    connection.close();
});

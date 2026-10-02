import { describe, expect, it } from 'vitest'
import type { Claim } from 'plowshare-client-ts/binding/auth'
import type { Closing } from 'plowshare-client-ts/binding/channel'
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection'
import { rooter, sameClaim } from './rooter.ts'

class FakeSocket implements Socket {
    readonly sent: string[] = []
    closed = false
    private messaged: ((event: Arrival) => void) | undefined
    private parted: ((event: unknown) => void) | undefined
    private readonly order: string[]
    private readonly label: string
    constructor(order: string[], label: string) {
        this.order = order
        this.label = label
    }
    send(frame: string): void {
        this.sent.push(frame)
    }
    close(): void {
        this.closed = true
        this.order.push(`closed ${this.label}`)
        // A real socket's close event is asynchronous, and the rooter must wait for it.
        setTimeout(() => this.parted?.({ code: 1000 }), 1)
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
            // one cast makes exact again for `refuse` and `close` below.
            this.parted = listener as unknown as (event: unknown) => void
        }
    }
    deliver(data: string): void {
        this.messaged?.({ data })
    }
    refuse(reason: string): void {
        this.parted?.({ code: 1003, reason })
    }
}

const LEDGER: Claim = { project: 'ledger', machine: 'bench', root: '/u/ledger' }
const NOTES: Claim = { project: 'notes', machine: 'bench', root: '/u/notes' }

/**
 * @param landing whether each socket says its claim landed, a tick after it
 *     opens — as the server does once it has declared it. Off for the tests
 *     about what happens before, or instead.
 */
function world(landing = true): {
    order: string[]
    sockets: FakeSocket[]
    lost: [Claim, Closing][]
    served: string[]
    held: ReturnType<typeof rooter>
} {
    const order: string[] = []
    const sockets: FakeSocket[] = []
    const lost: [Claim, Closing][] = []
    const served: string[] = []
    const held = rooter({
        open: (claim) => {
            order.push(`opened ${claim.project}`)
            const socket = new FakeSocket(order, claim.project)
            sockets.push(socket)
            if (landing) {
                setTimeout(() => socket.deliver(JSON.stringify({ ready: true, project: claim.project })), 1)
            }
            return Promise.resolve(socket)
        },
        answering: (root) => (request) => {
            served.push(root)
            return Promise.resolve({ id: request.id, outcome: 'ok', paths: [root] })
        },
        onLost: (claim, closing) => lost.push([claim, closing]),
        patience: 50,
        readiness: 80,
    })
    return { order, sockets, lost, served, held }
}

describe('a rooter waiting for its claim to land', () => {
    it('refuses an acknowledgement that names another project or no project', async () => {
        for (const project of ['other', undefined]) {
            const { sockets, held, lost } = world(false)
            const rooting = held.root(LEDGER)
            const refused = expect(rooting).rejects.toThrow('requested project')
            await new Promise(waited => setTimeout(waited, 5))
            sockets[0]?.deliver(JSON.stringify({ ready: true, project }))
            await refused
            expect(held.current()).toBeUndefined()
            expect(lost).toEqual([])
        }
    })
    it('does not call a root done until the server says the claim is in force', async () => {
        const { sockets, held } = world(false)
        let done = false
        const rooting = held.root(LEDGER).then(() => {
            done = true
        })
        await new Promise((waited) => setTimeout(waited, 20))
        expect(done).toBe(false)

        sockets[0]?.deliver(JSON.stringify({ ready: true, project: 'ledger' }))
        await rooting

        expect(done).toBe(true)
        expect(held.current()).toEqual(LEDGER)
    })

    it('refuses the root at once when the socket closes before the claim lands', async () => {
        const { sockets, held, lost } = world(false)
        const rooting = held.root(LEDGER)
        await new Promise((waited) => setTimeout(waited, 5))

        sockets[0]?.refuse('desk.local/srv/ledger/ledger already roots ledger')

        await expect(rooting).rejects.toThrow('desk.local/srv/ledger/ledger already roots ledger')
        expect(held.current()).toBeUndefined()
        // Said once, by whoever awaited the root — not a second time as a loss.
        expect(lost).toEqual([])
    })

    it('goes on as before when the server never says, rather than waiting forever', async () => {
        const { held } = world(false)
        const started = Date.now()
        await held.root(LEDGER)

        expect(Date.now() - started).toBeGreaterThanOrEqual(70)
        expect(held.current()).toEqual(LEDGER)
    })
})

describe('a rooter', () => {
    it('serves the root it was given, on the socket it opened for it', async () => {
        const { sockets, served, held } = world()
        await held.root(LEDGER)
        sockets[0]?.deliver(JSON.stringify({ id: 'r1', op: 'roots' }))
        await new Promise((done) => setTimeout(done, 5))

        expect(held.current()).toEqual(LEDGER)
        expect(served).toEqual(['/u/ledger'])
    })

    it('closes the old rooting, and waits for it, before opening the new one', async () => {
        const { order, held, lost } = world()
        await held.root(LEDGER)
        await held.root(NOTES)

        expect(order).toEqual(['opened ledger', 'closed ledger', 'opened notes'])
        expect(held.current()).toEqual(NOTES)
        expect(lost).toEqual([])
    })

    it('reports a close it did not ask for, and holds nothing from then on', async () => {
        const { sockets, held, lost } = world()
        await held.root(LEDGER)

        sockets[0]?.refuse('bench.local/u/ledger/ledger already roots it')

        expect(lost).toEqual([[LEDGER, { code: 1003, reason: 'bench.local/u/ledger/ledger already roots it' }]])
        expect(held.current()).toBeUndefined()
    })

    it('releases quietly', async () => {
        const { sockets, held, lost } = world()
        await held.root(LEDGER)
        await held.release()

        expect(sockets[0]?.closed).toBe(true)
        expect(held.current()).toBeUndefined()
        expect(lost).toEqual([])
    })

    it('compares claims by all three parts', () => {
        expect(sameClaim(LEDGER, { ...LEDGER })).toBe(true)
        expect(sameClaim(LEDGER, { ...LEDGER, root: '/elsewhere' })).toBe(false)
        expect(sameClaim(undefined, LEDGER)).toBe(false)
    })
})

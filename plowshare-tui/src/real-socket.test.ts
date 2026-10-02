import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import type { Server } from 'node:http'
import type { Socket as Stream } from 'node:net'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import { connect } from 'plowshare-client-ts/binding/connection'
import type { Socket } from 'plowshare-client-ts/binding/connection'
import { CURRENT_VERSION } from 'plowshare-client-ts/binding/envelope'

/**
 * <b>The claim task 4 made and could not run: a real `WebSocket` is a
 * {@link Socket}.</b>
 *
 * <p>`connection.ts` declares a four-member structural interface and says a
 * real `WebSocket` satisfies it. Task 4's own report is candid that the
 * assignment "is reasoning, not measurement" — it never had one. This task is
 * the first with a real socket in reach, so this file drives `connect()` over
 * an actual `new WebSocket(...)` before anything is built on top of it.
 *
 * <h2>Two halves, because neither is the whole claim</h2>
 *
 * <p>`src/platform/fits.ts` is the <b>type</b> half: it assigns
 * `(url) => new WebSocket(url)` to `(url: string) => Socket` under `lib: DOM`,
 * which is the WHATWG declaration Node's global implements, and `tsc -b` either
 * accepts it or does not. This file is the <b>runtime</b> half, on whatever Node
 * is actually running the suite: the socket really connects, `send` really puts
 * a frame on a wire, and the `message` listener really receives one — with
 * `event.data` a string, which is the single assumption in `connection.ts`'s
 * `arrived` that a fake can confirm only by construction.
 *
 * <p>The type half was measured by breaking it: a `drain(): void` planted in
 * {@link Socket} failed as `TS2741: Property 'drain' is missing in type
 * 'WebSocket'`, naming `fits.ts`, and was removed. A check that has never
 * refused anything proves nothing.
 *
 * <h2>Which Node, and what this module assumes</h2>
 *
 * <p><b>`WebSocket` is a global from Node 22.</b> Node 21 had it behind
 * `--experimental-websocket`; Node 20 and earlier have nothing to reach for, and
 * this project will not take a dependency to fill that gap — the client design's
 * §6 is why, and `package.json` carries `dependencies` not at all. So
 * `package.json` now names `engines.node >= 22`, which is the module writing
 * down what it needs instead of a developer discovering it as
 * `ReferenceError: WebSocket is not defined`. The assertion below fails with
 * that sentence rather than with a stack trace.
 *
 * <p><b>`binding/` itself needs none of this</b>, and that is the point of the
 * injection: `auth.ts` takes an opener, so nothing under `binding/` names
 * `WebSocket` and `types: []` stays intact. The Node floor is a requirement of
 * whoever <i>calls</i> the opener — the view, task 7 — and {@link opening}
 * below is the six lines it will need, proven here rather than left as advice.
 *
 * <h2>The server is hand-rolled, and it is forty lines because of the
 * zero-dependency rule</h2>
 *
 * <p>Node ships a WebSocket client and no WebSocket server, and `ws` is a
 * dependency this module will not take for a test. What a server needs for this
 * exchange is small and entirely in RFC 6455 §1.3 and §5: a SHA-1 of the
 * client's key against the protocol's fixed GUID for the handshake, an unmask
 * for the frames a client sends (always masked), and a two-byte header for the
 * ones it sends back (never masked). Nothing here is a general implementation —
 * no fragmentation, no ping, no binary — and it does not need to be: it exists
 * so that the client under test is the real one.
 *
 * <p>This file lives in `src/` rather than `binding/` for the reason
 * `neutrality.test.ts` and `mirrors-the-server.test.ts` do: it imports
 * `node:http`, and `src/binding/tsconfig.json` includes its own tests under
 * `types: []`, so the same import in there would compile only by installing
 * `@types/node` — the edit the injected-means-structural ruling exists to
 * prevent.
 */

/** RFC 6455 §1.3: the constant a server hashes the client's key against. */
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

/** One unmasked text frame, header and payload, for a payload under 64 KiB. */
function textFrame(text: string): Buffer {
    const payload = Buffer.from(text, 'utf8')
    if (payload.length < 126) {
        return Buffer.concat([Buffer.from([0x81, payload.length]), payload])
    }
    const header = Buffer.alloc(4)
    header[0] = 0x81
    header[1] = 126
    header.writeUInt16BE(payload.length, 2)
    return Buffer.concat([header, payload])
}

/** Whole text messages read off a stream, and the bytes of the next one. */
function read(buffered: Buffer): { messages: string[]; rest: Buffer } {
    const messages: string[] = []
    let rest = buffered
    for (;;) {
        if (rest.length < 2) {
            return { messages, rest }
        }
        const opcode = (rest[0] ?? 0) & 0x0f
        const flagged = rest[1] ?? 0
        const masked = (flagged & 0x80) !== 0
        let length = flagged & 0x7f
        let at = 2
        if (length === 126) {
            if (rest.length < 4) {
                return { messages, rest }
            }
            length = rest.readUInt16BE(2)
            at = 4
        }
        const mask = rest.subarray(at, masked ? at + 4 : at)
        at += masked ? 4 : 0
        if (rest.length < at + length) {
            return { messages, rest }
        }
        const payload = Buffer.from(rest.subarray(at, at + length))
        if (masked) {
            for (let index = 0; index < payload.length; index += 1) {
                payload[index] = (payload[index] ?? 0) ^ (mask[index % 4] ?? 0)
            }
        }
        rest = rest.subarray(at + length)
        if (opcode === 0x1) {
            messages.push(payload.toString('utf8'))
        }
        if (opcode === 0x8) {
            return { messages, rest: Buffer.alloc(0) }
        }
    }
}

/** What the server did with each frame, so a test can assert on the wire. */
const received: string[] = []

/**
 * Every upgraded stream, kept so `afterAll` can destroy them.
 *
 * `server.close()` waits for open connections and an upgraded one never ends on
 * its own, so without this the hook times out at ten seconds with four green
 * tests behind it — measured, and the reason this array exists rather than a
 * bare `close()`.
 */
const upgraded: Stream[] = []

let server: Server
let origin = ''

beforeAll(async () => {
    server = createServer()
    server.on('upgrade', (request, stream: Stream) => {
        upgraded.push(stream)
        const key = String(request.headers['sec-websocket-key'] ?? '')
        const accept = createHash('sha1').update(key + GUID).digest('base64')
        stream.write('HTTP/1.1 101 Switching Protocols\r\n'
            + 'Upgrade: websocket\r\nConnection: Upgrade\r\n'
            + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`)
        let buffered = Buffer.alloc(0)
        stream.on('data', (chunk: Buffer) => {
            buffered = Buffer.concat([buffered, chunk])
            const { messages, rest } = read(buffered)
            buffered = rest
            for (const message of messages) {
                received.push(message)
                const asked = JSON.parse(message) as { id: string; type: string }
                stream.write(textFrame(JSON.stringify({
                    id: asked.id,
                    type: asked.type,
                    protocol_version: CURRENT_VERSION,
                    payload: { code: 'OK', said: `answered ${asked.type}` },
                })))
            }
        })
    })
    await new Promise<void>((listening) => {
        server.listen(0, '127.0.0.1', listening)
    })
    const bound = server.address()
    origin = typeof bound === 'object' && bound !== null ? `ws://127.0.0.1:${bound.port}` : ''
})

afterAll(async () => {
    for (const stream of upgraded) {
        stream.destroy()
    }
    await new Promise<void>((closed) => {
        server.close(() => closed())
    })
})

/**
 * <b>The opener the view will inject, in the six lines it takes.</b>
 *
 * It resolves when the socket is <i>open</i>, which is the contract
 * `auth.ts`'s `Opening` states and the reason that type is a promise: `Socket`
 * has no `open` subscription — the module never needed one — so waiting for
 * readiness belongs to whoever constructed the thing. A `send` before the
 * handshake finishes throws `InvalidStateError`, which is exactly the failure
 * this resolves before it can happen, and it is the kind of thing that works on
 * a fake and fails on a wire.
 */
function opening(url: string): Promise<Socket> {
    return new Promise((open, fail) => {
        const socket = new WebSocket(url)
        socket.addEventListener('open', () => open(socket))
        socket.addEventListener('error', () => fail(new Error(`could not open ${url}`)))
    })
}

describe('a real WebSocket is the Socket this binding declares', () => {
    it('is a global on this Node at all, which is Node 22 and later', () => {
        expect(typeof WebSocket,
            'this module needs Node 22 or later, where WebSocket is a global;'
            + ' Node 21 had it behind --experimental-websocket and Node 20 not at all')
            .toBe('function')
    })

    it('has the four members the interface names, on a real instance', async () => {
        const socket = await opening(origin)
        expect(typeof socket.send).toBe('function')
        expect(typeof socket.close).toBe('function')
        expect(typeof socket.addEventListener).toBe('function')
        socket.close()
    })

    it('carries a frame there and an answer back, through connect()', async () => {
        const socket = await opening(origin)
        const connection = connect({ socket })
        const outcome = await connection.ask('conversation.turns', { conversation: 'c1' })
        expect(outcome).toEqual({ code: 'OK', said: 'answered conversation.turns' })
        expect(JSON.parse(received.at(-1) ?? '{}')).toMatchObject({
            type: 'conversation.turns',
            protocol_version: CURRENT_VERSION,
            payload: { conversation: 'c1' },
        })
        connection.close()
    })

    it('delivers the frame as a string, which is what arrived() assumes', async () => {
        const socket = await opening(origin)
        const seen: unknown[] = []
        socket.addEventListener('message', (event) => {
            seen.push(event.data)
        })
        const connection = connect({ socket })
        await connection.ask('conversation.list')
        // The assumption `connection.ts` makes and a fake can only assert by
        // construction: a text frame arrives as a string, so the `typeof data
        // !== 'string'` guard there is about binary and nothing else. Node's
        // WebSocket hands text over as a string and a Blob only for binary.
        expect(seen.map((data) => typeof data)).toEqual(['string'])
        connection.close()
    })
})

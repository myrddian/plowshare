import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import {existsSync} from 'node:fs'
import {join} from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
const entry = process.env.PLOWSHARE_SDK_NODE_ENTRY ?? fileURLToPath(new URL('../sdk/node/build/sdk.js', import.meta.url))
const { connectPlowshare, requirePayload, Refusal, OPERATIONS } = await import(entry)
const resolve = createRequire(entry)
const binding=(resolve.resolve.paths('plowshare-client-ts')??[]).map(root=>join(root,'plowshare-client-ts/build/binding/connection.js')).find(existsSync)
if(!binding)throw new Error('Installed SDK wire binding is missing')
const { connect } = await import(pathToFileURL(binding).href)
const { default: WebSocket } = await import(pathToFileURL(resolve.resolve('ws')).href)
const fixture = JSON.parse(await readFile(process.env.PLOWSHARE_SDK_FIXTURES, 'utf8'))
const catalog = JSON.parse(await readFile(process.env.PLOWSHARE_SDK_CATALOG, 'utf8'))
assert.deepEqual(OPERATIONS, catalog.operations)
const origin = process.argv[2]
await assert.rejects(connectPlowshare({ origin: origin + '/wrong', token: 'sdk-fixture-token' }))
await assert.rejects(connectPlowshare({ origin, token: 'sdk-redirect-fixture', session: 'node-redirect', timeoutMs: 300 }))
/** Arbitrary conformance payloads exercise the wire codec, not public operation DTOs. */
async function wireClient(session, onPush) {
    const address = new URL('/v1/events', origin)
    address.protocol = address.protocol === 'https:' ? 'wss:' : 'ws:'
    address.searchParams.set('session', session)
    const socket = new WebSocket(address, { headers: { Authorization: 'Bearer sdk-fixture-token' }, followRedirects: false })
    await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject) })
    socket.on('error', () => {}) // Close rejects all outstanding requests.
    return connect({ socket, onPush, deadline: { milliseconds: 300, schedule: (expired, delay) => { const timer = setTimeout(expired, delay); return () => clearTimeout(timer) } } })
}
const pushes = []
const client = await wireClient('node', push => pushes.push(push))
try {
    const [one, two] = await Promise.all([client.request('project.list', { scenario: 'multiplex-one' }), client.request('project.list', { scenario: 'multiplex-two' })])
    assert.equal(one.outcome.payload.sequence, 1)
    assert.equal(two.outcome.payload.sequence, 2)
    for (const test of fixture.cases) {
        const call = client.request('project.list', { scenario: test.name })
        if (test.delivery) await assert.rejects(call, error => error.delivery === test.delivery)
        else {
            const reply = await call
            assert.deepEqual(reply.raw.payload, test.response)
            assert.equal(reply.raw.futureEnvelope, true)
        }
    }
    assert.equal(pushes.length, 2)
    await assert.rejects(client.request('project.list', {}), error => error.code === 'NOT_SUBMITTED')
} finally { client.close() }
let acknowledge
const submitted = new Promise(resolve => { acknowledge = resolve })
const cancelled = await wireClient('node-cancel', push => acknowledge(push))
const abandoned = cancelled.request('project.list', { scenario: 'cancel' })
const failed = assert.rejects(abandoned, error => error.delivery === 'UNKNOWN')
assert.equal((await submitted).kind, 'fixture-submitted')
cancelled.close()
await failed
// Public SDK verification uses real project DTOs, explicit refusals and checked pushes.
const typedPushes = [], faults = []
const typed = await connectPlowshare({ origin, token: 'sdk-fixture-token', session: 'node-typed', onPush: push => typedPushes.push(push), onPushFault: fault => faults.push(fault) })
try {
    await assert.rejects(typed.request('project.list', { scenario: 'unsupported' }))
    assert.deepEqual(requirePayload(await typed.request('project.list', {})), [])
    assert.deepEqual(typedPushes, [{ kind: 'inbox.changed', unread: 1 }])
    assert.equal(faults.length, 1)
    const refusal=await typed.request('project.list', {});
    assert.throws(() => requirePayload(refusal), Refusal)
    await assert.rejects(typed.request('project.list', {}), error => error.code === 'INVALID_ENVELOPE')
} finally { typed.close() }
console.log('Node SDK conformance passed')

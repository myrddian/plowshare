import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const { connectPlowshare, requirePayload, Refusal, OPERATIONS } = await import(process.env.PLOWSHARE_SDK_NODE_ENTRY ?? '../plowshare-client-node/build/sdk.js')
const fixture = JSON.parse(await readFile(process.env.PLOWSHARE_SDK_FIXTURES, 'utf8'))
const catalog = JSON.parse(await readFile(process.env.PLOWSHARE_SDK_CATALOG, 'utf8'))
assert.deepEqual(OPERATIONS, catalog.operations)
const origin = process.argv[2]
await assert.rejects(connectPlowshare({ origin: origin + '/wrong', token: 'sdk-fixture-token' }))
await assert.rejects(connectPlowshare({ origin, token: 'sdk-redirect-fixture', session: 'node-redirect', timeoutMs: 300 }))
const pushes = []
const client = await connectPlowshare({ origin, token: 'sdk-fixture-token', session: 'node', timeoutMs: 300, onPush: push => pushes.push(push) })
try {
    const [one, two] = await Promise.all([client.request('project.list', { scenario: 'multiplex-one' }), client.request('project.list', { scenario: 'multiplex-two' })])
    assert.equal(requirePayload(one).sequence, 1)
    assert.equal(requirePayload(two).sequence, 2)
    for (const test of fixture.cases) {
        const call = client.request('project.list', { scenario: test.name })
        if (test.delivery) await assert.rejects(call, error => error.delivery === test.delivery)
        else {
            const reply = await call
            assert.deepEqual(reply.raw.payload, test.response)
            assert.equal(reply.raw.futureEnvelope, true)
            if (test.name === 'success') assert.equal(requirePayload(reply).nullable, null)
            if (test.name === 'refusal') assert.throws(() => requirePayload(reply), Refusal)
        }
    }
    assert.equal(pushes.length, 2)
    await assert.rejects(client.request('project.list', {}), error => error.code === 'NOT_SUBMITTED')
} finally { client.close() }
let acknowledge
const submitted = new Promise(resolve => { acknowledge = resolve })
const cancelled = await connectPlowshare({ origin, token: 'sdk-fixture-token', session: 'node-cancel', onPush: push => acknowledge(push) })
const abandoned = cancelled.request('project.list', { scenario: 'cancel' })
const failed = assert.rejects(abandoned, error => error.delivery === 'UNKNOWN')
assert.equal((await submitted).kind, 'fixture-submitted')
cancelled.close()
await failed
console.log('Node SDK conformance passed')

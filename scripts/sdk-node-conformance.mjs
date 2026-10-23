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
        // Dispatch backpressure cases need the negotiated window and several requests;
        // they are driven below, over the public typed connection.
        if (test.capacity) continue
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
// A public typed connection must complete B while the fixture still holds A.
let firstArrived;
const arrived = new Promise(resolve => { firstArrived = resolve });
const concurrent = await connectPlowshare({origin,token:'sdk-fixture-token',session:'typed-node/multiplex',onPush:() => firstArrived()});
try {
    let firstDone = false;
    const first = concurrent.request('project.list',{}).then(reply => {firstDone = true; return reply});
    await arrived;
    const second = await concurrent.request('project.list',{});
    assert.equal(requirePayload(second)[0].name,'second');
    assert.equal(firstDone,false,'A completed before the explicit release request');
    assert.equal(requirePayload(await concurrent.request('project.list',{}))[0].name,'third');
    assert.equal(requirePayload(await first)[0].name,'first');
} finally { concurrent.close() }

let capacityReached;
const full = new Promise(resolve => {capacityReached = resolve});
const capacity = await connectPlowshare({origin,token:'sdk-fixture-token',session:'typed-node/capacity',timeoutMs:5000,
    onPush:push => {if(push.kind==='inbox.changed' && push.unread===64)capacityReached()}});
const calls = Array.from({length:64},() => capacity.request('project.list',{}).then(
    () => {throw new Error('Held capacity call unexpectedly succeeded')}, error => error));
await full;
await assert.rejects(capacity.request('project.list',{}), error => error.delivery==='NOT_SUBMITTED');
capacity.close();
assert.ok((await Promise.all(calls)).every(error => error.delivery==='UNKNOWN'));

// Dispatch backpressure. CAPACITY_EXHAUSTED is flow control a caller never sees, the
// advertised window is authoritative, and a refused negotiation is never resubmitted.
const openCapacity = (name, timeoutMs, onPush) => connectPlowshare({origin,token:'sdk-fixture-token',session:'typed-node/'+name,timeoutMs,
    ...(onPush === undefined ? {} : {onPush})});
{
    const client = await openCapacity('capacity-refuse-once', 5000);
    try { assert.deepEqual(requirePayload(await client.request('project.list',{})),[],'a refused request lost its answer'); }
    finally { client.close(); }
}
{
    // Refusals outlast the deadline armed at first transmit, so the loop ends in the
    // caller's own timeout rather than hanging or surfacing the refusal.
    const client = await openCapacity('capacity-refuse-always', 600);
    try { await assert.rejects(client.request('project.list',{}), error => error.delivery==='NOT_SUBMITTED' && /deadline/.test(error.message)); }
    finally { client.close(); }
}
{
    let windowFull;
    const full = new Promise(resolve => {windowFull = resolve});
    const client = await openCapacity('capacity-window-one', 5000, () => windowFull());
    try {
        let secondDone = false;
        const first = client.request('project.list',{});
        const second = client.request('project.list',{}).then(reply => {secondDone = true; return reply});
        await full;
        assert.equal(secondDone,false,'B must wait while the advertised window of one is full');
        assert.deepEqual(requirePayload(await first),[]);
        assert.deepEqual(requirePayload(await second),[]);
    } finally { client.close(); }
}
{
    const client = await openCapacity('capacity-slot-order', 5000);
    try {
        const a = client.request('project.list',{});
        const b = client.request('project.list',{});
        assert.deepEqual(requirePayload(await a),[],'a refused request lost its answer');
        assert.deepEqual(requirePayload(await b),[],'the request behind a refused one lost its answer');
    } finally { client.close(); }
}
await assert.rejects(openCapacity('capacity-handshake-refuse', 5000), error => error.code==='NOT_SUBMITTED');
{
    let windowFull;
    const full = new Promise(resolve => {windowFull = resolve});
    const client = await openCapacity('capacity-close-while-parked', 5000, () => windowFull());
    const held = client.request('project.list',{});
    const parked = client.request('project.list',{});
    await full;
    // Nothing was written for the parked caller, so its delivery is unambiguous.
    await assert.rejects(held, error => error.delivery==='UNKNOWN');
    await assert.rejects(parked, error => error.delivery==='NOT_SUBMITTED');
    client.close();
}

const management = await connectPlowshare({ origin, token: 'sdk-fixture-token', session: 'typed-node/provider-owner-compat' });
try {
    for(const operation of ['provider.connection.list','tool.scope.host.list']) {
        const reply=await management.request(operation,{project:'fixture'});
        assert.throws(()=>requirePayload(reply),error=>error instanceof Refusal&&error.reply.outcome.code==='NOT_FOUND'&&error.reply.outcome.said===`no handler is registered for type "${operation}"`);
        assert.equal(JSON.stringify(reply).includes('must-not-escape'),false);
    }
} finally { management.close(); }
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
await assert.rejects(connectPlowshare({origin,token:'sdk-legacy-fixture',session:'node-legacy-refusal'}), error=>error.code==='NOT_SUBMITTED')
const largeText = '\u0001'.repeat(5 * 1024 * 1024)
const large = await connectPlowshare({origin,token:'sdk-fixture-token',session:'packet-large-node'})
try {
    const id='11111111-1111-1111-1111-111111111111'
    const publication=requirePayload(await large.request('relay.publish',{requestId:id,project:'fixture',topic:'large.events',text:largeText,occurredAt:'2026-10-09T00:00:00Z'}))
    assert.equal(publication.position,'1')
    const batch=requirePayload(await large.request('relay.consume',{project:'fixture',topic:'large.events',group:'fixture',consumerId:id,start:'OLDEST_RETAINED'}))
    assert.ok(batch.events[0].payload.text===largeText,'large typed reply lost bytes')
} finally { large.close() }
// More than 4096 small replies must not exhaust completed-transfer bookkeeping.
const longSession = await connectPlowshare({origin,token:'sdk-fixture-token',session:'typed-node/long-session'});
try {for(let i=0;i<4200;i++) assert.equal(requirePayload(await longSession.request('project.list',{}))[0].name,'long-session');}
finally {longSession.close();}
console.log('Node SDK conformance passed')

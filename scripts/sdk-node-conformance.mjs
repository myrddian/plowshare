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
const cases = new Map(fixture.cases.map(value => [value.name, value]))
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
// The advertised window is authoritative and the 65th request PARKS rather than failing
// fast. This assertion used to demand an immediate NOT_SUBMITTED, which was the behaviour
// #105 deliberately replaced: the binding now waits for a slot, and a never-launched
// request carries no deadline at all ("A never-launched request has no timer; its only
// exits are abort and connection close" -- connection.ts). Fail-fast would reject in the
// same tick, so still being pending after a pause is what distinguishes waiting from
// refusing. Closing the connection is what settles it, and NOT_SUBMITTED is honest
// because nothing was ever transmitted -- which is also why the 64-frame count below
// still holds.
//
// The 250ms pause must stay well under this connection's timeoutMs of 5000. At t=5000
// the 64 held requests expire of their own deadlines, free their slots, and the parked
// request would transmit -- which would break both the `=== 64` frame count the shared
// harness asserts and the NOT_SUBMITTED below, since the request would then have been
// sent. The margin is 20x; it is a bound on how long this pause may grow, not a
// duration the assertion measures.
const pending = Symbol('pending');
const parked = capacity.request('project.list',{});
const raced = await Promise.race([parked.then(() => 'settled', () => 'settled'),
    new Promise(resolve => setTimeout(() => resolve(pending), 250))]);
assert.equal(raced, pending, 'the 65th request must wait for a slot, not fail fast');
capacity.close();
assert.equal((await parked.then(() => null, error => error.delivery)), 'NOT_SUBMITTED');
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
    //
    // Both deliveries in the fixture are accepted, and the looseness is the strict
    // reading rather than a relaxation. The guaranteed invariant is a deadline expiry
    // that is never a surfaced CAPACITY_EXHAUSTED -- a refusal arrives as a `Refusal`,
    // which carries no deadline message. Which delivery code it carries depends on where
    // the deadline lands: NOT_SUBMITTED inside a backoff pause (`refused()` clears
    // `submitted`, connection.ts:671) and RESPONSE_LOST in the sub-millisecond window
    // between a transmit and the refusal arriving (:713, :731). Insisting on
    // NOT_SUBMITTED alone made this case fail roughly one run in forty, and the failing
    // run was reporting a truthful code, not a defect.
    const test = cases.get('capacity-refuse-always');
    // Defaulted rather than spread straight: `deliveryAlternates` is absent on every other
    // case in the fixture, where absence means "this case admits its `delivery` and nothing
    // else", and a driver author copying this line for one of those would crash on the
    // spread. Documented in the fixture's `capacityContract.deliveryAlternates`.
    const honest = [test.delivery, ...(test.deliveryAlternates ?? [])];
    const client = await openCapacity('capacity-refuse-always', 600);
    try { await assert.rejects(client.request('project.list',{}), error => honest.includes(error.delivery) && /deadline/.test(error.message)); }
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
        // This one check does rest on a timing margin, and it is worth naming rather than
        // denying: `secondDone` is read after the fixture's "window is full" push has been
        // delivered, and B would have to have been transmitted, answered and its
        // continuation run for it to be true. The fixture holds A for 250ms, so the margin
        // is that 250ms against a local round trip plus a microtask -- three orders of
        // magnitude. Shortening the hold is what would put this at risk.
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
{
    // The regression test for the originally reported bug. Two things must hold: client-side
    // queue time must not be charged against the request deadline, AND the deadline the
    // request then runs on must be its own, armed when the frame was transmitted.
    //
    // Shape. The fixture advertises a window of one and holds A for `holdMs` (900ms). B,
    // carrying a per-request timeout of `waitingTimeoutMs` (300ms), and a sibling carrying
    // only the connection's 5000ms both park the instant A takes the single slot. When the
    // hold ends, A is answered and B is admitted -- and from then on the fixture answers
    // nothing (`silentAfterWait`), so B's own deadline is the only thing that can settle it.
    // B must expire with a deadline error, while the sibling, admitted when B's slot frees,
    // is still pending; the pending sibling is what shows the silence is a fixture state
    // rather than a dead or closed connection.
    //
    // Why the silence. An earlier version of this case answered B in the same tick it was
    // admitted, so it passed on "B was answered" -- which is equally true of the fix, of a
    // request carrying NO deadline at all, and of one silently running on the connection's
    // 5000ms. That matters because the three SDKs this contract is written for all arm the
    // deadline too early, and the cheapest way to pass "B was answered" is to delete the
    // premature arm and forget to re-arm at transmit, leaving an unbounded hold -- the one
    // case the spec calls unsafe. The four directions now land in different places:
    //   - armed at creation (charged for the wait): B expires at t=300, during the hold, so
    //     it is never transmitted. The shared harness sees two frames where it demands three.
    //   - never armed: B never settles, and the probe below expires first.
    //   - armed with the connection's duration: B expires at t=900+5000=5900, and the probe
    //     below expires first.
    //   - armed at transmit with B's own 300ms: B expires at t=1200. Only this one passes.
    //
    // The numbers, and what they protect. Correct expiry is at holdMs + waitingTimeoutMs =
    // 1200ms after the window fills. The probe is `waitingProbeMs` (2500ms) from the same
    // push, which leaves 1300ms of scheduler tolerance above the correct expiry and 3400ms
    // of margin below the 5900ms of a request wrongly on the connection's deadline. Both
    // margins exceed 1200ms, so neither direction is a near miss. Widening the connection's
    // timeoutMs widens the lower margin; shortening holdMs is what would narrow the upper.
    const test = cases.get('capacity-deadline-survives-waiting');
    let windowFull;
    const full = new Promise(resolve => {windowFull = resolve});
    const client = await openCapacity('capacity-deadline-survives-waiting', 5000, () => windowFull());
    try {
        const held = client.request('project.list',{});
        const waited = client.request('project.list',{},{timeoutMs:test.waitingTimeoutMs});
        // The sibling's rejection on close is expected, so both settlements are absorbed
        // here rather than left as an unhandled rejection.
        let siblingSettled = false;
        client.request('project.list',{}).then(() => {siblingSettled = true}, () => {siblingSettled = true});
        await full;
        assert.deepEqual(requirePayload(await held),[]);
        const outcome = await Promise.race([waited.then(() => 'answered', error => error),
            new Promise(resolve => setTimeout(() => resolve(pending), test.waitingProbeMs))]);
        assert.notEqual(outcome, pending,
            'the waiting request did not expire on a deadline of its own: it is carrying no deadline or the connection timeout, not one armed at transmit');
        assert.notEqual(outcome, 'answered', 'the fixture must answer nothing once the hold is over');
        assert.equal(outcome.delivery, test.waitingDelivery,
            'the waiting request was transmitted, so its expiry may have left work done and is UNKNOWN');
        assert.match(outcome.message, /deadline/,
            'a connection teardown must not pass as a deadline expiry');
        // Read only after the waiting request has settled, so this is a state check and not
        // a race: it is the proof the connection was alive and the silence deliberate.
        assert.equal(siblingSettled, false,
            'the sibling carrying only the connection deadline must still be pending when the waiting request expires');
    } finally { client.close(); }
}
{
    // The explicit non-blocking form. The contract requires two forms and the seven cases
    // above all drive the waiting default, so this is the only assertion that an SDK offers
    // the second one at all. Its two halves:
    //
    //   1. A CAPACITY_EXHAUSTED reply is the CALLER'S outcome, not flow control. The waiting
    //      form absorbs that code and resubmits under the same id; this form reports it
    //      through the ordinary refused-reply channel and stops. `expectedSubmissions` of 2
    //      in the fixture is what pins "no backoff and no resubmission at all" -- a single
    //      retry of the refused id would be a third frame.
    //   2. A full window is an immediate NOT_SUBMITTED. Proved by order, not by a clock: the
    //      fixture holds B for `holdMs`, and C is submitted while that hold is active, so a
    //      form that queued for the slot could not settle C before B. `heldDone` is read
    //      after C has already rejected, which makes it a state check rather than a race --
    //      B would have to have been answered and its continuation run to be true, against
    //      the fixture's 300ms hold.
    //
    // Both forms arm the deadline at transmit, so nothing here turns on the connection's
    // 5000ms timeout; it only has to outlast the hold, which it does twenty times over.
    const test = cases.get('capacity-non-blocking');
    let windowFull;
    const full = new Promise(resolve => {windowFull = resolve});
    const client = await openCapacity('capacity-non-blocking', 5000, () => windowFull());
    try {
        const refused = await client.request('project.list',{},{nonBlocking:true});
        assert.equal(refused.kind,'refused','a non-blocking caller must be told the server refused it');
        assert.equal(refused.outcome.code,test.surfacedCode,
            'the non-blocking form must surface the refusal code rather than absorb it as flow control');
        assert.throws(() => requirePayload(refused), Refusal);
        let heldDone = false;
        const held = client.request('project.list',{},{nonBlocking:true}).then(reply => {heldDone = true; return reply});
        await full;
        await assert.rejects(client.request('project.list',{},{nonBlocking:true}),
            error => error.delivery===test.delivery && /window is full/.test(error.message));
        assert.equal(heldDone,false,'the non-blocking refusal must arrive while the window is still full');
        assert.deepEqual(requirePayload(await held),[],'the held request lost its answer');
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
    // Without this race the case proves nothing. Its other three facts -- one frame on the
    // wire, UNKNOWN for the held caller, NOT_SUBMITTED for the parked one -- are all
    // equally true of a client that refuses locally and immediately, which is why the Go
    // baseline passed it while never parking anything. Fail-fast settles `parked` in the
    // same tick, so finding it still pending here is what distinguishes parking from
    // refusing, and only then does the release on close mean anything.
    //
    // The pause lives inside the fixture's 1000ms hold, which is what ends this case by
    // closing the socket, and well under the connection's timeoutMs of 5000. If it ever
    // approached either, `parked` would settle on its own and the race would report
    // "settled" for a conforming client.
    const raced = await Promise.race([parked.then(() => 'settled', () => 'settled'),
        new Promise(resolve => setTimeout(() => resolve(pending), 200))]);
    assert.equal(raced, pending, 'the parked request must wait for a slot, not fail fast');
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

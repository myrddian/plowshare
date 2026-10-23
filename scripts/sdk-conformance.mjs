import { createServer } from 'node:http'
import {fixtureSocket} from './sdk-packet-fixture.mjs'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { WebSocketServer } from '../sdk/node/node_modules/ws/wrapper.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const fixture = JSON.parse(await readFile(root + 'test-support/contracts/sdk-conformance.json', 'utf8'))
const cases = new Map(fixture.cases.map(value => [value.name, value]))
const counts = new Map()
const packetCounts = new Map()
const packetMax = new Map()
const largeText = '\u0001'.repeat(5 * 1024 * 1024)
const upgrades = new Map()
/**
 * Dispatch backpressure observations per session: the order request ids were
 * submitted in, and how many times each id was submitted. Asserted after each
 * driver exits, because the contract is about resubmission of one request id
 * rather than about any single frame.
 */
const capacityFacts = new Map()
/**
 * How long a backpressure case holds its first request before answering it.
 *
 * <p>At an advertised window of one, a second request cannot reach the fixture
 * while the first is held — a conforming client parks it — so there is no frame
 * to wait for and the hold ends on a timer. The assertions do not depend on the
 * duration: the fixture fails the case if a second frame arrives while the hold
 * is active, whatever the timing.
 */
const CAPACITY_HOLD_MS = 250
const failures = []
const server = createServer((_, response) => response.writeHead(404).end())
const connections = new Set()
server.on('connection', socket => { connections.add(socket); socket.on('close', () => connections.delete(socket)) })
const sockets = new WebSocketServer({ noServer: true, handleProtocols: (protocols, request) => request.headers.authorization === 'Bearer sdk-legacy-fixture' ? false : protocols.values().next().value })
let origin
server.on('upgrade', (request, socket, head) => {
    const url = new URL(request.url, origin)
    const session = url.searchParams.get('session')
    try {
        assert.equal(url.pathname, '/v1/events')
        assert.ok(session)
        assert.equal(url.searchParams.has('token'), false)
        const auth = request.headers.authorization
        if (auth === 'Bearer sdk-redirect-fixture') {
            upgrades.set(session, (upgrades.get(session) ?? 0) + 1)
            socket.end(`HTTP/1.1 302 Found\r\nLocation: ${origin.replace('http:', 'ws:')}${request.url}\r\nContent-Length: 0\r\n\r\n`)
            return
        }
        assert.ok(['Bearer sdk-fixture-token','Bearer sdk-legacy-fixture'].includes(auth))
        sockets.handleUpgrade(request, socket, head, physical => {
            physical.on('message', wire => {
                if (physical.protocol === 'plowshare-segments-v1') {
                    packetMax.set(session, Math.max(packetMax.get(session) ?? 0, wire.length))
                    if(JSON.parse(wire.toString()).kind === 'transport.segment') packetCounts.set(session, (packetCounts.get(session) ?? 0) + 1)
                }
            })
            const ws = fixtureSocket(physical)
            let first, second, selected = false, applicationStarted = false
            // Backpressure cases are selected by the session's trailing segment, so the
            // negotiated window can be overridden before any application request arrives.
            const selection = cases.get(session.split('/').pop())
            const backpressure = selection?.capacity ? selection : undefined
            const facts = { order: [], attempts: new Map(), held: undefined }
            if (backpressure) capacityFacts.set(session, facts)
            const envelope = (frame, body) => ws.send(JSON.stringify({ id: frame.id, type: frame.type, protocol_version: fixture.protocolVersion, payload: body, futureEnvelope: true }))
            /**
             * Drives one dispatch backpressure case, from either the typed or the raw
             * scenario path.
             *
             * <p>Attempts are counted per request id within this connection, never per
             * session or per connection: a resubmission after `CAPACITY_EXHAUSTED`
             * reuses its id, and that is the property under test. Counting per
             * connection would make `capacity-refuse-once` indistinguishable from two
             * separate requests.
             */
            const backpressureAnswer = (sent, test) => {
                const attempt = (facts.attempts.get(sent.id) ?? 0) + 1
                facts.attempts.set(sent.id, attempt)
                facts.order.push(sent.id)
                // Refusals apply to the connection's first request id only, so a case can
                // refuse one request and watch what the requests behind it do.
                facts.first ??= sent.id
                if (sent.id === facts.first && (test.refuseAlways === true || attempt <= (test.refusals ?? 0))) {
                    envelope(sent, { code: 'CAPACITY_EXHAUSTED', said: 'fixture connection queue is full' })
                    return
                }
                if (test.holdUntilSubmitted !== undefined) {
                    if (facts.held === undefined) {
                        facts.held = sent.id
                        setTimeout(() => {
                            facts.held = null
                            if (test.closeWhileParked) ws.terminate()
                            else envelope(sent, test.response)
                        }, CAPACITY_HOLD_MS)
                        // Tells the driver the window is full, so it can prove the next call waits.
                        ws.send(JSON.stringify({ kind: 'inbox.changed', unread: facts.order.length }))
                        return
                    }
                    assert.equal(facts.held, null, 'a request was submitted while the advertised window was full')
                    assert.ok(facts.order.length <= test.holdUntilSubmitted, 'more requests were submitted than the advertised window allows')
                }
                envelope(sent, test.response)
            }
            ws.on('message', wire => {
                try {
                    const sent = JSON.parse(wire.toString())
                    assert.equal(sent.protocol_version, fixture.protocolVersion)
                    if (sent.type === 'transport.dispatch') {
                        // A refused negotiation is never flow control: the server treats a second
                        // selection on one connection as a protocol violation, so a resubmission
                        // would turn a refusal into a teardown.
                        assert.ok(!(selected && backpressure?.handshake), 'a refused dispatch negotiation was resubmitted')
                        assert.equal(selected, false, 'dispatch selected twice')
                        assert.equal(applicationStarted, false, 'dispatch selected after application request')
                        assert.deepEqual(sent.payload, {mode:'concurrent-v1'})
                        assert.equal(Object.hasOwn(sent, 'actingFor'), false)
                        selected = true
                        if (backpressure?.handshake) {
                            ws.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:fixture.protocolVersion,
                                payload:{code:'CAPACITY_EXHAUSTED',said:'fixture dispatch queue is full'}}))
                            return
                        }
                        ws.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:fixture.protocolVersion,
                            payload:{code:'OK',payload:{mode:'concurrent-v1',maxPending:backpressure?.maxPending ?? 64,maxIdBytes:128}}}))
                        return
                    }
                    applicationStarted = true
                    if (session.startsWith('packet-large-')) {
                        assert.equal(physical.protocol, 'plowshare-segments-v1')
                        assert.ok(['relay.publish','relay.consume'].includes(sent.type))
                        const key = session + '/' + sent.type
                        counts.set(key, (counts.get(key) ?? 0) + 1)
                        const at = '2026-10-09T00:00:00Z'
                        let payload
                        if(sent.type === 'relay.publish') {
                            assert.ok(sent.payload.text === largeText,'large logical publication lost bytes')
                            payload = {requestId:sent.payload.requestId, project:sent.payload.project, topic:sent.payload.topic, position:'1',publishedAt:at}
                        } else {
                            payload = {...sent.payload,status:'DATA',batchId:'22222222-2222-2222-2222-222222222222',fence:'1',through:'1',expiresAt:'2026-10-09T00:00:30Z',expiredThrough:null,
                                events:[{position:'1',eventId:'large-event',publisher:'fixture',occurredAt:at,publishedAt:at,correlationId:null,causationId:null,causation:null,payload:{kind:'TEXT',text:largeText,schedule:null,emits:null,fireAt:null,lifecycle:null,wake:null}}]}
                            delete payload.start
                        }
                        ws.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:fixture.protocolVersion,payload:{code:'OK',payload}}))
                        return
                    }
                    if(session.endsWith('/provider-owner-compat')) {
                        assert.ok(['provider.connection.list','tool.scope.host.list'].includes(sent.type));
                        assert.deepEqual(sent.payload,{project:'fixture'});
                        const key=session+'/'+sent.type;
                        counts.set(key,(counts.get(key)??0)+1);
                        ws.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:fixture.protocolVersion,
                            payload:{code:'NOT_FOUND',said:`no handler is registered for type "${sent.type}"`,payload:{accessToken:'must-not-escape'}}}));
                        return;
                    }
                    if(session.endsWith('/acting-selection')) {
                        assert.equal(sent.type,'conversation.turns');
                        assert.deepEqual(sent.payload,{conversation:'fixture'});
                        const count=(counts.get(session)??0)+1;
                        counts.set(session,count);
                        if(count<=2) assert.deepEqual(sent.actingFor,{context:'00000000-0000-0000-0000-00000000000'+count});
                        else { assert.equal(count,3); assert.equal(Object.hasOwn(sent,'actingFor'),false); }
                        ws.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:fixture.protocolVersion,payload:{code:'OK',payload:[]}}));
                        return;
                    }
                    assert.equal(sent.type, 'project.list')
                    assert.equal(typeof sent.id, 'string')
                    if (session.startsWith('typed-')) {
                        assert.deepEqual(sent.payload, {})
                        const name = session.split('/')[1]
                        const key = session + '/' + name
                        counts.set(key, (counts.get(key) ?? 0) + 1)
                        if (backpressure) { backpressureAnswer(sent, backpressure); return }
                        const project = name => ({name,workspace:'fixture',machine:null,members:[],lent:[],exclusions:[],future:{discard:true}})
                        const answer = body => ws.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:fixture.protocolVersion,payload:body,futureEnvelope:true}))
                        if(name==='long-session'){answer({code:'OK',payload:[project('long-session')]});return}
                        if(name==='multiplex') {
                            if (!first) {
                                first = sent
                                ws.send(JSON.stringify({kind:'inbox.changed',unread:1}))
                                return
                            }
                            if (!second) {
                                second = sent
                                answer({code:'OK',payload:[project('second')]})
                                return
                            }
                            answer({code:'OK',payload:[project('third')]})
                            ws.send(JSON.stringify({id:first.id,type:first.type,protocol_version:fixture.protocolVersion,payload:{code:'OK',payload:[project('first')]}}))
                            return
                        }
                        if(name==='capacity'){ws.send(JSON.stringify({kind:'inbox.changed',unread:counts.get(key)}));return}
                        if(name==='bad-packet'){physical.send('{"kind":"transport.credit","version":"1","transferId":"11111111-1111-1111-1111-111111111111","segmentNumber":1}');return}
                        if(name==='cancel'){ws.send(JSON.stringify({kind:'inbox.changed',unread:1}));return}
                        if(name==='invalid-input')throw new Error('invalid DTO reached the transport')
                        if(name==='malformed-nested'){answer({code:'OK',payload:[{...project('bad'),members:[42]}]});return}
                        if(name==='missing-payload'){answer({code:'OK'});return}
                        const test=cases.get(name);assert.ok(test,'unknown typed conformance case')
                        if(test.silent)return
                        if(test.disconnect){ws.terminate();return}
                        if(test.push){
                            ws.send(JSON.stringify({kind:'inbox.changed',unread:-1}))
                            ws.send(JSON.stringify({kind:'orchestration.resumed',orchestration:'fixture',requestId:'bad'}))
                            ws.send(JSON.stringify({kind:'inbox.changed',unread:1,future:true}))
                            ws.send(JSON.stringify({id:null,type:'usage.closed',protocol_version:fixture.protocolVersion,payload:{subscription:'11111111-1111-1111-1111-111111111111',code:'BAD_REQUEST'}}))
                        }
                        ws.send(JSON.stringify({id:sent.id,type:test.type??sent.type,protocol_version:test.version??fixture.protocolVersion,payload: ['success','push'].includes(name)?{code:'OK',payload:[project(name)],futureOutcome:true}:test.response,futureEnvelope:true}))
                        return
                    }
                    const name = sent.payload.scenario
                    const key = session + '/' + name
                    counts.set(key, (counts.get(key) ?? 0) + 1)
                    const answer = (frame, body) => ws.send(JSON.stringify({ id: frame.id, type: frame.type, protocol_version: fixture.protocolVersion, payload: body, futureEnvelope: true }))
                    if (session === 'node-typed') {
                        assert.deepEqual(sent.payload, {})
                        const turn = counts.get(key)
                        if (turn === 1) {
                            ws.send(JSON.stringify({ kind: 'inbox.changed', unread: 1, future: 'omitted' }))
                            ws.send(JSON.stringify({ kind: 'inbox.changed', unread: -1 }))
                            answer(sent, { code: 'OK', payload: [] })
                        } else if (turn === 2) answer(sent, { code: 'CONFLICT', said: 'fixture refusal' })
                        else if (turn === 3) answer(sent, { code: 'OK', payload: { malformed: true } })
                        else throw new Error('public SDK replayed or submitted invalid input')
                        return
                    }
                    if (name === 'multiplex-one' || name === 'multiplex-two') {
                        if (name === 'multiplex-one') first = sent
                        else second = sent
                        if (first && second) {
                            answer(second, { code: 'OK', payload: { sequence: 2 } })
                            answer(first, { code: 'OK', payload: { sequence: 1 } })
                        }
                        return
                    }
                    if (name === 'cancel') { ws.send(JSON.stringify({ kind: 'fixture-submitted' })); return }
                    const test = cases.get(name)
                    assert.ok(test, 'unknown conformance case')
                    if (test.capacity) { backpressureAnswer(sent, test); return }
                    if (test.silent) return
                    if (test.disconnect) { ws.terminate(); return }
                    if (test.push) {
                        ws.send(JSON.stringify({ job: 'job_fixture', kind: 'fixture-push' }))
                        ws.send(JSON.stringify({ id: null, type: 'usage.snapshot', protocol_version: fixture.protocolVersion, payload: { revision: 1 } }))
                    }
                    ws.send(JSON.stringify({ id: sent.id, type: test.type ?? sent.type, protocol_version: test.version ?? fixture.protocolVersion, payload: test.response, futureEnvelope: true }))
                } catch (error) { failures.push(error); ws.terminate() }
            })
        })
    } catch (error) { failures.push(error); socket.destroy() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
origin = `http://127.0.0.1:${server.address().port}`
const languages = (process.argv.find(value => value.startsWith('--languages='))?.split('=')[1] ?? 'node,python,dotnet,go').split(',')
const commands = {
    node: [process.execPath, ['scripts/sdk-node-conformance.mjs', origin]],
    python: [process.env.PLOWSHARE_SDK_PYTHON ?? (existsSync(root + 'build/sdk-python-env/bin/python') ? root + 'build/sdk-python-env/bin/python' : 'python3'), ['sdk/python/tests/conformance.py', origin]],
    dotnet: [process.env.PLOWSHARE_SDK_DOTNET ?? (existsSync(root + 'build/dotnet/dotnet') ? root + 'build/dotnet/dotnet' : 'dotnet'), ['run', '--no-build', '--project', process.env.PLOWSHARE_SDK_DOTNET_PROJECT ?? 'sdk/dotnet/Conformance', '--', origin]],
    go: [process.env.PLOWSHARE_SDK_GO ?? 'go', ['run', '-race', './cmd/conformance', origin]],
}
try {
    for (const language of languages) {
        const [command, args] = commands[language] ?? []
        assert.ok(command, 'unknown SDK language')
        await new Promise((resolve, reject) => {
            const child = spawn(command, args, { cwd: language === 'go' ? process.env.PLOWSHARE_SDK_GO_ROOT ?? root + 'sdk/go' : root, stdio: 'inherit', env: { ...process.env, PYTHONPATH: process.env.PLOWSHARE_SDK_PYTHONPATH ?? root + 'sdk/python/src', PLOWSHARE_SDK_FIXTURES: root + 'test-support/contracts/sdk-conformance.json', PLOWSHARE_SDK_CATALOG: root + 'test-support/contracts/sdk-protocol.json', DOTNET_CLI_TELEMETRY_OPTOUT: '1', DOTNET_GENERATE_ASPNET_CERTIFICATE: 'false', DOTNET_CLI_HOME: root + 'build/dotnet-home', NUGET_PACKAGES: process.env.NUGET_PACKAGES ?? root + 'build/nuget' } })
            const deadline = setTimeout(() => { child.kill('SIGKILL'); reject(new Error(language + ' conformance deadline expired')) }, 60000)
            child.on('error', reject)
            child.on('exit', code => { clearTimeout(deadline); code === 0 ? resolve() : reject(new Error(language + ' conformance failed: ' + code)) })
        })
        assert.equal(counts.get('typed-'+language+'/capacity/capacity'),64,language+' capacity admission failed')
        assert.equal(counts.get('typed-'+language+'/long-session/long-session'),4200,language+' did not survive the old completion cap')
        assert.equal(counts.get('typed-'+language+'/multiplex/multiplex'),3,language+' did not complete held-request proof')
        assert.equal(counts.get('packet-large-'+language+'/relay.publish'),1,'large publication repeated')
        assert.equal(counts.get('packet-large-'+language+'/relay.consume'),1,'large consume repeated')
        assert.ok(packetCounts.get('packet-large-'+language)>480,'large message was not segmented')
        assert.ok(packetMax.get('packet-large-'+language)<=128*1024,'packet exceeds wire allowance')
        for(const operation of ['provider.connection.list','tool.scope.host.list'])
            assert.equal(counts.get('typed-'+language+'/provider-owner-compat/'+operation),1,language+' replayed unsupported management');
        if(language==='node') assert.equal(counts.get('node-typed/undefined'),3,'public SDK replayed work or sent invalid input')
        assert.equal(upgrades.get(language + '-redirect'), 1, language + ' followed an authenticated upgrade redirect')
        for(const name of ['timeout','disconnect','cancel'])assert.equal(counts.get(language==='node'? language+(name==='cancel'?'-cancel':'')+'/'+name:'typed-'+language+'/'+name+'/'+name),1,language+' replayed '+name+' work')
        if(language!=='node')assert.equal(counts.get('typed-'+language+'/invalid-input/invalid-input'),undefined,'invalid input reached the transport')
        // Dispatch backpressure: one request id, resubmitted, never replayed as new work.
        const observed = name => capacityFacts.get('typed-'+language+'/'+name) ?? {order:[],attempts:new Map()}
        const once = observed('capacity-refuse-once')
        assert.deepEqual(once.order, [once.order[0], once.order[0]], language+' did not resubmit one refused request id exactly once')
        const always = observed('capacity-refuse-always')
        assert.ok(always.order.length>=2, language+' did not resubmit a repeatedly refused request')
        assert.equal(new Set(always.order).size, 1, language+' resubmitted a refused request as new work')
        const windowOne = observed('capacity-window-one')
        assert.equal(windowOne.order.length, 2, language+' did not submit the parked request once the window freed')
        assert.equal(new Set(windowOne.order).size, 2, language+' lost a request id at a window of one')
        // A refused request releases the dispatch slot it was using but keeps its place
        // ahead of work parked behind it, so a later request never overtakes it.
        const slots = observed('capacity-slot-order')
        assert.deepEqual(slots.order, [slots.order[0], slots.order[0], slots.order[2]], language+' overtook a backing-off request')
        assert.notEqual(slots.order[0], slots.order[2], language+' lost a request id at a window of one')
        assert.equal(observed('capacity-handshake-refuse').order.length, 0, language+' submitted work after a refused dispatch negotiation')
        assert.equal(observed('capacity-close-while-parked').order.length, 1, language+' submitted a parked request at a full window')
    }
    assert.deepEqual(failures, [])
    console.log('Shared SDK conformance passed:', languages.join(', '))
} finally {
    for (const ws of sockets.clients) ws.terminate()
    await new Promise(resolve => sockets.close(resolve))
    const stopped = new Promise(resolve => server.close(resolve))
    for (const socket of connections) socket.destroy()
    await stopped
}

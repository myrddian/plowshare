import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { respond } from './protocol.js'
import { call, tools } from './adapter.js'
import type { Backend } from './adapter.js'
import { ws } from './fixtures.test-support.js'
import { record } from './values.js'
import type { Data } from './values.js'

const fixture = JSON.parse(await readFile(new URL('../../plowshare-client/src/test/resources/compatibility/legacy-mcp.json', import.meta.url), 'utf8')) as {
    toolsList: { result: { tools: unknown[] } }; initialize: unknown; ping: unknown; notification: unknown; unknownTool: unknown
    cases: { id: string; backendMode: string; request: unknown; response: Data; backendCalls: { method: string; arguments: Data; returns?: unknown; throws?: string }[] }[]
}

test('the 35-tool menu, schemas, order, initialization and notification silence match Java', async () => {
    assert.deepEqual(tools, fixture.toolsList.result.tools)
    assert.equal(tools.length, 35)
    const unused: Backend = { invoke: async () => { throw new Error('unexpected backend call') }, runSession: () => null, root: async () => { throw new Error('unexpected rooting') } }
    assert.deepEqual(await respond(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }), unused, 'fixture-version'), fixture.initialize)
    assert.deepEqual(await respond(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }), unused), fixture.toolsList)
    assert.deepEqual(await respond(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'ping' }), unused), fixture.ping)
    assert.deepEqual(await respond(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'missing_tool', arguments: {} } }), unused), fixture.unknownTool)
    assert.equal(await respond(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }), unused), undefined)
})
for (const item of fixture.cases) test(item.id, async () => {
    let cursor = 0
    const backend: Backend = {
        invoke: async (type, payload) => {
            const expected = item.backendCalls[cursor++]
            assert.ok(expected, 'unexpected WS call')
            assert.deepEqual([type, payload], ws(expected.method, expected.arguments))
            if (item.backendMode === 'unreachable') throw new Error('the WS connection was lost or unreadable; completion is unknown; no mutation was replayed or cancelled; restart the MCP client')
            if (item.backendMode === 'refused') throw new Error('the Plowshare server answered NOT_FOUND: fixture missing')
            return expected.returns
        },
        runSession: () => null,
        root: async (_project, path) => { throw new Error(`'path' names ${path}, which is not a directory on this machine. A project cannot be served from a place that is not there. Nothing was rooted.`) },
    }
    const actual = await respond(JSON.stringify(item.request), backend)
    // Two reviewed transport migrations: WS codes retain their names, and
    // connection failures are redacted/unknown rather than native HTTP errors.
    const expected = structuredClone(item.response)
    if (item.backendMode === 'unreachable' || item.backendMode === 'refused') {
        record(expected['result'])['content'] = [{ type: 'text', text: item.backendMode === 'refused' ? 'the Plowshare server answered NOT_FOUND: fixture missing' : 'the WS connection was lost or unreadable; completion is unknown; no mutation was replayed or cancelled; restart the MCP client' }]
    }
    assert.deepEqual(actual, expected)
    assert.equal(cursor, item.backendCalls.length)
})

test('malformed protocol requests survive, notifications are silent and tool errors remain model-visible', async () => {
    const backend: Backend = { invoke: async () => { throw new Error('unreachable') }, runSession: () => null, root: async () => { throw new Error('unavailable') } }
    assert.equal((await respond('not JSON', backend))?.error?.code, -32700)
    assert.equal((await respond('[]', backend))?.error?.code, -32600)
    assert.equal(await respond(' ', backend), undefined)
    assert.equal(await respond('{"jsonrpc":"2.0","method":"unknown"}', backend), undefined)
    assert.equal((await respond('{"jsonrpc":"2.0","id":"a","method":"unknown"}', backend))?.error?.code, -32601)
})

test('information preserves scoped receipts and refuses human publication and migration before WS dispatch', async () => {
    const calls: unknown[] = []
    const backend: Backend = {invoke: async (type,payload) => {calls.push([type,payload]);return {revision:'r',resource:'q',created:true}},runSession:()=>null,root:async()=>{throw new Error('unexpected root')}}
    const payload = {scope:{kind:'project',project:'research',includeShared:false},requestId:'retained-receipt',name:'report',text:'Report',inputs:['cited-source','uncited-source']}
    await call(backend,'information',{operation:'record.report',payload})
    assert.deepEqual(calls,[['information.record.report',payload]])
    for (const operation of ['share','finalise','delete','migration.adopt']) await assert.rejects(call(backend,'information',{operation,payload}),/unsupported model information operation/)
    assert.equal(calls.length,1)
})

import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { WebSocketServer } from '../plowshare-client-node/node_modules/ws/wrapper.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const fixture = JSON.parse(await readFile(root + 'test-support/contracts/sdk-conformance.json', 'utf8'))
const cases = new Map(fixture.cases.map(value => [value.name, value]))
const counts = new Map()
const upgrades = new Map()
const failures = []
const server = createServer((_, response) => response.writeHead(404).end())
const connections = new Set()
server.on('connection', socket => { connections.add(socket); socket.on('close', () => connections.delete(socket)) })
const sockets = new WebSocketServer({ noServer: true })
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
        assert.equal(auth, 'Bearer sdk-fixture-token')
        sockets.handleUpgrade(request, socket, head, ws => {
            let first, second
            ws.on('message', wire => {
                try {
                    const sent = JSON.parse(wire.toString())
                    assert.equal(sent.protocol_version, fixture.protocolVersion)
                    assert.equal(sent.type, 'project.list')
                    assert.equal(typeof sent.id, 'string')
                    const name = sent.payload.scenario
                    const key = session + '/' + name
                    counts.set(key, (counts.get(key) ?? 0) + 1)
                    const answer = (frame, body) => ws.send(JSON.stringify({ id: frame.id, type: frame.type, protocol_version: fixture.protocolVersion, payload: body, futureEnvelope: true }))
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
    python: [process.env.PLOWSHARE_SDK_PYTHON ?? (existsSync(root + 'build/sdk-python-env/bin/python') ? root + 'build/sdk-python-env/bin/python' : 'python3'), ['plowshare-sdk-python/tests/conformance.py', origin]],
    dotnet: [process.env.PLOWSHARE_SDK_DOTNET ?? (existsSync(root + 'build/dotnet/dotnet') ? root + 'build/dotnet/dotnet' : 'dotnet'), ['run', '--no-build', '--project', process.env.PLOWSHARE_SDK_DOTNET_PROJECT ?? 'plowshare-sdk-dotnet/Conformance', '--', origin]],
    go: [process.env.PLOWSHARE_SDK_GO ?? 'go', ['run', '-race', './cmd/conformance', origin]],
}
try {
    for (const language of languages) {
        const [command, args] = commands[language] ?? []
        assert.ok(command, 'unknown SDK language')
        await new Promise((resolve, reject) => {
            const child = spawn(command, args, { cwd: language === 'go' ? process.env.PLOWSHARE_SDK_GO_ROOT ?? root + 'plowshare-sdk-go' : root, stdio: 'inherit', env: { ...process.env, PYTHONPATH: process.env.PLOWSHARE_SDK_PYTHONPATH ?? root + 'plowshare-sdk-python/src', PLOWSHARE_SDK_FIXTURES: root + 'test-support/contracts/sdk-conformance.json', PLOWSHARE_SDK_CATALOG: root + 'test-support/contracts/sdk-protocol.json', DOTNET_CLI_TELEMETRY_OPTOUT: '1', DOTNET_GENERATE_ASPNET_CERTIFICATE: 'false', DOTNET_CLI_HOME: root + 'build/dotnet-home', NUGET_PACKAGES: process.env.NUGET_PACKAGES ?? root + 'build/nuget' } })
            const deadline = setTimeout(() => { child.kill('SIGKILL'); reject(new Error(language + ' conformance deadline expired')) }, 60000)
            child.on('error', reject)
            child.on('exit', code => { clearTimeout(deadline); code === 0 ? resolve() : reject(new Error(language + ' conformance failed: ' + code)) })
        })
        assert.equal(upgrades.get(language + '-redirect'), 1, language + ' followed an authenticated upgrade redirect')
        assert.equal(counts.get(language + '/timeout'), 1, language + ' replayed timed-out work')
        assert.equal(counts.get(language + '/disconnect'), 1, language + ' replayed uncertain work')
        assert.equal(counts.get(language + '-cancel/cancel'), 1, language + ' replayed cancelled work')
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

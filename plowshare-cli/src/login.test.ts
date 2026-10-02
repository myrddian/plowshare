import assert from 'node:assert/strict'
import test from 'node:test'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { AddressInfo } from 'node:net'
import { fileURLToPath } from 'node:url'
import { WebSocketServer } from 'ws'
import { Credentials, LoginRequired, savedLoginServers } from 'plowshare-client-node/credentials'
import { run } from './run.js'

const main = process.env['PLOWSHARE_CLI_TEST_MAIN'] ?? fileURLToPath(new URL('../build/main.js', import.meta.url))
const replies = JSON.parse(await readFile(new URL('../../test-support/contracts/ws-retrieval-fixtures.json', import.meta.url), 'utf8')).replies
async function fixture(flagged = false) {
    let generation = 0, current = 'refresh-0', logins = 0, refreshes = 0, logout = false, failTicket = false, failLogout = false
    const paths: string[] = []
    const server = createServer(async (req, res) => {
        paths.push(req.url!)
        if (req.url === '/v1/auth/login') {
            logins++; logout = false
            res.writeHead(200).end(JSON.stringify({ access: 'access-' + generation, refresh: current, mustChangePassword: flagged }))
        } else if (req.url === '/v1/auth/password') {
            flagged = false; res.writeHead(204).end()
        } else if (req.url === '/v1/auth/refresh') {
            refreshes++
            if (logout || req.headers.cookie !== 'ps_refresh=' + current) { res.writeHead(401).end(); return }
            // Yield while holding the client lock to expose competing processes.
            await new Promise(resolve => setTimeout(resolve, 15))
            current = 'refresh-' + ++generation
            res.writeHead(204, { 'Set-Cookie': [`ps_access=access-${generation}; Path=/`, `ps_refresh=${current}; Path=/`] }).end()
        } else if (req.url === '/v1/auth/ticket') {
            if (failTicket) { res.writeHead(503).end(); return }
            res.writeHead(200).end(JSON.stringify({ ticket: 'ticket' }))
        } else if (req.url === '/v1/auth/logout') { if (failLogout) { res.writeHead(503).end(); return }; logout = true; res.writeHead(204).end() }
        else res.writeHead(404).end()
    })
    const sockets = new WebSocketServer({ server })
    sockets.on('connection', socket => socket.on('message', bytes => {
        const frame = JSON.parse(bytes.toString())
        socket.send(JSON.stringify({ id: frame.id, type: frame.type, protocol_version: frame.protocol_version, payload: { code: 'OK', payload: replies[frame.type] } }))
    }))
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
    const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
    return { base, paths, counts: () => ({ logins, refreshes }), fail: (value: boolean) => { failTicket = value },
        failLogout: (value: boolean) => { failLogout = value },
        door: { base, fetch: (url: string, init: Parameters<typeof fetch>[1]) => fetch(url, init) },
        async close() { for (const socket of sockets.clients) socket.terminate(); await new Promise<void>(resolve => sockets.close(() => resolve())); server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) } }
}
function child(args: string[], directory: string): Promise<{ code: number | null; out: string; err: string }> {
    return new Promise((resolve, reject) => {
        const proc = spawn(process.execPath, [main, ...args], { env: { ...process.env, PLOWSHARE_CONFIG_DIR: directory, PLOWSHARE_HANDLE: '', PLOWSHARE_PASSWORD: '', PLOWSHARE_PROJECT: undefined }, stdio: ['ignore', 'pipe', 'pipe'] })
        let out = '', err = ''; proc.stdout.on('data', chunk => { out += chunk }); proc.stderr.on('data', chunk => { err += chunk }); proc.once('error', reject); proc.once('exit', code => resolve({ code, out, err }))
    })
}

test('one prompted login serves concurrent CLI processes, saves rotated tokens and logout removes them', { timeout: 15000 }, async () => {
    const fake = await fixture(), directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'))
    let out = ''
    try {
        const code = await run(['--url', fake.base, 'login'], { env: { PLOWSHARE_CONFIG_DIR: directory }, stdout: text => { out += text }, stderr: () => {}, stdin: async () => '', login: async () => ({ handle: 'operator', password: 'never-save-password' }) })
        assert.equal(code, 0); assert.ok(!out.includes('never-save-password'))
        const results = await Promise.all([child(['--url', fake.base, '--json', 'memory', 'index'], directory), child(['--url', fake.base, '--json', 'memory', 'index'], directory)])
        for (const result of results) { assert.equal(result.code, 0, result.err + result.out); assert.equal(JSON.parse(result.out).status, 'completed'); assert.ok(!result.out.includes('refresh-')) }
        assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 })
        const privateDirectory = join(directory, 'credentials'), files = await readdir(privateDirectory)
        assert.equal(files.length, 1)
        assert.equal((await stat(privateDirectory)).mode & 0o777, 0o700)
        assert.equal((await stat(join(privateDirectory, files[0]!))).mode & 0o777, 0o600)
        const saved = await readFile(join(privateDirectory, files[0]!), 'utf8')
        assert.ok(!saved.includes('never-save-password')); assert.equal(JSON.parse(saved).tokens.refresh, 'refresh-2')
        const signedOut = await child(['--url', fake.base, 'logout'], directory)
        assert.equal(signedOut.code, 0, signedOut.err); assert.deepEqual(await readdir(privateDirectory), [])
        const missing = await child(['--url', fake.base, 'memory', 'index'], directory)
        assert.equal(missing.code, 2); assert.match(missing.err, /Sign in first/)
    } finally { await fake.close(); await rm(directory, { recursive: true, force: true }) }
})

test('failed ticket keeps the rotated pair and a later client never repeats login', { timeout: 15000 }, async () => {
    const fake = await fixture(), directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'))
    try {
        const store = new Credentials(fake.base, join(directory, 'credentials'))
        await store.login(fake.door, 'operator', 'password')
        fake.fail(true)
        assert.equal((await child(['--url', fake.base, 'memory', 'index'], directory)).code, 5)
        assert.equal((await store.session()).tokens.refresh, 'refresh-1')
        fake.fail(false)
        assert.equal((await child(['--url', fake.base, 'memory', 'index'], directory)).code, 0)
        assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 })
        await assert.rejects(new Credentials('http://localhost:1', join(directory, 'credentials')).session(), LoginRequired)
    } finally { await fake.close(); await rm(directory, { recursive: true, force: true }) }
})

test('uncertain renewal is fenced before sending and cannot replay a spent refresh', async () => {
    const fake = await fixture(), directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'))
    try {
        const store = new Credentials(fake.base, directory)
        await store.login(fake.door, 'operator', 'password')
        let calls = 0
        const broken = { base: fake.base, fetch: async (): Promise<never> => { calls++; throw new Error('reply lost') } }
        await assert.rejects(store.renew(broken), /uncertain refresh/)
        await assert.rejects(store.renew(broken), /previous token renewal/)
        await assert.rejects(store.logout(broken), /previous token renewal/)
        assert.equal(calls, 1)
        await store.login(fake.door, 'operator', 'password')
        assert.ok((await store.renew(fake.door)).refresh)
    } finally { await fake.close(); await rm(directory, { recursive: true, force: true }) }
})


test('interactive first login changes a required initial password before saving tokens', async () => {
    const fake = await fixture(true), directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'))
    try {
        const code = await run(['--url', fake.base, 'login'], { env: { PLOWSHARE_CONFIG_DIR: directory },
            stdout: () => {}, stderr: () => {}, stdin: async () => '',
            login: async () => ({ handle: 'operator', password: 'initial-password' }), newPassword: async () => 'replacement-password' })
        assert.equal(code, 0)
        assert.deepEqual(fake.paths, ['/v1/auth/login', '/v1/auth/password', '/v1/auth/login'])
        const saved = await new Credentials(fake.base, join(directory, 'credentials')).session()
        assert.equal(saved.handle, 'operator'); assert.equal(saved.mustChangePassword, false)
    } finally { await fake.close(); await rm(directory, { recursive: true, force: true }) }
})


test('failed server logout retains the rotated session for a safe retry', async () => {
    const fake = await fixture(), directory = await mkdtemp(join(tmpdir(), 'plowshare-login-'))
    try {
        const store = new Credentials(fake.base, directory)
        await store.login(fake.door, 'operator', 'password')
        fake.failLogout(true)
        await assert.rejects(store.logout(fake.door), /credentials were retained/)
        assert.equal((await store.session()).tokens.refresh, 'refresh-1')
        fake.failLogout(false)
        await store.logout(fake.door)
        await assert.rejects(store.session(), LoginRequired)
        assert.deepEqual(fake.counts(), { logins: 1, refreshes: 2 })
    } finally { await fake.close(); await rm(directory, { recursive: true, force: true }) }
})


test('shared-login discovery returns only server/account metadata and skips invalid sessions', async () => {
    const fake = await fixture(), directory = await mkdtemp(join(tmpdir(), 'plowshare-login-discovery-'))
    try {
        assert.deepEqual(await savedLoginServers(directory), [])
        const store = new Credentials(fake.base, directory)
        await store.login(fake.door, 'operator', 'private-password')
        await writeFile(join(directory, 'a'.repeat(64) + '.json'), '{broken')
        assert.deepEqual(await savedLoginServers(directory), [{ server: fake.base, account: 'operator' }])
        assert.ok(!JSON.stringify(await savedLoginServers(directory)).includes('refresh'))
        await new Credentials('http://localhost:12345', directory).login(fake.door, 'other', 'private-password')
        assert.equal((await savedLoginServers(directory)).length, 2)
        assert.equal(fake.counts().refreshes, 0)
    } finally { await fake.close(); await rm(directory, { recursive: true, force: true }) }
})

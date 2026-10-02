#!/usr/bin/env node
import { CredentialError } from 'plowshare-client-node/credentials'
import { authenticateConfigured } from 'plowshare-client-node/session'
import { MustChangePassword, SignInRefused } from 'plowshare-client-ts/binding/auth'
import { dispatch, request } from 'plowshare-client-ts/operations/direct'
import type { Request } from 'plowshare-client-ts/operations/direct'
import { respond } from './protocol.js'
import type { Backend } from './adapter.js'

const control = new AbortController()
const stop = (): void => { control.abort(); process.stdin.destroy() }
process.once('SIGINT', stop)
process.once('SIGTERM', stop)
let session: Awaited<ReturnType<typeof authenticateConfigured>> | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let exit = 0
const diagnostic = (text: string): void => { process.stderr.write(text + '\n') }
try {
    let origin = process.env['PLOWSHARE_URL'] ?? 'http://127.0.0.1:8091'
    let timeout = Number(process.env['PLOWSHARE_TIMEOUT_MS'] ?? 120000)
    const args = process.argv.slice(2)
    for (let at = 0; at < args.length; at++) {
        if (args[at] === '--help') {
            diagnostic('Usage: plowshare-mcp [--url ORIGIN] [--timeout-ms N]\nAuthentication: saved plowshare-cli login, or PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD. MCP stdout is JSON-RPC only.\nOperations use WS; HTTP is authentication/bootstrap only. Local rooting requires an explicit absolute path.\nLocal commands default off; file bytes stream over WS for server-side conversion.')
            process.exit(0)
        }
        if (args[at] === '--url' && args[at + 1] !== undefined) origin = args[++at]!
        else if (args[at] === '--timeout-ms' && args[at + 1] !== undefined) timeout = Number(args[++at])
        else throw new Error('unknown or incomplete option; use --help')
    }
    if (!Number.isInteger(timeout) || timeout < 1 || timeout > 2147483647) throw new Error('timeout must be an integer between 1 and 2147483647 milliseconds')
    let base: string
    try {
        const url = new URL(origin)
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error()
        base = url.origin
    } catch { throw new Error('PLOWSHARE_URL/--url must be an HTTP(S) origin without credentials, path, query or fragment') }
    timer = setTimeout(stop, timeout)
    try { session = await authenticateConfigured(base, process.env, control.signal, diagnostic) }
    catch (failure) {
        if (failure instanceof CredentialError) throw failure
        if (failure instanceof SignInRefused) throw new Error('sign-in refused; check PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD')
        if (failure instanceof MustChangePassword) throw new Error('password change required; change it through an interactive client before starting MCP')
        throw new Error('authentication or connection failed before MCP started; check the configured server and restart')
    } finally { clearTimeout(timer); timer = undefined }
    const active = session
    const backend: Backend = {
        invoke: async (type, payload) => {
            let result: Awaited<ReturnType<typeof dispatch>>
            // invoke's generic signature holds the type/payload relation; TS
            // loses that relation when distributing the public request union.
            try { result = await dispatch(active, request(type, payload) as Request) }
            catch { throw new Error('the WS connection was lost or unreadable; completion is unknown; no mutation was replayed or cancelled; restart the MCP client') }
            if (result.kind === 'invalid-response') throw new Error('the server returned an unreadable operation result; completion is unknown; no mutation was replayed or cancelled')
            if (result.kind === 'refused' && !['OK', 'CREATED', 'NO_CONTENT', 'ACCEPTED'].includes(result.outcome.code)) throw new Error(`the Plowshare server answered ${result.outcome.code}: ${result.outcome.said ?? 'request refused'}`)
            return result.outcome.payload
        },
        root: (project, path) => active.root(project, path), runSession: () => active.runSession(),
    }
    // One request at a time, like the Java transport. The event and file channels
    // remain live while a tool waits. A per-request deadline detaches; never replay.
    let buffered = ''
    process.stdin.setEncoding('utf8')
    for await (const chunk of process.stdin) {
        buffered += String(chunk)
        if (Buffer.byteLength(buffered) > 8 * 1024 * 1024) throw new Error('MCP input exceeded the 8 MiB message buffer; restart the client')
        for (let end = buffered.indexOf('\n'); end >= 0; end = buffered.indexOf('\n')) {
            const input = buffered.slice(0, end); buffered = buffered.slice(end + 1)
            timer = setTimeout(stop, timeout)
            let response
            try { response = await respond(input, backend) }
            finally { clearTimeout(timer); timer = undefined }
            if (response !== undefined) await new Promise<void>((resolve, reject) => process.stdout.write(JSON.stringify(response) + '\n', failure => failure ? reject(failure) : resolve()))
            if (control.signal.aborted) break
        }
        if (control.signal.aborted) { exit = 5; break }
    }
    if (!control.signal.aborted && buffered.trim()) {
        timer = setTimeout(stop, timeout)
        const response = await respond(buffered, backend)
        if (response !== undefined) await new Promise<void>((resolve, reject) => process.stdout.write(JSON.stringify(response) + '\n', failure => failure ? reject(failure) : resolve()))
    }
    if (control.signal.aborted) exit = 5
} catch (failure) {
    if (!control.signal.aborted) diagnostic(failure instanceof Error ? failure.message : 'MCP failed')
    exit = session === undefined ? 2 : 5
} finally {
    if (timer !== undefined) clearTimeout(timer)
    session?.close()
    process.removeListener('SIGINT', stop)
    process.removeListener('SIGTERM', stop)
}
process.exitCode = exit

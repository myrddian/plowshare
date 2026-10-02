import { Credentials, CredentialError, credentialDirectory } from 'plowshare-client-node/credentials'
import { ConnectionFault } from 'plowshare-client-ts/binding/connection'
import { discovery, commandHelp, effectiveScope, isMutation } from 'plowshare-client-ts/operations/discovery'
import { parseCommand, commandProblem, withSession } from 'plowshare-client-ts/operations/commands'
import { changePassword, MustChangePassword, PasswordRefused, SignInRefused } from 'plowshare-client-ts/binding/auth'
import type { Session } from 'plowshare-client-node/session'
import { syncer, type Syncer } from 'plowshare-client-node/sync/syncer'
import { syncAction } from 'plowshare-client-ts/operations/union'
import { dispatch, request, WAIT_OPERATIONS, waitForJob, waitForOrchestration } from 'plowshare-client-ts/operations/direct'
import { followCommand, jobProgress, observationAccepted, observedConversation } from 'plowshare-client-ts/operations/observation'
import type { Result } from 'plowshare-client-ts/operations/direct'
import { HELP, options, Usage } from './options.js'
import { authenticateConfigured, canonicalRoot, pause } from './platform.js'

export interface IO {
    readonly env: Readonly<Record<string, string | undefined>>
    readonly stdout: (text: string) => void
    readonly stderr: (text: string) => void
    readonly stdin: (signal: AbortSignal) => Promise<string>
    readonly newPassword?: (signal: AbortSignal) => Promise<string>
    readonly login?: (signal: AbortSignal) => Promise<{ handle: string; password: string }>
    readonly signal?: AbortSignal
}

function fields(value: unknown): Record<string, unknown> {
    return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {}
}

export function exitFor(result: Result): number {
    switch (result.kind) {
        case 'refused': return 1
        case 'invalid-response': return 5
        case 'accepted': case 'running': case 'cancelling': return 3
        case 'incomplete': return 4
        case 'completed': {
            if (result.job === undefined) return 0
            const ending = fields(fields(result.outcome.payload)['outcome'])['ending']
            if (ending === 'ANSWERED') return 0
            if (['UNAVAILABLE', 'SUB_AGENT_FAILED', 'SESSION_GONE', 'CALL_FAILURES'].includes(String(ending))) return 1
            // A future ending is carried intact but must not be reported as success.
            return 4
        }
    }
}

/** Ordinary commands return one result; explicit observers emit progress until
 * a durable outcome, loss, interrupt or deadline. Leaving never cancels a job. */
export async function run(args: readonly string[], io: IO): Promise<number> {
    let json = args.includes('--json')
    let connection: Session | undefined
    let syncing: Syncer | undefined
    let rooted = false, syncFailed = false
    let operation: string | undefined, job: string | undefined
    const identifiers: Record<string, { type: string; id: string }> = {}
    let orchestration: string | undefined, requestId: string | undefined, scope: unknown, mutation = false
    let interrupted = false, deadline = false, reading = false
    let submitted = false
    let observing = false, lost = false, active = true
    let progress: ReturnType<typeof jobProgress> | undefined
    const control = new AbortController()
    const interrupt = (): void => { interrupted = true; control.abort() }
    io.signal?.addEventListener('abort', interrupt, { once: true })
    if (io.signal?.aborted) interrupt()
    let timer: ReturnType<typeof setTimeout> | undefined
    const recovery = (code: string): Record<string, unknown> => {
        let nextActions: unknown[]
        if (code === 'SERVER_REFUSED') nextActions = [{ action: 'correct-input', operation }, { action: 'check-authority', operation }]
        else if (orchestration) nextActions = [{ action: 'inspect', command: `orchestration status ${orchestration}` }, { action: 'wait', command: `orchestration wait ${orchestration}` }]
        else if (job) nextActions = [{ action: 'inspect', command: `job status ${job}` }, { action: 'wait', command: `job wait ${job}` }]
        else if (submitted && operation === 'orchestration.start' && requestId) nextActions = [{ action: 'lookup-receipt', command: `orchestration receipt ${requestId}` }]
        else if (!submitted) nextActions = [{ action: code === 'INVALID_INPUT' ? 'correct-input' : code === 'AUTHENTICATION_REQUIRED' ? 'authenticate' : 'retry' }]
        else if (mutation) nextActions = [{ action: 'inspect', operation, requestId, replay: false }]
        else nextActions = [{ action: 'retry-read', operation }]
        return {
            code, scope, submission: !submitted ? 'not-submitted' : reading ? 'response-received' : 'uncertain',
            ids: { ...identifiers, ...(job ? { job: { type: 'job', id: job } } : {}),
                ...(orchestration ? { orchestration: { type: 'orchestration', id: orchestration } } : {}),
                ...(requestId ? { request: { type: 'request-uuid', id: requestId } } : {}) },
            nextActions, automaticReplay: false,
        }
    }
    const fail = (status: string, said: string, exit: number, code: string): number => {
        if (json) io.stdout(JSON.stringify({ status, said, ...(operation === undefined ? {} : { operation }), ...(job === undefined ? {} : { job }), ...(orchestration ? { orchestration } : {}), ...recovery(code) }) + '\n')
        else io.stderr(said + (orchestration ? `; orchestration ${orchestration} can be inspected with orchestration status` : job === undefined ? '' : `; job ${job} can be inspected with job status`) + '\n')
        return exit
    }
    try {
        const opts = options(args, io.env)
        json = opts.json
        if (opts.help) {
            io.stdout(json ? JSON.stringify({ status: 'help', ...discovery() }) + '\n' : HELP + '\n\nCommands and required fields:\n' + commandHelp() + '\n')
            return 0
        }
        timer = setTimeout(() => { deadline = true; control.abort() }, opts.command === 'login' && !args.includes('--timeout-ms') ? 300_000 : opts.timeoutMs)
        if (opts.command === 'login' || opts.command === 'logout') {
            if (opts.newConversation || opts.standalone) throw new Usage('--new-conversation and --standalone apply to agent run')
            if (opts.validate) throw new Usage('--validate applies to ordinary operation payloads; login/logout do not take it')
            const store = new Credentials(opts.base, credentialDirectory(io.env), control.signal)
            const door = { base: opts.base, fetch: (url: string, init: Parameters<typeof fetch>[1]) => fetch(url, { ...init, signal: control.signal, redirect: 'error' as const }) }
            if (opts.command === 'login') {
                let handle = io.env['PLOWSHARE_HANDLE'], password = io.env['PLOWSHARE_PASSWORD']
                if (!handle && !password) {
                    if (io.login === undefined) throw new Usage('Login needs a terminal, or PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD.')
                    const entered = await io.login(control.signal); handle = entered.handle; password = entered.password
                }
                if (!handle?.trim() || !password) throw new Usage('Provide both login handle and password.')
                let signed = await store.login(door, handle, password)
                if (signed.mustChangePassword) {
                    if (io.newPassword === undefined) throw new MustChangePassword()
                    const next = await io.newPassword(control.signal)
                    await changePassword(door, signed.tokens.access, password, next)
                    signed = await store.login(door, handle, next)
                    if (signed.mustChangePassword) throw new MustChangePassword()
                }
            } else await store.logout(door)
            io.stdout(json ? JSON.stringify({ operation: opts.command, status: 'completed' }) + '\n' : (opts.command === 'login' ? 'Signed in. Local clients can now use the saved session.' : 'Signed out.') + '\n')
            return 0
        }
        let command = opts.command
        if (opts.inputPayload) {
            const text = await io.stdin(control.signal)
            let payload: unknown
            try { payload = JSON.parse(text) } catch { throw new Usage('stdin must contain a valid JSON object') }
            if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) throw new Usage('stdin must contain a JSON object')
            command += ' ' + JSON.stringify(payload)
        }
        const presence = command === 'client root'
        const sync = command.startsWith('sync ') ? syncAction(command.slice(5) === 'status' ? '' : command.slice(5)) : undefined
        if (command.startsWith('sync ') && sync === undefined) throw new Usage('sync takes on, off, status, hidden <paths>, conflicts or resolve <path> mine|theirs|merge|done')
        if ((presence || sync !== undefined) && (opts.wait || opts.inputPayload)) throw new Usage('client root and sync do not take --wait, --watch or --payload')
        let directory: string | undefined
        if (opts.root !== undefined) {
            try { directory = await canonicalRoot(opts.root) } catch { throw new Usage('--root must be an absolute, existing directory on this machine') }
        }
        let follow: ReturnType<typeof followCommand>
        try { follow = followCommand(command) } catch { throw new Usage('conversation follow needs one conversation id') }
        if (follow !== undefined && (opts.wait || opts.inputPayload)) throw new Usage('conversation follow takes one id and runs until interrupt or deadline; do not use --wait, --watch or --payload')
        let parsed = presence || sync !== undefined ? { kind: 'request' as const, request: request('union.status', { project: opts.project! }) }
            : follow === undefined ? parseCommand(command, opts.project) : { kind: 'request' as const, request: follow }
        if (parsed.kind !== 'request') throw new Usage(parsed.kind === 'usage' ? parsed.said : 'unknown command; see --help')
        if ((opts.newConversation || opts.standalone) && parsed.request.type !== 'agent.run') throw new Usage('--new-conversation and --standalone apply to agent run')
        if (parsed.request.type === 'agent.run') {
            const payload = parsed.request.payload
            if (opts.newConversation && (payload.conversation != null || payload.newConversation === false)) throw new Usage('--new-conversation conflicts with a conversation id or newConversation:false')
            if (opts.standalone && (payload.conversation != null || payload.newConversation === true)) throw new Usage('--standalone conflicts with a conversation id or newConversation:true')
            const create = opts.newConversation || (!opts.standalone && payload.conversation == null && payload.newConversation !== false)
            parsed = { kind: 'request', request: { ...parsed.request, payload: { ...payload,
                ...(create || opts.standalone ? { newConversation: create } : {}) } } }
            const problem = commandProblem('agent.run', parsed.request.payload as Record<string, unknown>)
            if (problem) throw new Usage(problem)
        }
        scope = effectiveScope(parsed.request)
        mutation = isMutation(parsed.request.type)
        const body = parsed.request.payload as Record<string, unknown>
        requestId = typeof body['requestId'] === 'string' ? body['requestId'] : undefined
        for (const [key, type] of Object.entries({ revision: 'revision-uuid', acquisition: 'acquisition-uuid', evidence: 'evidence-uuid', conversation: 'conversation', root: 'orchestration-root' })) {
            if (typeof body[key] === 'string') identifiers[key] = { type, id: body[key] }
        }
        if (parsed.request.type === 'job.status' || parsed.request.type === 'job.cancel') job = parsed.request.payload.job
        if (parsed.request.type === 'orchestration.status') orchestration = parsed.request.payload.id
        operation = presence ? 'client.root' : sync === undefined ? parsed.request.type : `sync.${sync.kind}`
        if (opts.watch && parsed.request.type.startsWith('orchestration.')) throw new Usage('orchestration uses --wait; --watch observes jobs')
        if (opts.wait && !WAIT_OPERATIONS.includes(parsed.request.type)) throw new Usage('--wait requires a job submission/status/cancel, approval answer or orchestration start/status/receipt')
        if (directory !== undefined && WAIT_OPERATIONS.includes(parsed.request.type) && !opts.wait) throw new Usage('--root requires --wait or --watch for job operations so files remain served until completion')
        if (directory !== undefined && 'project' in parsed.request.payload && parsed.request.payload.project !== undefined && parsed.request.payload.project !== opts.project) throw new Usage('a rooted invocation must use its named project')
        if (opts.validate) {
            if (presence || sync !== undefined || follow !== undefined) throw new Usage('--validate applies to ordinary operation payloads; persistent platform commands do not take it')
            io.stdout(JSON.stringify({ status: 'validated', operation, scope, mutation, payload: parsed.request.payload, executed: false }) + '\n')
            return 0
        }
        observing = opts.watch || follow !== undefined || presence || sync?.kind === 'on'
        const emit = (push: unknown): void => {
            if (json) io.stdout(JSON.stringify({ operation, status: 'event', event: push }) + '\n')
            else io.stdout(`${operation}: event\n${JSON.stringify(push, null, 2)}\n`)
        }
        progress = opts.watch ? jobProgress(emit) : undefined
        if (opts.watch && (parsed.request.type === 'job.status' || parsed.request.type === 'job.cancel')) progress?.identify(parsed.request.payload.job)
        let following = false
        const earlyGrowth: unknown[] = []
        connection = await authenticateConfigured(opts.base, io.env, control.signal, () => undefined, {
            onPresenceLost: () => { if (active && !control.signal.aborted) { lost = true; control.abort() } },
            onWrite: () => syncing?.changed(),
            onPush: push => {
                if (!active) return
                progress?.receive(push)
                if (follow !== undefined && observedConversation(push, follow.payload.conversation)) {
                    if (following) emit(push)
                    else { if (earlyGrowth.length === 64) earlyGrowth.shift(); earlyGrowth.push(push) }
                }
            },
            onClose: () => { if (active && (observing || directory !== undefined) && !control.signal.aborted) { lost = true; control.abort() } },
        })
        control.signal.throwIfAborted()
        const record = (status: string, extra: Record<string, unknown>): void => {
            if (json) io.stdout(JSON.stringify({ status, ...extra }) + '\n')
            else io.stdout(`${status}: ${JSON.stringify(extra)}\n`)
        }
        if (directory !== undefined) {
            const claim = (await connection.root(opts.project!, directory)).current
            control.signal.throwIfAborted()
            rooted = true
            if (presence || sync !== undefined) record('rooted', { project: claim.project, root: claim.root, commandDefault: 'off' })
            if (opts.sync || sync !== undefined) {
                syncing = syncer({ claim, handle: connection.handle, base: opts.base, asker: connection, bearer: () => connection!.bearer(), strict: true, signal: control.signal,
                    tell: (trouble, lines) => {
                        if (!active || control.signal.aborted) return
                        if (trouble) { syncFailed = true; control.abort() }
                        else record('sync', { lines: lines.map(line => line.replaceAll('/here', 'client root').replaceAll('/sync', 'sync')) })
                    },
                    standing: notice => { if (active && notice !== undefined && !control.signal.aborted) record('sync-notice', { notice }) },
                })
                // Enabling is its own explicit action. Reconnect does not create a union.
                if (sync?.kind !== 'on' && sync?.kind !== 'status' && sync?.kind !== 'conflicts' && sync?.kind !== 'hidden') await syncing.connect()
                if (sync !== undefined) await syncing.run(sync)
                control.signal.throwIfAborted()
            }
        }
        if (presence || sync?.kind === 'on') {
            operation = presence ? 'client.root' : 'sync.on'
            record('serving', { project: opts.project, syncRequested: opts.sync || sync?.kind === 'on' })
            await new Promise<void>((_, reject) => {
                control.signal.throwIfAborted()
                control.signal.addEventListener('abort', () => reject(new Error('presence stopped')), { once: true })
            })
        }
        if (sync !== undefined) {
            await syncing?.leave()
            control.signal.throwIfAborted()
            record('completed', { operation: `sync.${sync.kind}`, project: opts.project })
            return 0
        }
        if (follow !== undefined || opts.watch) {
            const subscription = follow ?? { type: 'job.stream', payload: { on: true } }
            const outcome = await connection.ask(subscription.type, subscription.payload)
            control.signal.throwIfAborted()
            if (!observationAccepted(outcome)) {
                if (json) io.stdout(JSON.stringify({ operation: subscription.type, status: 'refused', outcome }) + '\n')
                else io.stdout(`${subscription.type}: refused\n${outcome.said ?? outcome.code}\n`)
                return 1
            }
            if (follow !== undefined) {
                if (json) io.stdout(JSON.stringify({ operation, status: 'following', conversation: follow.payload.conversation, outcome }) + '\n')
                else io.stdout(`${operation}: following ${follow.payload.conversation}\n`)
                following = true
                for (const push of earlyGrowth) emit(push)
                await new Promise<void>((_, reject) => {
                    control.signal.throwIfAborted()
                    control.signal.addEventListener('abort', () => reject(new Error('observer stopped')), { once: true })
                })
            }
        }
        if (parsed.request.type === 'job.status' || parsed.request.type === 'job.cancel') job = parsed.request.payload.job
        submitted = true
        let result = await dispatch(connection, withSession(parsed.request, connection.session))
        reading = true
        job = result.job ?? job
        orchestration = result.orchestration ?? orchestration
        if (result.conversation) identifiers['conversation'] = { type: 'conversation', id: result.conversation }
        if (opts.wait && orchestration !== undefined && ['accepted', 'completed'].includes(result.kind)) {
            result = await waitForOrchestration(connection, orchestration, () => pause(opts.pollMs, control.signal), parsed.request.type === 'orchestration.status' ? result : undefined)
        }
        if (job !== undefined) progress?.identify(job)
        if (opts.watch && result.kind === 'accepted') {
            if (json) io.stdout(JSON.stringify({ operation, status: result.kind, outcome: result.outcome, job }) + '\n')
            else io.stdout(`${operation}: accepted; job ${job}\n`)
        }
        if (opts.wait && job !== undefined && ['accepted', 'running', 'cancelling'].includes(result.kind)) {
            if (opts.watch) {
                if (result.kind === 'accepted') result = await dispatch(connection, request('job.status', { job }))
                while (result.kind === 'running' || result.kind === 'cancelling') {
                    if (json) io.stdout(JSON.stringify({ operation: 'job.status', status: result.kind, outcome: result.outcome, job }) + '\n')
                    else io.stdout(`job.status: ${result.kind}; job ${job}\n`)
                    await pause(opts.pollMs, control.signal)
                    result = await dispatch(connection, request('job.status', { job }))
                }
            } else {
                if (result.kind === 'running') await pause(opts.pollMs, control.signal)
                result = await waitForJob(connection, job, () => pause(opts.pollMs, control.signal))
            }
        }
        await syncing?.leave()
        control.signal.throwIfAborted()
        if (json) {
            const state = fields(fields(result.outcome.payload)['orchestration'])['state'] ?? fields(result.outcome.payload)['state']
            const code = result.kind === 'invalid-response' ? 'INVALID_SERVER_RESPONSE'
                : result.orchestration && result.outcome.code === 'OK' ? 'ORCHESTRATION_FAILED' : 'SERVER_REFUSED'
            io.stdout(JSON.stringify({ operation, status: result.kind, outcome: result.outcome,
                ...(job === undefined ? {} : { job }), ...(orchestration ? { orchestration, state } : {}),
                ...(identifiers['conversation'] ? { conversation: identifiers['conversation'].id } : {}),
                ...(result.kind === 'invalid-response' || result.kind === 'refused' ? recovery(code) : {}),
            }) + '\n')
        }
        else {
            const conversation = identifiers['conversation']?.id
            const detail = result.outcome.payload === undefined ? '' : '\n' + JSON.stringify(result.outcome.payload, null, 2)
            const said = result.outcome.said === undefined ? '' : '\n' + result.outcome.said
            io.stdout(`${operation}: ${result.kind}${conversation ? `; conversation ${conversation}` : ''}${orchestration ? `; orchestration ${orchestration}` : job === undefined ? '' : `; job ${job}`}${said}${detail}\n`)
        }
        return exitFor(result)
    } catch (error) {
        if (error instanceof CredentialError) return fail('error', error.message, 2, 'AUTHENTICATION_REQUIRED')
        if (error instanceof PasswordRefused) return fail('error', error.message, 2, 'AUTHENTICATION_REQUIRED')
        if (error instanceof Usage) return fail('error', error.message, 2, 'INVALID_INPUT')
        if (error instanceof SignInRefused) return fail('error', 'sign-in refused; check PLOWSHARE_HANDLE and PLOWSHARE_PASSWORD', 2, 'AUTHENTICATION_REQUIRED')
        if (error instanceof MustChangePassword) return fail('error', 'password change required; change it through an interactive client before using this CLI', 2, 'PASSWORD_CHANGE_REQUIRED')
        if (syncFailed) return fail('unknown', 'synchronization failed; inspect union status and local sync conflicts before retrying; no mutation was replayed', 5, 'SYNC_FAILED')
        if (error instanceof ConnectionFault && error.code === 'NOT_SUBMITTED') submitted = false
        const failureCode = error instanceof ConnectionFault && error.code === 'INVALID_ENVELOPE' ? 'INVALID_SERVER_RESPONSE'
            : interrupted ? 'INTERRUPTED' : deadline ? 'TIMEOUT' : reading || !mutation && submitted ? 'READ_FAILED' : submitted ? 'SUBMISSION_UNCERTAIN' : 'CONNECTION_BEFORE_SUBMISSION'
        // Native errors and upgrade URLs can carry credentials. Never print their contents.
        if ((observing || rooted) && control.signal.aborted) return fail(lost ? 'unknown' : 'stopped', lost
            ? 'connection or file presence lost; observation is incomplete and local files withdrawn; no request was replayed or job cancelled'
            : 'stopped by interrupt or deadline; local files withdrawn; no request was replayed or job cancelled', 5, failureCode)
        if (control.signal.aborted) return fail(submitted ? 'unknown' : 'error', submitted
            ? 'interrupted or deadline reached; completion is unknown; no mutation was replayed or cancelled'
            : 'interrupted or deadline reached before the operation was submitted', 5, failureCode)
        return fail(submitted ? 'unknown' : 'error', submitted
            ? 'connection lost or response unreadable; completion is unknown; no mutation was replayed'
            : 'authentication or connection failed before the operation was submitted', 5, failureCode)
    } finally {
        active = false
        progress?.close()
        if (timer !== undefined) clearTimeout(timer)
        io.signal?.removeEventListener('abort', interrupt)
        syncing?.stop()
        await connection?.release()
        connection?.close()
    }
}

import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import assert from 'node:assert/strict'
import test from 'node:test'
import { options, Usage } from './options.js'
import { exitFor, run } from './run.js'

test('explicit scope overrides the environment and leaves payload scope to shared parsing', () => {
    const env = { PLOWSHARE_PROJECT: 'inherited' }
    assert.equal(options(['--project', 'chosen', 'memory', 'index'], env).project, 'chosen')
    assert.equal(options(['--global', 'memory', 'index'], env).project, undefined)
    assert.equal(options(['memory', 'recall', 'a question'], env).command, 'memory recall a question')
    assert.throws(() => options(['memory', 'index'], { PLOWSHARE_PROJECT: ' ' }), Usage)
    assert.throws(() => options(['--global', '--project', 'chosen', 'memory', 'index'], env), Usage)
})

test('job wait/poll/result share the status request with explicit wait policy', () => {
    assert.equal(options(['job', 'wait', 'j'], {}).command, 'job status j')
    assert.equal(options(['job', 'wait', 'j'], {}).wait, true)
    assert.equal(options(['job', 'poll', 'j'], {}).wait, false)
    assert.equal(options(['job', 'result', 'j'], {}).command, 'job status j')
    assert.equal(options(['--wait', 'memory', 'digest'], {}).wait, true)
    assert.equal(options(['job', 'watch', 'j'], {}).command, 'job status j')
    assert.equal(options(['job', 'watch', 'j'], {}).watch, true)
    assert.equal(options(['--watch', 'agent', 'run', '{}'], {}).wait, true)
})

test('rejects credential-bearing URLs, arguments and invalid pacing without echoing them', () => {
    const secret = 'never-print-this'
    for (const args of [
        ['--url', `https://user:${secret}@example.com`, 'memory', 'index'],
        ['--password', secret, 'memory', 'index'], ['--url', 'https://example.com/v1', 'memory', 'index'],
        ['--poll-ms', '0', 'job', 'wait', 'j'], ['--timeout-ms', 'Infinity', 'memory', 'index'],
        ['--timeout-ms', '2147483648', 'memory', 'index'], ['--payload', 'file.json', 'memory', 'index'],
        ['--payload', '-', 'memory', 'read', 'm'],
    ]) {
        assert.throws(() => options(args, {}), error => error instanceof Usage && !error.message.includes(secret))
    }
})

test('help and input errors need no credentials, terminal or network', async t => {
    const directory = await mkdtemp(join(tmpdir(), 'plowshare-empty-login-'))
    t.after(() => rm(directory, { recursive: true, force: true }))
    let output = '', errors = '', reads = 0
    const io = { env: { PLOWSHARE_CONFIG_DIR: directory }, stdout: (text: string) => { output += text }, stderr: (text: string) => { errors += text }, stdin: async () => { reads++; return '{}' } }
    assert.equal(await run(['--json', '--help'], io), 0)
    assert.equal(JSON.parse(output).status, 'help')
    assert.equal(reads, 0)
    output = ''
    assert.equal(await run(['--json', 'memory', 'curate'], io), 2)
    assert.match(JSON.parse(output).said, /needs project/)
    output = ''
    assert.equal(await run(['--json', 'memory', 'index'], io), 2)
    assert.match(JSON.parse(output).said, /Sign in first/)
    assert.equal(errors, '')
    output = ''
    assert.equal(await run(['--json', 'conversation', 'follow'], io), 2)
    assert.match(JSON.parse(output).said, /conversation id/)
    output = ''
    assert.equal(await run(['--json', '--watch', 'conversation', 'follow', 'c'], io), 2)
    assert.match(JSON.parse(output).said, /do not use/)
    output = ''
    assert.equal(await run(['--json', '--payload', '-', 'memory', 'index'], { ...io, stdin: async () => '[]' }), 2)
    assert.match(JSON.parse(output).said, /JSON object/)
})

test('terminal job exits preserve truncation, awaiting and cancellation rather than claiming success', () => {
    const ended = (ending: string) => exitFor({ kind: 'completed', job: 'j', outcome: { code: 'OK', payload: { outcome: { ending } } } })
    assert.equal(ended('ANSWERED'), 0)
    for (const ending of ['CALL_BUDGET', 'TURN_CAP', 'CANCELLED', 'AWAITING', 'STUCK', 'future-ending']) assert.equal(ended(ending), 4)
    for (const ending of ['UNAVAILABLE', 'SUB_AGENT_FAILED', 'SESSION_GONE', 'CALL_FAILURES']) assert.equal(ended(ending), 1)
    assert.equal(exitFor({ kind: 'accepted', job: 'j', outcome: { code: 'ACCEPTED' } }), 3)
    assert.equal(exitFor({ kind: 'incomplete', outcome: { code: 'OK' } }), 4)
})

test('options may follow commands and offline validation resolves scope without authentication', async () => {
    let output = ''
    const io = { env: { PLOWSHARE_HANDLE: 'should-not-authenticate' }, stdout: (text: string) => { output += text }, stderr: () => {}, stdin: async () => '{}' }
    const key = '00000000-0000-0000-0000-000000000001'
    assert.equal(await run(['orchestration', 'start', JSON.stringify({ agent: 'caller', definition: 'custom', request: 'task', requestId: key }), '--project', 'repo', '--json', '--validate'], io), 0)
    assert.deepEqual(JSON.parse(output), { status: 'validated', operation: 'orchestration.start', scope: { kind: 'project', project: 'repo' }, mutation: true, payload: { agent: 'caller', definition: 'custom', request: 'task', requestId: key, project: 'repo' }, executed: false })
    output = ''
    assert.equal(await run(['information','acquire',JSON.stringify({scope:{kind:'personal'},requestId:key,url:'https://example.test/source'}),'--validate','--json'],io),0)
    assert.equal(JSON.parse(output).operation,'information.acquire')
    output = ''
    assert.equal(await run(['--json','--help'],io),0)
    const help=JSON.parse(output)
    assert.ok(help.commands.some((row: { command: string })=>row.command==='information acquire'))
    assert.ok(help.commands.some((row: { command: string })=>row.command==='orchestration start'))
    assert.match(help.exits['3'],/accepted/)
    assert.equal(options(['orchestration','wait','orc_one','--json'],{}).wait,true)
})

test('agent runs default to a new scoped conversation and explicit modes validate offline', async () => {
    let output = ''
    const io = { env: { PLOWSHARE_PROJECT: '' }, stdout: (text: string) => { output += text }, stderr: () => {}, stdin: async () => '{}' }
    const validate = async (payload: Record<string, unknown>, flags: string[] = []) => {
        output = ''
        const code = await run(['agent', 'run', JSON.stringify(payload), '--json', '--validate', ...flags], io)
        return { code, value: JSON.parse(output) }
    }
    const basic = { agent: 'a', task: 'work' }
    let result = await validate(basic)
    assert.equal(result.code, 0)
    assert.deepEqual(result.value.scope, { kind: 'global' })
    assert.equal(result.value.payload.newConversation, true)
    result = await validate(basic, ['--project', 'repo', '--new-conversation'])
    assert.equal(result.value.payload.project, 'repo')
    assert.equal(result.value.payload.newConversation, true)
    result = await validate({ ...basic, conversation: 'cnv_existing' })
    assert.equal(result.value.payload.newConversation, undefined)
    assert.deepEqual(result.value.scope, { kind: 'conversation', id: 'cnv_existing' })
    result = await validate(basic, ['--standalone'])
    assert.equal(result.value.payload.newConversation, false)
    assert.equal((await validate({ ...basic, images: ['img_1'] })).code, 2)
    assert.equal((await validate({ ...basic, images: ['img_1'] }, ['--standalone'])).code, 0)
    assert.equal((await validate({ ...basic, conversation: 'cnv_existing' }, ['--new-conversation'])).code, 2)
    assert.equal((await validate(basic, ['--new-conversation', '--standalone'])).code, 2)
    output = ''
    assert.equal(await run(['job', 'status', 'job_1', '--new-conversation', '--validate', '--json'], io), 2)
})

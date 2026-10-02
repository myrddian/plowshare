import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { CLI_OPERATIONS } from './catalog.ts'
import { isPayloadCommand, parseCommand, withSession } from './commands.ts'
import { dispatch, parseDirect, request } from './direct.ts'

describe('operator WS boundaries', () => {
    it('keeps standalone runs separate from conversations and preserves conversation-owned scope', () => {
        expect(parseCommand('agent run {"agent":"a","task":"work"}', 'repo')).toEqual({ kind: 'request', request: request('agent.run', { agent: 'a', task: 'work', project: 'repo' }) })
        expect(parseCommand('agent run {"agent":"a","task":"work","conversation":"c"}', 'repo')).toEqual({ kind: 'request', request: request('agent.run', { agent: 'a', task: 'work', conversation: 'c' }) })
        expect(parseCommand('agent run {"agent":"a","task":"work","project":null}', 'repo')).toMatchObject({ request: { payload: { project: null } } })
        expect(parseDirect('project list').kind).toBe('unhandled')
        expect(parseDirect('conversation list').kind).toBe('unhandled')
    })
    it('preserves server defaults and explicit global tiers, without adding projects to corpus reads', () => {
        expect(parseCommand('conversation open')).toEqual({ kind: 'request', request: request('conversation.open', {}) })
        expect(parseCommand('conversation list {"project":null}', 'repo')).toMatchObject({ request: { payload: { project: null } } })
        expect(parseCommand('document search build instructions', 'repo')).toEqual({ kind: 'request', request: request('document.search', { query: 'build instructions' }) })
        expect(parseCommand('document outline d')).toEqual(parseCommand('document detail d'))
        expect(parseCommand('document citations')).toEqual({ kind: 'request', request: request('document.citations', {}) })
        expect(parseCommand('conversation trajectory {"conversation":"c","tail":true,"limit":20}')).toMatchObject({ kind: 'request', request: { payload: { tail: true } } })
    })
    it('matches session identity only for job submissions that use it, preserving explicit absence', () => {
        expect(withSession(request('agent.run', { agent: 'a', task: 'x' }), 's')).toEqual(request('agent.run', { agent: 'a', task: 'x', session: 's' }))
        expect(withSession(request('conversation.resume', { conversation: 'c' }), 's')).toEqual(request('conversation.resume', { conversation: 'c', session: 's' }))
        const absent = request('agent.run', { agent: 'a', task: 'x', session: null })
        expect(withSession(absent, 's')).toBe(absent)
        const external = request('conversation.resume', { conversation: 'c', session: 'other' })
        expect(withSession(external, 's')).toBe(external)
        const read = request('agent.list', {})
        expect(withSession(read, 's')).toBe(read)
    })
    it.each([
        'agent run {"agent":"a","task":"x","maxModelCalls":10}',
        'agent run {"agent":"a","task":"x","conversation":"c","project":"p"}',
        'agent run {"agent":"a","task":"x","conversation":"c","images":["i"]}',
        'agent run {"agent":"a","task":"x","noTurnCap":true,"maxTurns":4}',
        'agent run {"agent":"a","task":"x","images":[1]}',
        'conversation open {"noBudget":true,"maxModelCalls":20}',
        'conversation resume {"conversation":"c","session":""}',
        'conversation trajectory {"conversation":"c","tail":true,"after":0}',
        'conversation trajectory {"conversation":"c","tail":3}',
        'conversation projection {"conversation":"c","turn":-1}',
        'document citations {"document":"d","conversation":"c"}',
        'web search {"query":"x"}', 'web search {"query":"x","pageSize":10,"max":10,"page":0}',
        'project define {"name":"p"}', 'project lend {"project":"p","roots":[]}',
        'project workspace {"project":"p","workspace":42}', 'project member-add {"project":"p"}',
    ])('refuses unfulfillable or malformed requests locally: %s', line => {
        expect(parseCommand(line).kind).toBe('usage')
    })
    it('requires declared WS verbs for stdin and verifies every discriminator against the registered server surface', () => {
        const java = readFileSync(new URL('../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java', import.meta.url), 'utf8')
        for (const [command, type] of Object.entries(CLI_OPERATIONS)) {
            expect(java).toContain(`"${type}"`)
            expect(isPayloadCommand(command)).toBe(true)
            expect(isPayloadCommand(command + ' argument')).toBe(false)
        }
        expect(isPayloadCommand('document upload')).toBe(false)
    })
    it.each([
        request('agent.run', { agent: 'a', task: 'x' }),
        request('conversation.resume', { conversation: 'c' }),
        request('document.ask', { document: 'd', question: 'x' }),
    ])('requires a nonblank accepted job handle without hidden polling: %s', async asked => {
        const types: string[] = []
        const result = await dispatch({ async ask(type) { types.push(type); return { code: 'ACCEPTED', payload: { id: 'j', future: true } } } }, asked)
        expect(result).toMatchObject({ kind: 'accepted', job: 'j', outcome: { payload: { future: true } } })
        expect(types).toEqual([asked.type])
        expect((await dispatch({ async ask() { return { code: 'ACCEPTED', payload: {} } } }, asked)).kind).toBe('invalid-response')
        expect((await dispatch({ async ask() { return { code: 'OK', payload: { id: 'j' } } } }, asked)).kind).toBe('invalid-response')
    })
    it('retains embedded provider refusals and real continuation offsets intact', async () => {
        const refused = { code: 'OK', payload: { refusal: 'provider allowance exhausted', hits: [], page: 2, future: true } } as const
        expect(await dispatch({ async ask() { return refused } }, request('web.search', { query: 'x', pageSize: 10, max: 20, page: 2 }))).toEqual({ kind: 'refused', outcome: refused })
        const fetched = { code: 'OK', payload: { url: 'https://example.com', title: null, total: 1000, refusal: null, offset: 0, nextOffset: 809, hasMore: true, text: 'window' } } as const
        expect(await dispatch({ async ask() { return fetched } }, request('web.fetch', { url: 'https://example.com' }))).toEqual({ kind: 'completed', outcome: fetched })
    })
})

describe('administrative commands preserve authority', () => {
    it('does not confuse approval scopes or inherit project into account/conversation questions', () => {
        expect(parseCommand('approval list', 'repo')).toMatchObject({ request: { payload: { project: 'repo' } } })
        expect(parseCommand('approval list {"mine":true}', 'repo')).toMatchObject({ request: { payload: { mine: true } } })
        expect(parseCommand('approval list {"conversation":"c"}', 'repo')).toEqual({ kind: 'request', request: request('approval.list', { conversation: 'c' }) })
        expect(parseCommand('approval answer {"id":"a","decision":"project","prefix":["program",""]}')).toMatchObject({ kind: 'request' })
    })
    it('keeps schedule reading a proposal and conversation triggers on conversation-owned limits', () => {
        expect(parseCommand('schedule read {"text":"at noon here","conversation":"c"}', 'repo')).toMatchObject({ request: { payload: { text: 'at noon here', conversation: 'c', project: 'repo' } } })
        expect(parseCommand('trigger define {"trigger":"t","event":"e","agent":"a","task":"x","conversation":"c"}', 'repo')).toEqual({ kind: 'request', request: request('trigger.define', { trigger: 't', event: 'e', agent: 'a', task: 'x', conversation: 'c' }) })
        expect(parseCommand('trigger define {"trigger":"t","event":"e","agent":"a","task":"x"}', 'repo')).toMatchObject({ request: { payload: { project: 'repo' } } })
        expect(parseCommand('event fire {"event":"e","data":{"nested":[null,true,{"x":3}]}}')).toMatchObject({ kind: 'request' })
        expect(parseCommand('orchestration answer {"id":"o","answer":"","choices":{"question":["first"]}}')).toMatchObject({ kind: 'request' })
    })
    it.each([
        'approval list', 'approval list {"mine":true,"project":"p"}',
        'approval answer {"id":"a","decision":"allow"}', 'approval answer {"id":"a","decision":"project"}',
        'inbox read {"items":[]}', 'schedule pause {"schedule":"s"}', 'trigger pause {"trigger":"t","paused":"yes"}',
        'trigger define {"trigger":"t","event":"e","agent":"a","task":"x","conversation":"c","maxModelCalls":5}',
        'board topup {"topic":"t"}', 'event fire {"event":"e","data":[1]}',
        'orchestration answer {"id":"o"}', 'orchestration record {"root":"o","tail":true,"before":2}',
        'web search {"query":"x","pageSize":10,"max":10,"page":1,"queueCap":1}',
    ])('rejects ambiguous or unsupported administrative requests before dispatch: %s', line => {
        expect(parseCommand(line).kind).toBe('usage')
    })
    it('does not claim a persistent subscription or rooted union mutation from a one-shot invocation', () => {
        for (const line of ['job stream', 'conversation follow c', 'union enable', 'union ready', 'union conflict-open', 'union conflict-resolve']) {
            expect(parseCommand(line)).toMatchObject({ kind: 'usage', said: expect.stringContaining('requires') })
            expect(isPayloadCommand(line)).toBe(false)
        }
        expect(parseCommand('union status', 'repo')).toEqual({ kind: 'request', request: request('union.status', { project: 'repo' }) })
    })
    it('treats approval continuations as pending jobs while preserving their original OK decision', async () => {
        const asked = request('approval.answer', { id: 'a', decision: 'once' })
        const original = { code: 'OK', payload: { id: 'a', state: 'allowed', job: 'j', busy: false, note: null } } as const
        expect(await dispatch({ async ask() { return original } }, asked)).toEqual({ kind: 'accepted', job: 'j', outcome: original })
        for (const busy of [true, false]) {
            const decision = { code: 'OK', payload: { id: 'a', state: 'allowed', job: null, busy, note: busy ? 'turn busy' : null } } as const
            expect(await dispatch({ async ask() { return decision } }, asked)).toEqual({ kind: 'completed', outcome: decision })
        }
        for (const body of [{ id: 'other', state: 'allowed', busy: false }, { id: 'a', state: 'allowed', job: '', busy: false }, { id: 'a', state: 'allowed', job: 'j', busy: true }]) {
            expect((await dispatch({ async ask() { return { code: 'OK', payload: body } } }, asked)).kind).toBe('invalid-response')
        }
    })
})

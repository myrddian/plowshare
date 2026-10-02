import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import type { Outcome } from '../binding/envelope.ts'
import { CONVERSATION_OPERATIONS, conversationReply, type ConversationOperation } from './conversation-replies.ts'
import { dispatch, request, type Request } from './direct.ts'

const fixtures = JSON.parse(readFileSync(new URL('../../../test-support/contracts/ws-conversation-fixtures.json', import.meta.url), 'utf8')) as Record<ConversationOperation, Outcome>
const asked = (type: ConversationOperation): Request => ({ type, payload: { conversation: 'c' } }) as Request
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
function missingFields(value: unknown): unknown[] {
    if (Array.isArray(value)) return value.flatMap((row, index) => missingFields(row).map(changed => value.map((each, n) => n === index ? changed : each)))
    if (!object(value)) return []
    return Object.keys(value).filter(key => key !== 'future').flatMap(key => {
        const without = { ...value }; delete without[key]
        return [without, ...missingFields(value[key]).map(changed => ({ ...value, [key]: changed }))]
    })
}

describe('complete conversation, agent and project WS replies', () => {
    it('covers each operation with a complete fixture, including no latest conversation', () => {
        expect(Object.keys(fixtures).sort()).toEqual([...CONVERSATION_OPERATIONS].sort())
    })
    it.each(CONVERSATION_OPERATIONS)('%s retains fields, raw row order and success disposition', async type => {
        const outcome = fixtures[type], calls: unknown[] = []
        expect(conversationReply(type, outcome)).toBe(outcome)
        const result = await dispatch({ async ask(type, payload) { calls.push({ type, payload }); return outcome } }, asked(type))
        expect(result).toEqual({ kind: 'completed', outcome })
        expect(result.outcome).toBe(outcome)
        expect(calls).toEqual([asked(type)])
    })
    it.each(CONVERSATION_OPERATIONS)('%s refuses partial nested rows and incorrect success codes without replay', async type => {
        const original = fixtures[type]
        const invalid: Outcome[] = [{ ...original, code: 'ACCEPTED' },
            { ...original, code: original.code === 'NO_CONTENT' ? 'OK' : 'NO_CONTENT' },
            { ...original, payload: { entries: [], members: [], future: true } },
            ...missingFields(original.payload).map(payload => ({ ...original, payload }))]
        if (type !== 'agent.define') invalid.push({ ...original, code: 'CREATED' })
        if (Array.isArray(original.payload)) invalid.push({ ...original, payload: [...original.payload, {}] })
        for (const outcome of invalid) {
            expect(conversationReply(type, outcome), JSON.stringify(outcome)).toBeUndefined()
            let calls = 0
            const result = await dispatch({ async ask() { calls++; return outcome } }, asked(type))
            expect(result.kind, JSON.stringify(outcome)).toBe('invalid-response')
            expect(result.outcome).toBe(outcome)
            expect(calls).toBe(1)
        }
        const refusal: Outcome = { code: 'NOT_FOUND', said: 'not in this account' }
        expect(await dispatch({ async ask() { return refusal } }, asked(type))).toEqual({ kind: 'refused', outcome: refusal })
    })
    it('keeps no latest conversation distinct from unreadable or refused listings', () => {
        for (const payload of [undefined, null, fixtures['conversation.open'].payload]) {
            expect(conversationReply('conversation.latest', { code: 'OK', ...(payload === undefined ? {} : { payload }) })).toBeDefined()
        }
        expect(conversationReply('conversation.list', { code: 'OK', payload: [] })).toBeDefined()
        expect(conversationReply('conversation.list', { code: 'OK' })).toBeUndefined()
        expect(conversationReply('conversation.list', { code: 'NOT_FOUND', payload: [] })).toBeUndefined()
    })
    it('accepts context reductions and provenance without inferring a token measurement or cache rate', () => {
        const context = fixtures['conversation.context'].payload as Record<string, unknown>
        expect((context['measuredTurns'] as Record<string, unknown>[])[1]!['grewBy']).toBe(-80)
        expect(context['cacheHitRate']).toBeNull()
        for (const field of ['systemPromptTokens','toolTokens','messageTokens']) {
            expect(conversationReply('conversation.context', { code: 'OK', payload: { ...context, [field]: 12 } })).toBeUndefined()
        }
        const unmeasured = { sent: null, sentAtTurn: null, turns: 0, turnsMeasured: 0, measuredTurns: [], systemPromptTokens: null, toolTokens: null, messageTokens: null, cacheHitRate: null, unavailable: [], prefix: null }
        expect(conversationReply('conversation.context', { code: 'OK', payload: unmeasured })).toBeDefined()
    })
    it('retains ejected excerpts and page coordinates, rejecting duplicate or foreign ordinals', () => {
        const page = fixtures['conversation.trajectory'].payload as Record<string, unknown>
        const entries = page['entries'] as Record<string, unknown>[]
        expect(entries[1]!['excerpt']).toBeNull()
        expect(conversationReply('conversation.trajectory', { code: 'OK', payload: { ...page, entries: [...entries, entries[0]] } })).toBeUndefined()
        for (const changed of [{ through: 1 }, { oldest: 9 }, { oldest: null }, { limit: 1 }, { total: 1 }]) {
            expect(conversationReply('conversation.trajectory', { code: 'OK', payload: { ...page, ...changed } })).toBeUndefined()
        }
        expect(conversationReply('conversation.trajectory', { code: 'OK', payload: { ...page, entries: [], oldest: null, more: null } })).toBeDefined()
    })
    it('accepts both definition dispositions and requires the requested lifecycle identity', async () => {
        expect(conversationReply('agent.define', { ...fixtures['agent.define'], code: 'OK' })).toBeDefined()
        const outcome: Outcome = { code: 'OK', payload: { id: 'other', lifecycle: 'archived' } }
        expect((await dispatch({ async ask() { return outcome } }, request('conversation.lifecycle', { conversation: 'c', lifecycle: 'archived' }))).kind).toBe('invalid-response')
    })
    it('mirrors each Java record, including nested context, entry and token contracts', () => {
        const source = ts.createSourceFile('conversation-replies.ts', readFileSync(new URL('./conversation-replies.ts', import.meta.url), 'utf8'), ts.ScriptTarget.Latest, true)
        const authorities: readonly [string, string, string, readonly string[], Record<string, string>?][] = [
            ['ConversationView','api/ConversationView','ConversationView',['project','maxModelCalls','modelCallsSpent','maxTurns','title']],
            ['ProjectView','api/ProjectView','ProjectView',['machine']], ['ProjectMembers','ws/ProjectMemberHandler','Changed',[]],
            ['AgentView','api/AgentView','AgentView',['model']], ['DefinedAgent','api/DefinedAgent','DefinedAgent',[]], ['LifecycleView','api/LifecycleView','LifecycleView',[]],
            ['TurnView','api/TurnView','TurnView',['promptTokens']], ['CompactionView','api/CompactionView','CompactionView',[]],
            ['OpenedView','api/EntryView','OpenedView',[]], ['AskedView','api/EntryView','AskedView',['salient','opened']],
            ['EntryView','api/EntryView','EntryView',['excerpt','ejectedAt','supersededBy','toolCallId','handle','recordedAt','tookMillis','dispatch','wireModel','completion','speaker','speakerName','outcome']],
            ['EntryPageView','api/EntryPageView','EntryPageView',['oldest','more']], ['TokenCount','llm/tokens/TokenCount','TokenCount',[],{ Basis: 'string' }],
            ['MeasuredTurn','api/ContextView','Measured',['grewBy','since']], ['Unavailable','api/ContextView','Unavailable',[]], ['ToolCost','api/ContextView','ToolCost',[]],
            ['Prefix','api/ContextView','Prefix',['contextLength']], ['ContextView','api/ContextView','ContextView',['sent','sentAtTurn','systemPromptTokens','toolTokens','messageTokens','cacheHitRate','prefix'],{ Measured: 'MeasuredTurn' }],
            ['ProjectionMessage','api/ProjectionView','Message',[]], ['ProjectionView','api/ProjectionView','ProjectionView',['turn'],{ Message: 'ProjectionMessage' }],
        ]
        expect(source.statements.filter(node => ts.isInterfaceDeclaration(node) && node.name.text !== 'ConversationReplies')).toHaveLength(authorities.length)
        for (const [type, path, name, nullable, renamed = {}] of authorities) {
            const java = readFileSync(new URL(`../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/${path}.java`, import.meta.url), 'utf8')
            const fields = new RegExp(`public record ${name}\\(([^)]*)\\)`, 's').exec(java)![1]!.split(',').map(part => {
                const [wireType, key] = part.trim().split(/\s+/)
                function mapped(raw: string): string {
                    const list = /^List<(.+)>$/.exec(raw)
                    if (list) return `readonly ${mapped(list[1]!)}[]`
                    return ({ String: 'string', Instant: 'string', int: 'number', Integer: 'number', Long: 'number', Double: 'number', boolean: 'boolean', Boolean: 'boolean', ...renamed } as Record<string,string>)[raw] ?? raw
                }
                return [key!, mapped(wireType!) + (nullable.includes(key!) ? ' | null' : '')]
            })
            const dto = source.statements.find(node => ts.isInterfaceDeclaration(node) && node.name.text === type) as ts.InterfaceDeclaration
            expect(dto, type).toBeDefined()
            expect(dto.members.map(member => {
                const field = member as ts.PropertySignature
                expect(field.questionToken, type).toBeUndefined()
                return [field.name.getText(source),field.type!.getText(source)]
            }), type).toEqual(fields)
        }
    })
})

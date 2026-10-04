import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import type { Outcome } from '../binding/envelope.ts'
import { INFORMATION_FRAMES, informationReply, type InformationFrame } from './information-replies.ts'
import { dispatch, type Request } from './direct.ts'
import { parseCommand } from './commands.ts'
import { InformationClient } from './information.ts'

const fixtures = JSON.parse(readFileSync(new URL('../../../test-support/contracts/ws-information-fixtures.json', import.meta.url), 'utf8')) as Record<InformationFrame, Outcome>
const asked = (type: InformationFrame): Request => ({type,payload:{scope:{kind:'personal'},...(type==='information.symbols'?{query:'load'}:{})}}) as Request

describe('scoped information request and receipt integration', () => {
    it('selects the code corpus explicitly over the existing socket and rejects unknown corpora', async () => {
        let sent: unknown
        const client = new InformationClient({ async ask(type,payload) {
            sent = { type, payload }; return { code: 'OK', payload: [] }
        } }, { kind: 'project', project: 'repo' }, 'code')
        await client.call('list')
        expect(sent).toEqual({ type: 'information.list', payload: { scope: {kind:'project',project:'repo'}, corpus:'code' } })
        expect(parseCommand('information list {"scope":{"kind":"personal"},"corpus":"code"}')).toMatchObject({
            kind:'request', request:{payload:{corpus:'code'}},
        })
        expect(parseCommand('information list {"scope":{"kind":"personal"},"corpus":"all"}')).toMatchObject({kind:'usage'})
    })
    it.each(INFORMATION_FRAMES)('%s validates the actual response family with one request', async type => {
        const outcome = fixtures[type]
        expect(informationReply(type,outcome)).toBe(outcome.payload)
        let calls = 0
        const result = await dispatch({async ask() { calls++; return outcome }},asked(type))
        expect(result.kind).toBe(outcome.code === 'ACCEPTED' ? 'accepted' : 'completed')
        expect(result.job).toBe(type === 'information.ask' ? 'j' : undefined)
        expect(calls).toBe(1)
        if (['information.read','information.ask','information.outline','information.status'].includes(type)) expect((await dispatch({async ask() { return outcome }},{type,payload:{scope:{kind:'personal'},revision:'another-revision'}} as Request)).kind).toBe('invalid-response')
        expect(informationReply(type,{code:'NO_CONTENT',payload:outcome.payload})).toBeUndefined()
        expect(informationReply(type,{code:outcome.code,payload:{}})).toBeUndefined()
    })
    it('validates syntax ranges and binds declaration results to the request', async () => {
        const outline = fixtures['information.outline']
        const payload = outline.payload as Record<string,unknown>
        const symbol = (payload['symbols'] as readonly Record<string,unknown>[])[0]!
        expect(informationReply('information.outline',{code:'OK',payload:{...payload,symbols:[{...symbol,end_offset:4}]}})).toBeUndefined()
        expect(informationReply('information.outline',{code:'OK',payload:{...payload,status:'complete'}})).toBeUndefined()
        expect(parseCommand('information outline {"scope":{"kind":"personal"},"corpus":"code","revision":"r"}').kind).toBe('request')
        expect(parseCommand('information symbols {"scope":{"kind":"personal"},"corpus":"code","query":"load","limit":101}').kind).toBe('usage')
        expect(parseCommand('information outline {"scope":{"kind":"personal"},"corpus":"documents","revision":"r"}').kind).toBe('usage')
        const symbols = fixtures['information.symbols']
        expect((await dispatch({async ask(){return symbols}},{type:'information.symbols',payload:{scope:{kind:'personal'},corpus:'code',query:'other'}})).kind).toBe('invalid-response')
        expect((await dispatch({async ask(){return symbols}},{type:'information.symbols',payload:{scope:{kind:'personal'},corpus:'code',query:'load',revision:'foreign'}})).kind).toBe('invalid-response')
    })
    it('does not turn queued source admissions into finished jobs or replay uncertain delivery', async () => {
        expect((await dispatch({async ask() { return fixtures['information.upload'] }},asked('information.upload'))).job).toBeUndefined()
        let calls = 0
        await expect(dispatch({async ask() { calls++; throw new Error('connection lost') }},asked('information.upload'))).rejects.toThrow('connection lost')
        expect(calls).toBe(1)
        expect((await dispatch({async ask() { return {code:'NOT_FOUND'} }},asked('information.list'))).kind).toBe('refused')
        expect(informationReply('information.ask',{code:'ACCEPTED',payload:{revision:'r'}})).toBeUndefined()
    })
    it('preserves explicit scope, mutation identity, exact offsets and report metadata', () => {
        const payload = {scope:{kind:'project',project:'research',includeShared:false},revision:'r',requestId:'stable',start:0,end:4,quote:'text',locator:'characters 0..4'}
        expect(parseCommand('information evidence.record '+JSON.stringify(payload))).toEqual({kind:'request',request:{type:'information.evidence.record',payload}})
        expect(parseCommand('information list {"scope":{"kind":"project","project":""}}').kind).toBe('usage')
        expect(parseCommand('information status {"scope":{"kind":"personal"}}').kind).toBe('usage')
        expect(parseCommand('information retry {"scope":{"kind":"personal"},"revision":"r"}').kind).toBe('usage')
        expect(parseCommand('information list {"scope":{"kind":"personal"},"owner":"someone"}').kind).toBe('usage')
        expect(informationReply('information.read',{code:'OK',payload:{revision:'r',start:0,end:5,total:5,text:'text'}})).toBeUndefined()
        expect(informationReply('information.evidence.read',{code:'OK',payload:{id:'e',revision_id:'r',start_offset:4,end_offset:2,quote:'text',locator:'x'}})).toBeUndefined()
    })
    it('keeps user and automatic tag filters distinct and rejects model writes to user tags', () => {
        const filter={tags:['my project'],autoTag:['postgresql'],author:'reader',documentAuthor:'Ada Lovelace',when:'2024-10',subtype:'markdown',search:'vector'}
        for (const operation of ['list','facets','search','rank']) {
            const payload={scope:{kind:'personal'},filter,...(['search','rank'].includes(operation)?{query:'vector'}:{})}
            expect(parseCommand('information '+operation+' '+JSON.stringify(payload))).toMatchObject({kind:'request',request:{payload}})
        }
        expect(parseCommand('information tags '+JSON.stringify({scope:{kind:'personal'},revision:'r',requestId:'stable',tags:[]}))).toMatchObject({kind:'request'})
        expect(parseCommand('information tags '+JSON.stringify({scope:{kind:'personal'},revision:'r',requestId:'stable',tags:[],autoTag:['invented']})).kind).toBe('usage')
        for(const filter of [{tags:'one'},{autoTag:['']},{when:'2024-13'},{kind:'howto'},{unknown:'anything'}])
            expect(parseCommand('information list '+JSON.stringify({scope:{kind:'personal'},filter})).kind).toBe('usage')
        const fixture=fixtures['information.facets'].payload as Record<string,unknown>
        expect(informationReply('information.facets',{code:'OK',payload:{...fixture,total:0}})).toBeUndefined()
    })
    it('counts distinct readiness outcomes and binds them to the requested fence', async () => {
        const payload={scope:{kind:'personal'},sources:[{revision:'r'}],waitMs:0}
        expect(parseCommand('information await '+JSON.stringify(payload))).toEqual({kind:'request',request:{type:'information.await',payload}})
        const outcome=fixtures['information.await']
        expect((await dispatch({async ask() {return outcome}},{type:'information.await',payload:{scope:{kind:'personal'},sources:[{revision:'foreign'}]}})).kind).toBe('invalid-response')
        expect(informationReply('information.await',{code:'OK',payload:{...outcome.payload as object,expected:2,settled:2,ready:2,outcomes:[{revision:'r',state:'ready'},{revision:'r',state:'ready'}]}})).toBeUndefined()
        expect(parseCommand('information await '+JSON.stringify({...payload,waitMs:30001})).kind).toBe('usage')
        expect(parseCommand('information await '+JSON.stringify({...payload,sources:[{revision:'r',acquisition:'a'}]})).kind).toBe('usage')
    })
})

import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import type { Outcome } from '../binding/envelope.ts'
import { INFORMATION_FRAMES, informationReply, type InformationFrame } from './information-replies.ts'
import { dispatch, type Request } from './direct.ts'
import { parseCommand } from './commands.ts'

const fixtures = JSON.parse(readFileSync(new URL('../../../test-support/contracts/ws-information-fixtures.json', import.meta.url), 'utf8')) as Record<InformationFrame, Outcome>
const asked = (type: InformationFrame): Request => ({type,payload:{scope:{kind:'personal'}}}) as Request

describe('scoped information request and receipt integration', () => {
    it.each(INFORMATION_FRAMES)('%s validates the actual response family with one request', async type => {
        const outcome = fixtures[type]
        expect(informationReply(type,outcome)).toBe(outcome.payload)
        let calls = 0
        const result = await dispatch({async ask() { calls++; return outcome }},asked(type))
        expect(result.kind).toBe(outcome.code === 'ACCEPTED' ? 'accepted' : 'completed')
        expect(result.job).toBe(type === 'information.ask' ? 'j' : undefined)
        expect(calls).toBe(1)
        if (['information.read','information.ask','information.status'].includes(type)) expect((await dispatch({async ask() { return outcome }},{type,payload:{scope:{kind:'personal'},revision:'another-revision'}} as Request)).kind).toBe('invalid-response')
        expect(informationReply(type,{code:'NO_CONTENT',payload:outcome.payload})).toBeUndefined()
        expect(informationReply(type,{code:outcome.code,payload:{}})).toBeUndefined()
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
})

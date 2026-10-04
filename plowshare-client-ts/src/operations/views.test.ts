import { describe, expect, it } from 'vitest'
import { answeredOf, backPageOf, projects, approvalsOf, entriesOf, conversations, agents } from './views.ts'

describe('shared client response readers', () => {
    it('keeps Personal display and routing identities distinct', () => {
        const reply = {code:'OK',payload:[{name:'personal:656e7a6f',kind:'personal',displayName:'Personal',routingIdentity:'Personal:enzo'}]}
        expect(projects(reply)).toEqual(reply.payload)
    })
    it('distinguishes an empty listing from an unreadable reply', () => {
        for (const read of [projects, conversations, agents]) {
            expect(read({ code: 'OK', payload: [] })).toEqual([])
            expect(read({ code: 'OK', payload: {} })).toBeUndefined()
            expect(read({ code: 'NOT_FOUND', payload: [] })).toBeUndefined()
        }
        expect(approvalsOf({ code: 'OK', payload: {} })).toBeUndefined()
        expect(approvalsOf({ code: 'OK', payload: { approvals: [] } })).toEqual([])
    })
    it('never replaces a trajectory with a made-up empty page', () => {
        for (const payload of [null, {}, [], { entries: 'broken' }]) expect(backPageOf({ code: 'OK', payload })).toBeUndefined()
        expect(backPageOf({ code: 'OK', payload: { entries: [], through: 4, more: false } })).toEqual({ entries: [], through: 4, more: false })
    })
    it('retains provenance, unknown row kinds, child destinations and backward ordering', () => {
        const answer = { code: 'OK', payload: { entries: [
            { ordinal: 10, turnOrdinal: 3, kind: 'FUTURE_KIND', excerpt: 'later', dispatch: 'fallback', wireModel: 'model', completion: 'called_tools', toolCalls: [{ id: 'c', name: 'agent_run', arguments: '{}', length: 2, cut: false, opened: { conversation: 'child', agent: 'a' } }] },
            { ordinal: 9, turnOrdinal: 2, kind: 'utterance', excerpt: 'first', speaker: 'person' },
        ], through: 10, more: true } }
        expect(backPageOf(answer)?.entries.map(entry => entry.ordinal)).toEqual([9, 10])
        expect(entriesOf(answer)[0]).toMatchObject({ dispatch: 'fallback', wireModel: 'model', kind: 'FUTURE_KIND', calls: [{ opened: { conversation: 'child', agent: 'a' } }] })
    })
    it('accepts an approval continuation only for the requested approval and a consistent busy flag', () => {
        const payload = { id: 'a', state: 'allowed', job: 'j', busy: false }
        expect(answeredOf({ code: 'OK', payload }, 'a')).toEqual(payload)
        expect(answeredOf({ code: 'OK', payload }, 'foreign')).toBeUndefined()
        for (const invalid of [{ ...payload, job: ' ' }, { ...payload, busy: true }, { id: 'a' }, { ...payload, state: '' }]) expect(answeredOf({ code: 'OK', payload: invalid }, 'a')).toBeUndefined()
        expect(answeredOf({ code: 'OK', payload: { ...payload, job: null, busy: true, note: 'running' } }, 'a')).toEqual({ id: 'a', state: 'allowed', busy: true, note: 'running' })
    })
})

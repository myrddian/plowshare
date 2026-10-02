import { describe, expect, it } from 'vitest'
import { parseCommand } from './commands.ts'
import { request, resultOf } from './direct.ts'

describe('job conversation selection', () => {
    it('validates fresh, reused and standalone requests without submitting work', () => {
        const payload = { agent: 'a', task: 'work', newConversation: true }
        expect(parseCommand(`agent run ${JSON.stringify(payload)}`, 'repo')).toEqual({ kind: 'request', request: request('agent.run', { ...payload, project: 'repo' }) })
        for (const conflict of [{ ...payload, conversation: 'cnv_old' }, { ...payload, images: ['img_1'] }, { ...payload, newConversation: 'true' }]) {
            expect(parseCommand(`agent run ${JSON.stringify(conflict)}`).kind).toBe('usage')
        }
        expect(parseCommand('agent run {"agent":"a","task":"work","newConversation":false,"images":["img_1"]}').kind).toBe('request')
    })
    it('requires an opened conversation receipt and preserves the accepted job for malformed replies', () => {
        const asked = request('agent.run', { agent: 'a', task: 'work', newConversation: true })
        const outcome = { code: 'ACCEPTED' as const, payload: { id: 'job_new', agent: 'a', conversation: 'cnv_new' } }
        expect(resultOf(asked, outcome)).toEqual({ kind: 'accepted', outcome, job: 'job_new', conversation: 'cnv_new' })
        for (const conversation of [undefined, null, 123, 'job_wrong']) {
            expect(resultOf(asked, { ...outcome, payload: { ...outcome.payload, conversation } })).toMatchObject({ kind: 'invalid-response', job: 'job_new' })
        }
        expect(resultOf(request('agent.run', { agent: 'a', task: 'work', conversation: 'cnv_old' }), outcome).kind).toBe('invalid-response')
    })
})

import { describe, expect, it } from 'vitest'
import { parseCommand } from './commands.ts'
import { resultOf, request, waitForOrchestration } from './direct.ts'
import type { Outcome } from '../binding/envelope.ts'
import { readFileSync } from 'node:fs'
const fixtures = JSON.parse(readFileSync(new URL('../../../test-support/contracts/ws-administrative-fixtures.json', import.meta.url), 'utf8'))
const key = '00000000-0000-0000-0000-000000000001'
const status = (state: string): Outcome => ({ code: 'OK', payload: { ...fixtures['orchestration.status'].payload, orchestration: { ...fixtures['orchestration.status'].payload.orchestration, id: 'orc_one', state } } })

describe('deterministic orchestration commands', () => {
    it('validates explicit authority and a durable key without executing', () => {
        expect(parseCommand(`orchestration start ${JSON.stringify({ agent: 'caller', definition: 'custom', request: 'task', requestId: key })}`, 'repo')).toEqual({ kind: 'request', request: { type: 'orchestration.start', payload: { agent: 'caller', definition: 'custom', request: 'task', requestId: key, project: 'repo' } } })
        expect(parseCommand('orchestration start {"definition":"custom","request":"task"}').kind).toBe('usage')
        expect(parseCommand('orchestration receipt not-a-uuid').kind).toBe('usage')
    })
    it('checks acknowledgment identity, request key and outcome code', () => {
        const asked = request('orchestration.start', { agent: 'caller', definition: 'custom', request: 'task', requestId: key })
        const payload = { id: 'orc_one', state: 'running', requestId: key }
        expect(resultOf(asked, { code: 'ACCEPTED', payload })).toMatchObject({ kind: 'accepted', orchestration: 'orc_one' })
        for (const changed of [{ ...payload, requestId: 'other' }, { ...payload, id: 'job_wrong' }, { ...payload, state: 'future' }]) expect(resultOf(asked, { code: 'ACCEPTED', payload: changed }).kind).toBe('invalid-response')
        expect(resultOf(asked, { code: 'OK', payload }).kind).toBe('invalid-response')
    })
    it.each([['finished','completed'], ['asking','incomplete'], ['cancelled','incomplete'], ['failed','refused'], ['capped','incomplete'], ['future','invalid-response']])('wait returns %s clearly and only reads durable status', async (state, kind) => {
        const operations: string[] = [], replies = [status('running'), status('waiting'), status(state!)]
        const result = await waitForOrchestration({ ask: async type => { operations.push(type); return replies.shift()! } }, 'orc_one', async () => {})
        expect(result).toMatchObject({ kind, orchestration: 'orc_one' })
        expect(operations).toEqual(['orchestration.status','orchestration.status','orchestration.status'])
        expect(result.outcome.payload).toEqual(status(state!).payload)
    })
})

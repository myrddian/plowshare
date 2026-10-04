import { describe, expect, it } from 'vitest'
import { parseCommand } from './commands.ts'
import { dispatch, request } from './direct.ts'
const id = '5cb343a9-6e24-4c11-859f-1cd049ef8502'
const receipt = { id, requestId: id, peer: 'research', project: null, conversation: null, message: { parts: [{ text: 'question' }] }, state: 'UNKNOWN', cancelRequested: false,
    remoteTask: null, remoteContext: null, result: null, error: 'unconfirmed', revision: 2, createdAt: '2026-10-03T10:00:00Z', future: { untouched: true } }
describe('outgoing external work', () => {
    it('retains stable request ids and the caller-selected home', () => {
        expect(parseCommand(`outgoing send ${JSON.stringify({ requestId: id, peer: 'research', message: { parts: [{ text: 'question' }] } })}`, 'repo')).toMatchObject({ kind: 'request', request: { type: 'outgoing.send', payload: { requestId: id, project: 'repo' } } })
        expect(parseCommand('outgoing send {"requestId":"new","peer":"research","message":{}}').kind).toBe('usage')
        expect(parseCommand(`outgoing send {"requestId":"${id}","peer":"research","message":"text"}`).kind).toBe('usage')
        expect(parseCommand('outgoing claim {"peers":["research"]}').kind).toBe('usage')
    })
    it('never presents unknown work as successful completion or performs a second request', async () => {
        let calls = 0
        const outcome = { code: 'OK', payload: receipt } as const
        expect(await dispatch({ async ask() { calls++; return outcome } }, request('outgoing.status', { id }))).toEqual({ kind: 'incomplete', outcome })
        expect(calls).toBe(1)
        expect((await dispatch({ async ask() { return { code: 'OK', payload: { ...receipt, id: 'other' } } } }, request('outgoing.status', { id }))).kind).toBe('invalid-response')
    })
    it('accepts durable send receipts without inventing local job handles', async () => {
        const outcome = { code: 'ACCEPTED', payload: { ...receipt, state: 'QUEUED' } } as const
        expect(await dispatch({ async ask() { return outcome } }, request('outgoing.send', { requestId: id, peer: 'research', message: {} }))).toEqual({ kind: 'accepted', outcome })
    })
    it('exposes opaque Agent Cards without dropping future fields and checks peer association', async () => {
        const payload = { peers: ['research'], details: [{ peer: 'research', agentCard: { name: 'Research', description: 'Find evidence', skills: [{ id: 'research' }], future: { kept: null } } }] }
        const outcome = { code: 'OK', payload } as const
        expect(await dispatch({ async ask() { return outcome } }, request('outgoing.peers', {}))).toEqual({ kind: 'completed', outcome })
        expect((await dispatch({ async ask() { return { code: 'OK', payload: { ...payload, details: [{ peer: 'foreign', agentCard: {} }] } } } }, request('outgoing.peers', {}))).kind).toBe('invalid-response')
        expect((await dispatch({ async ask() { return { code: 'OK', payload: { peers: ['research'] } } } }, request('outgoing.peers', {}))).kind).toBe('completed')
    })
})

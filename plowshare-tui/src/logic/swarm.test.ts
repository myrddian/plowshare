import { describe, expect, it } from 'vitest'
import { demoBoard } from './board-demo.ts'
import { memberAction, memberActions, memberKey, readingSwarmActivity, swarmActivityOf, swarmMembers } from './swarm.ts'

const wire = (ordinal: number, kind = 'answer', extra: Record<string, unknown> = {}) => ({ ordinal, turnOrdinal: 1, kind, state: 'stands', excerpt: 'Recorded action', ...extra })
describe('swarm member activity', () => {
    it('lists seats once, prioritizes working members, and preserves identity across state changes', () => {
        const fixture = demoBoard(), snapshot = fixture.swarm.value!
        const members = swarmMembers(snapshot, 'Plowshare', fixture.activity)
        expect(members.map(m => m.seat.seat.occupant)).toEqual(['researcher','spec_writer','critic'])
        const identity = memberKey(members[0]!.seat)
        snapshot.seats[0]!.state = 'passed'
        expect(swarmMembers(snapshot).some(m => memberKey(m.seat) === identity)).toBe(true)
        expect(swarmMembers(snapshot, 'Other')).toEqual([])
        expect(memberAction(members[0]!)).toContain('search · offline conflict strategies · ok')
    })
    it('does not drop account members whose topic metadata lies beyond the bounded topic snapshot', () => {
        const snapshot = demoBoard().swarm.value!
        snapshot.topics = []
        expect(swarmMembers(snapshot)).toHaveLength(3)
        expect(swarmMembers(snapshot, 'Plowshare')).toHaveLength(0)
    })
    it('pairs tool results and orders actions by the result rather than the original call', () => {
        const payload = { through: 4, entries: [
            wire(4, 'tool_result', { toolCallId: 'a', outcome: 'ok' }),
            wire(3, 'thinking', { excerpt: 'Looking at evidence' }),
            wire(2, 'answer', { excerpt: '', toolCalls: [{ id: 'a', name: 'search', arguments: '{}', length: 2, cut: false, salient: 'conflicts' }] }),
        ] }
        const activity = swarmActivityOf(payload), actions = memberActions(activity)
        expect(actions[0]).toEqual({ ordinal: 4, text: 'search · conflicts · ok' })
        expect(actions).toHaveLength(2)
        expect(actions[1]!.text).toContain('Reasoning')
        expect(readingSwarmActivity('seat-1')).toEqual({ type: 'conversation.trajectory', payload: { conversation: 'seat-1', tail: true, limit: 20 } })
    })
    it('labels pending tool calls and never invents actions on empty or failed reads', () => {
        const activity = swarmActivityOf({ through: 1, entries: [wire(1, 'answer', { toolCalls: [{ id: 'p', name: 'fetch', arguments: '{}', length: 2, cut: false }] })] })
        expect(memberActions(activity)[0]!.text).toContain('awaiting result')
        const member = swarmMembers(demoBoard().swarm.value!)[0]!
        expect(memberAction({ ...member, activity: { value: { through: 0, entries: [] } } })).toBe('No recorded actions yet')
        expect(memberAction({ ...member, activity: { error: 'Denied' } })).toBe('Activity unavailable')
        expect(memberActions({ through: 1, entries: [{ ordinal: 1, turnOrdinal: 1, kind: 'answer', state: 'stands', text: '\u001b[2J' + 'x'.repeat(1000) }] })[0]!.text.length).toBeLessThan(260)
    })
    it('rejects malformed rows, omitted watermarks and dropped tool calls', () => {
        for (const payload of [null, {}, { entries: [] }, { through: 1, entries: [{}] },
            { through: 1, entries: [wire(1), wire(1)] }, { through: 1, entries: [wire(2)] }, { through: 1, entries: [wire(1, 'answer', { toolCalls: [{}] })] }]) {
            expect(() => swarmActivityOf(payload)).toThrow()
        }
    })
})

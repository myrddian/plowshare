import { describe, expect, it } from 'vitest'

import type { Entry } from './session.ts'
import { traceGrew, traceOpened } from './trace.ts'

const row = (ordinal: number, kind: string, extra: Partial<Entry> = {}): Entry =>
    ({ ordinal, turnOrdinal: 3, kind, state: 'stands', text: '', ...extra })
const asking = (ordinal: number, ...ids: string[]): Entry => row(ordinal, 'answer', {
    asked: ids.length, calls: ids.map((id) => ({ id, name: 'run', arguments: '{}', length: 2, cut: false })),
})

describe('the tracer', () => {
    it('writes reasoning and settled calls once, in order, and holds back what follows a pending call', () => {
        const opened = traceOpened(10)
        const first = traceGrew(opened, [row(11, 'thinking', { text: 'hm' }), asking(12, 'c1', 'c2')])
        expect(first.written.map((step) => step.kind)).toEqual(['reasoning'])
        expect(first.pending.map((call) => call.id)).toEqual(['c1', 'c2'])

        const second = traceGrew(first.trace, [row(13, 'tool_result', { toolCallId: 'c2', outcome: 'ok' })])
        expect(second.written).toEqual([])
        expect(second.pending.map((call) => call.id)).toEqual(['c1'])

        const third = traceGrew(second.trace, [row(14, 'tool_result', { toolCallId: 'c1', outcome: 'exit 1' })])
        expect(third.written.map((step) => step.kind === 'call' ? step.id : step.kind)).toEqual(['c1', 'c2'])
        expect(third.pending).toEqual([])
        expect(third.trace.through).toBe(14)
    })

    it('ignores rows it has already read and never writes the person\'s words or the answer', () => {
        const once = traceGrew(traceOpened(0), [row(1, 'utterance', { text: 'hi' }), row(2, 'answer', { text: 'hello' })])
        expect(once.written).toEqual([])
        expect(traceGrew(once.trace, [row(2, 'answer', { text: 'hello' })]).written).toEqual([])
    })

    it('writes a call the log has moved past as unanswered, rather than silently dropping it when a batch catches up more than TURNS_KEPT turns at once', () => {
        const opened = traceOpened(0)
        const first = traceGrew(opened, [{ ...asking(1, 'c1'), turnOrdinal: 1 }])
        expect(first.pending.map((call) => call.id)).toEqual(['c1'])

        // One batch delivers three fresh turns at once — more than `TURNS_KEPT` — without ever
        // answering c1. `stepsOf` must be run over every row seen so far, not the rows already
        // trimmed to the window, or it never sees c1 beside a later turn to judge it outlived.
        const second = traceGrew(first.trace, [
            { ...row(2, 'thinking', { text: 't2' }), turnOrdinal: 2 },
            { ...row(3, 'thinking', { text: 't3' }), turnOrdinal: 3 },
            { ...row(4, 'thinking', { text: 't4' }), turnOrdinal: 4 },
        ])
        const settled = second.written.find((step) => step.kind === 'call')
        expect(settled).toMatchObject({ id: 'c1', pending: false, unanswered: true })
        expect(second.pending).toEqual([])

        // A new call asked in the newest turn stays pending, and its turn is not trimmed away —
        // while an older turn that has nothing left pending in it is.
        const third = traceGrew(second.trace, [{ ...asking(5, 'c2'), turnOrdinal: 5 }])
        expect(third.pending.map((call) => call.id)).toEqual(['c2'])
        const turns = new Set(third.trace.rows.map((each) => each.turnOrdinal))
        expect(turns.has(3)).toBe(false)
        expect(turns.has(5)).toBe(true)
    })

    it('forgets turns it has finished with', () => {
        let trace = traceOpened(0)
        for (let turn = 1; turn <= 5; turn += 1) {
            trace = traceGrew(trace, [{ ...row(turn * 10, 'thinking', { text: 't' }), turnOrdinal: turn }]).trace
        }
        expect(new Set(trace.rows.map((each) => each.turnOrdinal))).toEqual(new Set([4, 5]))
    })
})

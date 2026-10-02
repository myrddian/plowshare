import { describe, expect, it } from 'vitest'

import {
    current, cursorOf, explorerAscended, explorerDescended, explorerEarlier, explorerGrew, explorerKeyed,
    explorerOpened, levelOf, matchesOf, originOf, rowsOf, selectedRow, type Explorer,
} from './explorer.ts'
import type { Asked, BackPage, Entry } from './session.ts'

const row = (ordinal: number, turn: number, kind: string, extra: Partial<Entry> = {}): Entry =>
    ({ ordinal, turnOrdinal: turn, kind, state: 'stands', text: `row ${ordinal}`, ...extra })
const asking = (ordinal: number, turn: number, id: string, extra: Partial<Asked> = {}): Entry =>
    row(ordinal, turn, 'answer', { text: '', asked: 1, calls: [{ id, name: 'run', arguments: '{}', length: 2, cut: false, salient: id, ...extra }] })
const page = (entries: readonly Entry[], more = false): BackPage => ({
    entries, through: entries.reduce((most, each) => Math.max(most, each.ordinal), 0),
    ...(entries.length === 0 ? {} : { oldest: Math.min(...entries.map((each) => each.ordinal)) }),
    more, total: entries.length,
})

const LOG = [
    row(1, 1, 'utterance', { text: 'run the tests' }),
    asking(2, 1, 'c1'),
    row(3, 1, 'tool_result', { toolCallId: 'c1', outcome: 'exit 1', text: 'FAILED' }),
    row(4, 1, 'answer', { text: 'one failed' }),
    row(5, 2, 'utterance', { text: 'ask the reviewer' }),
    asking(6, 2, 'c2', { name: 'agent_run', opened: { conversation: 'cnv_child', agent: 'code_reviewer' } }),
    row(7, 2, 'tool_result', { toolCallId: 'c2', outcome: 'answered', text: 'looks fine' }),
    row(8, 2, 'answer', { text: 'the reviewer is happy' }),
]
const opened = (land?: Parameters<typeof levelOf>[4]): Explorer =>
    explorerOpened(levelOf('cnv_root', 'plowshare', page(LOG, true), 'trajectory', land), 'trajectory')
const keyed = (ex: Explorer, ...keys: Parameters<typeof explorerKeyed>[1][]): Explorer =>
    keys.reduce((was, key) => explorerKeyed(was, key, 5).explorer, ex)

describe('the explorer', () => {
    it('opens on the last row, following, and lands on a failure or a delegation when asked', () => {
        const ex = opened()
        expect(rowsOf(ex).map((each) => each.kind === 'step' ? each.step.kind : each.kind))
            .toEqual(['person', 'call', 'answer', 'person', 'call', 'answer'])
        expect(cursorOf(ex)).toBe(5)
        expect(current(ex).follow).toBe(true)
        expect(cursorOf(opened({ agent: 'code_reviewer' }))).toBe(4)
    })

    it('lands on a failure only when it is in the latest turn, and otherwise on the last row, following', () => {
        // The only failure is in turn 1 and the log has gone on to turn 2: the last row.
        expect(cursorOf(opened('failure'))).toBe(5)
        expect(current(opened('failure')).follow).toBe(true)
        const failingLast = [...LOG, asking(9, 3, 'c3'), row(10, 3, 'tool_result', { toolCallId: 'c3', outcome: 'exit 2' }),
            row(11, 3, 'answer', { text: 'still failing' })]
        const ex = explorerOpened(levelOf('cnv_root', 'plowshare', page(failingLast), 'trajectory', 'failure'), 'trajectory')
        expect(cursorOf(ex)).toBe(6)
        expect(current(ex).follow).toBe(false)
    })

    it('moves, asks for earlier at the top, and stops following once it moves up', () => {
        let ex = keyed(opened(), 'up')
        expect(cursorOf(ex)).toBe(4)
        expect(current(ex).follow).toBe(false)
        ex = keyed(ex, 'top')
        expect(cursorOf(ex)).toBe(0)
        expect(explorerKeyed(ex, 'up', 5).then).toBe('earlier')
        expect(cursorOf(keyed(ex, 'bottom'))).toBe(5)
        expect(current(keyed(ex, 'bottom')).follow).toBe(true)
    })

    it('jumps between turns and failures, and says when there is no failure that way', () => {
        expect(cursorOf(keyed(opened(), 'prevTurn'))).toBe(3)
        const ex = keyed(opened(), 'prevTurn', 'prevTurn')
        expect(cursorOf(ex)).toBe(0)
        expect(cursorOf(keyed(ex, 'nextTurn'))).toBe(3)
        expect(cursorOf(keyed(ex, 'nextFailure'))).toBe(1)
        expect(keyed(opened(), 'nextFailure').note).toBe('noFailureBelow')
        expect(cursorOf(keyed(opened(), 'prevFailure'))).toBe(1)
    })

    it('descends only through a door, and comes back to the call it left from', () => {
        expect(explorerKeyed(opened(), 'descend', 5)).toMatchObject({ then: 'draw', explorer: { note: 'notADoor' } })
        const onDoor = keyed(opened(), 'up')
        expect(explorerKeyed(onDoor, 'descend', 5).then).toBe('descend')
        const child = levelOf('cnv_child', 'code_reviewer', page([row(1, 1, 'utterance', { text: 'review it' })]), 'trajectory')
        const down = explorerDescended(onDoor, child)
        expect(down.levels.map((level) => level.label)).toEqual(['plowshare', 'code_reviewer'])
        expect(explorerKeyed(down, 'ascend', 5).then).toBe('ascend')
        const up = explorerAscended(down)
        expect(up.levels).toHaveLength(1)
        expect(cursorOf(up)).toBe(4)
        expect(explorerKeyed(up, 'ascend', 5).explorer.note).toBe('atTop')
        const unlinked = explorerOpened(levelOf('cnv_old', 'plowshare',
            page([asking(1, 1, 'c9', { name: 'agent_run' })]), 'trajectory'), 'trajectory')
        expect(explorerKeyed(unlinked, 'descend', 5).explorer.note).toBe('noLink')
    })

    it('folds to turns and back without losing its place, and switches to the log by ordinal', () => {
        const folded = keyed(opened(), 'prevTurn', 'prevTurn', 'fold')
        expect(rowsOf(folded).map((each) => each.kind)).toEqual(['turn', 'turn'])
        expect(cursorOf(folded)).toBe(0)
        expect(cursorOf(keyed(folded, 'fold'))).toBe(0)
        const log = keyed(keyed(opened(), 'up'), 'view')
        expect(log.view).toBe('log')
        expect(rowsOf(log)).toHaveLength(8)
        const selected = selectedRow(log)
        expect(selected?.kind === 'entry' ? selected.entry.ordinal : undefined).toBe(6)
    })

    it('searches what it holds, jumps to the nearest match above, and cycles', () => {
        let ex = keyed(opened(), 'search', { typed: 'rev' }, { typed: 'i' }, 'erase', { typed: 'iewer' })
        expect(ex.search).toEqual({ query: 'reviewer', typing: true })
        ex = keyed(ex, 'enter')
        expect(ex.search?.typing).toBe(false)
        expect(matchesOf(ex)).toEqual([3, 4, 5])
        expect(cursorOf(ex)).toBe(5)
        expect(cursorOf(keyed(ex, 'prevMatch'))).toBe(4)
        expect(keyed(ex, 'escape').search).toBeUndefined()
        expect(keyed(opened(), 'search', { typed: 'zzz' }, 'enter').note).toBe('noMatch')
    })

    it('scrolls the inspector when it has focus, tabs through panes, and escapes back to the list', () => {
        let ex = keyed(keyed(opened(), 'up'), 'inspect')
        expect(ex.focus).toBe('inspector')
        expect(ex.pane).toBe('result')
        ex = keyed(ex, 'down', 'down')
        expect(ex.scroll).toBe(2)
        expect(cursorOf(ex)).toBe(4)
        expect(keyed(ex, 'tab').pane).toBe('payload')
        expect(keyed(ex, 'tab', 'tab', 'tab').focus).toBe('list')
        expect(keyed(ex, 'escape').focus).toBe('list')
        expect(explorerKeyed(opened(), 'escape', 5).then).toBe('close')
    })

    it('takes an earlier page and a grown log without moving the cursor, unless it follows', () => {
        const early = [row(0, 0, 'utterance', { text: 'hello' })]
        const back = explorerEarlier(keyed(opened(), 'top'), 'cnv_root', page(early))
        expect(rowsOf(back)).toHaveLength(7)
        expect(cursorOf(back)).toBe(1)
        const grown = explorerGrew(opened(), 'cnv_root', [row(9, 3, 'utterance', { text: 'more' })], 9)
        expect(cursorOf(grown)).toBe(6)
        const paused = explorerGrew(keyed(opened(), 'up'), 'cnv_root', [row(9, 3, 'utterance', { text: 'more' })], 9)
        expect(cursorOf(paused)).toBe(4)
        expect(explorerGrew(opened(), 'cnv_other', [row(9, 3, 'utterance')], 9)).toEqual(opened())
    })

    it('puts an earlier page on the level it was read for, even when another is on screen by then', () => {
        const child = levelOf('cnv_child', 'code_reviewer', page([row(5, 1, 'utterance', { text: 'review it' })], true), 'trajectory')
        const down = explorerDescended(keyed(opened(), 'up'), child)
        // ↑ at the child's top asked for its earlier page; ← landed before the page did.
        const up = explorerAscended(keyed(down, 'top'))
        const late = explorerEarlier(up, 'cnv_child', page([row(1, 1, 'utterance', { text: 'the child, earlier' })]))
        expect(late).toBe(up)
        expect(current(late).entries).toEqual(LOG)
        // Still held a level up: it takes the page there, and the level on screen is untouched.
        const held = explorerEarlier(down, 'cnv_root', page([row(0, 0, 'utterance', { text: 'hello' })]))
        expect(held.levels[0]?.entries).toHaveLength(9)
        expect(current(held)).toBe(current(down))
    })

    it('drops a descent whose origin is no longer the level on screen', () => {
        const child = levelOf('cnv_child', 'code_reviewer', page([row(1, 1, 'utterance', { text: 'review it' })]), 'trajectory')
        const grand = levelOf('cnv_grand', 'test_runner', page([row(1, 1, 'utterance', { text: 'run them' })]), 'trajectory')
        const down = explorerDescended(keyed(opened(), 'up'), child)
        const from = originOf(down)
        expect(explorerDescended(down, grand, from).levels.map((level) => level.label))
            .toEqual(['plowshare', 'code_reviewer', 'test_runner'])
        // → from the child, then ← before the grandchild's log landed: it is not pushed onto the root.
        const up = explorerAscended(down)
        expect(explorerDescended(up, grand, from)).toBe(up)
    })

    it('scrolls the inspector no further than the limit it is given', () => {
        let ex = keyed(keyed(opened(), 'up'), 'inspect')
        for (const key of ['down', 'down', 'down', 'pageDown'] as const) {
            ex = explorerKeyed(ex, key, 5, 2).explorer
        }
        expect(ex.scroll).toBe(2)
        expect(explorerKeyed(ex, 'up', 5, 2).explorer.scroll).toBe(1)
        expect(explorerKeyed(ex, 'down', 5, 0).explorer.scroll).toBe(0)
    })
})

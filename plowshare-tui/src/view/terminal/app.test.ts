import process from 'node:process'
import { renderToString, Text } from 'ink'
import { createElement } from 'react'
import { afterEach, describe, expect, it } from 'vitest'

import { blank, traced } from '../../logic/screen.ts'
import type { Working } from '../../logic/screen.ts'
import { plainOf, tint } from '../../logic/tints.ts'
import type { Tinted } from '../../logic/tints.ts'
import type { Call } from '../../logic/trajectory.ts'
import { DEFAULT_LOOK } from '../look.ts'
import { app, dialogRoom, panelFitted, PANEL_ROWS, rowsOf, viewerFitted, viewerRoom } from './app.ts'
import type { Tree, Viewed } from '../../logic/record.ts'
import { describePanel, describeQuestionDialog } from '../../logic/wording.ts'

/** `trees` live trees of four lines each, as `describePanel` draws them. */
const trees = (count: number): string[] => Array.from({ length: count }, (_, at) => [
    `orc_${at}  implement_specification  1m 0s`,
    '  goal ✓ code ●',
    '  now: coder · run ./gradlew test → …',
    '  09:05:03 · code: pending → in_progress',
]).flat()

/** Plain lines as the tinted lines a surface is handed, each one run of text. */
const tinted = (lines: readonly string[]): Tinted[] => lines.map((line) => [tint(line)])

/** What else can be in the redrawn region beside the panel. */
interface Beside {
    readonly viewing?: Viewed
    readonly columns?: number
    readonly working?: Working
    readonly choosing?: readonly string[]
    readonly dialog?: readonly string[]
    readonly colour?: boolean
}

/** The redrawn region drawn with this panel in a terminal `rows` tall, line by line. */
function drawn(panel: readonly string[], rows: number, beside: Beside = {}): string[] {
    const columns = beside.columns ?? 80
    Object.defineProperty(process.stdout, 'rows', { value: rows, configurable: true })
    Object.defineProperty(process.stdout, 'columns', { value: columns, configurable: true })
    const screen = renderToString(app({
        screen: beside.working === undefined ? blank('') : { ...blank(''), working: beside.working },
        composing: { typed: '', at: 0, offering: [] },
        quietly: false,
        colour: beside.colour ?? false,
        unread: 0,
        look: DEFAULT_LOOK,
        panel: tinted(panel),
        ...(beside.choosing === undefined ? {} : { choosing: beside.choosing }),
        ...(beside.dialog === undefined ? {} : { dialog: beside.dialog }),
        ...(beside.viewing === undefined ? {} : { viewing: beside.viewing }),
        onKey: () => undefined,
    }), { columns })
    // The wordmark is written once above the region, and wraps on a narrow terminal: the region
    // starts after its last row and the margin under it.
    const lines = screen.split('\n')
    const banner = lines.reduce((last, line, at) => line.includes('▀') ? at : last, -1)
    return lines.slice(banner + 2)
}

const had = {
    rows: Object.getOwnPropertyDescriptor(process.stdout, 'rows'),
    columns: Object.getOwnPropertyDescriptor(process.stdout, 'columns'),
}
afterEach(() => {
    for (const key of ['rows', 'columns'] as const) {
        const was = had[key]
        if (was === undefined) {
            Reflect.deleteProperty(process.stdout, key)
        } else {
            Object.defineProperty(process.stdout, key, was)
        }
    }
})

describe('the runs panel, drawn', () => {
    it('keeps the redrawn region shorter than the terminal, however many trees are live', () => {
        const region = drawn(trees(10), 24)
        // As tall as the terminal, Ink clears it and writes the whole scrollback again each frame.
        expect(region.length).toBeLessThan(24)
        expect(region.join('\n')).toContain('orc_0  implement_specification')
        expect(region.join('\n')).toMatch(/… \d+ more lines/u)
        expect(region.join('\n')).toContain('ctrl-o hides runs')
    })

    it('keeps an asking tree\'s whole question inside the budget, each of its lines one row', () => {
        const question = Array.from({ length: 40 }, (_, at) => `word${at}`).join(' ')
        const tree: Tree = {
            run: { id: 'orc_1', definition: 'implement_specification', tier: 'project', state: 'asking', depth: 0,
                createdAt: '2026-09-28T09:00:00Z' },
            stages: [], phases: [], milestones: [],
            question: { ordinal: 2, at: '2026-09-28T09:05:03Z', run: 'orc_1', actor: 'conductor', kind: 'question_asked',
                text: 'asked: word0 word1…', body: question, tool: false },
        }
        const lines = describePanel([tree], Date.parse('2026-09-28T10:00:00Z'), 'UTC', 40).lines.map(plainOf)
        expect(lines.at(-1)).toBe('    … /watch orc_1 for the rest')
        // The question's lines fit inside the panel's padding; the heading is truncated as ever.
        for (const line of lines.slice(1)) {
            expect(line.length).toBeLessThanOrEqual(39)
        }
        const region = drawn(lines, 20, { columns: 40 })
        expect(region.length).toBeLessThan(20)
        // Drawn as it was worded: the question's lines are rows of their own, none wrapped over two.
        for (const line of lines.slice(1)) {
            expect(region).toContain(` ${line}`)
        }
        // A terminal too short for all of it: the panel's own budget cuts it, and says so.
        const short = drawn(lines, 12, { columns: 40 })
        expect(short.length).toBeLessThan(12)
        expect(short).toContain('   ? word0 word1 word2 word3 word4 word5')
        expect(short.some((line) => /… \d+ more lines/u.test(line))).toBe(true)
    })

    it('counts a working line and a question\'s keys that wrap, which the server can make as long as it likes', () => {
        // THE REVIEWER'S CASE: 22 rows drawn in a 20-row terminal, the tool's name wrapping the
        // working line over four rows that were budgeted as one.
        const tool = 'mcp__ee6ebd41-7d80-48d9-afdb-55e7abb5d319__query-docs-super-extra-long-tool-name'
        const working: Working = { job: 'job_1', since: Date.now(), phase: { kind: 'tool', tool } }
        // Shorter than the terminal and not only no taller: Ink redraws the world at as tall.
        for (const colour of [false, true]) {
            const going = drawn(trees(10), 20, { columns: 40, working, colour })
            expect(going.join('').replace(/\s/gu, '')).toContain('extra-long-tool-name')
            expect(going.length).toBeLessThan(20)
            expect(drawn(trees(10), 20, {
                columns: 40, colour,
                choosing: [`allow any command starting: ${'./gradlew :plowshare-server:test '.repeat(3)}?`,
                    'o once · c command · p prefix · d deny'],
            }).length).toBeLessThan(20)
        }
    })

    it('draws a panel that fits whole, with no line about more', () => {
        const region = drawn(trees(1), 40)
        expect(region.join('\n')).toContain('  09:05:03 · code: pending → in_progress')
        expect(region.join('\n')).not.toContain('more lines')
    })

    it('counts no fewer rows than Ink wraps a line into, and at most one more a row', () => {
        const lines = [
            '', 'aaaa bbbb', 'aaaa bbbbbb', 'aaaaaaa bbbbbbb ccccccc', 'x'.repeat(25), `ab x${'y'.repeat(20)}`,
            'one\ntwo', 'allow any command starting: ./gradlew :plowshare-server:test ./gradlew :plowshare-server:test',
            '⚙ calling mcp__ee6ebd41-7d80-48d9-afdb-55e7abb5d319__query-docs-super-extra-long-tool-name · 12m 3s · ctrl-c to stop',
        ]
        for (const width of [7, 10, 23, 39]) {
            for (const line of lines) {
                const ink = renderToString(createElement(Text, null, line), { columns: width }).split('\n').length
                const counted = rowsOf(line, width)
                expect(counted, `${JSON.stringify(line)} at ${width}`).toBeGreaterThanOrEqual(ink)
                expect(counted, `${JSON.stringify(line)} at ${width}`).toBeLessThanOrEqual(2 * ink)
            }
        }
    })

    it('takes no more than its own most rows in a tall terminal, and nothing where there is no room', () => {
        expect(panelFitted(tinted(trees(10)), 100)).toHaveLength(PANEL_ROWS)
        expect(plainOf(panelFitted(tinted(trees(10)), 100).at(-1) ?? [])).toBe(`… ${40 - PANEL_ROWS + 1} more lines`)
        expect(panelFitted(tinted(trees(10)), 1).map(plainOf)).toEqual(['… 40 more lines'])
        expect(panelFitted(tinted(trees(10)), 0)).toEqual([])
        expect(panelFitted(tinted(trees(1)), 4).map(plainOf)).toEqual(trees(1))
    })
})

/** A cap question as `describeCapDialog` words it, its milestone as long as the record makes it. */
const DIALOG = [
    'code_implementation (orc_2) stopped at its turn cap',
    `last: 05:21:07 · 03-character · spec: in_progress → done ${'and a long summary '.repeat(6)}`,
    'y continue · n stop · a always (auto-continue 3) · w watch · esc later',
]

describe('the cap dialog, drawn', () => {
    it('stands in place of the composer while it is up', () => {
        for (const colour of [false, true]) {
            const region = drawn([], 24, { dialog: DIALOG, colour }).join('\n')
            expect(region).toContain('code_implementation (orc_2) stopped at its turn cap')
            expect(region).toContain('last: 05:21:07 · 03-character')
            expect(region).toContain('y continue · n stop · a always (auto-continue 3) · w watch · esc later')
            expect(region).not.toContain('Ask anything…')
            expect(region).not.toContain('❯')
        }
    })

    it('leaves the panel only what it does not take, so the region stays shorter than the terminal', () => {
        for (const [rows, columns] of [[24, 80], [20, 40]] as const) {
            for (const colour of [false, true]) {
                const region = drawn(trees(10), rows, { dialog: DIALOG, columns, colour })
                // As tall as the terminal, Ink clears it and writes the whole scrollback again each frame.
                expect(region.length, `${rows}x${columns}`).toBeLessThan(rows)
                expect(region.join('\n')).toMatch(/… \d+ more lines/u)
                // Wrapped inside the box rather than cut off: the key that puts it away is there.
                expect(region.join(' ').replace(/[│\s]+/gu, ' ')).toContain('esc later')
            }
        }
    })
})

describe('the question dialog, drawn', () => {
    /** A root's question of thirty long lines, worded as `converse` words it for this terminal. */
    const asked = (rows: number, columns: number): string[] => describeQuestionDialog({
        id: 'orc_1', definition: 'implement_specification', callerAgent: 'sophron',
        question: Array.from({ length: 30 }, (_, at) => `line ${at + 1} ${'and a long clause '.repeat(4)}`).join('\n'),
    }, false, columns, dialogRoom(rows))

    it('keeps a twelve-line question and its keys shorter than the terminal, however small', () => {
        for (const [rows, columns] of [[40, 120], [24, 80], [20, 40], [12, 40]] as const) {
            for (const colour of [false, true]) {
                const region = drawn(trees(10), rows, { dialog: asked(rows, columns), columns, colour })
                // As tall as the terminal, Ink clears it and writes the whole scrollback again each frame.
                expect(region.length, `${rows}x${columns}`).toBeLessThan(rows)
                const flat = region.join(' ').replace(/[│╭╮╰╯─\s]+/gu, ' ')
                expect(flat, `${rows}x${columns}`).toContain('esc later')
                expect(flat, `${rows}x${columns}`).toContain('for the rest')
            }
        }
        // Where there is room, twelve lines of it.
        expect(asked(40, 120).filter((line) => line.startsWith('line '))).toHaveLength(12)
    })
})

/** A viewer over `rows` record lines under a tree of `head` lines, some far wider than a terminal. */
const viewer = (head: number, rows: number): Viewed => ({
    head: tinted(Array.from({ length: head }, (_, at) => at === 0 ? 'orc_1  implement_specification  3m 0s'
        : `  └ orc_${at + 1}  implement_phase  1m 0s ${'and more '.repeat(at % 3 === 0 ? 20 : 0)}`)),
    body: tinted(Array.from({ length: rows }, (_, at) => `09:05:03   coder · run ./gradlew test ${'x'.repeat(at % 7 === 0 ? 200 : 0)}`
        + ` line ${String(at + 1).padStart(3, '0')} → ok`)),
    foot: 'milestones and tool activity · ↑↓ PgUp PgDn scroll · e earlier · t milestones only · f follow · esc back',
})

describe('the viewer, drawn', () => {
    it('fills the terminal less one row, the newest line at the bottom, whatever it is given', () => {
        for (const [rows, columns] of [[24, 80], [12, 40], [5, 30]] as const) {
            for (const colour of [false, true]) {
                const region = drawn([], rows, { viewing: viewer(30, 300), columns, colour })
                // As tall as the terminal, Ink clears it and writes the whole scrollback again each frame.
                expect(region.length, `${rows}x${columns}`).toBe(rows - 1)
                expect(region.join('\n')).toContain('orc_1')
                // The keys wrap rather than lose the one that leaves, where there is room to.
                if (rows > 5) {
                    expect(region.join(' ')).toMatch(/esc\s+back/u)
                }
                // The newest line right above the keys.
                const keys = region.findIndex((line) => line.includes('milestones and'))
                expect(region[keys - 1]).toContain(columns > 40 ? 'line 300' : '09:05:03')
                // Nothing of the composer or the panel: the viewer is drawn in their place.
                expect(region.join('\n')).not.toContain('❯')
            }
        }
    })

    it('gives the tree at most half of it and the record the rest', () => {
        const fitted = viewerFitted(viewer(30, 300), 24, 120)
        expect(fitted.head.length).toBe(11)
        expect(plainOf(fitted.head[0] ?? [])).toBe('orc_1  implement_specification  3m 0s')
        expect(plainOf(fitted.head.at(-1) ?? [])).toMatch(/… \d+ more lines/u)
        expect(fitted.body).toHaveLength(22 - 11)
        expect(plainOf(fitted.body.at(-1) ?? [])).toContain('line 300')
        expect(viewerFitted(viewer(2, 3), 24, 120)).toMatchObject({ ...viewer(2, 3), footRows: 1 })
        // However short the terminal, the run's own line and the keys.
        expect(viewerFitted(viewer(30, 300), 4, 120)).toEqual({ head: tinted(['orc_1  implement_specification  3m 0s']),
            body: tinted(['09:05:03   coder · run ./gradlew test  line 300 → ok']), foot: viewer(1, 1).foot, footRows: 1,
            room: 1 })
    })

    it('says how many record lines it has room for, the line above them aside, and a refusal takes one', () => {
        // 24 rows: 23 drawn, a keys line, 2 of tree — 20 for the body, one of them the line above.
        expect(viewerRoom(viewer(2, 300), 24, 120)).toBe(19)
        expect(viewerRoom({ ...viewer(2, 300), said: 'refused' }, 24, 120)).toBe(18)
        expect(viewerRoom(viewer(30, 300), 4, 120)).toBe(1)
        const region = drawn([], 24, { viewing: { ...viewer(30, 300), said: 'the record was refused' } })
        expect(region.length).toBe(23)
        expect(region.join('\n')).toContain('the record was refused')
    })
})

describe('tool lines, drawn', () => {
    it('writes a trace entry as its tinted lines with no gutter mark, and a pending call under the working line', () => {
        const pending: Call = {
            kind: 'call', ordinal: 4, turn: 2, id: 'c2', tool: 'file_grep', salient: '"RatioTokenizer" src/',
            arguments: '{}', argumentsCut: false, argumentsLength: 2, pending: true, unanswered: false, hooks: [],
            entry: { ordinal: 4, turnOrdinal: 2, kind: 'answer', state: 'stands' },
        }
        Object.defineProperty(process.stdout, 'rows', { value: 24, configurable: true })
        Object.defineProperty(process.stdout, 'columns', { value: 80, configurable: true })
        const screen = renderToString(app({
            screen: {
                ...blank(''),
                entries: [traced(1, [[tint('  ● ', 'ok'), tint('run', 'tool'), tint('  ls  ✓ 88 lines', 'ok')]])],
                working: { job: 'j1', since: Date.now() - 1300, calls: [pending] },
            },
            composing: { typed: '', at: 0, offering: [] },
            quietly: false, colour: false, unread: 0, look: DEFAULT_LOOK, onKey: () => undefined,
        }), { columns: 80 })
        expect(screen).toContain('  ● run  ls  ✓ 88 lines')
        expect(screen).not.toContain('·   ● run')
        expect(screen).toMatch(/◌ file_grep {2}"RatioTokenizer" src\/ +1\.\ds/u)
    })

    it('times a pending call from when it was first seen, not from when the turn began', () => {
        const pending: Call = {
            kind: 'call', ordinal: 4, turn: 2, id: 'c2', tool: 'run', salient: 'make',
            arguments: '{}', argumentsCut: false, argumentsLength: 2, pending: true, unanswered: false, hooks: [],
            entry: { ordinal: 4, turnOrdinal: 2, kind: 'answer', state: 'stands' },
        }
        const now = Date.now()
        const drawn = renderToString(app({
            screen: { ...blank(''), working: { job: 'j1', since: now - 40_000, calls: [pending], callsSince: { c2: now - 2300 } } },
            composing: { typed: '', at: 0, offering: [] },
            quietly: false, colour: false, unread: 0, look: DEFAULT_LOOK, onKey: () => undefined,
        }), { columns: 80 })
        expect(drawn).toMatch(/◌ run {8}make +2\.\ds/u)
    })

    it('draws at most four pending calls and counts the rest', () => {
        const many = Array.from({ length: 6 }, (_, at): Call => ({
            kind: 'call', ordinal: 4, turn: 2, id: `c${at}`, tool: 'run', salient: `job ${at}`,
            arguments: '{}', argumentsCut: false, argumentsLength: 2, pending: true, unanswered: false, hooks: [],
            entry: { ordinal: 4, turnOrdinal: 2, kind: 'answer', state: 'stands' },
        }))
        const drawn = renderToString(app({
            screen: { ...blank(''), working: { job: 'j1', since: Date.now(), calls: many } },
            composing: { typed: '', at: 0, offering: [] },
            quietly: false, colour: false, unread: 0, look: DEFAULT_LOOK, onKey: () => undefined,
        }), { columns: 80 })
        expect(drawn.match(/◌ run/gu)?.length).toBe(4)
        expect(drawn).toContain('+2 running')
    })
})

describe('the explorer, drawn', () => {
    it('replaces the composer with the explorer\'s lines and keeps the region under the terminal', () => {
        Object.defineProperty(process.stdout, 'rows', { value: 20, configurable: true })
        Object.defineProperty(process.stdout, 'columns', { value: 80, configurable: true })
        const lines = Array.from({ length: 19 }, (_, at) => [tint(`line ${at}`, at === 0 ? 'strong' : 'text')])
        const screen = renderToString(app({
            screen: blank(''), composing: { typed: '', at: 0, offering: [] },
            quietly: false, colour: false, unread: 0, look: DEFAULT_LOOK, onKey: () => undefined,
            exploring: lines,
        }), { columns: 80 })
        expect(screen).toContain('line 0')
        expect(screen).toContain('line 18')
        expect(screen).not.toContain('Ask anything')
    })

    it('cuts lines fitted to a taller terminal to one fewer than the rows it has now', () => {
        Object.defineProperty(process.stdout, 'rows', { value: 10, configurable: true })
        Object.defineProperty(process.stdout, 'columns', { value: 80, configurable: true })
        const lines = Array.from({ length: 19 }, (_, at) => [tint(`line ${at}`, 'text')])
        const screen = renderToString(app({
            screen: blank(''), composing: { typed: '', at: 0, offering: [] },
            quietly: false, colour: false, unread: 0, look: DEFAULT_LOOK, onKey: () => undefined,
            exploring: lines,
        }), { columns: 80 })
        expect(screen).toContain('line 8')
        expect(screen).not.toContain('line 9')
    })
})

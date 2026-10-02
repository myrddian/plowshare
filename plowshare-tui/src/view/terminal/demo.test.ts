import { describe, expect, it } from 'vitest'

import { explorerOpened, levelOf } from '../../logic/explorer.ts'
import { plainOf } from '../../logic/tints.ts'
import { stepsOf } from '../../logic/trajectory.ts'
import { describeCallLines, describeExplorer } from '../../logic/wording.ts'
import { DEMO_CHILD, DEMO_GRANDCHILD, DEMO_ROOT, demoReads } from './demo-log.ts'

/** The arguments each tool is really called with: the key its salient argument comes from. */
const SHAPE: Readonly<Record<string, readonly string[]>> = {
    run: ['command'], file_read: ['path'], file_grep: ['pattern', 'path'], file_edit: ['path', 'old', 'new'],
    agent_run: ['agent', 'task'],
}

describe('the demo\'s fixture', () => {
    it('reads as a session with a failure, a denial, a fold, a pending call and a door two levels deep', async () => {
        const reads = demoReads()
        const root = await reads.tail('demo_root')
        const child = await reads.tail('demo_child')
        expect(root?.entries).toBe(DEMO_ROOT)
        const ex = explorerOpened(levelOf('demo_root', 'plowshare', root!, 'trajectory', 'failure'), 'trajectory')
        const screen = describeExplorer(ex, { rows: 40, columns: 160 }, { children: new Map(), zone: 'UTC' }).map(plainOf).join('\n')
        for (const text of ['✗ exit 1', '✗ denied', 'FOLD', '↳ code_reviewer', 'file_grep']) {
            expect(screen).toContain(text)
        }
        // The failed run's inline excerpt is its cause, not the build's opening lines.
        const failed = stepsOf(DEMO_ROOT).find((step) => step.kind === 'call' && step.id === 'c2')
        const body = failed?.kind === 'call' ? describeCallLines(failed, 'compact', 100).map(plainOf) : []
        expect(body.slice(1, 3)).toEqual(['  │ TokenizerTest > counts_multibyte() FAILED',
            '  │     org.opentest4j.AssertionFailedError: expected: <3> but was: <9>'])
        expect(child?.entries.some((entry) => entry.calls?.some((call) => call.opened?.agent === 'test_runner'))).toBe(true)
    })

    it('folds honestly: the fold stands in for the rows it covers, and the log says they were superseded', async () => {
        const root = (await demoReads().tail('demo_root'))!
        const size = { rows: 40, columns: 160 }
        const extras = { children: new Map(), zone: 'UTC' }
        const trajectory = describeExplorer(explorerOpened(levelOf('demo_root', 'plowshare', root, 'trajectory'), 'trajectory'),
            size, extras).map(plainOf).join('\n')
        expect(trajectory).toContain('FOLD')
        expect(trajectory).not.toContain('what is in this repo?')
        expect(trajectory).not.toContain('Six modules')
        const log = describeExplorer(explorerOpened(levelOf('demo_root', 'plowshare', root, 'log'), 'log'), size, extras)
            .map(plainOf)
        expect(log.filter((line) => line.includes('superseded by #3'))).toHaveLength(2)
    })

    it('calls each tool with its own arguments, the salient one among them', () => {
        const calls = [...DEMO_ROOT, ...DEMO_CHILD, ...DEMO_GRANDCHILD].flatMap((entry) => entry.calls ?? [])
        expect(calls.length).toBeGreaterThan(8)
        for (const call of calls) {
            const parsed = JSON.parse(call.arguments) as Record<string, unknown>
            expect(Object.keys(parsed), call.name).toEqual(SHAPE[call.name])
            expect(parsed).not.toHaveProperty('salient')
            expect(call.length).toBe([...call.arguments].length)
        }
    })
})

import { PassThrough } from 'node:stream'
import { describe, expect, it } from 'vitest'

import { parse } from '../logic/markdown.ts'
import { entered } from '../logic/screen.ts'
import { tint } from '../logic/tints.ts'
import { plain } from './plain.ts'

/** A writable that keeps what it was given, and is never a terminal. */
function piped(): NodeJS.WritableStream & { readonly written: () => string } {
    const chunks: string[] = []
    const stream = new PassThrough()
    stream.on('data', (chunk: Buffer) => chunks.push(chunk.toString('utf8')))
    return Object.assign(stream, { written: () => chunks.join('') })
}

function surfaced(colour = false) {
    const output = piped()
    const input = new PassThrough()
    const surface = plain({ input, output, colour })
    return { surface, output, input }
}

describe('the plain surface', () => {
    it('writes a body and its note on separate lines', () => {
        const { surface, output } = surfaced()
        surface.show(entered(1, 'bot', parse('the answer'), 'this run answered'))
        expect(output.written()).toBe('the answer\nthis run answered\n')
    })

    it('counts the runs waiting on a person once per change, and says nothing about none', () => {
        const { surface, output } = surfaced()
        const run = { id: 'orc_1', definition: 'implement_specification' }
        surface.waiting([])
        surface.waiting([run])
        surface.waiting([run])
        surface.waiting([run, { ...run, id: 'orc_2' }])
        surface.waiting([])
        expect(output.written()).toBe(
            '1 waiting on you · /answer\n2 waiting on you · /answer\n')
    })

    it('prints the panel\'s settled lines once per change, and nothing when it goes away', async () => {
        const { surface, output, input } = surfaced()
        const panel = (settled: string[], elapsed: string) =>
            ({ lines: [`orc_1  d  ${elapsed}`, ...settled.slice(1)].map((line) => [tint(line)]), settled })
        const asking = surface.asked()
        surface.panel?.(panel(['orc_1  d', '  goal ●'], '1s'))
        surface.panel?.(panel(['orc_1  d', '  goal ●'], '9s'))
        surface.panel?.(panel(['orc_1  d', '  goal ✓'], '12s'))
        surface.panel?.(undefined)
        input.end()
        await asking
        expect(output.written()).toBe('> orc_1  d\n  goal ●\norc_1  d\n  goal ✓\n')
    })

    it('holds the panel while nothing is asked for — a turn is going — and prints the latest at the prompt', async () => {
        // A pipe cannot put a line anywhere but the end, so a panel printed while a turn runs
        // lands between its progress lines and its answer, or inside a catch-up of the log.
        const { surface, output, input } = surfaced()
        const panel = (settled: string[]) => ({ lines: settled.map((line) => [tint(line)]), settled })
        surface.panel?.(panel(['orc_1  d', '  goal ●']))
        surface.panel?.(panel(['orc_1  d', '  goal ✓']))
        surface.show(entered(1, 'bot', parse('the answer')))
        expect(output.written()).toBe('the answer\n')
        input.write('next\n')
        await new Promise((settle) => setImmediate(settle))
        await expect(surface.asked()).resolves.toBe('next')
        expect(output.written()).toBe('the answer\norc_1  d\n  goal ✓\n')
        // Come and gone within one turn: the prompt has nothing to say about it.
        surface.panel?.(panel(['orc_1  d', '  code ●']))
        surface.panel?.(undefined)
        const asking = surface.asked()
        input.end()
        await asking
        expect(output.written()).toBe('the answer\norc_1  d\n  goal ✓\n> ')
    })

    it('writes nothing for an entry whose body is empty and has no note', () => {
        const { surface, output } = surfaced()
        surface.show(entered(1, 'client', parse('')))
        expect(output.written()).toBe('')
    })

    it('writes each distinct working line once and never repeats one', () => {
        const { surface, output } = surfaced()
        surface.working({ job: 'job_1', since: 0, said: '1 step' })
        surface.working({ job: 'job_1', since: 0, said: '1 step' })
        surface.working({ job: 'job_1', since: 0, said: '2 steps' })
        // A pipe has nowhere to redraw. Every push saying the same thing would
        // otherwise be another line.
        expect(output.written()).toBe('1 step\n2 steps\n')
    })

    it('writes nothing for a working state that has said nothing yet', () => {
        const { surface, output } = surfaced()
        surface.working({ job: 'job_1', since: 0 })
        expect(output.written()).toBe('')
    })

    it('says a line again after the run it belonged to was cleared', () => {
        const { surface, output } = surfaced()
        surface.working({ job: 'job_1', since: 0, said: '1 step' })
        surface.working(undefined)
        surface.working({ job: 'job_2', since: 0, said: '1 step' })
        // Two runs that each took one step both deserve to say so.
        expect(output.written()).toBe('1 step\n1 step\n')
    })

    it('never writes an escape sequence when colour is off', () => {
        const { surface, output } = surfaced(false)
        surface.show(entered(1, 'bot', parse('a `name` and **weight**')))
        expect(output.written()).not.toContain('')
    })

    it('writes escape sequences when colour is on', () => {
        const { surface, output } = surfaced(true)
        surface.show(entered(1, 'bot', parse('a `name`')))
        expect(output.written()).toContain('[38;2;')
    })

    it('draws no cursor escapes into a stream that is not a terminal', () => {
        const { surface, output } = surfaced(false)
        surface.show(entered(1, 'client', parse('hello')))
        // `cursorTo`/`clearLine` write to whatever they are handed without
        // asking whether it is a terminal. A transcript full of [2K is what
        // this guards, and it is invisible until somebody pipes the output.
        expect(output.written()).toBe('hello\n')
    })

    it('resolves asked with undefined when the input ends', async () => {
        const { surface, input } = surfaced()
        const asking = surface.asked()
        input.end()
        await expect(asking).resolves.toBeUndefined()
    })

    it('hands over a line that arrived before it was asked for', async () => {
        const { surface, input } = surfaced()
        input.write('typed ahead\n')
        await new Promise((settle) => setImmediate(settle))
        await expect(surface.asked()).resolves.toBe('typed ahead')
    })
})

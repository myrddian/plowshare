import { PassThrough } from 'node:stream'
import { performance } from 'node:perf_hooks'
import { setFlagsFromString } from 'node:v8'
import { runInNewContext } from 'node:vm'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'

import { parse } from '../../logic/markdown.ts'
import { entered } from '../../logic/screen.ts'
import type { Entry } from '../../logic/screen.ts'
import { tint } from '../../logic/tints.ts'
import type { Viewed } from '../../logic/record.ts'
import type { Surface } from '../surface.ts'
import type { terminal as Terminal } from './mounting.ts'

/**
 * <b>What a terminal left open for hours holds on to.</b> Measured 2026-09-30: a client left in
 * `/watch` on a busy tree died out of heap at ~4 GB after eleven hours. Driven here on a stream
 * that is not a terminal — which draws no frame worth reading (see `harness.ts`), but renders
 * every tree through React exactly as a terminal does, and that is where the heap went.
 *
 * <p><b>Held is said by a {@link WeakRef} after a forced collection</b>, not by the heap's size:
 * an object nothing reaches is collected, and one that is still there is still reached.
 */

/** A stream Ink takes for a terminal of `rows` by `columns`, whose bytes are kept in `written`. */
function tty(rows = 50, columns = 160): { out: NodeJS.WriteStream, input: NodeJS.ReadStream, written: string[] } {
    const written: string[] = []
    const out = Object.assign(new PassThrough(), { isTTY: true, rows, columns })
    out.on('data', (chunk: Buffer) => written.push(chunk.toString('utf8')))
    const input = Object.assign(new PassThrough(), {
        isTTY: true, setRawMode: () => input, ref: () => input, unref: () => input,
    })
    return { out: out as unknown as NodeJS.WriteStream, input: input as unknown as NodeJS.ReadStream, written }
}

/** A full collection, forced: only after one does a live {@link WeakRef} mean something reaches it. */
function collect(): void {
    setFlagsFromString('--expose-gc')
    const gc = runInNewContext('gc') as () => void
    gc()
    gc()
}

/** Long enough for Ink's throttled frame to have been written. */
const settle = (): Promise<void> => new Promise((done) => setTimeout(done, 60))

/** A viewer frame as `/watch` draws one on a 50-row terminal: a header and 45 record lines. */
const frame = (n: number): Viewed => ({
    head: [[tint(`orc_1  implement_specification  ${n}s`, 'strong')], [tint('  goal ✓ code ●')]],
    body: Array.from({ length: 45 }, (_, at) => [tint('09:05:03  ', 'time'), tint('coder      ', 'actor1'),
        tint(`● read_file src/some/where/file${n}-${at}.ts`, 'text'), tint('  ✓', 'ok')]),
    foot: 'milestones and tool activity · ↑↓ PgUp PgDn scroll · esc back',
})

const numbered = (at: number, text = ''): Entry => entered(at, 'client', parse(`entry ${String(at).padStart(4, '0')} ${text}`))

/** How many times `text` is in `bytes`. */
const times = (bytes: string, text: string): number => bytes.split(text).length - 1

describe('a terminal surface left open', () => {
    let terminal: typeof Terminal
    beforeAll(async () => {
        // THE CONSOLE THE CLIENT RUNS WITH, BEFORE REACT IS LOADED. React's development build
        // measures its renders only where `console.timeStamp` is a function, which Node's console
        // always is and the one vitest puts in its place is not — so without this, nothing here
        // would measure anything and the first case could not fail. Node's own is a no-op with no
        // inspector attached, as this one is.
        const stamping = console as { timeStamp?: (label?: string) => void }
        stamping.timeStamp ??= () => undefined
        terminal = (await import('./mounting.ts')).terminal
    })
    let surface: Surface | undefined
    afterEach(() => {
        surface?.close()
        surface = undefined
    })

    it('keeps no entry in the performance timeline for each redraw of the viewer', async () => {
        const { out, input } = tty()
        surface = terminal({ colour: true, stdout: out, stdin: input })
        surface.view?.(frame(0))
        await settle()
        performance.clearMeasures()
        for (let n = 1; n <= 300; n += 1) {
            surface.view?.(frame(n))
        }
        await settle()

        // React's development build measures every component it renders — about a hundred for
        // one viewer frame — and Node keeps every measure until somebody clears it: 30,000 here
        // before the fix, ~135 KB of heap a frame: the 4 GB of eleven hours is ~30,000 frames.
        expect(performance.getEntriesByType('measure').length).toBeLessThan(500)
    }, 60_000)

    it('holds no entry it has written, and writes each exactly once', async () => {
        const { out, input, written } = tty()
        surface = terminal({ colour: true, stdout: out, stdin: input })
        const shown: WeakRef<Entry>[] = []
        for (let at = 1; at <= 300; at += 1) {
            const entry = numbered(at, 'x'.repeat(200))
            shown.push(new WeakRef(entry))
            surface.show(entry)
        }
        await settle()
        collect()
        const bytes = written.join('')

        // Each entry is in the terminal once — Ink keeps its own copy of the text it wrote, to
        // write again should it ever clear the screen — and the screen's copy is let go.
        for (const at of [1, 150, 300]) {
            expect(times(bytes, `entry ${String(at).padStart(4, '0')}`)).toBe(1)
        }
        // All but the last, which the tree React last drew still holds until it draws another.
        expect(shown.slice(0, -1).filter((entry) => entry.deref() !== undefined)).toHaveLength(0)
    }, 60_000)

    it('writes what was shown while the viewer was up once it goes, and nothing twice', async () => {
        const { out, input, written } = tty()
        surface = terminal({ colour: true, stdout: out, stdin: input })
        surface.show(numbered(1, 'before the viewer'))
        surface.view?.(frame(0))
        const shown = surface
        // Made and shown in a scope of its own, so this case holds nothing of it but the WeakRef.
        const kept = ((): WeakRef<Entry> => {
            const held = numbered(2, 'while the viewer was up')
            shown.show(held)
            return new WeakRef(held)
        })()
        await settle()
        expect(written.join('')).not.toContain('while the viewer was up')
        surface.view?.(undefined)
        surface.show(numbered(3, 'after the viewer'))
        surface.show(numbered(4, 'and after that'))
        surface.show(numbered(5, 'and after that again'))
        await settle()
        collect()
        const bytes = written.join('')

        expect(times(bytes, 'before the viewer')).toBe(1)
        expect(times(bytes, 'while the viewer was up')).toBe(1)
        expect(times(bytes, 'after the viewer')).toBe(1)
        expect(bytes.indexOf('while the viewer was up')).toBeLessThan(bytes.indexOf('after the viewer'))
        expect(kept.deref()).toBeUndefined()
    }, 60_000)
})

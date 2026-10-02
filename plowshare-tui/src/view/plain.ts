import { clearLine, createInterface, cursorTo } from 'node:readline'

import { completing } from '../logic/session.ts'
import type { Panel } from '../logic/record.ts'
import type { Waiting } from '../logic/session.ts'
import { describeWaitingCount } from '../logic/wording.ts'
import type { Entry, Working } from '../logic/screen.ts'
import { plainOf } from '../logic/tints.ts'
import type { Look } from './look.ts'
import type { Surface } from './surface.ts'
import { toTerminal } from './scrollback.ts'

/**
 * The surface that appends lines, which is what a pipe gets.
 *
 * <h2>It is not a fallback, it is the argument</h2>
 *
 * <p>The spec's thesis is that a {@link import('../logic/screen.ts').Screen}
 * carries every decision and no drawing, so that two surfaces can render it in
 * their own idiom without either one leaking into the model. <b>This file is
 * the cheapest possible test of that claim, and it is a real one.</b> If a
 * line-appending surface cannot be written from `Screen` alone — if it needs a
 * width, or a colour the model was holding, or a formatted duration — then the
 * model has a surface baked into it and `logic/screen.ts` is wrong. It did not,
 * and that is worth more than the argument was.
 *
 * <h2>And it is also the one a pipe needs</h2>
 *
 * <p>Ink's `useInput` needs raw mode and raw mode needs a terminal. A piped
 * stdin has none, so the Ink surface throws where this one works — and a piped
 * stdin is not exotic here: every demo and every screenshot in this client's
 * history was driven by `printf … | node src/view/main.ts`. `main.ts` chooses
 * on <b>both</b> streams being terminals, because a TTY stdout with a piped
 * stdin is exactly that shape.
 *
 * <h2>Most of this is lifted from the retired `prompt.ts`, deliberately</h2>
 *
 * <p>The readline half — the queues in both directions, the `close` behaviour,
 * the `isTTY`-guarded cursor dance in {@link Surface.show} — is the part with
 * the bug history, and re-deriving it would be re-earning those bugs. What is
 * new is only the shape at the top: `show` and `working` where there was one
 * `say`, and a body that arrives as blocks rather than as a string.
 */

/** A stream that may or may not be a terminal, which is the only bit read. */
type Out = NodeJS.WritableStream & { readonly isTTY?: boolean }

export interface Plain {
    readonly input: NodeJS.ReadableStream
    readonly output: Out

    /** Whether to emit escape sequences at all. */
    readonly colour?: boolean

    /** What to draw with when there is colour. The default look otherwise. */
    readonly look?: Look

    /** What the live line is prefixed with. `'> '` by default. */
    readonly mark?: string
}

export function plain(options: Plain): Surface {
    const output = options.output
    const terminal = output.isTTY === true
    const colour = options.colour ?? false
    let look: Look | true = options.look ?? true
    /** The names Tab may finish. Empty until the roster has been handed over. */
    let declared: readonly string[] = []
    /** What a press of Ctrl-C means, once somebody has said. */
    let interrupted: (() => void) | undefined
    /**
     * The last progress line written.
     *
     * <b>Kept so it is not written twice.</b> `working` is called on every push
     * and most pushes say the same thing the last one did; a terminal replaces
     * its status region so repetition costs nothing there, but here every call
     * would be another line. Only a change is worth a line.
     */
    let last: string | undefined
    /** The last unread count printed, so a repeat of the same push is silent. */
    let lastUnread: number | undefined
    /** The last count of waiting runs printed, on {@link lastUnread}'s rule. */
    let lastWaiting = 0
    /** The panel's settled lines last printed, joined, so a repeat prints nothing. */
    let lastPanel = ''
    /**
     * The panel's settled lines to print once somebody is asked for a line, joined; nothing when
     * {@link lastPanel} says it all. <b>Held because a pipe has one place to put a line</b>, the
     * end: a panel printed while a turn runs would land between its progress and its answer, or
     * inside a catch-up of the log. Only the latest is kept — what the prompt owes a person is
     * where the runs stand now, not every step they took while the turn ran.
     */
    let heldPanel: string | undefined

    const reader = createInterface({
        input: options.input,
        output,
        prompt: options.mark ?? '> ',
        completer(line: string): [string[], string] {
            const { word, matches } = completing(line, declared)
            return [[...matches], word]
        },
    })
    /** Lines typed faster than they were asked for. */
    const typed: string[] = []
    /** Asks made before there was a line to answer them with. */
    const waiting: ((line: string | undefined) => void)[] = []
    let ended = false

    reader.on('SIGINT', () => {
        if (terminal) {
            reader.write(null, { ctrl: true, name: 'e' })
            reader.write(null, { ctrl: true, name: 'u' })
        }
        const press = interrupted
        if (press === undefined) {
            reader.close()
            return
        }
        press()
    })

    reader.on('line', (line: string) => {
        const asker = waiting.shift()
        if (asker === undefined) {
            typed.push(line)
        } else {
            asker(line)
        }
    })

    reader.on('close', () => {
        ended = true
        while (waiting.length > 0) {
            waiting.shift()?.(undefined)
        }
    })

    /** One line above the prompt, redrawing whatever is half-typed underneath. */
    const say = (line: string): void => {
        if (terminal) {
            cursorTo(output, 0)
            clearLine(output, 0)
        }
        output.write(`${line}\n`)
        if (terminal && !ended) {
            // `true` is the whole point: redraw the prompt AND the line buffer,
            // so a half-typed question survives an event landing in the middle
            // of it.
            reader.prompt(true)
        }
    }

    /** The held panel, printed — at most once per change, and nothing for one that went away. */
    const sayPanel = (): void => {
        const settled = heldPanel
        heldPanel = undefined
        if (settled === undefined || settled === lastPanel) {
            return
        }
        lastPanel = settled
        if (settled !== '') {
            say(settled)
        }
    }

    return {
        show(entry: Entry): void {
            // TOOL LINES AS THEY ARE, WITHOUT COLOUR: the glyphs — ✓ ✗ ◌ · — read on their own.
            if (entry.voice === 'trace') {
                for (const line of entry.lines ?? []) {
                    say(plainOf(line))
                }
                return
            }
            const columns = (output as { readonly columns?: number }).columns ?? 80
            const body = toTerminal(entry.body, colour ? look : false, columns)
            if (body !== '') {
                say(body)
            }
            const note = entry.note
            if (note !== undefined) {
                say(note)
            }
        },

        columns(): number {
            return (output as { readonly columns?: number }).columns ?? 80
        },

        working(state: Working | undefined): void {
            if (state === undefined) {
                last = undefined
                return
            }
            const line = state.said
            // NOT the elapsed time, and not a spinner. A pipe has nowhere to
            // redraw, so anything that changed on a timer would be one line per
            // tick. `Working.since` exists for the surface that can use it.
            if (line === undefined || line === last) {
                return
            }
            last = line
            say(line)
        },

        unread(count: number): void {
            // ONCE PER CHANGE, NOT ONCE PER PUSH. `inbox.changed` can repeat the
            // same count — two firings landing either side of a read leave it
            // unchanged — and a line for each would be noise this surface has
            // no way to take back.
            if (count === lastUnread) {
                return
            }
            lastUnread = count
            if (count > 0) {
                say(`inbox: ${count} unread · /inbox`)
            }
        },

        panel(next: Panel | undefined): void {
            // ONLY WHAT SETTLES, ONCE PER CHANGE, AND ONLY BETWEEN TURNS. A pipe cannot redraw,
            // and the elapsed times and the current tool line change by the second; the
            // checklists and the milestones are what a person reading a log of this session
            // wants a line for. Somebody being asked for a line is what "between turns" looks
            // like from here — see {@link heldPanel}.
            heldPanel = next === undefined ? '' : next.settled.join('\n')
            if (waiting.length > 0) {
                sayPanel()
            }
        },

        waiting(runs: readonly Waiting[]): void {
            // A COUNT, ONCE PER CHANGE. This surface has no menu to offer the
            // runs in and no status line to keep the count on, so a change is
            // a line; the question each run asked is already a line of its own.
            if (runs.length === lastWaiting) {
                return
            }
            lastWaiting = runs.length
            const count = describeWaitingCount(runs.length)
            if (count !== undefined) {
                say(count)
            }
        },

        asked(hidden = false): Promise<string | undefined> {
            if (hidden) {
                // READLINE ECHOES AND THIS SURFACE CANNOT STOP IT. `plain` is
                // what a PIPE gets, and a pipe has no terminal to mute: the
                // characters are already in whatever is feeding it. Rather
                // than pretend, this answers nothing -- `main.ts` reads that
                // as "cannot be asked here" and says so, which is a client
                // that refuses to take a password down a channel it cannot
                // keep it out of.
                return Promise.resolve(undefined)
            }
            // Before the line is taken, typed ahead or not: this is the turn's end.
            if (!ended) {
                sayPanel()
            }
            const ready = typed.shift()
            if (ready !== undefined) {
                return Promise.resolve(ready)
            }
            if (ended) {
                return Promise.resolve(undefined)
            }
            reader.prompt()
            return new Promise((answer) => {
                waiting.push(answer)
            })
        },

        completing(names: readonly string[]): void {
            declared = names
        },

        onInterrupt(listener: () => void): void {
            interrupted = listener
        },

        restyle(next: Look): void {
            look = next
        },

        close(): void {
            reader.close()
        },
    }
}

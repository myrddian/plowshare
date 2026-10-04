import { cleaned } from './clean.ts'
import type { Asked, Entry, Opened } from './client-views.ts'
import { tint, type Role, type Tint, type Tinted } from './tints.ts'

/**
 * A conversation's log read as what happened: who asked, what the model thought, each tool it
 * called paired with what came back, and its answers. Spec 2026-09-29 §3. Pure over entry
 * rows, so the inline lines, the explorer and the demo all read one shape.
 */

/** An outcome word as a person reads it at a glance. */
export type Standing = 'ok' | 'fail' | 'waiting' | 'unknown'

/**
 * `ToolLines`' words: `ok`, `exit 0` and a delegation that `answered` went well. `ran` is not
 * one of them — it is `ToolLines.RAN`, a `run` whose result the display cut took the status line
 * of, so whether it went well is not known.
 */
export function outcomeClass(outcome: string | undefined): Standing {
    if (outcome === undefined || outcome === '' || outcome === 'ran') {
        return 'unknown'
    }
    if (outcome === 'ok' || outcome === 'answered' || outcome === 'exit 0') {
        return 'ok'
    }
    if (outcome === 'asked' || outcome === 'cancelled') {
        return 'waiting'
    }
    return 'fail'
}

interface Based {
    readonly ordinal: number
    readonly turn: number
    /** The row the step is drawn from; for a call, the answer that asked for it. */
    readonly entry: Entry
}

export interface Call extends Based {
    readonly kind: 'call'
    readonly id: string
    readonly tool: string
    readonly salient?: string
    readonly arguments: string
    readonly argumentsCut: boolean
    readonly argumentsLength: number
    readonly opened?: Opened
    /** No result yet, and nothing after it says there never will be one. */
    readonly pending: boolean
    /** No result, and the turn went on past it: stopped, stuck, or a server that restarted. */
    readonly unanswered: boolean
    readonly result?: Entry
    /** Hook rows written after its result. */
    readonly hooks: readonly Entry[]
}

export type Step =
    | (Based & { readonly kind: 'person' })
    | (Based & { readonly kind: 'reasoning' })
    | (Based & { readonly kind: 'answer' })
    | (Based & { readonly kind: 'fold' })
    | (Based & { readonly kind: 'note' })
    | Call

function callOf(asked: Asked, entry: Entry): Call {
    return {
        kind: 'call', ordinal: entry.ordinal, turn: entry.turnOrdinal, entry,
        id: asked.id, tool: asked.name,
        ...(asked.salient === undefined ? {} : { salient: asked.salient }),
        arguments: asked.arguments, argumentsCut: asked.cut, argumentsLength: asked.length,
        ...(asked.opened === undefined ? {} : { opened: asked.opened }),
        pending: true, unanswered: false, hooks: [],
    }
}

/** A stable key for a step: a call is keyed by its answer's ordinal and its id. */
export function stepKey(step: Step): string {
    return step.kind === 'call' ? `${step.ordinal}:${step.id}` : String(step.ordinal)
}

/**
 * The steps of these rows, in log order. Model compaction never removes human history:
 * superseded entries remain inspectable. A result is paired with the call it answers. A result whose call is not among the
 * rows (it is on an earlier page) stays a row of its own rather than being dropped.
 */
export function stepsOf(entries: readonly Entry[]): Step[] {
    const ordered = [...entries].sort((a, b) => a.ordinal - b.ordinal)
    const steps: Step[] = []
    const open = new Map<string, number>()
    let lastSettled: number | undefined
    for (const entry of ordered) {
        const base = { ordinal: entry.ordinal, turn: entry.turnOrdinal, entry }
        switch (entry.kind) {
            case 'utterance':
                steps.push({ kind: 'person', ...base })
                break
            case 'thinking':
                steps.push({ kind: 'reasoning', ...base })
                break
            case 'summary':
            case 'turn_summary':
                steps.push({ kind: 'fold', ...base })
                break
            case 'answer':
                if ((entry.text ?? '').trim() !== '') {
                    steps.push({ kind: 'answer', ...base })
                }
                for (const asked of entry.calls ?? []) {
                    open.set(asked.id, steps.length)
                    steps.push(callOf(asked, entry))
                }
                break
            case 'tool_result': {
                const at = entry.toolCallId === undefined ? undefined : open.get(entry.toolCallId)
                const call = at === undefined ? undefined : steps[at]
                if (at !== undefined && call?.kind === 'call') {
                    steps[at] = { ...call, pending: false, result: entry }
                    open.delete(call.id)
                    lastSettled = at
                } else {
                    steps.push({ kind: 'note', ...base })
                }
                break
            }
            case 'hook': {
                const call = lastSettled === undefined ? undefined : steps[lastSettled]
                if (lastSettled !== undefined && call?.kind === 'call') {
                    steps[lastSettled] = { ...call, hooks: [...call.hooks, entry] }
                } else {
                    steps.push({ kind: 'note', ...base })
                }
                break
            }
            default:
                steps.push({ kind: 'note', ...base })
        }
    }
    return steps.map((step) => step.kind === 'call' && step.pending && outlived(step, ordered)
        ? { ...step, pending: false, unanswered: true }
        : step)
}

/** Whether the log went on past this call without answering it: a later turn, or its turn's last answer. */
function outlived(call: Call, ordered: readonly Entry[]): boolean {
    return ordered.some((entry) => entry.ordinal > call.ordinal
        && (entry.turnOrdinal > call.turn
            || (entry.kind === 'answer' && (entry.calls ?? []).length === 0)))
}

export function callStanding(call: Call): Standing {
    if (call.pending) {
        return 'waiting'
    }
    return call.unanswered ? 'unknown' : outcomeClass(call.result?.outcome)
}

export interface Turn {
    readonly ordinal: number
    readonly steps: readonly Step[]
    /** Distinct model calls: each answer row once, however many tools it asked for. */
    readonly modelCalls: number
    readonly modelMillis: number
    readonly toolMillis: number
    readonly calls: number
    readonly failed: number
}

export function turnsOf(steps: readonly Step[]): Turn[] {
    const byTurn = new Map<number, Step[]>()
    for (const step of steps) {
        byTurn.set(step.turn, [...(byTurn.get(step.turn) ?? []), step])
    }
    return [...byTurn.entries()].sort(([a], [b]) => a - b).map(([ordinal, own]) => {
        const answers = new Map<number, Entry>()
        for (const step of own) {
            if (step.kind === 'answer' || step.kind === 'call') {
                answers.set(step.entry.ordinal, step.entry)
            }
        }
        const calls = own.filter((step): step is Call => step.kind === 'call')
        return {
            ordinal, steps: own,
            modelCalls: answers.size,
            modelMillis: [...answers.values()].reduce((sum, entry) => sum + (entry.tookMillis ?? 0), 0),
            toolMillis: calls.reduce((sum, call) => sum + (call.result?.tookMillis ?? 0), 0),
            calls: calls.length,
            failed: calls.filter((call) => callStanding(call) === 'fail').length,
        }
    })
}

export interface Cut {
    readonly head: readonly string[]
    readonly tail: readonly string[]
    readonly hidden: number
}

/** `text`, cleaned to draw, as lines without the trailing blank ones. */
const linesOf = (text: string): string[] => cleaned(text).replace(/\n+$/u, '').split('\n')

/** The first `head` and last `tail` lines of `text`, cleaned to draw, and how many lie between. */
export function cutLines(text: string, head: number, tail: number): Cut {
    const lines = linesOf(text)
    if (lines.length <= head + tail) {
        return { head: lines, tail: [], hidden: 0 }
    }
    return { head: lines.slice(0, head), tail: lines.slice(lines.length - tail), hidden: lines.length - head - tail }
}

/** A line that says why a call failed, as test runners, compilers and stack traces write it. */
const CAUSE = /FAILED|Error|error:|Exception|expected|assert/iu

/**
 * A failed call's excerpt: the first line that says why — {@link CAUSE} — and the lines after it,
 * `span` in all, then the last `tail`. A `run`'s head is its status line and its stdout banner,
 * which say nothing the ✗ does not; the cause is further down. With no such line, or too few
 * lines to cut, it is {@link cutLines}. Spec 2026-09-29 §4.
 */
export function failureCut(text: string, span: number, tail: number): Cut {
    const lines = linesOf(text)
    const at = lines.findIndex((line) => CAUSE.test(line))
    if (at === -1 || lines.length <= span + tail) {
        return cutLines(text, span, tail)
    }
    // A cause among the last lines is shown by the tail; the span then sits just above it.
    const from = Math.min(at, lines.length - tail - span)
    return {
        head: lines.slice(from, from + span),
        tail: lines.slice(lines.length - tail),
        hidden: lines.length - span - tail,
    }
}

interface Span {
    readonly lane: 'model' | 'tools'
    readonly from: number
    readonly to: number
    readonly fail: boolean
    readonly step: number
}

/**
 * Where the time went, as two lanes `cells` wide: model calls above, tools below, each step as
 * wide as its share of the total. A failed call is drawn `fail`; the cursor's step is a `▲` on
 * the tool lane. A step that recorded no time is drawn one millisecond wide, so it still shows.
 */
export function stripOf(steps: readonly Step[], cells: number, cursor?: number): { model: Tinted, tools: Tinted } {
    const spans: Span[] = []
    let clock = 0
    const counted = new Set<number>()
    steps.forEach((step, at) => {
        // A call's entry is the answer that asked for it: its model time is counted once, by
        // whichever of that answer's steps comes first.
        if (step.kind === 'answer' || step.kind === 'reasoning' || step.kind === 'call') {
            if (!counted.has(step.entry.ordinal)) {
                counted.add(step.entry.ordinal)
                const took = Math.max(1, step.entry.tookMillis ?? 0)
                spans.push({ lane: 'model', from: clock, to: clock + took, fail: false, step: at })
                clock += took
            }
        }
        if (step.kind === 'call') {
            const took = Math.max(1, step.result?.tookMillis ?? 0)
            spans.push({ lane: 'tools', from: clock, to: clock + took, fail: callStanding(step) === 'fail', step: at })
            clock += took
        }
    })
    if (clock === 0 || cells <= 0) {
        return { model: [], tools: [] }
    }
    const pointed = spans.find((span) => span.step === cursor && span.lane === 'tools')
        ?? spans.find((span) => span.step === cursor)
    const cursorCell = pointed === undefined ? undefined
        : Math.min(cells - 1, Math.floor((pointed.from / clock) * cells))
    const lane = (which: 'model' | 'tools'): Tint[] => {
        const drawn: Tint[] = []
        for (let cell = 0; cell < cells; cell += 1) {
            const from = (cell * clock) / cells
            const to = ((cell + 1) * clock) / cells
            const here = spans.filter((span) => span.lane === which && span.from < to && span.to > from)
            const role: Role = here.some((span) => span.fail) ? 'fail'
                : which === 'model' ? 'timelineModel' : 'timelineTool'
            drawn.push(which === 'tools' && cell === cursorCell ? tint('▲', 'selected')
                : here.length > 0 ? tint('▀', role) : tint(' '))
        }
        return merged(drawn)
    }
    return { model: lane('model'), tools: lane('tools') }
}

/** Neighbouring tints of one role joined, so a surface paints runs rather than cells. */
function merged(cells: readonly Tint[]): Tint[] {
    const runs: Tint[] = []
    for (const each of cells) {
        const last = runs.at(-1)
        if (last !== undefined && last.role === each.role && last.back === each.back) {
            runs[runs.length - 1] = { ...last, text: last.text + each.text }
        } else {
            runs.push(each)
        }
    }
    return runs
}

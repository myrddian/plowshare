/**
 * `EditFacts`, `Excerpt` and the parts of `Replacement` that find what an edit
 * reports: where the new text is after a success, and what is nearest an `old`
 * that is not in the file — <b>as facts</b>, which the server words.
 *
 * <h2>Why a second copy, and why only the facts</h2>
 *
 * <p>`Replacement`'s reason, which is why `replacedAt` sits beside it in
 * `files.ts`: an edit is applied on the machine that owns the file, and this
 * client is one. Finding the lines needs the file, so it is done here; saying
 * anything about them does not, so it is not. This file used to be `EditView`
 * again, words and all, and measured on 2026-09-30 its words had drifted from the
 * Java client's — the spec (2026-09-30, the file side reports facts; the server
 * words them) moved every word to the server, character names included, and left
 * this the matching and the window-finding. `editfacts.test.ts` runs the shared
 * table `edit-results.json` that `EditFactsTest` runs, and `mirrors-the-server.test.ts`
 * reads the bounds and the look-alike table out of the Java.
 *
 * <h2>Java's strings, not JavaScript's</h2>
 *
 * <p>Both are UTF-16, so every index and every length here is the Java one.
 * Lines split as `String.lines()` splits them (`linesOf`), whitespace is
 * `Character.isWhitespace` (`isJavaWhitespace`), and `strip` is Java's.
 *
 * <h2>Nothing here changes what an edit does</h2>
 *
 * <p>A mismatch is still refused. Whitespace, a look-alike character and the
 * closest lines are reported, never applied.
 */
import { Unreplaced, isJavaWhitespace, linesOf, replacedAt } from './files.ts'
import {
    CLOSEST, EDIT, EDITED, EMPTY_FILE, EMPTY_OLD, LOOKALIKE, MANY_MATCHES, NO_MATCH, REFUSED, RESULT_VERSION,
    WHITESPACE,
} from './files.ts'
import type { Difference, Excerpt, FileResult } from './files.ts'

/** `Replacement.CONTEXT_LINES`: how many lines either side of the new text a success reports. */
export const CONTEXT_LINES = 3

/** `Replacement.MAX_SHOWN_LINES`: a longer region keeps its first and last lines. */
export const MAX_SHOWN_LINES = 40

/** `Replacement.MAX_SHOWN_LINE_CHARS`: a longer line is clipped and its index reported. */
export const MAX_SHOWN_LINE_CHARS = 500

/** `Replacement.MAX_LISTED`: the most foreign characters a no-match lists; the rest are counted. */
export const MAX_LISTED = 5

/** `Replacement.Edited`: the file after an edit, and where the new text is. */
export interface Edited {
    readonly text: string
    readonly first: number
    readonly last: number
    readonly removed: boolean
    readonly excerpt: Excerpt
}

/** `Replacement.edit`: {@link replacedAt}, and where the new text is now. */
export function edit(text: string, old: string, replacement: string): Edited {
    const made = replacedAt(text, old, replacement)
    return success(made.text, made.at, replacement)
}

/** `Replacement.Edited.result`: what this client answers with, about the file as named. */
export function editedResult(edited: Edited, named: string): FileResult {
    return {
        version: RESULT_VERSION, kind: EDITED, op: EDIT, path: named,
        first: edited.first, last: edited.last, removed: edited.removed, excerpt: edited.excerpt,
    }
}

/**
 * `Replacement.Refused.result`: a no-match with what is nearest, a many-matches
 * with the count, or an empty `old`.
 */
export function refusalResult(refusal: Unreplaced, named: string): FileResult {
    switch (refusal.kind) {
        case 'empty':
            return { version: RESULT_VERSION, kind: REFUSED, op: EDIT, path: named, reason: EMPTY_OLD }
        case 'absent':
            return absent(named, refusal.text ?? '', refusal.old ?? '')
        case 'ambiguous':
            return { version: RESULT_VERSION, kind: MANY_MATCHES, op: EDIT, path: named, count: refusal.count }
    }
}

// --- after a success ------------------------------------------------------------

/** `EditFacts.success`. */
function success(edited: string, at: number, replacement: string): Edited {
    const lines = linesOf(edited)
    const removed = replacement === ''
    if (lines.length === 0) {
        return { text: edited, first: 0, last: 0, removed, excerpt: excerptOf(lines, 0, -1) }
    }
    const last = lines.length - 1
    const first = Math.min(lineOf(edited, at), last)
    const end = removed ? first : Math.min(lineOf(edited, at + replacement.length - 1), last)
    return {
        text: edited, first, last: end, removed,
        excerpt: excerptOf(lines, first - CONTEXT_LINES, end + CONTEXT_LINES),
    }
}

// --- after a mismatch -----------------------------------------------------------

function noMatch(named: string, fields: Partial<FileResult>): FileResult {
    return { version: RESULT_VERSION, kind: NO_MATCH, op: EDIT, path: named, ...fields }
}

/** `EditFacts.absent`: a look-alike match, a whitespace match, or the closest lines and foreign characters. */
function absent(named: string, text: string, old: string): FileResult {
    if (text === '') {
        return noMatch(named, { near: EMPTY_FILE })
    }
    const lines = linesOf(text)
    const alike = lookalikes(named, text, old, lines)
    if (alike !== undefined) {
        return alike
    }
    const spaced = whitespace(named, text, old, lines)
    if (spaced !== undefined) {
        return spaced
    }
    const foreign = foreignOf(text, old)
    const near = closest(lines, old)
    return noMatch(named, {
        count: foreign.length,
        ...(near === undefined ? {} : { near: CLOSEST, excerpt: near }),
        foreign: foreign.slice(0, MAX_LISTED),
    })
}

/**
 * `old` matches once look-alike characters are folded to the ASCII they stand
 * for, on either side: each difference, and where.
 */
function lookalikes(named: string, text: string, old: string, lines: readonly string[]): FileResult | undefined {
    const foldedOld = fold(old)
    const foldedText = fold(text)
    if (foldedOld === old && foldedText === text) {
        return undefined
    }
    // One unit to one unit, so a position in the folded text is the same
    // position in the file.
    const at = foldedText.indexOf(foldedOld)
    if (at < 0) {
        return undefined
    }
    const differences: Difference[] = []
    const seen = new Set<string>()
    for (let i = 0; i < old.length; i += 1) {
        const sent = old.charCodeAt(i)
        const there = text.charCodeAt(at + i)
        if (sent !== there && !seen.has(`${sent}:${there}`)) {
            seen.add(`${sent}:${there}`)
            differences.push({ sent, there })
        }
    }
    return noMatch(named, {
        near: LOOKALIKE,
        excerpt: excerptOf(lines, lineOf(text, at), lineOf(text, at + old.length - 1)),
        differences,
    })
}

/** `old` matches once every whitespace character is ignored on both sides. */
function whitespace(named: string, text: string, old: string, lines: readonly string[]): FileResult | undefined {
    const squashedOld = squash(old)
    if (squashedOld === '') {
        return undefined
    }
    const at = squash(text).indexOf(squashedOld)
    if (at < 0) {
        return undefined
    }
    let start = -1
    let end = -1
    let seen = 0
    for (let i = 0; i < text.length && end < 0; i += 1) {
        if (blank(text.charCodeAt(i))) {
            continue
        }
        if (seen === at) {
            start = i
        }
        if (seen === at + squashedOld.length - 1) {
            end = i
        }
        seen += 1
    }
    return noMatch(named, { near: WHITESPACE, excerpt: excerptOf(lines, lineOf(text, start), lineOf(text, end)) })
}

/** The non-ASCII code points of `old` that are nowhere in the file, in order. */
function foreignOf(text: string, old: string): number[] {
    const seen = new Set<number>()
    for (const character of old) {
        const point = character.codePointAt(0) ?? 0
        if (point > 0x7f) {
            seen.add(point)
        }
    }
    return [...seen].filter((point) => !text.includes(String.fromCodePoint(point)))
}

/**
 * The window of `old`'s line count holding the most lines equal to `old`'s once
 * both are stripped, the first of equals; undefined when no line matches. Blank
 * lines are in every file and count for nothing.
 */
function closest(lines: readonly string[], old: string): Excerpt | undefined {
    const wanted = linesOf(old)
    const span = Math.max(1, wanted.length)
    const starts = Math.max(0, lines.length - span) + 1
    const where = new Map<string, number[]>()
    wanted.forEach((line, j) => {
        const stripped = strip(line)
        if (stripped !== '') {
            const at = where.get(stripped)
            if (at === undefined) {
                where.set(stripped, [j])
            } else {
                at.push(j)
            }
        }
    })
    if (where.size === 0) {
        return undefined
    }
    const score = new Array<number>(starts).fill(0)
    lines.forEach((line, at) => {
        for (const j of where.get(strip(line)) ?? []) {
            const start = at - j
            if (start >= 0 && start < starts) {
                score[start] = (score[start] ?? 0) + 1
            }
        }
    })
    let best = 0
    for (let start = 1; start < starts; start += 1) {
        if ((score[start] ?? 0) > (score[best] ?? 0)) {
            best = start
        }
    }
    if ((score[best] ?? 0) === 0) {
        return undefined
    }
    return excerptOf(lines, best, best + span - 1)
}

// --- the shared shape -----------------------------------------------------------

/**
 * `Excerpt.of`: lines `from` to `to` of a file, clamped to it and bounded — at
 * most {@link MAX_SHOWN_LINES}, the first and last half of a longer region, each
 * clipped at {@link MAX_SHOWN_LINE_CHARS}. Raw lines and where they are; the
 * server says how.
 */
export function excerptOf(all: readonly string[], from: number, to: number): Excerpt {
    const total = all.length
    from = Math.max(0, from)
    to = Math.min(total - 1, to)
    const count = to - from + 1
    const lines: string[] = []
    const clipped: number[] = []
    let gap: number | undefined
    const take = (line: string): void => {
        lines.push(clip(line, lines.length, clipped))
    }
    if (count > MAX_SHOWN_LINES) {
        const head = Math.floor(MAX_SHOWN_LINES / 2)
        const tail = MAX_SHOWN_LINES - head
        for (let i = from; i < from + head; i += 1) {
            take(all[i] ?? '')
        }
        gap = head
        for (let i = to - tail + 1; i <= to; i += 1) {
            take(all[i] ?? '')
        }
    } else {
        for (let i = from; i <= to; i += 1) {
            take(all[i] ?? '')
        }
    }
    return {
        from, to, total, lines,
        ...(gap === undefined ? {} : { gap }),
        ...(clipped.length === 0 ? {} : { clipped }),
    }
}

function clip(line: string, index: number, clipped: number[]): string {
    const most = MAX_SHOWN_LINE_CHARS
    if (line.length <= most) {
        return line
    }
    clipped.push(index)
    // Never half of a surrogate pair: a lone surrogate is not text.
    const unit = line.charCodeAt(most - 1)
    const end = unit >= 0xd800 && unit <= 0xdbff ? most - 1 : most
    return line.slice(0, end)
}

/**
 * `EditFacts.lineOf`: the line `at` is on, counting terminators as
 * `String.lines()` does — `\n`, `\r\n` and a lone `\r` each end one line.
 */
export function lineOf(text: string, at: number): number {
    let line = 0
    for (let i = 0; i < at; i += 1) {
        const c = text.charCodeAt(i)
        if (c === 0x0a || (c === 0x0d && (i + 1 >= text.length || text.charCodeAt(i + 1) !== 0x0a))) {
            line += 1
        }
    }
    return line
}

// --- characters -----------------------------------------------------------------

/**
 * `EditFacts.FOLDS`: characters a model types for the ASCII one a file has, each
 * to that one character — so folding never moves a position. Read out of
 * `EditFacts.java` by `mirrors-the-server.test.ts`, so the two tables are one.
 */
export const FOLD_SOURCES: readonly (readonly [string, string])[] = [
    ['‐‑‒–—―−﹣－', '-'],
    ['            　', ' '],
    ['‘’‚‛′＇', "'"],
    ['“”„‟″＂', '"'],
]

/** Each look-alike's code unit to its ASCII one's. */
const FOLDS: ReadonlyMap<number, number> = new Map(FOLD_SOURCES.flatMap(([alike, ascii]) =>
    [...alike].map((one) => [one.charCodeAt(0), ascii.charCodeAt(0)] as const)))

function fold(text: string): string {
    const pieces: string[] = []
    let from = 0
    for (let i = 0; i < text.length; i += 1) {
        const ascii = FOLDS.get(text.charCodeAt(i))
        if (ascii !== undefined) {
            pieces.push(text.slice(from, i), String.fromCharCode(ascii))
            from = i + 1
        }
    }
    if (from === 0) {
        return text
    }
    pieces.push(text.slice(from))
    return pieces.join('')
}

/**
 * `Character.isWhitespace` of one UTF-16 unit, as `EditFacts` asks it of a
 * `char`. Printable ASCII is answered without the regular expression, since a
 * file is mostly that and this is asked of every unit of it.
 */
function blank(unit: number): boolean {
    return !(unit > 0x20 && unit < 0x7f) && isJavaWhitespace(unit)
}

/** Every whitespace unit removed, as `EditFacts.squash` does. */
function squash(text: string): string {
    const pieces: string[] = []
    let from = 0
    for (let i = 0; i < text.length; i += 1) {
        if (blank(text.charCodeAt(i))) {
            pieces.push(text.slice(from, i))
            from = i + 1
        }
    }
    pieces.push(text.slice(from))
    return pieces.join('')
}

/** `String.strip()`: `Character.isWhitespace` code points off both ends. */
function strip(text: string): string {
    let start = 0
    let end = text.length
    while (start < end) {
        const point = text.codePointAt(start) ?? 0
        if (!isJavaWhitespace(point)) {
            break
        }
        start += point > 0xffff ? 2 : 1
    }
    while (end > start) {
        const low = text.charCodeAt(end - 1)
        const width = low >= 0xdc00 && low <= 0xdfff && end - 2 >= start
            && text.charCodeAt(end - 2) >= 0xd800 && text.charCodeAt(end - 2) <= 0xdbff ? 2 : 1
        if (!isJavaWhitespace(text.codePointAt(end - width) ?? 0)) {
            break
        }
        end -= width
    }
    return text.slice(start, end)
}

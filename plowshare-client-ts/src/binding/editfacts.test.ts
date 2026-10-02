import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { MAX_SHOWN_LINE_CHARS, MAX_SHOWN_LINES, edit, editedResult, excerptOf, refusalResult } from './editfacts.ts'
import { LOOKALIKE, Unreplaced, linesOf, replaced } from './files.ts'
import type { FileResult } from './files.ts'

/**
 * `EditFactsTest`'s table, `edit-results.json`, run against this port: the same
 * edits must report the same facts on this client as on the Java one, because the
 * server words whatever either sends. A case added there fails here the same day.
 */

const SRC = dirname(fileURLToPath(import.meta.url))

interface Case {
    readonly name: string
    readonly text: string
    readonly old: string
    readonly new: string
    readonly result: FileResult
}

const table = JSON.parse(readFileSync(join(SRC, '..', '..', '..',
    'plowshare-protocol/src/test/resources/io/aeyer/plowshare/protocol/edit-results.json'),
'utf8')) as Case[]

function resultOf(text: string, old: string, replacement: string): FileResult {
    try {
        return editedResult(edit(text, old, replacement), '/repo/A.java')
    } catch (trouble) {
        if (trouble instanceof Unreplaced) {
            return refusalResult(trouble, '/repo/A.java')
        }
        throw trouble
    }
}

/** `l0\n` through `l<n-1>\n`. */
function numbered(n: number): string {
    return Array.from({ length: n }, (_, i) => `l${i}\n`).join('')
}

describe('an edit reports what EditFacts reports', () => {
    it('reads a table with the cases it was written with', () => {
        expect(table.length).toBeGreaterThan(20)
    })

    it.each(table.map((one) => [one.name, one] as const))('%s', (_name, one) => {
        expect(resultOf(one.text, one.old, one.new)).toEqual(one.result)
    })
})

describe('what crosses the wire', () => {
    it('reports the lines a read of the edited file returns', () => {
        const edited = edit(numbered(20), 'l10\n', 'A\nB\n')

        expect(edited.excerpt.lines).toEqual(linesOf(edited.text).slice(7, 15))
    })

    it('is bounded whatever the file', () => {
        const wide = `${'y'.repeat(10_000)}\n`.repeat(500)
        const excerpt = edit(`${wide}mark\n${wide}`, 'mark', wide).excerpt

        expect(excerpt.lines.length).toBeLessThanOrEqual(MAX_SHOWN_LINES)
        expect(excerpt.lines.every((line) => line.length <= MAX_SHOWN_LINE_CHARS)).toBe(true)
    })

    it('never clips between the halves of a surrogate pair', () => {
        const wide = `${'x'.repeat(MAX_SHOWN_LINE_CHARS - 1)}\u{1F600}tail`
        const excerpt = excerptOf([wide], 0, 0)

        expect(excerpt.lines[0]).toBe('x'.repeat(MAX_SHOWN_LINE_CHARS - 1))
        expect(excerpt.clipped).toEqual([0])
    })

    it('edits to the text replaced() gives', () => {
        expect(edit('alpha beta', 'beta', 'gamma').text).toBe(replaced('alpha beta', 'beta', 'gamma'))
    })

    it('recognises every listed look-alike in either direction', () => {
        for (const alike of ['‐', '‑', '–', '—']) {
            const result = resultOf('a-b\n', `a${alike}b`, 'x')
            expect(result.near).toBe(LOOKALIKE)
            expect(result.differences).toEqual([{ sent: alike.charCodeAt(0), there: 0x2d }])
        }
    })
})

import { describe, expect, it } from 'vitest'
import {
    MAX_MATCHES, MAX_WINDOW_BYTES, MAX_WINDOW_LINES, Unservable, cut, find, globMatcher,
    linesOf, requestIn, sought, spellings, utf8Length, windowOf,
} from './files.ts'
import type { Match } from './files.ts'

describe('a request off the wire', () => {
    it('reads the fields a server sends and drops the nulls Jackson writes', () => {
        expect(requestIn(JSON.stringify({
            id: 'r1', op: 'read', path: 'a.txt', pattern: null, content: null,
            offset: 10, limit: 5, needle: null, ignoreCase: null,
        }))).toEqual({ id: 'r1', op: 'read', path: 'a.txt', offset: 10, limit: 5 })
    })

    it('is nothing when there is no id to answer', () => {
        expect(requestIn(JSON.stringify({ op: 'roots' }))).toBeUndefined()
        expect(requestIn('not json')).toBeUndefined()
        expect(requestIn(42)).toBeUndefined()
    })

    it('keeps the purpose the harness marks its own reads with', () => {
        expect(requestIn(JSON.stringify({
            id: 'r3', op: 'glob', pattern: '.plowshare/bots/*', purpose: 'definitions',
        }))?.purpose).toBe('definitions')
        expect(requestIn(JSON.stringify({ id: 'r4', op: 'read', path: 'a', purpose: null })))
            .toEqual({ id: 'r4', op: 'read', path: 'a' })
    })

    it('keeps an unknown op, so the refusal can name it', () => {
        expect(requestIn(JSON.stringify({ id: 'r2', op: 'chmod' }))?.op).toBe('chmod')
    })

    it('reads the edit, move and create-only fields, and drops them when null', () => {
        expect(requestIn(JSON.stringify({
            id: 'r5', op: 'edit', path: 'a', content: '', replacing: 'x', to: null, createOnly: null,
        }))).toEqual({ id: 'r5', op: 'edit', path: 'a', content: '', replacing: 'x' })
        expect(requestIn(JSON.stringify({ id: 'r6', op: 'move', path: 'a', to: 'b' }))?.to).toBe('b')
        expect(requestIn(JSON.stringify({ id: 'r7', op: 'write', path: 'a', content: 'x', createOnly: true }))
            ?.createOnly).toBe(true)
    })
})

describe('a window, as Window.of and Window.cut have it', () => {
    it('clamps a limit over the maximum rather than refusing it', () => {
        expect(windowOf({ id: 'x', op: 'read', limit: 99_999 }).limit).toBe(MAX_WINDOW_LINES)
    })

    it('refuses a negative offset and an empty limit', () => {
        expect(() => windowOf({ id: 'x', op: 'read', offset: -1 })).toThrow(Unservable)
        expect(() => windowOf({ id: 'x', op: 'read', limit: 0 })).toThrow(Unservable)
    })

    it('stops on lines, and says more remains', () => {
        expect(cut(['a', 'b', 'c'], 0, 2)).toEqual({
            lines: ['a', 'b'], offset: 0, totalLines: 3, more: true, stoppedBy: 'lines',
        })
    })

    it('says end, not lines, when the limit happens to land on the last line', () => {
        expect(cut(['a', 'b'], 0, 2).stoppedBy).toBe('end')
    })

    it('is empty and ended past the last line', () => {
        expect(cut(['a'], 5, 10)).toEqual({
            lines: [], offset: 5, totalLines: 1, more: false, stoppedBy: 'end',
        })
    })

    it('stops on bytes, counting UTF-8 and a newline per line', () => {
        const wide = 'é'.repeat(30_000)   // 60 000 bytes + 1
        const span = cut([wide, wide], 0, 10)
        expect(span.lines).toHaveLength(1)
        expect(span.stoppedBy).toBe('bytes')
    })

    it('refuses a first line wider than one window carries, as the facts the server words', () => {
        let thrown: unknown
        try {
            cut(['x'.repeat(MAX_WINDOW_BYTES + 1)], 0, 1)
        } catch (trouble) {
            thrown = trouble
        }
        expect(thrown).toBeInstanceOf(Unservable)
        expect((thrown as Unservable).facts).toEqual(
            { reason: 'line-too-wide', first: 0, bytes: MAX_WINDOW_BYTES + 1, limit: MAX_WINDOW_BYTES })
    })
})

describe('lines, as String.lines has them', () => {
    it('splits on every line ending and drops only a trailing empty line', () => {
        expect(linesOf('a\r\nb\rc\n')).toEqual(['a', 'b', 'c'])
        expect(linesOf('\n')).toEqual([''])
        expect(linesOf('')).toEqual([])
    })

    it('counts bytes as UTF-8 does', () => {
        expect(utf8Length('aé€😀')).toBe(1 + 2 + 3 + 4)
    })
})

describe('a needle', () => {
    it('refuses a blank one, which would match every line', () => {
        expect(() => sought({ id: 'x', op: 'grep', needle: '  ' })).toThrow(Unservable)
    })

    it('accepts a needle of only U+00A0, which Character.isWhitespace does not count', () => {
        // Java's String.isBlank() walks Character.isWhitespace, and that method
        // excludes the no-break space family (U+00A0, U+2007, U+202F) from the
        // Unicode space separators it otherwise treats as blank. A needle of
        // only U+00A0 is therefore not blank in Java and must not be refused
        // here either, even though `' '.repeat`-style ASCII whitespace still is.
        expect(() => sought({ id: 'x', op: 'grep', needle: ' ' })).not.toThrow()
    })

    it('finds by line offset, case folded when asked, shortened past the limit', () => {
        const into: Match[] = []
        const capped = find('f.txt', ['nothing', `Found ${'x'.repeat(300)}`], {
            text: 'found', ignoreCase: true,
        }, into)
        expect(capped).toBe(false)
        expect(into).toHaveLength(1)
        expect(into[0]?.offset).toBe(1)
        expect(into[0]?.line).toHaveLength(200)
        expect(into[0]?.truncated).toBe(true)
    })

    it('says capped only when a match past the maximum exists', () => {
        const into: Match[] = []
        const lines = Array.from({ length: MAX_MATCHES + 1 }, () => 'hit')
        expect(find('f', lines, { text: 'hit', ignoreCase: false }, into)).toBe(true)
        expect(into).toHaveLength(MAX_MATCHES)
    })

    it('folds per code unit the way regionMatches(true, …) does, not whole-string toLowerCase', () => {
        // Measured against real Java: "ΟΔΟΣ".regionMatches(true, i, "σ", 0, 1)
        // finds the trailing Σ (capital sigma), because Character.toUpperCase
        // and Character.toLowerCase operate one char at a time and never apply
        // Greek's word-final-sigma rule. JS's whole-string
        // "ΟΔΟΣ".toLowerCase() is context-sensitive and rewrites that same
        // trailing Σ to ς (U+03C2, final sigma) instead of σ (U+03C3) — which
        // does not contain the needle, and is why toLowerCase()+includes()
        // diverges from Java here.
        const into: Match[] = []
        expect(find('f', ['ΟΔΟΣ'], { text: 'σ', ignoreCase: true }, into)).toBe(false)
        expect(into).toHaveLength(1)
    })
})

describe('a glob, as GlobSpellings and the JDK glob have it', () => {
    it('spells **/ both as a directory and as none', () => {
        expect(spellings('src/**/a.ts')).toEqual(['src/**/a.ts', 'src/a.ts'])
    })

    it('refuses more recursive wildcards than it will expand', () => {
        expect(() => spellings('**/**/**/**/**/x')).toThrow(Unservable)
    })

    it('keeps * inside one segment and lets ** cross them', () => {
        expect(globMatcher('*.md')('a.md')).toBe(true)
        expect(globMatcher('*.md')('dir/a.md')).toBe(false)
        expect(globMatcher('**.md')('dir/a.md')).toBe(true)
    })

    it('reads ?, a class, a negated class and a group', () => {
        expect(globMatcher('a?.txt')('ab.txt')).toBe(true)
        expect(globMatcher('[ab].txt')('b.txt')).toBe(true)
        expect(globMatcher('[!ab].txt')('b.txt')).toBe(false)
        expect(globMatcher('*.{ts,md}')('x.md')).toBe(true)
        expect(globMatcher('*.{ts,md}')('x.js')).toBe(false)
    })

    it('matches a dot and a plus literally', () => {
        expect(globMatcher('a+b.txt')('a+b.txt')).toBe(true)
        expect(globMatcher('a.txt')('abtxt')).toBe(false)
    })

    it('refuses what it cannot read', () => {
        expect(() => globMatcher('[ab')).toThrow(Unservable)
        expect(() => globMatcher('{a,{b}}')).toThrow(Unservable)
    })
})

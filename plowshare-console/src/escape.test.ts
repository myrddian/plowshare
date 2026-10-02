import { describe, expect, it } from 'vitest'
import { escape } from './escape'

describe('escape', () => {
    it('escapes every character that could change the meaning of markup', () => {
        // One payload carrying all five, plus the javascript: URL the brief
        // asks for, so that a regression in any one of them fails here rather
        // than in whichever screen happens to render that character first.
        const payload = `<img src="x" onerror='alert(1)' title=a&b href=javascript:alert(2)>`
        const escaped = escape(payload)

        expect(escaped).not.toContain('<')
        expect(escaped).not.toContain('>')
        expect(escaped).not.toContain('"')
        expect(escaped).not.toContain("'")
        // No bare ampersand survives: every one in the output is the start of
        // an entity this function wrote.
        expect(escaped).not.toMatch(/&(?!amp;|lt;|gt;|quot;|#39;)/)
        // The plan's own assertion, kept verbatim.
        expect(escape('<img src=x onerror=alert(1)>')).not.toContain('<img')
    })

    it('does not double-encode its own output', () => {
        // The classic bug in a chain of replaces: escape < before &, and a
        // plain < becomes &lt; and then &amp;lt;, which renders as the literal
        // text "&lt;" on the page. One pass, one replacement per character.
        expect(escape('<')).toBe('&lt;')
        expect(escape('&lt;')).toBe('&amp;lt;')
        expect(escape('a & b')).toBe('a &amp; b')
    })

    it('leaves a javascript: URL exactly as it found it, which is the caveat', () => {
        // Measured rather than assumed, and asserted so that nobody reads
        // "escaped" as "safe to put in an href". None of the characters in a
        // javascript: URL are in the table, so this function is a no-op on one.
        // As TEXT that is correct and harmless -- it renders as the literal
        // string. As an ATTRIBUTE it is a live payload, and the defence against
        // that is a scheme check at the point of use, which escaping is not.
        expect(escape('javascript:alert(1)')).toBe('javascript:alert(1)')
    })

    it('converts whatever it is given, because a renderer is handed unknowns', () => {
        // Values reach a screen from JSON.parse and are therefore unknown: a
        // number, a null, an object. Typing the parameter as string would move
        // this conversion to every call site, and the call site that forgets is
        // the one that renders an attacker's toString.
        expect(escape(null)).toBe('null')
        expect(escape(7)).toBe('7')
        expect(escape({ toString: () => '<b>' })).toBe('&lt;b&gt;')
    })
})

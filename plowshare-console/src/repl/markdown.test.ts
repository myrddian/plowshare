import { describe, expect, it } from 'vitest'
import { markdown, parse, spansOf, toDom } from './markdown'

/** The fragment, mounted, so every assertion is on a real tree and not on a string. */
function render(text: string): HTMLElement {
    const root = document.createElement('div')
    root.append(markdown(text))
    return root
}

/** The tag names of the fragment's top-level blocks, in order. */
function blocks(text: string): string[] {
    return [...render(text).children].map((node) => node.tagName.toLowerCase())
}

describe('paragraphs', () => {
    it('renders prose as a paragraph rather than as preformatted text', () => {
        expect(blocks('a sentence about a document')).toEqual(['p'])
        expect(render('a sentence about a document').textContent)
            .toBe('a sentence about a document')
    })

    it('starts a new paragraph at a blank line', () => {
        expect(blocks('first\n\nsecond')).toEqual(['p', 'p'])
    })

    it('keeps a soft line break inside the paragraph it was written in', () => {
        // The pre this replaces showed every newline, and an answer whose lines
        // were reflowed into one river would read as a different answer. The
        // break is kept in the text; the stylesheet's pre-wrap draws it.
        const root = render('one line\nthe next line')
        expect(blocks('one line\nthe next line')).toEqual(['p'])
        expect(root.querySelector('p')?.textContent).toBe('one line\nthe next line')
    })

    it('renders nothing at all for nothing at all', () => {
        expect(blocks('')).toEqual([])
        expect(render('').textContent).toBe('')
    })
})

describe('inline marks', () => {
    it('renders a double-asterisk run as strong', () => {
        const root = render('the **whole point** of it')
        expect(root.querySelector('strong')?.textContent).toBe('whole point')
        expect(root.textContent).toBe('the whole point of it')
    })

    it('renders a single-asterisk run as emphasis', () => {
        const root = render('read it *before* answering')
        expect(root.querySelector('em')?.textContent).toBe('before')
        expect(root.querySelector('strong')).toBeNull()
    })

    it('renders a backtick run as code, and leaves the markers inside it alone', () => {
        // Code first in the pass order, so a name with an asterisk or an
        // underscore in it survives being quoted.
        const root = render('call `document_ask` with **that**')
        expect(root.querySelector('code')?.textContent).toBe('document_ask')
        expect(root.querySelector('strong')?.textContent).toBe('that')
    })

    it('does not read emphasis inside a code span', () => {
        const root = render('`a *b* c`')
        expect(root.querySelector('em')).toBeNull()
        expect(root.querySelector('code')?.textContent).toBe('a *b* c')
    })

    it('leaves an asterisk that marks nothing exactly as it was typed', () => {
        // Arithmetic, a footnote marker, a bullet quoted mid-sentence. A
        // renderer that guessed here would eat characters out of somebody's
        // answer, which is worse than showing the mark.
        expect(render('5 * 3 * 2 is 30').textContent).toBe('5 * 3 * 2 is 30')
        expect(render('5 * 3 * 2 is 30').querySelector('em')).toBeNull()
    })

    it('leaves snake_case names alone, because this corpus is full of them', () => {
        // THE REASON UNDERSCORE EMPHASIS IS NOT SUPPORTED AT ALL. The shipped
        // agents' prose names its tools constantly -- file_read, memory_recall,
        // document_ask -- and a pair rule over underscores turns the run
        // between two of them into emphasis, silently deleting the underscores
        // from a tool name a person is meant to be able to retype.
        const said = 'file_read and file_glob send you to file_roots'
        expect(render(said).textContent).toBe(said)
        expect(render(said).querySelector('em')).toBeNull()
        expect(render('__not bold__').textContent).toBe('__not bold__')
    })

    it('does not open emphasis on a mark with a space against it', () => {
        expect(render('a ** b ** c').querySelector('strong')).toBeNull()
        expect(render('a ** b ** c').textContent).toBe('a ** b ** c')
    })

    it('leaves an unclosed mark as text', () => {
        expect(render('an **unfinished thought').textContent).toBe('an **unfinished thought')
        expect(render('an `unfinished quote').textContent).toBe('an `unfinished quote')
    })
})

describe('headings', () => {
    it('clamps a heading below the two levels the page itself owns', () => {
        // An answer's `#` is not the page's title. The shell owns h1 and the
        // screens own h2, so a transcript that emitted an h1 would put a model's
        // wording above the console's own in every outline and screen reader.
        expect(blocks('# one')).toEqual(['h3'])
        expect(blocks('## two')).toEqual(['h4'])
        expect(blocks('### three')).toEqual(['h5'])
        expect(blocks('#### four')).toEqual(['h6'])
        expect(blocks('##### five')).toEqual(['h6'])
        expect(blocks('###### six')).toEqual(['h6'])
    })

    it('reads the marks in a heading like any other prose', () => {
        expect(render('## what `document_ask` answers').querySelector('code')?.textContent)
            .toBe('document_ask')
    })

    it('needs a space, so a hash against a word is not a heading', () => {
        expect(blocks('#1 on the list')).toEqual(['p'])
        expect(render('#1 on the list').textContent).toBe('#1 on the list')
    })
})

describe('lists', () => {
    it('renders a bullet list as a list, whichever bullet was typed', () => {
        for (const bullet of ['-', '*', '+']) {
            const root = render(`${bullet} first\n${bullet} second`)
            expect([...root.children].map((node) => node.tagName.toLowerCase())).toEqual(['ul'])
            expect([...root.querySelectorAll('li')].map((node) => node.textContent))
                .toEqual(['first', 'second'])
        }
    })

    it('renders a numbered list as one, and starts it where it was started', () => {
        // The agents write ordered instructions constantly -- close_reader's
        // body is four numbered steps -- and a list renumbered from 1 would be
        // this console rewriting which step a person was told to take.
        const root = render('3. third\n4. fourth')
        expect(root.querySelector('ol')?.getAttribute('start')).toBe('3')
        expect([...root.querySelectorAll('li')].map((node) => node.textContent))
            .toEqual(['third', 'fourth'])
    })

    it('reads the marks inside an item', () => {
        expect(render('- ask **once**').querySelector('li strong')?.textContent).toBe('once')
    })

    it('nests a list written under an item, rather than showing its bullet', () => {
        const root = render('- outer\n  - inner')
        expect(root.querySelector('ul > li > ul > li')?.textContent).toBe('inner')
        expect(root.textContent).not.toContain('- inner')
    })

    it('keeps a line wrapped under an item as part of that item', () => {
        const root = render('1. a step that runs\n   onto a second line')
        expect(root.querySelectorAll('li').length).toBe(1)
        expect(root.querySelector('li')?.textContent).toContain('onto a second line')
    })

    it('ends the list at the prose that follows it', () => {
        expect(blocks('- one\n- two\n\nand then a sentence')).toEqual(['ul', 'p'])
    })

    it('does not read a lone hyphen mid-sentence as a bullet', () => {
        expect(blocks('a sentence - with a dash in it')).toEqual(['p'])
    })

    it('reads a one-line item as prose, so a hash in it is a hash', () => {
        // Not CommonMark, and deliberately unchanged: this grammar has always
        // rendered `- # x` with the marker showing, and a refactor is not where
        // that gets decided.
        const root = render('- # not a heading\n- second')
        expect(root.querySelector('li h3')).toBeNull()
        expect(root.querySelector('li')?.textContent).toBe('# not a heading')
    })

    it('reads a one-line item as prose, so an angle bracket in it is text', () => {
        const root = render('- > quoted\n- second')
        expect(root.querySelector('li blockquote')).toBeNull()
        expect(root.querySelector('li')?.textContent).toBe('> quoted')
    })

    it('keeps a wrapped item loose, the way the source wrote it', () => {
        // The distinction is the source's, not the parse shape's: this item and
        // a one-line one both come out as a single paragraph, and only one of
        // them is meant to carry a paragraph's margins.
        const root = render('- a step that runs\n  onto a second line')
        expect(root.querySelector('li > p')).not.toBeNull()
        expect(root.querySelector('li')?.textContent)
            .toBe('a step that runs\nonto a second line')
    })

    it('keeps a one-line item tight', () => {
        const root = render('- one line')
        expect(root.querySelector('li > p')).toBeNull()
        expect(root.querySelector('li')?.textContent).toBe('one line')
    })
})

describe('quotes and rules', () => {
    it('renders a quoted block as a quote, with its own blocks inside', () => {
        const root = render('> what the paragraph said\n> across two lines')
        expect(root.querySelector('blockquote p')?.textContent)
            .toBe('what the paragraph said\nacross two lines')
    })

    it('renders a horizontal rule', () => {
        expect(blocks('above\n\n---\n\nbelow')).toEqual(['p', 'hr', 'p'])
    })
})

describe('fenced code', () => {
    it('renders a fence verbatim, marks and all', () => {
        const root = render('```\nif (a * b) { return `x` }\n```')
        expect(blocks('```\nif (a * b) { return `x` }\n```')).toEqual(['pre'])
        expect(root.querySelector('pre')?.textContent).toBe('if (a * b) { return `x` }')
        expect(root.querySelector('em')).toBeNull()
        expect(root.querySelector('code')).toBeNull()
    })

    it('takes an info string without putting it on the screen', () => {
        const root = render('```kotlin\nval a = 1\n```')
        expect(root.querySelector('pre')?.textContent).toBe('val a = 1')
        expect(root.textContent).not.toContain('kotlin')
    })

    it('leaves a fence nobody closed as the text it is', () => {
        // A truncated answer -- a TURN_CAP mid-block -- ends inside a fence, and
        // swallowing the rest of the answer into a code block would hide the
        // part a person most needs to see.
        const root = render('```\nstarted and cut off')
        expect(root.querySelector('pre')).toBeNull()
        expect(root.textContent).toContain('started and cut off')
    })
})

describe('links', () => {
    it('renders a link as its label and its address, and never as an anchor', () => {
        // No href on this screen comes from model output. escape.ts records
        // that a javascript: URL survives escaping intact, so the defence is
        // not to sanitise the scheme but to have no anchor to put one in.
        const root = render('see [the paper](https://example.org/p.pdf) for it')
        expect(root.querySelector('a')).toBeNull()
        expect(root.textContent).toBe('see the paper (https://example.org/p.pdf) for it')
    })

    it('renders a javascript: link as text like any other', () => {
        const root = render('[click me](javascript:alert(1))')
        expect(root.querySelector('a')).toBeNull()
        expect(root.textContent).toContain('javascript:alert(1)')
    })

    it('shows a label whose address is empty as just the label', () => {
        expect(render('[a label]()').textContent).toBe('a label')
    })
})

describe('rendering is escaping', () => {
    it('lands markup in the source in the DOM as text and not as an element', () => {
        const payload = '<img src=x onerror=alert(1)><script>alert(2)</script>'
        const root = render(`the file said ${payload}`)

        expect(root.querySelector('img')).toBeNull()
        expect(root.querySelector('script')).toBeNull()
        expect(root.textContent).toContain(payload)
        // And not double-encoded on the way in: the person reads what the file
        // said, entities and all.
        expect(root.textContent).not.toContain('&lt;')
    })

    it('lands markup inside a fence as text too', () => {
        const root = render('```\n<script>alert(1)</script>\n```')
        expect(root.querySelector('script')).toBeNull()
        expect(root.querySelector('pre')?.textContent).toBe('<script>alert(1)</script>')
    })
})

describe('spans as data', () => {
    it('records a code span rather than building an element', () => {
        expect(spansOf('call `document_ask` now')).toEqual([
            { kind: 'text', text: 'call ' },
            { kind: 'code', text: 'document_ask' },
            { kind: 'text', text: ' now' },
        ])
    })

    it('nests the marks inside a strong run', () => {
        expect(spansOf('**do `this`**')).toEqual([
            { kind: 'strong', spans: [
                { kind: 'text', text: 'do ' },
                { kind: 'code', text: 'this' },
            ] },
        ])
    })

    it('records a link with its address, leaving the flattening to an emitter', () => {
        // The parse keeps the address as an address. An emitter that renders it
        // as text is one choice of several, and a parse that had already
        // flattened would have thrown away the only structured copy.
        expect(spansOf('see [the paper](https://example.org/p.pdf)')).toEqual([
            { kind: 'text', text: 'see ' },
            {
                kind: 'link',
                label: [{ kind: 'text', text: 'the paper' }],
                address: 'https://example.org/p.pdf',
            },
        ])
    })

    it('records text that carries no marks as one span', () => {
        expect(spansOf('file_read and file_glob')).toEqual([
            { kind: 'text', text: 'file_read and file_glob' },
        ])
    })
})

describe('blocks as data', () => {
    it('records a heading at the level written, leaving the clamp to an emitter', () => {
        // The page owns h1 and h2, so a browser emitter clamps. That is a fact
        // about this page's outline; a terminal has no such constraint, and a
        // parse that clamped would have decided for both.
        expect(parse('# one')).toEqual([
            { kind: 'heading', level: 1, spans: [{ kind: 'text', text: 'one' }] },
        ])
        expect(parse('###### six')).toEqual([
            { kind: 'heading', level: 6, spans: [{ kind: 'text', text: 'six' }] },
        ])
    })

    it('records a paragraph with its soft line break intact', () => {
        expect(parse('one line\nthe next')).toEqual([
            { kind: 'para', spans: [{ kind: 'text', text: 'one line\nthe next' }] },
        ])
    })

    it('records a fence verbatim, with no spans read inside it', () => {
        expect(parse('```kotlin\nval a = 1 * 2\n```')).toEqual([
            { kind: 'code', text: 'val a = 1 * 2' },
        ])
    })

    it('records every list item as blocks, even a one-line one', () => {
        expect(parse('- first\n- second')).toEqual([{
            kind: 'list',
            ordered: false,
            items: [
                {
                    tight: true,
                    blocks: [{ kind: 'para', spans: [{ kind: 'text', text: 'first' }] }],
                },
                {
                    tight: true,
                    blocks: [{ kind: 'para', spans: [{ kind: 'text', text: 'second' }] }],
                },
            ],
        }])
    })

    it('keeps the number a numbered list started at', () => {
        const blocks = parse('3. third')
        expect(blocks).toEqual([{
            kind: 'list',
            ordered: true,
            start: 3,
            items: [{
                tight: true,
                blocks: [{ kind: 'para', spans: [{ kind: 'text', text: 'third' }] }],
            }],
        }])
    })

    it('records a quote as blocks inside a quote', () => {
        expect(parse('> what it said')).toEqual([{
            kind: 'quote',
            blocks: [{ kind: 'para', spans: [{ kind: 'text', text: 'what it said' }] }],
        }])
    })

    it('records a rule', () => {
        expect(parse('---')).toEqual([{ kind: 'rule' }])
    })

    it('records nothing for nothing', () => {
        expect(parse('')).toEqual([])
    })
})

describe('toDom', () => {
    it('emits from blocks alone, so a grammar consumer can hold the data', () => {
        const root = document.createElement('div')
        root.append(toDom(parse('## a\n\n- one\n- two\n\n```\ncode\n```\n\n> quoted')))

        expect([...root.children].map((node) => node.tagName.toLowerCase()))
            .toEqual(['h4', 'ul', 'pre', 'blockquote'])
        expect(root.querySelectorAll('li').length).toBe(2)
        expect(root.querySelector('pre')?.textContent).toBe('code')
        expect(root.querySelector('blockquote p')?.textContent).toBe('quoted')
    })
})

describe('code is what somebody pastes', () => {
    // The same group as `plowshare-tui/src/logic/markdown.test.ts`, because the
    // same defect exists in both copies of this grammar. See `pasteable`.
    it('turns a narrow no-break space inside inline code into a space', () => {
        const [block] = parse('Try `ORDER BY col\u202fDESC;` next')
        const code = block?.kind === 'para'
            ? block.spans.find((span) => span.kind === 'code') : undefined
        expect(code?.kind === 'code' ? code.text : '').toBe('ORDER BY col DESC;')
    })

    it('leaves the very same character alone in prose', () => {
        // Both text branches: `spansOf` pushes plain text before an inline
        // match and after the last one, and a line with no inline markup only
        // ever reaches the second.
        const [block] = parse('if\u202fDESC\u202fis `code` then\u202fASC\u202fis not')
        const prose = block?.kind === 'para'
            ? block.spans.filter((span) => span.kind === 'text') : []
        expect(prose).toHaveLength(2)
        for (const span of prose) {
            expect(span.kind === 'text' ? span.text : '').toContain('\u202f')
        }
    })

    it('cleans a fenced block, which is the one people actually paste', () => {
        const [block] = parse('```sql\nSELECT *\nORDER BY col\u202fDESC;\n```')
        expect(block?.kind === 'code' ? block.text : '')
            .toBe('SELECT *\nORDER BY col DESC;')
    })

    it('turns a non-breaking hyphen inside code into a plain one', () => {
        const [block] = parse('```\nls \u2011\u2011color\n```')
        expect(block?.kind === 'code' ? block.text : '').toBe('ls --color')
    })

    it('leaves ordinary code entirely alone', () => {
        const [block] = parse('```\nSELECT * FROM t ORDER BY col DESC;\n```')
        expect(block?.kind === 'code' ? block.text : '')
            .toBe('SELECT * FROM t ORDER BY col DESC;')
    })

    it('does not touch a curly quote, which is visible and may be meant', () => {
        const [block] = parse('`echo \u2019hi\u2019`')
        const code = block?.kind === 'para'
            ? block.spans.find((span) => span.kind === 'code') : undefined
        expect(code?.kind === 'code' ? code.text : '').toBe('echo \u2019hi\u2019')
    })
})

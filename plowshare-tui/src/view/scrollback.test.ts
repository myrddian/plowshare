import { stripVTControlCharacters as bare } from 'node:util'

import { describe, expect, it } from 'vitest'

import { parse } from '../logic/markdown.ts'
import { DEFAULT_LOOK } from './look.ts'
import type { Colour } from './theme/tokens.ts'
import { toListing, toTerminal } from './scrollback.ts'

/**
 * The second emitter, asserted the way the first one was.
 *
 * <b>This is the file that makes the parse/emit split worth having made.</b>
 * One grammar in `logic/markdown.ts`, two emitters: `toDom` in the console and
 * {@link toTerminal} here. The grammar's own suite travelled with it in task 3
 * and asserts over `Block[]`; this suite asserts over what a terminal is handed,
 * which is a string, and it is the only place in this repository where that
 * string is checked at all.
 *
 * <h2>Escape sequences are written out, not computed</h2>
 *
 * <p>The constants below are typed literally rather than produced by calling
 * `styleText`, because a test that built its expectation with the same function
 * the code under test uses would assert that the function equals itself. `1m`
 * is bold, `3m` italic, `2m` dim, `36m` cyan, and each has the closer the
 * terminal actually needs — <b>bold and dim share `22m`</b>, which is why
 * nothing here nests one inside the other and why that is worth knowing rather
 * than discovering.
 *
 * <h2>Three cases this file exists to hold, named because they are the ones
 * that would otherwise be asserted nowhere</h2>
 *
 * <ol>
 * <li><b>Fenced code survives verbatim.</b> The plan's words: an emitter that
 *     styles the inside of a code block is the same bug as an HTML one that
 *     escapes twice. So the assertions are byte-for-byte and one of them is
 *     that the rendered fence contains no `` at all, <i>with colour on</i>
 *     — which is the only form of the claim that a styling emitter can fail.
 * <li><b>Headings keep the level that was written.</b> `markdown.test.ts`
 *     records that the `h3` clamp lives in the console's `toDom` and nowhere
 *     else, and that the console's copy is destined for deletion; after that
 *     deletion nothing anywhere asserts what an emitter does with a level
 *     unless this file does. A terminal has no outline of its own to protect,
 *     so it clamps nothing, and all six levels arrive distinguishable.
 * <li><b>No underscore emphasis.</b> A grammar decision, asserted here at the
 *     emitter because that is where it would be <i>visible</i>: the whole point
 *     of refusing `_` is that `plowshare_memory_write` reaches a person as the
 *     name they can retype, and "reaches a person" is a claim about the string
 *     this file produces.
 * </ol>
 */

/** SGR 1 and its closer. Bold. */
const BOLD = ['[1m', '[22m']
/** SGR 3 and its closer. Italic. */
const ITALIC = ['[3m', '[23m']
/** SGR 2. Dim, whose closer is bold's — see the header. */
const DIM = ['[2m', '[22m']
/** 24-bit foreground `#ffc777`, which is what an inline code run is coloured. */
const CODE = ['[38;2;255;199;119m', '[39m']

/** `text`, wrapped in one of the pairs above. */
function styled(pair: string[], text: string): string {
    return `${pair[0] ?? ''}${text}${pair[1] ?? ''}`
}

/** The emitter, over source rather than over hand-built blocks. */
function shown(text: string, colour = false): string {
    return toTerminal(parse(text), colour)
}

describe('prose, as lines', () => {
    it('renders a paragraph as its own text', () => {
        expect(shown('hello there')).toBe('hello there')
    })

    it('keeps a wrapped paragraph wrapped where its author wrapped it', () => {
        expect(shown('one\ntwo')).toBe('one\ntwo')
    })

    it('puts a blank line between blocks, which is the whole of the layout', () => {
        expect(shown('one\n\ntwo')).toBe('one\n\ntwo')
    })

    it('renders nothing for nothing, rather than a stray newline', () => {
        expect(toTerminal([])).toBe('')
    })
})

describe('headings keep the level that was written', () => {
    it('shows the level as the run of marks it was written as', () => {
        expect(shown('# One')).toBe('# One')
        expect(shown('### Three')).toBe('### Three')
    })

    it('clamps nothing, so all six levels stay distinguishable', () => {
        // The console's `toDom` renders `Math.min(6, level + 2)`, so its h4,
        // h5 and h6 are one tag. That clamp is a fact about a page that owns
        // its own h1 and h2; a scrollback owns no outline and has nothing to
        // protect, so six written levels arrive as six different lines.
        const rendered = [1, 2, 3, 4, 5, 6].map((level) => shown(`${'#'.repeat(level)} T`))
        expect(new Set(rendered).size).toBe(6)
        expect(rendered[5]).toBe('###### T')
    })

    it('draws a level as colour and weight in place of its marks when there is colour', () => {
        const rendered = shown('## Two', true)
        expect(bare(rendered)).toBe('▍ Two')
        expect(rendered).toContain(BOLD[0])
        expect(rendered).not.toContain(DIM[0])
    })

    it('keeps six levels distinguishable with colour too', () => {
        const rendered = [1, 2, 3, 4, 5, 6].map((level) => shown(`${'#'.repeat(level)} T`, true))
        expect(new Set(rendered).size).toBe(6)
    })

    it('underlines a first-level heading as wide as its text', () => {
        expect(bare(shown('# Title', true))).toBe('Title\n━━━━━')
    })

    it('renders the spans in a heading rather than its source', () => {
        expect(shown('# a **b**')).toBe('# a b')
    })
})

describe('fenced code survives verbatim', () => {
    it('hands back exactly what was fenced, marks and all', () => {
        expect(shown('```\n**a** `b` _c_\n```')).toBe('**a** `b` _c_')
    })

    it('adds colour but not one character inside a fence', () => {
        // The bug this case exists for: an emitter that ran the inline grammar
        // over a code block, or put a gutter in front of its lines, changes
        // what somebody pastes. Colour is allowed — a selection does not copy
        // it — so the escapes are stripped and the lines compared byte for
        // byte. Asserted with colour ON, because with colour off every emitter
        // passes.
        const rendered = shown('```js\nconst a = **not bold**\n  b()\n```', true)
        const lines = bare(rendered).split('\n')
        expect(lines.slice(1, -1)).toEqual(['const a = **not bold**', '  b()'])
        expect(rendered).not.toContain(BOLD[0])
    })

    it('frames a fence above and below, naming its language', () => {
        const lines = bare(shown('```ts\nx\n```', true)).split('\n')
        expect(lines[0]).toMatch(/^── ts ─+$/u)
        expect(lines.at(-1)).toMatch(/^─+$/u)
    })

    it('colours keywords, strings and comments through highlight.js', () => {
        const rendered = shown('```ts\nconst a = "s" // c\n```', true)
        const fg = (colour: Colour): string => {
            const hex = colour.kind === 'rgb' ? colour.hex : '#000000'
            const value = Number.parseInt(hex.slice(1), 16)
            return `[38;2;${value >> 16};${(value >> 8) & 255};${value & 255}m`
        }
        expect(rendered).toContain(`${fg(DEFAULT_LOOK.palette.syntaxKeyword)}const`)
        expect(rendered).toContain(`${fg(DEFAULT_LOOK.palette.syntaxString)}"s"`)
        expect(rendered).toContain(`${fg(DEFAULT_LOOK.palette.syntaxComment)}// c`)
    })

    it('leaves a fence with no language uncoloured, since it may be output', () => {
        const lines = shown('```\nconst a = 1\n```', true).split('\n')
        expect(lines[1]).toBe('const a = 1')
    })

    it('keeps every line of a fence, blank ones included', () => {
        expect(shown('```\none\n\nthree\n```')).toBe('one\n\nthree')
    })

    it('keeps the indentation inside a fence, which is what code is', () => {
        expect(shown('```\nif (a) {\n    b()\n}\n```')).toBe('if (a) {\n    b()\n}')
    })

    it('drops nothing when a fence is the whole answer', () => {
        expect(shown('```\n\n```')).toBe('')
    })
})

describe('inline marks', () => {
    it('renders strong as bold and em as italic', () => {
        expect(shown('a **b** c', true)).toBe(`a ${styled(BOLD, 'b')} c`)
        expect(shown('a *b* c', true)).toBe(`a ${styled(ITALIC, 'b')} c`)
    })

    it('keeps an inline code run inside its backticks', () => {
        // The marks are kept rather than replaced by colour, because a
        // scrollback gets piped and a NO_COLOR terminal gets used: the
        // backticks are what still say "this is a name" when nothing else does.
        expect(shown('call `plowshare_memory_write` now'))
            .toBe('call `plowshare_memory_write` now')
        // With colour the colour says it, and the backticks — which a person
        // copying the name would have to take back out — are dropped.
        expect(shown('call `x` now', true)).toBe(`call ${styled(CODE, 'x')} now`)
    })

    it('nests, because the grammar does', () => {
        expect(shown('**a *b* c**', true))
            .toBe(styled(BOLD, `a ${styled(ITALIC, 'b')} c`))
    })

    it('adds no escape sequence at all when colour is off', () => {
        expect(shown('**a** *b* `c` [d](http://e)')).not.toContain('')
    })
})

describe('leaves snake_case names alone', () => {
    it('renders an underscore as an underscore', () => {
        // The grammar decision, asserted where it is visible. Any pair rule
        // over `_` eats the underscores out of a name the reader is meant to
        // be able to retype, and the corpus is full of them.
        expect(shown('file_read and file_glob send you to file_roots', true))
            .toBe('file_read and file_glob send you to file_roots')
    })

    it('renders a doubled underscore as two underscores', () => {
        expect(shown('__notbold__', true)).toBe('__notbold__')
    })
})

describe('lists', () => {
    it('marks an unordered item with a dash', () => {
        expect(shown('- one\n- two')).toBe('- one\n- two')
    })

    it('numbers an ordered list from where it was written to start', () => {
        expect(shown('3. three\n4. four')).toBe('3. three\n4. four')
    })

    it('indents a nested list under its parent', () => {
        expect(shown('- one\n    - deeper')).toBe('- one\n  - deeper')
    })

    it('gives a loose list the air its author gave it', () => {
        // `tight` is a fact about the source — an item written across more
        // than one line — and NOT about the blank lines between items, which
        // the grammar does not record. So `- one\n\n- two` is two tight items
        // and packs, and this is the spelling that actually makes a list
        // loose. An emitter that inferred looseness from the block shape
        // could not tell these apart, which is what `Item.tight` exists for.
        expect(shown('- one\n  two\n- three')).toBe('- one\n  two\n\n- three')
        expect(shown('- one\n\n- two')).toBe('- one\n- two')
    })

    it('lines a wrapped item up under its own text and not under its mark', () => {
        expect(shown('- one\n  two')).toBe('- one\n  two')
    })
})

describe('quotes and rules', () => {
    it('gutters a quote, every line of it', () => {
        expect(shown('> one\n> two')).toBe('> one\n> two')
    })

    it('gutters a quote around a block that has blocks in it', () => {
        expect(shown('> one\n>\n> two')).toBe('> one\n>\n> two')
    })

    it('draws a rule as a rule rather than as the characters that spelt it', () => {
        const rule = shown('---')
        expect(rule).not.toBe('---')
        expect(rule.length).toBeGreaterThan(3)
        expect([...new Set(rule)]).toEqual(['-'])
    })
})

describe('tables are drawn, in either rendering', () => {
    const source = '| a | bb |\n|:--|--:|\n| 1 | 2 |'

    it('draws the header, a rule under it and the rows in a frame', () => {
        expect(shown(source)).toBe([
            '╭───┬────╮',
            '│ a │ bb │',
            '├───┼────┤',
            '│ 1 │  2 │',
            '╰───┴────╯',
        ].join('\n'))
    })

    it('fits the width it is given by wrapping inside cells', () => {
        const wide = `| x | y |\n|---|---|\n| ${'word '.repeat(20)}| short |`
        const lines = toTerminal(parse(wide), false, 40).split('\n')
        expect(Math.max(...lines.map((line) => [...line].length))).toBeLessThanOrEqual(40)
        expect(lines.length).toBeGreaterThan(5)
    })

    it('breaks a word that fits nowhere rather than overflowing', () => {
        const long = `| x |\n|---|\n| ${'a'.repeat(60)} |`
        const lines = toTerminal(parse(long), false, 30).split('\n')
        expect(Math.max(...lines.map((line) => [...line].length))).toBeLessThanOrEqual(30)
    })

    it('renders the marks inside a cell', () => {
        expect(bare(shown('| x |\n|---|\n| **b** |', true))).toContain('│ b │')
    })
})

describe('with colour, the structure is drawn rather than spelt', () => {
    it('marks list items with bullets, nested ones differently', () => {
        expect(bare(shown('- one\n    - two', true))).toBe('• one\n  ◦ two')
    })

    it('gutters a quote with a bar', () => {
        expect(bare(shown('> one', true))).toBe('▎ one')
    })

    it('draws a rule the width it is given', () => {
        expect(bare(toTerminal(parse('---'), true, 30))).toBe('─'.repeat(30))
    })

    it('never uses dim, which vanishes on common dark themes', () => {
        const everything = '# a\n\n> b\n\n- c\n\n---\n\n[d](e) `f`\n\n| g |\n|---|\n| h |'
        expect(shown(everything, true)).not.toContain(DIM[0])
    })

    it('restores a heading\'s colour after inline code inside it', () => {
        const rendered = shown('### a `b` c', true)
        const [, after] = rendered.split('b')
        expect(after?.startsWith('[38;2;')).toBe(true)
    })
})

describe('links keep their address, because a person decides where to go', () => {
    it('shows the label and then the address', () => {
        expect(shown('see [the paper](https://example.org/p.pdf)'))
            .toBe('see the paper (https://example.org/p.pdf)')
    })

    it('shows a bare label when there was no address', () => {
        expect(shown('see [nothing]()')).toBe('see nothing')
    })
})

describe('markup in the source is text, here as much as in a browser', () => {
    it('renders a tag as the characters it is', () => {
        expect(shown('<img src=x onerror=y>')).toBe('<img src=x onerror=y>')
    })

    it('renders an entity as the characters it is', () => {
        expect(shown('&lt;a&gt;')).toBe('&lt;a&gt;')
    })
})

describe('a whole answer, which is what a turn hands the scrollback', () => {
    it('renders the shape an agent actually writes', () => {
        const answer = [
            '## What I did',
            '',
            'I read `pom.xml` and found **three** modules.',
            '',
            '1. parse',
            '2. emit',
            '',
            '```',
            'mvn -q test',
            '```',
        ].join('\n')
        expect(shown(answer)).toBe([
            '## What I did',
            '',
            'I read `pom.xml` and found three modules.',
            '',
            '1. parse',
            '2. emit',
            '',
            'mvn -q test',
        ].join('\n'))
    })
})

describe('a listing, which is one block and not five loose lines', () => {
    it('sets every row in from the margin, the empty-listing sentence included', () => {
        expect(toListing(['plowshare', 'notes'])).toBe('  plowshare\n  notes')
        expect(toListing(['no projects have been defined on this server']))
            .toBe('  no projects have been defined on this server')
    })

    it('adds no marker, so a listing does not read as something an agent wrote', () => {
        // `- ` in front of each row is what `toTerminal` draws for a markdown
        // list. Borrowing it here would make an answer to `/projects` look like
        // a list the agent produced.
        const shown = toListing(['(not yet named) — cnv_3134E666E2D847AD'])

        expect(shown).not.toContain('-  ')
        expect(shown.trimStart()).toBe('(not yet named) — cnv_3134E666E2D847AD')
    })

    it('writes no escape sequence, whatever the terminal is', () => {
        // No colour argument at all, unlike `toTerminal`: the only part of a
        // row worth playing down is the id, and dimming it would mean taking
        // the sentence `wording.ts` wrote back apart. It is played down by
        // being second on the line, which survives a pipe and NO_COLOR alike.
        expect(toListing(['plowshare', 'notes'])).not.toContain('')
    })

    it('ends without a newline, because Prompt.say owns that', () => {
        expect(toListing(['plowshare'])).toBe('  plowshare')
        expect(toListing([])).toBe('')
    })
})

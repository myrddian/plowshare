import type { Align, Block, Cell, Item, Span } from '../logic/markdown.ts'
import { highlight } from './highlight.ts'
import { DEFAULT_LOOK, encode, tinted, widthOf } from './look.ts'
import type { Look, Run, Style } from './look.ts'
import type { Palette } from './theme/tokens.ts'

/**
 * The terminal emitter: `toDom`'s sibling, and the point of the parse/emit
 * split.
 *
 * <h2>Two emitters over one grammar</h2>
 *
 * <p>`logic/markdown.ts` knows the grammar and produces plain data. The console
 * turns that data into nodes; this turns it into a string with escape sequences
 * in it. Neither emitter parses anything, so a grammar bug is fixed once and a
 * rendering decision is taken where it belongs.
 *
 * <h2>Two renderings, and which one a reader gets is the caller's decision</h2>
 *
 * <p><b>With a {@link Look}</b> — a terminal a person is watching — markdown is
 * rendered rather than transcribed: headings in colour without their `#`
 * marks, bullets as bullets, inline code in its own colour without its
 * backticks, fences framed and highlighted, tables drawn as tables. This used
 * to be a much plainer emitter that kept every mark on the grounds that marks
 * survive a pipe; they do, and that is what the second rendering is for.
 *
 * <p><b>Without one</b> — a pipe, a file, `NO_COLOR` — every mark that carries
 * meaning is kept (`##`, backticks, `- `, `> `) and not one escape sequence is
 * written, so the scrollback greps and reads the same as the source did.
 * Tables are drawn in both, because a table's pipes were never legible as
 * source.
 *
 * <h2>A fence's characters are never touched</h2>
 *
 * <p>Colour may be added inside a fence; <b>characters may not</b>. No gutter,
 * no indent, no line numbers: a fence is what somebody selects and pastes into
 * a shell, a terminal's selection copies characters and not SGR, and every
 * character this file put in front of a line would be one they had to take
 * back out. The frame is a line above and a line below, which a selection of
 * the code does not include. `scrollback.test.ts` holds it by stripping the
 * escapes and comparing what is left byte for byte.
 *
 * <h2>Width is a parameter because tables need one</h2>
 *
 * <p>Prose is left for the surface to wrap. A table cannot be — a wrapped
 * border is a broken table — so it is fitted to the width the caller names,
 * wrapping inside cells rather than across them.
 */

/** How wide a rule is drawn when there is no look to size it with. */
const RULE_WIDTH = 48

/** What every block is drawn against. */
interface Drawing {
    readonly look: Look | undefined
    readonly width: number
}

type Line = Run[]

/** The palette, when there is one. */
const tone = (drawing: Drawing): Palette | undefined => drawing.look?.palette

/** One run of prose, as runs. */
function runsOf(spans: readonly Span[], drawing: Drawing, base: Style): Run[] {
    const palette = tone(drawing)
    return spans.flatMap((span): Run[] => {
        switch (span.kind) {
            case 'text':
                return [{ text: span.text, style: base }]
            case 'code':
                // The backticks travel with it only when nothing else can say
                // "this is a name" — see the header.
                return palette === undefined
                    ? [{ text: `\`${span.text}\``, style: base }]
                    : [{ text: span.text, style: tinted(base, palette.mdCode) }]
            case 'strong':
                return runsOf(span.spans, drawing, { ...base, bold: true })
            case 'em':
                return runsOf(span.spans, drawing, { ...base, italic: true })
            case 'link': {
                const label = runsOf(span.label, drawing, palette === undefined
                    ? base : { ...tinted(base, palette.mdLink), underline: true })
                return span.address === ''
                    ? label
                    : [...label, { text: ` (${span.address})`, style: tinted(base, palette?.mdLinkUrl) }]
            }
        }
    })
}

/** Runs cut at their newlines. */
function linesOfRuns(runs: readonly Run[]): Line[] {
    const lines: Line[] = [[]]
    for (const run of runs) {
        run.text.split('\n').forEach((piece, at) => {
            if (at > 0) {
                lines.push([])
            }
            ;(lines[lines.length - 1] as Line).push({ text: piece, style: run.style })
        })
    }
    return lines
}

const widthOfLine = (line: readonly Run[]): number =>
    line.reduce((sum, run) => sum + widthOf(run.text), 0)

// ---------------------------------------------------------------- code

/** A fence: verbatim without a look, framed and coloured with one. */
function codeLines(text: string, lang: string | undefined, drawing: Drawing): Line[] {
    const lines = text.split('\n')
    const palette = tone(drawing)
    if (palette === undefined) {
        return lines.map((line) => [{ text: line, style: {} }])
    }
    const name = (lang ?? '').toLowerCase()
    const longest = Math.max(0, ...lines.map(widthOf))
    const frame = Math.max(8, Math.min(drawing.width, Math.max(longest, 40)))
    const border = tinted({}, palette.mdCodeBlockBorder)
    const label = name === '' ? '' : ` ${name} `
    const top: Line = [
        { text: '──', style: border },
        { text: label, style: { ...tinted({}, palette.mdCodeBlockLabel), italic: true } },
        { text: '─'.repeat(Math.max(0, frame - 2 - widthOf(label))), style: border },
    ]
    const coloured = highlight(text, name, palette)
    const body = coloured === undefined
        ? lines.map((line): Line => [{ text: line, style: {} }])
        : linesOfRuns(coloured)
    return [top, ...(text === '' ? [] : body), [{ text: '─'.repeat(frame), style: border }]]
}

// ---------------------------------------------------------------- tables

/** Runs as words: each word a list of runs, spaces between words dropped. */
function wordsOf(runs: readonly Run[]): Run[][] {
    const words: Run[][] = []
    let word: Run[] = []
    for (const run of runs) {
        for (const piece of run.text.split(/(\s+)/u)) {
            if (piece === '') {
                continue
            }
            if (/^\s+$/u.test(piece)) {
                if (word.length > 0) {
                    words.push(word)
                    word = []
                }
                continue
            }
            word.push({ text: piece, style: run.style })
        }
    }
    if (word.length > 0) {
        words.push(word)
    }
    return words
}

/** A word cut into pieces no wider than `width`, for a word that fits nowhere. */
function broken(word: readonly Run[], width: number): Run[][] {
    const pieces: Run[][] = [[]]
    let used = 0
    for (const run of word) {
        for (const char of run.text) {
            const wide = widthOf(char)
            if (used + wide > width && used > 0) {
                pieces.push([])
                used = 0
            }
            ;(pieces[pieces.length - 1] as Run[]).push({ text: char, style: run.style })
            used += wide
        }
    }
    return pieces
}

/** Runs filled into lines of at most `width` columns, breaking between words. */
function wrapped(runs: readonly Run[], width: number): Line[] {
    const lines: Line[] = []
    let line: Line = []
    let used = 0
    const flush = (): void => {
        lines.push(line)
        line = []
        used = 0
    }
    for (const word of wordsOf(runs)) {
        const size = widthOfLine(word)
        const pieces = size > width ? broken(word, width) : [word]
        pieces.forEach((piece, at) => {
            const pieceSize = widthOfLine(piece)
            // The pieces of one broken word follow each other with no space.
            if (at > 0 || (used > 0 && used + 1 + pieceSize > width)) {
                flush()
            }
            if (used > 0) {
                line.push({ text: ' ', style: piece[0]?.style ?? {} })
                used += 1
            }
            line.push(...piece)
            used += pieceSize
        })
    }
    if (line.length > 0 || lines.length === 0) {
        flush()
    }
    return lines
}

/** A line padded to `width`, placed as its column asks. */
function aligned(line: Line, width: number, align: Align): Line {
    const gap = Math.max(0, width - widthOfLine(line))
    const left = align === 'right' ? gap : align === 'center' ? Math.floor(gap / 2) : 0
    return [{ text: ' '.repeat(left), style: {} }, ...line,
        { text: ' '.repeat(gap - left), style: {} }]
}

/** How wide each column is drawn: as wide as it wants, narrowed widest-first to fit. */
function columnWidths(cells: readonly (readonly Run[])[][], available: number): number[] {
    const widths = (cells[0] ?? []).map((_, column) =>
        Math.max(1, ...cells.map((row) => widthOfLine(row[column] ?? []))))
    let total = widths.reduce((sum, width) => sum + width, 0)
    while (total > available) {
        let widest = 0
        widths.forEach((width, column) => {
            if (width > (widths[widest] ?? 0)) {
                widest = column
            }
        })
        if ((widths[widest] ?? 0) <= 6) {
            break
        }
        widths[widest] = (widths[widest] ?? 0) - 1
        total -= 1
    }
    return widths
}

function tableLines(
    align: readonly Align[],
    header: readonly Cell[],
    rows: readonly (readonly Cell[])[],
    drawing: Drawing,
): Line[] {
    const palette = tone(drawing)
    const border = tinted({}, palette?.mdTableBorder)
    const heading = tinted({ bold: true }, palette?.mdTableHeader)
    const cells = [
        header.map((cell) => runsOf(cell, drawing, heading)),
        ...rows.map((row) => row.map((cell) => runsOf(cell, drawing, {}))),
    ]
    const widths = columnWidths(cells, drawing.width - (3 * header.length + 1))
    const laid = cells.map((row) => row.map((cell, column) => wrapped(cell, widths[column] ?? 1)))
    const tall = laid.slice(1).some((row) => row.some((cell) => cell.length > 1))

    const edge = (left: string, cross: string, right: string): Line => [{
        text: left + widths.map((width) => '─'.repeat(width + 2)).join(cross) + right,
        style: border,
    }]
    const rowLines = (row: Line[][]): Line[] => {
        const height = Math.max(1, ...row.map((cell) => cell.length))
        return Array.from({ length: height }, (_, at) => {
            const line: Line = [{ text: '│ ', style: border }]
            row.forEach((cell, column) => {
                if (column > 0) {
                    line.push({ text: ' │ ', style: border })
                }
                line.push(...aligned(cell[at] ?? [], widths[column] ?? 1, align[column] ?? 'left'))
            })
            line.push({ text: ' │', style: border })
            return line
        })
    }

    const lines: Line[] = [edge('╭', '┬', '╮'), ...rowLines(laid[0] ?? []), edge('├', '┼', '┤')]
    laid.slice(1).forEach((row, at) => {
        if (tall && at > 0) {
            lines.push(edge('├', '┼', '┤'))
        }
        lines.push(...rowLines(row))
    })
    lines.push(edge('╰', '┴', '╯'))
    return lines
}

// ---------------------------------------------------------------- blocks

/** `lines`, each with `gutter` in front of it and a bare gutter for a blank. */
function guttered(lines: readonly Line[], gutter: Run): Line[] {
    return lines.map((line) => (widthOfLine(line) === 0
        ? [{ text: gutter.text.trimEnd(), style: gutter.style }]
        : [gutter, ...line]))
}

/** Bullets by depth, so a nested list does not look like its parent. */
const BULLETS = ['•', '◦', '▪'] as const

/**
 * One item: its first line beside the marker, the rest lined up under its text.
 *
 * <p><b>A nested list is not separated from the line it hangs off</b>: inside
 * an item the nested list <i>is</i> the continuation of that item's own line.
 */
function itemLines(entry: Item, marker: Run, drawing: Drawing, depth: number): Line[] {
    const indent = widthOf(marker.text)
    const inner: Line[] = []
    const within = { ...drawing, width: Math.max(10, drawing.width - indent) }
    for (const block of entry.blocks) {
        if (inner.length > 0 && block.kind !== 'list') {
            inner.push([])
        }
        inner.push(...linesOf(block, within, depth + (block.kind === 'list' ? 1 : 0)))
    }
    return inner.map((line, at) => {
        if (at === 0) {
            return [marker, ...line]
        }
        return widthOfLine(line) === 0 ? [] : [{ text: ' '.repeat(indent), style: {} }, ...line]
    })
}

/** One list, marked and numbered, loose if any of its items was written so. */
function listLines(
    items: readonly Item[],
    marker: (at: number) => string,
    drawing: Drawing,
    depth: number,
): Line[] {
    // `tight` is a fact about the SOURCE — see `Item` — so a list nobody wrote
    // across lines stays packed, and one somebody did is given the air they
    // gave it.
    const loose = items.some((entry) => !entry.tight)
    const style = tinted({}, tone(drawing)?.mdBullet)
    const lines: Line[] = []
    items.forEach((entry, at) => {
        if (loose && lines.length > 0) {
            lines.push([])
        }
        lines.push(...itemLines(entry, { text: marker(at), style }, drawing, depth))
    })
    return lines
}

/** A heading: its marks without a look, its level as colour and weight with one. */
function headingLines(level: number, spans: readonly Span[], drawing: Drawing): Line[] {
    const palette = tone(drawing)
    if (palette === undefined) {
        return [[{ text: `${'#'.repeat(level)} `, style: {} }, ...runsOf(spans, drawing, {})]]
    }
    switch (level) {
        case 1: {
            const text = runsOf(spans, drawing, tinted({ bold: true }, palette.mdHeading1))
            const under = Math.min(drawing.width, Math.max(3, widthOfLine(text)))
            return [text, [{ text: '━'.repeat(under), style: tinted({}, palette.mdHeading1) }]]
        }
        case 2:
            return [[{ text: '▍ ', style: tinted({}, palette.mdHeading2) },
                ...runsOf(spans, drawing, tinted({ bold: true }, palette.mdHeading2))]]
        case 3:
            return [runsOf(spans, drawing, tinted({ bold: true }, palette.mdHeading3))]
        case 4:
            return [runsOf(spans, drawing, { bold: true })]
        case 5:
            return [runsOf(spans, drawing, { bold: true, italic: true })]
        default:
            return [runsOf(spans, drawing, tinted({ bold: true }, palette.muted))]
    }
}

/** One block, as the lines it occupies. */
function linesOf(block: Block, drawing: Drawing, depth = 0): Line[] {
    const palette = tone(drawing)
    switch (block.kind) {
        case 'para':
            return linesOfRuns(runsOf(block.spans, drawing, {}))
        case 'heading':
            return headingLines(block.level, block.spans, drawing)
        case 'code':
            return codeLines(block.text, block.lang, drawing)
        case 'table':
            return tableLines(block.align, block.header, block.rows, drawing)
        case 'rule':
            return palette === undefined
                ? [[{ text: '-'.repeat(RULE_WIDTH), style: {} }]]
                : [[{ text: '─'.repeat(drawing.width), style: tinted({}, palette.mdRule) }]]
        case 'quote': {
            const gutter: Run = palette === undefined
                ? { text: '> ', style: {} }
                : { text: '▎ ', style: tinted({}, palette.mdQuote) }
            const within = { ...drawing, width: Math.max(10, drawing.width - 2) }
            return guttered(stacked(block.blocks, within), gutter)
        }
        case 'list': {
            const bullet = palette === undefined
                ? '- ' : `${BULLETS[depth % BULLETS.length] ?? '•'} `
            return block.ordered
                ? listLines(block.items, (at) => `${block.start + at}. `, drawing, depth)
                : listLines(block.items, () => bullet, drawing, depth)
        }
    }
}

/** Every block, with one blank line between each pair and none at the ends. */
function stacked(blocks: readonly Block[], drawing: Drawing): Line[] {
    const lines: Line[] = []
    for (const block of blocks) {
        if (lines.length > 0) {
            lines.push([])
        }
        lines.push(...linesOf(block, drawing))
    }
    return lines
}

/**
 * The blocks, as the text a terminal shows for them.
 *
 * @param blocks what `logic/markdown.ts`'s `parse` produced
 * @param colour `false` for the plain rendering (see the header), `true` for
 *     the default look, or a {@link Look} to draw with. `false` by default, so
 *     a caller who has not decided gets a string safe to pipe
 * @param width the columns a table may take. Prose is not wrapped to it
 * @returns the rendering, with no trailing newline — a scrollback decides its
 *     own spacing
 */
export function toTerminal(blocks: readonly Block[], colour: boolean | Look = false, width = 80): string {
    const look = colour === true ? DEFAULT_LOOK : colour === false ? undefined : colour
    return stacked(blocks, { look, width: Math.max(20, width) })
        .map((line) => encode(line, look))
        .join('\n')
}

/** How far a listing sits in from the transcript around it. */
const LISTING_INDENT = '  '

/**
 * A listing as one block: the lines it was given, each set in from the margin.
 *
 * <p><b>Layout, which is why it is here and not in `wording.ts`.</b> What each
 * row <i>says</i> is decided by `logic/wording.ts`; where it sits on a screen
 * is not. An answer to `/projects` is one thing a person asked for, so it reads
 * as one block rather than as loose lines.
 *
 * <p><b>An indent and not a marker, and no colour at all.</b> A bullet in front
 * of each row is what `toTerminal` draws for a markdown list, and reusing it
 * would make a listing look like something an agent wrote.
 *
 * @param lines what `describeProjects` or `describeConversations` answered,
 *     including the single sentence either of them gives for an empty listing
 * @returns the block, with no trailing newline: {@code Prompt.say} owns that
 */
export function toListing(lines: readonly string[]): string {
    return lines.map((line) => `${LISTING_INDENT}${line}`).join('\n')
}

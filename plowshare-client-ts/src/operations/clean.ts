/**
 * Text read out of a log, made safe to draw. A tool's output is whatever the command printed: a
 * tab measures one code point but a terminal prints it up to eight columns wide, a carriage
 * return sends the cursor back to column zero, and an escape sequence recolours, moves the cursor
 * or retitles the terminal. Every width here is counted in code points, so each of those breaks
 * the layout — or worse, writes where the redrawn region does not know it wrote.
 *
 * <p><b>One cleaner, applied wherever entry or record text becomes drawn lines</b>: the inline
 * tool lines and their failure bodies, reasoning lines, the explorer's rows and inspector, the
 * `/watch` rows and the plain listing. It is `composer.ts`'s control-character class, kept in
 * `logic/` so every drawn line can reach it, and made to keep line breaks: a result's lines are
 * its shape.
 */

/** How far apart tab stops are. */
const TAB = 4

/** OSC (`ESC ]` … BEL or `ESC \`), and DCS/SOS/PM/APC (`ESC P` `X` `^` `_` … `ESC \`). */
const STRING_SEQUENCE = /\u001b[\]PX^_][\s\S]*?(?:\u0007|\u001b\\|$)/gu
/** CSI, as `ESC [` or the one-byte C1 form, with its parameters and final byte. */
const CSI = /(?:\u001b\[|\u009b)[0-?]*[ -/]*[@-~]?/gu
/** Any other escape: ESC and the one character after it (`ESC 7`, `ESC c`, `ESC ( B` loses `B` too). */
const OTHER_ESCAPE = /\u001b[ -/]*[ -~]?/gu
/** C0 controls except `\t` (expanded below) and `\n` (kept), DEL, and the C1 controls. */
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/gu

/** `line` with each tab expanded to spaces up to the next stop. */
function expanded(line: string): string {
    if (!line.includes('\t')) {
        return line
    }
    let out = ''
    let column = 0
    for (const point of line) {
        if (point === '\t') {
            const pad = TAB - (column % TAB)
            out += ' '.repeat(pad)
            column += pad
        } else {
            out += point
            column += 1
        }
    }
    return out
}

/**
 * `text` as a terminal can draw it at the width it measures: `\r\n` and a lone `\r` become `\n`,
 * escape sequences are dropped whole, every other control character is dropped, and a tab
 * becomes spaces to the next stop four columns on.
 */
export function cleaned(text: string): string {
    const bare = text
        .replace(/\r\n?/gu, '\n')
        .replace(STRING_SEQUENCE, '')
        .replace(CSI, '')
        .replace(OTHER_ESCAPE, '')
        .replace(CONTROL, '')
    return bare.includes('\t') ? bare.split('\n').map(expanded).join('\n') : bare
}

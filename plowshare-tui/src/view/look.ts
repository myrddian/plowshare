import { BUILTIN } from './theme/builtin.ts'
import { resolve } from './theme/format.ts'
import { keyOf } from './theme/tokens.ts'
import type { Colour, Mode, Palette } from './theme/tokens.ts'

/**
 * A resolved theme, and the escape sequences that carry it.
 *
 * <h2>Named colours, not the terminal's sixteen, unless a theme asks</h2>
 *
 * <p>This client used to draw with SGR 2 (dim) and the sixteen named ANSI
 * colours, on the theory that the terminal's own palette suits its own
 * background. <b>It does not, and it was measured not to.</b> Solarized maps
 * "bright black" to its background and dims SGR 2 almost to nothing, so rules,
 * notes, the footer and the composer border all vanished.
 *
 * <p>So themes are explicit (`theme/`), written as 24-bit colour where the
 * terminal takes it and as the nearest xterm-256 entry where it does not — the
 * 256 cube is fixed by the standard rather than by the scheme. A theme may
 * still name `ansi:cyan` and get the terminal's own cyan; the `system` theme
 * does better, by asking the terminal what its colours are.
 */

/** How many colours a terminal accepts. */
export type Depth = 'truecolor' | '256'

/** A palette and how to write it. What a surface draws with. */
export interface Look {
    readonly name: string
    readonly mode: Mode
    readonly palette: Palette
    readonly depth: Depth
}

const builtin = (name: string) => BUILTIN.find((theme) => theme.name === name)

export const DEFAULT_LOOK: Look = {
    name: 'plowshare',
    mode: 'dark',
    palette: resolve('plowshare', 'dark', builtin).palette,
    depth: 'truecolor',
}

/** `#rrggbb` as three numbers. */
function channels(hex: string): [number, number, number] {
    const value = Number.parseInt(hex.slice(1), 16)
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

/** The nearest entry in xterm's 6x6x6 cube or its grey ramp. */
function nearest256(hex: string): number {
    const [r, g, b] = channels(hex)
    const step = (channel: number): number =>
        channel < 48 ? 0 : channel < 115 ? 1 : Math.min(5, Math.floor((channel - 35) / 40))
    const level = (index: number): number => (index === 0 ? 0 : 55 + index * 40)
    const [cr, cg, cb] = [step(r), step(g), step(b)]
    const cube = 16 + 36 * cr + 6 * cg + cb
    const cubeError = (level(cr) - r) ** 2 + (level(cg) - g) ** 2 + (level(cb) - b) ** 2
    const greyIndex = Math.max(0, Math.min(23, Math.round(((r + g + b) / 3 - 8) / 10)))
    const greyLevel = 8 + greyIndex * 10
    const greyError = (greyLevel - r) ** 2 + (greyLevel - g) ** 2 + (greyLevel - b) ** 2
    return greyError < cubeError ? 232 + greyIndex : cube
}

/** The SGR parameters that set a foreground (`38`) or background (`48`). */
export function colourCode(colour: Colour, depth: Depth, layer: 38 | 48): string {
    switch (colour.kind) {
        case 'rgb':
            return depth === 'truecolor'
                ? `${layer};2;${channels(colour.hex).join(';')}`
                : `${layer};5;${nearest256(colour.hex)}`
        case 'index':
            return `${layer};5;${colour.index}`
        case 'ansi': {
            const offset = layer === 38 ? 30 : 40
            return String(colour.slot < 8 ? offset + colour.slot : offset + 60 + colour.slot - 8)
        }
        case 'none':
            return layer === 38 ? '39' : '49'
    }
}

const INK_NAMES = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white'] as const

/** A colour as Ink's `color` props spell it, or `undefined` for the terminal's own. */
export function inkColour(colour: Colour | undefined): string | undefined {
    if (colour === undefined) {
        return undefined
    }
    switch (colour.kind) {
        case 'rgb':
            return colour.hex
        case 'index':
            return `ansi256(${colour.index})`
        case 'ansi':
            return `${INK_NAMES[colour.slot % 8] ?? 'white'}${colour.slot >= 8 ? 'Bright' : ''}`
        case 'none':
            return undefined
    }
}

/** The styles one run of text can carry. Absent means "whatever is underneath". */
export interface Style {
    readonly fg?: Colour
    readonly bold?: boolean
    readonly italic?: boolean
    readonly underline?: boolean
}

/** A piece of text and how it is drawn. */
export interface Run {
    readonly text: string
    readonly style: Style
}

/** `style` with a foreground, unchanged when the colour is absent or the terminal's own. */
export function tinted(style: Style, fg: Colour | undefined): Style {
    return fg === undefined || fg.kind === 'none' ? style : { ...style, fg }
}

/** The escapes that move from one style to the next, and nothing more. */
function transition(from: Style, to: Style, look: Look): string {
    const codes: string[] = []
    if ((from.bold ?? false) !== (to.bold ?? false)) {
        codes.push(to.bold === true ? '1' : '22')
    }
    if ((from.italic ?? false) !== (to.italic ?? false)) {
        codes.push(to.italic === true ? '3' : '23')
    }
    if ((from.underline ?? false) !== (to.underline ?? false)) {
        codes.push(to.underline === true ? '4' : '24')
    }
    if (keyOf(from.fg) !== keyOf(to.fg)) {
        codes.push(to.fg === undefined ? '39' : colourCode(to.fg, look.depth, 38))
    }
    // One sequence per change, so a run's opener and closer are each the
    // conventional single code a reader can recognise.
    return codes.map((code) => `[${code}m`).join('')
}

/**
 * Runs as the characters a terminal is handed, or as bare text with no look.
 *
 * <p><b>Transitions, not open-and-close pairs</b>, so nesting is honest: an
 * inline code run inside a coloured heading goes back to the heading's colour
 * afterwards rather than to the terminal's default. Every line ends with every
 * style closed, so a line can be put anywhere without its colour leaking.
 */
export function encode(runs: readonly Run[], look: Look | undefined): string {
    if (look === undefined) {
        return runs.map((run) => run.text).join('')
    }
    let current: Style = {}
    let out = ''
    for (const run of runs) {
        if (run.text === '') {
            continue
        }
        out += transition(current, run.style, look)
        out += run.text
        current = run.style
    }
    return out + transition(current, {}, look)
}

/** How many columns `text` occupies, ignoring escapes. */
export function widthOf(text: string): number {
    // eslint-disable-next-line no-control-regex
    const bare = text.replace(/\[[0-9;]*m/gu, '')
    let width = 0
    for (const char of bare) {
        const point = char.codePointAt(0) ?? 0
        if (/\p{M}|​|‌|‍|️|﻿/u.test(char)) {
            continue
        }
        // Pictographs below U+1F000 (⏺, ✗, ☐) are drawn one column wide by
        // most terminals, so only the emoji planes count as two.
        const wide = (point >= 0x1f000 && /\p{Extended_Pictographic}/u.test(char))
            || (point >= 0x1100 && point <= 0x115f)
            || (point >= 0x2e80 && point <= 0xa4cf)
            || (point >= 0xac00 && point <= 0xd7a3)
            || (point >= 0xf900 && point <= 0xfaff)
            || (point >= 0xfe30 && point <= 0xfe4f)
            || (point >= 0xff00 && point <= 0xff60)
            || (point >= 0xffe0 && point <= 0xffe6)
            || (point >= 0x20000 && point <= 0x3fffd)
        width += wide ? 2 : 1
    }
    return width
}

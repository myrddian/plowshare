/**
 * Lines a surface paints, as runs of text each naming what it MEANS — a role — and never what
 * colour it is. Spec 2026-09-29 §7: the words stay in `wording.ts`, the roles are decided
 * beside them, and the view alone maps a role to a theme token. The inline tool lines, the
 * explorer, `/watch` and the runs panel are all drawn from these, so they read as one product.
 *
 * Widths are counted in code points. That is right for everything this client writes itself
 * (glyphs, ASCII, paths) and a small overcount risk for wide characters in a model's text,
 * which the view's own truncation catches.
 */

export type Role =
    | 'text' | 'muted' | 'strong' | 'accent'
    | 'tool' | 'salient' | 'time' | 'rail'
    | 'ok' | 'fail' | 'waiting' | 'unknown'
    | 'reasoning' | 'milestone' | 'selected'
    | 'actor' | 'actor1' | 'actor2' | 'actor3' | 'actor4'
    | 'badgeUser' | 'badgeThink' | 'badgeAnswer' | 'badgeTool' | 'badgeFold'
    | 'badgeNote' | 'badgeFail' | 'badgeHook' | 'badgePlan'
    | 'timelineModel' | 'timelineTool'
    | 'diffAdded' | 'diffRemoved'

export interface Tint {
    readonly text: string
    readonly role: Role
    /** A background: only the explorer's and the viewer's cursor row has one. */
    readonly back?: 'selection'
}

/** One line: its tints, left to right, never containing a newline. */
export type Tinted = readonly Tint[]

export const tint = (text: string, role: Role = 'text'): Tint => ({ text, role })

export const lengthOf = (text: string): number => [...text].length

export function plainOf(line: Tinted): string {
    return line.map((each) => each.text).join('')
}

/** The line cut to `width`, its last visible code point replaced by `…` when anything was cut. */
export function fitTinted(line: Tinted, width: number): Tinted {
    if (width <= 0) {
        return []
    }
    if (lengthOf(plainOf(line)) <= width) {
        return line
    }
    const kept: Tint[] = []
    let room = width - 1
    for (const each of line) {
        if (room <= 0) {
            break
        }
        const points = [...each.text]
        if (points.length <= room) {
            kept.push(each)
            room -= points.length
            continue
        }
        kept.push({ ...each, text: points.slice(0, room).join('') })
        room = 0
    }
    const last = kept.at(-1)
    if (last === undefined) {
        return [tint('…', line[0]?.role ?? 'text')]
    }
    kept[kept.length - 1] = { ...last, text: `${last.text}…` }
    return kept
}

/** The line padded with plain spaces to `width`; a longer line is returned as it is. */
export function padTinted(line: Tinted, width: number): Tinted {
    const short = width - lengthOf(plainOf(line))
    return short <= 0 ? line : [...line, tint(' '.repeat(short))]
}

export function selectedLine(line: Tinted): Tinted {
    return line.map((each) => ({ ...each, back: 'selection' as const }))
}

/** `text` in at most `width` code points, cut in the middle so a path keeps its root and its file. */
export function shortenMiddle(text: string, width: number): string {
    const points = [...text]
    if (points.length <= width) {
        return text
    }
    if (width <= 1) {
        return '…'.slice(0, width)
    }
    const front = Math.ceil((width - 1) / 2)
    const back = Math.floor((width - 1) / 2)
    return `${points.slice(0, front).join('')}…${points.slice(points.length - back).join('')}`
}

import { Text } from 'ink'
import { createElement as el, type ReactElement } from 'react'

import type { Role, Tinted } from '../../logic/tints.ts'
import { inkColour } from '../look.ts'
import type { Colour, Palette } from '../theme/tokens.ts'

/** The one place a role becomes a colour. Spec 2026-09-29 §7-8. */
export function roleColour(role: Role, palette: Palette): Colour {
    switch (role) {
        case 'text':
        case 'salient':
        case 'strong':
            return palette.text
        case 'muted':
        case 'time':
        case 'unknown':
            return palette.muted
        case 'accent':
        case 'selected':
            return palette.accent
        case 'rail':
            return palette.border
        case 'milestone':
            return palette.mdHeading2
        case 'actor':
            return palette.muted
        case 'diffAdded':
            return palette.diffAdded
        case 'diffRemoved':
            return palette.diffRemoved
        default:
            return palette[role]
    }
}

const BOLD: ReadonlySet<Role> = new Set<Role>(['tool', 'strong', 'selected', 'actor', 'badgeUser',
    'badgeThink', 'badgeAnswer', 'badgeTool', 'badgeFold', 'badgeNote', 'badgeFail', 'badgeHook', 'badgePlan'])

/** One tinted line as a row of Ink text; with no palette, the plain text alone. */
export function tintedOf(line: Tinted, palette: Palette | undefined, key: string): ReactElement {
    return el(Text, { key, wrap: 'truncate-end' }, ...line.map((each, at) => {
        if (palette === undefined) {
            return each.text
        }
        const colour = inkColour(roleColour(each.role, palette))
        const back = each.back === 'selection' ? inkColour(palette.selection) : undefined
        return el(Text, {
            key: `${key}-${at}`,
            ...(colour === undefined ? {} : { color: colour }),
            ...(back === undefined ? {} : { backgroundColor: back }),
            ...(BOLD.has(each.role) ? { bold: true } : {}),
            ...(each.role === 'reasoning' ? { italic: true } : {}),
        }, each.text)
    }))
}

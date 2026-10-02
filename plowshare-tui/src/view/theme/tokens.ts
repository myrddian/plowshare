/**
 * The names a theme gives colours to, and what a resolved colour is.
 *
 * <h2>Named by role, never by hue</h2>
 *
 * <p>A theme says what a <i>heading</i> looks like, not what <i>purple</i> looks
 * like — so a theme that wants blue headings changes one token, and nothing in
 * `scrollback.ts` or `app.ts` ever names a colour. Grouped the way a person
 * writing a theme thinks about the screen: the chrome around the conversation,
 * the markdown inside it, the code inside that, and diffs.
 *
 * <p>Every token is required in a resolved {@link Palette}. A theme FILE may
 * set any subset of them, because it starts from a `base`; see `format.ts`.
 */

export const TOKENS = [
    // The screen around the conversation.
    'text', 'muted', 'accent', 'border', 'panel',
    'person', 'bot', 'trouble', 'busy', 'success', 'warning',
    'wordmarkFaint', 'wordmarkBright',
    // Markdown.
    'mdHeading1', 'mdHeading2', 'mdHeading3', 'mdLink', 'mdLinkUrl', 'mdCode',
    'mdCodeBlockBorder', 'mdCodeBlockLabel', 'mdQuote', 'mdRule', 'mdBullet',
    'mdTableBorder', 'mdTableHeader',
    // Code.
    'syntaxComment', 'syntaxKeyword', 'syntaxFunction', 'syntaxVariable', 'syntaxString',
    'syntaxNumber', 'syntaxType', 'syntaxOperator', 'syntaxPunctuation', 'syntaxMeta',
    // Diffs.
    'diffAdded', 'diffRemoved', 'diffHunk',
    // The trajectory: tool lines, the explorer, /watch — spec 2026-09-29 §8.
    'tool', 'ok', 'fail', 'waiting', 'reasoning', 'selection',
    'actor1', 'actor2', 'actor3', 'actor4',
    'badgeUser', 'badgeThink', 'badgeAnswer', 'badgeTool', 'badgeFold',
    'badgeNote', 'badgeFail', 'badgeHook', 'badgePlan',
    'timelineModel', 'timelineTool',
] as const

export type Token = typeof TOKENS[number]

/** Whether `name` is one of {@link TOKENS}. */
export function isToken(name: string): name is Token {
    return (TOKENS as readonly string[]).includes(name)
}

/** The sixteen terminal palette slots, by the names themes write them with. */
export const ANSI_NAMES = [
    'black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
    'brightBlack', 'brightRed', 'brightGreen', 'brightYellow',
    'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite',
] as const

/**
 * One resolved colour.
 *
 * <ul>
 * <li>`rgb` — a fixed colour, written as 24-bit or the nearest xterm-256 entry
 * <li>`index` — an xterm-256 entry, written as itself
 * <li>`ansi` — one of the terminal's own sixteen, whatever the theme maps it to.
 *     The reason `system`-less themes can still follow a terminal's scheme,
 *     and the reason to use them sparingly: see `look.ts` on Solarized
 * <li>`none` — the terminal's default foreground or background
 * </ul>
 */
export type Colour =
    | { readonly kind: 'rgb', readonly hex: string }
    | { readonly kind: 'index', readonly index: number }
    | { readonly kind: 'ansi', readonly slot: number }
    | { readonly kind: 'none' }

export type Palette = Readonly<Record<Token, Colour>>

/** Whether the theme was resolved for a dark or a light background. */
export type Mode = 'dark' | 'light'

export const rgb = (hex: string): Colour => ({ kind: 'rgb', hex: hex.toLowerCase() })

/** A stable key for a colour, so two runs can be compared. */
export function keyOf(colour: Colour | undefined): string {
    if (colour === undefined) {
        return ''
    }
    switch (colour.kind) {
        case 'rgb':
            return colour.hex
        case 'index':
            return `i${colour.index}`
        case 'ansi':
            return `a${colour.slot}`
        case 'none':
            return 'none'
    }
}

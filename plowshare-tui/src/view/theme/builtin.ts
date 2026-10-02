import type { ThemeFile } from './format.ts'

/**
 * The themes that ship, written in exactly the format a person's own theme file
 * is — so a built-in is also the worked example, and `/theme` can show where
 * any theme's colours came from without two code paths.
 *
 * <p>`plowshare` is the complete one: every token, both modes. Everything else,
 * built-in or on disk, is sparse over a `base`.
 */

export const PLOWSHARE: ThemeFile = {
    name: 'plowshare',
    vars: {
        ink: { dark: '#e4ecf7', light: '#1f2733' },
        slate: { dark: '#8b9bb4', light: '#5b6878' },
        steel: { dark: '#5a6b85', light: '#9aa6b6' },
        dusk: { dark: '#1e2a3b', light: '#e4e9f1' },
        blue: { dark: '#7aa2f7', light: '#2e5cb8' },
        violet: { dark: '#c099ff', light: '#7a3eb1' },
        sky: { dark: '#86e1fc', light: '#0f6f7c' },
        amber: { dark: '#ffc777', light: '#9a4a12' },
        green: { dark: '#c3e88d', light: '#2f7d32' },
        coral: { dark: '#ff757f', light: '#c62f45' },
        orange: { dark: '#ff966c', light: '#b35c00' },
        stone: { dark: '#7a88a1', light: '#7c8796' },
        faint: { dark: '#6b7a90', light: '#9aa6b6' },
        lift: { dark: '#26344d', light: '#e1e8f2' },
    },
    colors: {
        text: 'none',
        muted: 'slate',
        accent: 'blue',
        border: 'steel',
        panel: 'dusk',
        person: 'sky',
        bot: 'green',
        trouble: 'coral',
        busy: 'amber',
        success: 'green',
        warning: 'amber',
        wordmarkFaint: 'faint',
        wordmarkBright: 'ink',

        mdHeading1: 'violet',
        mdHeading2: 'blue',
        mdHeading3: 'sky',
        mdLink: 'sky',
        mdLinkUrl: 'slate',
        mdCode: 'amber',
        mdCodeBlockBorder: 'steel',
        mdCodeBlockLabel: 'slate',
        mdQuote: 'violet',
        mdRule: 'steel',
        mdBullet: 'blue',
        mdTableBorder: 'steel',
        mdTableHeader: 'blue',

        syntaxComment: 'stone',
        syntaxKeyword: 'violet',
        syntaxFunction: 'blue',
        syntaxVariable: 'none',
        syntaxString: 'green',
        syntaxNumber: 'orange',
        syntaxType: 'sky',
        syntaxOperator: 'sky',
        syntaxPunctuation: 'slate',
        syntaxMeta: 'slate',

        diffAdded: 'green',
        diffRemoved: 'coral',
        diffHunk: 'blue',

        tool: 'blue', ok: 'green', fail: 'coral', waiting: 'amber', reasoning: 'slate', selection: 'lift',
        actor1: 'violet', actor2: 'sky', actor3: 'orange', actor4: 'green',
        badgeUser: 'sky', badgeThink: 'stone', badgeAnswer: 'green', badgeTool: 'blue', badgeFold: 'violet',
        badgeNote: 'slate', badgeFail: 'coral', badgeHook: 'orange', badgePlan: 'amber',
        timelineModel: 'violet', timelineTool: 'blue',
    },
}

/** The name of the theme built from the terminal's own palette. See `system.ts`. */
export const SYSTEM = 'system'

export const BUILTIN: readonly ThemeFile[] = [PLOWSHARE]

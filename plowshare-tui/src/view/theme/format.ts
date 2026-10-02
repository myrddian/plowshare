import { ANSI_NAMES, isToken, rgb, TOKENS } from './tokens.ts'
import type { Colour, Mode, Palette, Token } from './tokens.ts'

/**
 * A theme file, and how it becomes a {@link Palette}.
 *
 * <h2>The format, which borrows one idea from each client that got one right</h2>
 *
 * <pre>
 * {
 *   "$schema": "…/plowshare-theme.schema.json",
 *   "name": "dusk",
 *   "base": "plowshare",
 *   "vars": { "violet": "#a78bfa", "night": { "dark": "#11111b", "light": "#eff1f5" } },
 *   "colors": { "accent": "violet", "mdHeading2": "accent", "panel": "night" }
 * }
 * </pre>
 *
 * <ul>
 * <li><b>`base` plus a sparse `colors`</b>, so a theme that changes three colours
 *     is three lines. Every token not named comes from the base.
 * <li><b>`vars`</b>, so a colour is spelt once and named everywhere it is used.
 *     A value may also name another token, in this file or the base.
 * <li><b>`{ "dark": …, "light": … }` anywhere a value goes</b>, so one file
 *     serves both backgrounds.
 * </ul>
 *
 * <p>A value is `#rgb` or `#rrggbb`, a 0–255 integer or `ansi256(n)` for an
 * xterm-256 entry, `ansi:cyan` (or any of {@link ANSI_NAMES}) for one of the
 * terminal's own sixteen, `none` for the terminal's default, or a name.
 *
 * <h2>A bad colour is a problem reported, never a theme refused</h2>
 *
 * <p>Somebody editing a theme by hand gets the rest of their theme and a
 * sentence naming the line that did not work, rather than being dropped back to
 * the default for a typo. Unknown tokens, unresolvable names and reference
 * cycles all land in {@link Resolved.problems}; the token keeps its base colour.
 *
 * <p>Pure: no file system. `loader.ts` reads the files; this reads the data.
 */

export type Value = string | number | { readonly dark: Value, readonly light: Value }

export interface ThemeFile {
    readonly name?: string
    readonly base?: string
    readonly vars?: Readonly<Record<string, Value>>
    readonly colors?: Readonly<Record<string, Value>>
}

export interface Resolved {
    readonly palette: Palette
    readonly problems: readonly string[]
}

/** Looks a theme up by name, for `base`. */
export type Themes = (name: string) => ThemeFile | undefined

const HEX = /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/iu

/** `#abc` as `#aabbcc`. */
function longHex(hex: string): string {
    return hex.length === 4
        ? `#${[...hex.slice(1)].map((digit) => digit + digit).join('')}`
        : hex
}

/** A value that is a colour by its spelling alone, or `undefined` if it is a name. */
function literal(value: string | number): Colour | undefined {
    if (typeof value === 'number') {
        return Number.isInteger(value) && value >= 0 && value <= 255
            ? { kind: 'index', index: value }
            : undefined
    }
    if (HEX.test(value)) {
        return rgb(longHex(value))
    }
    const indexed = /^ansi256\((\d{1,3})\)$/u.exec(value)
    if (indexed !== null) {
        return literal(Number(indexed[1]))
    }
    if (value.startsWith('ansi:')) {
        const slot = (ANSI_NAMES as readonly string[]).indexOf(value.slice(5))
        return slot === -1 ? undefined : { kind: 'ansi', slot }
    }
    if (value === 'none') {
        return { kind: 'none' }
    }
    return undefined
}

/** Whether a string could only ever have been meant as a literal. */
const looksLiteral = (value: string): boolean =>
    value.startsWith('#') || value.startsWith('ansi')

/**
 * The theme called `name`, resolved for `mode`.
 *
 * @param themes every theme this client knows, built-in and on disk
 */
export function resolve(name: string, mode: Mode, themes: Themes): Resolved {
    return resolving(name, mode, themes, [])
}

function resolving(name: string, mode: Mode, themes: Themes, chain: readonly string[]): Resolved {
    const file = themes(name)
    if (file === undefined) {
        throw new Error(`there is no theme called ${name}`)
    }
    if (chain.includes(name)) {
        throw new Error(`theme ${[...chain, name].join(' → ')} is its own base`)
    }
    const problems: string[] = []
    let base: Palette | undefined
    if (file.base !== undefined) {
        const resolved = resolving(file.base, mode, themes, [...chain, name])
        base = resolved.palette
        problems.push(...resolved.problems)
    }
    const vars = file.vars ?? {}
    const colors = file.colors ?? {}
    for (const key of Object.keys(colors)) {
        if (!isToken(key)) {
            problems.push(`${name}: there is no colour called ${key}`)
        }
    }

    const done = new Map<string, Colour>()
    const value = (spelt: Value, path: readonly string[]): Colour | undefined => {
        if (typeof spelt === 'object') {
            return value(spelt[mode], path)
        }
        const known = literal(spelt)
        if (known !== undefined) {
            return known
        }
        if (typeof spelt === 'number' || looksLiteral(spelt)) {
            problems.push(`${name}: ${String(spelt)} is not a colour`)
            return undefined
        }
        return named(spelt, path)
    }
    // A var and a token may share a name — `"text": "text"` pointing a token at
    // a var is the ordinary way to write one — so the two are kept apart, and a
    // bare name means the var when there is one.
    const entry = (key: string, spelt: Value, path: readonly string[]): Colour | undefined => {
        if (path.includes(key)) {
            const circle = [...path, key].map((step) => step.slice(2)).join(' → ')
            problems.push(`${name}: ${circle} goes round in a circle`)
            return undefined
        }
        const cached = done.get(key)
        if (cached !== undefined) {
            return cached
        }
        const found = value(spelt, [...path, key])
        if (found !== undefined) {
            done.set(key, found)
        }
        return found
    }
    const token = (named: Token, path: readonly string[]): Colour | undefined =>
        Object.hasOwn(colors, named)
            ? entry(`t:${named}`, colors[named] as Value, path)
            : base?.[named]
    const named = (reference: string, path: readonly string[]): Colour | undefined => {
        if (Object.hasOwn(vars, reference)) {
            return entry(`v:${reference}`, vars[reference] as Value, path)
        }
        if (isToken(reference) && (Object.hasOwn(colors, reference) || base !== undefined)) {
            return token(reference, path)
        }
        problems.push(`${name}: nothing called ${reference}`)
        return undefined
    }

    const palette = {} as Record<Token, Colour>
    for (const each of TOKENS) {
        const own = Object.hasOwn(colors, each) ? token(each, []) : undefined
        const colour = own ?? base?.[each]
        if (colour === undefined) {
            problems.push(`${name}: ${each} has no colour and no base to take one from`)
            palette[each] = { kind: 'none' }
        } else {
            palette[each] = colour
        }
    }
    return { palette, problems }
}

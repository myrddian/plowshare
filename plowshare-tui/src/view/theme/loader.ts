import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'

import { BUILTIN } from './builtin.ts'
import type { ThemeFile } from './format.ts'

/**
 * Where themes come from, and where the choice of one is kept.
 *
 * <h2>Three places, later wins</h2>
 *
 * <ol>
 * <li>the built-ins, in `builtin.ts`
 * <li>`$XDG_CONFIG_HOME/plowshare/themes/*.json` — `~/.config/plowshare/themes`
 *     by default, beside the `console-token` the other clients keep there
 * <li>`.plowshare/themes/*.json` in the directory this client was started in
 * </ol>
 *
 * <p>A file's name is its theme's name: `dusk.json` is `dusk`, whatever its
 * `name` field says, so the name `/theme` offers is always one the person can
 * find on disk. A file that shares a built-in's name replaces it.
 */

export interface Library {
    readonly names: readonly string[]
    readonly get: (name: string) => ThemeFile | undefined
    /** Where each theme came from: a path, or `built-in`. */
    readonly source: (name: string) => string | undefined
    /** Files that could not be read, as sentences. */
    readonly problems: readonly string[]
}

type Env = Readonly<Record<string, string | undefined>>

/** `~/.config/plowshare`, or wherever `XDG_CONFIG_HOME` says. */
export function configDir(env: Env, home: string): string {
    const base = env['XDG_CONFIG_HOME'] ?? join(home, '.config')
    return join(base, 'plowshare')
}

/** The directories themes are read from, lowest precedence first. */
export function themeDirs(env: Env, home: string, cwd: string): string[] {
    return [join(configDir(env, home), 'themes'), join(cwd, '.plowshare', 'themes')]
}

/** Every theme: the built-ins, then each directory's files over them. */
export function loadLibrary(dirs: readonly string[], extra: readonly ThemeFile[] = []): Library {
    const themes = new Map<string, ThemeFile>()
    const sources = new Map<string, string>()
    const problems: string[] = []
    for (const theme of [...BUILTIN, ...extra]) {
        const name = theme.name ?? ''
        themes.set(name, theme)
        sources.set(name, 'built-in')
    }
    for (const dir of dirs) {
        let files: string[]
        try {
            files = readdirSync(dir).filter((file) => file.endsWith('.json')).sort()
        } catch {
            // No directory is the ordinary case, not a problem.
            continue
        }
        for (const file of files) {
            const path = join(dir, file)
            const name = basename(file, '.json')
            try {
                const parsed: unknown = JSON.parse(readFileSync(path, 'utf8'))
                if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
                    problems.push(`${path}: a theme is a JSON object`)
                    continue
                }
                themes.set(name, { ...(parsed as ThemeFile), name })
                sources.set(name, path)
            } catch (trouble) {
                problems.push(`${path}: ${trouble instanceof Error ? trouble.message : String(trouble)}`)
            }
        }
    }
    return {
        names: [...themes.keys()].sort(),
        get: (name) => themes.get(name),
        source: (name) => sources.get(name),
        problems,
    }
}

/** What `/theme` and Ctrl-T saved, so the next start opens in them. */
export interface Settings {
    readonly theme?: string
    /** How tool lines are drawn in the chat — spec 2026-09-29 §4. */
    readonly density?: 'compact' | 'full' | 'hidden'
}

export const settingsPath = (env: Env, home: string): string =>
    join(configDir(env, home), 'tui.json')

export function readSettings(path: string): Settings {
    try {
        const parsed: unknown = JSON.parse(readFileSync(path, 'utf8'))
        return typeof parsed === 'object' && parsed !== null ? parsed as Settings : {}
    } catch {
        return {}
    }
}

/** Saves `changes` over what is there, keeping any key this client does not know. */
export function writeSettings(path: string, changes: Settings): void {
    const current = readSettings(path)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, `${JSON.stringify({ ...current, ...changes }, null, 2)}\n`)
}

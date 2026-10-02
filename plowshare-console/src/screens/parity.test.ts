import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { VIEWS } from './shell'

/**
 * The console is the third front end, and this is the half of that claim no
 * Java can check.
 *
 * `client/Capabilities` declares, per capability, the MCP tools that offer it,
 * the CLI commands that offer it and now the console screens that offer it. The
 * first two are enforced where each surface is assembled — `PlowshareClient.tools`
 * and `Commands`' static initialiser refuse a spelling nothing declares — and
 * `ParityTest` pins the direction no assembly can see. **Neither mechanism can
 * reach the console.** There is no Java that builds these screens, so a list of
 * screen names on that side is precisely the fixture `ParityTest`'s own javadoc
 * warns about: one that "would pass on the day a tool was deleted".
 *
 * So the check lives here, where the surface actually is, and it is held against
 * `VIEWS` — the list the shell really builds its nav from — rather than against
 * a second list written down for the purpose. Both directions matter and they
 * catch different mistakes:
 *
 * - **A screen named there and not here** is a capability the register says a
 *   person can reach in the browser when they cannot: a view renamed or deleted
 *   with the declaration left behind.
 * - **A view here and named nowhere there** is the drift that started all of
 *   this: log search shipped on two surfaces of three because a third surface
 *   existed that nothing described. A screen added to this console has to say
 *   what capability it offers before it counts as offered.
 *
 * Reading the Java source rather than importing anything is the only option
 * across the module boundary, and it is why `Capabilities.inConsole` exists as a
 * method: the marker has to be one shape a regular expression can find without
 * parsing Java.
 */

const HERE = dirname(fileURLToPath(import.meta.url))

/** The register, from the client module. `screens/` is three below the root. */
const CAPABILITIES = join(
    HERE, '..', '..', '..',
    'plowshare-client', 'src', 'main', 'java', 'io', 'aeyer', 'plowshare', 'client',
    'Capabilities.java')

/**
 * The file with its comments stripped.
 *
 * Stripped for `render.test.ts`'s reason, sharpened: that file's javadoc names
 * `inConsole` in prose several times, and a check that matched a comment would
 * be a check that forbids explaining itself. Java text blocks are left alone —
 * none of them contains the marker, and a `"""` block that did would be prose
 * about the marker rather than a declaration of one.
 */
function source(): string {
    return readFileSync(CAPABILITIES, 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '')
}

/** Every console view the register names, from the marker calls themselves. */
function declared(): string[] {
    const found = new Set<string>()
    for (const call of source().matchAll(/inConsole\(([^)]*)\)/g)) {
        for (const literal of (call[1] ?? '').matchAll(/"([^"]*)"/g)) {
            found.add(literal[1] as string)
        }
    }
    return [...found].sort()
}

describe('the console is a declared front end', () => {
    it('finds the register where the client module keeps it', () => {
        // A path that has gone stale would otherwise make every assertion below
        // pass against an empty string, which is the one failure mode of a test
        // that reads a file.
        expect(source()).toContain('class Capabilities')
        expect(declared().length).toBeGreaterThan(0)
    })

    it('names only screens this console really has', () => {
        for (const screen of declared()) {
            expect(VIEWS as readonly string[]).toContain(screen)
        }
    })

    it('names every screen this console has, so a new one has to say what it offers', () => {
        // The direction the whole third column was added for. A view that no
        // capability names is a surface offering something the register cannot
        // see -- which is exactly how log search came to be on two surfaces of
        // three with nothing recording it.
        expect([...VIEWS].sort()).toEqual(declared())
    })
})

/**
 * `.plowshare/project`: the one file that makes a directory a project.
 *
 * <p>Spec §3 and §5. Found by walking up from where the client stands, nearest
 * first, so a sub-project shadows the project around it without either knowing.
 * It holds a name and nothing else — no server, no id — because a name is all
 * discovery needs and anything more would marry a directory to one deployment.
 * A `.plowshare/` without it is only a source of definitions, and the walk goes on.
 */
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises'
import { basename, dirname, join, relative, isAbsolute } from 'node:path'
import { DEFINITIONS_DIRECTORY as DEFINITIONS } from './enforcer.ts'

export const PROJECT_FILE = 'project'
export const MACHINE_ENV = 'PLOWSHARE_MACHINE'
export const UNNAMED_MACHINE = 'unnamed-machine'

export interface Marked {
    /** Canonical: the directory holding `.plowshare/`, and the root that is served. */
    readonly root: string
    readonly project: string
}

/** The name a directory's own marker holds, or nothing — never an ancestor's. */
export async function markedName(directory: string): Promise<string | undefined> {
    let text: string
    try {
        text = await readFile(join(directory, DEFINITIONS, PROJECT_FILE), 'utf8')
    } catch {
        return undefined
    }
    const name = (text.split(/\r\n|\r|\n/)[0] ?? '').trim()
    return name === '' ? undefined : name
}

export async function discover(from: string): Promise<Marked | undefined> {
    let at = await realpath(from)
    for (;;) {
        const project = await markedName(at)
        if (project !== undefined) {
            return { root: at, project }
        }
        const parent = dirname(at)
        if (parent === at) {
            return undefined
        }
        at = parent
    }
}

/** A directory that is already a different project. Nothing was written. */
export class AlreadyMarked extends Error {
    // Fields assigned by hand, not parameter properties: Node runs this file by
    // stripping types, and a parameter property is code, not a type.
    readonly root: string
    readonly existing: string
    readonly asked: string

    constructor(root: string, existing: string, asked: string) {
        super(`${root} is already the project ${existing}, so it cannot also be ${asked}`)
        this.name = 'AlreadyMarked'
        this.root = root
        this.existing = existing
        this.asked = asked
    }
}

/**
 * Makes `root` the project `project`, unless it already is — in which case this
 * writes nothing — or is another project, in which case it refuses.
 */
export async function mark(root: string, project: string): Promise<Marked> {
    const existing = await markedName(root)
    if (existing !== undefined && existing !== project) {
        throw new AlreadyMarked(root, existing, project)
    }
    if (existing === undefined) {
        await mkdir(join(root, DEFINITIONS), { recursive: true })
        await writeFile(join(root, DEFINITIONS, PROJECT_FILE), `${project}\n`, 'utf8')
    }
    return { root, project }
}

/** `Rooting.thisMachine`, with the host name passed in so it has a test. */
export function thisMachine(
        environment: Readonly<Record<string, string | undefined>>, named: string): string {
    const fromEnvironment = (environment[MACHINE_ENV] ?? '').trim()
    if (fromEnvironment !== '') {
        return fromEnvironment
    }
    const host = named.trim()
    return host === '' ? UNNAMED_MACHINE : host
}

/** What `/here` calls a project nobody named: its directory. */
export function namedAfter(root: string): string {
    const name = basename(root)
    return name === '' ? 'root' : name
}

/** Whether `child` is `parent` or somewhere below it. Both must be canonical. */
export function within(parent: string, child: string): boolean {
    const between = relative(parent, child)
    return between === '' || (!between.startsWith('..') && !isAbsolute(between))
}

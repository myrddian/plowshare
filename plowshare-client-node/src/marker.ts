/** Shared project discovery: local markers take precedence; root manifests are private candidates. */
import { mkdir, lstat, readFile, realpath, writeFile } from 'node:fs/promises'
import { basename, dirname, join, relative, isAbsolute } from 'node:path'
import type { Connection } from 'plowshare-client-ts/binding/connection'
import { conversationReply } from 'plowshare-client-ts/operations/conversation-replies'
import { projectSettings } from 'plowshare-client-ts/binding/project-settings'
import type { ProjectManifestSettings } from 'plowshare-client-ts/binding/project-settings'
import { clientProject, projectLabel } from 'plowshare-client-ts/operations/project-label'
export { clientProject, projectLabel } from 'plowshare-client-ts/operations/project-label'
const DEFINITIONS = '.plowshare'

export const PROJECT_FILE = 'project'
export const MACHINE_ENV = 'PLOWSHARE_MACHINE'
export const UNNAMED_MACHINE = 'unnamed-machine'

/** Declarative configuration; discovery does not authorize or deliver messages. */
export interface ProjectRouting {
    readonly acceptFrom?: readonly string[]
    readonly sendTo?: readonly string[]
    readonly routeFiles?: readonly string[]
    readonly [property: string]: unknown
}

/** Identity plus extensible application/SDK configuration. */
export interface ProjectManifest extends ProjectManifestSettings {
    readonly version: 1
    readonly name: string
    readonly routing?: ProjectRouting
    readonly [property: string]: unknown
}

export interface Marked {
    /** Canonical: the directory holding `.plowshare/`, and the root that is served. */
    readonly root: string
    readonly project: string
    readonly kind?: 'DISJOINT'
    readonly displayName?: string
}

/** The name a directory's own marker holds, or nothing — never an ancestor's. */
export async function markedName(directory: string): Promise<string | undefined> {
    for (const file of ['plowshare', PROJECT_FILE]) {
        const text = await markerText(directory, join(DEFINITIONS, file))
        if (text !== undefined) {
            if (!text.trimStart().startsWith('{') && !(text.split(/\r\n|\r|\n/)[0] ?? '').trim()) continue
            return markerName(text)
        }
    }
    return undefined
}

async function markerText(directory: string, relativeFile: string): Promise<string | undefined> {
    try {
        if (relativeFile.includes('/')) {
            const parent = await lstat(join(directory, DEFINITIONS))
            if (parent.isSymbolicLink() || !parent.isDirectory()) throw new Error('The project marker needs a regular .plowshare directory')
        }
        const file = join(directory, relativeFile), info = await lstat(file)
        if (info.isSymbolicLink() || !info.isFile()) throw new Error('The project marker must be a regular file')
        if (info.size > 65536) throw new Error('The project marker is too large')
        const text = await readFile(file, 'utf8')
        if (relativeFile === 'plowshare' && text.startsWith('#!')) return undefined // The CLI executable is not a manifest.
        return text
    } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined; throw error }
}

/** Parse without discarding application fields. Legacy markers normalize to version 1. */
export function projectManifest(text: string): ProjectManifest {
    let record: Record<string, unknown>
    if (text.trimStart().startsWith('{') || text.trimStart().startsWith('[')) {
        let value: unknown
        try { value = JSON.parse(text) } catch { throw new Error('Invalid JSON project manifest') }
        if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('The project manifest must be an object')
        record = value as Record<string, unknown>
        if (record['version'] !== 1) throw new Error('Use project manifest version 1 with version and name fields')
    } else record = {version: 1, name: (text.split(/\r\n|\r|\n/)[0] ?? '').trim()}
    if (!validProjectName(record['name'])) throw new Error('Invalid project name in marker')
    projectSettings(record)
    if (record['routing'] !== undefined) {
        const routing = record['routing']
        if (!routing || typeof routing !== 'object' || Array.isArray(routing)) throw new Error('Project routing must be an object')
        const fields = routing as Record<string, unknown>
        for (const field of ['acceptFrom', 'sendTo', 'routeFiles']) {
            const entries = fields[field]
            if (entries === undefined) continue
            if (!Array.isArray(entries) || entries.length > 256 || new Set(entries).size !== entries.length
                || !entries.every(field === 'routeFiles' ? validRouteFile : validProjectName)) {
                throw new Error(`Invalid project routing ${field}`)
            }
        }
    }
    return record as unknown as ProjectManifest
}

function validProjectName(value: unknown): value is string {
    return typeof value === 'string' && value.trim() !== '' && value === value.trim()
        && value.length <= 512 && !/[\r\n\0]/u.test(value)
}

function validRouteFile(value: unknown): value is string {
    return validProjectName(value) && !/[\\:]/u.test(value)
        && value.split('/').every(part => part !== '' && part !== '.' && part !== '..')
}

/** Versioned JSON is preferred; a plain first-line name remains compatible. */
export function markerName(text: string): string { return projectManifest(text).name }

/** Read a directory's configuration with the same local/root precedence as discovery. */
export async function readProjectManifest(directory: string): Promise<ProjectManifest | undefined> {
    return (await readProjectManifestFile(directory))?.manifest
}

export async function readProjectManifestFile(directory: string): Promise<{file:string; source:string; manifest:ProjectManifest} | undefined> {
    for (const file of [join(DEFINITIONS, 'plowshare'), join(DEFINITIONS, PROJECT_FILE), 'plowshare']) {
        const text = await markerText(directory, file)
        if (text === undefined) continue
        if (file !== 'plowshare' && !text.trimStart().startsWith('{') && !(text.split(/\r\n|\r|\n/)[0] ?? '').trim()) continue
        return {file:join(directory,file), source:text, manifest:projectManifest(text)}
    }
    return undefined
}

/** Check existing local/UNION identities before creating an isolated client scope. */
export async function resolveMarked(found: Marked, connection: Connection, machine: string): Promise<Marked> {
    if (found.kind !== 'DISJOINT') return found
    const listed = conversationReply('project.list', await connection.ask('project.list', {}))
    if (!listed || listed.code !== 'OK' || !listed.payload) throw new Error('Could not check existing local and UNION projects; nothing was attached')
    const existing = listed.payload.find(row => row.name === found.project && (row.type === undefined || ['STANDARD','LOCAL','UNION'].includes(row.type))
        || row.workspace === found.root && row.machine === machine && row.type !== 'DISJOINT')
    if (existing) return { root: found.root, project: existing.name }
    const attached = conversationReply('project.attach', await connection.ask('project.attach', {name: found.project, workspace: found.root, machine}))
    if (!attached || attached.code !== 'OK' || !attached.payload || attached.payload.type !== 'DISJOINT' || !clientProject(attached.payload.name)) throw new Error('The server did not acknowledge a client-only project; nothing was attached')
    return { ...found, project: attached.payload.name, displayName: found.project }
}

export async function discover(from: string): Promise<Marked | undefined> {
    let at = await realpath(from)
    for (;;) {
        const project = await markedName(at)
        if (project !== undefined) {
            return { root: at, project }
        }
        const disjoint = await markerText(at, 'plowshare')
        if (disjoint !== undefined) return {root: at, project: markerName(disjoint), kind:'DISJOINT'}
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
    if (clientProject(project)) {
        const text = await markerText(root, 'plowshare')
        if (text === undefined || markerName(text) !== projectLabel(project)) throw new Error('Client project manifest changed; nothing was marked')
        return {root, project, kind:'DISJOINT'}
    }
    const existing = await markedName(root)
    if (existing !== undefined && existing !== project) {
        throw new AlreadyMarked(root, existing, project)
    }
    if (existing === undefined) {
        await mkdir(join(root, DEFINITIONS), { recursive: true })
        await writeFile(join(root, DEFINITIONS, PROJECT_FILE), JSON.stringify({version:1,name:project},null,2) + '\n', { encoding: 'utf8', flag: 'wx' })
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

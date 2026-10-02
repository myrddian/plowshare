/**
 * What this machine lends a run: one root, read and written through a fence.
 *
 * <h2>`ClientEnforcer`'s rules, for the terminal client</h2>
 *
 * <p>The client is the enforcement point for its own files — the server does not
 * have this disk and must not guess about it — so every path is canonicalised
 * here, links included, and checked against the one root this session claimed.
 * Sentences are the Java client's where a model already knows how to act on them.
 *
 * <h2>Every answer is facts, never words</h2>
 *
 * <p>A write, an edit, a delete and a move answer with a `FileResult` and no
 * sentence, refused or not; so does every refusal of a read, a stat, a glob and a
 * search, and an outage — the same facts `ClientEnforcer` reports, held to the
 * same table (`file-results.json`), and worded on the server (spec 2026-09-30,
 * the file side reports facts; the server words them). This file holds no word a
 * model reads about a file. The one exception is a `run`'s own consent and
 * bounds, whose refusals are that spec's last step; its working directory's
 * fence is facts like every other path's.
 *
 * <h2>Two deliberate differences from the Java client</h2>
 *
 * <p><b>The harness may read the definitions; the model may not.</b> `FileAccess`
 * hides every dot-path below a root so that an agent's file tools cannot reach a
 * credential (TODO §13) — a rule about the model. A request marked
 * `purpose: "definitions"` is the server's harness, and it alone may read, stat
 * and glob the direct children of `.plowshare/agents` and `.plowshare/bots`,
 * because a person's own agents and bots were always meant to be definable here —
 * and `.plowshare/environment.yml`, the one file beside them, which the server reads
 * to resolve what `run` may do on this side.
 * An unmarked request — every model tool call — sees none of it, and nothing
 * under `.plowshare` is writable by anybody.
 *
 * <p><b>The hooks mark is the harness too, and narrower.</b> A request marked
 * `purpose: "hooks"` is the server snapshotting this session's local hooks when a log
 * opens (spec 2026-09-30-local-hooks-are-served): it may read, stat and glob the direct
 * children of `.plowshare/hooks` and nothing else hidden. This client runs none of it.
 *
 * <p><b>Conversion is server-side.</b> SOURCE streams fenced bytes without
 * interpretation. The server caches converted PDF text and handles images.
 * Legacy READ requests remain text-only for older servers.
 *
 * <h2>`run` asks this machine, not the server</h2>
 *
 * <p>The server resolves an environment and sends what it resolved; this client does
 * not take that on trust. <b>An attended TUI defaults to {@code ask}</b>: the server
 * has obtained its signed-in person's approval before the command reaches this door.
 * Its own `.plowshare/environment.yml`, read on every run, can explicitly turn local
 * execution off or relax it; its `server:` section is ignored. A shell is still
 * refused unless explicitly allowed.
 * The deadline and the output bound are the smaller of the request's and its own.
 * A `run` holds nothing up: the channel answers each request as it finishes, so a
 * long command and a read asked meanwhile cross, and `cancel` names the run to kill.
 *
 * <p>Relative paths resolve against the root, not against wherever this process
 * happens to be running — `bin/plowshare-talk` changes directory before it starts.
 */
import { constants } from 'node:fs'
import { createHash } from 'node:crypto'
import { lstat, mkdir, open, readFile, readdir, readlink, realpath, rename, stat, unlink, writeFile } from 'node:fs/promises'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import type { Answering } from 'plowshare-client-ts/binding/channel'
import { ASK, DEFAULT_SIDE, OFF, Unreadable, isShell, parseEnvironment, sideOff, sideWith } from 'plowshare-client-ts/binding/environment'
import type { Side } from 'plowshare-client-ts/binding/environment'
import {
    ABSOLUTE_PATTERN, CANCEL, DEFINITIONS, DELETE, DELETED, DESTINATION_EXISTS, DIRECTORY, EDIT, EXISTS, FAILED,
    GLOB, GREP, HIDDEN, HOOKS, INTERNAL, LINK, MISSING, MOVE, MOVED, NOT_REGULAR, NOT_TEXT, NOT_UTF8, NO_FILE,
    NO_PATH, NO_PATTERN, OUTSIDE, READ, REFUSED, REPLY_OK, REPLY_REFUSED, REPLY_UNAVAILABLE, RESULT_VERSION,
    ROOTS, ROOT_GONE, ROOT_NOT_DIRECTORY, RUN, SOURCE, SOURCE_CHUNK_BYTES, STAT, TOO_LARGE, TOO_MANY_MATCHES, UNAVAILABLE, UNCONVERTED,
    UNKNOWN_OP, UNLISTABLE, Unreplaced, Unservable, WRITE, WRITTEN, cut, find, globMatcher, lineCount, linesOf,
    sought, spellings, utf8Length, windowOf,
} from 'plowshare-client-ts/binding/files'
import { edit as editText, editedResult, refusalResult } from 'plowshare-client-ts/binding/editfacts'
import type { FileReply, FileRequest, FileResult, Found, Match, Needle, Refusal } from 'plowshare-client-ts/binding/files'
import { CommandRefused, runCommand } from './runner.ts'

export const MAX_FILE_BYTES = 8 * 1024 * 1024
export const MAX_GLOB_MATCHES = 10_000
export const DEFINITIONS_DIRECTORY = '.plowshare'
/** How many dangling links `canonical` follows before it stops, as the kernel's own ELOOP bound does. */
const MAX_LINK_HOPS = 40
const READABLE_DEFINITIONS: readonly string[] = ['agents', 'bots']
/** The one directory under `.plowshare` the hooks mark reads, one level deep. */
const HOOKS_DIRECTORY = 'hooks'
/** The one file directly under `.plowshare` the harness may read. */
export const ENVIRONMENT_FILE = 'environment.yml'

/** The attended TUI's default; the shared protocol and non-interactive clients stay off. */
const DEFAULT_OWN_SIDE: Side = { ...DEFAULT_SIDE, mode: ASK }
/** What the TUI reports to the server when the optional file is absent. */
const DEFAULT_ENVIRONMENT_LINES: readonly string[] = ['local:', '  mode: ask']

/** `reading` is a model's tool; `definitions` and `hooks` are the harness; `writing` is anybody. */
export type Purpose = 'reading' | 'definitions' | 'hooks' | 'writing'

/** Which kind of read a request is, by the harness's mark and nothing else. */
function readingFor(request: FileRequest): Purpose {
    if (request.purpose === DEFINITIONS) {
        return 'definitions'
    }
    return request.purpose === HOOKS ? 'hooks' : 'reading'
}

/**
 * Correctable by whoever asked: answered `refused` with its {@link FileResult},
 * which the server words — or, for a `run`'s own consent and bounds only, with
 * the sentence that still says why.
 */
class Refused extends Error {
    readonly result: FileResult | undefined

    constructor(sentence: string, result?: FileResult) {
        super(sentence)
        this.result = result
    }
}

/** A refusal as facts, and no sentence for anybody. */
function refusedWith(result: FileResult): Refused {
    return new Refused(`${result.kind}${result.reason === undefined ? '' : ` (${result.reason})`}`, result)
}

/** An {@link Unservable}'s facts, stamped with the op they answer. */
function stamped(op: string, facts: Refusal, more: Partial<FileResult> = {}): FileResult {
    return { version: RESULT_VERSION, kind: REFUSED, op, ...facts, ...more }
}

/** A rule whose only datum is the path — `FileResult.refused`. */
function ruled(op: string, reason: string, path: string | undefined): Refused {
    return refusedWith({ version: RESULT_VERSION, kind: REFUSED, op, reason, ...(path === undefined ? {} : { path }) })
}

function noFile(op: string, path: string): Refused {
    return refusedWith({ version: RESULT_VERSION, kind: NO_FILE, op, path })
}

function missing(op: string, argument: string): Refused {
    return refusedWith({ version: RESULT_VERSION, kind: REFUSED, op, reason: MISSING, argument })
}

function failed(op: string, path: string, trouble: unknown, to?: string): Refused {
    return refusedWith({
        version: RESULT_VERSION, kind: REFUSED, op, path, ...(to === undefined ? {} : { to }), reason: FAILED,
        detail: messageOf(trouble),
    })
}

/** Nothing the asker can correct — the root itself is gone: answered `unavailable`, with why. */
class Vanished extends Error {
    readonly result: FileResult

    constructor(result: FileResult) {
        super(`${result.kind} (${result.reason ?? ''})`)
        this.result = result
    }
}

export function enforcing(root: string, attendance: boolean | AbortSignal = true): Answering & { close(): void } {
    const attended = typeof attendance === 'boolean' ? attendance : true
    const lifetime = typeof attendance === 'boolean' ? undefined : attendance
    const defaults = attended ? DEFAULT_OWN_SIDE : DEFAULT_SIDE
    const environmentLines = attended ? DEFAULT_ENVIRONMENT_LINES : ['local:', '  mode: off']
    /** Every `run` still going, by its request id, which is what a `cancel` names. */
    const running = new Map<string, AbortController>()
    const answer: Answering = async (request) => {
        try {
            if (lifetime?.aborted) throw new Refused("this file presence has been withdrawn")
            return await answered(root, request, running, defaults, environmentLines, lifetime)
        } catch (trouble) {
            // Facts on every path but a run's own consent and bounds.
            if (trouble instanceof Refused && trouble.result !== undefined) {
                return { id: request.id, outcome: REPLY_REFUSED, result: trouble.result }
            }
            if (trouble instanceof Unservable) {
                return { id: request.id, outcome: REPLY_REFUSED, result: stamped(request.op, trouble.facts) }
            }
            if (trouble instanceof Refused || trouble instanceof CommandRefused) {
                return { id: request.id, outcome: REPLY_REFUSED, sentence: trouble.message }
            }
            if (trouble instanceof Vanished) {
                return { id: request.id, outcome: REPLY_UNAVAILABLE, result: trouble.result }
            }
            return {
                id: request.id, outcome: REPLY_UNAVAILABLE,
                result: { version: RESULT_VERSION, kind: UNAVAILABLE, op: request.op, reason: INTERNAL, detail: String(trouble) },
            }
        }
    }
    return Object.assign(answer, { close: () => { for (const control of running.values()) control.abort() } })
}

async function answered(
        root: string, request: FileRequest, running: Map<string, AbortController>, defaults: Side, environmentLines: readonly string[], lifetime?: AbortSignal): Promise<FileReply> {
    await reachable(root, request.op)
    const id = request.id
    switch (request.op) {
        case ROOTS:
            return { id, outcome: REPLY_OK, paths: [root] }
        case SOURCE:
            return await source(root, request)
        case READ: {
            const { offset, limit } = windowOf(request)
            const target = await permitted(root, request.path, readingFor(request), READ)
            const whenAbsent = request.purpose === DEFINITIONS
                    && target === join(root, DEFINITIONS_DIRECTORY, ENVIRONMENT_FILE)
                ? environmentLines
                : undefined
            const all = await lines(target, request.path ?? '', READ, whenAbsent)
            try {
                return { id, outcome: REPLY_OK, span: cut(all, offset, limit) }
            } catch (trouble) {
                // A line no window can carry: `ClientEnforcer` names the file it is in.
                if (trouble instanceof Unservable && request.path !== undefined) {
                    throw refusedWith(stamped(READ, trouble.facts, { path: request.path }))
                }
                throw trouble
            }
        }
        case STAT: {
            const target = await permitted(root, request.path, readingFor(request), STAT)
            const total = (await lines(target, request.path ?? '', STAT)).length
            return {
                id, outcome: REPLY_OK,
                span: { lines: [], offset: 0, totalLines: total, more: total > 0, stoppedBy: total > 0 ? 'lines' : 'end' },
            }
        }
        case GLOB:
            return { id, outcome: REPLY_OK, paths: await glob(root, request.pattern, readingFor(request)) }
        case GREP:
            return { id, outcome: REPLY_OK, found: await grep(root, request) }
        case WRITE:
            return { id, outcome: REPLY_OK, result: await write(root, request) }
        case EDIT:
            // Facts only (`FileReply.changed`): the server says "Replaced the one
            // occurrence…" and words the lines this reports.
            return { id, outcome: REPLY_OK, result: await edit(root, request) }
        case DELETE:
            return { id, outcome: REPLY_OK, result: await remove(root, request) }
        case MOVE:
            return { id, outcome: REPLY_OK, result: await move(root, request) }
        case RUN:
            return await run(root, request, running, defaults, lifetime)
        case CANCEL:
            // Answered ok whether or not it was still running: a run that already
            // ended has nothing left to kill, and its own answer says how it ended.
            if (request.path !== undefined) {
                running.get(request.path)?.abort()
            }
            return { id, outcome: REPLY_OK }
        default:
            throw ruled(request.op, UNKNOWN_OP, undefined)
    }
}

async function reachable(root: string, op: string): Promise<void> {
    let about
    try {
        about = await stat(root)
    } catch {
        throw new Vanished({ version: RESULT_VERSION, kind: UNAVAILABLE, op, reason: ROOT_GONE, path: root })
    }
    if (!about.isDirectory()) {
        throw new Vanished({ version: RESULT_VERSION, kind: UNAVAILABLE, op, reason: ROOT_NOT_DIRECTORY, path: root })
    }
}

/**
 * Whether `candidate` — already canonical — may be reached for `purpose`.
 *
 * <p>The root itself is readable (a grep may name it) and never writable.
 */
export function allows(root: string, candidate: string, purpose: Purpose): boolean {
    if (candidate === root) {
        return purpose !== 'writing'
    }
    const prefix = root.endsWith(sep) ? root : root + sep
    if (!candidate.startsWith(prefix)) {
        return false
    }
    const below = relative(root, candidate).split(sep)
    if (!below.some((name) => name.startsWith('.'))) {
        return true
    }
    // THE HOOKS MARK (spec 2026-09-30-local-hooks-are-served decision 2): the server snapshots a
    // log's local hooks with it, and it reaches exactly `.plowshare/hooks/<one name>` — nothing
    // deeper, nothing hidden, and none of the definitions beside it.
    if (purpose === 'hooks') {
        return below.length === 3
            && below[0] === DEFINITIONS_DIRECTORY
            && below[1] === HOOKS_DIRECTORY
            && !(below[2] ?? '.').startsWith('.')
    }
    // THE FENCE IS THE MODEL'S. Only the harness's marked request gets past it,
    // and only into the two definition directories, one level deep.
    if (purpose === 'definitions' && below.length === 2
        && below[0] === DEFINITIONS_DIRECTORY && below[1] === ENVIRONMENT_FILE) {
        return true
    }
    return purpose === 'definitions'
        && below.length === 3
        && below[0] === DEFINITIONS_DIRECTORY
        && READABLE_DEFINITIONS.includes(below[1] ?? '')
        && !(below[2] ?? '.').startsWith('.')
}

/**
 * `FileAccess.canonical`: links resolved as far as the path exists, and whatever
 * does not exist yet appended to the part that does — so a file about to be
 * written is judged by where its directory really is.
 *
 * <p><b>A link that leads nowhere is followed anyway.</b> `realpath` fails on a
 * dangling link exactly as it fails on a name that does not exist, and treating
 * the two alike judged `d/x.txt` — with `d` a link to a directory outside that
 * nobody has made yet — as inside the root, leaving only `mkdir` between a write
 * and the far end of the link. So a component that does not resolve is asked
 * whether it is a link, and if it is, its target is canonicalised in its place:
 * the fence then sees where the write would really land and refuses it as the
 * outside path it is. Bounded, since a link can name itself.
 */
export async function canonical(path: string, hops = 0): Promise<string> {
    const absolute = resolve(path)
    const below: string[] = []
    let resolving = absolute
    for (;;) {
        try {
            const real = await realpath(resolving)
            return below.length === 0 ? real : join(real, ...below)
        } catch {
            const link = await lstat(resolving).catch(() => undefined)
            if (link?.isSymbolicLink() === true && hops < MAX_LINK_HOPS) {
                const target = resolve(dirname(resolving), await readlink(resolving))
                return canonical(below.length === 0 ? target : join(target, ...below), hops + 1)
            }
            const parent = dirname(resolving)
            if (parent === resolving) {
                return absolute
            }
            below.unshift(relative(parent, resolving))
            resolving = parent
        }
    }
}

/**
 * The canonical path `named` reaches, if `purpose` may reach it.
 *
 * <p>Refused as hidden when it is under the root and the fence still says no —
 * a model that can see `/root/.env` is under `/root` must not be told it is
 * outside — and as a directory when it is the root itself asked to be written.
 *
 * @param op the op, which the refusal's facts name
 */
async function permitted(
    root: string, named: string | undefined, purpose: Purpose, op: string,
): Promise<string> {
    if (named === undefined) {
        throw ruled(op, NO_PATH, undefined)
    }
    const candidate = await canonical(isAbsolute(named) ? named : resolve(root, named))
    if (!allows(root, candidate, purpose)) {
        if (candidate === root) {
            throw ruled(op, DIRECTORY, named)
        }
        const reason = candidate.startsWith(root.endsWith(sep) ? root : root + sep) ? HIDDEN : OUTSIDE
        throw refusedWith({ version: RESULT_VERSION, kind: REFUSED, op, path: named, reason, roots: [root] })
    }
    return candidate
}

/**
 * The format of a file this client will not read as text, by its first bytes —
 * `PDF`, or a picture as `ImageFormat.declared()` spells it — for the facts the
 * server words ("a PDF", "a png image").
 */
export function binaryKind(bytes: Uint8Array): string | undefined {
    const starts = (...signature: number[]): boolean =>
        signature.every((byte, at) => bytes[at] === byte)
    if (starts(0x25, 0x50, 0x44, 0x46)) {
        return 'PDF'
    }
    if (starts(0x89, 0x50, 0x4e, 0x47)) {
        return 'png'
    }
    if (starts(0xff, 0xd8, 0xff)) {
        return 'jpeg'
    }
    if (starts(0x47, 0x49, 0x46, 0x38)) {
        return 'gif'
    }
    if (starts(0x52, 0x49, 0x46, 0x46) && bytes[8] === 0x57 && bytes[9] === 0x45
        && bytes[10] === 0x42 && bytes[11] === 0x50) {
        return 'webp'
    }
    return undefined
}

/** Disk bytes only. Every range rechecks the fence; no client conversion/cache.
 * Metadata hashes the current bytes, so a cached conversion still requires fresh
 * authority and content. The server checks the hash of an assembled transfer. */
async function source(root: string, request: FileRequest): Promise<FileReply> {
    const named = request.path ?? '', target = await permitted(root, request.path, readingFor(request), READ)
    let handle
    try {
        handle = await open(target, constants.O_RDONLY | constants.O_NOFOLLOW)
        const before = await handle.stat()
        if (!before.isFile()) throw ruled(READ, before.isDirectory() ? DIRECTORY : NOT_REGULAR, named)
        if (before.size > MAX_FILE_BYTES) throw refusedWith({ version: RESULT_VERSION, kind: REFUSED, op: READ, path: named, reason: TOO_LARGE, bytes: before.size, limit: MAX_FILE_BYTES })
        const buffer = Buffer.alloc(SOURCE_CHUNK_BYTES)
        let reply: FileReply
        if (request.offset === undefined && request.limit === undefined) {
            const hash = createHash('sha256')
            let offset = 0
            while (offset < before.size) {
                const { bytesRead } = await handle.read(buffer, 0, Math.min(buffer.length, before.size - offset), offset)
                if (!bytesRead) throw ruled(READ, FAILED, named)
                hash.update(buffer.subarray(0, bytesRead)); offset += bytesRead
            }
            reply = { id: request.id, outcome: REPLY_OK, source: { size: before.size, sha256: hash.digest('hex') } }
        } else {
            const offset = request.offset, limit = request.limit
            if (offset === undefined || !Number.isSafeInteger(offset) || offset < 0 || offset > before.size || limit === undefined || !Number.isSafeInteger(limit) || limit < 1 || limit > SOURCE_CHUNK_BYTES) throw ruled(READ, FAILED, named)
            const { bytesRead } = await handle.read(buffer, 0, Math.min(limit, before.size - offset), offset)
            reply = { id: request.id, outcome: REPLY_OK, source: { size: before.size, offset, data: buffer.subarray(0, bytesRead).toString('base64') } }
        }
        const after = await handle.stat()
        if (before.size !== after.size || before.mtimeMs !== after.mtimeMs || before.ctimeMs !== after.ctimeMs || await permitted(root, request.path, readingFor(request), READ) !== target) throw ruled(READ, FAILED, named)
        return reply
    } catch (trouble) {
        if (trouble instanceof Refused) throw trouble
        if (codeOf(trouble) === 'ENOENT') throw noFile(READ, named)
        throw failed(READ, named, trouble)
    } finally { await handle?.close() }
}

async function lines(
    target: string, named: string, op: string, whenAbsent?: readonly string[],
): Promise<string[]> {
    let about
    try {
        about = await stat(target)
    } catch (trouble) {
        if (codeOf(trouble) === 'ENOENT' && whenAbsent !== undefined) {
            return [...whenAbsent]
        }
        if (codeOf(trouble) === 'ENOENT') {
            throw noFile(op, named)
        }
        throw failed(op, named, trouble)
    }
    if (!about.isFile()) {
        throw ruled(op, about.isDirectory() ? DIRECTORY : NOT_REGULAR, named)
    }
    if (about.size > MAX_FILE_BYTES) {
        throw refusedWith({
            version: RESULT_VERSION, kind: REFUSED, op, path: named, reason: TOO_LARGE, bytes: about.size,
            limit: MAX_FILE_BYTES,
        })
    }
    const bytes = await readFile(target)
    const format = binaryKind(bytes)
    if (format !== undefined) {
        throw refusedWith({ version: RESULT_VERSION, kind: NOT_TEXT, op, path: named, reason: UNCONVERTED, format })
    }
    try {
        return linesOf(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
    } catch {
        throw refusedWith({ version: RESULT_VERSION, kind: NOT_TEXT, op, path: named, reason: NOT_UTF8 })
    }
}

/**
 * Whether the walk goes into the directory at `path` (names below the root) for
 * this purpose: never into a hidden one, except the harness into `.plowshare`
 * and its two definition directories.
 */
function descends(path: readonly string[], purpose: Purpose): boolean {
    const name = path[path.length - 1] ?? ''
    if (!name.startsWith('.')) {
        return true
    }
    if (purpose === 'hooks') {
        return path[0] === DEFINITIONS_DIRECTORY
            && (path.length === 1 || (path.length === 2 && path[1] === HOOKS_DIRECTORY))
    }
    return purpose === 'definitions' && path[0] === DEFINITIONS_DIRECTORY
        && (path.length === 1 || (path.length === 2 && READABLE_DEFINITIONS.includes(path[1] ?? '')))
}

/**
 * The directories a pattern names before its first wildcard — `.plowshare/bots`
 * for `.plowshare/bots/*`, nothing for `**` — so a walk can start there.
 *
 * <p>A segment holding `* ? [ {` or an escape is where the literal part ends, and
 * the last segment is a file name, never a directory to start in. A `.`, `..` or
 * empty segment gives up and starts at the root: those are spellings the match
 * against root-relative paths already answers, and a start computed from them
 * could only be wrong.
 */
function literalDirectories(pattern: string): string[] {
    const segments = pattern.split('/')
    const literal: string[] = []
    for (const segment of segments.slice(0, -1)) {
        if (/[*?[{\\]/.test(segment)) {
            break
        }
        if (segment === '' || segment === '.' || segment === '..') {
            return []
        }
        literal.push(segment)
    }
    return literal
}

/**
 * Every regular file under `start` this purpose may read, in name order. Links
 * are not followed.
 *
 * <h2>From where the question starts, not from the root</h2>
 *
 * <p><b>Measured, and why this is not a tidying:</b> the harness's
 * `.plowshare/bots/*` glob walked the whole root — `node_modules` included — on
 * every resolution, to keep the handful of names under one directory. `start` is
 * the pattern's literal directories for a glob and the directory named for a
 * grep; the rules for what is hidden are applied to those directories on the way
 * down exactly as the walk applies them below, and a start that is a link, a file,
 * or not there at all has nothing under it to find.
 *
 * <h2>A directory it cannot read is skipped, below the start</h2>
 *
 * <p>It used to refuse the whole answer, which meant one unreadable directory
 * anywhere in a tree — a root-owned cache, a mounted volume — refused every glob
 * and every sweep in it, and `ChannelDefinitions` read that refusal as "no client
 * definitions" with nothing said. A directory the question starts in is still a
 * refusal: there the whole answer is what could not be read.
 */
async function files(
    root: string, purpose: Purpose = 'reading', below: readonly string[] = [], op: string = GLOB,
): Promise<string[]> {
    const found: string[] = []
    let start = root
    for (let depth = 0; depth < below.length; depth += 1) {
        const path = below.slice(0, depth + 1)
        start = join(start, below[depth] ?? '')
        const about = await lstat(start).catch(() => undefined)
        if (about?.isDirectory() !== true || !descends(path, purpose)) {
            return found
        }
    }
    const walk = async (directory: string, at: readonly string[]): Promise<void> => {
        let entries
        try {
            entries = await readdir(directory, { withFileTypes: true })
        } catch (trouble) {
            if (directory === start) {
                throw refusedWith({
                    version: RESULT_VERSION, kind: REFUSED, op, path: start, reason: UNLISTABLE,
                    detail: messageOf(trouble),
                })
            }
            return
        }
        entries.sort((one, other) => (one.name < other.name ? -1 : one.name > other.name ? 1 : 0))
        for (const entry of entries) {
            const path = [...at, entry.name]
            const full = join(directory, entry.name)
            if (entry.isDirectory()) {
                if (descends(path, purpose)) {
                    await walk(full, path)
                }
            } else if (entry.isFile() && allows(root, full, purpose)) {
                found.push(full)
            }
        }
    }
    await walk(start, below)
    return found
}

async function glob(root: string, pattern: string | undefined, purpose: Purpose): Promise<string[]> {
    if (pattern === undefined || pattern.trim() === '') {
        throw ruled(GLOB, NO_PATTERN, undefined)
    }
    if (pattern.startsWith('/')) {
        throw refusedWith({ version: RESULT_VERSION, kind: REFUSED, op: GLOB, reason: ABSOLUTE_PATTERN, pattern })
    }
    let matchers
    try {
        matchers = spellings(pattern).map(globMatcher)
    } catch (trouble) {
        // One expanded spelling is what failed; the pattern the caller wrote is what it names.
        if (trouble instanceof Unservable) {
            throw refusedWith(stamped(GLOB, trouble.facts, { pattern }))
        }
        throw trouble
    }
    const hits: string[] = []
    for (const file of await files(root, purpose, literalDirectories(pattern))) {
        const spelled = relative(root, file).split(sep).join('/')
        if (!matchers.some((matches) => matches(spelled))) {
            continue
        }
        if (hits.length >= MAX_GLOB_MATCHES) {
            throw refusedWith({ version: RESULT_VERSION, kind: REFUSED, op: GLOB, reason: TOO_MANY_MATCHES, limit: MAX_GLOB_MATCHES })
        }
        hits.push(file)
    }
    return hits
}

async function grep(root: string, request: FileRequest): Promise<Found> {
    const needle = sought(request)
    const into: Match[] = []
    let under = root
    if (request.path !== undefined) {
        const target = await permitted(root, request.path, 'reading', GREP)
        const about = await stat(target).catch(() => undefined)
        if (about?.isDirectory() !== true) {
            const capped = find(target, await lines(target, request.path, GREP), needle, into)
            return { matches: into, stoppedBy: capped ? 'matches' : 'end' }
        }
        under = target
    }
    const capped = await sweep(root, under, needle, into)
    return { matches: into, stoppedBy: capped ? 'matches' : 'end' }
}

/** `under` is canonical and already through the fence, so the walk starts there. */
async function sweep(root: string, under: string, needle: Needle, into: Match[]): Promise<boolean> {
    const below = under === root ? [] : relative(root, under).split(sep)
    for (const file of await files(root, 'reading', below, GREP)) {
        let read: string[]
        try {
            read = await lines(file, file, GREP)
        } catch {
            continue
        }
        if (find(file, read, needle, into)) {
            return true
        }
    }
    return false
}

async function write(root: string, request: FileRequest): Promise<FileResult> {
    if (request.content === undefined) {
        throw missing(WRITE, 'content')
    }
    const named = request.path ?? ''
    const target = await permitted(root, request.path, 'writing', WRITE)
    const about = await lstat(target).catch(() => undefined)
    if (about?.isDirectory() === true) {
        throw ruled(WRITE, DIRECTORY, named)
    }
    if (about?.isSymbolicLink() === true) {
        // This client writes through no link whose end it has not checked.
        throw ruled(WRITE, LINK, named)
    }
    const creating = request.createOnly === true
    if (creating && about !== undefined) {
        throw ruled(WRITE, EXISTS, named)
    }
    try {
        await mkdir(dirname(target), { recursive: true })
        // `wx` is what makes create-only hold: the check above is a courtesy, and a
        // file made between it and this line is refused here by the kernel.
        await writeFile(target, request.content, { encoding: 'utf8', flag: creating ? 'wx' : 'w' })
    } catch (trouble) {
        if (creating && codeOf(trouble) === 'EEXIST') {
            throw ruled(WRITE, EXISTS, named)
        }
        throw failed(WRITE, named, trouble)
    }
    // Counted from what was sent as the Java client counts it: its UTF-8 length,
    // and its lines as `String.lines()` splits them.
    return {
        version: RESULT_VERSION, kind: WRITTEN, op: WRITE, path: named,
        bytes: utf8Length(request.content), lines: linesOf(request.content).length,
    }
}

/**
 * The one file an edit, a delete or a move acts on: there, regular, and not a link.
 *
 * <p><b>The link is asked about by the path as it was spelled</b>, not only by the
 * canonical one `permitted` returns. Canonicalising resolves a link that stays
 * inside the root to the file it names, so a check on `target` alone would delete
 * or move that file and leave the link behind — which is not the file the model
 * named, and the spec refuses a link outright.
 *
 * @returns the file's size in bytes
 */
async function regularFile(root: string, named: string, target: string, op: string): Promise<number> {
    const spelled = await lstat(isAbsolute(named) ? named : resolve(root, named)).catch(() => undefined)
    let about
    try {
        about = await lstat(target)
    } catch (trouble) {
        if (spelled?.isSymbolicLink() === true) {
            throw ruled(op, LINK, named)
        }
        if (codeOf(trouble) === 'ENOENT') {
            throw noFile(op, named)
        }
        throw failed(op, named, trouble)
    }
    if (spelled?.isSymbolicLink() === true || about.isSymbolicLink()) {
        throw ruled(op, LINK, named)
    }
    if (about.isDirectory()) {
        throw ruled(op, DIRECTORY, named)
    }
    if (!about.isFile()) {
        throw ruled(op, NOT_REGULAR, named)
    }
    return about.size
}

/**
 * `Replacement.edit` against the file's own bytes, so its CRLFs and its last
 * newline — which a read never carried to the server — are exactly as they were.
 *
 * <p>Decoded strictly and with the byte-order mark kept: `TextDecoder` drops a
 * BOM by default, and a file that began with one would lose it on the way back.
 *
 * <p><b>Answered with facts</b>, as the Java client answers: where the new text
 * is and the lines around it, which the server shows the model so its next edit
 * is built from the file as it is; for an `old` that is not there, what is
 * nearest it; for a file that is not there, only that. Measured live on
 * 2026-09-30, this client's own words for these had fallen behind the Java
 * client's — the server's are the only words now.
 */
async function edit(root: string, request: FileRequest): Promise<FileResult> {
    if (request.replacing === undefined) {
        throw missing(EDIT, 'replacing')
    }
    if (request.content === undefined) {
        throw missing(EDIT, 'content')
    }
    const named = request.path ?? ''
    const target = await permitted(root, request.path, 'writing', EDIT)
    const size = await regularFile(root, named, target, EDIT)
    if (size > MAX_FILE_BYTES) {
        throw refusedWith({
            version: RESULT_VERSION, kind: REFUSED, op: EDIT, path: named, reason: TOO_LARGE,
            bytes: size, limit: MAX_FILE_BYTES,
        })
    }
    let text
    try {
        text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await readFile(target))
    } catch (trouble) {
        if (trouble instanceof TypeError) {
            throw refusedWith({ version: RESULT_VERSION, kind: NOT_TEXT, op: EDIT, path: named, reason: NOT_UTF8 })
        }
        if (codeOf(trouble) === 'ENOENT') {
            throw noFile(EDIT, named)
        }
        throw failed(EDIT, named, trouble)
    }
    let edited
    try {
        edited = editText(text, request.replacing, request.content)
    } catch (trouble) {
        if (trouble instanceof Unreplaced) {
            throw refusedWith(refusalResult(trouble, named))
        }
        throw trouble
    }
    // No O_CREAT, as the Java client opens it without CREATE: a file removed
    // between the read and this write is refused as absent rather than brought
    // back holding only the edit. O_TRUNC and O_NOFOLLOW for its other two options.
    let handle
    try {
        handle = await open(target, constants.O_WRONLY | constants.O_TRUNC | (constants.O_NOFOLLOW ?? 0))
    } catch (trouble) {
        if (codeOf(trouble) === 'ENOENT') {
            throw noFile(EDIT, named)
        }
        throw failed(EDIT, named, trouble)
    }
    try {
        await handle.writeFile(edited.text, 'utf8')
    } catch (trouble) {
        throw failed(EDIT, named, trouble)
    } finally {
        await handle.close()
    }
    return editedResult(edited, named)
}

/**
 * One file removed, answered with how big it was — and how many lines, counted
 * from its bytes as a read counts them, when it is small enough to load.
 */
async function remove(root: string, request: FileRequest): Promise<FileResult> {
    const named = request.path ?? ''
    const target = await permitted(root, request.path, 'writing', DELETE)
    const size = await regularFile(root, named, target, DELETE)
    let lines: number | undefined
    try {
        if (size <= MAX_FILE_BYTES) {
            lines = lineCount(await readFile(target))
        }
        await unlink(target)
    } catch (trouble) {
        if (codeOf(trouble) === 'ENOENT') {
            throw noFile(DELETE, named)
        }
        throw failed(DELETE, named, trouble)
    }
    return {
        version: RESULT_VERSION, kind: DELETED, op: DELETE, path: named, bytes: size,
        ...(lines === undefined ? {} : { lines }),
    }
}

/**
 * One file renamed, never over another: both paths through the fence, and the
 * directories above the destination made. The source is asked about first and
 * the destination after, in `ClientEnforcer`'s order, so the two clients refuse
 * the same request for the same reason.
 *
 * <p>The destination is checked and then renamed onto, which is `Files.move`
 * without `REPLACE_EXISTING` as the Java client has it — a file made in between
 * is replaced. Both paths are this session's and the window is one syscall.
 */
async function move(root: string, request: FileRequest): Promise<FileResult> {
    const named = request.path ?? ''
    const source = await permitted(root, request.path, 'writing', MOVE)
    const size = await regularFile(root, named, source, MOVE)
    if (request.to === undefined) {
        throw missing(MOVE, 'to')
    }
    const to = request.to
    const destination = await permitted(root, to, 'writing', MOVE)
    const spelled = isAbsolute(to) ? to : resolve(root, to)
    const there = await lstat(destination).catch(() => undefined)
        ?? await lstat(spelled).catch(() => undefined)
    if (there !== undefined) {
        throw refusedWith({
            version: RESULT_VERSION, kind: REFUSED, op: MOVE, path: named, to, reason: DESTINATION_EXISTS,
        })
    }
    try {
        await mkdir(dirname(destination), { recursive: true })
        await rename(source, destination)
    } catch (trouble) {
        if (codeOf(trouble) === 'ENOENT') {
            throw noFile(MOVE, named)
        }
        throw failed(MOVE, named, trouble, to)
    }
    return { version: RESULT_VERSION, kind: MOVED, op: MOVE, path: named, to, bytes: size }
}

/**
 * What this machine's own file lets a run do: its `local:` over the attended TUI default, its
 * `server:` ignored — that section is the server's to decide, not this client's.
 *
 * <p>Read straight from disk rather than through the fence, which keeps the file
 * from every model and has no reason to keep it from this client itself.
 */
async function ownSide(root: string, defaults: Side): Promise<{ side: Side; unreadable?: string }> {
    let text
    try {
        text = await readFile(join(root, DEFINITIONS_DIRECTORY, ENVIRONMENT_FILE), 'utf8')
    } catch (trouble) {
        if (codeOf(trouble) === 'ENOENT') {
            return { side: defaults }
        }
        return { side: sideOff(defaults), unreadable: messageOf(trouble) }
    }
    try {
        return { side: sideWith(defaults, parseEnvironment(text).local) }
    } catch (trouble) {
        if (trouble instanceof Unreadable) {
            return { side: sideOff(defaults), unreadable: trouble.message }
        }
        throw trouble
    }
}

/** The smaller of what was asked and what this side allows; this side's when nothing usable was asked. */
function bounded(asked: number | undefined, own: number): number {
    return asked !== undefined && Number.isFinite(asked) && asked > 0 ? Math.min(asked, own) : own
}

/**
 * `run`: consent from this machine's own file, the working directory through the
 * fence, and then the command.
 */
async function run(root: string, request: FileRequest, running: Map<string, AbortController>, defaults: Side, lifetime?: AbortSignal): Promise<FileReply> {
    const { side, unreadable } = await ownSide(root, defaults)
    if (unreadable !== undefined) {
        throw new Refused(`this machine's .plowshare/environment.yml does not allow commands to run`
            + ` here; it could not be read (${unreadable}), so its local mode is off`)
    }
    if (side.mode === OFF) {
        throw new Refused("this machine's .plowshare/environment.yml does not allow commands to run"
            + ' here; its local mode is off')
    }
    const argv = request.argv ?? []
    if (isShell(argv[0]) && !side.shells) {
        throw new Refused(`'${argv[0] ?? ''}' is a shell, and this machine's .plowshare/environment.yml`
            + ' does not allow shells here; its local shells is false')
    }
    const named = request.path ?? ''
    const cwd = await permitted(root, request.path, 'reading', RUN)
    const about = await stat(cwd).catch(() => undefined)
    if (about?.isDirectory() !== true) {
        throw new Refused(`the working directory ${named} is not a directory on this machine`)
    }
    if (lifetime?.aborted) throw new Refused("this file presence has been withdrawn")
    const cancel = new AbortController()
    const withdrawn = () => cancel.abort()
    lifetime?.addEventListener('abort', withdrawn, { once: true })
    running.set(request.id, cancel)
    try {
        const outcome = await runCommand({
            argv,
            cwd,
            env: request.env ?? {},
            // Only what this machine's own file also names: a server may narrow which
            // host variables a command sees, and may not reach for one this file did not.
            inherit: (request.inherit ?? []).filter((name) => side.inherit.includes(name)),
            timeoutMillis: bounded(request.timeoutMillis, side.timeoutMillis),
            outputBytes: bounded(request.outputBytes, side.outputBytes),
            ...(request.stdin === undefined ? {} : { stdin: request.stdin }),
        }, process.env, cancel.signal)
        return {
            id: request.id,
            outcome: REPLY_OK,
            exitCode: outcome.exitCode,
            timedOut: outcome.timedOut,
            stdout: outcome.stdout,
            stdoutCut: outcome.stdoutCut,
            stderr: outcome.stderr,
            stderrCut: outcome.stderrCut,
            millis: outcome.millis,
        }
    } finally {
        lifetime?.removeEventListener('abort', withdrawn)
        running.delete(request.id)
    }
}

function codeOf(trouble: unknown): string | undefined {
    return typeof trouble === 'object' && trouble !== null && 'code' in trouble
        ? String((trouble as { code: unknown }).code)
        : undefined
}

function messageOf(trouble: unknown): string {
    return trouble instanceof Error ? trouble.message : String(trouble)
}

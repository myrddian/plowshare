import { spawn } from 'node:child_process'
import { rm } from 'node:fs/promises'
import { join } from 'node:path'

/**
 * Git against a union's shadow repository — `.plowshare/sync.git`, with the
 * project directory as its work tree. The project's own `.git` is never read or
 * written: `GIT_DIR` points elsewhere, and `/.git/` is excluded (rules.ts).
 * Spec 2026-09-14-a-project-can-be-a-union §3.3.
 */

/** Git 2.50 first makes ort honor `-X no-renames`, preserving local edits at renamed paths. */
export const MIN_GIT: readonly [number, number] = [2, 50]

export interface GitResult {
    readonly code: number
    readonly stdout: Buffer
    readonly stderr: string
}

export class GitFailed extends Error {
    readonly args: readonly string[]
    readonly result: GitResult

    constructor(args: readonly string[], result: GitResult) {
        super(`git ${args.join(' ')} failed (${result.code}): ${result.stderr.trim()}`)
        this.args = args
        this.result = result
    }
}

export interface RunOptions {
    readonly input?: string | Buffer
    readonly env?: Readonly<Record<string, string>>
    /**
     * How long git may run before it is killed and answered as exit 124. git
     * has no low-speed limit of its own, so without this a stalled hub holds a
     * sync — and a quit waiting on it — forever. Default {@link GIT_TIMEOUT_MS}.
     */
    readonly timeoutMs?: number
}

export const GIT_TIMEOUT_MS = 120_000
/** The exit code a killed-for-time git is answered with, as `timeout(1)` does. */
export const TIMED_OUT = 124

export interface Git {
    run(args: readonly string[], options?: RunOptions): Promise<GitResult>
    ok(args: readonly string[], options?: RunOptions): Promise<string>
}

export interface ShadowPaths {
    readonly root: string
    readonly gitDir: string
}

export function shadowPaths(root: string): ShadowPaths {
    return { root, gitDir: join(root, '.plowshare', 'sync.git') }
}

/** Commands that talk to the hub, and so carry the bearer token. */
const REMOTE = new Set(['fetch', 'push', 'ls-remote'])

export function redacted(text: string): string {
    return text.replace(/Bearer\s+\S+/g, 'Bearer [redacted]')
}

export function shadowGit(paths: ShadowPaths, bearer?: () => Promise<string>, signal?: AbortSignal): Git {
    const run = async (args: readonly string[], options: RunOptions = {}): Promise<GitResult> => {
        signal?.throwIfAborted()
        // Build environment in security order:
        // (1) Copy string-valued process.env
        // (2) Merge options.env (allow caller to provide vars, but...)
        // (3) Delete trace variables (prevent caller/process from enabling tracing)
        // (4) Set security-critical variables last (prevent any earlier source from overriding them)

        const env: Record<string, string> = {}

        // (1) Copy string-valued process.env
        for (const [key, value] of Object.entries(process.env)) {
            if (typeof value === 'string') {
                env[key] = value
            }
        }

        // (2) Merge caller-provided env vars
        if (options.env !== undefined) {
            Object.assign(env, options.env)
        }

        // (3) Delete trace variables that could leak the token
        for (const key of Object.keys(env)) {
            if (key.startsWith('GIT_TRACE') || key === 'GIT_CURL_VERBOSE' || key.startsWith('GIT_CURL_VERBOSE_')) {
                delete env[key]
            }
        }

        // (4) Set security-critical variables last; nothing can override them
        env.GIT_DIR = paths.gitDir
        env.GIT_WORK_TREE = paths.root
        env.GIT_TERMINAL_PROMPT = '0'

        // Config is passed through environment counter only. Never through config files.
        // This ensures the token is only in memory and preserves the host's proxy/CA config.
        const configCount = bearer !== undefined && REMOTE.has(args[0] ?? '') ? 4 : 3
        env.GIT_CONFIG_COUNT = String(configCount)
        env.GIT_CONFIG_KEY_0 = 'commit.gpgsign'
        env.GIT_CONFIG_VALUE_0 = 'false'
        env.GIT_CONFIG_KEY_1 = 'tag.gpgsign'
        env.GIT_CONFIG_VALUE_1 = 'false'
        env.GIT_CONFIG_KEY_2 = 'core.hooksPath'
        env.GIT_CONFIG_VALUE_2 = join(paths.gitDir, 'hooks')

        if (bearer !== undefined && REMOTE.has(args[0] ?? '')) {
            // The token goes in through the environment of this one child process
            // and is never written into a git config file.
            env.GIT_CONFIG_KEY_3 = 'http.extraHeader'
            env.GIT_CONFIG_VALUE_3 = `Authorization: Bearer ${await bearer()}`
        }

        const timeoutMs = options.timeoutMs ?? GIT_TIMEOUT_MS
        signal?.throwIfAborted()
        return new Promise((resolve, reject) => {
            const child = spawn('git', args, { cwd: paths.root, env, detached: process.platform !== 'win32', stdio: ['pipe', 'pipe', 'pipe'] })
            const out: Buffer[] = []
            const err: Buffer[] = []
            let settled = false
            // ANSWERED AT THE KILL, not at `close`: a grandchild git started (a
            // remote helper, an editor) can hold the pipes open after git dies.
            const kill = (reason: string): void => {
                if (settled) return
                settled = true
                clearTimeout(timer)
                signal?.removeEventListener('abort', aborted)
                try {
                    if (process.platform !== 'win32' && child.pid !== undefined) process.kill(-child.pid, 'SIGKILL')
                    else child.kill('SIGKILL')
                } catch { child.kill('SIGKILL') }
                child.stdin.destroy(); child.stdout.destroy(); child.stderr.destroy()
                // A git killed mid-write leaves index.lock, and every later git in this shadow
                // would refuse to start until someone removed it by hand. This shadow's gits run
                // one at a time (the syncer's queue), so the lock can only be the killed one's.
                void rm(join(paths.gitDir, 'index.lock'), { force: true }).catch(() => undefined).then(() => {
                    resolve({
                        code: TIMED_OUT,
                        stdout: Buffer.concat(out),
                        stderr: `${redacted(Buffer.concat(err).toString('utf8'))}${reason}`,
                    })
                })
            }
            const aborted = (): void => kill('git interrupted')
            const timer = setTimeout(() => kill(`git timed out after ${timeoutMs} ms`), timeoutMs)
            signal?.addEventListener('abort', aborted, { once: true })
            if (signal?.aborted) aborted()
            child.stdout.on('data', (chunk: Buffer) => out.push(chunk))
            child.stderr.on('data', (chunk: Buffer) => err.push(chunk))
            child.on('error', (error) => {
                clearTimeout(timer)
                signal?.removeEventListener('abort', aborted)
                if (!settled) {
                    settled = true
                    reject(error)
                }
            })
            child.on('close', (code) => {
                clearTimeout(timer)
                signal?.removeEventListener('abort', aborted)
                if (settled) {
                    return
                }
                settled = true
                resolve({
                    code: code ?? -1,
                    stdout: Buffer.concat(out),
                    stderr: redacted(Buffer.concat(err).toString('utf8')),
                })
            })
            child.stdin.on('error', () => {
                // EPIPE is expected if git exits before reading stdin; don't crash
            })
            child.stdin.end(options.input ?? '')
        })
    }
    return {
        run,
        async ok(args, options) {
            const result = await run(args, options)
            if (result.code !== 0) {
                throw new GitFailed(args, result)
            }
            return result.stdout.toString('utf8').replace(/\n+$/, '')
        },
    }
}

export function parseVersion(text: string): readonly [number, number] | undefined {
    const found = /git version (\d+)\.(\d+)/.exec(text)
    return found === null ? undefined : [Number(found[1]), Number(found[2])]
}

export function supported(version: readonly [number, number] | undefined): boolean {
    if (version === undefined) {
        return false
    }
    return version[0] > MIN_GIT[0] || (version[0] === MIN_GIT[0] && version[1] >= MIN_GIT[1])
}

export async function installedGit(): Promise<readonly [number, number] | undefined> {
    return new Promise((resolve) => {
        const child = spawn('git', ['--version'], { stdio: ['ignore', 'pipe', 'ignore'] })
        const out: Buffer[] = []
        child.stdout.on('data', (chunk: Buffer) => out.push(chunk))
        child.on('error', () => resolve(undefined))
        child.on('close', () => resolve(parseVersion(Buffer.concat(out).toString('utf8'))))
    })
}

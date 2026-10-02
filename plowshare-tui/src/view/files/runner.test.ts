import { readFileSync } from 'node:fs'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { CommandRefused, ranFor, runCommand } from './runner.ts'
import type { Outcome } from './runner.ts'

/**
 * `CommandRunner`'s contract, run against this client's copy of it.
 *
 * <p>The table is `CommandRunnerTest`'s own, so a case added there fails here the
 * same day. Every case starts `sh`, which Windows does not have — skipped there,
 * as the spec's "where the platform allows" says.
 */
interface Case {
    readonly name: string
    readonly argv: readonly string[]
    readonly host?: Readonly<Record<string, string>>
    readonly inherit?: readonly string[]
    readonly env?: Readonly<Record<string, string>>
    readonly timeoutMillis?: number
    readonly outputBytes?: number
    readonly stdin?: string
    /** That many x's as stdin, for a bound too long to write out. */
    readonly stdinBytes?: number
    readonly expect: {
        readonly exitCode?: number | null
        readonly timedOut?: boolean
        readonly stdout?: string
        readonly stderr?: string
        readonly stdoutLength?: number
        readonly stdoutCut?: number
        readonly stdoutEndsWith?: string
        readonly maxMillis?: number
        readonly minMillis?: number
        /** runs-for: the case's deadline read as how long it had to run (`ranFor`). */
        readonly ranFor?: boolean
        readonly refused?: boolean
    }
}

const HERE = dirname(fileURLToPath(import.meta.url))
const table = JSON.parse(readFileSync(join(HERE, '..', '..', '..', '..',
    'plowshare-protocol/src/test/resources/io/aeyer/plowshare/protocol/commands.json'), 'utf8')) as Case[]

let cwd = ''

beforeEach(async () => {
    cwd = await realpath(await mkdtemp(join(tmpdir(), 'runner-')))
})

afterEach(async () => {
    await rm(cwd, { recursive: true, force: true })
})

describe.skipIf(process.platform === 'win32')('a command runs as CommandRunner runs it', () => {
    it('reads a table with something in it', () => {
        expect(table.length).toBeGreaterThan(0)
    })

    it.each(table.map((one) => [one.name, one] as const))('%s', async (_name, one) => {
        // The table's defaults are `CommandRunnerTest`'s: this process's PATH, and
        // generous bounds unless the case names its own.
        const host = { PATH: process.env['PATH'] ?? '', ...(one.host ?? {}) }
        const running = runCommand({
            argv: one.argv,
            cwd,
            env: one.env ?? {},
            inherit: one.inherit ?? ['PATH'],
            timeoutMillis: one.timeoutMillis ?? 10_000,
            outputBytes: one.outputBytes ?? 1024 * 1024,
            ...(one.stdinBytes !== undefined ? { stdin: 'x'.repeat(one.stdinBytes) }
                : one.stdin === undefined ? {} : { stdin: one.stdin }),
        }, host)
        const wanted = one.expect
        if (wanted.refused === true) {
            await expect(running).rejects.toBeInstanceOf(CommandRefused)
            return
        }
        const outcome: Outcome = await running
        if (wanted.exitCode !== undefined) {
            expect(outcome.exitCode).toBe(wanted.exitCode)
        }
        if (wanted.timedOut !== undefined) {
            expect(outcome.timedOut).toBe(wanted.timedOut)
        }
        if (wanted.stdout !== undefined) {
            expect(outcome.stdout).toBe(wanted.stdout)
        }
        if (wanted.stderr !== undefined) {
            expect(outcome.stderr).toBe(wanted.stderr)
        }
        if (wanted.stdoutLength !== undefined) {
            expect(outcome.stdout).toHaveLength(wanted.stdoutLength)
        }
        if (wanted.stdoutCut !== undefined) {
            expect(outcome.stdoutCut).toBe(wanted.stdoutCut)
        }
        if (wanted.stdoutEndsWith !== undefined) {
            expect(outcome.stdout.endsWith(wanted.stdoutEndsWith)).toBe(true)
        }
        if (wanted.maxMillis !== undefined) {
            expect(outcome.millis).toBeLessThan(wanted.maxMillis)
        }
        if (wanted.minMillis !== undefined) {
            expect(outcome.millis).toBeGreaterThanOrEqual(wanted.minMillis)
        }
        if (wanted.ranFor !== undefined) {
            expect(ranFor(outcome, one.timeoutMillis ?? 10_000)).toBe(wanted.ranFor)
        }
    }, 15_000)

    it('refuses a working directory that is not one', async () => {
        await expect(runCommand({
            argv: ['true'], cwd: join(cwd, 'absent'), env: {}, inherit: ['PATH'], timeoutMillis: 1000,
            outputBytes: 1024,
        })).rejects.toThrow('is not a directory')
    })

    it('looks the program up on the command\'s PATH, not this process\'s', async () => {
        await expect(runCommand({
            argv: ['sh', '-c', 'exit 0'], cwd, env: {}, inherit: [], timeoutMillis: 1000, outputBytes: 1024,
        }, process.env)).rejects.toThrow('which is not set; inherit PATH in environment.yml')
    })

    it('answers a cancel as cancelled, with no exit code, promptly', async () => {
        const cancel = new AbortController()
        const running = runCommand({
            argv: ['sleep', '30'], cwd, env: {}, inherit: ['PATH'], timeoutMillis: 60_000, outputBytes: 1024,
        }, process.env, cancel.signal)
        setTimeout(() => cancel.abort(), 200)
        const outcome = await running
        expect(outcome).toMatchObject({ exitCode: null, timedOut: false, cancelled: true })
        expect(outcome.millis).toBeLessThan(5_000)
    })
})

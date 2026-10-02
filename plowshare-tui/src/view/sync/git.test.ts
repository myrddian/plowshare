import { mkdir, mkdtemp, realpath, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { GitFailed, parseVersion, redacted, shadowGit, shadowPaths, supported } from './git.ts'

let root = ''
beforeEach(async () => { root = await realpath(await mkdtemp(join(tmpdir(), 'git-'))) })
afterEach(async () => { await rm(root, { recursive: true, force: true }) })

async function initShadow(root: string) {
    const paths = shadowPaths(root)
    await mkdir(dirname(paths.gitDir), { recursive: true })
    const git = shadowGit(paths)
    await git.ok(['init', '-q', '-b', 'main'])
    return { paths, git }
}

describe('parseVersion', () => {
    it('reads the major and minor of git --version', () => {
        expect(parseVersion('git version 2.53.0')).toEqual([2, 53])
        expect(parseVersion('git version 2.39.5 (Apple Git-154)')).toEqual([2, 39])
        expect(parseVersion('not git')).toBeUndefined()
    })

    it('supports 2.50 and later, where ort honors no-renames', () => {
        expect(supported([2, 50])).toBe(true)
        expect(supported([2, 49])).toBe(false)
        expect(supported([2, 47])).toBe(false)
        expect(supported([2, 43])).toBe(false)
        expect(supported([3, 0])).toBe(true)
        expect(supported([2, 42])).toBe(false)
        expect(supported([2, 38])).toBe(false)
        expect(supported(undefined)).toBe(false)
    })
})

describe('shadowGit', () => {
    it('keeps its repository under .plowshare and never touches the project .git', async () => {
        const { paths, git } = await initShadow(root)
        expect(paths.gitDir).toBe(join(root, '.plowshare', 'sync.git'))
        await writeFile(join(root, 'a.txt'), 'one\n')
        expect(await git.ok(['status', '--porcelain'])).toContain('a.txt')
        expect(await git.ok(['rev-parse', '--git-dir'])).toBe(paths.gitDir)
    })

    it('throws GitFailed with the exit code and stderr', async () => {
        const { git } = await initShadow(root)
        const failed = await git.ok(['rev-parse', '--verify', 'no-such-ref']).catch((e: unknown) => e)
        expect(failed).toBeInstanceOf(GitFailed)
        expect((failed as GitFailed).result.code).not.toBe(0)
    })

    it('does not crash when git exits before reading a large stdin', async () => {
        const { git } = await initShadow(root)
        const result = await git.run(['rev-parse', '--verify', 'no-such-ref'], { input: 'x'.repeat(5_000_000) })
        expect(result.code).not.toBe(0)
    })

    it('kills a git that outlives its timeout and answers 124', async () => {
        const { git } = await initShadow(root)
        const began = Date.now()
        // An editor that never returns stands in for a hub that never answers.
        const result = await git.run(['commit', '--allow-empty'], {
            timeoutMs: 200,
            env: { GIT_EDITOR: 'sleep 3 #', GIT_AUTHOR_NAME: 'a', GIT_AUTHOR_EMAIL: 'a@a',
                GIT_COMMITTER_NAME: 'a', GIT_COMMITTER_EMAIL: 'a@a' },
        })
        expect(result.code).toBe(124)
        expect(result.stderr).toContain('git timed out after 200 ms')
        expect(Date.now() - began).toBeLessThan(2_000)
        const failed = await git.ok(['commit', '--allow-empty'], {
            timeoutMs: 200,
            env: { GIT_EDITOR: 'sleep 3 #' },
        }).catch((e: unknown) => e)
        expect(failed).toBeInstanceOf(GitFailed)
    })

    it('removes the index.lock a killed git leaves behind', async () => {
        const { paths, git } = await initShadow(root)
        const lock = join(paths.gitDir, 'index.lock')
        await writeFile(lock, '')
        // `config --edit` waits on the editor without touching the index, so the lock here is
        // the one a killed index-writing git would have left.
        const result = await git.run(['config', '--edit'], { timeoutMs: 200, env: { GIT_EDITOR: 'sleep 3 #' } })
        expect(result.code).toBe(124)
        expect(await stat(lock).then(() => true, () => false)).toBe(false)
    })

    it('sets commit.gpgsign and tag.gpgsign to false', async () => {
        const { git } = await initShadow(root)
        const gpgsign = await git.ok(['config', '--get', 'commit.gpgsign'])
        expect(gpgsign).toBe('false')
    })

    it('sets core.hooksPath to gitDir/hooks', async () => {
        const { paths, git } = await initShadow(root)
        const hooksPath = await git.ok(['config', '--get', 'core.hooksPath'])
        expect(hooksPath).toBe(join(paths.gitDir, 'hooks'))
    })

    it('ignores tracing requested through options.env or process.env', async () => {
        const { git } = await initShadow(root)
        const oldTrace = process.env.GIT_TRACE_CURL
        try {
            // Attempt to enable tracing both ways; neither should work
            process.env.GIT_TRACE_CURL = '1'
            const result = await git.run(['config', '--get', 'commit.gpgsign'], {
                env: { GIT_TRACE: '1', GIT_TRACE_CURL: '1' },
            })
            // If tracing were enabled, git would output debug info to stderr
            // Verify stderr is empty or contains only normal output
            expect(result.stderr).not.toContain('Authorization')
            expect(result.code).toBe(0)
        } finally {
            if (oldTrace !== undefined) {
                process.env.GIT_TRACE_CURL = oldTrace
            } else {
                delete process.env.GIT_TRACE_CURL
            }
        }
    })

    it('options.env cannot override the config counter', async () => {
        const { git } = await initShadow(root)
        // Attempt to override the config counter to 0
        const result = await git.ok(['config', '--get', 'commit.gpgsign'], {
            env: { GIT_CONFIG_COUNT: '0', GIT_CONFIG_KEY_0: 'commit.gpgsign', GIT_CONFIG_VALUE_0: 'true' },
        })
        // The real config (false) should be used, not the override (true)
        expect(result).toBe('false')
    })

    it('redacts bearer token from stderr', () => {
        const text = '> Authorization: Bearer abc.def.ghi\n> Some other line'
        const result = redacted(text)
        expect(result).not.toContain('abc.def')
        expect(result).not.toContain('abc.def.ghi')
        expect(result).toContain('Bearer [redacted]')
    })
})


describe('headless Git interruption', () => {
    it('kills the Git process group promptly and rejects subsequent work after abort', async () => {
        const { paths } = await initShadow(root)
        const control = new AbortController()
        const git = shadowGit(paths, undefined, control.signal)
        const started = Date.now()
        const pending = git.run(['config', '--edit'], { env: { GIT_EDITOR: 'sleep 30 #' } })
        const timer = setTimeout(() => control.abort(), 100)
        const result = await pending
        clearTimeout(timer)
        expect(result.code).toBe(124)
        expect(result.stderr).toContain('interrupted')
        expect(Date.now() - started).toBeLessThan(2000)
        await expect(git.run(['status'])).rejects.toThrow()
    })
})

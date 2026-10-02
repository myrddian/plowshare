import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
    AlreadyMarked, UNNAMED_MACHINE, discover, mark, markedName, namedAfter, thisMachine, within,
} from './marker.ts'

let top = ''

beforeEach(async () => {
    top = await realpath(await mkdtemp(join(tmpdir(), 'marker-')))
})

afterEach(async () => {
    await rm(top, { recursive: true, force: true })
})

async function marked(directory: string, line: string): Promise<void> {
    await mkdir(join(directory, '.plowshare'), { recursive: true })
    await writeFile(join(directory, '.plowshare', 'project'), line)
}

describe('discovery walks up, and the nearest marker wins', () => {
    it('finds a marker in an ancestor, the way git finds .git', async () => {
        await marked(top, 'accounts\n')
        await mkdir(join(top, 'src', 'deep'), { recursive: true })
        expect(await discover(join(top, 'src', 'deep'))).toEqual({ root: top, project: 'accounts' })
    })

    it('lets a sub-project shadow the project it sits in', async () => {
        await marked(top, 'accounts')
        await marked(join(top, 'ledger'), 'ledger')
        await mkdir(join(top, 'ledger', 'src'), { recursive: true })
        expect(await discover(join(top, 'ledger', 'src')))
            .toEqual({ root: join(top, 'ledger'), project: 'ledger' })
    })

    it('walks past a .plowshare that holds definitions and no name', async () => {
        await marked(top, 'accounts')
        await mkdir(join(top, 'tools', '.plowshare', 'bots'), { recursive: true })
        expect((await discover(join(top, 'tools')))?.project).toBe('accounts')
    })

    it('reads a blank name as no marker', async () => {
        await marked(top, '   \n')
        expect(await markedName(top)).toBeUndefined()
    })
})

describe('marking', () => {
    it('writes the name and nothing else', async () => {
        expect(await mark(top, 'ledger')).toEqual({ root: top, project: 'ledger' })
        expect(await readFile(join(top, '.plowshare', 'project'), 'utf8')).toBe('ledger\n')
    })

    it('is a no-op for the name already there, and refuses a different one', async () => {
        await marked(top, 'ledger\n')
        await expect(mark(top, 'ledger')).resolves.toEqual({ root: top, project: 'ledger' })
        await expect(mark(top, 'accounts')).rejects.toBeInstanceOf(AlreadyMarked)
        expect(await readFile(join(top, '.plowshare', 'project'), 'utf8')).toBe('ledger\n')
    })
})

describe('names', () => {
    it('takes the machine from PLOWSHARE_MACHINE, then the host, then a placeholder', () => {
        expect(thisMachine({ PLOWSHARE_MACHINE: ' bench ' }, 'host.local')).toBe('bench')
        expect(thisMachine({}, 'host.local')).toBe('host.local')
        expect(thisMachine({ PLOWSHARE_MACHINE: '' }, '  ')).toBe(UNNAMED_MACHINE)
    })

    it('names a new project after its directory', () => {
        expect(namedAfter(join(top, 'ledger'))).toBe('ledger')
    })

    it('knows a directory is within itself and its ancestors, and not a sibling', () => {
        expect(within(top, top)).toBe(true)
        expect(within(top, join(top, 'a', 'b'))).toBe(true)
        expect(within(join(top, 'a'), join(top, 'ab'))).toBe(false)
    })
})

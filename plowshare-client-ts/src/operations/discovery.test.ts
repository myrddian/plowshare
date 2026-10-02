import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { CLI_OPERATIONS } from './catalog.ts'
import { MEMORY_OPERATIONS } from './direct.ts'
import { parseCommand } from './commands.ts'
import { discovery, commandHelp } from './discovery.ts'

describe('offline command discovery', () => {
    it('generates current schemas from the canonical request and reply types', () => {
        execFileSync(process.execPath, [fileURLToPath(new URL('../../scripts/generate-operation-schemas.mjs', import.meta.url)), '--check'])
    })
    it('covers every ordinary command in prose and machine-readable help', () => {
        const help = discovery(), names = help.commands.map(row => row.command)
        for (const name of [...Object.keys(CLI_OPERATIONS), ...Object.keys(MEMORY_OPERATIONS).map(verb => 'memory ' + verb), 'search', 'job status', 'job cancel']) {
            expect(names).toContain(name); expect(commandHelp()).toContain(name)
            const row = help.commands.find(row => row.command === name)!
            expect(row.input, name).toBeDefined();expect(row.result, name).toBeDefined()
        }
    })
    it.each(['information acquire','agent run','orchestration start'])('discovers usable %s fields, example, scope and results', command => {
        const row = discovery().commands.find(row => row.command === command)!
        expect(parseCommand(`${command} ${JSON.stringify(row.example)}`).kind).toBe('request')
        expect(row.mutation).toBe(true);expect(row.required?.length).toBeGreaterThan(0)
        expect(row.scope).toContain('account');expect(row.result).toBeDefined()
    })
})

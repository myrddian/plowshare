import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import { acceptedJobOf, jobOutcome, jobStatusOf, jobView } from './job-view.ts'

const ended = { id: 'j', state: 'DONE', outcome: { ending: 'ANSWERED', answered: true, text: 'done' } }
describe('checked job response boundary', () => {
    it('keeps matching identity, nullable limits and future fields/endings without mutating the raw reply', () => {
        const raw = { ...ended, future: ['kept'], limits: { maxTurns: null, noTurnCap: true, maxModelCalls: null, noBudget: true, modelCallsSpent: 4 },
            outcome: { ending: 'FUTURE_ENDING', answered: false, resumable: true, detail: null, future: 7, pace: { tokensPerSecond: 3.5, reasoningEstimated: true } } }
        const read = jobStatusOf({ code: 'OK', payload: raw }, 'j')
        expect(read).toEqual(raw)
        expect(read).not.toBe(raw)
        expect(read?.outcome).not.toBe(raw.outcome)
        expect(raw.future).toEqual(['kept'])
    })
    it.each([
        undefined, [], { ...ended, id: '' }, { ...ended, id: 'other' }, { state: 'DONE', outcome: ended.outcome },
        { ...ended, state: '' }, { ...ended, cancelRequested: 'true' }, { ...ended, conversation: 42 },
        { ...ended, outcome: {} }, { ...ended, outcome: 'finished' }, { ...ended, outcome: { ending: 'ANSWERED' } }, { ...ended, outcome: { ending: 'ANSWERED', answered: false } },
        { ...ended, outcome: { ending: 'ANSWERED', answered: 'true' } },
        { ...ended, outcome: { ending: ' ', answered: true } },
        { ...ended, outcome: { ...ended.outcome, steps: 0.5 } },
        { ...ended, outcome: { ...ended.outcome, pace: { tokensPerSecond: Infinity } } },
        { ...ended, limits: { noBudget: true, maxModelCalls: 30 } },
        { ...ended, limits: { modelCallsSpent: -1 } },
    ])('refuses malformed/foreign status without fabricating completion: %j', raw => {
        expect(jobStatusOf({ code: 'OK', payload: raw }, 'j')).toBeUndefined()
    })
    it('requires the successful status code and distinguishes a running null outcome from DONE without one', () => {
        expect(jobStatusOf({ code: 'NOT_FOUND', payload: ended }, 'j')).toBeUndefined()
        expect(jobView({ id: 'j', state: 'RUNNING', outcome: null }, 'j')?.outcome).toBeNull()
        expect(jobView({ id: 'j', state: 'DONE' }, 'j')?.outcome).toBeUndefined()
        expect(jobOutcome({ ending: 'CANCELLED', answered: false, text: 'partial' })).toMatchObject({ answered: false, text: 'partial' })
    })
    it('reads nonblank accepted handles without accepting OK or an unreadable named agent', () => {
        expect(acceptedJobOf({ code: 'ACCEPTED', payload: { id: 'j', future: true } })?.id).toBe('j')
        for (const payload of [{ id: ' ' }, { id: 1 }, { id: 'j', agent: false }, null]) expect(acceptedJobOf({ code: 'ACCEPTED', payload })).toBeUndefined()
        expect(acceptedJobOf({ code: 'OK', payload: { id: 'j' } })).toBeUndefined()
    })
    it('holds every DTO field to the actual Java records', () => {
        const path = new URL('./job-view.ts', import.meta.url)
        const source = ts.createSourceFile(path.pathname, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true)
        for (const [type, file, record] of [
            ['JobView', 'api/JobView.java', 'JobView'], ['JobLimits', 'api/JobView.java', 'LimitsView'],
            ['JobOutcome', 'api/JobView.java', 'OutcomeView'], ['JobPace', 'agents/Pace.java', 'Pace'],
        ]) {
            const java = readFileSync(new URL('../../../plowshare-server/src/main/java/io/aeyer/plowshare/server/' + file, import.meta.url), 'utf8')
            const fields = new RegExp(`public record ${record}\\(([^)]*)\\)`, 's').exec(java)![1]!.split(',').map(part => {
                const [wireType, name] = part.trim().split(/\s+/)
                const types: Record<string, string> = {
                    String: 'string', boolean: 'boolean', int: 'number',
                    Integer: 'number | null', Long: 'number | null', Double: 'number | null',
                    OutcomeView: 'JobOutcome | null', LimitsView: 'JobLimits | null', Pace: 'JobPace | null',
                }
                expect(wireType! in types, `${record}.${name}: unmapped Java type`).toBe(true)
                const nullableString = name === 'conversation' || name === 'detail'
                return [name!, types[wireType!]! + (nullableString ? ' | null' : '')]
            }).sort(([a], [b]) => a!.localeCompare(b!))
            const dto = source.statements.find(node => ts.isInterfaceDeclaration(node) && node.name.text === type) as ts.InterfaceDeclaration
            expect(dto.members.map(member => {
                const field = member as ts.PropertySignature
                expect(field.questionToken, `${type}.${field.name.getText(source)} is a required wire field`).toBeUndefined()
                return [field.name.getText(source), field.type!.getText(source)]
            }).sort(([a], [b]) => a!.localeCompare(b!)), type).toEqual(fields)
        }
    })
})

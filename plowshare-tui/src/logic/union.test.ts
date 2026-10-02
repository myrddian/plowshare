import { describe, expect, it } from 'vitest'
import { typed } from './session.ts'
import {
    UNION_READY, conflictsOf, openedOf, syncAction, syncNotice, unionAsk, unionStatusOf,
} from './union.ts'
import { describeConflicts, describeSync } from './wording.ts'

describe('syncAction', () => {
    it('reads each verb', () => {
        expect(syncAction('')).toEqual({ kind: 'status' })
        expect(syncAction('on')).toEqual({ kind: 'on' })
        expect(syncAction('off')).toEqual({ kind: 'off' })
        expect(syncAction('conflicts')).toEqual({ kind: 'conflicts' })
        expect(syncAction('hidden .github/ .eslintrc')).toEqual({ kind: 'hidden', paths: ['.github/', '.eslintrc'] })
        expect(syncAction('resolve src/a b.ts theirs')).toEqual({ kind: 'resolve', path: 'src/a b.ts', how: 'theirs' })
    })

    it('refuses what it cannot read', () => {
        expect(syncAction('sideways')).toBeUndefined()
        expect(syncAction('resolve src/a.ts')).toBeUndefined()
        expect(syncAction('resolve src/a.ts maybe')).toBeUndefined()
    })

    it('is what /sync parses to', () => {
        expect(typed('/sync on')).toEqual({ kind: 'sync', action: { kind: 'on' } })
        expect(typed('/sync nonsense')).toEqual({ kind: 'usage', command: '/sync' })
    })
})

describe('frames', () => {
    it('always name the project', () => {
        expect(unionAsk(UNION_READY, 'ledger', { commit: 'abc' }))
            .toEqual({ type: 'union.ready', payload: { project: 'ledger', commit: 'abc' } })
    })

    it('read a status, a conflict list and an opened number', () => {
        expect(unionStatusOf({ code: 'OK', payload: { eligible: true, enabled: true, state: 'LIVE',
            syncHidden: ['.github/'], maxFileBytes: 5, openConflicts: 2, url: '/v1/sync/ledger.git' } }))
            .toEqual({ eligible: true, enabled: true, state: 'LIVE', syncHidden: ['.github/'], maxFileBytes: 5,
                openConflicts: 2, url: '/v1/sync/ledger.git' })
        expect(unionStatusOf({ code: 'OK', payload: { eligible: false, enabled: false } }))
            .toEqual({ eligible: false, enabled: false, syncHidden: [], maxFileBytes: 0, openConflicts: 0 })
        expect(conflictsOf({ code: 'OK', payload: { conflicts: [
            { n: 1, path: 'a', theirsAuthor: 'bot', theirsBlob: 't', baseBlob: null }] } }))
            .toEqual([{ n: 1, path: 'a', theirsAuthor: 'bot', theirsBlob: 't' }])
        expect(openedOf({ code: 'OK', payload: { n: 4 } })).toBe(4)
        expect(openedOf({ code: 'BAD_REQUEST', said: 'no' })).toBeUndefined()
    })
})

describe('wording', () => {
    it('counts conflicts for the status line', () => {
        expect(syncNotice(0)).toBeUndefined()
        expect(syncNotice(1)).toBe('⚠ 1 sync conflict')
        expect(syncNotice(3)).toBe('⚠ 3 sync conflicts')
    })

    it('describes a status and a conflict list', () => {
        expect(describeSync({ eligible: true, enabled: false, syncHidden: [], maxFileBytes: 0, openConflicts: 0 }))
            .toEqual(["this project's files are only on this machine — /sync on keeps a copy on the server"])
        expect(describeConflicts([{ n: 1, path: 'src/a.ts', theirsAuthor: 'nightly-bot', runId: 'run_7' }]))
            .toEqual(['src/a.ts — your version kept; nightly-bot\'s (run_7) set aside',
                '  /sync resolve src/a.ts mine | theirs | merge'])
    })
})

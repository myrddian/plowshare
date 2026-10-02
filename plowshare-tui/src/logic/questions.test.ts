import { describe, expect, it } from 'vitest'

import { answeringAbout, answeringOn, draftAsked, settledAnswering } from './questions.ts'
import type { Answering, QuestionStroke } from './questions.ts'
import type { Structure } from './session.ts'

/**
 * A question with options answered a key at a time — without a terminal. The property every case
 * comes back to: <b>what is sent is what the person was shown chosen</b>.
 */

const TWO: Structure = {
    lead: 'First:',
    questions: [
        { header: 'Store', question: 'Which database?', multi: false, options: [
            { label: 'Postgres', description: 'p' }, { label: 'SQLite', description: 's' },
        ] },
        { header: 'Clients', question: 'Which clients?', multi: true, options: [
            { label: 'TUI', description: 't' }, { label: 'Console', description: 'c' },
        ] },
    ],
}

const key = (text: string): QuestionStroke => ({ kind: 'text', text })
const ENTER: QuestionStroke = { kind: 'enter' }
const ESCAPE: QuestionStroke = { kind: 'escape' }
const DOWN: QuestionStroke = { kind: 'down' }
const TAB: QuestionStroke = { kind: 'tab' }
const BACKSPACE: QuestionStroke = { kind: 'backspace' }
const typed = (text: string): QuestionStroke[] => [...text].map(key)
const pressed = (...strokes: QuestionStroke[]): Answering =>
    strokes.reduce(answeringOn, answeringAbout('orc_1', TWO))

const sent = (state: Answering): unknown => (state.kind === 'answered' ? state.ask : undefined)

describe('answering a question with options', () => {
    it('enter picks the focused option and moves on; enter on the last sends', () => {
        const state = pressed(DOWN, ENTER, key(' '), ENTER)
        expect(sent(state)).toEqual({ type: 'orchestration.answer', payload: {
            id: 'orc_1', choices: [
                { header: 'Store', chosen: ['SQLite'] },
                { header: 'Clients', chosen: ['TUI'] },
            ],
        } })
        expect(settledAnswering(state)).toBe(true)
    })

    it('a digit picks its option; on a multi question it toggles', () => {
        const state = pressed(key('1'), TAB, key('1'), key('2'), key('1'), ENTER)
        expect(sent(state)).toEqual({ type: 'orchestration.answer', payload: {
            id: 'orc_1', choices: [
                { header: 'Store', chosen: ['Postgres'] },
                { header: 'Clients', chosen: ['Console'] },
            ],
        } })
    })

    it('other replaces a single choice, and a choice replaces other', () => {
        const replaced = pressed(key('1'), key('o'), ...typed('MySQL'), ENTER)
        expect(replaced.kind === 'choosing' ? replaced.answers[0] : undefined)
            .toEqual({ chosen: [], other: 'MySQL' })
        const back = answeringOn(replaced, key('2'))
        expect(back.kind === 'choosing' ? back.answers[0] : undefined).toEqual({ chosen: [1] })
    })

    it('a note rides beside the choice; backspace edits and esc drops what was typed', () => {
        const noted = pressed(key('2'), key('n'), ...typed('for nowx'), BACKSPACE, ENTER,
            key('o'), ...typed('ignored'), ESCAPE)
        expect(noted.kind === 'choosing' ? noted.answers[0] : undefined)
            .toEqual({ chosen: [1], note: 'for now' })
    })

    it('sending with a question unanswered moves to it and names it', () => {
        const state = pressed(TAB, key('1'), ENTER)
        expect(state.kind).toBe('choosing')
        expect(state.kind === 'choosing' ? [state.at, state.missing] : undefined).toEqual([0, 'Store'])
    })

    it('esc leaves the question open and sends nothing', () => {
        const state = pressed(key('1'), ESCAPE)
        expect(state).toEqual({ kind: 'left', run: 'orc_1', structure: TWO })
        expect(settledAnswering(state)).toBe(true)
    })

    it('takes no more words than the server does — two thousand characters — a paste cut there', () => {
        const typing = pressed(key('o'), key('a'.repeat(1990)))
        const full = answeringOn(typing, key('b'.repeat(20)))
        expect(full.kind === 'typing' ? full.text : undefined).toBe(`${'a'.repeat(1990)}${'b'.repeat(10)}`)
        expect(answeringOn(full, key('c'))).toEqual(full)
        // A character outside the basic plane is two of the server's characters, and not split.
        const edge = answeringOn(pressed(key('o'), key('a'.repeat(1999))), key('😀'))
        expect(edge.kind === 'typing' ? edge.text.length : undefined).toBe(1999)
        expect(answeringOn(full, BACKSPACE).kind === 'typing').toBe(true)
    })

    it('a digit past the options, and a letter that means nothing, change nothing', () => {
        const start = answeringAbout('orc_1', TWO)
        expect(answeringOn(start, key('3'))).toEqual(start)
        expect(answeringOn(start, key('x'))).toEqual(start)
    })
})

describe('an install question\'s draft, asked for with v', () => {
    const DRAFT = { name: 'x', path: 'artifacts/x.md', text: 'Do it.' }
    const WITH: Structure = { ...TWO, draft: DRAFT }

    it('v while choosing asks for the draft, and leaves the question as it was', () => {
        const chosen = [key('2'), DOWN].reduce(answeringOn, answeringAbout('orc_1', WITH))
        expect(draftAsked(chosen, key('v'))).toEqual(DRAFT)
        expect(draftAsked(chosen, key('V'))).toEqual(DRAFT)
        // THE FOLD IGNORES IT: the view shows the draft and hands the same state back.
        expect(answeringOn(chosen, key('v'))).toEqual(chosen)
    })

    it('v asks for nothing without a draft, while typing, or as anything but one key', () => {
        const start = answeringAbout('orc_1', TWO)
        expect(draftAsked(start, key('v'))).toBeUndefined()
        const typing = answeringOn(answeringAbout('orc_1', WITH), key('o'))
        expect(draftAsked(typing, key('v'))).toBeUndefined()
        // Typing, v is a letter of the words.
        const next = answeringOn(typing, key('v'))
        expect(next.kind === 'typing' ? next.text : undefined).toBe('v')
        expect(draftAsked(answeringAbout('orc_1', WITH), key('vv'))).toBeUndefined()
        expect(draftAsked(answeringAbout('orc_1', WITH), ENTER)).toBeUndefined()
    })
})

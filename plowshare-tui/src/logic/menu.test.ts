import { describe, expect, it } from 'vitest'

import { matching, menuFor, picking } from './menu.ts'
import type { Vocabulary } from './menu.ts'

const VOCABULARY: Vocabulary = {
    commands: [
        { name: '/help', detail: 'this' },
        { name: '/bots', detail: 'who there is to talk to' },
        { name: '/theme', detail: 'the colours this client draws in' },
    ],
    names: [
        { name: 'aristoxenus', detail: 'a music theorist' },
        { name: 'archivist' },
    ],
    arguments: {
        '/theme': () => [{ name: 'auto' }, { name: 'dusk' }, { name: 'system' }],
    },
}

describe('when a line has a menu', () => {
    it('opens on a slash at the start, offering every command', () => {
        const menu = menuFor('/', 1, VOCABULARY)
        expect(menu?.kind).toBe('command')
        expect(menu?.choices.map((offer) => offer.name)).toEqual(['/help', '/bots', '/theme'])
    })

    it('narrows by what was typed', () => {
        expect(menuFor('/th', 3, VOCABULARY)?.choices.map((offer) => offer.name)).toEqual(['/theme'])
    })

    it('offers a name typed in full first, ahead of a longer one it is a prefix of', () => {
        // `/project` is a whole command and also the start of `/projects`,
        // which comes first in the list; Tab on the first would take the second.
        const offers = [{ name: '/projects' }, { name: '/project' }]
        expect(matching(offers, 'project', '/').map((offer) => offer.name)).toEqual(['/project', '/projects'])
        expect(matching(offers, 'proj', '/').map((offer) => offer.name)).toEqual(['/projects', '/project'])
    })

    it('finds a command by its description after the ones named that way', () => {
        expect(menuFor('/colour', 7, VOCABULARY)?.choices.map((offer) => offer.name)).toEqual(['/theme'])
    })

    it('has none for a slash that is not at the start, or a command nothing matches', () => {
        expect(menuFor('a /he', 5, VOCABULARY)).toBeUndefined()
        expect(menuFor('/zzz', 4, VOCABULARY)).toBeUndefined()
    })

    it('offers a command\'s arguments after it and a space', () => {
        const menu = menuFor('/theme du', 9, VOCABULARY)
        expect(menu?.kind).toBe('argument')
        expect(menu?.choices.map((offer) => offer.name)).toEqual(['dusk'])
        expect(menu?.start).toBe(7)
    })

    it('offers nothing after a command that takes nothing', () => {
        expect(menuFor('/help ', 6, VOCABULARY)).toBeUndefined()
    })

    it('offers names after an @ that starts a word, anywhere in the line', () => {
        const menu = menuFor('ask @ar', 7, VOCABULARY)
        expect(menu?.kind).toBe('mention')
        expect(menu?.choices.map((offer) => offer.name)).toEqual(['aristoxenus', 'archivist'])
        expect(menuFor('mail me@ar', 10, VOCABULARY)).toBeUndefined()
    })

    it('closes once a space follows the word', () => {
        expect(menuFor('/help ', 6, VOCABULARY)).toBeUndefined()
        expect(menuFor('ask @ari now', 12, VOCABULARY)).toBeUndefined()
    })
})

describe('picking an argument that more is typed after', () => {
    // `/answer ` offers the runs waiting; the id is only the start of the line,
    // and the answer is what the person still has to type.
    const vocabulary: Vocabulary = {
        ...VOCABULARY,
        arguments: { ...VOCABULARY.arguments, '/answer': () => [{ name: 'orc_1' }, { name: 'orc_2' }] },
        openEnded: ['/answer'],
    }

    it('leaves the cursor after the id and a space, and does not call the line complete', () => {
        const menu = menuFor('/answer o', 9, vocabulary)
        expect(picking('/answer o', menu!, { name: 'orc_2' }, vocabulary))
            .toEqual({ typed: '/answer orc_2 ', at: 14, complete: false })
    })

    it('closes once the id is followed by a space, so the answer is typed without a menu', () => {
        expect(menuFor('/answer orc_1 ', 14, vocabulary)).toBeUndefined()
    })
})

describe('picking', () => {
    it('replaces the whole word the cursor is in, not just what is before it', () => {
        const menu = menuFor('/thme', 2, VOCABULARY)
        expect(menu).toBeDefined()
        // `/t` with the cursor after it matches /theme; the `hme` after the
        // cursor goes with the word.
        const picked = picking('/thme', menu!, { name: '/theme' }, VOCABULARY)
        expect(picked.typed).toBe('/theme ')
    })

    it('leaves a command that takes an argument open for one', () => {
        const picked = picking('/th', menuFor('/th', 3, VOCABULARY)!, { name: '/theme' }, VOCABULARY)
        expect(picked).toEqual({ typed: '/theme ', at: 7, complete: false })
    })

    it('calls a command that takes nothing, or a command with its argument, complete', () => {
        expect(picking('/he', menuFor('/he', 3, VOCABULARY)!, { name: '/help' }, VOCABULARY).complete).toBe(true)
        const argument = menuFor('/theme d', 8, VOCABULARY)!
        expect(picking('/theme d', argument, { name: 'dusk' }, VOCABULARY))
            .toEqual({ typed: '/theme dusk', at: 11, complete: true })
    })

    it('writes a mention with its @ and a space, in the middle of a sentence', () => {
        const line = 'ask @ar about modes'
        const picked = picking(line, menuFor(line, 7, VOCABULARY)!, { name: 'aristoxenus' }, VOCABULARY)
        expect(picked).toEqual({ typed: 'ask @aristoxenus about modes', at: 17, complete: false })
    })
})

describe('matching', () => {
    it('is case-insensitive and keeps the given order among prefix matches', () => {
        expect(matching([{ name: 'Beta' }, { name: 'bravo' }], 'B').map((offer) => offer.name))
            .toEqual(['Beta', 'bravo'])
    })
})

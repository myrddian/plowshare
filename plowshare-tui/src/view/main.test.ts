import { describe, expect, it } from 'vitest'

import { agentNamed, unreachable } from './main.ts'
import { describeUnreachable } from '../logic/wording.ts'

/**
 * The two decisions {@link run} makes before it opens anything.
 *
 * <p>`main.ts` is otherwise driven end to end by `composition.test.ts`, over a
 * real socket, and that is the right shape for everything below sign-in. What
 * it cannot reach is {@link run} itself, which reads a real `process` and
 * drives a real terminal — so the checks that happen <i>before</i> `converse`
 * is called live in functions that take what they read, and this is where they
 * are exercised.
 *
 * <p><b>Which is not a formality.</b> What this reads out of the environment
 * decides whether a person is talking to a bot or to a role, and the one thing
 * it must not do is turn an unset variable into a name.
 */

describe('who a person named, read out of the environment', () => {
    it('hands back the agent the environment names', () => {
        expect(agentNamed({ PLOWSHARE_AGENT: 'close_reader' })).toBe('close_reader')
    })

    it('hands back nothing when nobody was named, rather than refusing', () => {
        // THE CHANGE TASK 3 MADE. This used to throw: `PLOWSHARE_AGENT` was
        // required, which made the ordinary way to use this system — talking to
        // somebody — the thing you had to configure. `agent.list` at sign-in
        // answers the question instead, and `session.whoAnswers` decides.
        //
        // The timing worry the throw existed for is better served now, not
        // dropped: a name this server does not serve is refused at sign-in with
        // nothing created, which is something this function could never check.
        expect(agentNamed({})).toBeUndefined()
    })

    it('treats a variable set to nothing as a variable that is not set', () => {
        // `export PLOWSHARE_AGENT=` is somebody not naming an agent, not
        // somebody naming one called ''. An all-spaces value is the same thing
        // with a shell's whitespace in it. `whoAnswers` trims and rules the
        // same way, so neither end can decide differently from the other.
        expect(agentNamed({ PLOWSHARE_AGENT: '' })).toBeUndefined()
        expect(agentNamed({ PLOWSHARE_AGENT: '   ' })).toBeUndefined()
    })

    it('trims a name that a shell left whitespace around', () => {
        expect(agentNamed({ PLOWSHARE_AGENT: ' close_reader ' })).toBe('close_reader')
    })
})


describe('a server that cannot be reached says so, and says where it looked', () => {
    /**
     * The likeliest first run of this client, and it printed `fetch failed`
     * until somebody actually ran it against a closed port.
     *
     * <p>The server is remote by design — it needs Postgres and a model
     * configuration — so "not running" and "wrong address" are the two ordinary
     * first experiences rather than exotic ones. `fetch` reports both as a
     * `TypeError` whose message is the four words with no information in them,
     * and puts the real reason in `cause`. So the shape is what is tested, not
     * the message: matching on "fetch failed" would break on a Node that
     * reworded it, and would miss nothing else.
     */
    it('recognises fetch declining to reach the host at all', () => {
        const refused = new TypeError('fetch failed', { cause: new Error('ECONNREFUSED') })
        expect(unreachable(refused)).toBe(true)
    })

    it('does not mistake an ordinary TypeError for an unreachable server', () => {
        // No cause, so nothing under it said why -- this is a bug in this
        // client, and telling somebody to check PLOWSHARE_URL would send them
        // looking in the wrong place.
        expect(unreachable(new TypeError('x is not a function'))).toBe(false)
        expect(unreachable(new Error('anything else'))).toBe(false)
    })

    it('names the address it tried, because that is the thing to check', () => {
        const said = describeUnreachable('https://plowshare.example:8443')
        expect(said).toContain('https://plowshare.example:8443')
        expect(said).toContain('PLOWSHARE_URL')
        // Every refusal on this surface says what did not happen.
        expect(said).toContain('Nothing was sent')
    })
})

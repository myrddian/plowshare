import { describe, expect, it } from 'vitest'
import { grantFieldFor } from './grant'

describe('which field a grant raises', () => {
    it('grants turns for a run that stopped at its turn cap', () => {
        expect(grantFieldFor('TURN_CAP')).toBe('maxTurns')
    })

    it('grants model calls for a run that stopped at the conversation budget', () => {
        expect(grantFieldFor('CALL_BUDGET')).toBe('maxModelCalls')
    })

    it('knows nothing to raise for an ending outside that pair, rather than guessing', () => {
        // A copy of this table is exactly the mistake this module exists to
        // prevent; this test is the one place that pins its two entries and no
        // more. `STUCK` and `ANSWERED` are both real endings and neither is one
        // a grant continues by raising a ceiling.
        expect(grantFieldFor('STUCK')).toBeNull()
        expect(grantFieldFor('ANSWERED')).toBeNull()
        expect(grantFieldFor('SOMETHING_THIS_FILE_HAS_NEVER_HEARD_OF')).toBeNull()
    })
})

/**
 * The one fact the REPL and the jobs screen both need about a stopped,
 * resumable run: which field of a grant would raise the ceiling that actually
 * stopped it.
 *
 * <h2>Why this lives here, sibling to both, and in neither</h2>
 *
 * `repl.ts`'s own `considerOffer` held this mapping first, inline, for the
 * grant control beside a stopped run's transcript. The jobs screen needs the
 * identical decision for its own continue control, over a run that stopped
 * unattended -- same server, same two endings, same rule. Copying the mapping
 * into `jobs.ts` would be a second table beside `Turn.CONTINUABLE`'s, correct
 * on the day it is written and silently wrong the day a third ending is added
 * to one copy and not the other, which is Stage 1's own finding about a
 * second copy of a wire-shaped decision restated at this layer.
 *
 * Putting it in `repl/` would make the jobs screen depend on the REPL's own
 * internals to draw a button the REPL never shows; putting it in `screens/`
 * would invert the dependency the REPL already has none of. `api.ts` and
 * `events.ts` already sit here, sibling to both directories, for the same
 * reason -- this is where a fact both sides need and neither owns belongs.
 *
 * <h2>What this is not</h2>
 *
 * **This does not decide whether a grant is offered at all.** That is the
 * server's `OutcomeView.resumable` bit alone, read once and never re-derived
 * by enumerating endings -- see that field's own javadoc and `considerOffer`'s.
 * A copy of *that* table here would be exactly the mistake this module exists
 * to prevent, one layer up. This module only answers the narrower question a
 * caller reaches once `resumable` has already said yes: of the two fields
 * `AdjustLimitsRequest` and `ResumeRunRequest` both carry, which one would
 * raise the ceiling this particular run stopped at. `TURN_CAP` and
 * `CALL_BUDGET` are the two endings a stopped, resumable run currently has;
 * anything else answers `null` rather than guessing, because a grant sent
 * against the wrong field buys nothing and a field invented for an ending this
 * module has never heard of is a number no endpoint has a method for.
 */

/** The wire field a grant for this ending would carry, or `null` if none is known. */
export type GrantField = 'maxTurns' | 'maxModelCalls' | null;

/** Which field of a grant raises the ceiling that stopped this ending, if either does. */
export function grantFieldFor(ending: string): GrantField {
  return ending === 'TURN_CAP'
    ? 'maxTurns'
    : ending === 'CALL_BUDGET'
      ? 'maxModelCalls'
      : null;
}

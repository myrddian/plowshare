-- A conversation that owns its allowance and puts no ceiling on it.
--
-- WHAT CHANGED SINCE V17 SECTION 4, which is the argument this file amends
-- rather than repeats. V17 enumerated the allowance stories this server had --
-- a turn spends the conversation's, a delegated child spends its parent's, a
-- curator's ruling spends the pass's, a submission spends one built from the
-- definition -- and every one of them either OWNS a number or SPENDS SOMEBODY
-- ELSE'S. There was no fourth shape, so V17 could tie the two budget columns
-- together and tie both to `origin`, and both of those ties were exactly right
-- for the set of stories that existed. A third case exists now: a person opens
-- a conversation whose turns are not to be stopped at a number at all. It is
-- nobody else's allowance -- the conversation owns it, the spending is its own,
-- and there is simply no ceiling.
--
-- WHY NULL COULD NOT SIMPLY BE REUSED, which was the obvious move and is the
-- one V17 rules out in advance. `budget_total IS NULL` already MEANS something
-- here, and the thing it means is load-bearing: "a conversation that spends an
-- allowance it does not own holds NO NUMBERS AT ALL", which is how this table
-- refuses to hold a second copy of a shared budget. Nulling the total for an
-- unbounded conversation would put a shared allowance and an unbounded one in
-- the same column in the same shape, and then:
--
--   * the first thing to sum spending across a tree could no longer tell a row
--     whose count is its own from a row that has no count, so the double
--     accounting V17 spends its section preventing would come back through the
--     door V17 left open;
--   * `conversations_an_allowance_is_owned_or_shared` says a turn's row carries
--     a total, so an unbounded turn conversation would have to be exempted from
--     it -- and that constraint is the one holding "owns" and "shares" apart;
--   * `ConversationRecord.budget()` would be null for a conversation that has a
--     budget, and `Turn.speak` refuses to speak into a null one -- so the state
--     would be unreachable in exactly the code path it was added for.
--
-- SO IT IS V10'S SHAPE, ONE TABLE OVER. Two columns for one knob, because there
-- are three states and a single nullable column can only hold two of them
-- honestly; `agents.Budget.none()` is the Java half and says why no sentinel
-- will do ("-1 read by code that forgot to check for it first becomes a budget
-- nothing can spend against"). The states this table now holds are:
--
--   budget_lifted = FALSE, budget_total = n, budget_spent = m
--                                       -- an allowance of n, m of it spent
--   budget_lifted = FALSE, both NULL    -- spends an allowance it does not own;
--                                          V17's state, unchanged
--   budget_lifted = TRUE,  budget_total NULL, budget_spent = m
--                                       -- owns its allowance, no ceiling on
--                                          it, m calls made
--
-- and the combinations left over are refused below rather than left for two
-- readers to read two ways.
--
-- NOT NULL WITH A DEFAULT, V10's reasoning verbatim in its effect: every
-- conversation written before this migration had a ceiling or shared one, so
-- FALSE is what they are and not a guess. A nullable boolean would add a fourth
-- state to the column that exists to remove one.
ALTER TABLE conversations
    ADD COLUMN budget_lifted BOOLEAN NOT NULL DEFAULT FALSE;

-- A number or no ceiling, never both -- `conversations_turn_cap_is_a_number_or_
-- no_cap`'s predicate applied to the other knob, and for its reason: a row
-- holding both has answered one question twice, and whichever of the two a
-- reader preferred would be a decision taken by the reader rather than by
-- whoever opened the conversation.
ALTER TABLE conversations ADD CONSTRAINT conversations_budget_is_a_number_or_no_ceiling
    CHECK (NOT (budget_lifted AND budget_total IS NOT NULL));

-- WHICH ORIGINS OWN AN ALLOWANCE, unchanged as a question and widened in what
-- counts as answering it. V17 wrote `budget_total IS NOT NULL` because at the
-- time a number WAS the only way to own one; the fact the predicate is about is
-- ownership, not arithmetic, so the right-hand side becomes "says something
-- about a ceiling of its own" -- a total, or the deliberate absence of one.
--
-- The boolean is on the OWNING side rather than standing beside the predicate,
-- and that placement is the whole of what this constraint now stops. A
-- delegated child or a curator's ruling with `budget_lifted = TRUE` would be a
-- conversation declaring there is no ceiling on an allowance that is not its to
-- describe -- the same double claim as copying its parent's numbers down,
-- spelled in one column instead of two, and reaching the same sum wrongly.
--
-- 'memory' IS CARRIED ACROSS FROM V29 AND IS NOT A NEW MEMBER OF THIS LIST.
-- V17 wrote the two-origin version; V29 added the memory origin and restated
-- the whole predicate to include it. Recreating the constraint from V17's text
-- would silently revoke that -- every memory operation refused at the first
-- write, by a rule nobody in this file was reasoning about. A dropped-and-
-- recreated constraint inherits from whatever last defined it, not from
-- whichever migration a reader happens to have open.
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_owned_or_shared;

ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared
    CHECK ((origin IN ('turn', 'submission', 'memory'))
           = (budget_total IS NOT NULL OR budget_lifted));

-- AN ALLOWANCE IS BOTH HALVES OR NEITHER, AND NOW ALSO A COUNT ON ITS OWN.
-- V17's version is `(budget_total IS NULL) = (budget_spent IS NULL)`, and the
-- sentence under it is the one worth keeping: a count with no total is spending
-- with no ceiling, which `conversations_spent_within_budget` cannot catch,
-- because that predicate is unknown -- and therefore satisfied -- when the total
-- is NULL. That is still true of every row that has not said so. What has
-- changed is that "spending with no ceiling" went from being the description of
-- a mistake to being a state somebody chooses, and the boolean is what tells the
-- two apart: without it the row is still the mistake and is still refused.
--
-- SPEND IS A MEASUREMENT AND THE CEILING IS A POLICY. V17 tied them because at
-- the time "no total" had exactly one meaning, so the two really did arrive and
-- depart together; they are not the same kind of fact, and the third row below
-- is what separating them buys -- a conversation with no ceiling still reports
-- what it has used, which for an operator watching one is the only number left
-- to watch. The three legal rows, and nothing else:
--
--   total and count      -- an owned allowance
--   neither, not lifted  -- a shared one, holding no numbers at all
--   count, no total, lifted
--                        -- an owned allowance with no ceiling
--
-- Written as three explicit disjuncts rather than as a relaxation of the
-- equality, so that the row a reader is looking for is one of three lines they
-- can point at, and so that a fourth shape cannot be admitted by loosening one
-- side of an `=`.
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_both_halves_or_neither;

ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_both_halves_or_neither
    CHECK ((budget_total IS NOT NULL AND budget_spent IS NOT NULL)
        OR (budget_total IS NULL AND budget_spent IS NULL AND NOT budget_lifted)
        OR (budget_total IS NULL AND budget_spent IS NOT NULL AND budget_lifted));

COMMENT ON COLUMN conversations.budget_lifted IS
    'Whether this conversation owns its allowance and puts no ceiling on it. '
    'FALSE is the ordinary shape: budget_total is the ceiling, or both budget '
    'columns are NULL because the allowance is somebody else''s. Never TRUE '
    'beside a number, and never TRUE on a conversation that shares an allowance '
    'it does not own.';

COMMENT ON COLUMN conversations.budget_total IS
    'The allowance every turn in this conversation spends from, or NULL for a '
    'conversation that spends an allowance it does not own -- a delegated child '
    'spends its parent''s by reference, a curator''s ruling spends the pass''s. '
    'NULL is how this table refuses to hold a second copy of a shared budget, '
    'which would double the accounting the first time anything summed it. NULL '
    'with budget_lifted TRUE is the third case and not that one: the allowance '
    'is this conversation''s own and has no ceiling, and budget_spent still '
    'counts what it has used.';

COMMENT ON COLUMN conversations.budget_spent IS
    'How many model calls this conversation has made, or NULL when the '
    'allowance is not its own to count. Present without budget_total exactly '
    'when budget_lifted is TRUE: spend is a measurement and the ceiling is a '
    'policy, so a conversation may have the first without the second.';

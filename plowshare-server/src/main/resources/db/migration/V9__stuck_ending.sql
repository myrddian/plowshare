-- One more way a run can end: it repeated itself and was stopped.
--
-- `Outcome.Ending.STUCK`. V7 wrote `turns_ending_is_known` over the seven
-- constants that existed then and said, in the same breath, what this file is:
-- "ADDING AN ENDING THEREFORE NEEDS A MIGRATION, and that is correct rather
-- than a cost". This is the first one, and it arrives with the change that
-- raised the turn cap -- a run that may take a hundred turns, or no bounded
-- number of them, is only responsible if something notices one going nowhere.
--
-- WHY THE WHOLE LIST IS WRITTEN OUT AGAIN. A CHECK constraint cannot be added
-- to. Postgres has no `ALTER CONSTRAINT ... ADD VALUE`, so the only way to widen
-- one is to drop it and write a new one, and the new one has to name every value
-- the old one named. That makes this file a second copy of a list whose first
-- copy is in V7 and whose third is `Outcome.Ending` itself, in Java.
--
-- NOTHING HOLDS THE THREE TOGETHER, and that is worth saying here rather than
-- only in the enum. A constant added in Java is not a compile error against this
-- schema; it is a row Postgres refuses at the moment a real conversation reaches
-- it, which is the latest and most expensive place to find out.
-- `TurnStoreTest.every_ending_this_server_can_reach_is_a_turn_this_table_holds`
-- enumerates `Ending.values()` and writes a turn for each, so it fails on the
-- build that adds a constant without a migration rather than on the first person
-- to hit the new ending. That test is the seam. Generating this constraint from
-- the enum would put a code generator between a schema and its own file, which
-- is a bigger thing than the check it would replace.
--
-- WHAT A STUCK TURN MUST CARRY, which is not new and is not relaxed here.
-- `turns_a_turn_that_stopped_says_how` already requires a non-empty answer for
-- every ending but ANSWERED, so a turn recorded with this one has to say what
-- the run kept doing. It does: `JobRuntime.Repeats.futility` names the tool and
-- the count, and `stopped` appends the tool trail, exactly as for every other
-- stopping ending.
--
-- NO DATA MIGRATION. No row can hold the new value before this file runs -- the
-- constraint it replaces is what stops one -- so there is nothing to backfill
-- and nothing to correct. Existing rows are re-checked against the new
-- constraint when it is added, and every one of them holds a value the new list
-- still names.
ALTER TABLE turns DROP CONSTRAINT turns_ending_is_known;

ALTER TABLE turns ADD CONSTRAINT turns_ending_is_known CHECK (ending IN (
    'ANSWERED',
    'TURN_CAP',
    'CALL_BUDGET',
    'CANCELLED',
    'STUCK',
    'UNAVAILABLE',
    'SUB_AGENT_FAILED',
    'SESSION_GONE'
));

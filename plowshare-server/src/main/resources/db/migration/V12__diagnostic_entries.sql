-- An eighth kind of entry: what the harness noticed about a conversation, in
-- the conversation's own log, where no model will ever read it.
--
-- WHAT IT IS FOR. Things like "compaction triggered" are facts about a
-- particular conversation and they are currently recorded nowhere that knows
-- which conversation they belong to. They go to the server's own log, which is
-- one stream shared by every run on the box and is gone when the file rolls.
-- Written here they sit between the entries they happened between, so a person
-- reading a conversation back sees the machinery act on it at the moment it
-- acted, in the order it acted -- which is the thing a line in a shared stream
-- cannot say however carefully it is worded.
--
-- WHY IT IS INVISIBLE, and this kind is the one where invisibility is the whole
-- point rather than a consequence. The other silent kinds are silent because
-- replaying them would be dishonest: an `attempt_failed` never reached a model,
-- a `runtime_note` was already delivered in-run. A `diagnostic` is different in
-- kind. It is not part of the conversation at all -- it is the harness talking
-- ABOUT the conversation, and a model shown one would be reading its own
-- plumbing as though a participant had said it. `summary` is not a
-- counter-example: it stands in for turns that really were said, which is why it
-- is the one harness voice a model hears.
--
-- WHY THIS FILE ONLY TOUCHES ONE OF THE TWO CONSTRAINTS, which is the part worth
-- reading before adding a ninth kind. V11 wrote the projection rule twice, on
-- purpose, and the two halves are not symmetrical:
--
--   * `entries_kind_is_known` is a closed list, so a kind is unwritable until it
--     is named here. That is what this file changes, and it is the only thing
--     that has to be changed by hand.
--   * `entries_role_matches_kind` derives the role from the kind with a CASE
--     that has NO ELSE. A kind the CASE does not name yields NULL, and the
--     predicate then requires the row to hold NULL. So `diagnostic` is confined
--     to `role NULL` by the constraint that was written before it existed, and
--     the projection skips a NULL role.
--
-- The consequence, stated because it is the safety property: adding a kind to
-- the list below WITHOUT deciding what a model should see makes it invisible
-- rather than accidentally visible. The failure mode of forgetting is a missing
-- message, which is loud, and never a leaked one, which is not. Giving a kind a
-- role is a deliberate second edit to a second constraint, and it belongs in a
-- migration of its own with an argument about what a model is now shown.
--
-- WHY A NEW FILE AND NOT AN EDIT TO V11. Flyway checksums a migration over its
-- whole text, comments included, so editing an applied file fails every database
-- that has already run it, at boot, and the repair is a manual `flyway repair`
-- on each. `MigrationsAreImmutableTest` holds every migration on `master` byte
-- for byte against git for exactly this reason, and it has caught the mistake
-- twice already.
--
-- DROP AND ADD AND NOT AN ALTER, because Postgres has no way to widen a CHECK in
-- place. The ADD revalidates every existing row, which is what makes this safe
-- to run against a live `entries`: the new list is a superset of the old one, so
-- nothing already written can fail it, and if something somehow did, this
-- migration stops rather than a constraint quietly ceasing to hold. The two
-- statements are one transaction -- Flyway runs a migration inside one on
-- Postgres -- so there is no window in which the table has no such rule.
ALTER TABLE entries DROP CONSTRAINT entries_kind_is_known;

-- The same list V11 wrote, plus one, and rewritten in full rather than patched:
-- a reader of this file should be able to see the whole classified set without
-- also reading V11.
ALTER TABLE entries ADD CONSTRAINT entries_kind_is_known CHECK (kind IN (
    'utterance',
    'answer',
    'tool_result',
    'summary',
    'attempt_failed',
    'runtime_note',
    'plan',
    'diagnostic'
));

-- V11's comment named the set, so it is now out of date by one. Restated in full
-- for the same reason the CHECK is: the catalogue is what somebody reads who is
-- not reading migrations at all.
COMMENT ON COLUMN entries.kind IS
    'What kind of thing this is. utterance, answer, tool_result and summary '
    'project into a request as user, assistant, tool and system; '
    'attempt_failed, runtime_note, plan and diagnostic are recorded and never '
    'reach a model. A kind with no role does not project, so a kind added '
    'without a decision is invisible rather than accidentally visible. A '
    'diagnostic is the harness noting what it did to this conversation -- '
    'compaction triggered, and the like -- which is about the conversation and '
    'never part of it.';

-- The system block a turn actually went out with.
--
-- WHAT WAS WRONG, AND WHY IT WAS WRONG ON THE ONE SCREEN THAT CANNOT AFFORD IT.
-- `GET /v1/conversations/{id}/projection?turn=` answers what a turn was shown.
-- V11's log makes the HISTORY half of that exact -- `EntryStore.thatProjectedAt`
-- reconstructs the rows a turn's prompt was assembled from, bounded by the
-- ordinal of that turn's first entry -- and the other half was not stored
-- anywhere at all. The agent's prompt comes out of a `.md` file an operator
-- edits between turns, and the route read that file AS IT STANDS NOW and put
-- today's text at the front of a projection of a turn from last week. Nothing in
-- the answer said so. That is a confident wrong answer on an audit screen, which
-- is the failure shape this project keeps naming and this migration closes.
--
-- CONTENT-ADDRESSED, AND THIS IS THE DECISION WORTH ARGUING. The obvious column
-- is `turns.system_block TEXT` holding the text. A shipped prompt is ~5 kB and
-- is MOSTLY STATIC -- an operator edits an agent rarely and speaks to it often
-- -- so a 200-turn conversation would carry about a megabyte of byte-identical
-- text, once per turn, for a value that changed zero times. This codebase
-- measures that kind of cost rather than shrugging at it:
-- implementation rationale records a 34 MB read
-- that nothing noticed, and `EntryStore.thatProjectFor`'s reasoning about what
-- deliberately does not travel is the same argument one layer up. So the block
-- is written ONCE per distinct text, and a turn points at it.
--
-- WHAT THIS DOES NOT VERSION, named so it is a decision and not an oversight. A
-- projection also carries the agent's TOOL schemas, and those change the same
-- way a prompt does, with the same consequence for the same screen. Same
-- problem, same shape of answer, and out of scope here.

-- ---------------------------------------------------------------------------
-- 1. the blocks
-- ---------------------------------------------------------------------------

-- One row per distinct system block any turn has ever gone out with.
--
-- THE KEY IS THE BLOCK'S OWN HASH AND NOT A SEQUENCE. A synthetic id would need
-- a UNIQUE on `body` to make "insert if absent" mean anything, and Postgres
-- cannot build one: a btree index entry is capped near 2704 bytes and a shipped
-- prompt is twice that, so the index would be REFUSED AT CREATE TIME for exactly
-- the blocks this table exists to hold. A hash is a fixed-width key over
-- arbitrary text, which is the property wanted, and it makes the insert-if-
-- absent an ON CONFLICT on the primary key rather than a read-then-write.
--
-- SHA-256, AND THE CHOICE IS AN IDENTITY RATHER THAN A SECURITY CHECK. Nothing
-- here is defending against an adversary picking a collision; the agent files
-- are an operator's own. What a collision WOULD do is decide that two different
-- prompts are one, and then serve one agent's block as another agent's history
-- -- silently, correctly formatted, on the screen a person opened precisely
-- because they did not want to be told a plausible story. So the width is
-- chosen against accident, not against attack:
--
--   * A 32-BIT DIGEST -- `String.hashCode`, CRC32 -- IS REJECTED. The birthday
--     bound puts an even chance of a collision at about 77,000 distinct blocks,
--     and a deployment that edits its agents while it runs reaches thousands
--     without trying. A one-in-a-few-thousand chance of a wrong audit answer is
--     not a rounding error, it is the defect.
--   * MD5 IS REJECTED, and not on strength grounds -- 128 bits is ample against
--     accident. It is rejected because it costs the same as SHA-256 over 5 kB
--     and buys a reader a question: every maintainer meeting an MD5 has to work
--     out whether somebody made a security claim badly. SHA-256 is in the JDK
--     with no dependency, and 64 hex characters is what a reader already reads
--     as "a digest of the thing".
--
-- The hash is taken over the block's UTF-8 bytes EXACTLY AS SENT, with no
-- trimming and no normalisation. Two files differing by trailing whitespace are
-- two different things a model was shown, and folding them together here would
-- lose a difference this table exists to keep.
CREATE TABLE system_blocks (
    -- Lowercase hex SHA-256 of `body`'s UTF-8 bytes. `archive.TurnStore.hashOf`
    -- is the one place that computes it.
    hash TEXT NOT NULL,

    -- The block itself, whole. Never abbreviated and never a reference to a
    -- file: a file is a thing that changes, and the whole point of the row is to
    -- hold what a file said at a moment that has passed.
    body TEXT NOT NULL,

    CONSTRAINT system_blocks_are_named_by_their_hash PRIMARY KEY (hash),

    -- A blank block is not a block. `JobRuntime.oneSystemMessageFirst` sends NO
    -- system message at all when there is no system text -- "a blank system turn
    -- is not the same input as no system turn, and a small model notices" -- so
    -- a row holding the empty string would be a record of a message that was
    -- never sent. The absence is `turns.system_block IS NULL` and it is one
    -- column over.
    CONSTRAINT system_blocks_a_block_is_not_blank CHECK (body <> ''),

    -- The key really is a hash of the body and not whatever a caller had handy.
    -- This cannot check that the digest MATCHES -- Postgres has no sha256 over
    -- text without pgcrypto, and adding an extension to restate a rule one
    -- writer already holds is a second mechanism for one fact -- but it does
    -- refuse the shapes a caller that skipped the hashing would produce: a file
    -- path, an agent's name, an uppercase digest from a different formatter.
    CONSTRAINT system_blocks_hash_is_a_sha_256 CHECK (hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE system_blocks IS
    'One row per distinct system block any turn has gone out with, keyed by the '
    'SHA-256 of its own bytes. Written once however many turns sent it; a turn '
    'that ran after somebody edited the agent''s file writes a second row. '
    'Nothing deletes from here: a block is still the record of what a turn was '
    'shown after the last turn referencing it is read for the last time.';

-- ---------------------------------------------------------------------------
-- 2. the reference from a turn
-- ---------------------------------------------------------------------------

-- The block this turn went out with, or NULL for a turn that did not record one.
--
-- NULLABLE, AND NEVER BACKFILLED. THIS IS THE POINT OF THE COLUMN AND NOT A
-- CONCESSION TO ONE. Every row `turns` already holds was written by a server
-- that stored nothing about the prompt text, and there is nowhere to recover it
-- from: `entries` has no kind for a system message -- `entries_kind_is_known` is
-- closed over eight and none of them is one -- and the agent's file today is a
-- file today. So NULL means exactly "this turn did not record its block", and
-- `Compaction.projectionAsOf` answers such a turn with the CURRENT definition
-- while saying on the wire that it did so.
--
-- A BACKFILL FROM TODAY'S FILES WAS REJECTED, and it is the tempting wrong move,
-- so it is written down. It would fill the column with the agent's prompt AS IT
-- STANDS NOW for every historical turn, and the projection route would then
-- report those turns as having been sent that text -- which is precisely the
-- assertion this whole feature exists to stop making, restated as a fact in the
-- database where nothing downstream could ever tell it from a real recording.
-- V17's `turns.agent` took the same decision for the same reason one column
-- over: "a backfill to the interlocutor's name would be a guess written down as
-- a fact". NOT NULL is not available and inventing a value to make it available
-- is worse than the null.
--
-- IT IS ALSO NULL FOR A TURN WHOSE BLOCK COULD NOT BE STORED. `Compaction`
-- records the block beside the turn row and swallows a failure to do so, on
-- `turns.record`'s own terms -- the turn is the work and this is the audit trail
-- beside it. That turn reads back as not recorded, which is true of it.
ALTER TABLE turns ADD COLUMN system_block TEXT;

-- A recorded block is one this table holds. Named rather than left to Postgres's
-- `turns_system_block_fkey`, on V7's terms: a violated key should read as the
-- rule it is rather than as a column and a suffix.
--
-- No ON DELETE clause, for V7's and V17's reason exactly: nothing in this schema
-- deletes a block, so there is no delete path to choose a behaviour for, and a
-- CASCADE written ahead of one would silently discard the record of what a turn
-- was shown.
ALTER TABLE turns ADD CONSTRAINT turns_a_recorded_block_is_one_this_table_holds
    FOREIGN KEY (system_block) REFERENCES system_blocks (hash);

-- Reading a turn's block is `WHERE conversation_id = ? AND ordinal = ?` and then
-- one primary-key lookup on `system_blocks`, so both sides are already indexed
-- -- `turns_one_per_place_in_a_conversation` is the first and
-- `system_blocks_are_named_by_their_hash` is the second. NO INDEX IS ADDED HERE.
-- The one query that would want `turns (system_block)` is "which turns sent this
-- block", and nothing asks it; an index nothing reads is a question nobody asked
-- paid for on every insert, which is V7's own rule about a column with no reader.

COMMENT ON COLUMN turns.system_block IS
    'The SHA-256 of the system block this turn was actually sent, into '
    'system_blocks, or NULL for a turn that did not record one -- every turn '
    'written before V32, and any turn whose block could not be stored. NULL is '
    'never filled in: a backfill from the agent''s current file would assert '
    'that a past turn was sent today''s prompt, which is the claim this column '
    'exists to stop. The projection route answers a NULL turn with the current '
    'definition and says on the wire that it did.';

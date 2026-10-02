-- A job becomes a row, and the row is pruned by policy after a while.
--
-- THE DEFECT. `agents.JobStore` minted `"job_" + String.format("%06d",
-- ids.incrementAndGet())` from an in-process `AtomicLong`. The counter starts at
-- zero every boot, so the first run after a restart is `job_000001` again --
-- which is also what the first run of last Tuesday was called. Two unrelated
-- runs on two different days share an identifier. That is not a theoretical
-- hazard: a probe script broke on it, having kept a job id across a restart and
-- been handed a different run's answer under the name it remembered.
--
-- An id that is only unique within one process life has two consequences and the
-- second is the larger one. Nothing durable can reference a job -- there is
-- nothing to put in a foreign key that would still mean the same thing tomorrow
-- -- and so nothing durable says a run happened at all. `JobStore`'s own javadoc
-- argued that this was the right trade, on the ground that "a run in progress
-- does not survive a restart either, so a durable job row would come back saying
-- RUNNING about a thread that no longer exists", and that what has to be durable
-- is what a run PRODUCED. The second half is still true and this table does not
-- touch it: memories, proposals, entries, summaries all live where they lived.
-- The first half stopped being the whole answer when a summariser cascade became
-- ~26 minutes on one handle. Losing the handle to a restart now loses a person's
-- ability to find out what happened, and "the work survives" is not the same
-- sentence as "somebody can tell that an ingest ran".
--
-- THE OWNER'S DECISION, quoted rather than paraphrased:
--
--     as for the job identity -> write it to a new table as a decision that gets
--     pruned by policy after a while
--
-- Both halves are load-bearing. A job is a row; and jobs age out, because they
-- are operational records rather than anybody's conversation.

CREATE TABLE jobs (

    -- `job_` under `protocol.MemoryIds.mint`, which is the FOURTH caller of that
    -- scheme after `mem_`, `prp_` and `cnv_`. Ten hex digits of milliseconds
    -- from a 2020 epoch, zero-padded, then six of randomness -- so string order
    -- is minting order and `ORDER BY id` means "in the order things happened".
    --
    -- THAT ORDERING IS THE WHOLE REASON IT IS THIS SCHEME AND NOT A HASH OR A
    -- UUIDv4. A trajectory view is a list of runs in the order they were
    -- started; a random id would make that ordering depend on a timestamp column
    -- being present, correct and consulted on every read, which is exactly the
    -- consistency `MemoryIds`' javadoc says the padding exists to remove. The
    -- ten digits run out in 2054 and the class says so once, in one place.
    --
    -- AND IT IS NOT DERIVED FROM THE PROJECT OR THE WORKSPACE. Both of those
    -- move now -- `project_move` renames a project, `moveWorkspace` relocates
    -- one -- and putting a changeable fact inside an immutable key is precisely
    -- what `V14__project_ids.sql` introduced a surrogate key to STOP. A job id
    -- that spelled its project would be wrong the first time somebody renamed
    -- one, and wrong silently, because nothing reads a key for its parts. The
    -- project belongs on this row as a reference and it is the column below.
    id           TEXT        PRIMARY KEY,

    -- What the job is polled under: an agent's name for a run of one agent, and
    -- the caller's own name for work that is not -- `Curator.BY` for a pass,
    -- `DocumentController.BY` for an ingest. `Job.agent` reports the same
    -- string, which is what makes a listing read as something other than a
    -- column of identifiers, and this column is that string written down.
    --
    -- NOT A FOREIGN KEY to anything. There is no table of agents -- an agent is
    -- a file in a directory an operator owns -- and two of the three names above
    -- are not agents at all. The same reasoning `conversations.agent` carries.
    agent        TEXT        NOT NULL,

    -- The project this run answered in, as a REFERENCE, and NULL is the global
    -- tier. `conversations.project_id` exactly, for `V6`'s reason: a run that
    -- names no project is the ordinary shape of a caller who did not say which,
    -- not a degenerate one, so a NOT NULL here would make a job the only thing
    -- in this server that cannot be global.
    --
    -- A surrogate id and not the name, which is the whole of `V14`: a project
    -- that is renamed keeps its rows, and a job row that spelled the old name
    -- would be a record pointing at a project nobody can look up.
    project_id   BIGINT,

    -- When the run was submitted, on the same clock that minted the id.
    --
    -- WRITTEN FROM THE STORE'S OWN INSTANT AND NOT `DEFAULT now()`, and not
    -- because a test needs to choose it. `MemoryIds`' javadoc names the failure:
    -- an id whose embedded timestamp disagrees with the timestamp beside it
    -- "would sort into a position its own history contradicts". The id and this
    -- column come from one `Instant`, taken once, so `ORDER BY id` and `ORDER BY
    -- started_at` cannot disagree about which of two runs came first.
    started_at   TIMESTAMPTZ NOT NULL,

    -- When it finished, and NULL means this process never filed an outcome for
    -- it.
    --
    -- WHICH IS TWO DIFFERENT SITUATIONS AND THE ROW DOES NOT DISTINGUISH THEM,
    -- deliberately. A NULL here is either a run going on right now or a run that
    -- was going on when the process died. Nothing durable can tell those apart
    -- -- that is the same fact `JobStore`'s javadoc states from the other side,
    -- and a `RUNNING` written into a column would be this table asserting
    -- something it cannot know after a restart. What CAN tell them apart is
    -- `JobStore` itself, which holds the live handles for this process life and
    -- remains the authority on what is running now. This table's claim is
    -- narrower and durable: this job existed, it started then, and here is how
    -- it ended if anything ever filed one.
    ended_at     TIMESTAMPTZ,

    -- Which of `Outcome.Ending`'s eight, by the constant's own name. `turns.ending`
    -- exactly, including the choice to store `name()` rather than a wire form:
    -- an ending this server cannot read back is a row written successfully and
    -- unreadable for ever, and the enum is the one place that can produce it.
    ending       TEXT,

    -- What the run did, as its own loop counted: turns taken and model calls
    -- made. `Outcome.steps` and `Outcome.modelCalls`.
    --
    -- HERE AND NOT ONLY ON `turns`, and the two are not the same number. A
    -- `turns` row exists per turn of a conversation and only for a run that HAS
    -- one; a curator pass is a job with no conversation of its own, and an
    -- ingest is one job whose calls are spread across ~220 conversations. So
    -- "what did this job cost" is a question `turns` cannot answer for the two
    -- most expensive things this server runs, and these two columns are the
    -- answer that survives the process.
    steps        INT,
    model_calls  INT,

    -- An id nothing can name is a job nothing can poll. `conversations_id_named`
    -- and `projects_name_named` say the same about their own keys; the store
    -- refuses it first and this stops anything that bypasses the store.
    CONSTRAINT jobs_id_named CHECK (id <> ''),

    -- `JobStore.submit(String, Function)` already refuses a blank name -- "a job
    -- needs a name to be polled under" -- and this is that sentence held where a
    -- second writer cannot get past it.
    CONSTRAINT jobs_agent_is_named CHECK (agent <> ''),

    -- The project has to be one, which is the whole point of the surrogate key.
    CONSTRAINT jobs_project_exists FOREIGN KEY (project_id) REFERENCES projects (id),

    -- V7's list plus V9's `STUCK`, which is `Outcome.Ending` in full. Written
    -- out rather than referred to, because a CHECK cannot refer to another one,
    -- and a ninth ending would come with a migration here exactly as V9 was one
    -- for `turns`.
    CONSTRAINT jobs_ending_is_known CHECK (ending IS NULL OR ending IN (
        'ANSWERED',
        'TURN_CAP',
        'CALL_BUDGET',
        'CANCELLED',
        'STUCK',
        'UNAVAILABLE',
        'SUB_AGENT_FAILED',
        'SESSION_GONE'
    )),

    -- A job has ended or it has not, and every column that describes the ending
    -- agrees about which. Four separate constraints rather than one conjunction,
    -- on `V5`'s and `V6`'s reasoning: one predicate reporting any of four faults
    -- makes Postgres name a rule without naming which half broke, and "it ended
    -- and said nothing about how" sends a reader somewhere different from "it
    -- has not ended and reports a count anyway".
    CONSTRAINT jobs_an_ended_run_says_how CHECK ((ended_at IS NULL) = (ending IS NULL)),
    CONSTRAINT jobs_an_ended_run_says_how_many_steps
        CHECK ((ended_at IS NULL) = (steps IS NULL)),
    CONSTRAINT jobs_an_ended_run_says_how_many_calls
        CHECK ((ended_at IS NULL) = (model_calls IS NULL)),

    -- Counted things are not negative. Zero is ordinary and is not the same as
    -- absent: `JobStore`'s last-resort clause files an `UNAVAILABLE` outcome
    -- with two zeroes for a run that failed before it could count anything, and
    -- its comment calls those "the honest numbers here and misleading anywhere
    -- else". A row holding them is a run that really did nothing.
    CONSTRAINT jobs_counts_are_not_negative
        CHECK ((steps IS NULL OR steps >= 0) AND (model_calls IS NULL OR model_calls >= 0)),

    -- And it did not finish before it started. Equality is allowed: a run that
    -- failed in the same millisecond it was submitted is a fixture and a real
    -- possibility, not a fault -- the allowance
    -- `conversations_turn_is_not_before_the_conversation` makes for the same
    -- reason one table over.
    CONSTRAINT jobs_did_not_end_before_it_started
        CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- The one read that is not by primary key: everything older than a cutoff, which
-- is what a prune selects. Leads with the column the WHERE names.
--
-- `started_at` and not `ended_at` is the prune key, and that choice is here
-- rather than in Java. A row that never ended -- the restart case above -- has
-- no `ended_at` to be older than anything, so a policy written on that column
-- would keep every lost run for ever, which is the opposite of what an operator
-- asking for pruning wants. `started_at` is NOT NULL, never moves, and the
-- longest run this server can produce is minutes against a policy measured in
-- days, so nothing is pruned out from under itself.
CREATE INDEX jobs_by_age ON jobs (started_at);

-- HOW IT IS PRUNED, AND WHY THAT IS A DELETE WHERE V19 REFUSED ONE.
--
-- `POST /v1/retention/sweep` gains a third stage, after ejecting what was marked
-- and marking what policy selects: delete the job rows older than
-- `plowshare.jobs.retention-after-days`. It belongs there and not in a mechanism
-- of its own because `archive.Retention` already carries the four reasons the
-- trigger is an explicit operation rather than a timer -- a sweep deletes bytes
-- and a timer deletes them with nobody watching; a timer started from a bean
-- fires in a test suite that stands up dozens of Spring contexts; several
-- servers on one database is several sweeps racing; and the operator already has
-- cron. Every one of those applies unchanged to job rows, so a second trigger
-- would be a second thing to get wrong for no new argument. The sweep is safe to
-- call twice and a prune does not change that: a second call finds nothing older
-- than the cutoff that is still there.
--
-- IT IS NOT `ConversationLifecycle` AND IT MUST NOT BECOME IT. `V19` argues at
-- length that a conversation is DEMOTED and its payload EJECTED, and that
-- nothing deletes the row -- because a trajectory with holes in it is worse than
-- a trajectory that is large. That argument is about a record of what was SAID,
-- to and by a person, which is the thing this server exists to keep. A job row
-- is not that. It holds no text: an identifier, an agent's name, a project, two
-- instants and three counts, and every readable thing the run produced is in
-- another table under its own retention. There is nothing here to eject, because
-- there is no payload; there is no staged path, because there is nothing to
-- recover in the window; and there is no `lifecycle` column, because a state
-- machine over a row with nothing in it is machinery with no decision to hold.
-- An earlier draft of the retention design was corrected for borrowing the
-- memory archive's vocabulary for something that was not a memory, and reusing
-- `active / to_be_ejected / ejected` here would be the same mistake with the
-- conversation archive's vocabulary. Jobs age out. That is the whole policy.
--
-- AND IT IS OFF BY DEFAULT, on `RetentionPolicy`'s reasoning exactly: too large
-- costs disk and too small deletes something, the two are not symmetric, and
-- there is no arithmetic for "how many days a job record is worth keeping". An
-- unconfigured server keeps every job row for ever and a sweep prunes none.

-- WHAT `entries` DOES NOT GAIN, WHICH WAS THE OTHER HALF OF THE DECISION.
--
-- implementation rationale §2 says a job column on `entries` is what §3.1 and §3.3 would
-- attach to. It is not added here, and the reason is not the cost of a column --
-- though `entries` is the hottest table in this schema and carries two generated
-- tsvectors and a GIN index already, so it is not nothing.
--
-- The reason is that it would be a per-message copy of a per-run fact. The
-- finest grain at which "which job wrote this" varies inside a conversation is
-- the TURN: a run is a turn, `entries.turn_ordinal` already partitions a
-- conversation's entries by it, and `turns` already holds exactly one row per
-- run. So a job reference on `entries` would repeat, on every message, tool call
-- and tool result, a value that changes once per turn -- and the first time
-- anything wrote one of them differently, the rows of a single turn would
-- disagree about which run produced them, with no constraint able to say which
-- was right. That is the denormalisation `V14` was written against, on the table
-- where it costs the most.
--
-- What §3.2 asked for -- "one column plus a digest ... attaches to the job row
-- from §2" -- attaches to THIS table, where a run is one row, and that is the
-- shape it wanted. If per-run attachment inside a conversation is ever needed as
-- well, `turns` is the place: one row per run, already there, already carrying
-- the agent and the ending.

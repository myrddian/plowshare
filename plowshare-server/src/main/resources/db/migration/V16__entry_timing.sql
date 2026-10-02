-- When each thing in a conversation happened, and -- for the two kinds that
-- record an operation this server performed -- how long that operation took.
--
-- WHAT WAS MISSING, AND IT WAS ALL OF IT. `entries` and `turns` carried no time
-- at all; only `conversations` had `created_at` and `last_turn_at`. Timing
-- EXISTS -- the CLI prints `6.7s +6.7s tool called file_read` while a run goes
-- -- but it is computed from the arrival of a `JobEvent` on the client and is
-- never written anywhere. `JobEvent`'s own javadoc says so and gives the reason
-- it is right for that record: "a listener stamps what it receives, and a server
-- clock on a droppable stream would be a second, partial record of a run whose
-- real record is JobStore's". That argument is about the EVENT STREAM and it
-- does not extend to the log. The consequence of having only the stream is that
-- YOU CAN WATCH A RUN AND YOU CANNOT REVIEW ONE: a conversation read back an
-- hour later says what was said and in what order, and cannot say when, or how
-- long any of it took, or which part of a slow turn was slow.
--
-- TWO COLUMNS AND NOT ONE, AND THEY ARE NOT THE SAME FACT. `recorded_at` is when
-- an entry was written. `took_ms` is how long the operation that produced it
-- ran. The second does not follow from the first, in either direction, and the
-- reason is the whole argument for storing it:
--
--   AN ENTRY IS WRITTEN WHEN SOMETHING COMPLETES. There is no entry at the
--   moment a model call is SENT. So the gap between the `utterance` row and the
--   `answer` row is not a model call's duration -- it contains the history read,
--   the request build, queueing at the endpoint, prefill, decode, and, on any
--   turn past the first, whole tool calls in between. Subtracting two
--   `recorded_at` values gives a number that is WRONG IN A WAY NOTHING
--   DOWNSTREAM CAN DETECT, which is worse than having no number: it is a
--   confident answer to a question nobody measured. This schema has refused that
--   shape before -- `turns_prompt_tokens_are_a_measurement` refuses the zero
--   that would make "nobody measured" look like "it was free" -- and this is the
--   same refusal one table over.
--
-- So a duration is MEASURED WHERE THE OPERATION HAPPENS and carried to the row
-- with the thing it produced. `JobRuntime` already brackets both operations by
-- construction: it publishes `watch.modelCall(...)` before it spends the budget
-- and holds the `Completion` afterwards, and it calls `tool.run(...)` and holds
-- the result afterwards. The interval was always observable at runtime; it was
-- simply never written down. `LoggedEntry.took` is what carries it, and
-- `EntryStore.append` is what puts it here.
--
-- WHY `turns` GETS NEITHER COLUMN, WHICH IS A DECISION AND NOT AN OMISSION.
-- V7 declined a per-turn instant and named the condition on which it would be
-- reconsidered -- "if a per-turn instant ever gains a reader it is one migration
-- away". It has gained one, and the answer is still no, for three reasons that
-- are about this table rather than about that one:
--
--   * A TURN'S SPAN IS NOW DERIVABLE FROM ITS OWN ENTRIES, and this is the
--     "prefer deriving over storing a second copy" rule that V6 applies to a
--     conversation's budget. The first entry of turn n is its `utterance` and
--     the last is the `answer` that closes it, so `MIN(recorded_at)` and
--     `MAX(recorded_at)` over `turn_ordinal = n` are the two ends. ONE FILTER IS
--     REQUIRED AND A READER WHO OMITS IT GETS A WRONG ANSWER: a `summary` row
--     carries THE REACH as its `turn_ordinal` -- V11's own column comment says
--     "or for a summary the last turn it stands for" -- and it is appended long
--     after that turn ended, on a fold's own thread. So a span is taken over
--     `kind <> 'summary'`. That trap is written here because it is this file's
--     to warn about: nothing else in the schema makes `turn_ordinal` mean two
--     things.
--
--   * THE ROW THAT WOULD CARRY IT IS THE ONE THAT IS MISSING WHEN IT MATTERS
--     MOST. V11 argues at length that there is no foreign key from `entries` to
--     `turns` because "a turn row is written when the turn ENDS ... and entries
--     are written as the turn RUNS", and that "a run that dies mid-tool-call
--     leaves entries and no turn row at all". A timestamp on `turns` would
--     therefore be absent for exactly the runs whose timing somebody most wants
--     to review -- the one that hung, the one that was killed, the one whose
--     client went away -- while the entries of those runs are all still here
--     with their times on them. A column that is present for the healthy case
--     and absent for the interesting one is not a record of when things
--     happened.
--
--   * A `started_at` ON `turns` WOULD BE RECONSTRUCTED, NOT MEASURED, which is
--     the same fault this file exists to refuse. `TurnStore.record` runs once,
--     when the turn is already over; it has no memory of when the turn began, so
--     the number would have to be carried through `agents.Turn` from somewhere
--     -- and the somewhere is the moment the `utterance` entry is written, which
--     is the value this table already holds.
--
-- What `turns` keeps is what it already had and what no entry can answer:
-- `ending` and `prompt_tokens`. Nothing here is removed and nothing there is
-- duplicated.

-- WHEN THIS ENTRY WAS WRITTEN, which for every kind this server appends is when
-- the thing it records finished.
--
-- WRITTEN BY THE STORE FROM AN INJECTED CLOCK AND NOT `DEFAULT now()`, which is
-- V6's choice for `conversations.created_at` and is made here for its reason
-- exactly: this value is on `EntryRecord`, so a test asserting on it has to be
-- able to choose it, and `now()` inside Postgres is a clock no test can pull on.
-- It is also the clock a duration is measured against, and a measurement taken
-- against one clock and stamped against another is two answers to one question.
--
-- NULLABLE, so this migration runs against a live `entries` without rewriting
-- history -- V13's sentence about `handle`, for the same reason and with the
-- same meaning on an old row: NULL is "this entry is older than the mechanism",
-- never "this entry happened at no time". THE BACKFILL WAS CONSIDERED AND
-- REFUSED. The only times available to it are `conversations.created_at` and
-- `last_turn_at`, and both are facts about the CONVERSATION; stamping every
-- entry of a fifty-turn conversation with the moment it was opened would put a
-- number in a column whose whole purpose is to be trusted, and the first
-- duration anybody derived from a pair of them would be zero. An absence a
-- reader can see is worth more than a plausible number, and this project has
-- said so before -- see implementation rationale on the cache hit rate that cannot
-- be obtained and must be reported as unavailable rather than estimated.
--
-- NOT NULL IS THEREFORE NOT AVAILABLE HERE and is not simulated with a CHECK
-- over new rows, which Postgres cannot express and which would be a rule that
-- means different things on two halves of one table. What holds it for
-- everything written from now on is `EntryStore.append`, which takes the stamp
-- from its own clock and has no parameter a caller could omit it through --
-- `ordinal`'s rule and `handle`'s.
ALTER TABLE entries ADD COLUMN recorded_at TIMESTAMPTZ;

-- HOW LONG THE OPERATION THAT PRODUCED THIS ENTRY TOOK, in milliseconds, or NULL
-- for an entry that records no operation and for one nobody measured.
--
-- MILLISECONDS AND NOT AN `INTERVAL`, and not nanoseconds. An interval would be
-- the more expressive type and it is the wrong one: every reader of this number
-- -- a lane strip, a "this turn took 41 s" line, an MCP tool putting it in front
-- of a model -- wants an integer it can do arithmetic on, and Postgres's
-- interval arrives in Java as a driver-specific type that each of them would
-- have to convert. Milliseconds because that is the resolution the thing being
-- measured has: a model call is seconds, a file read is milliseconds, and a
-- number finer than the operation is a false claim about how well it was
-- measured.
--
-- `BIGINT` AND NOT `INT`, WHICH IS FOUR BYTES A ROW TO DELETE A FAILURE MODE.
-- An INT holds 24 days of milliseconds, which is far past anything this server
-- can produce -- every model call is under a pool's stream deadline and every
-- tool call under an HTTP timeout. It was still the wrong type, because the code
-- that fills it would then have to decide what to do with a `Duration` that does
-- not fit, and both answers are bad: throwing puts an arithmetic error inside a
-- running turn, and clamping writes a number that is not the measurement while
-- looking exactly like one. A `long` of milliseconds cannot overflow from any
-- `Duration` a run can hold, so there is no branch to get wrong and no ceiling
-- for a reader to mistake for a value.
--
-- ZERO IS LEGITIMATE HERE, WHICH IS THE OPPOSITE OF `turns.prompt_tokens` AND
-- THE CONTRAST IS WORTH READING. A prompt of no tokens does not exist, so a zero
-- there is a caller that had no number and wrote one anyway. An operation that
-- took under a millisecond DOES exist -- a `memory_read` that hits a warm row, a
-- tool that refuses its arguments before doing anything -- so a zero here is a
-- measurement and refusing it would push exactly those operations into the NULL
-- that means "not measured". The two columns say opposite things about the same
-- digit because the two quantities are different, and each says it in its own
-- CHECK.
ALTER TABLE entries ADD COLUMN took_ms BIGINT;

-- A duration is a length of time and never a negative one. Nothing in Java can
-- produce one -- the two measurements are taken from a single clock read before
-- and after -- so this is V2's and V5's argument again: what holds it today is
-- one method, and a second writer, a migration or a psql session does not go
-- through it.
ALTER TABLE entries ADD CONSTRAINT entries_a_duration_is_not_negative
    CHECK (took_ms IS NULL OR took_ms >= 0);

-- ONLY AN ENTRY THAT RECORDS AN OPERATION THIS SERVER PERFORMED MAY CARRY ONE,
-- and the list is exactly the two this build writes a duration for:
--
--   * `answer` -- one model call. The entry holds what that call produced, so
--     the duration is that call's;
--   * `tool_result` -- one tool call, including one that failed, since the
--     result records the failure and the time it took to arrive at it.
--
-- WHY EVERY OTHER KIND IS REFUSED RATHER THAN LEFT NULL BY CONVENTION. An
-- `utterance` is a person speaking and this server did not do it -- a number
-- there could only be the time spent waiting for a human, which is not an
-- operation and not this system's to charge. A `runtime_note` and a `plan` are
-- text the harness already had. A `diagnostic` is the harness talking about the
-- conversation. An `attempt_failed` is written by `agents.Turn` from an
-- `Outcome` after the run is over, and it stands for a whole run rather than for
-- one call, so a duration on it would be a fourth thing called a duration in a
-- table that now has one meaning for the word.
--
-- `summary` IS THE ONE ABSENCE THAT IS ARGUABLE, and it is deliberate. A fold's
-- summary really is the product of a model call -- `Compaction.summarise` makes
-- one -- and that call is not timed today: `summarise` returns a String, and
-- threading a measurement out of it is a change to the fold path, which is on
-- its own thread, has its own failure handling, and is not what this slice is
-- about. So the kind is refused rather than allowed-and-always-NULL, on V13's
-- reasoning about `handle`: a column confined by a CHECK to the kinds that have
-- a reason for it beats one confined by whatever the writing code remembers.
-- ADDING `summary` LATER IS A MIGRATION, and that is the price this schema
-- already charges for widening a closed list -- V9 for an eighth ending, V12 for
-- an eighth kind. It is a small price and it is paid by whoever has actually
-- measured the call.
ALTER TABLE entries ADD CONSTRAINT entries_only_a_completed_operation_is_timed
    CHECK (took_ms IS NULL OR kind IN ('answer', 'tool_result'));

COMMENT ON COLUMN entries.recorded_at IS
    'When this entry was written, which for every kind this server appends is '
    'when the thing it records finished. NULL for an entry written before V16. '
    'Written by EntryStore.append from an injected clock and never by its '
    'caller. DO NOT SUBTRACT TWO OF THESE AND CALL IT A DURATION: the gap '
    'between two entries contains everything that happened between them, '
    'including whole tool calls. took_ms is the measured number.';

COMMENT ON COLUMN entries.took_ms IS
    'How long the operation that produced this entry took, in milliseconds, '
    'measured where the operation was performed -- around the model call for an '
    'answer, around the tool for a tool_result. NULL for a kind that records no '
    'operation, for an entry written before V16, and for an answer written by '
    'agents.Turn out of an Outcome rather than out of a completion. Zero is a '
    'real measurement of an operation that took under a millisecond, unlike '
    'turns.prompt_tokens where zero is refused.';

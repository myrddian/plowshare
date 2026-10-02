-- A refusal is recorded and never shown, and an answer says which model wrote it.
--
-- WHAT WAS MISSING. An `answer` row said what the model said and, since V16, how
-- long the call took. It did not say WHICH model: `turns.agent` names the agent,
-- the agent names a specifier, and a specifier is resolved per call to whichever
-- pool is lightest. That was an inference an operator could make while one agent
-- meant one model. Refusal fallback ends that. An agent whose definition allows
-- it can have a probable refusal from its own model answered by a fallback
-- class, so one turn holds two model calls from two models, and a log that could
-- not tell them apart would attribute the fallback's words to the model that
-- refused.
--
-- A REFUSAL IS ITS OWN KIND, AND IT HAS NO ROLE. When a refusal is rerouted, the
-- conversation is the person's utterance and the fallback's answer, and nothing
-- else: the next request carries one ordinary assistant message, and the prefix
-- up to the utterance is the one the refused call was sent. The refusal still
-- happened, so it is written down -- when it arrives, with its duration and its
-- model -- as `refusal`, which `entries_role_matches_kind` (V11, a CASE with no
-- ELSE) already confines to a NULL role, and a NULL role is what the projection
-- skips. Nothing is superseded and nothing is edited: the row was never an
-- answer. `attempt_failed`, `runtime_note` and `diagnostic` are the precedent.
--
-- A refusal that is NOT rerouted stays an `answer`, because it is what the
-- assistant said: an agent with no `fallback:`, a fallback that itself failed.
-- Its `completion` column says `refused`, which is how an operator counts them.
--
-- PROVENANCE IS COLUMNS ON `entries` AND NOT A TABLE BESIDE IT. It is a fact
-- about the one row it describes, written in the same INSERT, the same shape as
-- `took_ms`. A side table would be a foreign key into `entries` for a fact with
-- no life of its own. None of it projects: `Projection` reads kind, content,
-- tool calls and supersession, and nothing here is any of those.
--
-- NULLABLE, AND NULL IS "NOT RECORDED" RATHER THAN "NO MODEL". Every answer
-- written before this migration has none, and so does an answer the runtime
-- wrote itself for a stopping ending, which no model call produced.

-- Restated whole from V12, which last defined it; see V9 for why a CHECK is
-- dropped and rewritten rather than altered.
ALTER TABLE entries DROP CONSTRAINT entries_kind_is_known;
ALTER TABLE entries ADD CONSTRAINT entries_kind_is_known CHECK (kind IN (
    'utterance',
    'answer',
    'tool_result',
    'summary',
    'attempt_failed',
    'runtime_note',
    'plan',
    'diagnostic',
    'refusal'
));

-- Restated from V16, which last defined it. A refusal was a model call and its
-- duration is as much a measurement as an answer's.
ALTER TABLE entries DROP CONSTRAINT entries_only_a_completed_operation_is_timed;
ALTER TABLE entries ADD CONSTRAINT entries_only_a_completed_operation_is_timed
    CHECK (took_ms IS NULL OR kind IN ('answer', 'tool_result', 'refusal'));

ALTER TABLE entries ADD CONSTRAINT entries_a_refusal_is_not_blank
    CHECK (kind <> 'refusal' OR content <> '');

ALTER TABLE entries ADD COLUMN invocation        UUID;
ALTER TABLE entries ADD COLUMN produced_by       TEXT;
ALTER TABLE entries ADD COLUMN dispatch          TEXT;
ALTER TABLE entries ADD COLUMN model_specifier   TEXT;
ALTER TABLE entries ADD COLUMN model_pool        TEXT;
ALTER TABLE entries ADD COLUMN wire_model        TEXT;
ALTER TABLE entries ADD COLUMN completion        TEXT;
ALTER TABLE entries ADD COLUMN finish_reason     TEXT;
ALTER TABLE entries ADD COLUMN prompt_tokens     INT;
ALTER TABLE entries ADD COLUMN completion_tokens INT;
ALTER TABLE entries ADD COLUMN reasoning_tokens  INT;
ALTER TABLE entries ADD COLUMN sent_at           TIMESTAMPTZ;
ALTER TABLE entries ADD COLUMN fallback_reason   TEXT;

ALTER TABLE entries ADD CONSTRAINT entries_only_a_model_says_something_by_a_call
    CHECK (invocation IS NULL OR kind IN ('answer', 'refusal'));

-- A refusal is only ever written because a model call produced one.
ALTER TABLE entries ADD CONSTRAINT entries_a_refusal_names_its_call
    CHECK (kind <> 'refusal' OR invocation IS NOT NULL);

-- All or nothing: a call recorded with no agent, specifier or outcome is a row
-- that says a model spoke and cannot say which, or what came of it.
ALTER TABLE entries ADD CONSTRAINT entries_a_model_call_is_recorded_whole
    CHECK ((invocation IS NULL) = (produced_by IS NULL)
       AND (invocation IS NULL) = (dispatch IS NULL)
       AND (invocation IS NULL) = (model_specifier IS NULL)
       AND (invocation IS NULL) = (completion IS NULL)
       AND (invocation IS NULL) = (sent_at IS NULL));

ALTER TABLE entries ADD CONSTRAINT entries_dispatch_is_known
    CHECK (dispatch IS NULL OR dispatch IN ('primary', 'fallback'));

ALTER TABLE entries ADD CONSTRAINT entries_completion_is_known
    CHECK (completion IS NULL OR completion IN ('answered', 'refused', 'cut_off', 'called_tools'));

ALTER TABLE entries ADD CONSTRAINT entries_a_refusal_row_is_a_refused_completion
    CHECK (kind <> 'refusal' OR completion = 'refused');

-- Every call a fallback made says why the fallback was taken.
ALTER TABLE entries ADD CONSTRAINT entries_a_fallback_says_why
    CHECK (dispatch IS DISTINCT FROM 'fallback' OR fallback_reason IS NOT NULL);

ALTER TABLE entries ADD CONSTRAINT entries_token_counts_are_not_negative
    CHECK ((prompt_tokens IS NULL OR prompt_tokens >= 0)
       AND (completion_tokens IS NULL OR completion_tokens >= 0)
       AND (reasoning_tokens IS NULL OR reasoning_tokens >= 0));

COMMENT ON COLUMN entries.kind IS
    'What kind of thing this is. utterance, answer, tool_result and summary '
    'project into a request as user, assistant, tool and system; '
    'attempt_failed, runtime_note, plan, diagnostic and refusal are recorded and '
    'never reach a model. A kind with no role does not project, so a kind added '
    'without a decision is invisible rather than accidentally visible. A refusal '
    'is a model''s probable refusal that the agent''s fallback was dispatched to '
    'answer in its place.';
COMMENT ON COLUMN entries.invocation IS
    'The id of the model call that produced this row, minted by the runtime. NULL '
    'for anything no model call produced, and for answers written before V39.';
COMMENT ON COLUMN entries.produced_by IS
    'The agent whose run made the call. Execution provenance; never projected.';
COMMENT ON COLUMN entries.dispatch IS
    'primary: the agent''s own model. fallback: the class its definition names under '
    'fallback.model, dispatched because the primary call was a probable refusal.';
COMMENT ON COLUMN entries.model_specifier IS
    'What the request named -- a class or a wire model -- as the agent file spells it.';
COMMENT ON COLUMN entries.model_pool IS
    'The pool the dispatcher routed the call to, or NULL when it did not say.';
COMMENT ON COLUMN entries.wire_model IS
    'The model that pool was asked for, or NULL when the dispatcher did not say.';
COMMENT ON COLUMN entries.completion IS
    'What the completion was judged to be: answered, refused, cut_off (finish_reason '
    'length -- a token or context limit, which the endpoint does not distinguish) or '
    'called_tools. Judged by the harness, never by the model.';
COMMENT ON COLUMN entries.fallback_reason IS
    'Why a fallback was taken, on the refusal that caused it and on every call the '
    'fallback made.';

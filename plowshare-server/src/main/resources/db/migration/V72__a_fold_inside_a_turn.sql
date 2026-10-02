-- A fold can now run inside a turn (spec 2026-09-30-fold-at-60-and-80 §1-§2):
-- at a tool-call boundary it summarises the older steps of the turn in
-- progress, and any earlier turns no fold stands for yet, keeping the turn's
-- opening request and its most recent whole steps word for word. What it puts
-- where the older steps were is a new kind of entry, 'turn_summary', which
-- projects as a USER message: it has to be read in place, between the request
-- and the steps kept, and a system message anywhere but first is a request the
-- reference model refuses. The rows it stands for point at it through
-- superseded_by exactly as a between-turn fold's do, so nothing new is needed
-- there, and nothing is deleted.
--
-- WRITTEN AS V70 AND RENUMBERED V72 AT MERGE: master's V70 (a question's options)
-- and V71 (install) landed first. Neither touches the two constraints rewritten
-- here (checked at merge), so V44/V40 are still their latest definitions.
--
-- REWRITTEN FROM THE MIGRATION THAT LAST DEFINED EACH: entries_kind_is_known
-- from V44 (which added 'thinking'; V45-V69 did not touch it), and
-- entries_role_matches_kind from V40 (which added 'notice'; V43 and V44 left it
-- alone, their kinds taking the CASE's NULL). Rewriting one from the file that
-- explains it rather than the one that last defined it silently drops whatever
-- came between.
--
-- NOT REWRITTEN, DELIBERATELY: entries_only_a_completed_operation_is_timed (a
-- turn_summary carries no duration, which it permits for any kind), V39's
-- entries_only_a_model_says_something_by_a_call (no invocation, likewise), V57's
-- speaker checks (only an utterance has a speaker) and V62's outcome/salients
-- checks (only a tool_result and an answer carry those).

ALTER TABLE entries DROP CONSTRAINT entries_kind_is_known;
ALTER TABLE entries ADD CONSTRAINT entries_kind_is_known CHECK (kind IN (
    'utterance', 'answer', 'tool_result', 'summary',
    'attempt_failed', 'runtime_note', 'plan', 'diagnostic',
    'refusal',
    'notice',
    'hook',
    'thinking',
    'turn_summary'
));

ALTER TABLE entries DROP CONSTRAINT entries_role_matches_kind;
ALTER TABLE entries ADD CONSTRAINT entries_role_matches_kind CHECK (
    role IS NOT DISTINCT FROM CASE kind
        WHEN 'utterance'    THEN 'user'
        WHEN 'answer'       THEN 'assistant'
        WHEN 'tool_result'  THEN 'tool'
        WHEN 'summary'      THEN 'system'
        WHEN 'notice'       THEN 'user'
        WHEN 'turn_summary' THEN 'user'
    END
);

-- Restated from V44, which last set it, so the column's own description names
-- the kind this migration adds.
COMMENT ON COLUMN entries.kind IS
    'What kind of thing this is. utterance, answer, tool_result and summary '
    'project into a request as user, assistant, tool and system; notice -- '
    'the harness telling a bot about its speaker''s unread inbox -- and '
    'turn_summary -- what a fold inside a turn put where that turn''s older steps '
    'were -- project as user; '
    'attempt_failed, runtime_note, plan, diagnostic, refusal, hook and thinking '
    'are recorded and never reach a model. A kind with no role does not project, so '
    'a kind added without a decision is invisible rather than accidentally '
    'visible. A refusal is a model''s probable refusal that the agent''s fallback '
    'was dispatched to answer in its place. A thinking row is what a model '
    'streamed as reasoning during one call, just before what that call said.';

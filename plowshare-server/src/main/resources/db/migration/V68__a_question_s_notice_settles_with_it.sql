-- A question's inbox notice leaves the inbox once the question is settled.
--
-- Measured 2026-09-29: in eight hours the person's inbox took 21 approval notices ("Approve
-- running python -m pytest -q in ... ? [apr_...]") and 3 orchestration questions only they could
-- answer, each also answered in the TUI, and every one stayed in the inbox after. "Answers
-- shouldn't land on the inbox -- if they do because they have to, because answer already covers
-- it they shouldn't be there." A notice that asks the person something may still be written (they
-- may be away; it is how they learn of it), but it is settled the moment its question is, and a
-- settled notice is not listed, not counted as unread, and not shown. News is untouched.
--
--   about       what question the notice asks, or null for news: 'approval:<apr id>' for an
--               approval's question, 'question:<orchestration message id>' for a run's question.
--               The message, not the run: a run asks many questions over its life, and a notice
--               keyed on the run would be settled by nothing more specific than "the run is
--               asking again".
--   settled_at  when that question was answered, withdrawn, or its run left asking or ended.
ALTER TABLE user_inbox ADD COLUMN about TEXT;
ALTER TABLE user_inbox ADD COLUMN settled_at TIMESTAMPTZ;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_only_a_question_is_settled
    CHECK (settled_at IS NULL OR about IS NOT NULL);

-- V40's index, for the listing and the count, which now skip settled notices too.
DROP INDEX user_inbox_unread;
CREATE INDEX user_inbox_unread ON user_inbox (handle, arrived_at)
    WHERE read_at IS NULL AND settled_at IS NULL;
-- What InboxStore.settle looks a question up by.
CREATE INDEX user_inbox_open_questions ON user_inbox (about)
    WHERE about IS NOT NULL AND settled_at IS NULL;

-- Backfill, approvals: ApprovalDelivery.question ends every approval question with " [<apr id>]",
-- and its only other notice of that kind ("Approval <id> continued the run, ...") is news. Settled
-- when the approval is no longer asked, or no longer exists.
UPDATE user_inbox
   SET about = 'approval:' || substring(answer from '\[(apr_[0-9a-z]+)\]$')
 WHERE kind = 'approval' AND answer LIKE 'Approve running %'
   AND substring(answer from '\[(apr_[0-9a-z]+)\]$') IS NOT NULL;
UPDATE user_inbox i
   SET settled_at = GREATEST(a.answered_at, i.arrived_at)
  FROM run_approvals a
 WHERE i.about = 'approval:' || a.id AND a.state <> 'asked';
UPDATE user_inbox i
   SET settled_at = now()
 WHERE i.about LIKE 'approval:%' AND i.settled_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM run_approvals a WHERE i.about = 'approval:' || a.id);

-- Backfill, a run's questions: recognised only by the harness's own opening words, which no
-- ending and no stall notice shares -- Utterances.questionForCaller (an ordinary question, "is
-- asking a question"), capQuestion (a cap, either copy: "(<id>): the conductor stopped at"),
-- stuckQuestion ("ended N turns in a row without making progress.") and uncoveredQuestion ("wrote
-- spec.md's acceptance commands,"). An ending reads "(id <id>) finished." or "stopped:", and a
-- stall "has done nothing". A notice in any other words is left as it is. Its question is the run's
-- latest one asked by the time it arrived; it is settled unless that question is still the one its
-- run is asking.
WITH asked AS (
    SELECT i.id, i.arrived_at, COALESCE(
        substring(i.answer from '^The orchestration ''[^'']*'' \(id (orc_[0-9A-Za-z]+)\) is asking a question and waits for the answer\.'),
        substring(i.answer from '^The orchestration ''[^'']*'' \((orc_[0-9A-Za-z]+)\): the conductor stopped at '),
        substring(i.answer from '^`(orc_[0-9A-Za-z]+)` \(`[^`]*`\) ended [0-9]+ turns in a row without making progress\.'),
        substring(i.answer from '^`(orc_[0-9A-Za-z]+)` \(`[^`]*`\) wrote spec\.md''s acceptance commands,'))
        AS orchestration
      FROM user_inbox i
     WHERE i.kind = 'orchestration' AND i.about IS NULL
), question AS (
    SELECT a.id, (SELECT m.id FROM orchestration_messages m
                   WHERE m.orchestration = a.orchestration AND m.kind = 'question'
                     AND m.created_at <= a.arrived_at
                   ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS message
      FROM asked a
     WHERE a.orchestration IS NOT NULL
)
UPDATE user_inbox i
   SET about = 'question:' || q.message
  FROM question q
 WHERE i.id = q.id AND q.message IS NOT NULL;
UPDATE user_inbox i
   SET settled_at = now()
 WHERE i.about LIKE 'question:%' AND i.settled_at IS NULL
   AND NOT EXISTS (
       SELECT 1 FROM orchestration_messages m JOIN orchestrations o ON o.id = m.orchestration
        WHERE i.about = 'question:' || m.id AND o.state = 'asking'
          AND NOT EXISTS (SELECT 1 FROM orchestration_messages later
                           WHERE later.orchestration = m.orchestration
                             AND later.kind = 'question'
                             AND (later.created_at, later.id) > (m.created_at, m.id)));

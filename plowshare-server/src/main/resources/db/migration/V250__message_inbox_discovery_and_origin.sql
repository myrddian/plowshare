-- Numeric tuple keysets match these active/discovery indexes; no expression sort per poll.
CREATE INDEX message_agent_inboxes_active_cursor ON message_agent_inboxes(project_id,topic)
 WHERE retired_at IS NULL;

-- MESSAGE shares the instance's allowance, like a forum seat shares its topic pot.
-- V230 remains the allowance invariant; adding a shared origin does not change it.
ALTER TABLE conversations DROP CONSTRAINT conversations_origin_is_known;
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
    CHECK (origin IN ('turn','delegation','curator','submission','memory','event',
                      'orchestration','board','contained','message'));

-- Reclassify only native private contexts retained by messaging. A route pinned to a person's
-- TURN conversation and a real forum seat retain their original origins and allowance owners.
UPDATE conversations c SET origin='message'
WHERE c.origin='board' AND EXISTS(SELECT 1 FROM message_instances i WHERE i.conversation=c.id)
  AND NOT EXISTS(SELECT 1 FROM board_seats s WHERE s.conversation=c.id);

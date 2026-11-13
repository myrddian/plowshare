-- Different messaging definitions can share history without sharing an execution slot. Reserve
-- turn ordinals before inference so completion order cannot relabel their entries or receipts.
ALTER TABLE conversations ADD COLUMN turn_sequence INTEGER NOT NULL DEFAULT 0 CHECK(turn_sequence>=0);
WITH evidence AS (
 SELECT conversation_id,max(ordinal) AS ordinal FROM turns GROUP BY conversation_id
 UNION ALL SELECT conversation_id,max(turn_ordinal) FROM entries GROUP BY conversation_id
), high AS (SELECT conversation_id,max(ordinal) AS ordinal FROM evidence GROUP BY conversation_id)
UPDATE conversations c SET turn_sequence=high.ordinal FROM high WHERE c.id=high.conversation_id;

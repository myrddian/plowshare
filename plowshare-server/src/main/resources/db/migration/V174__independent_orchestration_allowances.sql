-- Authority ancestry and spending ownership are separate contracts. A conductor keeps its own
-- configured allowance even when launched by another conductor or a board seat. Ordinary
-- agent_run/delegation children must still retain their parent's exact allowance.
-- Existing immutable envelopes and claims are intentionally not rebound or recharged.
CREATE OR REPLACE FUNCTION operation_conversation_valid() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source conversations;
 independent_conductor BOOLEAN;
BEGIN
 SELECT * INTO source FROM conversations WHERE id=NEW.conversation FOR UPDATE;
 independent_conductor=source.origin='orchestration' AND source.parent_id IS NULL AND source.owns_allowance;
 IF NOT EXISTS (
  SELECT 1 FROM operation_admissions a JOIN projects p ON p.id=source.project_id
  WHERE a.id=NEW.operation AND a.sealed AND source.agent=NEW.agent AND p.name=a.project_name
   AND (source.parent_id IS NOT DISTINCT FROM NEW.parent_conversation OR source.origin='orchestration' AND source.parent_id IS NULL)
   AND NOT EXISTS(SELECT 1 FROM operation_admission_support s WHERE s.operation=a.id AND s.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))
 ) THEN RAISE EXCEPTION 'Conversation does not match retained execution'; END IF;
 IF NEW.allowance_id IS NULL OR NOT EXISTS(SELECT 1 FROM execution_allowances WHERE id=NEW.allowance_id) THEN
  RAISE EXCEPTION 'Missing retained allowance'; END IF;
 -- Independent funding is permitted only for the conductor's own retained source. The caller
 -- must still be the actual authority ancestor; another operation's grants cannot be substituted.
 IF NEW.parent_conversation IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM conversations parent JOIN operation_admission_parents edge ON edge.child=NEW.operation
  LEFT JOIN operation_conversations bound ON bound.conversation=parent.id
  WHERE parent.id=NEW.parent_conversation
    AND (parent.allowance_id=NEW.allowance_id OR independent_conductor)
    AND (bound.operation IS NULL OR EXISTS (
      WITH RECURSIVE lineage(id,depth) AS (
       SELECT edge.parent,0 UNION ALL
       SELECT e.parent,l.depth+1 FROM operation_admission_parents e
       JOIN lineage l ON e.child=l.id WHERE l.depth<64
      ) SELECT 1 FROM lineage WHERE id=bound.operation
    ))
 ) THEN RAISE EXCEPTION 'Child cannot replace retained parent lineage or allowance'; END IF;
 IF source.allowance_id IS NOT NULL AND source.allowance_id<>NEW.allowance_id THEN RAISE EXCEPTION 'Allowance source changed'; END IF;
 UPDATE conversations SET allowance_id=NEW.allowance_id WHERE id=NEW.conversation AND allowance_id IS NULL;
 RETURN NEW;
END $$;

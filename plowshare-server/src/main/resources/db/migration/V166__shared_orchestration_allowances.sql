-- R5: existing nested conductors keep their own logs/lifecycles and charge the caller's original
-- durable allowance. This source change does not run a deployment or migrate a live database.
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_owned_or_shared;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared CHECK (
  CASE WHEN origin='orchestration' THEN true
       ELSE (origin IN ('turn','submission','memory','event')) = (budget_total IS NOT NULL OR budget_lifted) END);

-- Checked at transaction commit so the owning service can insert the conversation and immutable
-- authority binding atomically. A missing/replaced caller or budget source cannot commit.
CREATE FUNCTION require_shared_orchestration_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.origin='orchestration' AND NEW.budget_total IS NULL AND NOT NEW.budget_lifted THEN
   IF NOT EXISTS (
     SELECT 1 FROM operation_conversations c
     JOIN operation_conversations parent ON parent.conversation=c.parent_conversation
     JOIN conversations source ON source.id=c.budget_conversation
     WHERE c.conversation=NEW.id AND c.parent_conversation IS NOT NULL
       AND parent.budget_conversation=c.budget_conversation
       AND source.project_id IS NOT DISTINCT FROM NEW.project_id
       AND (source.budget_total IS NOT NULL OR source.budget_lifted)
   ) THEN RAISE EXCEPTION 'Shared orchestration needs its retained caller and original allowance'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER shared_orchestration_source AFTER INSERT OR UPDATE ON conversations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_shared_orchestration_source();

-- Ordinary delegations retain the existing log ancestry rule. Only a shared conductor may bind
-- a separate lifecycle root to its exact retained caller and that caller's original source.
CREATE OR REPLACE FUNCTION operation_conversation_valid() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE shared_conductor BOOLEAN;
BEGIN
 SELECT c.origin='orchestration' AND c.parent_id IS NULL AND c.budget_total IS NULL
   AND NOT c.budget_lifted AND NEW.parent_conversation IS NOT NULL
 INTO shared_conductor FROM conversations c WHERE c.id=NEW.conversation;
 IF NOT EXISTS (
   SELECT 1 FROM operation_admissions a JOIN conversations c ON c.id=NEW.conversation
     JOIN projects p ON p.id=c.project_id
   WHERE a.id=NEW.operation AND a.sealed AND c.agent=NEW.agent AND p.name=a.project_name
     AND (c.parent_id IS NOT DISTINCT FROM NEW.parent_conversation OR shared_conductor)
     AND NOT EXISTS (SELECT 1 FROM operation_admission_support s WHERE s.operation=a.id
       AND s.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))
     AND (NEW.parent_conversation IS NULL OR EXISTS (
       SELECT 1 FROM operation_admission_parents edge WHERE edge.child=a.id))) THEN
   RAISE EXCEPTION 'conversation does not match retained execution';
 END IF;
 IF shared_conductor THEN
   IF NOT EXISTS (
     SELECT 1 FROM operation_conversations parent
     JOIN operation_admission_parents edge ON edge.parent=parent.operation AND edge.child=NEW.operation
     JOIN conversations source ON source.id=parent.budget_conversation
     JOIN conversations child ON child.id=NEW.conversation
     WHERE parent.conversation=NEW.parent_conversation AND parent.budget_conversation=NEW.budget_conversation
       AND source.project_id IS NOT DISTINCT FROM child.project_id
       AND (source.budget_total IS NOT NULL OR source.budget_lifted)
   ) THEN RAISE EXCEPTION 'conductor cannot replace its retained caller allowance'; END IF;
 ELSIF NEW.budget_conversation NOT LIKE 'budget:%' AND NOT EXISTS (
   WITH RECURSIVE chain(id,parent_id,budget_total,budget_lifted,depth) AS (
     SELECT id,parent_id,budget_total,budget_lifted,0 FROM conversations WHERE id=NEW.conversation
     UNION ALL SELECT c.id,c.parent_id,c.budget_total,c.budget_lifted,chain.depth+1
       FROM conversations c JOIN chain ON c.id=chain.parent_id WHERE chain.depth<64
   ) SELECT 1 FROM chain WHERE id=NEW.budget_conversation
       AND (budget_total IS NOT NULL OR budget_lifted)) AND NOT EXISTS (
   SELECT 1 FROM operation_conversations parent
   JOIN operation_admission_parents edge ON edge.parent=parent.operation AND edge.child=NEW.operation
   JOIN conversations source ON source.id=parent.budget_conversation
   JOIN conversations child ON child.id=NEW.conversation
   WHERE parent.conversation=NEW.parent_conversation
     AND parent.budget_conversation=NEW.budget_conversation
     AND source.project_id IS NOT DISTINCT FROM child.project_id
     AND (source.budget_total IS NOT NULL OR source.budget_lifted)) THEN
   RAISE EXCEPTION 'budget owner is outside retained conversation lineage';
 END IF;
 RETURN NEW;
END $$;

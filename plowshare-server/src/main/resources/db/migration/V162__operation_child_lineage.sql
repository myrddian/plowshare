-- Retained execution evidence, not assignable agent roles or a restartable-job registry.
CREATE TABLE operation_admission_parents (
 child UUID PRIMARY KEY REFERENCES operation_admissions(id),
 parent UUID NOT NULL REFERENCES operation_admissions(id),
 CHECK(child<>parent)
);
CREATE TABLE operation_conversations (
 conversation TEXT PRIMARY KEY CHECK(length(conversation) BETWEEN 1 AND 1024),
 agent TEXT NOT NULL CHECK(length(agent) BETWEEN 1 AND 1024),
 operation UUID NOT NULL UNIQUE REFERENCES operation_admissions(id),
 parent_conversation TEXT,
 budget_conversation TEXT NOT NULL CHECK(length(budget_conversation) BETWEEN 1 AND 1024),
 capabilities TEXT NOT NULL CHECK(capabilities ~ '^[0-9a-f]{64}$'),
 role_ceiling TEXT NOT NULL DEFAULT 'CONTRIBUTOR' CHECK(role_ceiling='CONTRIBUTOR'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(parent_conversation IS NULL OR parent_conversation<>conversation)
);
-- Historical IDs deliberately do not prevent conversation deletion. Missing data refuses execution.
CREATE FUNCTION operation_lineage_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'operation lineage is immutable';
END $$;
CREATE TRIGGER operation_parent_immutable BEFORE UPDATE OR DELETE ON operation_admission_parents
 FOR EACH ROW EXECUTE FUNCTION operation_lineage_immutable();
CREATE TRIGGER operation_conversation_immutable BEFORE UPDATE OR DELETE ON operation_conversations
 FOR EACH ROW EXECUTE FUNCTION operation_lineage_immutable();

CREATE FUNCTION operation_parent_valid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- Serialize overlapping ancestry inserts without changing an existing admission.
 PERFORM id FROM operation_admissions WHERE id IN (NEW.child,NEW.parent) ORDER BY id FOR UPDATE;
 IF NOT EXISTS (
   SELECT 1 FROM operation_admissions c JOIN operation_admissions p ON p.id=NEW.parent
   WHERE c.id=NEW.child AND c.sealed AND p.sealed AND c.job_id IS NULL
     AND c.executor=p.executor AND c.executor_handle=p.executor_handle
     AND c.scope=p.scope AND c.project_name=p.project_name AND c.source_account=p.source_account
     AND NOT EXISTS (SELECT 1 FROM operation_conversations bound WHERE bound.operation=c.id)
     AND NOT EXISTS (
       SELECT 1 FROM operation_admission_support cs WHERE cs.operation=c.id AND
         (cs.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL') OR NOT EXISTS (
           SELECT 1 FROM operation_admission_support ps WHERE ps.operation=p.id
             AND ps.action=cs.action AND ps.grant_id=cs.grant_id)))) THEN
   RAISE EXCEPTION 'child authority exceeds retained parent';
 END IF;
 IF EXISTS (WITH RECURSIVE chain(id,depth) AS (
   SELECT NEW.parent,0 UNION ALL
   SELECT edge.parent,chain.depth+1 FROM operation_admission_parents edge JOIN chain ON edge.child=chain.id
   WHERE chain.depth<64
 ) SELECT 1 FROM chain WHERE id=NEW.child OR depth=64) THEN
   RAISE EXCEPTION 'invalid operation ancestry';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operation_parent_valid BEFORE INSERT ON operation_admission_parents
 FOR EACH ROW EXECUTE FUNCTION operation_parent_valid();

CREATE FUNCTION operation_conversation_valid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (
   SELECT 1 FROM operation_admissions a JOIN conversations c ON c.id=NEW.conversation
     JOIN projects p ON p.id=c.project_id
   WHERE a.id=NEW.operation AND a.sealed AND c.agent=NEW.agent AND p.name=a.project_name
     AND c.parent_id IS NOT DISTINCT FROM NEW.parent_conversation
     AND NOT EXISTS (SELECT 1 FROM operation_admission_support s WHERE s.operation=a.id
       AND s.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))
     AND (NEW.parent_conversation IS NULL OR EXISTS (
       SELECT 1 FROM operation_admission_parents edge WHERE edge.child=a.id))) THEN
   RAISE EXCEPTION 'conversation does not match retained execution';
 END IF;
 IF NEW.budget_conversation NOT LIKE 'budget:%' AND NOT EXISTS (
   WITH RECURSIVE chain(id,parent_id,budget_total,budget_lifted,depth) AS (
     SELECT id,parent_id,budget_total,budget_lifted,0 FROM conversations WHERE id=NEW.conversation
     UNION ALL SELECT c.id,c.parent_id,c.budget_total,c.budget_lifted,chain.depth+1
       FROM conversations c JOIN chain ON c.id=chain.parent_id WHERE chain.depth<64
   ) SELECT 1 FROM chain WHERE id=NEW.budget_conversation
       AND (budget_total IS NOT NULL OR budget_lifted)) THEN
   RAISE EXCEPTION 'budget owner is outside retained conversation lineage';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operation_conversation_valid BEFORE INSERT ON operation_conversations
 FOR EACH ROW EXECUTE FUNCTION operation_conversation_valid();

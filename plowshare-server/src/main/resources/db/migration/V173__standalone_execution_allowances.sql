-- Spending identity belongs to the operation, never to the history/input row.
-- Old counters remain immutable historical snapshots; the views below project canonical state.
CREATE TABLE execution_allowances (
 id UUID PRIMARY KEY,
 call_limit INTEGER CHECK(call_limit>=0),
 spent INTEGER NOT NULL DEFAULT 0 CHECK(spent>=0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE execution_allowance_claims (
 budget_id UUID NOT NULL REFERENCES execution_allowances(id),
 ordinal INTEGER NOT NULL CHECK(ordinal>0),
 call_limit INTEGER CHECK(call_limit>0 AND ordinal<=call_limit),
 PRIMARY KEY(budget_id,ordinal)
);
INSERT INTO execution_allowances(id,call_limit,spent)
 SELECT budget_id,CASE WHEN budget_lifted THEN NULL ELSE budget_total END,budget_spent
 FROM conversations WHERE budget_total IS NOT NULL OR budget_lifted;
INSERT INTO execution_allowances(id,call_limit,spent)
 SELECT processing_budget_id,allowance_total,allowance_spent FROM information_revisions;
INSERT INTO execution_allowances(id,call_limit,spent)
 SELECT budget_id,call_limit,spent FROM embedding_queries;
-- Ephemeral historical worker/wake identities must not be merged: their ordinals overlap.
INSERT INTO execution_allowances(id,call_limit,spent)
 SELECT DISTINCT ON (budget_id) budget_id,call_limit,ordinal
 FROM inference_admissions ORDER BY budget_id,ordinal DESC ON CONFLICT(id) DO NOTHING;
INSERT INTO execution_allowance_claims(budget_id,ordinal,call_limit)
 SELECT budget_id,ordinal,call_limit FROM inference_admissions;
ALTER TABLE conversations ADD COLUMN allowance_id UUID REFERENCES execution_allowances(id);
ALTER TABLE conversations ADD COLUMN owns_allowance BOOLEAN NOT NULL DEFAULT false;
UPDATE conversations SET allowance_id=budget_id,owns_allowance=true
 WHERE budget_total IS NOT NULL OR budget_lifted;
UPDATE conversations c SET allowance_id=r.processing_budget_id FROM information_revisions r
 WHERE r.processing_log=c.id;
ALTER TABLE board_topics ADD COLUMN allowance_id UUID;
ALTER TABLE board_topics DROP CONSTRAINT board_topics_only_a_root_holds_the_pot;
ALTER TABLE board_topics DROP CONSTRAINT board_topics_the_pot_is_sane;
ALTER TABLE board_topics ADD CONSTRAINT board_topics_allowance_shape CHECK (
 (parent IS NULL AND pot_spent IS NOT NULL AND reserve IS NOT NULL AND reserve>=0)
 OR (parent IS NOT NULL AND pot_total IS NULL AND pot_spent IS NULL AND reserve IS NULL));
UPDATE board_topics SET allowance_id=gen_random_uuid() WHERE parent IS NULL;
INSERT INTO execution_allowances(id,call_limit,spent)
 SELECT allowance_id,pot_total,pot_spent FROM board_topics WHERE parent IS NULL;
UPDATE board_topics child SET allowance_id=root.allowance_id FROM board_topics root WHERE root.id=child.root;
-- Existing roots have deferred shape/reference checks; finish them before further table DDL.
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE board_topics ADD FOREIGN KEY(allowance_id) REFERENCES execution_allowances(id);
SET CONSTRAINTS ALL DEFERRED;
ALTER TABLE operation_conversations ADD COLUMN allowance_id UUID REFERENCES execution_allowances(id);
ALTER TABLE operation_conversations DISABLE TRIGGER USER;
UPDATE operation_conversations o SET allowance_id=c.allowance_id FROM conversations c
 WHERE c.id=o.budget_conversation;
UPDATE operation_conversations o SET allowance_id=a.id FROM execution_allowances a
 WHERE o.budget_conversation='budget:'||a.id::text AND o.allowance_id IS NULL;
UPDATE conversations c SET allowance_id=o.allowance_id FROM operation_conversations o
 WHERE o.conversation=c.id AND NOT c.owns_allowance;
WITH RECURSIVE inherited(id,allowance_id,depth) AS (
 SELECT id,allowance_id,0 FROM conversations WHERE allowance_id IS NOT NULL
 UNION ALL SELECT c.id,i.allowance_id,i.depth+1 FROM conversations c JOIN inherited i ON c.parent_id=i.id
 WHERE c.allowance_id IS NULL AND i.depth<64
) UPDATE conversations c SET allowance_id=i.allowance_id FROM inherited i WHERE c.id=i.id AND c.allowance_id IS NULL;
-- Board history retains old per-wake inference IDs, whose ordinals must never be merged.
-- Future seat/delegate execution instead references its existing tree's canonical pot.
WITH RECURSIVE edges(child,parent) AS (
 SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL AND NOT owns_allowance
 UNION SELECT o.conversation,o.parent_conversation FROM operation_conversations o
 JOIN conversations c ON c.id=o.conversation WHERE o.parent_conversation IS NOT NULL AND NOT c.owns_allowance
), board_sources(conversation,allowance_id,depth) AS (
 SELECT s.conversation,t.allowance_id,0 FROM board_seats s JOIN board_topics t ON t.id=s.topic
 UNION ALL SELECT e.child,b.allowance_id,b.depth+1 FROM edges e
 JOIN board_sources b ON e.parent=b.conversation WHERE b.depth<64
) UPDATE conversations c SET allowance_id=b.allowance_id FROM board_sources b
 WHERE c.id=b.conversation AND NOT c.owns_allowance;
UPDATE operation_conversations o SET allowance_id=c.allowance_id FROM conversations c
 WHERE c.id=o.conversation AND EXISTS(SELECT 1 FROM board_topics t WHERE t.allowance_id=c.allowance_id);
ALTER TABLE operation_conversations ENABLE TRIGGER USER;
ALTER TABLE operation_conversations ALTER COLUMN budget_conversation DROP NOT NULL;

CREATE FUNCTION execution_allowance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Allowance evidence is retained'; END IF;
 IF NEW.id<>OLD.id OR NEW.created_at<>OLD.created_at OR NEW.spent<OLD.spent
   OR (OLD.call_limit=0 AND NEW.call_limit IS DISTINCT FROM 0 AND NOT EXISTS(
     SELECT 1 FROM information_revisions r WHERE r.processing_budget_id=OLD.id
       AND r.processing_policy='explicit_only'))
   THEN RAISE EXCEPTION 'Allowance cannot be rebound, reset or enabled from no-call'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER execution_allowance_immutable BEFORE UPDATE OR DELETE ON execution_allowances
 FOR EACH ROW EXECUTE FUNCTION execution_allowance_immutable();
CREATE TRIGGER execution_allowance_claim_immutable BEFORE UPDATE OR DELETE ON execution_allowance_claims
 FOR EACH ROW EXECUTE FUNCTION operation_lineage_immutable();
-- A claim is charged before dispatch. Reserve is a swarm scheduling floor, not permission.
CREATE FUNCTION execution_allowance_claim(source UUID, reserve INTEGER, expected_spent INTEGER DEFAULT NULL)
 RETURNS TABLE(ordinal INTEGER,call_limit INTEGER) LANGUAGE plpgsql AS $$
BEGIN
 IF reserve<0 THEN RAISE EXCEPTION 'Negative reserve'; END IF;
 RETURN QUERY WITH charged AS (
  UPDATE execution_allowances a SET spent=a.spent+1 WHERE a.id=source AND a.spent<2147483647
   AND (a.call_limit IS NULL OR a.spent::bigint+reserve<a.call_limit)
   AND (expected_spent IS NULL OR a.spent=expected_spent)
  RETURNING a.spent,a.call_limit
 ), retained AS (
  INSERT INTO execution_allowance_claims(budget_id,ordinal,call_limit)
  SELECT source,c.spent,c.call_limit FROM charged c RETURNING execution_allowance_claims.ordinal,execution_allowance_claims.call_limit
 ) SELECT r.ordinal,r.call_limit FROM retained r;
 IF NOT FOUND AND EXISTS(SELECT 1 FROM execution_allowances a
   WHERE a.id=source AND a.call_limit IS NULL AND a.spent=2147483647) THEN
  RAISE EXCEPTION 'Model allowance counter exhausted';
 END IF;
END $$;
-- Existing live-object SYSTEM consumers join the same canonical counter at inference admission.
-- Already reserved claims are checked without charging again. The inference ledger still rejects reuse.
CREATE FUNCTION execution_allowance_accept(source UUID, sequence INTEGER, ceiling INTEGER)
 RETURNS void LANGUAGE plpgsql AS $$
DECLARE prior execution_allowance_claims; current execution_allowances;
BEGIN
 IF sequence<1 OR ceiling IS NOT NULL AND (ceiling<sequence OR ceiling<1) THEN
  RAISE EXCEPTION 'Invalid inference claim'; END IF;
 INSERT INTO execution_allowances(id,call_limit,spent) VALUES(source,ceiling,0) ON CONFLICT(id) DO NOTHING;
 SELECT * INTO current FROM execution_allowances WHERE id=source FOR UPDATE;
 SELECT * INTO prior FROM execution_allowance_claims WHERE budget_id=source AND ordinal=sequence;
 IF FOUND THEN
  IF prior.call_limit IS DISTINCT FROM ceiling THEN RAISE EXCEPTION 'Claim ceiling changed'; END IF;
 ELSIF current.spent::bigint+1=sequence AND current.call_limit IS NOT DISTINCT FROM ceiling THEN
  UPDATE execution_allowances SET spent=sequence WHERE id=source;
  INSERT INTO execution_allowance_claims VALUES(source,sequence,ceiling);
 ELSE RAISE EXCEPTION 'Unreserved, stale or exhausted inference claim'; END IF;
END $$;

-- Initial policy capture remains legal; subsequent spending belongs only to execution_allowances.
CREATE FUNCTION attach_execution_allowance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='conversations' THEN
  IF NEW.budget_total IS NOT NULL OR NEW.budget_lifted THEN
   INSERT INTO execution_allowances(id,call_limit,spent)
    VALUES(NEW.budget_id,CASE WHEN NEW.budget_lifted THEN NULL ELSE NEW.budget_total END,NEW.budget_spent)
    ON CONFLICT(id) DO NOTHING;
   NEW.allowance_id=NEW.budget_id; NEW.owns_allowance=true;
  ELSIF NEW.parent_id IS NOT NULL THEN
   SELECT allowance_id INTO NEW.allowance_id FROM conversations WHERE id=NEW.parent_id;
  END IF;
 ELSIF TG_TABLE_NAME='information_revisions' THEN
  INSERT INTO execution_allowances(id,call_limit,spent) VALUES(NEW.processing_budget_id,NEW.allowance_total,NEW.allowance_spent);
 ELSIF TG_TABLE_NAME='embedding_queries' THEN
  INSERT INTO execution_allowances(id,call_limit,spent) VALUES(NEW.budget_id,NEW.call_limit,NEW.spent);
 ELSIF TG_TABLE_NAME='board_topics' THEN
  IF NEW.parent IS NULL THEN
   NEW.allowance_id=gen_random_uuid();
   INSERT INTO execution_allowances(id,call_limit,spent) VALUES(NEW.allowance_id,NEW.pot_total,NEW.pot_spent);
  ELSE SELECT allowance_id INTO NEW.allowance_id FROM board_topics WHERE id=NEW.root; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON conversations FOR EACH ROW EXECUTE FUNCTION attach_execution_allowance();
CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON information_revisions FOR EACH ROW EXECUTE FUNCTION attach_execution_allowance();
CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON embedding_queries FOR EACH ROW EXECUTE FUNCTION attach_execution_allowance();
CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON board_topics FOR EACH ROW EXECUTE FUNCTION attach_execution_allowance();

-- Views retain established row DTOs, deriving every active counter from the canonical allowance.
DO $$
DECLARE columns TEXT; relation TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['conversations','information_revisions','board_topics'] LOOP
  SELECT string_agg(CASE
   WHEN relation='conversations' AND column_name='budget_total' THEN 'CASE WHEN r.owns_allowance THEN a.call_limit END AS budget_total'
   WHEN relation='conversations' AND column_name='budget_spent' THEN 'CASE WHEN r.owns_allowance THEN a.spent END AS budget_spent'
   WHEN relation='conversations' AND column_name='budget_lifted' THEN '(r.owns_allowance AND a.call_limit IS NULL) AS budget_lifted'
   WHEN relation='conversations' AND column_name='budget_id' THEN 'COALESCE(a.id,r.budget_id) AS budget_id'
   WHEN relation='information_revisions' AND column_name='allowance_total' THEN 'a.call_limit AS allowance_total'
   WHEN relation='information_revisions' AND column_name='allowance_spent' THEN 'a.spent AS allowance_spent'
   WHEN relation='board_topics' AND column_name='pot_total' THEN 'CASE WHEN r.parent IS NULL THEN a.call_limit END AS pot_total'
   WHEN relation='board_topics' AND column_name='pot_spent' THEN 'CASE WHEN r.parent IS NULL THEN a.spent END AS pot_spent'
   ELSE 'r.'||quote_ident(column_name) END,',' ORDER BY ordinal_position) INTO columns
  FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=relation;
  EXECUTE 'CREATE VIEW '||quote_ident(CASE relation WHEN 'conversations' THEN 'conversation_allowance_state' WHEN 'information_revisions' THEN 'information_revision_allowances' ELSE 'board_allowance_state' END)
   ||' AS SELECT '||columns||' FROM '||quote_ident(relation)||' r'
   ||CASE WHEN relation='conversations' THEN ' LEFT JOIN ' ELSE ' JOIN ' END||'execution_allowances a ON a.id=r.'
   ||CASE WHEN relation='information_revisions' THEN 'processing_budget_id' ELSE 'allowance_id' END;
 END LOOP;
END $$;

CREATE VIEW embedding_query_allowances AS
 SELECT q.id,q.budget_id,q.account_id,q.scope_id,q.fingerprint,q.invocation,
        a.call_limit,a.spent,q.closed,q.created_at,q.consumer_kind
 FROM embedding_queries q JOIN execution_allowances a ON a.id=q.budget_id;

-- Retained source policy is captured on insertion. Old binaries must not continue writing mirrors.
CREATE FUNCTION historical_allowance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='conversations' THEN
  IF (NEW.budget_id,NEW.budget_total,NEW.budget_spent,NEW.budget_lifted,NEW.owns_allowance)
    IS DISTINCT FROM (OLD.budget_id,OLD.budget_total,OLD.budget_spent,OLD.budget_lifted,OLD.owns_allowance)
   THEN RAISE EXCEPTION 'Conversation allowance snapshots are historical'; END IF;
 ELSIF TG_TABLE_NAME='information_revisions' THEN
  IF (NEW.processing_budget_id,NEW.allowance_total,NEW.allowance_spent)
    IS DISTINCT FROM (OLD.processing_budget_id,OLD.allowance_total,OLD.allowance_spent)
   THEN RAISE EXCEPTION 'Preparation allowance snapshots are historical'; END IF;
 ELSIF TG_TABLE_NAME='board_topics' THEN
  IF (NEW.allowance_id,NEW.pot_total,NEW.pot_spent)
    IS DISTINCT FROM (OLD.allowance_id,OLD.pot_total,OLD.pot_spent)
   THEN RAISE EXCEPTION 'Board allowance snapshots are historical'; END IF;
 ELSIF TG_TABLE_NAME='embedding_queries' THEN
  IF NEW.spent IS DISTINCT FROM OLD.spent THEN
   RAISE EXCEPTION 'Query allowance snapshots are historical';
  END IF;
 END IF;
 IF TG_TABLE_NAME='conversations' THEN
 IF OLD.allowance_id IS NOT NULL AND NEW.allowance_id IS DISTINCT FROM OLD.allowance_id THEN
  -- Processing logs are attached once by their owning revision, never rebound by a caller.
  IF NOT EXISTS(SELECT 1 FROM information_revisions r
    WHERE r.processing_log=NEW.id AND r.processing_budget_id=NEW.allowance_id) THEN
   RAISE EXCEPTION 'Conversation allowance cannot be rebound'; END IF;
 END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON conversations
 FOR EACH ROW EXECUTE FUNCTION historical_allowance_immutable();
CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON information_revisions
 FOR EACH ROW EXECUTE FUNCTION historical_allowance_immutable();
CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON board_topics
 FOR EACH ROW EXECUTE FUNCTION historical_allowance_immutable();
CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON embedding_queries
 FOR EACH ROW EXECUTE FUNCTION historical_allowance_immutable();

CREATE OR REPLACE FUNCTION require_shared_orchestration_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.origin='orchestration' AND NOT NEW.owns_allowance AND NOT EXISTS (
   SELECT 1 FROM operation_conversations c JOIN operation_conversations parent ON parent.conversation=c.parent_conversation
   WHERE c.conversation=NEW.id AND c.allowance_id IS NOT NULL AND parent.allowance_id=c.allowance_id
 ) THEN RAISE EXCEPTION 'Shared orchestration needs retained caller and allowance'; END IF;
 RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION operation_conversation_valid() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source conversations;
BEGIN
 SELECT * INTO source FROM conversations WHERE id=NEW.conversation FOR UPDATE;
 IF NOT EXISTS (
  SELECT 1 FROM operation_admissions a JOIN projects p ON p.id=source.project_id
  WHERE a.id=NEW.operation AND a.sealed AND source.agent=NEW.agent AND p.name=a.project_name
   AND (source.parent_id IS NOT DISTINCT FROM NEW.parent_conversation OR source.origin='orchestration' AND source.parent_id IS NULL AND NOT source.owns_allowance)
   AND NOT EXISTS(SELECT 1 FROM operation_admission_support s WHERE s.operation=a.id AND s.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))
 ) THEN RAISE EXCEPTION 'Conversation does not match retained execution'; END IF;
 IF NEW.allowance_id IS NULL OR NOT EXISTS(SELECT 1 FROM execution_allowances WHERE id=NEW.allowance_id) THEN
  RAISE EXCEPTION 'Missing retained allowance'; END IF;
 IF NEW.parent_conversation IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM conversations parent JOIN operation_admission_parents edge ON edge.child=NEW.operation
  LEFT JOIN operation_conversations bound ON bound.conversation=parent.id
  WHERE parent.id=NEW.parent_conversation AND parent.allowance_id=NEW.allowance_id
    AND (bound.operation IS NULL OR EXISTS (
      WITH RECURSIVE lineage(id,depth) AS (
       SELECT edge.parent,0 UNION ALL
       SELECT e.parent,l.depth+1 FROM operation_admission_parents e
       JOIN lineage l ON e.child=l.id WHERE l.depth<64
      ) SELECT 1 FROM lineage WHERE id=bound.operation
    ))
 ) THEN RAISE EXCEPTION 'Child cannot replace retained parent allowance'; END IF;
 IF source.allowance_id IS NOT NULL AND source.allowance_id<>NEW.allowance_id THEN RAISE EXCEPTION 'Allowance source changed'; END IF;
 UPDATE conversations SET allowance_id=NEW.allowance_id WHERE id=NEW.conversation AND allowance_id IS NULL;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION protect_preparation_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.preparation_revision IS NOT NULL AND (NEW.preparation_revision IS DISTINCT FROM OLD.preparation_revision
  OR NEW.preparation_generation IS DISTINCT FROM OLD.preparation_generation OR NEW.preparation_targets IS DISTINCT FROM OLD.preparation_targets
  OR OLD.preparation_published AND NOT NEW.preparation_published) THEN RAISE EXCEPTION 'Preparation claim targets are immutable'; END IF;
 IF NEW.preparation_revision IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM information_revisions r JOIN execution_allowances a ON a.id=r.processing_budget_id
  WHERE r.id=NEW.preparation_revision AND a.id=NEW.budget_id AND NEW.ordinal<=a.spent
 ) THEN RAISE EXCEPTION 'Preparation claim has no charged source'; END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION protect_query_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.query_id IS NOT NULL AND (NEW.query_id IS DISTINCT FROM OLD.query_id OR NEW.query_task_hash IS DISTINCT FROM OLD.query_task_hash) THEN RAISE EXCEPTION 'Query claim cannot be rebound'; END IF;
 IF NEW.query_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM embedding_queries q JOIN execution_allowances a ON a.id=q.budget_id
  WHERE q.id=NEW.query_id AND a.id=NEW.budget_id AND NEW.ordinal<=a.spent
 ) THEN RAISE EXCEPTION 'Query claim has no charged source'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION processing_log_allowance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.processing_log IS NOT NULL AND NEW.processing_log IS DISTINCT FROM OLD.processing_log THEN
  UPDATE conversations SET allowance_id=NEW.processing_budget_id WHERE id=NEW.processing_log;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER processing_log_allowance AFTER UPDATE OF processing_log ON information_revisions
 FOR EACH ROW EXECUTE FUNCTION processing_log_allowance();

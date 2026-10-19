-- Closed SYSTEM embedding purpose; no account grant or public operation is added.
ALTER TABLE system_operations DROP CONSTRAINT system_operations_purpose_check;
ALTER TABLE system_operations ADD CHECK(purpose IN ('DOCUMENT_PREPARATION','CONVERSATION_COMPACTION','SKILL_SUMMARY','SCRIBE','LEARNING','MEMORY_DIGEST','MEMORY_CURATION','STUCK_ADVISOR','CALL_VALIDATOR','COMMAND_JUDGE','SCHEDULE_READER','SEMANTIC_QUERY'));

-- The search consumer owns this finite source. The foundation cannot mint an allowance.
-- Historical identity survives account/project deletion; these rows confer no live capability.
CREATE TABLE embedding_queries (
 id UUID PRIMARY KEY,
 budget_id UUID NOT NULL UNIQUE,
 account_id UUID NOT NULL,
 scope_id UUID NOT NULL,
 fingerprint TEXT NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
 invocation TEXT,
 call_limit INTEGER NOT NULL CHECK(call_limit>0),
 spent INTEGER NOT NULL DEFAULT 0 CHECK(spent>=0 AND spent<=call_limit),
 closed BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(account_id,invocation)
);
CREATE FUNCTION protect_embedding_query() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Query evidence is retained'; END IF;
 IF NEW.id<>OLD.id OR NEW.budget_id<>OLD.budget_id OR NEW.account_id<>OLD.account_id
   OR NEW.scope_id<>OLD.scope_id OR NEW.fingerprint<>OLD.fingerprint
   OR NEW.invocation IS DISTINCT FROM OLD.invocation OR NEW.call_limit<>OLD.call_limit
   OR NEW.created_at<>OLD.created_at OR NEW.spent<OLD.spent
   OR OLD.closed AND (NOT NEW.closed OR NEW.spent<>OLD.spent) THEN
   RAISE EXCEPTION 'Query source cannot be rebound or reset';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_embedding_query BEFORE UPDATE OR DELETE ON embedding_queries
 FOR EACH ROW EXECUTE FUNCTION protect_embedding_query();

-- Reuse the owning claim ledger, including its unique budget ordinal and one-submission fence.
ALTER TABLE inference_admissions ADD COLUMN query_id UUID REFERENCES embedding_queries(id);
ALTER TABLE inference_admissions ADD COLUMN query_task_hash TEXT;
ALTER TABLE inference_admissions ADD CHECK(
 (query_id IS NULL AND query_task_hash IS NULL) OR
 (query_id IS NOT NULL AND query_task_hash IS NOT NULL AND query_task_hash ~ '^[a-f0-9]{64}$' AND preparation_revision IS NULL));
CREATE UNIQUE INDEX inference_query_batch ON inference_admissions(query_id,query_task_hash) WHERE query_id IS NOT NULL;
CREATE FUNCTION protect_query_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.query_id IS NOT NULL AND (NEW.query_id IS DISTINCT FROM OLD.query_id OR NEW.query_task_hash IS DISTINCT FROM OLD.query_task_hash) THEN
   RAISE EXCEPTION 'Query claim cannot be rebound';
 END IF;
 IF NEW.query_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM embedding_queries q
   WHERE q.id=NEW.query_id AND q.budget_id=NEW.budget_id AND NEW.ordinal<=q.spent AND NEW.call_limit=q.call_limit) THEN
   RAISE EXCEPTION 'Query claim has no charged source';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_query_claim BEFORE UPDATE ON inference_admissions FOR EACH ROW EXECUTE FUNCTION protect_query_claim();

-- Closed write-time indexing purpose; no public execution home or role is introduced.
ALTER TABLE system_operations DROP CONSTRAINT system_operations_purpose_check;
ALTER TABLE system_operations ADD CONSTRAINT system_operations_purpose_check CHECK(purpose IN ('DOCUMENT_PREPARATION','CONVERSATION_COMPACTION','SKILL_SUMMARY','SCRIBE','LEARNING','MEMORY_DIGEST','MEMORY_CURATION','STUCK_ADVISOR','CALL_VALIDATOR','COMMAND_JUDGE','SCHEDULE_READER','SEMANTIC_QUERY','MEMORY_WRITE'));

-- Reuse retained source/claim evidence, including writes that deliberately make no calls.
ALTER TABLE embedding_queries ADD COLUMN consumer_kind TEXT NOT NULL DEFAULT 'QUERY'
 CHECK(consumer_kind IN ('QUERY','MEMORY_WRITE'));
ALTER TABLE embedding_queries DROP CONSTRAINT embedding_queries_call_limit_check;
ALTER TABLE embedding_queries ADD CONSTRAINT embedding_queries_call_limit_check CHECK(
 call_limit IS NULL OR call_limit>0 OR (consumer_kind='MEMORY_WRITE' AND call_limit=0));
CREATE OR REPLACE FUNCTION protect_embedding_query() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Query evidence is retained'; END IF;
 IF NEW.id<>OLD.id OR NEW.budget_id<>OLD.budget_id OR NEW.account_id<>OLD.account_id
   OR NEW.scope_id<>OLD.scope_id OR NEW.fingerprint<>OLD.fingerprint
   OR NEW.invocation IS DISTINCT FROM OLD.invocation OR NEW.call_limit IS DISTINCT FROM OLD.call_limit
   OR NEW.consumer_kind<>OLD.consumer_kind OR NEW.created_at<>OLD.created_at OR NEW.spent<OLD.spent
   OR OLD.closed AND (NOT NEW.closed OR NEW.spent<>OLD.spent) THEN
   RAISE EXCEPTION 'Operation source cannot be rebound or reset';
 END IF;
 RETURN NEW;
END $$;

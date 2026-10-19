-- Reuse the inference ledger's nullable ceiling: NULL is unlimited, not unaccounted.
-- Existing finite sources and claims keep their original immutable ceilings.
-- Existing CHECKs reject negative usage/nonpositive finite limits; NULL has no upper bound.
ALTER TABLE embedding_queries ALTER COLUMN call_limit DROP NOT NULL;

CREATE OR REPLACE FUNCTION protect_embedding_query() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Query evidence is retained'; END IF;
 IF NEW.id<>OLD.id OR NEW.budget_id<>OLD.budget_id OR NEW.account_id<>OLD.account_id
   OR NEW.scope_id<>OLD.scope_id OR NEW.fingerprint<>OLD.fingerprint
   OR NEW.invocation IS DISTINCT FROM OLD.invocation OR NEW.call_limit IS DISTINCT FROM OLD.call_limit
   OR NEW.created_at<>OLD.created_at OR NEW.spent<OLD.spent
   OR OLD.closed AND (NOT NEW.closed OR NEW.spent<>OLD.spent) THEN
   RAISE EXCEPTION 'Query source cannot be rebound or reset';
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION protect_query_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.query_id IS NOT NULL AND (NEW.query_id IS DISTINCT FROM OLD.query_id OR NEW.query_task_hash IS DISTINCT FROM OLD.query_task_hash) THEN
   RAISE EXCEPTION 'Query claim cannot be rebound';
 END IF;
 IF NEW.query_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM embedding_queries q
   WHERE q.id=NEW.query_id AND q.budget_id=NEW.budget_id AND NEW.ordinal<=q.spent AND NEW.call_limit IS NOT DISTINCT FROM q.call_limit) THEN
   RAISE EXCEPTION 'Query claim has no charged source';
 END IF;
 RETURN NEW;
END $$;

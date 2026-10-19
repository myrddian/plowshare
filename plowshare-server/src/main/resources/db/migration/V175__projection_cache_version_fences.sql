-- Source allocation hints, never a MAX/high-water commit cursor. The Java repository reads these
-- before a fresh pg_current_snapshot(), after committing an allocation barrier, and subsequently
-- rejects any captured pending XID that commits. This closes out-of-order/late commit without
-- locking one shared epoch row per writer.
-- CACHE 1 is part of correctness: each nextval must change the externally observed last_value.
CREATE SEQUENCE snapshot_cache_documents MINVALUE 1 START 1 CACHE 1 NO CYCLE;
CREATE SEQUENCE snapshot_cache_search MINVALUE 1 START 1 CACHE 1 NO CYCLE;
CREATE SEQUENCE snapshot_cache_schedules MINVALUE 1 START 1 CACHE 1 NO CYCLE;
CREATE SEQUENCE snapshot_cache_authority MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_documents'),nextval('snapshot_cache_search'),
 nextval('snapshot_cache_schedules'),nextval('snapshot_cache_authority');

-- This singleton is read-only identity, not an updated global mutation lock. A reconnect/restore
-- still requires the process-local recovery epoch and a new authoritative baseline.
CREATE TABLE snapshot_cache_identity (
 singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
 database_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid()
);
INSERT INTO snapshot_cache_identity(singleton) VALUES(true);
CREATE FUNCTION snapshot_cache_identity_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Projection cache database identity is immutable';
END $$;
CREATE TRIGGER snapshot_cache_identity_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
 ON snapshot_cache_identity FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_identity_immutable();

-- A snapshot's xmax is based on completed XIDs, not the next allocated XID. Merely reading the
-- counters and then taking a fresh snapshot can omit still-active tail writers >= xmax. This
-- function first captures allocations, then assigns a strictly newer probe XID. Its sole SELECT
-- must COMMIT in autocommit mode before the pending snapshot is taken, advancing xmax past every
-- observed writer. It costs one short-lived XID per capture; it does not update/lock an epoch row.
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,authority BIGINT)
 LANGUAGE plpgsql VOLATILE PARALLEL UNSAFE AS $$
BEGIN
 IF pg_current_xact_id_if_assigned() IS NOT NULL THEN
  RAISE EXCEPTION 'Projection counter capture requires a fresh autocommit statement';
 END IF;
 SELECT identity.database_id,
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_documents),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_search),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_schedules),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_authority)
 INTO database_id,documents,search,schedules,authority
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 PERFORM pg_current_xact_id();
 RETURN NEXT;
END $$;

-- Expiry can change authority without a write/notification. These support the bounded min/max
-- window probes; current live authorization is still evaluated under its existing caller fences.
CREATE INDEX authority_grants_cache_expiry ON authority_grants(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX service_tokens_cache_expiry ON service_tokens(expires_at) WHERE expires_at IS NOT NULL;

CREATE FUNCTION snapshot_cache_source_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE domain TEXT;
BEGIN
 -- No-op registrations (INSERT ON CONFLICT DO NOTHING) must not invalidate an entire domain.
 -- Transition relations are named by the three separate DML statement triggers below.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  -- Only document processing budgets are fields of the adopted metadata DTO. Ordinary conversation
  -- accounting must not flush the corpus on every inference call. V164's unique budget index
  -- supports this bounded join without scanning all revisions for every unrelated allowance.
  IF TG_TABLE_NAME='execution_allowances' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows changed JOIN information_revisions revision
     ON revision.processing_budget_id=changed.id) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 -- Force a top-level XID before allocation: a sequence bump alone need not assign one. A fresh
 -- snapshot taken after observing this allocation AND committing the capture probe must name
 -- every still-uncommitted writer, including allocated tail XIDs beyond an earlier snapshot xmax.
 PERFORM pg_current_xact_id();
 -- Transaction-local, including savepoint rollback; dirty outer transactions cannot share DTOs.
 PERFORM set_config('plowshare.cache_dirty','on',true);
 FOREACH domain IN ARRAY TG_ARGV LOOP
  IF domain NOT IN ('documents','search','schedules','authority') THEN
   RAISE EXCEPTION 'Unknown projection cache dependency';
  END IF;
  PERFORM nextval(('snapshot_cache_'||domain)::regclass);
  -- PostgreSQL delivers only after commit and coalesces identical channel/payload pairs. Payloads
  -- contain a fixed dependency domain, never an entity ID, credential or source content.
  PERFORM pg_notify('plowshare_projection_changes',domain);
 END LOOP;
 RETURN NULL;
END $$;

-- Whole projection domains deliberately cover membership/absence, cascades and direct SQL writers,
-- not just objects already resident in one server. Future projection adoption must extend this list
-- when its actual SQL depends on a new relation; triggers must not be disabled by application roles.
DO $$
DECLARE relation TEXT; domains TEXT[]; operation TEXT; transition TEXT; arguments TEXT;
BEGIN
 FOR relation,domains IN
 SELECT unnest(ARRAY[
  'information_resources','information_revisions','information_steps','information_reports',
  'information_links','information_inputs','information_resource_policies',
  'information_navigation_policies','information_reference_obligations',
  'information_content_type_versions','information_revision_content'
 ]),ARRAY['documents','search']
 UNION ALL SELECT unnest(ARRAY['information_content_types','execution_allowances']),ARRAY['documents']
 UNION ALL SELECT unnest(ARRAY[
  'documents','paragraphs','chunks','embedding_spaces','embedding_slots',
  'chunks_embedding_staging','documents_embedding_staging'
 ]),ARRAY['search']
 UNION ALL SELECT unnest(ARRAY['schedule_sources','schedule_files']),ARRAY['schedules']
 UNION ALL SELECT unnest(ARRAY[
  'admins','service_tokens','service_token_scopes','authority_accounts','authority_scopes',
  'authority_roles','authority_role_actions','authority_grants','project_members'
 ]),ARRAY['authority']
 -- Project names/ownership are projected by documents and schedules as well as live authority.
 UNION ALL SELECT 'projects',ARRAY['documents','search','schedules','authority']
 LOOP
  SELECT string_agg(quote_literal(domain),',') INTO arguments FROM unnest(domains) domain;
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   transition := CASE WHEN operation='DELETE' THEN 'OLD' ELSE 'NEW' END;
   EXECUTE format('CREATE TRIGGER %I AFTER %s ON %I REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed(%s)',
    'snapshot_cache_changed_'||lower(operation),operation,relation,transition,arguments);
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed(%s)',relation,arguments);
 END LOOP;
END $$;

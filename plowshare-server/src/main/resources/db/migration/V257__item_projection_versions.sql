-- Item versions and keyed notifications for the READABILITY domain, and the missing AUTHORITY
-- version trigger on contained_principals.
--
-- A READABILITY write still allocates the domain counter, records its writer and sends the domain
-- hint exactly as before, so every domain-scoped projection keeps its current behaviour. In
-- addition each change names the row groups (items) it affected:
--
--   conversation:<root log>  a conversations, orchestrations, information_job_inputs or
--                            information_quarantined_payloads row. <root log> is each log with no
--                            parent among the ancestors of the changed row's logs, using exactly
--                            the ancestry edges of information_log_readable (V235): conversation
--                            parent_id, orchestration caller -> conductor, parent orchestration's
--                            conductor -> child conductor, and orchestration id -> conductor. Both
--                            the old and the new endpoints of a changed edge are walked, so a tree
--                            that is joined, split or deleted is addressed under its old and new
--                            roots.
--   revision:<id>            an information_revisions, information_links (revision_id),
--                            information_inputs (derived_revision) or reference obligation
--                            (pinned_revision) row.
--   resource:<id>            an information_resources row, a revision's resource, a resource
--                            policy, a reference obligation's source and target, and a
--                            document-level navigation policy ('document:<resource id>').
--   scope:<id>               an information_resources row's home scope (old and new).
--   *                        every item of the domain: the fan-out fallback described below.
--
-- An address is opaque, bounded and never content or an allow decision. Ids that cannot be written
-- in the grammar enforced below (printable ASCII without '|' or space, at most 200 characters)
-- take the fan-out fallback instead of being truncated.
--
-- Fan-out is bounded. A statement touching more than 1024 rows, a TRUNCATE, an ancestry walk
-- visiting more than 256 logs, a change with no item address (platform or project navigation
-- policies, information_document_policies), or a transaction naming more than 64 items in one
-- domain bumps that domain's '*' item instead of the individual items. Readers record '*' with
-- their own items, so the fallback invalidates every item-scoped entry of the domain, as a domain
-- change does today.
--
-- Counters move once per transaction, at commit. Triggers only accumulate the transaction's items
-- in a transaction-local setting and schedule one deferred flush. The flush runs in the commit's
-- pre-commit phase, takes the item rows' locks in (domain,item) order and stores counters drawn
-- from one sequence, so concurrent writers serialize only for the instant before their commits,
-- can never deadlock on item rows, and a counter value is never reused. Savepoint rollback discards
-- the accumulated items and the scheduled flush together. Counters are transactional: a reader that
-- reads its items' counters before loading, and compares them for equality afterwards, sees every
-- write committed after its capture. Rows are never deleted; a counter that went back to an old
-- value could revalidate an old entry.
--
-- After commit, listeners on plowshare_item_changes receive '<domain>|<item>|<item>...', at most
-- 7900 bytes per notification (PostgreSQL's limit is 8000), or '<domain>|*' for the fallback. Old
-- server binaries do not LISTEN on this channel.

-- Drain old trigger writers before replacing their function, as V205 and V208 do.
DO $$
DECLARE relation REGCLASS;
BEGIN
 FOR relation IN SELECT unnest(ARRAY['conversations','orchestrations','information_job_inputs',
  'information_quarantined_payloads','information_revisions','information_resources',
  'information_links','information_inputs','information_resource_policies',
  'information_navigation_policies','information_reference_obligations',
  'information_document_policies','contained_principals']::regclass[])
 LOOP EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',relation); END LOOP;
END $$;

CREATE TABLE snapshot_cache_item_versions (
 domain TEXT NOT NULL CHECK(domain IN ('readability')),
 item TEXT NOT NULL CHECK(item ~ '^(\*|(conversation|revision|resource|scope):[!-{}~]{1,200})$'),
 counter BIGINT NOT NULL CHECK(counter>0),
 PRIMARY KEY(domain,item)
);
CREATE SEQUENCE snapshot_cache_item_allocations MINVALUE 1 START 1 CACHE 1 NO CYCLE;
-- One row per transaction with pending items, existing only until that transaction's flush.
CREATE UNLOGGED TABLE snapshot_cache_item_flushes (xid XID8 PRIMARY KEY);
REVOKE ALL ON snapshot_cache_item_versions,snapshot_cache_item_flushes FROM PUBLIC;
REVOKE ALL ON SEQUENCE snapshot_cache_item_allocations FROM PUBLIC;

-- The parents of one log under information_log_readable's ancestry edges (V235).
CREATE FUNCTION snapshot_cache_log_parents(log TEXT) RETURNS SETOF TEXT
 LANGUAGE SQL STABLE AS $$
 SELECT parent_id FROM conversations WHERE id=$1 AND parent_id IS NOT NULL
 UNION ALL SELECT caller_conversation FROM orchestrations
  WHERE conductor_conversation=$1 AND caller_conversation IS NOT NULL
 UNION ALL SELECT p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
  WHERE o.conductor_conversation=$1 AND p.conductor_conversation IS NOT NULL
 UNION ALL SELECT conductor_conversation FROM orchestrations
  WHERE id=$1 AND conductor_conversation IS NOT NULL
$$;

-- Root logs above the given logs, or NULL when the walk visits more than 256 logs. UNION ends
-- cycles; a cycle with no root contributes no address, so the caller must treat an empty result
-- for a non-empty start as unaddressable. The LIMIT stops the recursive evaluation early.
CREATE FUNCTION snapshot_cache_log_roots(starts TEXT[]) RETURNS TEXT[]
 LANGUAGE plpgsql STABLE AS $$
DECLARE visited TEXT[]; roots TEXT[];
BEGIN
 WITH RECURSIVE ancestors(log) AS (
  SELECT DISTINCT start FROM unnest(starts) start WHERE start IS NOT NULL
  UNION SELECT parent.log FROM ancestors a CROSS JOIN LATERAL snapshot_cache_log_parents(a.log) parent(log)
 ) SELECT array_agg(log) INTO visited FROM (SELECT log FROM ancestors LIMIT 257) bounded;
 IF coalesce(cardinality(visited),0)>256 THEN RETURN NULL; END IF;
 SELECT array_agg(log ORDER BY log) INTO roots FROM unnest(visited) log
  WHERE NOT EXISTS(SELECT 1 FROM snapshot_cache_log_parents(log));
 IF coalesce(cardinality(visited),0)>0 AND coalesce(cardinality(roots),0)=0 THEN RETURN NULL; END IF;
 RETURN coalesce(roots,ARRAY[]::TEXT[]);
END $$;

-- Schedules the commit-time flush once per transaction (and again after a flush forced early by
-- SET CONSTRAINTS ... IMMEDIATE). The marker and the row roll back together with a savepoint.
CREATE FUNCTION snapshot_cache_schedule_item_flush() RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 IF current_setting('plowshare.cache_item_flush',true) IS DISTINCT FROM 'on' THEN
  INSERT INTO snapshot_cache_item_flushes(xid) VALUES(pg_current_xact_id()) ON CONFLICT DO NOTHING;
  PERFORM set_config('plowshare.cache_item_flush','on',true);
 END IF;
END $$;

-- Accumulates items for this transaction; NULL items (an unaddressable change) select the
-- domain-wide fallback. Never takes an item row lock.
CREATE FUNCTION snapshot_cache_record_items(selected_domain TEXT,selected TEXT[]) RETURNS VOID
 LANGUAGE plpgsql AS $$
DECLARE overflow TEXT[]; pending TEXT[]; merged TEXT[];
BEGIN
 IF selected_domain IS DISTINCT FROM 'readability' THEN
  RAISE EXCEPTION 'Unknown item-tracked projection domain';
 END IF;
 PERFORM snapshot_cache_schedule_item_flush();
 overflow:=coalesce(nullif(current_setting('plowshare.cache_item_overflow',true),'')::TEXT[],ARRAY[]::TEXT[]);
 IF selected_domain=ANY(overflow) THEN RETURN; END IF;
 pending:=coalesce(nullif(current_setting('plowshare.cache_items',true),'')::TEXT[],ARRAY[]::TEXT[]);
 IF selected IS NOT NULL AND NOT EXISTS(SELECT 1 FROM unnest(selected) item
   WHERE item IS NULL OR item !~ '^(conversation|revision|resource|scope):[!-{}~]{1,200}$') THEN
  SELECT array_agg(DISTINCT entry ORDER BY entry) INTO merged FROM (
   SELECT unnest(pending) AS entry UNION SELECT selected_domain||'|'||unnest(selected)) entries;
  IF (SELECT count(*) FROM unnest(merged) entry WHERE entry LIKE selected_domain||'|%')<=64 THEN
   PERFORM set_config('plowshare.cache_items',coalesce(merged,ARRAY[]::TEXT[])::TEXT,true);
   RETURN;
  END IF;
 END IF;
 -- Over the bound or unaddressable: drop the domain's items and bump its '*' item at commit.
 SELECT array_agg(entry ORDER BY entry) INTO pending FROM unnest(pending) entry
  WHERE entry NOT LIKE selected_domain||'|%';
 PERFORM set_config('plowshare.cache_items',coalesce(pending,ARRAY[]::TEXT[])::TEXT,true);
 PERFORM set_config('plowshare.cache_item_overflow',(overflow||selected_domain)::TEXT,true);
END $$;

-- Deferred to commit. Bumps every accumulated item, or each overflowed domain's '*', in one sorted
-- statement, then queues the keyed notifications that PostgreSQL delivers after commit.
CREATE FUNCTION snapshot_cache_flush_items() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE pending TEXT[]; overflow TEXT[]; selected_domain TEXT; payload TEXT; address TEXT;
BEGIN
 pending:=coalesce(nullif(current_setting('plowshare.cache_items',true),'')::TEXT[],ARRAY[]::TEXT[]);
 overflow:=coalesce(nullif(current_setting('plowshare.cache_item_overflow',true),'')::TEXT[],ARRAY[]::TEXT[]);
 PERFORM set_config('plowshare.cache_items','',true);
 PERFORM set_config('plowshare.cache_item_overflow','',true);
 PERFORM set_config('plowshare.cache_item_flush','',true);
 DELETE FROM snapshot_cache_item_flushes WHERE xid=NEW.xid;
 INSERT INTO snapshot_cache_item_versions AS version(domain,item,counter)
  SELECT changed.domain,changed.item,nextval('snapshot_cache_item_allocations') FROM (
   SELECT split_part(entry,'|',1) AS domain,substr(entry,strpos(entry,'|')+1) AS item
    FROM unnest(pending) entry WHERE NOT split_part(entry,'|',1)=ANY(overflow)
   UNION SELECT unnest(overflow),'*'
  ) changed ORDER BY changed.domain,changed.item
 ON CONFLICT(domain,item) DO UPDATE SET counter=EXCLUDED.counter;
 FOREACH selected_domain IN ARRAY overflow LOOP
  PERFORM pg_notify('plowshare_item_changes',selected_domain||'|*');
 END LOOP;
 FOR selected_domain IN SELECT DISTINCT split_part(entry,'|',1) FROM unnest(pending) entry
   WHERE NOT split_part(entry,'|',1)=ANY(overflow) LOOP
  payload:=selected_domain;
  FOR address IN SELECT substr(entry,strpos(entry,'|')+1) FROM unnest(pending) entry
    WHERE split_part(entry,'|',1)=selected_domain ORDER BY 1 LOOP
   IF octet_length(payload)+1+octet_length(address)>7900 THEN
    PERFORM pg_notify('plowshare_item_changes',payload);
    payload:=selected_domain;
   END IF;
   payload:=payload||'|'||address;
  END LOOP;
  PERFORM pg_notify('plowshare_item_changes',payload);
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER snapshot_cache_flush_items AFTER INSERT ON snapshot_cache_item_flushes
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION snapshot_cache_flush_items();

-- Items of one changed log-tree row set. NULL when the walk is unbounded or unaddressable.
CREATE FUNCTION snapshot_cache_log_items(starts TEXT[]) RETURNS TEXT[] LANGUAGE plpgsql STABLE AS $$
DECLARE roots TEXT[];
BEGIN
 roots:=snapshot_cache_log_roots(starts);
 IF roots IS NULL THEN RETURN NULL; END IF;
 RETURN ARRAY(SELECT 'conversation:'||root FROM unnest(roots) root);
END $$;

-- Items of one changed information row, given as its relevant columns. NULL is unaddressable.
CREATE FUNCTION snapshot_cache_information_items(relation TEXT,fields JSONB) RETURNS TEXT[]
 LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE policy TEXT;
BEGIN
 CASE relation
  WHEN 'information_revisions' THEN
   RETURN ARRAY['revision:'||(fields->>'id'),'resource:'||(fields->>'resource_id')];
  WHEN 'information_resources' THEN
   RETURN ARRAY['resource:'||(fields->>'id')]
    ||CASE WHEN fields->>'home_scope_id' IS NULL THEN ARRAY[]::TEXT[] ELSE ARRAY['scope:'||(fields->>'home_scope_id')] END;
  WHEN 'information_links' THEN RETURN ARRAY['revision:'||(fields->>'revision_id')];
  WHEN 'information_inputs' THEN RETURN ARRAY['revision:'||(fields->>'derived_revision')];
  WHEN 'information_resource_policies' THEN RETURN ARRAY['resource:'||(fields->>'resource_id')];
  WHEN 'information_reference_obligations' THEN
   RETURN ARRAY['resource:'||(fields->>'source'),'resource:'||(fields->>'target')]
    ||CASE WHEN fields->>'pinned_revision' IS NULL THEN ARRAY[]::TEXT[] ELSE ARRAY['revision:'||(fields->>'pinned_revision')] END;
  WHEN 'information_navigation_policies' THEN
   policy:=fields->>'policy_key';
   IF policy LIKE 'document:%' THEN RETURN ARRAY['resource:'||substr(policy,10)]; END IF;
   RETURN NULL;
  ELSE RETURN NULL;
 END CASE;
END $$;

-- The domain counter, writer record and domain hint are unchanged from V205. Item recording is
-- added after them. Statement triggers see at most 1024 rows before taking the fallback; row
-- triggers (column-filtered UPDATEs) are bounded by the per-transaction item bound.
CREATE OR REPLACE FUNCTION snapshot_cache_readability_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE items TEXT[]; starts TEXT[]; fields JSONB[];
BEGIN
 IF TG_OP<>'TRUNCATE' AND TG_LEVEL='STATEMENT' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['readability']);
 PERFORM nextval('snapshot_cache_readability');
 PERFORM pg_notify('plowshare_readability_changes','readability');

 IF TG_OP='TRUNCATE' THEN
  PERFORM snapshot_cache_record_items('readability',NULL);
  RETURN NULL;
 END IF;
 IF TG_LEVEL='ROW' THEN
  -- Column-filtered UPDATE triggers: conversations, orchestrations, revisions and resources.
  IF TG_TABLE_NAME='conversations' THEN
   items:=snapshot_cache_log_items(ARRAY[OLD.id,OLD.parent_id,NEW.id,NEW.parent_id]);
  ELSIF TG_TABLE_NAME='orchestrations' THEN
   items:=snapshot_cache_log_items(ARRAY[OLD.id,OLD.conductor_conversation,OLD.caller_conversation,
    NEW.id,NEW.conductor_conversation,NEW.caller_conversation]
    ||ARRAY(SELECT conductor_conversation FROM orchestrations WHERE id IN (OLD.parent,NEW.parent)));
  ELSIF TG_TABLE_NAME='information_revisions' THEN
   items:=snapshot_cache_information_items(TG_TABLE_NAME,jsonb_build_object('id',OLD.id,'resource_id',OLD.resource_id))
    ||snapshot_cache_information_items(TG_TABLE_NAME,jsonb_build_object('id',NEW.id,'resource_id',NEW.resource_id));
  ELSIF TG_TABLE_NAME='information_resources' THEN
   items:=snapshot_cache_information_items(TG_TABLE_NAME,jsonb_build_object('id',OLD.id,'home_scope_id',OLD.home_scope_id))
    ||snapshot_cache_information_items(TG_TABLE_NAME,jsonb_build_object('id',NEW.id,'home_scope_id',NEW.home_scope_id));
  END IF;
  PERFORM snapshot_cache_record_items('readability',items);
  RETURN NULL;
 END IF;

 IF (SELECT count(*) FROM (SELECT 1 FROM changed_rows LIMIT 1025) bounded)>1024 THEN
  PERFORM snapshot_cache_record_items('readability',NULL);
  RETURN NULL;
 END IF;
 -- Statement triggers. UPDATE triggers also expose previous_rows, so both old and new addresses
 -- are named; every branch reads only the narrow address columns of its own relation.
 IF TG_TABLE_NAME='conversations' THEN
  starts:=ARRAY(SELECT unnest(ARRAY[id,parent_id]) FROM changed_rows);
  items:=snapshot_cache_log_items(starts);
 ELSIF TG_TABLE_NAME='orchestrations' THEN
  starts:=ARRAY(SELECT unnest(ARRAY[c.id,c.conductor_conversation,c.caller_conversation,p.conductor_conversation])
   FROM changed_rows c LEFT JOIN orchestrations p ON p.id=c.parent);
  items:=snapshot_cache_log_items(starts);
 ELSIF TG_TABLE_NAME='information_job_inputs' THEN
  starts:=ARRAY(SELECT job_id FROM changed_rows);
  IF TG_OP='UPDATE' THEN starts:=starts||ARRAY(SELECT job_id FROM previous_rows); END IF;
  items:=snapshot_cache_log_items(starts);
 ELSIF TG_TABLE_NAME='information_quarantined_payloads' THEN
  starts:=ARRAY(SELECT payload_id FROM changed_rows);
  IF TG_OP='UPDATE' THEN starts:=starts||ARRAY(SELECT payload_id FROM previous_rows); END IF;
  items:=snapshot_cache_log_items(starts);
 ELSIF TG_TABLE_NAME='information_document_policies' THEN
  -- Document policies govern documents, which have no information address.
  items:=NULL;
 ELSE
  IF TG_TABLE_NAME='information_revisions' THEN
   fields:=ARRAY(SELECT jsonb_build_object('id',id,'resource_id',resource_id) FROM changed_rows);
  ELSIF TG_TABLE_NAME='information_resources' THEN
   fields:=ARRAY(SELECT jsonb_build_object('id',id,'home_scope_id',home_scope_id) FROM changed_rows);
  ELSIF TG_TABLE_NAME='information_links' THEN
   fields:=ARRAY(SELECT jsonb_build_object('revision_id',revision_id) FROM changed_rows);
   IF TG_OP='UPDATE' THEN
    fields:=fields||ARRAY(SELECT jsonb_build_object('revision_id',revision_id) FROM previous_rows);
   END IF;
  ELSIF TG_TABLE_NAME='information_inputs' THEN
   fields:=ARRAY(SELECT jsonb_build_object('derived_revision',derived_revision) FROM changed_rows);
   IF TG_OP='UPDATE' THEN
    fields:=fields||ARRAY(SELECT jsonb_build_object('derived_revision',derived_revision) FROM previous_rows);
   END IF;
  ELSIF TG_TABLE_NAME='information_resource_policies' THEN
   fields:=ARRAY(SELECT jsonb_build_object('resource_id',resource_id) FROM changed_rows);
   IF TG_OP='UPDATE' THEN
    fields:=fields||ARRAY(SELECT jsonb_build_object('resource_id',resource_id) FROM previous_rows);
   END IF;
  ELSIF TG_TABLE_NAME='information_navigation_policies' THEN
   fields:=ARRAY(SELECT jsonb_build_object('policy_key',policy_key) FROM changed_rows);
   IF TG_OP='UPDATE' THEN
    fields:=fields||ARRAY(SELECT jsonb_build_object('policy_key',policy_key) FROM previous_rows);
   END IF;
  ELSIF TG_TABLE_NAME='information_reference_obligations' THEN
   fields:=ARRAY(SELECT jsonb_build_object('source',source,'target',target,'pinned_revision',pinned_revision) FROM changed_rows);
   IF TG_OP='UPDATE' THEN
    fields:=fields||ARRAY(SELECT jsonb_build_object('source',source,'target',target,'pinned_revision',pinned_revision) FROM previous_rows);
   END IF;
  END IF;
  -- Any unaddressable row (or an unknown relation, fields NULL) makes the whole set NULL.
  IF fields IS NULL OR EXISTS(SELECT 1 FROM unnest(fields) changed
    WHERE snapshot_cache_information_items(TG_TABLE_NAME,changed) IS NULL) THEN
   items:=NULL;
  ELSE
   items:=ARRAY(SELECT DISTINCT unnest(snapshot_cache_information_items(TG_TABLE_NAME,changed)) FROM unnest(fields) changed);
  END IF;
 END IF;
 PERFORM snapshot_cache_record_items('readability',items);
 RETURN NULL;
END $$;

-- Statement UPDATE triggers previously exposed only the new rows. Recreate them with the old rows
-- as well, so a row moving between items invalidates both.
DO $$
DECLARE relation TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['information_job_inputs','information_quarantined_payloads',
  'information_links','information_inputs','information_resource_policies',
  'information_navigation_policies','information_reference_obligations','information_document_policies']
 LOOP
  EXECUTE format('DROP TRIGGER snapshot_cache_readability_update ON %I',relation);
  EXECUTE format('CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON %I REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_readability_changed()',relation);
 END LOOP;
END $$;

-- contained_principals had no version trigger (V218 added the table after V175 listed the AUTHORITY
-- tables), so a principal's creation or removal moved no authority version. It joins AUTHORITY
-- with the same statement triggers as the other authority tables. AUTHORITY is captured as one
-- snapshot, so no item address is added.
DO $$
DECLARE operation TEXT; transition TEXT;
BEGIN
 FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
  transition:=CASE WHEN operation='DELETE' THEN 'OLD' ELSE 'NEW' END;
  EXECUTE format('CREATE TRIGGER %I AFTER %s ON contained_principals REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed(''authority'')',
   'snapshot_cache_changed_'||lower(operation),operation,transition);
 END LOOP;
 CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON contained_principals
  FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed('authority');
END $$;

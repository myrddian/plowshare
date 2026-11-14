-- Log visibility per log tree (design slice 2): the downward edge walk its tree facts are loaded
-- with, an index for that walk, and one addressing correction for joined trees.
--
-- V257 names a changed log-tree row by the roots of its logs, walked after the change. When an
-- edge gives a log that was a root a parent (a conversation inserted or re-parented under another,
-- an orchestration attaching an existing conductor conversation to a caller or a parent run), the
-- post-change walk reaches only the new roots: the old root, whose tree a reader may hold cached
-- as a root with no parents, was never addressed. Every new or changed edge now also names its
-- child endpoint as conversation:<child>. That is the old root whenever the child was one; for any
-- other child the extra item is harmless, because the child's old roots are among its new roots,
-- which the walk already names. Deleted edges need no change: their parent endpoint is walked, and
-- its roots are the old roots of the child. Edges are:
--   conversations          child id, when parent_id is set;
--   orchestrations         child id (under its conductor), and child conductor_conversation when
--                          caller_conversation or parent is set.
-- An orchestration's parent is a foreign key, so no child run predates its parent's edge, and an
-- orchestration's conductor change re-parents child runs' conductors that already had a parent.
--
-- Everything else in the trigger is reproduced from V257 unchanged: domain counter, writer
-- record, domain hint, bounds, fallbacks, information-row addresses and the commit-time flush.

DO $$
DECLARE relation REGCLASS;
BEGIN
 FOR relation IN SELECT unnest(ARRAY['conversations','orchestrations','information_job_inputs',
  'information_quarantined_payloads','information_revisions','information_resources',
  'information_links','information_inputs','information_resource_policies',
  'information_navigation_policies','information_reference_obligations',
  'information_document_policies']::regclass[])
 LOOP EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',relation); END LOOP;
END $$;

-- The exact inverse of snapshot_cache_log_parents (V257): the children of one log under
-- information_unreadable_logs' descendant edges (V235). conductor_conversation is NOT NULL, so no
-- child is NULL.
CREATE FUNCTION snapshot_cache_log_children(log TEXT) RETURNS SETOF TEXT
 LANGUAGE SQL STABLE AS $$
 SELECT id FROM conversations WHERE parent_id=$1
 UNION ALL SELECT conductor_conversation FROM orchestrations WHERE caller_conversation=$1
 UNION ALL SELECT o.conductor_conversation FROM orchestrations p JOIN orchestrations o ON o.parent=p.id
  WHERE p.conductor_conversation=$1
 UNION ALL SELECT id FROM orchestrations WHERE conductor_conversation=$1
$$;

-- The caller edge's downward step. V51's index on the same column covers live runs only.
CREATE INDEX orchestrations_by_caller_conversation ON orchestrations (caller_conversation)
 WHERE caller_conversation IS NOT NULL;

-- Items of one changed edge set: the roots above every start (snapshot_cache_log_items) and the
-- child endpoint of every new edge. NULL, the domain fallback, whenever the roots are.
CREATE FUNCTION snapshot_cache_log_edge_items(starts TEXT[],children TEXT[]) RETURNS TEXT[]
 LANGUAGE plpgsql STABLE AS $$
DECLARE roots TEXT[];
BEGIN
 roots:=snapshot_cache_log_items(starts);
 IF roots IS NULL THEN RETURN NULL; END IF;
 RETURN ARRAY(SELECT DISTINCT item FROM (
  SELECT unnest(roots) AS item
  UNION SELECT 'conversation:'||child FROM unnest(coalesce(children,ARRAY[]::TEXT[])) child
   WHERE child IS NOT NULL) items ORDER BY item);
END $$;

-- The domain counter, writer record and domain hint are unchanged from V205.
CREATE OR REPLACE FUNCTION snapshot_cache_readability_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE items TEXT[]; starts TEXT[]; children TEXT[]; fields JSONB[];
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
   items:=snapshot_cache_log_edge_items(ARRAY[OLD.id,OLD.parent_id,NEW.id,NEW.parent_id],
    ARRAY[CASE WHEN NEW.parent_id IS NOT NULL THEN NEW.id END]);
  ELSIF TG_TABLE_NAME='orchestrations' THEN
   items:=snapshot_cache_log_edge_items(ARRAY[OLD.id,OLD.conductor_conversation,OLD.caller_conversation,
    NEW.id,NEW.conductor_conversation,NEW.caller_conversation]
    ||ARRAY(SELECT conductor_conversation FROM orchestrations WHERE id IN (OLD.parent,NEW.parent)),
    ARRAY[NEW.id,CASE WHEN NEW.caller_conversation IS NOT NULL OR NEW.parent IS NOT NULL
     THEN NEW.conductor_conversation END]);
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
  children:=CASE WHEN TG_OP='INSERT'
   THEN ARRAY(SELECT id FROM changed_rows WHERE parent_id IS NOT NULL) END;
  items:=snapshot_cache_log_edge_items(starts,children);
 ELSIF TG_TABLE_NAME='orchestrations' THEN
  starts:=ARRAY(SELECT unnest(ARRAY[c.id,c.conductor_conversation,c.caller_conversation,p.conductor_conversation])
   FROM changed_rows c LEFT JOIN orchestrations p ON p.id=c.parent);
  children:=CASE WHEN TG_OP='INSERT' THEN ARRAY(
   SELECT unnest(ARRAY[c.id,CASE WHEN c.caller_conversation IS NOT NULL OR c.parent IS NOT NULL
    THEN c.conductor_conversation END]) FROM changed_rows c) END;
  items:=snapshot_cache_log_edge_items(starts,children);
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

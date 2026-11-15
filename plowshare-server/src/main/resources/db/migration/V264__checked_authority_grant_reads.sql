-- Grant reads may reuse a previously published authority snapshot inside a write transaction,
-- but allocation equality alone is not commit evidence. A writer can allocate before capture,
-- remain invisible to its MVCC snapshot and commit later without another allocation. Validate
-- the snapshot's ORIGINAL pending/future-XID journal evidence in this statement's MVCC snapshot.
-- The slow branch uses that same snapshot, including the caller's preceding uncommitted writes.
--
-- V208's transaction-local writer-domain marker already identifies authority writes and rolls
-- back with savepoints. Reuse it rather than installing another marker or replacing triggers.
-- All authority dependencies retain their statement triggers (V175 and V257).
CREATE FUNCTION authority_grants_check(
 p_database UUID,p_seen BIGINT,p_pending TEXT[],p_future_from TEXT,
 p_window_start TIMESTAMPTZ,p_valid_until TIMESTAMPTZ
) RETURNS TABLE(reason TEXT,checked_version BIGINT) LANGUAGE plpgsql STABLE AS $$
DECLARE identity UUID; floor XID8;
BEGIN
 SELECT CASE WHEN is_called THEN last_value ELSE 0 END INTO checked_version
  FROM snapshot_cache_authority;
 IF 'authority'=ANY(string_to_array(nullif(current_setting('plowshare.cache_writer_domains',true),''),',')) THEN
  reason:='dirty';
 ELSIF current_setting('transaction_isolation')<>'read committed' THEN
  reason:='isolation';
 ELSIF p_database IS NULL OR p_seen IS NULL OR p_pending IS NULL OR p_future_from IS NULL THEN
  reason:='ineligible';
 ELSIF (p_window_start IS NOT NULL AND transaction_timestamp()<p_window_start)
    OR (p_valid_until IS NOT NULL AND transaction_timestamp()>=p_valid_until) THEN
  -- now() in authority_live_grants/account_active is the TRANSACTION timestamp. An old caller
  -- can therefore see an already expired grant that a newer wall-clock snapshot excludes.
  reason:='window';
 ELSE
  SELECT database_id INTO identity FROM snapshot_cache_identity WHERE singleton;
  SELECT through INTO floor FROM snapshot_cache_writer_retention WHERE singleton;
  IF identity IS DISTINCT FROM p_database OR checked_version<>p_seen OR floor IS NULL
     OR p_future_from::xid8<=floor
     OR EXISTS(SELECT 1 FROM unnest(p_pending) pending(xid)
       WHERE pending.xid::xid8<=floor OR pg_xact_status(pending.xid::xid8) IS NULL)
     OR EXISTS(SELECT 1 FROM snapshot_cache_writers writer
       WHERE writer.domain='authority' AND writer.xid>=p_future_from::xid8)
     OR EXISTS(SELECT 1 FROM snapshot_cache_writers writer
       WHERE writer.domain='authority' AND writer.xid=ANY(p_pending::xid8[])) THEN
   reason:='version';
  ELSE
   reason:='exact';
  END IF;
 END IF;
 RETURN NEXT;
END $$;

-- These functions own the only live/lineage query copies used by the grant repository. NULL
-- evidence always executes SQL. A single NULL grant sentinel represents either confirmation or
-- an empty SQL answer; the exact/reason columns distinguish them, and callers inspect grant id.
CREATE FUNCTION authority_live_grants_checked(
 p_account UUID,p_agent TEXT,p_database UUID,p_seen BIGINT,p_pending TEXT[],p_future_from TEXT,
 p_window_start TIMESTAMPTZ,p_valid_until TIMESTAMPTZ
) RETURNS TABLE(exact BOOLEAN,reason TEXT,checked_version BIGINT,grant_row authority_grant_records)
 LANGUAGE plpgsql STABLE AS $$
DECLARE check_reason TEXT; v_checked_version BIGINT;
BEGIN
 SELECT c.reason,c.checked_version INTO check_reason,v_checked_version FROM authority_grants_check(
  p_database,p_seen,p_pending,p_future_from,p_window_start,p_valid_until) c;
 IF check_reason='exact' THEN
  RETURN QUERY SELECT true,check_reason,v_checked_version,NULL::authority_grant_records;
 ELSE
  RETURN QUERY SELECT false,check_reason,v_checked_version,g FROM authority_grant_records g
   JOIN authority_live_grants() l ON l.id=g.id WHERE g.subject_account=p_account
   AND g.subject_agent IS NOT DISTINCT FROM p_agent ORDER BY g.created_at,g.id;
  IF NOT FOUND THEN
   RETURN QUERY SELECT false,check_reason,v_checked_version,NULL::authority_grant_records;
  END IF;
 END IF;
END $$;

CREATE FUNCTION authority_lineage_checked(
 p_grant UUID,p_database UUID,p_seen BIGINT,p_pending TEXT[],p_future_from TEXT,
 p_window_start TIMESTAMPTZ,p_valid_until TIMESTAMPTZ
) RETURNS TABLE(exact BOOLEAN,reason TEXT,checked_version BIGINT,grant_row authority_grant_records)
 LANGUAGE plpgsql STABLE AS $$
DECLARE check_reason TEXT; v_checked_version BIGINT;
BEGIN
 SELECT c.reason,c.checked_version INTO check_reason,v_checked_version FROM authority_grants_check(
  p_database,p_seen,p_pending,p_future_from,p_window_start,p_valid_until) c;
 IF check_reason='exact' THEN
  RETURN QUERY SELECT true,check_reason,v_checked_version,NULL::authority_grant_records;
 ELSE
  RETURN QUERY WITH RECURSIVE chain(id,issuing_grant,depth) AS (
   SELECT g.id,g.issuing_grant,0 FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    WHERE g.id=p_grant
   UNION ALL SELECT p.id,p.issuing_grant,c.depth+1 FROM authority_grants p JOIN chain c
    ON c.issuing_grant=p.id WHERE c.depth<32
  ) SELECT false,check_reason,v_checked_version,g FROM chain c JOIN authority_grant_records g ON g.id=c.id
    ORDER BY c.depth;
  IF NOT FOUND THEN
   RETURN QUERY SELECT false,check_reason,v_checked_version,NULL::authority_grant_records;
  END IF;
 END IF;
END $$;

-- Invoker rights only. The schema owner retains execution. Deployments with a separate runtime
-- role must explicitly grant these signatures (and their existing underlying read privileges).
REVOKE ALL ON FUNCTION authority_grants_check(UUID,BIGINT,TEXT[],TEXT,TIMESTAMPTZ,TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION authority_live_grants_checked(UUID,TEXT,UUID,BIGINT,TEXT[],TEXT,TIMESTAMPTZ,TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION authority_lineage_checked(UUID,UUID,BIGINT,TEXT[],TEXT,TIMESTAMPTZ,TIMESTAMPTZ) FROM PUBLIC;

-- Re-root existing person-issued grants. V260 makes new person-issued grants SYSTEM-rooted
-- (role-exercised issuance); this migration brings grants written under the old V156 lineage into
-- the same shape, so a member no longer falls with the person who added them.
--
-- Invariant: the set of live access is exactly the same before and after this migration. Nothing
-- that was live is lost and nothing that was dead is revived. Grant rows are immutable apart from
-- one revocation, so "moving" a grant means revoking it (attributed to the SYSTEM root) and
-- inserting a copy through authority_grant_insert(); every existing trigger check runs on the copy,
-- and a copy the trigger refuses aborts the migration rather than being skipped.
--
-- Snapshot: the live grant ids (authority_live_grants(), with depth) are captured before the first
-- write. Every liveness decision below is made against that snapshot, because the live set changes
-- as old grants are revoked.
--
-- Dormant grants. authority_live_grants() refuses a grant when any account on its path is disabled
-- (account_active: the subject, an ancestor's subject, or the issuing token's principal or owning
-- service), and re-enabling the account (admins.enabled) brings the grant back. Such a grant is
-- not dead, so this migration must not end it. "Viable" is authority_live_grants() with every
-- admins.enabled test removed: pg_temp.reroot_viable_grants() below copies V216's definition and
-- drops account_active() on the subject and on the token principal, keeping everything else
-- (revocation, expiry, scope kind, bootstrap, server_admin for ADMIN_CONTROL/LEGACY_ADMIN_DATA,
-- the token's MANAGER scope, and the token's own credential, revocation and expiry, which
-- re-enabling an account does not undo). A grant is
--   * live:    in authority_live_grants();
--   * dormant: viable but not live (it revives when the disabled account is re-enabled);
--   * dead:    not viable (revoked or expired lineage, or otherwise never live again).
-- Dormant grants are never revoked or re-rooted, and neither is any grant on the path from the
-- root to a dormant grant (its "kept" ancestors): moving a live ancestor would revoke the old
-- lineage the dormant subtree hangs from. A kept live grant simply stays on its V156 lineage, so it
-- still falls with its issuer, as before this migration. Only dead grants are revoked.
--
-- Pass 1, person-issued grants. Candidates are unrevoked MEMBERSHIP or ROLE grants that are not
-- kept (dormant, or an ancestor of a dormant grant), whose issuing grant is not the root, issued by
-- a person (issuer account_kind='USER', no issuer_agent, no
-- issuing_token) to someone else. Self-issued grants (subject_account = issuer_account, including
-- grants to one's own agents) are not candidates: V260 refuses exercised self-issuance so a
-- temporary grant cannot reissue itself as a permanent one.
--   * Live: revoke, then insert a copy with issuing_grant = root and exercised_grant = the old
--     issuing grant (V260's role-exercised branch). Processed deepest first: a child's exercised
--     grant is its old parent, which must still be live when the child is reissued, and re-rooting
--     the parent revokes the old parent id.
--   * Dead (not viable: lineage already fell, or expired): revoke only, so re-rooting revives
--     nothing. Dead grants go last; revoking them cannot affect any live or dormant grant, since
--     every descendant of a dead grant is dead too.
--
-- Pass 2, delegated descendants. Revoking an old grant in pass 1 ends its lineage, which would
-- also end every grant hanging under it that pass 1 does not re-root: agent-issued, token-issued,
-- self-issued and SERVICE-issued grants. Each such grant that was live in the snapshot and
-- descends (through any number of such grants) from a pass-1 reissued grant is re-parented: revoked,
-- then copied with issuing_grant = the new id of its old parent and every other attribute (issuer,
-- issuer_agent, issuing_token, expires_at, manager_forced, coverage, provenance) unchanged and
-- exercised_grant NULL, so it keeps V156/V216's delegated rules. The copy's parent has the same
-- subject, agent, role, scope and expiry as the old parent, so the unchanged trigger admits it.
-- Processed shallowest first, so a parent's new id exists before its children are copied. A
-- reissued grant has no dormant descendant (it would have been kept), so pass 2 never leaves a
-- dormant grant under a revoked parent; a kept grant reached here raises.
--
-- Self-check: before committing, the multiset of live (subject_account, subject_agent, scope_id,
-- role_id) is compared with the snapshot's, and so is the multiset over viable grants (which
-- covers the dormant ones); any difference raises and rolls the migration back.
--
-- Revoke always precedes insert so the one-current MEMBERSHIP / ADMIN_CONTROL per scope and
-- subject indexes hold. Revocations and issues are recorded in authority_grant_audit by the
-- existing triggers (an exercised reissue's audit authority is the exercised grant).
-- Viable grants: V216's authority_live_grants() with the account_active() conditions removed (see
-- the header). Temporary: it exists only for this migration's session and is dropped below.
CREATE FUNCTION pg_temp.reroot_viable_grants() RETURNS TABLE(id UUID, depth INTEGER) LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE viable(id,depth) AS (
        SELECT g.id,0 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id
        JOIN authority_accounts a ON a.id=g.subject_account JOIN admins u ON u.handle=a.legacy_handle
        JOIN authority_scopes root_scope ON root_scope.id=g.scope_id AND root_scope.kind='ADMINISTRATION' AND root_scope.account_id=r.intrinsic_account
        WHERE g.provenance='ROOT_POLICY' AND g.revoked_at IS NULL
            AND r.intrinsic_account=a.id AND NOT r.assignable AND u.account_kind='BUILTIN'
            AND NOT u.enabled AND g.coverage='ALL_SCOPES'
        UNION ALL
        SELECT g.id,v.depth+1 FROM authority_grants g JOIN viable v ON v.id=g.issuing_grant
        JOIN authority_accounts a ON a.id=g.subject_account JOIN admins u ON u.handle=a.legacy_handle
        JOIN authority_scopes s ON s.id=g.scope_id
        WHERE v.depth<32 AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at>now())
            AND s.kind NOT IN ('RESTRICTED','CONTAINED') AND NOT u.bootstrap
            AND (s.kind<>'PERSONAL' OR s.account_id=g.subject_account)
            -- account_active(t.principal_handle) without its two admins.enabled tests (principal
            -- and owning service); the token's own state is permanent and still required.
            AND (g.issuing_token IS NULL OR EXISTS(SELECT 1 FROM service_tokens t JOIN service_token_scopes ts ON ts.token_id=t.id
                JOIN projects p ON p.id=ts.project_id
                JOIN admins principal ON principal.handle=t.principal_handle
                JOIN admins owner ON owner.handle=t.owner_handle AND owner.account_kind='SERVICE'
                WHERE t.id=g.issuing_token AND ts.project_id=s.project_id AND ts.role='MANAGER'
                    AND t.credential_issued AND t.revoked_at IS NULL AND t.expires_at>now()))
            AND (g.provenance NOT IN ('ADMIN_CONTROL','LEGACY_ADMIN_DATA') OR (u.account_kind='USER' AND u.server_admin))
    ) SELECT * FROM viable;
$$;

DO $$
DECLARE root authority_grants; old authority_grants; plan UUID[]; alive BOOLEAN[]; i INTEGER;
    parent UUID; reissued UUID;
BEGIN
    -- STRICT: a missing or duplicated root must fail the migration, not make every comparison
    -- with root.id NULL and silently select nothing.
    SELECT * INTO STRICT root FROM authority_grants WHERE provenance='ROOT_POLICY';
    -- Both tables live only for this migration's transaction. The snapshot keeps each live grant's
    -- access tuple for the closing self-check.
    CREATE TEMP TABLE reroot_live ON COMMIT DROP AS
        SELECT l.id,l.depth,g.subject_account,g.subject_agent,g.scope_id,g.role_id
        FROM authority_live_grants() l JOIN authority_grants g ON g.id=l.id;
    ALTER TABLE reroot_live ADD PRIMARY KEY (id);
    CREATE TEMP TABLE reroot_viable ON COMMIT DROP AS
        SELECT v.id,g.subject_account,g.subject_agent,g.scope_id,g.role_id
        FROM pg_temp.reroot_viable_grants() v JOIN authority_grants g ON g.id=v.id;
    ALTER TABLE reroot_viable ADD PRIMARY KEY (id);
    -- Every live grant is viable (viability only drops conditions); anything else means the copy
    -- above has diverged from authority_live_grants().
    IF EXISTS(SELECT 1 FROM reroot_live l WHERE NOT EXISTS(SELECT 1 FROM reroot_viable v WHERE v.id=l.id)) THEN
        RAISE EXCEPTION 'Re-root viability does not cover the live grant set'; END IF;
    -- Kept: every dormant grant and every grant on its lineage up to the root. None is moved.
    CREATE TEMP TABLE reroot_kept ON COMMIT DROP AS
        WITH RECURSIVE up(id) AS (
            SELECT v.id FROM reroot_viable v WHERE NOT EXISTS(SELECT 1 FROM reroot_live l WHERE l.id=v.id)
            UNION SELECT g.issuing_grant FROM authority_grants g JOIN up ON up.id=g.id WHERE g.issuing_grant IS NOT NULL)
        SELECT id FROM up;
    ALTER TABLE reroot_kept ADD PRIMARY KEY (id);
    CREATE TEMP TABLE reroot_moved(old_id UUID PRIMARY KEY, new_id UUID NOT NULL) ON COMMIT DROP;

    -- Pass 1: person-issued grants that are not kept, live ones deepest first, then dead ones
    -- (not kept and not live means not viable).
    SELECT coalesce(array_agg(c.id ORDER BY l.depth DESC NULLS LAST, c.id),'{}'),
        coalesce(array_agg(l.id IS NOT NULL ORDER BY l.depth DESC NULLS LAST, c.id),'{}')
        INTO plan,alive
        FROM authority_grants c
        JOIN authority_accounts issuer ON issuer.id=c.issuer_account
        JOIN admins u ON u.handle=issuer.legacy_handle
        LEFT JOIN reroot_live l ON l.id=c.id
        WHERE c.revoked_at IS NULL AND c.provenance IN ('MEMBERSHIP','ROLE') AND c.issuing_grant<>root.id
            AND c.issuer_agent IS NULL AND c.issuing_token IS NULL AND u.account_kind='USER'
            AND c.subject_account<>c.issuer_account
            AND NOT EXISTS(SELECT 1 FROM reroot_kept k WHERE k.id=c.id);
    FOR i IN 1..coalesce(array_length(plan,1),0) LOOP
        SELECT * INTO old FROM authority_grants WHERE id=plan[i];
        UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=root.subject_account,revoking_grant=root.id
            WHERE id=old.id;
        IF alive[i] THEN
            INSERT INTO authority_grants(subject_account,subject_agent,scope_id,role_id,requested_role,role_version,coverage,
                issuer_account,issuing_grant,exercised_grant,manager_forced,expires_at,provenance)
            VALUES(old.subject_account,old.subject_agent,old.scope_id,old.role_id,old.requested_role,old.role_version,old.coverage,
                old.issuer_account,root.id,old.issuing_grant,old.manager_forced,old.expires_at,old.provenance)
            RETURNING id INTO reissued;
            INSERT INTO reroot_moved VALUES(old.id,reissued);
        END IF;
    END LOOP;

    -- Pass 2: snapshot-live descendants of reissued grants that pass 1 did not move, shallowest
    -- first. The walk follows the old lineage; snapshot liveness bounds it to grants that had access.
    WITH RECURSIVE below(id) AS (
        SELECT old_id FROM reroot_moved
        UNION SELECT g.id FROM authority_grants g JOIN below b ON g.issuing_grant=b.id JOIN reroot_live l ON l.id=g.id)
    SELECT coalesce(array_agg(b.id ORDER BY l.depth, b.id),'{}') INTO plan
        FROM below b JOIN reroot_live l ON l.id=b.id
        WHERE NOT EXISTS(SELECT 1 FROM reroot_moved m WHERE m.old_id=b.id);
    FOR i IN 1..coalesce(array_length(plan,1),0) LOOP
        SELECT * INTO old FROM authority_grants WHERE id=plan[i];
        SELECT new_id INTO parent FROM reroot_moved WHERE old_id=old.issuing_grant;
        IF parent IS NULL OR old.revoked_at IS NOT NULL OR EXISTS(SELECT 1 FROM reroot_kept k WHERE k.id=old.id) THEN
            RAISE EXCEPTION 'Cannot re-parent delegated grant %', old.id; END IF;
        UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=root.subject_account,revoking_grant=root.id
            WHERE id=old.id;
        INSERT INTO authority_grants(subject_account,subject_agent,scope_id,role_id,requested_role,role_version,coverage,
            issuer_account,issuer_agent,issuing_grant,issuing_token,manager_forced,expires_at,provenance)
        VALUES(old.subject_account,old.subject_agent,old.scope_id,old.role_id,old.requested_role,old.role_version,old.coverage,
            old.issuer_account,old.issuer_agent,parent,old.issuing_token,old.manager_forced,old.expires_at,old.provenance)
        RETURNING id INTO reissued;
        INSERT INTO reroot_moved VALUES(old.id,reissued);
    END LOOP;

    -- Self-check: live access must be exactly the snapshot's, as a multiset (EXCEPT ALL treats
    -- NULL subject_agent values as equal).
    CREATE TEMP TABLE reroot_after ON COMMIT DROP AS
        SELECT g.subject_account,g.subject_agent,g.scope_id,g.role_id
        FROM authority_live_grants() l JOIN authority_grants g ON g.id=l.id;
    IF EXISTS((SELECT subject_account,subject_agent,scope_id,role_id FROM reroot_live) EXCEPT ALL TABLE reroot_after)
        OR EXISTS(TABLE reroot_after EXCEPT ALL (SELECT subject_account,subject_agent,scope_id,role_id FROM reroot_live)) THEN
        RAISE EXCEPTION 'Re-root changed the live grant set'; END IF;
    -- The same over viable grants: no dormant grant was ended or orphaned and none was created.
    CREATE TEMP TABLE reroot_viable_after ON COMMIT DROP AS
        SELECT g.subject_account,g.subject_agent,g.scope_id,g.role_id
        FROM pg_temp.reroot_viable_grants() v JOIN authority_grants g ON g.id=v.id;
    IF EXISTS((SELECT subject_account,subject_agent,scope_id,role_id FROM reroot_viable) EXCEPT ALL TABLE reroot_viable_after)
        OR EXISTS(TABLE reroot_viable_after EXCEPT ALL (SELECT subject_account,subject_agent,scope_id,role_id FROM reroot_viable)) THEN
        RAISE EXCEPTION 'Re-root changed the dormant grant set'; END IF;
END $$;

DROP FUNCTION pg_temp.reroot_viable_grants();

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
-- Pass 1, person-issued grants. Candidates are unrevoked MEMBERSHIP or ROLE grants whose issuing
-- grant is not the root, issued by a person (issuer account_kind='USER', no issuer_agent, no
-- issuing_token) to someone else. Self-issued grants (subject_account = issuer_account, including
-- grants to one's own agents) are not candidates: V260 refuses exercised self-issuance so a
-- temporary grant cannot reissue itself as a permanent one.
--   * Live: revoke, then insert a copy with issuing_grant = root and exercised_grant = the old
--     issuing grant (V260's role-exercised branch). Processed deepest first: a child's exercised
--     grant is its old parent, which must still be live when the child is reissued, and re-rooting
--     the parent revokes the old parent id.
--   * Not live (lineage already fell, or expired): revoke only, so re-rooting revives nothing.
--     Not-live grants go last; revoking them cannot affect any live grant.
--
-- Pass 2, delegated descendants. Revoking an old grant in pass 1 ends its lineage, which would
-- also end every grant hanging under it that pass 1 does not re-root: agent-issued, token-issued,
-- self-issued and SERVICE-issued grants. Each such grant that was live in the snapshot and
-- descends (through any number of such grants) from a pass-1 reissued grant is re-parented: revoked,
-- then copied with issuing_grant = the new id of its old parent and every other attribute (issuer,
-- issuer_agent, issuing_token, expires_at, manager_forced, coverage, provenance) unchanged and
-- exercised_grant NULL, so it keeps V156/V216's delegated rules. The copy's parent has the same
-- subject, agent, role, scope and expiry as the old parent, so the unchanged trigger admits it.
-- Processed shallowest first, so a parent's new id exists before its children are copied.
--
-- Revoke always precedes insert so the one-current MEMBERSHIP / ADMIN_CONTROL per scope and
-- subject indexes hold. Revocations and issues are recorded in authority_grant_audit by the
-- existing triggers (an exercised reissue's audit authority is the exercised grant).
DO $$
DECLARE root authority_grants; old authority_grants; plan UUID[]; alive BOOLEAN[]; i INTEGER;
    parent UUID; reissued UUID;
BEGIN
    SELECT * INTO root FROM authority_grants WHERE provenance='ROOT_POLICY';
    -- Both tables live only for this migration's transaction.
    CREATE TEMP TABLE reroot_live ON COMMIT DROP AS SELECT id,depth FROM authority_live_grants();
    ALTER TABLE reroot_live ADD PRIMARY KEY (id);
    CREATE TEMP TABLE reroot_moved(old_id UUID PRIMARY KEY, new_id UUID NOT NULL) ON COMMIT DROP;

    -- Pass 1: person-issued grants, live ones deepest first, then not-live ones.
    SELECT coalesce(array_agg(c.id ORDER BY l.depth DESC NULLS LAST, c.id),'{}'),
        coalesce(array_agg(l.id IS NOT NULL ORDER BY l.depth DESC NULLS LAST, c.id),'{}')
        INTO plan,alive
        FROM authority_grants c
        JOIN authority_accounts issuer ON issuer.id=c.issuer_account
        JOIN admins u ON u.handle=issuer.legacy_handle
        LEFT JOIN reroot_live l ON l.id=c.id
        WHERE c.revoked_at IS NULL AND c.provenance IN ('MEMBERSHIP','ROLE') AND c.issuing_grant<>root.id
            AND c.issuer_agent IS NULL AND c.issuing_token IS NULL AND u.account_kind='USER'
            AND c.subject_account<>c.issuer_account;
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
        IF parent IS NULL OR old.revoked_at IS NOT NULL THEN
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
END $$;

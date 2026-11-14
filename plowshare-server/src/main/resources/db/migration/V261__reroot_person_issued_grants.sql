-- Re-root existing person-issued grants. V260 makes new person-issued grants SYSTEM-rooted
-- (role-exercised issuance); this migration brings grants written under the old V156 lineage into
-- the same shape, so a member no longer falls with the person who added them.
--
-- Candidates: unrevoked MEMBERSHIP or ROLE grants whose issuing grant is not the root, issued by a
-- person (issuer account_kind='USER', no issuer_agent, no issuing_token) to someone else.
--   * Grants issued by SERVICE accounts, agents or tokens keep their delegated lineage untouched.
--   * Self-issued grants (subject_account = issuer_account, including grants to one's own agents)
--     are untouched: V260 refuses exercised self-issuance so a temporary grant cannot reissue
--     itself as a permanent one. They stay live or dead exactly as their lineage says.
--
-- For each candidate, liveness is decided once, before any change:
--   * Live: revoke it (attributed to the SYSTEM root) and insert an identical grant whose
--     issuing_grant is the root and whose exercised_grant is the old issuing grant. The insert goes
--     through authority_grant_insert(), so every V260 check (live exercised grant held by the
--     issuer, person issuer, role, scope and coverage ceilings) runs against real data; a grant
--     that fails them aborts the migration rather than being silently dropped.
--   * Not live (its lineage already fell, or it expired): revoke it, attributed to the root, so
--     re-rooting never revives access that is already gone.
--
-- Ordering: live candidates are processed deepest first. A child's exercised grant is its old
-- parent, which must still be live when the child is reissued; re-rooting the parent revokes the
-- old parent id, so it must come after all of its children. Revoking a not-live grant cannot affect
-- any live grant, so those come last.
--
-- Revoking an old grant ends its lineage. Descendants that are re-rooted are already reissued by
-- then; descendants this migration deliberately leaves untouched (agent-, token- or self-issued
-- grants hanging under a re-rooted grant) stop being live with it, exactly as if the old grant had
-- been removed. Both revocations and reissues are recorded in authority_grant_audit by the
-- existing triggers (a reissue's audit authority is the exercised grant).
DO $$
DECLARE root authority_grants; old authority_grants; plan UUID[]; alive BOOLEAN[]; i INTEGER;
BEGIN
    SELECT * INTO root FROM authority_grants WHERE provenance='ROOT_POLICY';
    -- Materialize the plan, with liveness, before the first write: the live set changes as old
    -- grants are revoked, and every decision must be made against the state before migration.
    SELECT coalesce(array_agg(c.id ORDER BY l.depth DESC NULLS LAST, c.id),'{}'),
        coalesce(array_agg(l.id IS NOT NULL ORDER BY l.depth DESC NULLS LAST, c.id),'{}')
        INTO plan,alive
        FROM authority_grants c
        JOIN authority_accounts issuer ON issuer.id=c.issuer_account
        JOIN admins u ON u.handle=issuer.legacy_handle
        LEFT JOIN authority_live_grants() l ON l.id=c.id
        WHERE c.revoked_at IS NULL AND c.provenance IN ('MEMBERSHIP','ROLE') AND c.issuing_grant<>root.id
            AND c.issuer_agent IS NULL AND c.issuing_token IS NULL AND u.account_kind='USER'
            AND c.subject_account<>c.issuer_account;
    FOR i IN 1..coalesce(array_length(plan,1),0) LOOP
        SELECT * INTO old FROM authority_grants WHERE id=plan[i];
        -- Revoke before reissuing: authority_current_membership allows one unrevoked MEMBERSHIP
        -- per scope and account.
        UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=root.subject_account,revoking_grant=root.id
            WHERE id=old.id;
        IF alive[i] THEN
            INSERT INTO authority_grants(subject_account,subject_agent,scope_id,role_id,requested_role,role_version,coverage,
                issuer_account,issuing_grant,exercised_grant,manager_forced,expires_at,provenance)
            VALUES(old.subject_account,old.subject_agent,old.scope_id,old.role_id,old.requested_role,old.role_version,old.coverage,
                old.issuer_account,root.id,old.issuing_grant,old.manager_forced,old.expires_at,old.provenance);
        END IF;
    END LOOP;
END $$;

-- Role-exercised issuance. A role is permanent; the authority to exercise it is temporary. When a
-- person (a USER account acting with no agent and no service token) issues a grant by exercising
-- one of their own live grants, the new grant belongs to the role rather than to that account:
--
--   * issuing_grant (lineage, and therefore liveness) is the SYSTEM ROOT_POLICY grant, so the new
--     grant never falls with the issuer's grant, account state or later demotion;
--   * exercised_grant records which grant authorized the issue, for authorization and audit;
--   * issuer_account still records the person.
--
-- Authorization is exactly as strict as before: the trigger runs every existing check against the
-- exercised grant (liveness, depth, principal, role ceiling, scope coverage, descendant coverage).
-- Only the "outlives issuing authority" cap is dropped, because the new grant no longer depends on
-- the exercised grant. Delegated issuers (issuer_agent, issuing_token) keep the V156/V216 lineage
-- and expiry: they act inside someone else's bounded authority and their narrowing is the point.

-- The CHECK is the single owner of "delegated issuers cannot use the exercised path"; the trigger
-- runs before constraints, so it deliberately does not duplicate this test and the CHECK reports it.
ALTER TABLE authority_grants ADD COLUMN exercised_grant UUID REFERENCES authority_grants(id),
    ADD CONSTRAINT authority_grants_exercised_by_person
        CHECK (exercised_grant IS NULL OR (issuer_agent IS NULL AND issuing_token IS NULL));

-- authority_grant_insert(): V216's definition with one added branch. When exercised_grant is NULL
-- every statement runs as in V216, in the same order. When it is set, `parent` is the exercised
-- grant, so the shared role, scope and coverage checks below apply to it unchanged; the branch
-- additionally requires the issuing grant to be the live SYSTEM root and the issuer to be a USER.
CREATE OR REPLACE FUNCTION authority_grant_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent authority_grants; parent_role TEXT; requested TEXT; effective TEXT; destination authority_scopes;
    subject_kind TEXT; issuer_kind TEXT; parent_depth INTEGER; rooted BOOLEAN;
BEGIN
    IF NEW.provenance='ROOT_POLICY' THEN RAISE EXCEPTION 'Root policy cannot be assigned'; END IF;
    IF NEW.exercised_grant IS NULL THEN
        PERFORM authority_lock_lineage(NEW.issuing_grant);
        SELECT * INTO parent FROM authority_grants WHERE id=NEW.issuing_grant;
        SELECT depth INTO parent_depth FROM authority_live_grants() WHERE id=parent.id;
    ELSE
        -- A person cannot exercise a grant for themselves: otherwise a temporary (expiring or
        -- revocable-with-its-issuer) grant could reissue itself as a permanent SYSTEM-rooted one.
        IF NEW.subject_account=NEW.issuer_account THEN RAISE EXCEPTION 'Cannot exercise a grant for yourself'; END IF;
        -- Share-lock the exercised lineage so a concurrent revocation of the exercised grant or
        -- any ancestor cannot commit between this check and the new grant's commit. The root
        -- itself is immutable (authority_grant_mutation refuses ROOT_POLICY updates).
        PERFORM authority_lock_lineage(NEW.exercised_grant);
        SELECT * INTO parent FROM authority_grants WHERE id=NEW.exercised_grant;
        -- One live-grant evaluation answers both questions: the exercised grant's depth, and
        -- whether the issuing grant is the live root (depth 0 is only ever a ROOT_POLICY grant).
        SELECT max(l.depth) FILTER (WHERE l.id=NEW.exercised_grant),
            coalesce(bool_or(l.id=NEW.issuing_grant AND l.depth=0 AND g.provenance='ROOT_POLICY'),FALSE)
            INTO parent_depth,rooted
            FROM authority_live_grants() l JOIN authority_grants g ON g.id=l.id
            WHERE l.id IN (NEW.exercised_grant,NEW.issuing_grant);
        IF NOT rooted THEN RAISE EXCEPTION 'Exercised issuance must be rooted at the system policy'; END IF;
    END IF;
    IF parent_depth IS NULL OR parent_depth>=32 THEN RAISE EXCEPTION 'Issuing authority is not live or chain is too deep'; END IF;
    IF NEW.exercised_grant IS NULL THEN
        IF NOT ((parent.subject_account=NEW.issuer_account AND parent.subject_agent IS NOT DISTINCT FROM NEW.issuer_agent AND NEW.issuing_token IS NULL)
            OR (NEW.issuing_token IS NOT NULL AND NEW.issuer_agent IS NULL AND parent.subject_agent IS NULL AND EXISTS(
                SELECT 1 FROM service_tokens t JOIN authority_accounts owner ON owner.legacy_handle=t.owner_handle
                JOIN authority_accounts principal ON principal.legacy_handle=t.principal_handle
                JOIN service_token_scopes ts ON ts.token_id=t.id JOIN authority_scopes s ON s.project_id=ts.project_id
                WHERE t.id=NEW.issuing_token AND owner.id=parent.subject_account AND principal.id=NEW.issuer_account
                    AND s.id=NEW.scope_id AND ts.role='MANAGER' AND account_active(t.principal_handle)
                    AND NEW.expires_at IS NOT NULL AND NEW.expires_at<=t.expires_at))) THEN
            RAISE EXCEPTION 'Issuing grant belongs to a different principal'; END IF;
    ELSIF NOT (parent.subject_account=NEW.issuer_account AND parent.subject_agent IS NULL) THEN
        -- issuer_agent and issuing_token are refused for this branch by the CHECK constraint.
        RAISE EXCEPTION 'Issuing grant belongs to a different principal';
    END IF;
    SELECT name INTO parent_role FROM authority_roles WHERE id=parent.role_id;
    SELECT name INTO effective FROM authority_roles WHERE id=NEW.role_id;
    SELECT * INTO destination FROM authority_scopes WHERE id=NEW.scope_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Grant scope is unavailable'; END IF;
    SELECT u.account_kind INTO subject_kind FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=NEW.subject_account;
    SELECT u.account_kind INTO issuer_kind FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=NEW.issuer_account;
    IF NEW.exercised_grant IS NOT NULL AND issuer_kind IS DISTINCT FROM 'USER' THEN
        RAISE EXCEPTION 'Exercised issuance requires a person issuer'; END IF;
    IF subject_kind NOT IN ('USER','SERVICE') OR destination.kind IN ('RESTRICTED','CONTAINED')
        OR (destination.kind='PERSONAL' AND destination.account_id<>NEW.subject_account)
        OR (effective='ADMIN' AND (subject_kind<>'USER' OR NEW.subject_agent IS NOT NULL)) THEN
        RAISE EXCEPTION 'Recipient or scope cannot receive that role'; END IF;
    IF effective IN ('SYSTEM','GLOBAL_READ') OR NEW.requested_role='SYSTEM' THEN RAISE EXCEPTION 'Role is not assignable'; END IF;
    IF NEW.subject_agent IS NOT NULL AND NEW.requested_role='ADMIN' THEN RAISE EXCEPTION 'Agents cannot receive ADMIN'; END IF;
    IF NEW.manager_forced AND (NEW.subject_agent IS NULL OR NEW.requested_role<>'MANAGER'
        OR issuer_kind<>'USER' OR NEW.issuer_agent IS NOT NULL) THEN RAISE EXCEPTION 'MANAGER force requires a human issuer'; END IF;
    IF NEW.issuer_agent IS NOT NULL AND (NEW.manager_forced OR effective='ADMIN') THEN
        RAISE EXCEPTION 'Agent cannot propagate administrative exceptions'; END IF;
    requested := CASE WHEN NEW.subject_agent IS NOT NULL AND NEW.requested_role='MANAGER' AND NOT NEW.manager_forced
        THEN 'CONTRIBUTOR' ELSE NEW.requested_role END;
    IF effective IS DISTINCT FROM requested OR parent_role NOT IN ('SYSTEM','ADMIN','MANAGER')
        OR (parent_role='MANAGER' AND effective='ADMIN') THEN RAISE EXCEPTION 'Role exceeds issuing authority'; END IF;
    IF NOT (parent.coverage='ALL_SCOPES' OR
        parent.scope_id=NEW.scope_id OR
        (parent.coverage='ORDINARY_PROJECT_CONTROL' AND destination.kind='PROJECT')) THEN
        RAISE EXCEPTION 'Grant exceeds issuing scope'; END IF;
    IF NEW.coverage<>'SELF' AND NOT (NEW.provenance='ADMIN_CONTROL' AND parent.provenance='ROOT_POLICY'
        AND NEW.coverage='ORDINARY_PROJECT_CONTROL' AND destination.kind='ADMINISTRATION' AND effective='ADMIN') THEN
        RAISE EXCEPTION 'Unsupported descendant coverage'; END IF;
    -- An exercised grant hangs off the root, not the exercised grant, so it does not inherit the
    -- exercised grant's expiry.
    IF NEW.exercised_grant IS NULL AND parent.expires_at IS NOT NULL AND (NEW.expires_at IS NULL OR NEW.expires_at>parent.expires_at) THEN
        RAISE EXCEPTION 'Grant outlives issuing authority'; END IF;
    IF NEW.version<>1 OR NEW.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'Invalid initial grant state'; END IF;
    RETURN NEW;
END $$;

-- authority_audit_grant(): the audit authority of an exercised issue is the grant the person
-- exercised, not the SYSTEM root it is attached to. Revocations still name their revoking grant.
CREATE OR REPLACE FUNCTION authority_audit_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO authority_grant_audit(grant_id,event,version,actor_account,actor_agent,authority)
    VALUES(NEW.id,CASE WHEN TG_OP='INSERT' THEN 'ISSUE' ELSE 'REVOKE' END,NEW.version,coalesce(NEW.revoked_by,NEW.issuer_account),
        CASE WHEN TG_OP='INSERT' THEN NEW.issuer_agent ELSE NEW.revoked_by_agent END,
        coalesce(NEW.revoking_grant,NEW.exercised_grant,NEW.issuing_grant));
    RETURN NEW;
END $$;

-- authority_issue_membership(): a person (USER actor, no service token) exercises their management
-- grant and issues a SYSTEM-rooted, uncapped membership to someone else. Service tokens,
-- SERVICE-account actors and self-assignment keep V156's behaviour exactly: the membership hangs off the authorizing grant and inherits its
-- expiry, narrowed to the token's.
CREATE OR REPLACE FUNCTION authority_issue_membership(project TEXT, target TEXT, actor TEXT, next_role TEXT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE authority UUID; sid UUID; subject UUID; issuer UUID; result UUID; expiry TIMESTAMPTZ; token UUID; owner TEXT; token_expiry TIMESTAMPTZ;
    root UUID; person BOOLEAN;
BEGIN
    authority := authority_project_management_grant(project,actor);
    IF authority IS NULL AND service_project_role(project,actor)='MANAGER' THEN
        SELECT t.id,t.owner_handle,t.expires_at INTO token,owner,token_expiry FROM service_tokens t WHERE t.principal_handle=actor;
        authority := authority_project_management_grant(project,owner);
    END IF;
    IF authority IS NULL THEN RAISE EXCEPTION 'Account cannot manage this project'; END IF;
    SELECT s.id INTO sid FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=project;
    SELECT id INTO subject FROM authority_accounts WHERE legacy_handle=target;
    SELECT id INTO issuer FROM authority_accounts WHERE legacy_handle=actor;
    IF EXISTS(SELECT 1 FROM authority_grants WHERE id=authority AND provenance='MEMBERSHIP' AND subject_account=subject) THEN
        RAISE EXCEPTION 'Replacing your issuing membership requires another manager'; END IF;
    -- Self-assignment is not role-exercised issuance: it stays on the delegated path below, bound
    -- to the authority that permitted it and capped at its expiry, so a temporary grant cannot
    -- launder itself into a permanent SYSTEM-rooted one. The trigger refuses exercised self-issuance.
    person := token IS NULL AND subject IS DISTINCT FROM issuer
        AND EXISTS(SELECT 1 FROM admins WHERE handle=actor AND account_kind='USER');
    UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=issuer,revoking_grant=authority
        WHERE scope_id=sid AND subject_account=subject AND provenance='MEMBERSHIP' AND revoked_at IS NULL;
    IF person THEN
        SELECT id INTO root FROM authority_grants WHERE provenance='ROOT_POLICY';
        INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,exercised_grant,expires_at,provenance)
        SELECT subject,sid,r.id,r.name,issuer,root,authority,NULL,'MEMBERSHIP' FROM authority_roles r
            WHERE r.name=CASE WHEN next_role='VIEWER' THEN 'READER' ELSE next_role END RETURNING id INTO result;
    ELSE
        SELECT expires_at INTO expiry FROM authority_grants WHERE id=authority;
        expiry := least(expiry,token_expiry);
        INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,expires_at,issuing_token,provenance)
        SELECT subject,sid,r.id,r.name,issuer,authority,expiry,token,'MEMBERSHIP' FROM authority_roles r
            WHERE r.name=CASE WHEN next_role='VIEWER' THEN 'READER' ELSE next_role END RETURNING id INTO result;
    END IF;
    IF result IS NULL THEN RAISE EXCEPTION 'Unknown project role'; END IF;
    RETURN result;
END $$;

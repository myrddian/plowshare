-- Knowledge custody, creation evidence and revision authorship are independent dimensions.
ALTER TABLE information_resources ADD COLUMN home_scope_id UUID REFERENCES authority_scopes(id);
ALTER TABLE information_resources ADD COLUMN created_by UUID REFERENCES authority_accounts(id);
ALTER TABLE information_resources ADD COLUMN creator_evidence TEXT NOT NULL DEFAULT 'unknown'
    CHECK (creator_evidence IN ('unknown','admission_request','intake_event','authenticated_admission'));
ALTER TABLE information_revisions ADD COLUMN created_by UUID REFERENCES authority_accounts(id);
ALTER TABLE information_revisions ADD COLUMN creator_evidence TEXT NOT NULL DEFAULT 'unknown'
    CHECK (creator_evidence IN ('unknown','admission_request','intake_event','authenticated_admission'));
ALTER TABLE information_resources ADD CHECK ((created_by IS NULL)=(creator_evidence='unknown'));
ALTER TABLE information_revisions ADD CHECK ((created_by IS NULL)=(creator_evidence='unknown'));

-- Do not use owner_handle: it may have been assigned after creation of a legacy document.
WITH candidates AS (
    SELECT request.revision_id,a.id,'admission_request'::text AS evidence
    FROM information_requests request JOIN authority_accounts a ON a.legacy_handle=request.account
    UNION ALL
    SELECT e.revision_id,a.id,'intake_event' FROM information_events e
    JOIN authority_accounts a ON a.legacy_handle=e.actor_handle
    WHERE e.stage='intake' AND e.action='retained'
), evidence AS (
    SELECT revision_id,min(id::text)::uuid AS account,count(DISTINCT id) AS accounts,
        CASE WHEN bool_or(evidence='admission_request') THEN 'admission_request' ELSE 'intake_event' END AS source
    FROM candidates GROUP BY revision_id
)
UPDATE information_revisions r SET created_by=e.account,creator_evidence=e.source
FROM evidence e WHERE e.revision_id=r.id AND e.accounts=1;
UPDATE information_resources q SET created_by=r.created_by,creator_evidence=r.creator_evidence
FROM information_revisions r WHERE r.resource_id=q.id AND r.ordinal=1;

CREATE FUNCTION information_home(project BIGINT, namespace TEXT, custodian TEXT) RETURNS UUID
LANGUAGE SQL STABLE AS $$
    SELECT s.id FROM authority_scopes s WHERE
      ($1 IS NOT NULL AND s.project_id=$1 AND (($2='legacy' AND $3 IS NOT NULL) OR $2='project:'||(SELECT name FROM projects WHERE id=$1)))
      OR ($1 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=(
          SELECT id FROM authority_accounts WHERE legacy_handle=$3)
          AND $2='account:'||$3);
$$;
-- Lookup by the explicit home also survives project renames. Stored namespaces remain historical
-- compatibility locators; they are not scope identities and must not create a second resource.
CREATE FUNCTION information_namespace_home(namespace TEXT) RETURNS UUID LANGUAGE SQL STABLE AS $$
    SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE 'project:'||p.name=$1
    UNION ALL SELECT s.id FROM authority_scopes s JOIN authority_accounts a ON a.id=s.account_id
        WHERE s.kind='ACCOUNT_KNOWLEDGE' AND 'account:'||a.legacy_handle=$1;
$$;
UPDATE information_resources SET home_scope_id=information_home(project_id,namespace,owner_handle);
CREATE UNIQUE INDEX information_resources_home_name ON information_resources(home_scope_id,source_name) WHERE home_scope_id IS NOT NULL;

CREATE FUNCTION information_knowledge_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected UUID;
BEGIN
    IF TG_TABLE_NAME='information_resources' THEN
        expected:=information_home(NEW.project_id,NEW.namespace,NEW.owner_handle);
        IF TG_OP='UPDATE' AND (NEW.created_by IS DISTINCT FROM OLD.created_by
            OR NEW.creator_evidence IS DISTINCT FROM OLD.creator_evidence) THEN
            RAISE EXCEPTION 'knowledge creator provenance is immutable';
        END IF;
        -- Existing administrative legacy assignment is the sole supported home change. There is
        -- no general move API; collection links do not modify these fields.
        IF TG_OP='UPDATE' AND OLD.home_scope_id IS NOT NULL AND
            (NEW.home_scope_id IS DISTINCT FROM OLD.home_scope_id
             OR NEW.project_id IS DISTINCT FROM OLD.project_id
             OR NEW.namespace IS DISTINCT FROM OLD.namespace
             OR NEW.owner_handle IS DISTINCT FROM OLD.owner_handle) THEN
            RAISE EXCEPTION 'knowledge home transfer is not supported';
        END IF;
        -- A project rename changes its locator, not its scope or resource namespace history.
        IF TG_OP='UPDATE' AND OLD.home_scope_id IS NOT NULL AND NEW.project_id IS NOT NULL
            AND EXISTS(SELECT 1 FROM authority_scopes WHERE id=OLD.home_scope_id AND project_id=NEW.project_id) THEN
            expected:=OLD.home_scope_id;
        END IF;
        IF NEW.home_scope_id IS NULL THEN NEW.home_scope_id:=expected; END IF;
        IF NEW.home_scope_id IS DISTINCT FROM expected THEN
            RAISE EXCEPTION 'knowledge home does not match its compatibility mapping';
        END IF;
    ELSIF TG_OP='UPDATE' AND (NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.creator_evidence IS DISTINCT FROM OLD.creator_evidence) THEN
        RAISE EXCEPTION 'revision creator provenance is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_resource_identity BEFORE INSERT OR UPDATE ON information_resources
    FOR EACH ROW EXECUTE FUNCTION information_knowledge_identity();
CREATE TRIGGER information_revision_creator BEFORE UPDATE ON information_revisions
    FOR EACH ROW EXECUTE FUNCTION information_knowledge_identity();

-- Preserve memory formation/source history. NULL here has a documented meaning: archive Global
-- tier. This is not a rule for documents, conversations or omitted execution targets.
ALTER TABLE memories ADD COLUMN home_scope_id UUID REFERENCES authority_scopes(id);
UPDATE memories m SET home_scope_id=CASE WHEN m.project_id IS NULL
    THEN '00000000-0000-0000-0000-000000000005'::uuid
    ELSE (SELECT s.id FROM authority_scopes s WHERE s.project_id=m.project_id) END;
ALTER TABLE memories ALTER COLUMN home_scope_id SET NOT NULL;
CREATE INDEX memories_home ON memories(home_scope_id);
CREATE FUNCTION memory_knowledge_scope(project BIGINT) RETURNS UUID LANGUAGE SQL STABLE AS $$
    SELECT CASE WHEN $1 IS NULL THEN '00000000-0000-0000-0000-000000000005'::uuid
        ELSE (SELECT id FROM authority_scopes WHERE project_id=$1) END;
$$;
CREATE FUNCTION memory_knowledge_home() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected UUID;
BEGIN
    expected:=CASE WHEN NEW.project_id IS NULL THEN '00000000-0000-0000-0000-000000000005'::uuid
        ELSE (SELECT id FROM authority_scopes WHERE project_id=NEW.project_id) END;
    IF expected IS NULL THEN
        RAISE foreign_key_violation USING MESSAGE='memories_project_exists: memory requires an existing project scope';
    END IF;
    -- Keep existing storage saves atomic with the explicit mapping; promotion creates a separate
    -- row and its own provenance. This trigger grants no new archive operation.
    IF TG_OP='INSERT' AND NEW.home_scope_id IS NOT NULL AND NEW.home_scope_id IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'memory home does not match its project';
    END IF;
    IF TG_OP='UPDATE' AND NEW.project_id IS NOT DISTINCT FROM OLD.project_id
        AND NEW.home_scope_id IS DISTINCT FROM OLD.home_scope_id THEN
        RAISE EXCEPTION 'memory home does not match its project';
    END IF;
    NEW.home_scope_id:=expected;
    RETURN NEW;
END $$;
CREATE TRIGGER memory_knowledge_home BEFORE INSERT OR UPDATE ON memories
    FOR EACH ROW EXECUTE FUNCTION memory_knowledge_home();

-- Current persisted role/token grants defend repositories independently of Java admission. The
-- Application-aware resolver remains an additional ceiling at the service boundary. Personal
-- account knowledge keeps its exact-subject rule; restricted/unclassified homes fail closed.
CREATE FUNCTION information_home_allows(resource UUID, account TEXT, selected_scope TEXT,
    selected_project TEXT, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT coalesce(account_active($2) AND EXISTS(SELECT 1 FROM admins WHERE handle=$2 AND NOT bootstrap) AND $5 IN ('READ','WRITE','SET_AUDIENCE') AND (
      (s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=a.id AND q.project_id IS NULL
          AND q.namespace='account:'||$2 AND $3='personal' AND $4 IS NULL)
      OR (s.kind IN ('PROJECT','PERSONAL') AND s.project_id=q.project_id
          AND p.name=$4 AND $3='project'
          AND CASE $5
            WHEN 'READ' THEN coalesce(authority_project_role(p.name,$2),service_project_role(p.name,$2)) IS NOT NULL
            WHEN 'WRITE' THEN coalesce(authority_project_role(p.name,$2),service_project_role(p.name,$2)) IN ('CONTRIBUTOR','MANAGER')
            WHEN 'SET_AUDIENCE' THEN coalesce(authority_project_role(p.name,$2),service_project_role(p.name,$2))='MANAGER'
          END)),false)
    FROM information_resources q JOIN authority_scopes s ON s.id=q.home_scope_id
    LEFT JOIN projects p ON p.id=s.project_id
    LEFT JOIN authority_accounts a ON a.legacy_handle=$2 WHERE q.id=$1;
$$;
CREATE FUNCTION information_knowledge_allows(revision UUID, account TEXT, selected_scope TEXT,
    selected_project TEXT, include_shared BOOLEAN, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT coalesce(information_home_allows(q.id,$2,$3,$4,$6)
        AND ($2 NOT LIKE '@service/%' OR ($3='project' AND NOT $5))
        AND (NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true) OR q.owner_handle=$2)
        AND information_reference_readable(r.id,$2,$4)
        AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id
            AND NOT information_readable(i.input_revision,$2,$3,$4,$5))
        -- A projected private/quarantined revision cannot become project-writable merely because
        -- its compatibility home names that project. Pending sources have no corpus policy yet.
        AND NOT EXISTS(SELECT 1 FROM information_document_policies policy WHERE policy.document_id=r.id
            AND (policy.visibility='quarantined' OR (policy.visibility='personal' AND policy.owner_handle<>$2))),false)
    FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id WHERE r.id=$1;
$$;
-- Source identity changes take account locks before grant-row locks. Ancestor recipient accounts
-- and token identities must stay active through commit as well as the immediate writer.
CREATE FUNCTION information_lock_scope(account TEXT, scope UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE grant_id UUID;
BEGIN
    PERFORM id FROM projects WHERE id=(SELECT project_id FROM authority_scopes WHERE id=$2) FOR SHARE;
    PERFORM id FROM authority_scopes WHERE id=$2 FOR SHARE;
    PERFORM u.handle FROM admins u WHERE u.handle=$1 OR u.handle=(SELECT owner_handle FROM service_tokens WHERE principal_handle=$1)
        OR u.handle IN (
            WITH RECURSIVE chain AS (
                SELECT g.id,g.subject_account,g.issuing_grant,g.issuing_token,0 AS depth
                FROM authority_grants g JOIN authority_live_grants() live ON live.id=g.id JOIN authority_accounts a ON a.id=g.subject_account
                WHERE g.scope_id=$2 AND g.subject_agent IS NULL
                    AND (a.legacy_handle=$1 OR a.legacy_handle=(SELECT owner_handle FROM service_tokens WHERE principal_handle=$1))
                UNION
                SELECT g.id,g.subject_account,g.issuing_grant,g.issuing_token,c.depth+1
                FROM authority_grants g JOIN chain c ON c.issuing_grant=g.id WHERE c.depth<32
            ) SELECT a.legacy_handle FROM chain c JOIN authority_accounts a ON a.id=c.subject_account
        ) ORDER BY u.handle FOR SHARE;
    PERFORM id FROM service_tokens WHERE principal_handle=$1 FOR SHARE;
    FOR grant_id IN SELECT g.id FROM authority_grants g JOIN authority_live_grants() live ON live.id=g.id JOIN authority_accounts a ON a.id=g.subject_account
        WHERE g.subject_agent IS NULL AND g.scope_id=$2
          AND (a.legacy_handle=$1 OR a.legacy_handle=(SELECT owner_handle FROM service_tokens WHERE principal_handle=$1))
        ORDER BY g.id
    LOOP
        PERFORM authority_lock_lineage(grant_id);
    END LOOP;
END $$;
CREATE FUNCTION information_lock_destination(account TEXT, selected_scope TEXT, selected_project TEXT, include_shared BOOLEAN)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE home UUID;
BEGIN
    -- Shared lock excludes policy/reference changes without serializing ordinary content writes.
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
    SELECT s.id INTO home FROM authority_scopes s LEFT JOIN projects p ON p.id=s.project_id
        LEFT JOIN authority_accounts a ON a.id=s.account_id WHERE
        ($2='project' AND p.name=$3 AND s.kind IN ('PROJECT','PERSONAL')) OR
        ($2='personal' AND $3 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND a.legacy_handle=$1 AND a.builtin IS NULL);
    IF home IS NULL OR ($1 LIKE '@service/%' AND ($2<>'project' OR $4)) THEN RETURN false; END IF;
    PERFORM information_lock_scope($1,home);
    RETURN coalesce(account_active($1) AND EXISTS(SELECT 1 FROM admins WHERE handle=$1 AND NOT bootstrap) AND CASE WHEN $2='project' THEN
        coalesce(authority_project_role($3,$1),service_project_role($3,$1)) IN ('CONTRIBUTOR','MANAGER')
        ELSE true END,false);
END $$;
CREATE FUNCTION information_lock_knowledge(revision UUID, account TEXT, selected_scope TEXT,
    selected_project TEXT, include_shared BOOLEAN, action TEXT) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE resource UUID; home UUID;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
    SELECT q.id,q.home_scope_id INTO resource,home FROM information_revisions r
        JOIN information_resources q ON q.id=r.resource_id WHERE r.id=$1;
    IF home IS NULL THEN RETURN false; END IF;
    -- Match membership/grant mutation order, then lock resource. Recheck against a fresh command
    -- snapshot after waiting; no remote/model work runs in this transaction.
    PERFORM information_lock_scope($2,home);
    PERFORM id FROM information_resources WHERE id=resource FOR UPDATE;
    IF NOT coalesce(information_knowledge_allows($1,$2,$3,$4,$5,$6),false) THEN RETURN false; END IF;
    IF $6='SET_AUDIENCE' THEN
        -- Transaction-local marker fences pre-cutover owner-based audience writers. It records
        -- this exact admitted revision/actor, never an ambient or client-supplied permission.
        PERFORM set_config('plowshare.knowledge_audience_actor',$2,true);
        PERFORM set_config('plowshare.knowledge_audience_revision',$1::text,true);
    END IF;
    RETURN true;
END $$;

-- Pending project sources use the same scoped READ as their retained home. Projected visibility,
-- per-revision publication and the outer mandatory input/reference checks remain unchanged.
CREATE OR REPLACE FUNCTION information_legacy_readable(target UUID, account TEXT, selected_scope TEXT,
    selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE inputs(id) AS (
        SELECT target UNION SELECT i.input_revision FROM information_inputs i JOIN inputs ON i.derived_revision=inputs.id
    )
    SELECT account_active(account) AND EXISTS(SELECT 1 FROM admins a WHERE a.handle=account AND NOT a.bootstrap) AND (account NOT LIKE '@service/%' OR (selected_scope='project' AND NOT include_shared)) AND coalesce(bool_and(
        (p.document_id IS NOT NULL OR (p.document_id IS NULL AND information_home_allows(q.id,account,selected_scope,selected_project,'READ') AND q.namespace<>'legacy'
            AND ((q.project_id IS NULL AND selected_scope='personal') OR (selected_scope='project' AND EXISTS(
                SELECT 1 FROM projects pr
                WHERE pr.id=q.project_id AND pr.name=selected_project AND information_project_readable(pr.id,account))))))
        AND coalesce(r.availability='active',true)
        AND (
            ((selected_scope='shared' OR include_shared) AND p.visibility='shared')
            OR (selected_scope='personal' AND ((p.visibility='personal' AND p.owner_handle=account) OR (p.document_id IS NULL AND q.owner_handle=account AND q.project_id IS NULL AND q.namespace<>'legacy')))
            OR (selected_scope='project' AND (
                ((p.visibility='project' OR (p.document_id IS NULL AND information_home_allows(q.id,account,selected_scope,selected_project,'READ') AND q.namespace<>'legacy')) AND EXISTS(SELECT 1 FROM projects pr
                    WHERE pr.id=coalesce(p.project_id,q.project_id) AND pr.name=selected_project AND information_project_readable(pr.id,account)))
                OR (p.visibility='personal' AND p.owner_handle=account AND EXISTS(SELECT 1 FROM information_links l
                    JOIN projects pr ON pr.id=l.project_id
                    WHERE l.revision_id=inputs.id AND pr.name=selected_project AND information_project_readable(pr.id,account)))
            ))
        )
    ),false)
    FROM inputs LEFT JOIN information_document_policies p ON p.document_id=inputs.id
    LEFT JOIN information_revisions r ON r.id=inputs.id
    LEFT JOIN information_resources q ON q.id=r.resource_id;
$$;

-- Old binaries must not regain creator-based share/unshare authority during a mixed deployment.
-- Initial corpus projection and explicit quarantined legacy assignment keep their own contracts.
CREATE FUNCTION information_audience_write_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor TEXT; home_project TEXT; home_scope TEXT;
BEGIN
    IF (NEW.visibility IS DISTINCT FROM OLD.visibility OR NEW.project_id IS DISTINCT FROM OLD.project_id)
        AND NEW.visibility IN ('personal','project','shared') AND OLD.visibility IN ('personal','project','shared')
        AND EXISTS(SELECT 1 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
            WHERE r.id=NEW.document_id AND q.home_scope_id IS NOT NULL) THEN
        actor:=nullif(current_setting('plowshare.knowledge_audience_actor',true),'');
        SELECT p.name,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END
            INTO home_project,home_scope FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id
            LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=NEW.document_id;
        IF actor IS NULL OR current_setting('plowshare.knowledge_audience_revision',true) IS DISTINCT FROM NEW.document_id::text
            OR NOT coalesce(information_knowledge_allows(NEW.document_id,actor,home_scope,home_project,actor NOT LIKE '@service/%','SET_AUDIENCE'),false) THEN
            RAISE insufficient_privilege USING MESSAGE='audience mutation requires current scoped control; owner-based writers are incompatible';
        END IF;
        PERFORM set_config('plowshare.knowledge_audience_actor','',true);
        PERFORM set_config('plowshare.knowledge_audience_revision','',true);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_audience_write_fence BEFORE UPDATE ON information_document_policies
    FOR EACH ROW EXECUTE FUNCTION information_audience_write_fence();

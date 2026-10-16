-- Resource relationships are independent of revision collection links and retained evidence.
CREATE TABLE information_navigation_policies (
    policy_key TEXT PRIMARY KEY,
    version BIGINT NOT NULL CHECK(version > 0),
    settings JSONB NOT NULL,
    actor TEXT NOT NULL REFERENCES admins(handle),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(settings)='object')
);
CREATE TABLE information_relationships (
    id UUID PRIMARY KEY,
    source UUID NOT NULL REFERENCES information_resources(id),
    target UUID NOT NULL REFERENCES information_resources(id),
    pinned_revision UUID REFERENCES information_revisions(id),
    relation TEXT NOT NULL CHECK(relation ~ '^[a-z][a-z0-9_-]{0,63}$'),
    actor TEXT NOT NULL REFERENCES admins(handle),
    version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
    removed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX information_relationships_source ON information_relationships(source) WHERE NOT removed;
CREATE INDEX information_relationships_target ON information_relationships(target) WHERE NOT removed;
-- Append-only edges survive unlinking. A target that later tightens is covered transactionally.
CREATE TABLE information_reference_obligations (
    relationship UUID PRIMARY KEY REFERENCES information_relationships(id),
    source UUID NOT NULL REFERENCES information_resources(id),
    target UUID NOT NULL REFERENCES information_resources(id),
    pinned_revision UUID REFERENCES information_revisions(id),
    incurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX information_reference_obligations_source ON information_reference_obligations(source);
CREATE TABLE information_navigation_views (
    account TEXT NOT NULL REFERENCES admins(handle),
    id UUID NOT NULL,
    label TEXT NOT NULL CHECK(length(label) BETWEEN 1 AND 120),
    path JSONB NOT NULL,
    version BIGINT NOT NULL CHECK(version>0),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(account,id)
);
CREATE TABLE information_navigation_receipts (
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    fingerprint TEXT NOT NULL,
    result JSONB NOT NULL,
    PRIMARY KEY(account,request_id)
);
CREATE TABLE information_navigation_audit (
    sequence BIGSERIAL PRIMARY KEY,
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    action TEXT NOT NULL,
    subject TEXT NOT NULL,
    reason TEXT NOT NULL,
    previous_value JSONB,
    next_value JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account,request_id)
);

-- Project defaults can be overridden only where permitted. Mandatory platform/project controls
-- combine monotonically; personal documents use the platform defaults and document overrides.
CREATE FUNCTION information_navigation_policy(resource UUID) RETURNS JSONB LANGUAGE SQL STABLE AS $$
 WITH settings AS (
   SELECT q.id, global.settings AS g, project.settings AS p, document.settings AS d
   FROM information_resources q
   LEFT JOIN information_navigation_policies global ON global.policy_key='platform'
   LEFT JOIN information_navigation_policies project ON project.policy_key='project:'||q.project_id::text
   LEFT JOIN information_navigation_policies document ON document.policy_key='document:'||q.id::text
   WHERE q.id=resource
 ), chosen AS (
   SELECT *, coalesce(g,'{"defaults":{"ownerOnly":false,"referenceHidden":true,"propagate":true},"mandatory":{"ownerOnly":false,"referenceHidden":false,"propagate":false},"allowOverrides":true,"referenceLabel":"Restricted document"}'::jsonb) AS platform,
     coalesce(p,g,'{"defaults":{"ownerOnly":false,"referenceHidden":true,"propagate":true},"mandatory":{"ownerOnly":false,"referenceHidden":false,"propagate":false},"allowOverrides":true,"referenceLabel":"Restricted document"}'::jsonb) AS project
   FROM settings
 ), effective AS (
   SELECT *, CASE WHEN coalesce((platform->>'allowOverrides')::boolean,false)
         AND coalesce((project->>'allowOverrides')::boolean,false) THEN coalesce(d,project) ELSE project END AS selected
   FROM chosen
 )
 SELECT jsonb_build_object(
   'ownerOnly', coalesce((selected->'defaults'->>'ownerOnly')::boolean,true) OR coalesce((selected->'mandatory'->>'ownerOnly')::boolean,true)
      OR coalesce((platform->'mandatory'->>'ownerOnly')::boolean,true) OR coalesce((project->'mandatory'->>'ownerOnly')::boolean,true),
   'referenceHidden', coalesce((selected->'defaults'->>'referenceHidden')::boolean,true) OR coalesce((selected->'mandatory'->>'referenceHidden')::boolean,true)
      OR coalesce((platform->'mandatory'->>'referenceHidden')::boolean,true) OR coalesce((project->'mandatory'->>'referenceHidden')::boolean,true),
   'propagate', coalesce((selected->'defaults'->>'propagate')::boolean,true) OR coalesce((selected->'mandatory'->>'propagate')::boolean,true)
      OR coalesce((platform->'mandatory'->>'propagate')::boolean,true) OR coalesce((project->'mandatory'->>'propagate')::boolean,true),
   'referenceLabel', selected->>'referenceLabel') FROM effective;
$$;

CREATE FUNCTION information_navigation_head(resource UUID) RETURNS UUID LANGUAGE SQL STABLE AS $$
 SELECT id FROM information_revisions WHERE resource_id=resource ORDER BY ordinal DESC LIMIT 1;
$$;

ALTER FUNCTION information_readable(UUID,TEXT,TEXT,TEXT,BOOLEAN) RENAME TO information_legacy_readable;

-- Evaluate an individual dependency in its durable scope. Service identities cannot traverse
-- out of the selected project or acquire Shared/Personal authority by guessing UUIDs.
CREATE FUNCTION information_navigation_own_readable(revision UUID, account TEXT,
 selected_project TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce((NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true) OR q.owner_handle=account)
   AND (account NOT LIKE '@service/%' OR (p.name=selected_project AND p.id IS NOT NULL))
   AND information_legacy_readable(r.id,account,CASE WHEN p.id IS NULL THEN 'personal' ELSE 'project' END,
         p.name,account NOT LIKE '@service/%'),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=revision;
$$;

-- Reference disclosure never releases a quarantined legacy record. The metadata representation
-- requires either an assigned corpus policy or an authenticated, owned pending source.
CREATE FUNCTION information_navigation_reference_visible(resource UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM information_resources q
   JOIN information_revisions r ON r.id=information_navigation_head(q.id)
   LEFT JOIN projects project ON project.id=q.project_id
   LEFT JOIN information_document_policies policy ON policy.document_id=r.id
   WHERE q.id=$1 AND r.availability='active' AND NOT r.excluded
     AND NOT coalesce((information_navigation_policy(q.id)->>'referenceHidden')::boolean,true)
     AND ((policy.visibility IN ('personal','project','shared') AND policy.owner_handle IS NOT NULL)
       OR (policy.document_id IS NULL AND q.owner_handle IS NOT NULL AND q.namespace<>'legacy'))
     AND ($2 NOT LIKE '@service/%' OR project.name=$3));
$$;

-- Bound new reference traversal independently of the existing retained-input ledger. Large legacy
-- input sets retain their prior access; each consumed revision still checks its own policy and
-- every required reference. Actual inputs of a referenced target remain part of that dependency.
CREATE FUNCTION information_required_references_readable(target UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE required(id) AS (
   -- Qualify the parameter: an unqualified target would bind the relationship column.
   SELECT coalesce(o.pinned_revision,information_navigation_head(o.target))
     FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source
     WHERE r.id=$1
   UNION
   SELECT edge.id FROM required d CROSS JOIN LATERAL (
     SELECT i.input_revision AS id FROM information_inputs i WHERE i.derived_revision=d.id
     UNION
     SELECT coalesce(o.pinned_revision,information_navigation_head(o.target))
       FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source
       WHERE r.id=d.id
   ) edge
 ), bounded AS (SELECT id FROM required LIMIT 1025)
 SELECT count(*) <= 1024 AND coalesce(bool_and(
   coalesce(information_navigation_own_readable(b.id,account,selected_project),false)),true)
 FROM bounded b;
$$;
CREATE FUNCTION information_reference_readable(target UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE inputs(id) AS (
   SELECT target UNION SELECT i.input_revision FROM information_inputs i JOIN inputs p ON i.derived_revision=p.id
 )
 SELECT coalesce(bool_and((CASE WHEN EXISTS(SELECT 1 FROM information_revisions r WHERE r.id=i.id)
   THEN EXISTS(SELECT 1 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
     WHERE r.id=i.id AND (NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true) OR q.owner_handle=account))
   -- Pre-lifecycle corpus rows have no resource policy/relationships. Their existing explicit
   -- legacy policy is still checked by information_legacy_readable, never inferred here.
   ELSE EXISTS(SELECT 1 FROM information_document_policies p WHERE p.document_id=i.id)
     AND NOT EXISTS(SELECT 1 FROM information_resources q WHERE q.id=i.id) END)
   AND information_required_references_readable(i.id,account,selected_project)),false)
 FROM inputs i;
$$;
CREATE FUNCTION information_readable(target UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_legacy_readable(target,account,selected_scope,selected_project,include_shared)
   AND information_reference_readable(target,account,selected_project),false);
$$;

CREATE FUNCTION information_navigation_obligations() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed INTEGER; rounds INTEGER := 0;
BEGIN
 IF TG_TABLE_NAME='information_relationships' THEN
   IF NEW.pinned_revision IS NOT NULL AND NOT EXISTS(SELECT 1 FROM information_revisions
       WHERE id=NEW.pinned_revision AND resource_id=NEW.target) THEN
     RAISE EXCEPTION 'pinned revision must belong to relationship target';
   END IF;
 END IF;
 -- A non-propagating intermediary cannot launder inherited restrictions. Reach a fixed point over
 -- current incoming references whenever an obligation is added or a target policy tightens.
 LOOP
   INSERT INTO information_reference_obligations(relationship,source,target,pinned_revision)
     SELECT e.id,e.source,e.target,e.pinned_revision FROM information_relationships e
     WHERE NOT e.removed AND (
       coalesce((information_navigation_policy(e.target)->>'propagate')::boolean,true)
       OR EXISTS(SELECT 1 FROM information_reference_obligations inherited WHERE inherited.source=e.target))
     ON CONFLICT DO NOTHING;
   GET DIAGNOSTICS changed = ROW_COUNT;
   EXIT WHEN changed=0;
   rounds := rounds+1;
   IF rounds>1024 THEN RAISE EXCEPTION 'reference propagation exceeds closure bound'; END IF;
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER information_relationship_obligations AFTER INSERT OR UPDATE ON information_relationships
 FOR EACH ROW EXECUTE FUNCTION information_navigation_obligations();
CREATE TRIGGER information_policy_obligations AFTER INSERT OR UPDATE ON information_navigation_policies
 FOR EACH ROW EXECUTE FUNCTION information_navigation_obligations();

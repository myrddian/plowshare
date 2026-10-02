-- Public identity is scoped; immutable revisions have independent corpus projections.
ALTER TABLE documents ADD COLUMN information_namespace TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE documents DROP CONSTRAINT documents_source_name_key;
ALTER TABLE documents ADD CONSTRAINT documents_namespaced_name UNIQUE (information_namespace, source_name);

CREATE TABLE information_resources (
    id UUID PRIMARY KEY,
    namespace TEXT NOT NULL,
    source_name TEXT NOT NULL CHECK (btrim(source_name) <> ''),
    owner_handle TEXT REFERENCES admins(handle),
    project_id BIGINT REFERENCES projects(id),
    kind TEXT NOT NULL CHECK (kind IN ('source', 'report')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(namespace, source_name)
);
CREATE TABLE information_revisions (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES information_resources(id),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    -- Source metadata survives a deliberate content deletion.
    title TEXT NOT NULL,
    media_type TEXT NOT NULL,
    source_uri TEXT,
    content_hash TEXT NOT NULL,
    text_hash TEXT,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    source_bytes BYTEA,
    extracted_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    availability TEXT NOT NULL DEFAULT 'active' CHECK (availability IN ('active', 'excluded', 'withdrawn', 'deleted')),
    excluded BOOLEAN NOT NULL DEFAULT false,
    generation BIGINT NOT NULL DEFAULT 1,
    UNIQUE(resource_id, ordinal)
);
-- Existing projections are retained, never reattributed or silently re-extracted.
INSERT INTO information_resources(id, namespace, source_name, owner_handle, project_id, kind, created_at)
SELECT d.id, 'legacy', d.source_name, p.owner_handle, p.project_id, 'source', d.ingested_at
FROM documents d JOIN information_document_policies p ON p.document_id=d.id;
INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,created_at)
SELECT id,id,1,title,'application/x-plowshare-legacy',content_hash,text_hash,byte_size,ingested_at FROM documents;

CREATE TABLE information_requests (
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    fingerprint TEXT NOT NULL,
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    PRIMARY KEY(account, request_id)
);
CREATE TABLE information_steps (
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    generation BIGINT NOT NULL,
    stage TEXT NOT NULL CHECK (stage IN ('extract','derive','embed','summarise','summary_embed')),
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','running','ready','failed','blocked','cancelled','skipped')),
    attempt INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error TEXT,
    fingerprint TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY(revision_id,generation,stage)
);
CREATE INDEX information_pending_steps ON information_steps(state,lease_until);
CREATE TABLE information_events (
    sequence BIGSERIAL PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    generation BIGINT NOT NULL,
    actor_handle TEXT REFERENCES admins(handle),
    stage TEXT NOT NULL,
    action TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE information_links (
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    project_id BIGINT NOT NULL REFERENCES projects(id),
    actor_handle TEXT NOT NULL REFERENCES admins(handle),
    PRIMARY KEY(revision_id,project_id)
);
CREATE TABLE information_inputs (
    derived_revision UUID NOT NULL REFERENCES information_revisions(id),
    input_revision UUID NOT NULL REFERENCES information_revisions(id),
    PRIMARY KEY(derived_revision,input_revision),
    CHECK(derived_revision<>input_revision)
);
CREATE TABLE information_evidence (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    paragraph_id UUID,
    start_offset INTEGER NOT NULL CHECK (start_offset>=0),
    end_offset INTEGER NOT NULL CHECK (end_offset>start_offset),
    quote TEXT NOT NULL CHECK (quote<>''),
    locator TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE information_reports (
    revision_id UUID PRIMARY KEY REFERENCES information_revisions(id),
    status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','final','superseded')),
    feedback_revision UUID REFERENCES information_revisions(id),
    details JSONB NOT NULL DEFAULT '{"objectives":[],"findings":[],"reviews":[],"scopeChanges":[]}',
    produced_by TEXT,
    definition_hash TEXT,
    finalised_at TIMESTAMPTZ
);
CREATE TABLE information_report_citations (
    report_revision UUID NOT NULL REFERENCES information_reports(revision_id),
    evidence_id UUID NOT NULL REFERENCES information_evidence(id),
    PRIMARY KEY(report_revision,evidence_id)
);
CREATE FUNCTION information_report_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.revision_id IS DISTINCT FROM OLD.revision_id OR NEW.feedback_revision IS DISTINCT FROM OLD.feedback_revision
  OR NEW.produced_by IS DISTINCT FROM OLD.produced_by OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash THEN
  RAISE EXCEPTION 'report provenance is immutable';
 END IF;
 IF NEW.details IS DISTINCT FROM OLD.details AND NOT (NEW.details='{"deleted":true}'::jsonb
   AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted') THEN
  RAISE EXCEPTION 'report findings are immutable; record a feedback revision';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER information_report_immutable BEFORE UPDATE ON information_reports
 FOR EACH ROW EXECUTE FUNCTION information_report_immutable();
CREATE TABLE information_job_inputs (
    job_id TEXT NOT NULL,
    owner_handle TEXT NOT NULL REFERENCES admins(handle),
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    scope TEXT NOT NULL,
    project_name TEXT,
    include_shared BOOLEAN NOT NULL,
    PRIMARY KEY(job_id,revision_id)
);

-- Check EVERY ancestor, not merely cited inputs. UNION terminates even for malformed cycles.
CREATE FUNCTION information_readable(target UUID, account TEXT, selected_scope TEXT,
    selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE inputs(id) AS (
        SELECT target UNION SELECT i.input_revision FROM information_inputs i JOIN inputs ON i.derived_revision=inputs.id
    )
    SELECT coalesce(bool_and(
        (p.document_id IS NOT NULL OR (p.document_id IS NULL AND q.owner_handle=account AND q.namespace<>'legacy'
            AND ((q.project_id IS NULL AND selected_scope='personal') OR (selected_scope='project' AND EXISTS(
                SELECT 1 FROM projects pr JOIN project_members m ON m.project_id=pr.id
                WHERE pr.id=q.project_id AND pr.name=selected_project AND m.handle=account)))))
        AND coalesce(r.availability='active',true)
        AND (
            ((selected_scope='shared' OR include_shared) AND p.visibility='shared')
            OR (selected_scope='personal' AND ((p.visibility='personal' AND p.owner_handle=account) OR (p.document_id IS NULL AND q.owner_handle=account AND q.project_id IS NULL AND q.namespace<>'legacy')))
            OR (selected_scope='project' AND (
                ((p.visibility='project' OR (p.document_id IS NULL AND q.owner_handle=account AND q.namespace<>'legacy')) AND EXISTS(SELECT 1 FROM projects pr JOIN project_members m ON m.project_id=pr.id
                    WHERE pr.id=coalesce(p.project_id,q.project_id) AND pr.name=selected_project AND m.handle=account))
                OR (p.visibility='personal' AND p.owner_handle=account AND EXISTS(SELECT 1 FROM information_links l
                    JOIN projects pr ON pr.id=l.project_id JOIN project_members m ON m.project_id=pr.id
                    WHERE l.revision_id=inputs.id AND pr.name=selected_project AND m.handle=account))
            ))
        )
    ),false)
    FROM inputs LEFT JOIN information_document_policies p ON p.document_id=inputs.id
    LEFT JOIN information_revisions r ON r.id=inputs.id
    LEFT JOIN information_resources q ON q.id=r.resource_id;
$$;
ALTER TABLE information_revisions ADD COLUMN processing_log TEXT;
ALTER TABLE information_revisions ADD COLUMN caller_session TEXT;
-- Old corpus payloads have no reliable all-input ledger. Quarantine the known paths for explicit audit.
CREATE TABLE information_quarantined_payloads (
    payload_id TEXT PRIMARY KEY,
    reason TEXT NOT NULL
);
INSERT INTO information_quarantined_payloads
SELECT id,'legacy document job has no authenticated input ledger' FROM jobs WHERE agent IN ('ask','ingest');
WITH RECURSIVE affected(id) AS (
    SELECT id FROM conversations WHERE agent IN ('ask_proposer','ask_critic','ask_reviewer','ask_synthesiser',
        'paragraph_summariser','span_summariser','section_summariser','chapter_summariser','document_summariser')
    UNION SELECT conversation_id FROM entries WHERE tool_calls::text ~ '"name"\s*:\s*"document_(search|list|ask)"'
    UNION SELECT c.parent_id FROM conversations c JOIN affected a ON a.id=c.id WHERE c.parent_id IS NOT NULL
)
INSERT INTO information_quarantined_payloads SELECT id,'legacy corpus log has no complete input ledger' FROM affected ON CONFLICT DO NOTHING;
CREATE FUNCTION information_log_readable(target TEXT, account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE edges(child,parent) AS (
        SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL
        UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
        UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
        UNION SELECT id,conductor_conversation FROM orchestrations
    ), ancestors(id) AS (
        SELECT target UNION SELECT e.parent FROM edges e JOIN ancestors a ON e.child=a.id
    )
    SELECT NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_quarantined_payloads q ON q.payload_id=a.id)
    AND NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_job_inputs i ON i.job_id=a.id WHERE
        account IS NULL OR account<>i.owner_handle
        OR NOT information_readable(i.revision_id,account,i.scope,i.project_name,i.include_shared));
$$;
ALTER TABLE information_revisions ADD COLUMN converter TEXT;
ALTER TABLE information_revisions ADD COLUMN outline_top_level JSONB NOT NULL DEFAULT '[]';
ALTER TABLE information_revisions ADD COLUMN allowance_total INTEGER NOT NULL DEFAULT 1000 CHECK(allowance_total>=0);
ALTER TABLE information_revisions ADD COLUMN allowance_spent INTEGER NOT NULL DEFAULT 0 CHECK(allowance_spent>=0 AND allowance_spent<=allowance_total);
-- Classify surviving legacy capability outputs without asserting historical completeness.
UPDATE information_revisions SET converter='legacy projection; original response bytes unavailable'
 WHERE media_type='application/x-plowshare-legacy';
INSERT INTO information_steps(revision_id,generation,stage,state,error)
 SELECT d.id,1,'extract','skipped','Original source bytes and full extraction were not retained by the legacy corpus.' FROM documents d
 UNION ALL SELECT d.id,1,'derive',CASE WHEN EXISTS(SELECT 1 FROM paragraphs p WHERE p.document_id=d.id) THEN 'ready' ELSE 'failed' END,
   CASE WHEN EXISTS(SELECT 1 FROM paragraphs p WHERE p.document_id=d.id) THEN NULL ELSE 'No valid legacy derivation; supply a new source revision.' END FROM documents d
 UNION ALL SELECT d.id,1,'embed',CASE WHEN EXISTS(SELECT 1 FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=d.id)
   AND NOT EXISTS(SELECT 1 FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=d.id AND c.embedding IS NULL) THEN 'ready' ELSE 'failed' END,
   'Legacy passage vector coverage must be inspected before retry.' FROM documents d
 UNION ALL SELECT d.id,1,'summarise',CASE WHEN d.summary IS NOT NULL
   AND NOT EXISTS(SELECT 1 FROM paragraphs p WHERE p.document_id=d.id AND p.summary IS NULL)
   AND NOT EXISTS(SELECT 1 FROM sections s WHERE s.document_id=d.id AND s.summary IS NULL AND EXISTS(SELECT 1 FROM paragraphs p WHERE p.section_id=s.id))
   AND NOT EXISTS(SELECT 1 FROM chapters c WHERE c.document_id=d.id AND c.summary IS NULL AND EXISTS(SELECT 1 FROM sections s JOIN paragraphs p ON p.section_id=s.id WHERE s.chapter_id=c.id)) THEN 'ready' ELSE 'failed' END,
   'Legacy hierarchy completeness must be inspected before retry.' FROM documents d
 UNION ALL SELECT d.id,1,'summary_embed',CASE WHEN d.summary IS NOT NULL AND d.summary_embedding IS NOT NULL THEN 'ready' ELSE 'failed' END,
   'Legacy summary vector coverage must be inspected before retry.' FROM documents d;
UPDATE information_steps SET error=NULL WHERE state='ready';

-- Content is immutable once retained/extracted; deletion may remove it but may never replace it.
CREATE FUNCTION information_revision_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.resource_id<>OLD.resource_id OR NEW.ordinal<>OLD.ordinal OR NEW.content_hash<>OLD.content_hash
       OR NEW.byte_size<>OLD.byte_size OR NEW.created_at<>OLD.created_at
       OR NEW.media_type<>OLD.media_type OR NEW.source_uri IS DISTINCT FROM OLD.source_uri
       OR (OLD.converter IS NOT NULL AND NEW.converter IS DISTINCT FROM OLD.converter)
       OR (OLD.text_hash IS NOT NULL AND NEW.text_hash IS DISTINCT FROM OLD.text_hash)
       OR (OLD.extracted_text IS NOT NULL AND NEW.outline_top_level IS DISTINCT FROM OLD.outline_top_level)
       OR (NEW.source_bytes IS DISTINCT FROM OLD.source_bytes AND NOT (NEW.availability='deleted' AND NEW.source_bytes IS NULL))
       OR (OLD.extracted_text IS NOT NULL AND NEW.extracted_text IS DISTINCT FROM OLD.extracted_text
           AND NOT (NEW.availability='deleted' AND NEW.extracted_text IS NULL)) THEN
        RAISE EXCEPTION 'information revision content is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER information_revision_immutable BEFORE UPDATE ON information_revisions
FOR EACH ROW EXECUTE FUNCTION information_revision_immutable();

-- A report's dependency and citation sets are immutable after admission. Content deletion
-- retains the dependency graph so that descendants continue to fail closed.
CREATE FUNCTION information_input_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'information input dependencies are immutable';
END;
$$;
CREATE TRIGGER information_input_immutable BEFORE UPDATE OR DELETE ON information_inputs
FOR EACH ROW EXECUTE FUNCTION information_input_immutable();

-- Paid model responses are durable even when a post gate withholds their use.
CREATE TABLE information_model_steps (
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    generation BIGINT NOT NULL,
    stage_key TEXT NOT NULL,
    stage TEXT NOT NULL,
    owner_handle TEXT NOT NULL REFERENCES admins(handle),
    response JSONB NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('blocked','ready')),
    log_id TEXT,
    PRIMARY KEY(revision_id,generation,stage_key)
);

CREATE TABLE information_commands (
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('reserved','applied','completed','blocked','failed')),
    token UUID NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    error TEXT,
    PRIMARY KEY(account,request_id)
);

-- Acquisition failures and receipts exist before network work, independently of retained revisions.
CREATE TABLE information_acquisitions (
    id UUID PRIMARY KEY,
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    scope TEXT NOT NULL CHECK(scope IN ('personal','project')),
    project_name TEXT,
    fingerprint TEXT NOT NULL,
    url TEXT NOT NULL,
    source_name TEXT NOT NULL,
    caller_session TEXT,
    log_id TEXT,
    allowance_total INTEGER NOT NULL CHECK(allowance_total>0),
    state TEXT NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','running','blocked','failed','succeeded')),
    revision_id UUID REFERENCES information_revisions(id),
    attempt INTEGER NOT NULL DEFAULT 0,
    token UUID,
    lease_until TIMESTAMPTZ,
    error TEXT,
    pre_gate JSONB,
    post_gate JSONB,
    finish_records JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account,request_id)
);

CREATE TABLE information_event_recipients (
    sequence BIGINT NOT NULL REFERENCES information_events(sequence),
    account TEXT NOT NULL REFERENCES admins(handle),
    PRIMARY KEY(sequence,account)
);
CREATE TABLE information_event_dispatch (
    singleton BOOLEAN PRIMARY KEY CHECK(singleton),
    cursor BIGINT NOT NULL DEFAULT 0,
    token UUID,
    lease_until TIMESTAMPTZ
);
INSERT INTO information_event_dispatch(singleton) VALUES(true);

ALTER TABLE information_evidence ADD COLUMN owner_handle TEXT REFERENCES admins(handle);
ALTER TABLE information_evidence ADD COLUMN request_id UUID;
ALTER TABLE information_evidence ADD CONSTRAINT information_evidence_receipts UNIQUE(owner_handle,request_id);

-- The memory archive has no source-policy context. Corpus-derived records must stay in
-- information reports rather than reappear through implicit lessons or digest navigation.
CREATE FUNCTION information_memory_safe(memory TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT NOT EXISTS(SELECT 1 FROM memory_provenance p WHERE p.memory_id=memory
     AND NOT information_log_readable(p.conversation_id,NULL));
$$;
CREATE FUNCTION information_digest_safe(root TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE tree(id) AS (SELECT root UNION SELECT e.child_id FROM digest_children e JOIN tree t ON e.parent_id=t.id)
 SELECT NOT EXISTS(SELECT 1 FROM tree t JOIN digest_spans s ON s.digest_id=t.id
      WHERE NOT information_log_readable(s.conversation_id,NULL))
 AND NOT EXISTS(SELECT 1 FROM tree t JOIN digest_memories m ON m.digest_id=t.id
      WHERE NOT information_memory_safe(m.memory_id));
$$;

CREATE FUNCTION information_immutable_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW IS DISTINCT FROM OLD THEN
   IF NEW.quote='[deleted]' AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted'
      AND (to_jsonb(NEW)-'quote')=(to_jsonb(OLD)-'quote') THEN RETURN NEW; END IF;
   RAISE EXCEPTION 'retained evidence is immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER information_evidence_immutable BEFORE UPDATE ON information_evidence
 FOR EACH ROW EXECUTE FUNCTION information_immutable_evidence();

CREATE TABLE information_payload_assignments (
 id UUID PRIMARY KEY,
 payload_id TEXT NOT NULL,
 actor_handle TEXT NOT NULL REFERENCES admins(handle),
 owner_handle TEXT REFERENCES admins(handle),
 action TEXT NOT NULL CHECK(action IN ('inspect','release')),
 inputs JSONB NOT NULL,
 reason TEXT NOT NULL CHECK(btrim(reason)<>''),
 recorded_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE information_write_gates (
 account TEXT NOT NULL REFERENCES admins(handle),
 request_id UUID NOT NULL,
 fingerprint TEXT NOT NULL,
 operation TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'reserved' CHECK(state IN ('reserved','approved','completed','blocked')),
 token UUID NOT NULL,
 lease_until TIMESTAMPTZ NOT NULL,
 log_id TEXT,
 pre_gate JSONB,
 post_gate JSONB,
 finish_records JSONB,
 response JSONB,
 error TEXT,
 PRIMARY KEY(account,request_id)
);

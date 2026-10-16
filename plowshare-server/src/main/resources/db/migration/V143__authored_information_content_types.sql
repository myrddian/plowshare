-- Scoped immutable contracts and exact revision bindings; no inferred backfill or byte rewrites.
CREATE TABLE information_content_types (
    id UUID PRIMARY KEY,
    namespace TEXT NOT NULL,
    slug TEXT NOT NULL CHECK (slug ~ '^[a-z]([a-z0-9-]{0,62}[a-z0-9])?$'),
    label TEXT NOT NULL CHECK (length(label) BETWEEN 1 AND 120),
    description TEXT NOT NULL CHECK (length(description) <= 4096),
    created_by TEXT NOT NULL REFERENCES admins(handle),
    control_revision BIGINT NOT NULL DEFAULT 1 CHECK (control_revision > 0),
    next_version INTEGER NOT NULL DEFAULT 1 CHECK (next_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(namespace, slug)
);
CREATE TABLE information_content_type_versions (
    id UUID PRIMARY KEY,
    type_id UUID NOT NULL REFERENCES information_content_types(id),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    schema_definition JSONB NOT NULL CHECK (jsonb_typeof(schema_definition) = 'object'),
    presentation JSONB NOT NULL CHECK (jsonb_typeof(presentation) = 'object'),
    profile TEXT NOT NULL CHECK (profile = 'plowshare-document-metadata/1'),
    definition_hash TEXT NOT NULL CHECK (definition_hash ~ '^[a-f0-9]{64}$'),
    state TEXT NOT NULL DEFAULT 'draft' CHECK (state IN ('draft', 'active', 'deprecated')),
    created_by TEXT NOT NULL REFERENCES admins(handle),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(type_id, ordinal)
);
CREATE UNIQUE INDEX information_content_type_active ON information_content_type_versions(type_id) WHERE state = 'active';
CREATE FUNCTION information_content_type_identity_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.namespace,NEW.slug,NEW.label,NEW.description,NEW.created_by,NEW.created_at)
       IS DISTINCT FROM (OLD.id,OLD.namespace,OLD.slug,OLD.label,OLD.description,OLD.created_by,OLD.created_at) THEN
        RAISE EXCEPTION 'content type identity is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_content_type_identity_immutable BEFORE UPDATE ON information_content_types
FOR EACH ROW EXECUTE FUNCTION information_content_type_identity_immutable();
CREATE FUNCTION information_content_type_version_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.type_id IS DISTINCT FROM OLD.type_id
       OR NEW.ordinal IS DISTINCT FROM OLD.ordinal OR NEW.schema_definition IS DISTINCT FROM OLD.schema_definition
       OR NEW.presentation IS DISTINCT FROM OLD.presentation OR NEW.profile IS DISTINCT FROM OLD.profile
       OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'content type definition is immutable';
    END IF;
    IF NEW.state <> OLD.state AND NOT ((OLD.state='draft' AND NEW.state='active') OR (OLD.state='active' AND NEW.state='deprecated')) THEN
        RAISE EXCEPTION 'invalid content type lifecycle transition';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_content_type_version_immutable BEFORE UPDATE ON information_content_type_versions
FOR EACH ROW EXECUTE FUNCTION information_content_type_version_immutable();
-- Management receipts use information_write_gates; a second effect ledger is not introduced.
CREATE TABLE information_revision_content (
    revision_id UUID PRIMARY KEY REFERENCES information_revisions(id),
    type_version UUID NOT NULL REFERENCES information_content_type_versions(id),
    profile TEXT NOT NULL CHECK (profile = 'plowshare-document-metadata/1'),
    definition_hash TEXT NOT NULL CHECK (definition_hash ~ '^[a-f0-9]{64}$'),
    metadata_hash TEXT NOT NULL CHECK (metadata_hash ~ '^[a-f0-9]{64}$'),
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    redacted BOOLEAN NOT NULL DEFAULT false
);
CREATE FUNCTION information_revision_content_contract() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM information_content_type_versions v
        JOIN information_content_types t ON t.id=v.type_id
        JOIN information_revisions r ON r.id=NEW.revision_id
        JOIN information_resources q ON q.id=r.resource_id
        WHERE v.id=NEW.type_version AND v.profile=NEW.profile AND v.definition_hash=NEW.definition_hash
          AND t.namespace=q.namespace AND r.document_type='document' AND v.state IN ('active','deprecated')) THEN
        RAISE EXCEPTION 'revision content contract mismatch';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_revision_content_contract BEFORE INSERT ON information_revision_content
FOR EACH ROW EXECUTE FUNCTION information_revision_content_contract();
CREATE FUNCTION information_revision_content_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'typed revision bindings cannot be removed'; END IF;
    IF NEW.revision_id IS DISTINCT FROM OLD.revision_id OR NEW.type_version IS DISTINCT FROM OLD.type_version
       OR NEW.profile IS DISTINCT FROM OLD.profile OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash
       OR NEW.metadata_hash IS DISTINCT FROM OLD.metadata_hash
       OR ((NEW.metadata IS DISTINCT FROM OLD.metadata OR NEW.redacted IS DISTINCT FROM OLD.redacted)
           AND NOT (NOT OLD.redacted AND NEW.redacted AND NEW.metadata='{}'::jsonb
               AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted')) THEN
        RAISE EXCEPTION 'typed revision metadata is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_revision_content_immutable BEFORE UPDATE OR DELETE ON information_revision_content
FOR EACH ROW EXECUTE FUNCTION information_revision_content_immutable();

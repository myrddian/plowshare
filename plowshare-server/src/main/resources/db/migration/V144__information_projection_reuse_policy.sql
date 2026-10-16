-- Metadata-only revisions are retained without automatically granting inference authority.
ALTER TABLE information_revisions ADD COLUMN processing_policy TEXT NOT NULL DEFAULT 'automatic'
    CHECK (processing_policy IN ('automatic','explicit_only'));
ALTER TABLE information_revisions ADD COLUMN starting_revision UUID REFERENCES information_revisions(id);
ALTER TABLE information_revisions ADD COLUMN revised_by TEXT REFERENCES admins(handle);
CREATE TABLE information_stage_authorizations (
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    stage TEXT NOT NULL CHECK (stage IN ('extract','derive','embed','summarise','summary_embed','autoTag','tagGroups')),
    actor TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(revision_id,generation,stage)
);
CREATE TABLE information_projection_reuse (
    revision_id UUID NOT NULL REFERENCES information_revisions(id),
    stage TEXT NOT NULL CHECK (stage IN ('extract','derive','embed','summarise','summary_embed','autoTag','tagGroups')),
    origin_revision UUID NOT NULL REFERENCES information_revisions(id),
    origin_generation BIGINT NOT NULL CHECK (origin_generation > 0),
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    manifest_hash TEXT NOT NULL CHECK (manifest_hash ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY(revision_id,stage)
);
CREATE FUNCTION information_processing_provenance_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'processing authorizations and reuse provenance are immutable';
END $$;
CREATE TRIGGER information_stage_authorizations_immutable BEFORE UPDATE OR DELETE ON information_stage_authorizations
FOR EACH ROW EXECUTE FUNCTION information_processing_provenance_immutable();
CREATE TRIGGER information_projection_reuse_immutable BEFORE UPDATE OR DELETE ON information_projection_reuse
FOR EACH ROW EXECUTE FUNCTION information_processing_provenance_immutable();
CREATE FUNCTION information_revision_lineage_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.processing_policy IS DISTINCT FROM OLD.processing_policy
       OR NEW.starting_revision IS DISTINCT FROM OLD.starting_revision
       OR NEW.revised_by IS DISTINCT FROM OLD.revised_by THEN
        RAISE EXCEPTION 'revision processing policy and lineage are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_revision_lineage_immutable BEFORE UPDATE ON information_revisions
FOR EACH ROW EXECUTE FUNCTION information_revision_lineage_immutable();

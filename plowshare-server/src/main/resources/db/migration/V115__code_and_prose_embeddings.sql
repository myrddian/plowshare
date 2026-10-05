-- Additive transition: legacy vectors retain their original columns and width.
-- Their model provenance is unknown; copying them into a named slot would claim
-- a compatibility guarantee the database cannot establish. Rebuild both slots
-- from retained source after configuring their exact embedding spaces.
-- A new passage must also be able to exist before embedding, without a dummy
-- 768-dimensional legacy vector. Existing passage vectors remain unchanged.
ALTER TABLE retrieval_passages ALTER COLUMN embedding DROP NOT NULL;

CREATE TABLE embedding_spaces (
    id text PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
    model_id text NOT NULL CHECK (model_id = btrim(model_id) AND length(model_id) BETWEEN 1 AND 512),
    model_revision text NOT NULL CHECK (model_revision = btrim(model_revision) AND length(model_revision) BETWEEN 1 AND 512),
    dimensions integer NOT NULL CHECK (dimensions BETWEEN 1 AND 16000),
    query_prefix text NOT NULL CHECK (length(query_prefix) <= 16384),
    document_prefix text NOT NULL CHECK (length(document_prefix) <= 16384),
    pooling text NOT NULL CHECK (pooling = btrim(pooling) AND length(pooling) BETWEEN 1 AND 512),
    normalization text NOT NULL CHECK (normalization IN ('none', 'l2')),
    reduction text NOT NULL CHECK (reduction = btrim(reduction) AND length(reduction) BETWEEN 1 AND 512),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Canonical, length-safe serialization distinguishes prefixes and model revisions
-- even when dimensions match. Endpoints and credentials are not model identity.
-- PostgreSQL's built-in SHA-256 needs no additional extension.
CREATE FUNCTION embedding_space_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fingerprint text;
BEGIN
    fingerprint := encode(sha256(convert_to(jsonb_build_array(
        NEW.model_id, NEW.model_revision, NEW.dimensions, NEW.query_prefix,
        NEW.document_prefix, NEW.pooling, NEW.normalization, NEW.reduction)::text, 'UTF8')), 'hex');
    IF NEW.id IS NOT NULL AND NEW.id <> fingerprint THEN
        RAISE EXCEPTION 'embedding space identifier does not match its descriptor' USING ERRCODE = '23514';
    END IF;
    NEW.id := fingerprint;
    RETURN NEW;
END $$;
CREATE TRIGGER embedding_space_identity BEFORE INSERT ON embedding_spaces
    FOR EACH ROW EXECUTE FUNCTION embedding_space_identity();

CREATE FUNCTION embedding_space_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'embedding space descriptors are immutable; register a new space';
END $$;
CREATE TRIGGER embedding_space_immutable BEFORE UPDATE OR DELETE ON embedding_spaces
    FOR EACH ROW EXECUTE FUNCTION embedding_space_immutable();
CREATE TRIGGER embedding_space_no_truncate BEFORE TRUNCATE ON embedding_spaces
    FOR EACH STATEMENT EXECUTE FUNCTION embedding_space_immutable();

-- These are two stable query choices, not one global model or an unbounded pool
-- of retrieval profiles. Active/target pointers are deliberately unset at upgrade.
-- A future durable rebuild activates a slot across all stores in one transaction.
CREATE TABLE embedding_slots (
    slot text PRIMARY KEY CHECK (slot IN ('code', 'prose')),
    active_space_id text REFERENCES embedding_spaces(id),
    target_space_id text REFERENCES embedding_spaces(id),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
INSERT INTO embedding_slots(slot) VALUES ('code'), ('prose');

CREATE FUNCTION embedding_slot_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.slot IS DISTINCT FROM OLD.slot THEN
        RAISE EXCEPTION 'embedding slot identities cannot be removed or renamed';
    END IF;
    -- Callers fence configuration changes with WHERE version = expected_version.
    -- Updating pointers advances the version even if the requested model is equal.
    NEW.version := OLD.version + 1;
    RETURN NEW;
END $$;
CREATE TRIGGER embedding_slot_version BEFORE UPDATE OR DELETE ON embedding_slots
    FOR EACH ROW EXECUTE FUNCTION embedding_slot_version();
CREATE TRIGGER embedding_slot_no_truncate BEFORE TRUNCATE ON embedding_slots
    FOR EACH STATEMENT EXECUTE FUNCTION embedding_space_immutable();

-- Shared validation is confined to persistence. It never supplies a model,
-- coerces a dimension, truncates output, or treats a missing vector as success.
CREATE FUNCTION embedding_validate_pair(space_id text, value vector, source_revision bigint,
    current_revision bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE descriptor embedding_spaces; magnitude double precision;
BEGIN
    IF space_id IS NULL AND value IS NULL AND source_revision IS NULL THEN RETURN; END IF;
    IF space_id IS NULL OR value IS NULL OR source_revision IS NULL THEN
        RAISE EXCEPTION 'embedding vector, space and source revision must be present together' USING ERRCODE = '23514';
    END IF;
    IF current_revision IS NULL OR source_revision < 0 OR source_revision <> current_revision THEN
        RAISE EXCEPTION 'embedding source revision is stale' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO descriptor FROM embedding_spaces WHERE id = space_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'embedding space is not registered' USING ERRCODE = '23503';
    END IF;
    IF vector_dims(value) <> descriptor.dimensions THEN
        RAISE EXCEPTION 'embedding dimensions do not match the registered space' USING ERRCODE = '23514';
    END IF;
    magnitude := vector_norm(value);
    IF magnitude <= 0 OR (descriptor.normalization = 'l2' AND abs(magnitude - 1) > 0.001) THEN
        RAISE EXCEPTION 'embedding vector violates the space normalization contract' USING ERRCODE = '23514';
    END IF;
END $$;

-- The five stores retain their own ownership/foreign keys. Unconstrained vector
-- columns permit different code/prose widths up to pgvector's 16,000-dimensional
-- storage limit; every write checks the descriptor. Storage and ANN index limits
-- are distinct: vector indexes support 2,000 dimensions, halfvec indexes 4,000.
-- Per-space ANN casts/indexes are a repository administration responsibility;
-- no fixed dimension or unsafe caller-generated SQL is shipped here.
-- Tokens come from one sequence, so deletion/recreation of the same source ID
-- cannot make an old pending result current again. Gaps (including rollbacks)
-- are intentional; these are freshness tokens, not counts of source edits.
-- Existing rows retain token zero until their first source edit, avoiding a
-- full-table data rewrite. Newly inserted/recreated rows never receive zero.
CREATE SEQUENCE embedding_source_revisions;
DO $$
DECLARE owner_table text; slot_name text;
BEGIN
    FOREACH owner_table IN ARRAY ARRAY['memories', 'digests', 'chunks', 'documents', 'retrieval_passages'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN embedding_source_revision bigint NOT NULL DEFAULT 0 CHECK (embedding_source_revision >= 0)', owner_table);
        FOREACH slot_name IN ARRAY ARRAY['code', 'prose'] LOOP
            EXECUTE format('ALTER TABLE %I ADD COLUMN %I vector, ADD COLUMN %I text REFERENCES embedding_spaces(id), ADD COLUMN %I bigint',
                owner_table, slot_name || '_embedding', slot_name || '_space_id', slot_name || '_source_revision');
            EXECUTE format('CREATE INDEX %I ON %I (%I) WHERE %I IS NOT NULL',
                owner_table || '_' || slot_name || '_space', owner_table,
                slot_name || '_space_id', slot_name || '_embedding');
        END LOOP;
    END LOOP;
END $$;

CREATE FUNCTION embedding_record_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_changed boolean := false;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.embedding_source_revision <> 0 THEN
            RAISE EXCEPTION 'embedding source revisions are maintained by the source trigger' USING ERRCODE = '23514';
        END IF;
        NEW.embedding_source_revision := nextval('embedding_source_revisions');
    ELSE
        -- Only changes to the owning embedding input advance the token. Slot
        -- publication and unrelated metadata must not invalidate the other slot.
        CASE TG_TABLE_NAME
            WHEN 'memories' THEN source_changed := ROW(NEW.summary, NEW.body, NEW.scope) IS DISTINCT FROM ROW(OLD.summary, OLD.body, OLD.scope);
            WHEN 'digests' THEN source_changed := ROW(NEW.summary, NEW.revision, NEW.stale_at) IS DISTINCT FROM ROW(OLD.summary, OLD.revision, OLD.stale_at);
            WHEN 'chunks' THEN source_changed := ROW(NEW.text, NEW.paragraph_id) IS DISTINCT FROM ROW(OLD.text, OLD.paragraph_id);
            WHEN 'documents' THEN source_changed := ROW(NEW.summary, NEW.text_hash) IS DISTINCT FROM ROW(OLD.summary, OLD.text_hash);
            WHEN 'retrieval_passages' THEN source_changed := ROW(NEW.passage, NEW.source_type, NEW.source_id, NEW.position) IS DISTINCT FROM ROW(OLD.passage, OLD.source_type, OLD.source_id, OLD.position);
            ELSE RAISE EXCEPTION 'unsupported embedding source';
        END CASE;
        IF NEW.embedding_source_revision <> OLD.embedding_source_revision THEN
            RAISE EXCEPTION 'embedding source revisions are maintained by the source trigger' USING ERRCODE = '23514';
        END IF;
        IF source_changed THEN
            -- Source writes and replacement publication are separate operations.
            -- Never silently discard an explicitly supplied replacement vector.
            IF ROW(NEW.code_embedding, NEW.code_space_id, NEW.code_source_revision,
                   NEW.prose_embedding, NEW.prose_space_id, NEW.prose_source_revision)
                IS DISTINCT FROM ROW(OLD.code_embedding, OLD.code_space_id, OLD.code_source_revision,
                                     OLD.prose_embedding, OLD.prose_space_id, OLD.prose_source_revision)
                AND (NEW.code_embedding IS NOT NULL OR NEW.code_space_id IS NOT NULL OR NEW.code_source_revision IS NOT NULL
                     OR NEW.prose_embedding IS NOT NULL OR NEW.prose_space_id IS NOT NULL OR NEW.prose_source_revision IS NOT NULL) THEN
                RAISE EXCEPTION 'commit source changes before publishing replacement embeddings' USING ERRCODE = '23514';
            END IF;
            NEW.embedding_source_revision := nextval('embedding_source_revisions');
            NEW.code_embedding := NULL; NEW.code_space_id := NULL; NEW.code_source_revision := NULL;
            NEW.prose_embedding := NULL; NEW.prose_space_id := NULL; NEW.prose_source_revision := NULL;
        END IF;
    END IF;
    -- A removed document summary cannot retain or acquire a summary embedding.
    IF TG_TABLE_NAME = 'documents' THEN
        IF NEW.summary IS NULL AND (NEW.code_embedding IS NOT NULL OR NEW.prose_embedding IS NOT NULL) THEN
            RAISE EXCEPTION 'a document summary is required for its embedding' USING ERRCODE = '23514';
        END IF;
    END IF;
    PERFORM embedding_validate_pair(NEW.code_space_id, NEW.code_embedding, NEW.code_source_revision, NEW.embedding_source_revision);
    PERFORM embedding_validate_pair(NEW.prose_space_id, NEW.prose_embedding, NEW.prose_source_revision, NEW.embedding_source_revision);
    RETURN NEW;
END $$;
DO $$
DECLARE owner_table text;
BEGIN
    FOREACH owner_table IN ARRAY ARRAY['memories', 'digests', 'chunks', 'documents', 'retrieval_passages'] LOOP
        EXECUTE format('CREATE TRIGGER embedding_record_guard BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION embedding_record_guard()', owner_table);
    END LOOP;
END $$;

-- Staging has actual source foreign keys, never orphanable polymorphic IDs.
-- It contains replacement vectors only; active columns keep serving until an
-- atomic activation. A stale stage is retained for diagnosis and must never be
-- copied to an active column without matching the current source revision.
CREATE TABLE memories_embedding_staging (
    source_id text NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
    slot text NOT NULL REFERENCES embedding_slots(slot),
    space_id text NOT NULL REFERENCES embedding_spaces(id),
    source_revision bigint NOT NULL CHECK (source_revision >= 0),
    embedding vector NOT NULL,
    PRIMARY KEY(source_id, slot, space_id, source_revision)
);
CREATE TABLE digests_embedding_staging (
    source_id text NOT NULL REFERENCES digests(id) ON DELETE CASCADE,
    slot text NOT NULL REFERENCES embedding_slots(slot),
    space_id text NOT NULL REFERENCES embedding_spaces(id),
    source_revision bigint NOT NULL CHECK (source_revision >= 0),
    embedding vector NOT NULL,
    PRIMARY KEY(source_id, slot, space_id, source_revision)
);
CREATE TABLE chunks_embedding_staging (
    source_id uuid NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    slot text NOT NULL REFERENCES embedding_slots(slot),
    space_id text NOT NULL REFERENCES embedding_spaces(id),
    source_revision bigint NOT NULL CHECK (source_revision >= 0),
    embedding vector NOT NULL,
    PRIMARY KEY(source_id, slot, space_id, source_revision)
);
CREATE TABLE documents_embedding_staging (
    source_id uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    slot text NOT NULL REFERENCES embedding_slots(slot),
    space_id text NOT NULL REFERENCES embedding_spaces(id),
    source_revision bigint NOT NULL CHECK (source_revision >= 0),
    embedding vector NOT NULL,
    PRIMARY KEY(source_id, slot, space_id, source_revision)
);
CREATE TABLE retrieval_passages_embedding_staging (
    source_type text NOT NULL,
    source_id text NOT NULL,
    position integer NOT NULL,
    slot text NOT NULL REFERENCES embedding_slots(slot),
    space_id text NOT NULL REFERENCES embedding_spaces(id),
    source_revision bigint NOT NULL CHECK (source_revision >= 0),
    embedding vector NOT NULL,
    PRIMARY KEY(source_type, source_id, position, slot, space_id, source_revision),
    FOREIGN KEY(source_type, source_id, position) REFERENCES retrieval_passages ON DELETE CASCADE
);

CREATE FUNCTION embedding_staging_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target text; current_revision bigint; owner_table text;
BEGIN
    -- Lock order for future activation: slot configuration, then source rows.
    -- The target snapshot cannot change while a staging publication commits.
    SELECT target_space_id INTO target FROM embedding_slots WHERE slot = NEW.slot FOR SHARE;
    IF target IS NULL OR target <> NEW.space_id THEN
        RAISE EXCEPTION 'staged embedding does not match the slot target' USING ERRCODE = '23514';
    END IF;
    CASE TG_TABLE_NAME
        WHEN 'memories_embedding_staging' THEN owner_table := 'memories';
        WHEN 'digests_embedding_staging' THEN owner_table := 'digests';
        WHEN 'chunks_embedding_staging' THEN owner_table := 'chunks';
        WHEN 'documents_embedding_staging' THEN owner_table := 'documents';
        WHEN 'retrieval_passages_embedding_staging' THEN owner_table := 'retrieval_passages';
        ELSE RAISE EXCEPTION 'unsupported embedding staging table';
    END CASE;
    IF owner_table = 'retrieval_passages' THEN
        SELECT embedding_source_revision INTO current_revision FROM retrieval_passages
            WHERE source_type = NEW.source_type AND source_id = NEW.source_id AND position = NEW.position FOR SHARE;
    ELSE
        -- Identifiers are selected only from the fixed table allowlist above.
        EXECUTE format('SELECT embedding_source_revision FROM %I WHERE id = $1 FOR SHARE', owner_table)
            INTO current_revision USING NEW.source_id;
    END IF;
    IF current_revision IS NULL THEN
        RAISE EXCEPTION 'embedding source no longer exists' USING ERRCODE = '23503';
    END IF;
    IF owner_table = 'documents' THEN
        IF (SELECT summary IS NULL FROM documents WHERE id = NEW.source_id) THEN
            RAISE EXCEPTION 'a document summary is required for its embedding' USING ERRCODE = '23514';
        END IF;
    END IF;
    PERFORM embedding_validate_pair(NEW.space_id, NEW.embedding, NEW.source_revision, current_revision);
    RETURN NEW;
END $$;
DO $$
DECLARE staging_table text;
BEGIN
    FOREACH staging_table IN ARRAY ARRAY['memories_embedding_staging', 'digests_embedding_staging', 'chunks_embedding_staging', 'documents_embedding_staging', 'retrieval_passages_embedding_staging'] LOOP
        EXECUTE format('CREATE TRIGGER embedding_staging_guard BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION embedding_staging_guard()', staging_table);
    END LOOP;
END $$;

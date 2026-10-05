-- Permanent runtime metadata, independent of model names, widths or deployment.
ALTER TABLE embedding_slots
    ADD COLUMN active_mode text,
    ADD COLUMN active_distance text,
    ADD COLUMN target_mode text,
    ADD COLUMN target_distance text,
    ADD CHECK (active_mode IS NULL OR active_mode IN ('exact','hnsw_vector','hnsw_half','hnsw_binary','ivfflat_vector','ivfflat_half','ivfflat_binary')),
    ADD CHECK (target_mode IS NULL OR target_mode IN ('exact','hnsw_vector','hnsw_half','hnsw_binary','ivfflat_vector','ivfflat_half','ivfflat_binary')),
    ADD CHECK (active_distance IS NULL OR active_distance IN ('COSINE','L2','INNER_PRODUCT','L1')),
    ADD CHECK (target_distance IS NULL OR target_distance IN ('COSINE','L2','INNER_PRODUCT','L1'));
-- Nullable policies permit an upgrade with unset pointers. Runtime configuration
-- owns pointer/policy transitions together; invalid partial snapshots fail reads.

-- Failures do not erase the committed source or successful work in the other
-- slot. Actual foreign keys clean up retry metadata when a source disappears.
DO $$
DECLARE owner_table text; key_type text;
BEGIN
    FOREACH owner_table IN ARRAY ARRAY['memories','digests','chunks','documents'] LOOP
        key_type := CASE WHEN owner_table IN ('chunks','documents') THEN 'uuid' ELSE 'text' END;
        EXECUTE format('CREATE TABLE %I (
            source_id %s NOT NULL REFERENCES %I(id) ON DELETE CASCADE,
            slot text NOT NULL REFERENCES embedding_slots(slot),
            space_id text NOT NULL REFERENCES embedding_spaces(id),
            source_revision bigint NOT NULL CHECK(source_revision >= 0),
            attempts integer NOT NULL DEFAULT 1 CHECK(attempts > 0),
            category text NOT NULL CHECK(category ~ ''^[A-Za-z][A-Za-z0-9]{0,79}$''),
            retry_at timestamptz NOT NULL,
            PRIMARY KEY(source_id,slot,space_id))',
            owner_table || '_embedding_failures', key_type, owner_table);
    END LOOP;
END $$;
CREATE TABLE retrieval_passages_embedding_failures (
    source_type text NOT NULL,
    source_id text NOT NULL,
    position integer NOT NULL,
    slot text NOT NULL REFERENCES embedding_slots(slot),
    space_id text NOT NULL REFERENCES embedding_spaces(id),
    source_revision bigint NOT NULL CHECK(source_revision >= 0),
    attempts integer NOT NULL DEFAULT 1 CHECK(attempts > 0),
    category text NOT NULL CHECK(category ~ '^[A-Za-z][A-Za-z0-9]{0,79}$'),
    retry_at timestamptz NOT NULL,
    PRIMARY KEY(source_type,source_id,position,slot,space_id),
    FOREIGN KEY(source_type,source_id,position) REFERENCES retrieval_passages ON DELETE CASCADE
);
-- Input allowances are dispatch bounds, not vector identity. Retain the bound
-- for old active spaces while replacement descriptors are configured.
CREATE TABLE embedding_input_limits (
    space_id text PRIMARY KEY REFERENCES embedding_spaces(id),
    max_input_tokens integer NOT NULL CHECK(max_input_tokens > 0)
);

-- New/edited inputs also need serving-generation repair during a rolling rebuild.
-- Slot and source fences are unchanged; unrelated historical spaces remain rejected.
CREATE OR REPLACE FUNCTION embedding_staging_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target text; active text; current_revision bigint; owner_table text;
BEGIN
    -- Lock order for future activation: slot configuration, then source rows.
    -- The target snapshot cannot change while a staging publication commits.
    SELECT target_space_id, active_space_id INTO target, active FROM embedding_slots WHERE slot = NEW.slot FOR SHARE;
    IF NEW.space_id IS DISTINCT FROM target AND NEW.space_id IS DISTINCT FROM active THEN
        RAISE EXCEPTION 'staged embedding does not match the slot active or target space' USING ERRCODE = '23514';
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

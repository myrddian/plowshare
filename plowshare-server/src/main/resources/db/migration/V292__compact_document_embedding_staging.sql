SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

-- Version-0 manifests fingerprint their original staging rows. Preserve those
-- rows while a metadata revision can still resume with the same approval.
CREATE VIEW information_legacy_reuse_projections AS
SELECT DISTINCT (p.payload->'manifest'->>'revision')::uuid AS revision_id
FROM information_reuse_preparations p
JOIN information_write_gates g ON g.account=p.actor AND g.request_id=p.request_id
WHERE g.state IN ('reserved','approved')
  AND coalesce(p.payload->'manifest'->>'snapshotVersion','0')='0'
  AND p.payload->'manifest'->'reusable' ? 'derive';

-- Storage-only duplicates are excluded from new manifests and copies. Stale
-- revisions and different vector values remain distinct retained material.
CREATE VIEW information_document_staging_state AS
SELECT s.*, d.id AS document_id, (
    s.source_revision=d.embedding_source_revision AND
    CASE s.slot
        WHEN 'code' THEN s.space_id=d.code_space_id
            AND s.source_revision=d.code_source_revision AND s.embedding=d.code_embedding
        WHEN 'prose' THEN s.space_id=d.prose_space_id
            AND s.source_revision=d.prose_source_revision AND s.embedding=d.prose_embedding
    END
) IS TRUE AS redundant
FROM documents_embedding_staging s JOIN documents d ON d.id=s.source_id;

CREATE VIEW information_chunk_staging_state AS
SELECT s.*, p.document_id, (
    s.source_revision=c.embedding_source_revision AND
    CASE s.slot
        WHEN 'code' THEN s.space_id=c.code_space_id
            AND s.source_revision=c.code_source_revision AND s.embedding=c.code_embedding
        WHEN 'prose' THEN s.space_id=c.prose_space_id
            AND s.source_revision=c.prose_source_revision AND s.embedding=c.prose_embedding
    END
) IS TRUE AS redundant
FROM chunks_embedding_staging s JOIN chunks c ON c.id=s.source_id
JOIN paragraphs p ON p.id=c.paragraph_id;

-- Block publication, activation and manifest capture before rewriting either
-- staging store. Acquire source locks before the staging locks.
SELECT slot FROM embedding_slots ORDER BY slot FOR UPDATE;
LOCK TABLE documents,chunks IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE documents_embedding_staging,chunks_embedding_staging IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE source record;
DECLARE original_count bigint;
BEGIN
    FOR source IN SELECT * FROM (VALUES
        ('documents_embedding_staging','information_document_staging_state'),
        ('chunks_embedding_staging','information_chunk_staging_state')
    ) AS stores(table_name, view_name)
    LOOP
        EXECUTE format('SELECT count(*) FROM %I', source.table_name) INTO original_count;
        IF original_count=0 THEN CONTINUE; END IF;
        EXECUTE format(
            'CREATE TEMP TABLE retained_information_embeddings ON COMMIT DROP AS
             SELECT s.source_id,s.slot,s.space_id,s.source_revision,s.embedding FROM %I s
             WHERE NOT s.redundant OR EXISTS (
                 SELECT 1 FROM information_legacy_reuse_projections p WHERE p.revision_id=s.document_id
             )', source.view_name);
        IF (SELECT count(*) FROM pg_temp.retained_information_embeddings) < original_count THEN
            -- Existing rows already passed publication checks. Restore their exact
            -- revisions without firing validation or rescheduling retained work.
            EXECUTE format('ALTER TABLE %I DISABLE TRIGGER embedding_staging_guard,
                            DISABLE TRIGGER embedding_repair_progress', source.table_name);
            EXECUTE format('TRUNCATE %I', source.table_name);
            EXECUTE format(
                'INSERT INTO %I(source_id,slot,space_id,source_revision,embedding)
                 SELECT source_id,slot,space_id,source_revision,embedding
                 FROM pg_temp.retained_information_embeddings', source.table_name);
            EXECUTE format('ALTER TABLE %I ENABLE TRIGGER embedding_staging_guard,
                            ENABLE TRIGGER embedding_repair_progress', source.table_name);
        END IF;
        DROP TABLE pg_temp.retained_information_embeddings;
    END LOOP;
END $$;

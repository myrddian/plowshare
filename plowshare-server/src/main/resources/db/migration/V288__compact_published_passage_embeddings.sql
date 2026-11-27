-- Reclaim published passage staging on disk, including its TOAST relation and indexes.
-- Preserve pending, stale and differing vectors exactly as previously retained.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

-- Use the same slot-before-source order as publication, including the row locks
-- needed by the restore's foreign keys.
SELECT slot FROM embedding_slots ORDER BY slot FOR SHARE;
LOCK TABLE retrieval_passages IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE retrieval_passages_embedding_staging IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM retrieval_passages_embedding_staging) THEN
        RETURN;
    END IF;

    CREATE TEMP TABLE retained_passage_embeddings ON COMMIT DROP AS
    SELECT s.source_type, s.source_id, s.position, s.slot, s.space_id, s.source_revision, s.embedding
    FROM retrieval_passages_embedding_staging s
    JOIN retrieval_passages p USING (source_type, source_id, position)
    WHERE (
        s.source_revision = p.embedding_source_revision
        AND CASE s.slot
            WHEN 'code' THEN s.space_id = p.code_space_id
                AND s.source_revision = p.code_source_revision AND s.embedding = p.code_embedding
            WHEN 'prose' THEN s.space_id = p.prose_space_id
                AND s.source_revision = p.prose_source_revision AND s.embedding = p.prose_embedding
        END
    ) IS NOT TRUE;

    IF (SELECT count(*) FROM retained_passage_embeddings) =
       (SELECT count(*) FROM retrieval_passages_embedding_staging) THEN
        RETURN;
    END IF;

    -- The original rows already passed insertion checks. Restoring them without user
    -- triggers allows historical rows to keep their old revisions and leaves queued work
    -- untouched. Foreign keys and constraints still apply. The rewrite, trigger settings,
    -- and restore all roll back together on failure.
    ALTER TABLE retrieval_passages_embedding_staging
        DISABLE TRIGGER embedding_staging_guard,
        DISABLE TRIGGER embedding_repair_progress;
    TRUNCATE retrieval_passages_embedding_staging;
    INSERT INTO retrieval_passages_embedding_staging
        (source_type, source_id, position, slot, space_id, source_revision, embedding)
    SELECT source_type, source_id, position, slot, space_id, source_revision, embedding
    FROM retained_passage_embeddings;
    ALTER TABLE retrieval_passages_embedding_staging
        ENABLE TRIGGER embedding_staging_guard,
        ENABLE TRIGGER embedding_repair_progress;
END
$$;

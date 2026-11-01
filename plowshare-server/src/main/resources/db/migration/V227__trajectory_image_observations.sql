-- Typed provider content is metadata only. Original and effective content stay distinguishable;
-- ejection removes both in the same transaction as text payload ejection.
ALTER TABLE entries ADD COLUMN tool_observation JSONB;
ALTER TABLE entries ADD CONSTRAINT entries_tool_observation_kind CHECK (
    tool_observation IS NULL OR (kind='tool_result' AND ejected_at IS NULL
      AND jsonb_typeof(tool_observation)='object'));

-- The entry, not Relay or the invocation, owns this independent reference and blob claim.
-- No FK to the invocation: its retention must not delete Trajectory's evidence.
CREATE TABLE trajectory_image_references (
    conversation_id TEXT NOT NULL,
    entry_ordinal INTEGER NOT NULL,
    slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 15),
    image_id TEXT NOT NULL CHECK(image_id ~ '^img_[0-9a-f]{32}$'),
    blob_scope TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    byte_length BIGINT NOT NULL CHECK(byte_length BETWEEN 1 AND 2147483647),
    observe BOOLEAN NOT NULL,
    PRIMARY KEY(conversation_id,entry_ordinal,slot),
    FOREIGN KEY(conversation_id,entry_ordinal) REFERENCES entries(conversation_id,ordinal) ON DELETE CASCADE,
    FOREIGN KEY(blob_scope,sha256) REFERENCES binary_blobs(scope,sha256),
    CHECK(image_id='img_' || substring(sha256 FROM 1 FOR 32))
);

CREATE FUNCTION release_trajectory_blob_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM binary_blobs WHERE scope=OLD.blob_scope AND sha256=OLD.sha256 FOR UPDATE;
    DELETE FROM binary_blob_claims WHERE scope=OLD.blob_scope AND sha256=OLD.sha256
      AND kind='trajectory-entry' AND owner=OLD.conversation_id || ':' || OLD.entry_ordinal
      AND slot=OLD.slot AND retention='RELEASABLE';
    UPDATE binary_blobs SET unclaimed_at=clock_timestamp()
      WHERE scope=OLD.blob_scope AND sha256=OLD.sha256;
    RETURN OLD;
END;
$$;
CREATE TRIGGER trajectory_blob_claim_release AFTER DELETE ON trajectory_image_references
    FOR EACH ROW EXECUTE FUNCTION release_trajectory_blob_claim();

CREATE FUNCTION eject_trajectory_images() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ejected_at IS NULL AND NEW.ejected_at IS NOT NULL THEN
        DELETE FROM trajectory_image_references
          WHERE conversation_id=OLD.conversation_id AND entry_ordinal=OLD.ordinal;
        NEW.tool_observation := NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trajectory_image_ejection BEFORE UPDATE OF ejected_at ON entries
    FOR EACH ROW EXECUTE FUNCTION eject_trajectory_images();

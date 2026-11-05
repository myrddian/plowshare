ALTER TABLE relay_topics DROP CONSTRAINT relay_topic_payload_family;
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_payload_family CHECK (
    payload_kind IN ('EMPTY','TEXT','BLOB','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED'));
ALTER TABLE relay_topics ADD CONSTRAINT relay_blob_project_scope
    CHECK (payload_kind <> 'BLOB' OR scope_key <> 'system');

-- One owning-record link; publication append and claim acquisition share a transaction.
CREATE TABLE relay_blob_publications (
    scope_key TEXT NOT NULL,
    topic TEXT NOT NULL,
    position BIGINT NOT NULL,
    blob_scope TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    claim_owner TEXT NOT NULL CHECK (claim_owner ~ '^[0-9a-f]{64}:[1-9][0-9]{0,18}$'),
    PRIMARY KEY (scope_key,topic,position),
    FOREIGN KEY (scope_key,topic,position)
        REFERENCES relay_publications(scope_key,topic,position) ON DELETE CASCADE,
    FOREIGN KEY (blob_scope,sha256) REFERENCES binary_blobs(scope,sha256),
    CHECK (scope_key ~ '^project:[1-9][0-9]{0,18}$'),
    CHECK (blob_scope = 'projects/' || substring(scope_key FROM 9))
);

CREATE FUNCTION release_relay_blob_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM binary_blobs WHERE scope=OLD.blob_scope AND sha256=OLD.sha256 FOR UPDATE;
    DELETE FROM binary_blob_claims WHERE scope=OLD.blob_scope AND sha256=OLD.sha256
        AND kind='relay-publication' AND owner=OLD.claim_owner AND slot=0 AND retention='RELEASABLE';
    UPDATE binary_blobs SET unclaimed_at=clock_timestamp()
        WHERE scope=OLD.blob_scope AND sha256=OLD.sha256;
    RETURN OLD;
END;
$$;
CREATE TRIGGER relay_blob_claim_release AFTER DELETE ON relay_blob_publications
    FOR EACH ROW EXECUTE FUNCTION release_relay_blob_claim();

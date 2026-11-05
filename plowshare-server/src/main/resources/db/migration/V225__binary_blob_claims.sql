-- Byte storage is scoped independently of live project rows: deleting a project must not
-- discard the location/tombstone needed to finish disk cleanup. No image bytes or metadata here.
CREATE TABLE binary_blobs (
    scope TEXT NOT NULL CHECK (scope ~ '^(global|projects/[1-9][0-9]{0,18})$'),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    file TEXT NOT NULL CHECK (length(file) BETWEEN 1 AND 1024),
    byte_length BIGINT NOT NULL CHECK (byte_length BETWEEN 1 AND 2147483647),
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    state TEXT NOT NULL CHECK (state IN ('READY','DELETING','DELETED')),
    unclaimed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope,sha256),
    UNIQUE (file)
);
CREATE INDEX binary_blobs_collection ON binary_blobs(state,unclaimed_at,scope,sha256)
    WHERE state IN ('READY','DELETING');

CREATE TABLE binary_blob_aliases (
    scope TEXT NOT NULL,
    alias TEXT NOT NULL CHECK (length(alias) BETWEEN 1 AND 256 AND alias ~ '^[A-Za-z0-9_.:-]+$'),
    sha256 TEXT NOT NULL,
    PRIMARY KEY (scope,alias),
    FOREIGN KEY (scope,sha256) REFERENCES binary_blobs(scope,sha256)
);

CREATE TABLE binary_blob_claims (
    scope TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind ~ '^[a-z][a-z0-9._-]{0,63}$'),
    owner TEXT NOT NULL CHECK (length(owner) BETWEEN 1 AND 256 AND owner ~ '^[A-Za-z0-9_.:-]+$'),
    slot INTEGER NOT NULL CHECK (slot BETWEEN 0 AND 65535),
    sha256 TEXT NOT NULL,
    retention TEXT NOT NULL CHECK (retention IN ('PINNED','RELEASABLE')),
    PRIMARY KEY (scope,kind,owner,slot),
    FOREIGN KEY (scope,sha256) REFERENCES binary_blobs(scope,sha256)
);
CREATE INDEX binary_blob_claims_object ON binary_blob_claims(scope,sha256);

-- Admission rows already cascade from invocation/project deletion. Release the exact owning
-- slot in that same transaction, with the same blob-row lock used by acquire and collection.
CREATE FUNCTION release_invocation_blob_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM binary_blobs b JOIN binary_blob_claims c
        ON c.scope=b.scope AND c.sha256=b.sha256
        WHERE c.kind='invocation-admission' AND c.owner=OLD.invocation_id::text
          AND c.slot=OLD.ordinal
        ORDER BY b.scope,b.sha256 FOR UPDATE OF b;
    UPDATE binary_blobs b SET unclaimed_at=clock_timestamp()
        FROM binary_blob_claims c
        WHERE c.scope=b.scope AND c.sha256=b.sha256
          AND c.kind='invocation-admission' AND c.owner=OLD.invocation_id::text
          AND c.slot=OLD.ordinal;
    DELETE FROM binary_blob_claims
        WHERE kind='invocation-admission' AND owner=OLD.invocation_id::text
          AND slot=OLD.ordinal AND retention='RELEASABLE';
    RETURN OLD;
END;
$$;
CREATE TRIGGER invocation_blob_claim_release AFTER DELETE ON invocation_image_admissions
    FOR EACH ROW EXECUTE FUNCTION release_invocation_blob_claim();

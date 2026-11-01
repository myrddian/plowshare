-- Invocation-owned holds, not a second execution ledger or a duplicate image catalogue.
-- Original bytes and display metadata remain in ImageStore. Removing a project/invocation removes
-- its logical holds; it never deletes shared/legacy image bytes from disk.
CREATE TABLE invocation_image_admissions (
    invocation_id UUID NOT NULL REFERENCES relay_tool_invocations(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 15),
    request_id UUID NOT NULL UNIQUE,
    image_id TEXT NOT NULL CHECK (image_id ~ '^img_[0-9a-f]{32}$'),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 32768),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 32768),
    byte_length BIGINT NOT NULL CHECK (byte_length BETWEEN 1 AND 2147483647),
    admitted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (invocation_id, ordinal),
    CHECK (image_id = 'img_' || substring(sha256 FROM 1 FOR 32)),
    CHECK (width::bigint * height <= 64000000)
);

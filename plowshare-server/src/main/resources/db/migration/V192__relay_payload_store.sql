-- One content-addressed copy of each Relay payload. Publications and admissions will reference
-- it by digest instead of each carrying their own copy of the bytes. Purely additive: nothing
-- references this table yet.
--
-- The payload bounds are the ones V135 placed on relay_publications and relay_admissions:
-- 50 MiB of text and a 300 MiB encoded envelope, enforced once per stored byte.
CREATE TABLE relay_payloads (
    digest TEXT PRIMARY KEY CHECK (digest ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (
        jsonb_typeof(payload) = 'object'
        AND octet_length(convert_to(payload::text, 'UTF8')) <= 314573824
        AND (NOT (payload ? 'text') OR (
            jsonb_typeof(payload->'text') = 'string'
            AND octet_length(convert_to(payload->>'text', 'UTF8')) <= 52428800
        ))
    ),
    byte_length BIGINT NOT NULL CHECK (byte_length > 0),
    ejection_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX relay_payloads_ejection ON relay_payloads(ejection_at);

COMMENT ON TABLE relay_payloads IS
    'ejection_at defers the next examination. It never authorizes a deletion. Reachability is the only authority. A date that is too early costs one wasted check; a date that is too late leaves bytes for one more window. Neither can remove something referenced.';

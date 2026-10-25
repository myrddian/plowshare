-- Publications and admissions reference their payload by digest in relay_payloads (V192) instead
-- of each carrying a copy. This migration only adds the reference and populates it; the payload
-- column stays, nullable semantics unchanged, until readers have moved to the digest. New rows are
-- written with both, so every reader keeps working while the cutover is staged.
--
-- Existing rows are rewritten rather than discarded, as V189 did for the same two tables. A
-- topic's publications are a live retention window that subscribers hold durable positions into,
-- and an admission is input already handed to a subscriber. Dropping them to start with a clean
-- reference would turn a storage change into unacknowledged retention loss.
--
-- The digest is SHA-256 over the UTF-8 text of the stored JSONB, lowercase hex. That is the same
-- function RelayPort.hash applies to a string, but over PostgreSQL's normalized rendering (sorted
-- keys, canonical spacing) rather than the application's serialization, so a backfilled row can
-- hold a different digest from a freshly stored payload with equal content. That costs a missed
-- deduplication only; each digest still names exactly the bytes stored under it.

ALTER TABLE relay_publications
    ADD COLUMN payload_digest TEXT REFERENCES relay_payloads(digest);
ALTER TABLE relay_admissions
    ADD COLUMN payload_digest TEXT REFERENCES relay_payloads(digest);

-- One row per distinct payload, whichever table it came from. ejection_at is now() so the first
-- sweep examines these rows; every one is referenced by the backfill below and survives.
INSERT INTO relay_payloads(digest, payload, byte_length, ejection_at)
SELECT DISTINCT ON (digest) digest, payload, octet_length(payload::text), now()
FROM (
    SELECT encode(sha256(convert_to(payload::text, 'UTF8')), 'hex') AS digest, payload
    FROM relay_publications
    UNION ALL
    SELECT encode(sha256(convert_to(payload::text, 'UTF8')), 'hex'), payload
    FROM relay_admissions
) AS held
ORDER BY digest
ON CONFLICT (digest) DO NOTHING;

UPDATE relay_publications
SET payload_digest = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex');

UPDATE relay_admissions
SET payload_digest = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex');

-- These serve the sweep's reachability check and the foreign key's delete-side lookups.
CREATE INDEX relay_publications_payload_digest ON relay_publications(payload_digest);
CREATE INDEX relay_admissions_payload_digest ON relay_admissions(payload_digest);

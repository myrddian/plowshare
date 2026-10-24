-- The readers now take their payload bytes from relay_payloads (V191) through the payload_digest
-- reference V192 added, so the copies these two tables carried have no remaining reader. This
-- migration finishes the reference and removes the copies. It ships in the same commit as the
-- reader change: a server reading the old column against this schema would fail at runtime, and a
-- server writing the old column against it would fail on the insert, so the two cannot be
-- separated.
--
-- The backfill below repeats V192's for anything written without a digest. V192 left the column
-- nullable precisely so a server that predates the digest could keep appending during a rolling
-- deploy; such a row is the only way a null survives to here. Its bytes may not yet have a
-- relay_payloads row either, so the payload is stored before the reference is set, in the same
-- order and with the same digest function V192 used: SHA-256 over the UTF-8 text of PostgreSQL's
-- JSONB rendering, lowercase hex. ejection_at is now(), so the first sweep examines these rows and
-- finds every one of them referenced.
--
-- The lock is taken first, and for the same reason the backfill exists. A server predating the
-- digest can still be appending when this migration starts; without the lock it could commit a
-- null-digest row between the backfill and SET NOT NULL, which would then fail. The failure is a
-- cleanly aborted migration rather than data loss, but the window is avoidable. DROP COLUMN needs
-- ACCESS EXCLUSIVE on both tables regardless, so taking it up front only moves the wait earlier
-- and makes the whole migration one atomic step against a quiet table.
LOCK TABLE relay_publications, relay_admissions IN ACCESS EXCLUSIVE MODE;

INSERT INTO relay_payloads(digest, payload, byte_length, ejection_at)
SELECT DISTINCT ON (digest) digest, payload, octet_length(payload::text), now()
FROM (
    SELECT encode(sha256(convert_to(payload::text, 'UTF8')), 'hex') AS digest, payload
    FROM relay_publications
    WHERE payload_digest IS NULL
    UNION ALL
    SELECT encode(sha256(convert_to(payload::text, 'UTF8')), 'hex'), payload
    FROM relay_admissions
    WHERE payload_digest IS NULL
) AS held
ORDER BY digest
ON CONFLICT (digest) DO NOTHING;

UPDATE relay_publications
SET payload_digest = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
WHERE payload_digest IS NULL;

UPDATE relay_admissions
SET payload_digest = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
WHERE payload_digest IS NULL;

-- Every publication and admission now has payload bytes exactly once, named by this reference. The
-- foreign key already guarantees the named row exists; NOT NULL makes the reference itself the
-- row's only way to hold a payload, so a row can no longer exist with no bytes behind it.
ALTER TABLE relay_publications
    ALTER COLUMN payload_digest SET NOT NULL;
ALTER TABLE relay_admissions
    ALTER COLUMN payload_digest SET NOT NULL;

-- Dropping the column takes the V135 payload CHECK on each table with it. That is the intent: the
-- same bounds -- a 300 MiB encoded envelope and 50 MiB of TEXT content -- are enforced once per
-- stored byte by the CHECK V191 placed on relay_payloads, which this migration leaves untouched.
ALTER TABLE relay_publications
    DROP COLUMN payload;
ALTER TABLE relay_admissions
    DROP COLUMN payload;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

-- Match the old jsonb_strip_nulls equality contract. PostgreSQL renders JSONB
-- objects in key order, but can retain the scale of a numeric value. Copy quoted
-- strings verbatim and trim only insignificant zeroes in decimal literals.
-- This encoding is part of the receipt format and must remain stable.
CREATE FUNCTION inference_event_digest_v1(event jsonb) RETURNS bytea
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
RETURN sha256(convert_to(regexp_replace(
    jsonb_strip_nulls(event)::text COLLATE "C",
    $pattern$("(?:[^"\\]|\\.)*")|(-?[0-9]+)(?:(\.[0-9]*[1-9])0*|\.0+)(?=[,}\] ]|$)$pattern$,
    $replacement$\1\2\3$replacement$,
    'g'
), 'UTF8'));

-- ALTER TYPE rewrites the relation and rebuilds its existing indexes atomically.
-- Keeping every identity also covers a retry that is appended under a new sequence.
ALTER TABLE inference_accounting_events
    ALTER COLUMN event_data TYPE bytea USING inference_event_digest_v1(event_data);
ALTER TABLE inference_accounting_events RENAME COLUMN event_data TO event_digest;
ALTER TABLE inference_accounting_events
    ADD CONSTRAINT inference_event_digest_width CHECK (octet_length(event_digest) = 32);

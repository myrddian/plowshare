-- Portable Relay TEXT ceiling is 50 MiB of raw UTF-8 content.
-- Deployment admission defaults to 5 MiB and is enforced on new repository appends.
-- A control byte can expand to six JSON bytes. Keep a bounded encoded envelope too;
-- both the publication and its retained admission copy enforce the same contract.
ALTER TABLE relay_publications DROP CONSTRAINT relay_publications_payload_check;
ALTER TABLE relay_publications ADD CONSTRAINT relay_publications_payload_check CHECK (
    jsonb_typeof(payload) = 'object'
    AND octet_length(convert_to(payload::text, 'UTF8')) <= 314573824
    AND (NOT (payload ? 'text') OR (
        jsonb_typeof(payload->'text') = 'string'
        AND octet_length(convert_to(payload->>'text', 'UTF8')) <= 52428800
    ))
);

ALTER TABLE relay_admissions DROP CONSTRAINT relay_admissions_payload_check;
ALTER TABLE relay_admissions ADD CONSTRAINT relay_admissions_payload_check CHECK (
    jsonb_typeof(payload) = 'object'
    AND octet_length(convert_to(payload::text, 'UTF8')) <= 314573824
    AND (NOT (payload ? 'text') OR (
        jsonb_typeof(payload->'text') = 'string'
        AND octet_length(convert_to(payload->>'text', 'UTF8')) <= 52428800
    ))
);

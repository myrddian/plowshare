-- V192's CHECK re-renders/detoasts the whole payload even when cleanup only updates ejection_at.
-- Keep precisely the same validation at every byte-writing boundary. PostgreSQL runs this trigger
-- on INSERT and any UPDATE naming payload (even SET payload=payload), but not metadata-only updates.
-- Existing rows already passed the CHECK; no bytes or examination dates are rewritten here.
CREATE FUNCTION relay_payload_validate_bytes() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT (
        jsonb_typeof(NEW.payload) = 'object'
        AND octet_length(convert_to(NEW.payload::text, 'UTF8')) <= 314573824
        AND (NOT (NEW.payload ? 'text') OR (
            jsonb_typeof(NEW.payload->'text') = 'string'
            AND octet_length(convert_to(NEW.payload->>'text', 'UTF8')) <= 52428800
        ))
    ) THEN
        RAISE EXCEPTION 'Relay payload violates byte/type bounds'
            USING ERRCODE = '23514', CONSTRAINT = 'relay_payloads_payload_check';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER relay_payloads_validate_bytes
BEFORE INSERT OR UPDATE OF payload ON relay_payloads
FOR EACH ROW EXECUTE FUNCTION relay_payload_validate_bytes();

-- The NOT NULL, digest format, byte_length and referencing foreign key constraints remain intact.
ALTER TABLE relay_payloads DROP CONSTRAINT relay_payloads_payload_check;

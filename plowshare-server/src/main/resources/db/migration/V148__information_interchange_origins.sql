-- Foreign provenance is data, never authority. Deletion redacts duplicate metadata/evidence too.
CREATE TABLE information_import_origins (
    revision_id UUID PRIMARY KEY REFERENCES information_revisions(id),
    origin JSONB NOT NULL CHECK (jsonb_typeof(origin)='object'),
    redacted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE FUNCTION information_import_origin_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NOT (
        NEW.revision_id=OLD.revision_id AND NOT OLD.redacted AND NEW.redacted
        AND NEW.origin='{}'::jsonb
        AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted'
    ) THEN RAISE EXCEPTION 'import origins are immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_import_origin_immutable BEFORE UPDATE OR DELETE ON information_import_origins
FOR EACH ROW EXECUTE FUNCTION information_import_origin_immutable();
CREATE FUNCTION information_import_origin_redact() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.availability='deleted' AND OLD.availability<>'deleted' THEN
        UPDATE information_import_origins SET origin='{}'::jsonb,redacted=TRUE WHERE revision_id=NEW.id AND NOT redacted;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_import_origin_redact AFTER UPDATE OF availability ON information_revisions
FOR EACH ROW EXECUTE FUNCTION information_import_origin_redact();

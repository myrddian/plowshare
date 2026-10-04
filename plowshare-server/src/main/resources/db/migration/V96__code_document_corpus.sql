-- Code is an explicit document type; existing corpus rows retain their original interpretation.
ALTER TABLE information_revisions ADD COLUMN document_type TEXT NOT NULL DEFAULT 'document'
    CHECK (document_type IN ('document', 'code'));
ALTER TABLE information_revisions ADD COLUMN document_subtype TEXT NOT NULL DEFAULT 'text'
    CHECK (document_subtype ~ '^[a-z][a-z0-9_]*$');
ALTER TABLE documents ADD COLUMN document_type TEXT NOT NULL DEFAULT 'document'
    CHECK (document_type IN ('document', 'code'));
ALTER TABLE documents ADD COLUMN document_subtype TEXT NOT NULL DEFAULT 'text'
    CHECK (document_subtype ~ '^[a-z][a-z0-9_]*$');
CREATE INDEX information_revisions_by_document_type ON information_revisions(document_type, created_at, id);
CREATE INDEX documents_by_document_type ON documents(document_type, ingested_at, id);

CREATE FUNCTION information_document_type_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.document_type IS DISTINCT FROM OLD.document_type OR NEW.document_subtype IS DISTINCT FROM OLD.document_subtype THEN
        RAISE EXCEPTION 'information revision document type is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER information_document_type_immutable BEFORE UPDATE ON information_revisions
    FOR EACH ROW EXECUTE FUNCTION information_document_type_immutable();

ALTER TABLE information_acquisitions ADD COLUMN corpus TEXT NOT NULL DEFAULT 'documents'
    CHECK (corpus IN ('documents', 'code'));

-- Exact UTF-16 coordinates in the retained extraction, including repeated identical source passages.
CREATE TABLE code_passages (
    chunk_id UUID PRIMARY KEY REFERENCES chunks(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset)
);

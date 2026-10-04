-- Current paths are disposable projections. Retained revisions keep their existing lifecycle.
ALTER TABLE information_revisions DROP CONSTRAINT information_revisions_byte_size_check;
ALTER TABLE information_revisions ADD CONSTRAINT information_revisions_byte_size_check
    CHECK (byte_size > 0 OR (byte_size = 0 AND document_type = 'code'));
ALTER TABLE documents DROP CONSTRAINT documents_has_content;
ALTER TABLE documents ADD CONSTRAINT documents_has_content
    CHECK (byte_size > 0 OR (byte_size = 0 AND document_type = 'code'));
ALTER TABLE code_workspace_sources ADD COLUMN revision_id UUID REFERENCES information_revisions(id) ON DELETE SET NULL;
CREATE TABLE code_workspace_indexes (
    workspace_id TEXT NOT NULL REFERENCES code_workspaces(id) ON DELETE CASCADE,
    source_key TEXT NOT NULL,
    source_hash TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    revision_id UUID NOT NULL REFERENCES information_revisions(id) ON DELETE CASCADE,
    PRIMARY KEY (workspace_id, source_key, source_hash, parser_version)
);
CREATE INDEX code_workspace_indexes_revision ON code_workspace_indexes(revision_id);

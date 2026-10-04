-- Bibliographic attribution belongs to a retained revision, separately from its owner.
-- Unattributed existing revisions use the owner account without any new paid work.
ALTER TABLE information_revisions ADD COLUMN document_author TEXT;
ALTER TABLE information_revisions ADD COLUMN document_author_source TEXT;
ALTER TABLE information_revisions ADD COLUMN document_author_evidence TEXT;
ALTER TABLE information_revisions ADD CONSTRAINT information_document_author_valid CHECK (
    (document_author IS NULL AND document_author_source IS NULL AND document_author_evidence IS NULL)
    OR (document_author IS NOT NULL AND length(btrim(document_author)) BETWEEN 1 AND 256
        AND document_author_source IS NOT NULL AND document_author_source IN ('person','organisation')
        AND document_author_evidence IS NOT NULL AND length(btrim(document_author_evidence)) BETWEEN 1 AND 512)
);
CREATE INDEX information_revisions_document_author ON information_revisions(document_author);

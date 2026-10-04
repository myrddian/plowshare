-- Disposable, revision-backed syntax projections; retained source remains authoritative.
CREATE TABLE code_outlines (
    document_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    source_hash TEXT NOT NULL,
    language TEXT NOT NULL,
    parser_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ready','partial','unsupported','limited','failed')),
    reason TEXT,
    symbol_count INTEGER NOT NULL CHECK (symbol_count >= 0)
);
CREATE TABLE code_symbols (
    document_id UUID NOT NULL REFERENCES code_outlines(document_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    parent_ordinal INTEGER,
    signature TEXT NOT NULL,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    start_line INTEGER NOT NULL CHECK (start_line > 0),
    end_line INTEGER NOT NULL CHECK (end_line >= start_line),
    PRIMARY KEY (document_id, ordinal),
    FOREIGN KEY (document_id, parent_ordinal) REFERENCES code_symbols(document_id, ordinal)
);
CREATE INDEX code_symbols_by_name ON code_symbols(lower(name) text_pattern_ops, document_id, ordinal);

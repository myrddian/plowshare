ALTER TABLE projects ADD COLUMN project_type TEXT NOT NULL DEFAULT 'STANDARD'
    CHECK (project_type IN ('STANDARD', 'MANAGED', 'DISJOINT'));
ALTER TABLE projects ADD COLUMN write_paths TEXT[] NOT NULL DEFAULT ARRAY['.']::TEXT[];
ALTER TABLE projects ADD CONSTRAINT disjoint_never_union CHECK (project_type <> 'DISJOINT' OR union_since IS NULL);

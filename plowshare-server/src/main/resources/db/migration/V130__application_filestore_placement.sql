-- Null preserves the explicitly legacy source-directory/write_paths contract.
-- Alias-based grants are separate from source lifecycle and ordinary account membership.
ALTER TABLE projects ADD COLUMN application_storage JSONB;
ALTER TABLE projects ADD CONSTRAINT projects_application_storage_shape CHECK (
  application_storage IS NULL OR (
    machine IS NULL AND project_type IN ('MANAGED', 'DISJOINT')
    AND application_boundary
    AND jsonb_typeof(application_storage) = 'object'
    AND application_storage ? 'applicationRoot'
    AND application_storage ? 'writableAreas'
    AND jsonb_typeof(application_storage -> 'applicationRoot') = 'object'
    AND jsonb_typeof(application_storage -> 'writableAreas') = 'array'
  )
);

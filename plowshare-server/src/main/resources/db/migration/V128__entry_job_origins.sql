-- Capture the actual owning job before runtime work begins. Existing entries are deliberately
-- left unknown: timestamps and a conversation's first cause cannot identify a later run.
-- Resuming a turn can submit a different job with the same turn ordinal, so the link belongs
-- to entries rather than the turn summary. It is an identity link, not ownership or permission to read the referenced job.
ALTER TABLE entries ADD COLUMN job_id TEXT;
ALTER TABLE entries ADD CONSTRAINT entries_job_id_named
  CHECK (job_id IS NULL OR (length(job_id) BETWEEN 1 AND 256 AND job_id = btrim(job_id)));
CREATE INDEX entries_job_id ON entries(job_id) WHERE job_id IS NOT NULL;

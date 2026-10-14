-- Retain the exact validated typing candidate through fetch leases and retries.
ALTER TABLE information_acquisitions ADD COLUMN content_intent JSONB
    CHECK (content_intent IS NULL OR jsonb_typeof(content_intent)='object');

-- The broker's retention window is not an execution idempotency window. Keep call receipts
-- independently of topics/publications, so cleanup cannot make a paid command publish again.
CREATE TABLE relay_execution_publications (
  id UUID PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  account TEXT NOT NULL,
  agent TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  turn_ordinal BIGINT NOT NULL CHECK (turn_ordinal > 0),
  step_ordinal BIGINT NOT NULL CHECK (step_ordinal > 0),
  call_id TEXT NOT NULL,
  topic TEXT NOT NULL,
  fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  position BIGINT CHECK (position > 0),
  published_at TIMESTAMPTZ,
  CHECK ((position IS NULL) = (published_at IS NULL))
);

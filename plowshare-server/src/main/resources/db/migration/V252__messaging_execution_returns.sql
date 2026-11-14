-- A granted execution export and its native requester are one transaction with the broker append.
-- Native receipts outlive broker retention; no adapter-supplied instance or conversation is stored.
CREATE TABLE message_execution_exports (
  source_event UUID PRIMARY KEY REFERENCES relay_execution_publications(id) ON DELETE CASCADE,
  project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  project TEXT NOT NULL,
  request_topic TEXT NOT NULL,
  channel TEXT NOT NULL,
  return_topic TEXT NOT NULL,
  return_generation UUID NOT NULL,
  adapter_account TEXT NOT NULL REFERENCES admins(handle),
  request_message TEXT NOT NULL UNIQUE REFERENCES message_records(id) ON DELETE CASCADE,
  requester TEXT NOT NULL REFERENCES message_instances(id),
  external_participant TEXT NOT NULL REFERENCES message_instances(id),
  account TEXT NOT NULL REFERENCES admins(handle),
  relay_causation JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX message_execution_exports_requester ON message_execution_exports(requester);

CREATE TABLE message_execution_returns (
  request_id UUID PRIMARY KEY,
  source_event UUID NOT NULL REFERENCES message_execution_exports(source_event) ON DELETE CASCADE,
  project_id BIGINT NOT NULL,
  project TEXT NOT NULL,
  channel TEXT NOT NULL,
  return_topic TEXT NOT NULL,
  adapter_account TEXT NOT NULL,
  fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  message TEXT NOT NULL UNIQUE REFERENCES message_records(id) ON DELETE CASCADE,
  relay_causation JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

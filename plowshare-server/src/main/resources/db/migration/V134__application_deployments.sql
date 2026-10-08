-- Source revisions are retained independently of the active project placement.
-- A receipt and placement switch commit together; staged filesystem orphans never become active.
CREATE TABLE application_releases (
  revision UUID PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES projects(id),
  digest TEXT NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
  file_count INTEGER NOT NULL CHECK (file_count BETWEEN 1 AND 128),
  destination JSONB NOT NULL,
  placement JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, revision)
);
CREATE TABLE application_deployment_heads (
  project_id BIGINT PRIMARY KEY REFERENCES projects(id),
  revision UUID NOT NULL,
  FOREIGN KEY (project_id, revision) REFERENCES application_releases(project_id, revision)
);
CREATE TABLE application_deployment_receipts (
  request_id UUID PRIMARY KEY,
  account TEXT NOT NULL,
  project_id BIGINT NOT NULL REFERENCES projects(id),
  fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  revision UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (project_id, revision) REFERENCES application_releases(project_id, revision)
);

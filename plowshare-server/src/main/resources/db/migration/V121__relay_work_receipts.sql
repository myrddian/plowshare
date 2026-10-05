-- Destinations and exact handler bytes are pinned before effect dispatch.
ALTER TABLE relay_deliveries ADD COLUMN work_agent TEXT CHECK(length(work_agent) BETWEEN 1 AND 256);
ALTER TABLE relay_deliveries ADD COLUMN work_project TEXT CHECK(length(work_project) BETWEEN 1 AND 256);
ALTER TABLE relay_deliveries ADD COLUMN work_definition TEXT CHECK(length(work_definition) BETWEEN 1 AND 256);
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_work_destination CHECK (
    (receiver IN ('agent.run','script.run','orchestration.start') AND work_agent IS NOT NULL
      AND ((receiver='orchestration.start') = (work_definition IS NOT NULL))
      AND ((receiver='script.run') = (handler_path IS NOT NULL)))
    OR (receiver NOT IN ('agent.run','script.run','orchestration.start')
      AND work_agent IS NULL AND work_project IS NULL AND work_definition IS NULL)
);

-- Separate lifetime from topic TTL and settled delivery cleanup. An unreceipted intent is
-- uncertainty, never a fresh pending job. Handler source remains with its execution lineage.
CREATE TABLE relay_executions (
    request_id UUID PRIMARY KEY,
    account TEXT NOT NULL CHECK(length(account) BETWEEN 1 AND 256),
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    handler_source TEXT CHECK(octet_length(handler_source) <= 131072),
    handler_hash TEXT CHECK(handler_hash ~ '^[0-9a-f]{64}$'),
    receipt_namespace TEXT,
    receipt_id TEXT,
    conversation_id TEXT REFERENCES conversations(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ,
    CHECK ((handler_source IS NULL) = (handler_hash IS NULL)),
    CHECK ((receipt_namespace IS NULL AND receipt_id IS NULL AND accepted_at IS NULL)
      OR (receipt_namespace IS NOT NULL AND receipt_id IS NOT NULL AND accepted_at IS NOT NULL))
);

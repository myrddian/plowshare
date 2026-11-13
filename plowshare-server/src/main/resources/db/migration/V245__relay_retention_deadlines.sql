-- Retention discovery must not probe the publication log once for every live topic.
-- The broker maintains these values under its existing topic lock on append, policy change
-- and prefix pruning. Empty topics have no deadline. Count pressure is an immutable expression.
ALTER TABLE relay_topics ADD COLUMN oldest_published_at TIMESTAMPTZ;
ALTER TABLE relay_topics ADD COLUMN prune_at TIMESTAMPTZ;
ALTER TABLE relay_topics ADD COLUMN over_record_limit BOOLEAN GENERATED ALWAYS AS
  (max_records IS NOT NULL AND last_position-expired_through>max_records) STORED;

WITH oldest AS (
  SELECT t.scope_key,t.name,p.published_at
  FROM relay_topics t JOIN relay_publications p
    ON p.scope_key=t.scope_key AND p.topic=t.name AND p.position=t.expired_through+1
)
UPDATE relay_topics t SET oldest_published_at=o.published_at,
  prune_at=o.published_at+t.retention_seconds*interval '1 second'
FROM oldest o WHERE o.scope_key=t.scope_key AND o.name=t.name;

CREATE INDEX relay_topics_expiry_due ON relay_topics(prune_at,scope_key,name)
  WHERE prune_at IS NOT NULL;
CREATE INDEX relay_topics_record_excess ON relay_topics(oldest_published_at,scope_key,name)
  WHERE over_record_limit;

-- Physical retirement also discovers a bounded keyset without scanning active agent bindings.
CREATE INDEX message_agent_inboxes_retired ON message_agent_inboxes(project_id,topic)
  WHERE retired_at IS NOT NULL;

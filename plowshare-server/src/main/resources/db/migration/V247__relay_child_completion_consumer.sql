-- Every terminal child outcome is an availability notice; result text stays with its owner.
CREATE OR REPLACE FUNCTION relay_capture_orchestration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT; event_topic TEXT;
BEGIN
    SELECT project_id INTO project_key FROM conversations WHERE id=NEW.conductor_conversation;
    IF TG_OP='INSERT' THEN event_topic='orchestration.started';
    ELSIF NEW.state IN ('finished','failed','capped','cancelled') AND OLD.state IS DISTINCT FROM NEW.state THEN
        event_topic='orchestration.ended';
    ELSIF OLD.state='failed' AND NEW.state='running' THEN event_topic='orchestration.resumed';
    END IF;
    IF event_topic IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,event_topic,'LIFECYCLE',NEW.id,NEW.state,NEW.conductor_conversation,NEW.parent,
            COALESCE(NEW.ended_at,CASE WHEN TG_OP='INSERT' THEN NEW.created_at ELSE clock_timestamp() END));
    END IF;
    IF TG_OP='UPDATE' AND OLD.state IS DISTINCT FROM NEW.state AND NEW.state IN ('finished','failed','capped','cancelled') AND NEW.parent IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'orchestration.child.completed','LIFECYCLE',NEW.id,NEW.state,NEW.conductor_conversation,NEW.parent,NEW.ended_at);
    END IF;
    RETURN NEW;
END $$;

CREATE INDEX orchestrations_pending_child_completion ON orchestrations(id)
  WHERE parent IS NOT NULL AND ended_at IS NOT NULL AND result_delivered_at IS NULL;
CREATE INDEX orchestrations_pending_child_parent ON orchestrations(parent)
  WHERE parent IS NOT NULL AND ended_at IS NOT NULL AND result_delivered_at IS NULL;
CREATE INDEX relay_child_completion_outbox ON relay_source_events(subject,occurred_at) WHERE topic='orchestration.child.completed';
CREATE INDEX relay_child_completion_topics ON relay_topics(scope_key) WHERE name='orchestration.child.completed';

-- Separate recovery position: advancing a Relay offset acknowledges availability, not delivery.
-- Busy reports rotate even across process restarts and publication retention gaps.
CREATE TABLE orchestration_completion_recovery (
  scope_key TEXT PRIMARY KEY,
  topic TEXT NOT NULL DEFAULT 'orchestration.child.completed' CHECK (topic='orchestration.child.completed'),
  subscriber TEXT NOT NULL DEFAULT 'builtin.orchestrations.children' CHECK (subscriber='builtin.orchestrations.children'),
  after_child TEXT,
  through_child TEXT,
  CHECK ((after_child IS NULL) = (through_child IS NULL)),
  CHECK (after_child IS NULL OR (after_child ~ '^orc_[A-Za-z0-9]+$'
    AND through_child ~ '^orc_[A-Za-z0-9]+$' AND after_child<=through_child)),
  FOREIGN KEY(scope_key,topic,subscriber) REFERENCES relay_subscriptions(scope_key,topic,subscriber) ON DELETE CASCADE
);

-- Recover pending terminal attempts only. Delivered history is never manufactured as new work.
INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
SELECT c.project_id,'orchestration.child.completed','LIFECYCLE',o.id,o.state,o.conductor_conversation,o.parent,o.ended_at
FROM orchestrations o JOIN conversations c ON c.id=o.conductor_conversation
WHERE o.parent IS NOT NULL AND o.ended_at IS NOT NULL AND o.result_delivered_at IS NULL
  AND NOT EXISTS(SELECT 1 FROM relay_source_events s WHERE s.topic='orchestration.child.completed'
    AND s.subject=o.id AND s.occurred_at=o.ended_at);

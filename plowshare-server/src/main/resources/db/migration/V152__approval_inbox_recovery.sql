-- Native approval notifications consume reference-only Relay events. Retained approval rows
-- remain the recovery source after notification loss or publication expiration.
CREATE INDEX run_approvals_project_pending ON run_approvals(project_id,id) WHERE state='asked';

CREATE TABLE relay_approval_gap_recoveries (
    scope_key TEXT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    expired_through BIGINT NOT NULL CHECK(expired_through>0),
    pending_approvals BIGINT NOT NULL CHECK(pending_approvals>=0),
    recovered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(scope_key,topic,subscriber,expired_through),
    FOREIGN KEY(scope_key,topic,subscriber)
        REFERENCES relay_subscriptions(scope_key,topic,subscriber) ON DELETE CASCADE
);
CREATE INDEX relay_approval_gap_recoveries_age ON relay_approval_gap_recoveries(recovered_at);

-- Existing pending questions may have been acknowledged as text without ever reaching Inbox.
-- Re-publish only their identities; delivery is idempotent and never changes approval state.
INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
SELECT a.project_id,'approval.requested','LIFECYCLE',a.id,a.state,a.conversation,a.asked_in,a.created_at
FROM run_approvals a
WHERE a.state='asked'
  AND NOT EXISTS (SELECT 1 FROM relay_source_events e
    WHERE e.topic='approval.requested' AND e.subject=a.id AND e.project_id=a.project_id)
  AND NOT EXISTS (SELECT 1 FROM user_inbox i WHERE i.kind='approval'
    AND i.about='approval:'||a.id
    AND i.handle=COALESCE(a.handle,(SELECT c.owner_handle FROM conversations c
        WHERE c.id=a.conversation)));

INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
SELECT a.project_id,'approval.resolved','LIFECYCLE',a.id,a.state,a.conversation,a.asked_in,
    COALESCE(a.answered_at,a.created_at)
FROM run_approvals a
WHERE a.state<>'asked'
  AND EXISTS (SELECT 1 FROM user_inbox i WHERE i.kind='approval'
    AND i.about='approval:'||a.id AND i.settled_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM relay_source_events e
    WHERE e.topic='approval.resolved' AND e.subject=a.id AND e.project_id=a.project_id);

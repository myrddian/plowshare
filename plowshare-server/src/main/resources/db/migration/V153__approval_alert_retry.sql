-- A socket push is an attempt, not proof the person saw an approval. Preserve unread state
-- and retry after the configured interval, including crashes after this timestamp is stored.
ALTER TABLE user_inbox ADD COLUMN question_alert_attempted_at TIMESTAMPTZ;
CREATE INDEX user_inbox_pending_approval_alert ON user_inbox(about,handle,question_alert_attempted_at)
    WHERE kind='approval' AND read_at IS NULL AND settled_at IS NULL;

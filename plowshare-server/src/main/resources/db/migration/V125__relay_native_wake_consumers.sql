ALTER TABLE relay_topics DROP CONSTRAINT relay_topics_payload_kind_check;
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_payload_family CHECK (
    payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED'));
ALTER TABLE relay_topics ADD CONSTRAINT relay_native_wake_family CHECK (
    (payload_kind='WAKE_REQUESTED')=(scope_key='system' AND name IN ('message.wake.requested','board.wake.requested')));
CREATE TABLE relay_native_consumers (
    scope_key TEXT NOT NULL CHECK (scope_key='system'),
    topic TEXT NOT NULL CHECK (topic IN ('message.wake.requested','board.wake.requested')),
    subscriber TEXT NOT NULL,
    target TEXT NOT NULL CHECK (target LIKE 'conversation:%' AND length(target) BETWEEN 14 AND 1024),
    account TEXT NOT NULL REFERENCES admins(handle) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    PRIMARY KEY(scope_key,topic,subscriber),
    FOREIGN KEY(scope_key,topic,subscriber) REFERENCES relay_subscriptions(scope_key,topic,subscriber) ON DELETE CASCADE
);
CREATE INDEX relay_native_consumers_target ON relay_native_consumers(target);
CREATE TABLE relay_native_gap_recoveries (
    scope_key TEXT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    expired_through BIGINT NOT NULL CHECK(expired_through>0),
    pending_inbox BIGINT NOT NULL CHECK(pending_inbox>=0),
    recovered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(scope_key,topic,subscriber,expired_through),
    FOREIGN KEY(scope_key,topic,subscriber) REFERENCES relay_native_consumers(scope_key,topic,subscriber) ON DELETE CASCADE
);

CREATE INDEX relay_native_gap_recoveries_age ON relay_native_gap_recoveries(recovered_at);

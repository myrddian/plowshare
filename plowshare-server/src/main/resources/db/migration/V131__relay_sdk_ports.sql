-- External consumer groups are separate from native routing/admission cursors. A retained
-- batch token bounds what can be acknowledged; database time and a fence reject stale clients.
CREATE TABLE relay_sdk_batches (
    scope_key TEXT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256),
    consumer_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    fence BIGINT NOT NULL CHECK (fence > 0),
    through_position BIGINT NOT NULL CHECK (through_position >= 0),
    gap_through BIGINT CHECK (gap_through >= 0),
    lease_until TIMESTAMPTZ NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (scope_key, topic, subscriber),
    FOREIGN KEY (scope_key, topic, subscriber)
        REFERENCES relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE
);

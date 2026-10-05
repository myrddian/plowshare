-- Independent ownership per scoped topic/group. Release retains the epoch so a restarted
-- or reassigned worker cannot reuse an old fencing generation.
CREATE TABLE relay_consumer_leases (
    scope_key TEXT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    worker TEXT NOT NULL CHECK (length(worker) BETWEEN 1 AND 256),
    account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256),
    epoch BIGINT NOT NULL CHECK (epoch > 0),
    lease_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope_key, topic, subscriber),
    FOREIGN KEY (scope_key, topic, subscriber)
        REFERENCES relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE
);

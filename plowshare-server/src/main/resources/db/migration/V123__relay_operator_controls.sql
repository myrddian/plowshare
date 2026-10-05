-- Incarnations prevent delayed removal requests from deleting recreated names/offsets.
ALTER TABLE relay_topics ADD COLUMN generation UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE relay_subscriptions ADD COLUMN generation UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE relay_deliveries DROP CONSTRAINT relay_deliveries_state_check;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_delivery_state_known CHECK (
    state IN ('READY','CLAIMED','DISPATCHING','ACCEPTED','FAILED','UNCERTAIN','ABANDONED','ABANDONED_UNCERTAIN')
);
DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint
        WHERE conrelid='relay_deliveries'::regclass AND contype='c'
            AND pg_get_constraintdef(oid) LIKE '%failure_code%'
    LOOP
        EXECUTE format('ALTER TABLE relay_deliveries DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_delivery_failure_known CHECK (
    (state IN ('FAILED','UNCERTAIN','ABANDONED','ABANDONED_UNCERTAIN')) = (failure_code IS NOT NULL)
);
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_delivery_failure_bounded CHECK (
    failure_code IS NULL OR (length(failure_code)<=160 AND failure_code ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$')
);

-- The receipt and SQL mutation commit together. No remote inspection/effect runs under its lock.
-- Topic/subscriber names survive removal here; project deletion still cascades its private audit.
CREATE TABLE relay_operator_receipts (
    request_id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256),
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    topic TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('ACKNOWLEDGE_GAP','RECONCILE','ABANDON','REMOVE_SUBSCRIPTION','REMOVE_TOPIC')),
    subscriber TEXT,
    delivery_id UUID,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 256),
    status TEXT,
    seen_through BIGINT CHECK (seen_through>=0),
    completed_at TIMESTAMPTZ,
    CHECK ((status IS NULL) = (completed_at IS NULL))
);
CREATE INDEX relay_operator_receipts_retention ON relay_operator_receipts(completed_at);

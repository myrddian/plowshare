-- Separate queueing and durable admission/start I/O from measured transport duration.
-- NULL means unknown (including recovered calls), not a measured zero.
ALTER TABLE inference_calls
    ADD COLUMN queue_millis BIGINT CHECK (queue_millis >= 0),
    ADD COLUMN admission_accounting_millis BIGINT CHECK (admission_accounting_millis >= 0);
ALTER TABLE inference_attempts
    ADD COLUMN start_accounting_millis BIGINT CHECK (start_accounting_millis >= 0);

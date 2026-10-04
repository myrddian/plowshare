-- A message remains pending across approval waits. Continuations keep its identity
-- and use the same durable queue as the initial wake.
ALTER TABLE board_message_routes ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE board_message_routes ADD COLUMN awaiting BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX board_message_one_handling_request
    ON board_message_routes(recipient) WHERE started_at IS NOT NULL AND handled_at IS NULL;
CREATE UNIQUE INDEX board_message_approval_continuation
    ON firings ((data->>'message_approval')) WHERE data ? 'message_approval';
ALTER TABLE board_message_instances ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE board_message_instances ADD COLUMN request_id TEXT;
ALTER TABLE board_message_instances ADD COLUMN open_default BOOLEAN;
CREATE UNIQUE INDEX board_message_instance_open_receipt
    ON board_message_instances(account, project, request_id) WHERE request_id IS NOT NULL;
ALTER TABLE board_message_routes ADD COLUMN deadline_at TIMESTAMPTZ;
ALTER TABLE board_message_routes ADD COLUMN termination TEXT;
CREATE INDEX board_message_deadlines ON board_message_routes(deadline_at)
    WHERE handled_at IS NULL AND deadline_at IS NOT NULL;

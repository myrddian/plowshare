-- External return addresses retain replies without creating a model wake.
ALTER TABLE board_message_instances DROP CONSTRAINT board_message_instances_lifetime_check;
ALTER TABLE board_message_instances ADD CONSTRAINT board_message_instances_lifetime_check
    CHECK (lifetime IN ('persistent', 'task', 'caller', 'external'));
CREATE TABLE message_external_contexts (
    id UUID PRIMARY KEY,
    account TEXT NOT NULL REFERENCES admins(handle),
    project TEXT NOT NULL,
    client TEXT NOT NULL,
    agent TEXT NOT NULL,
    sender TEXT NOT NULL UNIQUE REFERENCES board_message_instances(id),
    recipient TEXT NOT NULL UNIQUE REFERENCES board_message_instances(id)
);
CREATE TABLE message_external_tasks (
    id UUID PRIMARY KEY,
    context UUID NOT NULL REFERENCES message_external_contexts(id),
    account TEXT NOT NULL REFERENCES admins(handle),
    project TEXT NOT NULL,
    client TEXT NOT NULL,
    request_id UUID NOT NULL,
    request_context UUID,
    body TEXT NOT NULL,
    command TEXT,
    source JSONB NOT NULL,
    message TEXT NOT NULL UNIQUE REFERENCES board_message_routes(message),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account, project, client, request_id)
);

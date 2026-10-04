-- Direct messaging uses board messages, topics, budget leases and durable firings.
-- Instances bind an address to a definition and a conversation, including a caller's
-- existing conversation. A private topic is the transport; it is never a public swarm.
CREATE TABLE board_message_instances (
    id TEXT PRIMARY KEY,
    account TEXT NOT NULL REFERENCES admins(handle),
    project TEXT NOT NULL,
    agent TEXT NOT NULL,
    conversation TEXT NOT NULL REFERENCES conversations(id),
    topic TEXT NOT NULL UNIQUE REFERENCES board_topics(id),
    lifetime TEXT NOT NULL CHECK (lifetime IN ('persistent', 'task', 'caller')),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation, agent),
    CHECK (NOT is_default OR lifetime = 'persistent')
);
CREATE UNIQUE INDEX board_message_default_instance
    ON board_message_instances(account, project, agent) WHERE is_default;

CREATE TABLE board_message_routes (
    message TEXT PRIMARY KEY REFERENCES board_messages(id),
    sender TEXT NOT NULL REFERENCES board_message_instances(id),
    recipient TEXT NOT NULL REFERENCES board_message_instances(id),
    reply_to TEXT REFERENCES board_message_routes(message),
    reply_expected BOOLEAN NOT NULL,
    final BOOLEAN NOT NULL DEFAULT FALSE,
    generated BOOLEAN NOT NULL DEFAULT FALSE,
    ending TEXT,
    call_id TEXT,
    handled_at TIMESTAMPTZ,
    CHECK (NOT final OR reply_to IS NOT NULL),
    CHECK (NOT generated OR (final AND ending IS NOT NULL)),
    UNIQUE (sender, call_id)
);
CREATE UNIQUE INDEX board_message_final_reply ON board_message_routes(reply_to) WHERE final;
CREATE INDEX board_message_pending ON board_message_routes(recipient) WHERE handled_at IS NULL;

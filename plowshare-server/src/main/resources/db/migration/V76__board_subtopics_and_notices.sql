-- Request decisions and lifecycle notices survive restarts with their topics.
CREATE TABLE board_decisions (
    request TEXT PRIMARY KEY REFERENCES board_messages(id),
    approved BOOLEAN NOT NULL,
    reason TEXT NOT NULL CHECK (reason <> ''),
    child TEXT UNIQUE REFERENCES board_topics(id),
    decided_at TIMESTAMPTZ NOT NULL,
    CHECK (approved = (child IS NOT NULL))
);
CREATE TABLE board_notices (
    message TEXT PRIMARY KEY REFERENCES board_messages(id),
    topic TEXT NOT NULL REFERENCES board_topics(id),
    kind TEXT NOT NULL CHECK (kind IN ('quiet', 'exhausted')),
    delivered_at TIMESTAMPTZ,
    wake TEXT REFERENCES firings(id)
);
CREATE INDEX board_notices_owed ON board_notices(topic) WHERE delivered_at IS NULL;
ALTER TABLE board_topics ADD COLUMN quiet_through TEXT REFERENCES board_messages(id);
CREATE TABLE board_reads (
    conversation TEXT NOT NULL REFERENCES conversations(id),
    topic TEXT NOT NULL REFERENCES board_topics(id),
    seen_through TEXT NOT NULL REFERENCES board_messages(id),
    PRIMARY KEY(conversation, topic)
);

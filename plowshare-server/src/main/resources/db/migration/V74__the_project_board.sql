-- The project board and the swarm, step 2 (spec 2026-09-29-the-project-board-and-the-swarm-design
-- §4). A project's board is its topics; a topic's messages are what was said on it; a seat is
-- one member's (or the opener's) place on one topic, and its own conversation.
--
-- Every CHECK extended here is rewritten from the migration that LAST defined it:
-- conversations_origin_is_known from V48, firings_unmatched_has_no_trigger from V40,
-- user_inbox_kind_is_known from V61. conversations_an_allowance_is_owned_or_shared (V48) needs
-- no change: 'board' is not an owning origin, so the V48 predicate already demands that a seat's
-- row hold no budget numbers — its wakes spend the root topic's pot.

ALTER TABLE conversations DROP CONSTRAINT conversations_origin_is_known;
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
    CHECK (origin IN ('turn', 'delegation', 'curator', 'submission', 'memory', 'event',
                      'orchestration', 'board'));

ALTER TABLE user_inbox DROP CONSTRAINT user_inbox_kind_is_known;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run', 'sync.conflict', 'orchestration', 'approval', 'hook', 'board'));

CREATE TABLE board_topics (
    id                   TEXT        PRIMARY KEY,
    project              TEXT        NOT NULL,
    parent               TEXT        REFERENCES board_topics (id),
    root                 TEXT        NOT NULL REFERENCES board_topics (id),
    depth                INT         NOT NULL,
    title                TEXT        NOT NULL,
    label                TEXT        NOT NULL,
    -- The account the root was opened for: the swarm scheduler's fair-share key.
    account              TEXT        NOT NULL REFERENCES admins (handle),
    opener_kind          TEXT        NOT NULL,
    opener               TEXT        NOT NULL,
    -- The conversation a bot opened this from; where its resolution is delivered (step 2b).
    origin_conversation  TEXT        REFERENCES conversations (id),
    state                TEXT        NOT NULL,
    -- A board_messages id. No foreign key: the two tables would reference each other.
    resolution           TEXT,
    pot_total            INT,
    pot_spent            INT,
    reserve              INT,
    quiet_notified_at    TIMESTAMPTZ,
    opened_at            TIMESTAMPTZ NOT NULL,
    closed_at            TIMESTAMPTZ,
    CONSTRAINT board_topics_state_is_known CHECK (state IN ('open', 'exhausted', 'closed')),
    CONSTRAINT board_topics_opener_kind_is_known
        CHECK (opener_kind IN ('person', 'bot', 'member')),
    CONSTRAINT board_topics_named CHECK (project <> '' AND title <> '' AND label <> ''),
    -- A title and a label are an author's words that every woken seat is told inside the
    -- harness's own wake line; Board folds each onto one line and caps it, and this bounds them
    -- for any writer that does not.
    CONSTRAINT board_topics_title_and_label_are_bounded
        CHECK (char_length(title) <= 120 AND char_length(label) <= 60),
    CONSTRAINT board_topics_a_root_is_depth_zero CHECK ((parent IS NULL) = (depth = 0)),
    CONSTRAINT board_topics_a_root_is_its_own_root CHECK ((parent IS NULL) = (root = id)),
    CONSTRAINT board_topics_only_a_root_holds_the_pot CHECK (
        (parent IS NULL AND pot_total IS NOT NULL AND pot_spent IS NOT NULL
             AND reserve IS NOT NULL)
        OR (parent IS NOT NULL AND pot_total IS NULL AND pot_spent IS NULL AND reserve IS NULL)),
    CONSTRAINT board_topics_the_pot_is_sane CHECK (pot_total IS NULL
        OR (pot_total > 0 AND pot_spent >= 0 AND reserve >= 1 AND reserve < pot_total)),
    CONSTRAINT board_topics_closed_iff_ended CHECK ((state = 'closed') = (closed_at IS NOT NULL)),
    CONSTRAINT board_topics_a_bot_opens_from_a_conversation
        CHECK ((opener_kind = 'bot') = (origin_conversation IS NOT NULL))
);
CREATE INDEX board_topics_open ON board_topics (project) WHERE closed_at IS NULL;
CREATE INDEX board_topics_children ON board_topics (parent) WHERE parent IS NOT NULL;

CREATE TABLE board_messages (
    id            TEXT        PRIMARY KEY,
    -- Postgres's own arrival order. board_messages.id is a MemoryIds id, and MemoryIds breaks a
    -- same-millisecond tie with random bytes (MemoryIds.java), so two messages posted within one
    -- millisecond can mint out of the order they were actually inserted in. seq never ties.
    seq           BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    topic         TEXT        NOT NULL REFERENCES board_topics (id),
    reply_to      TEXT        REFERENCES board_messages (id),
    author_kind   TEXT        NOT NULL,
    author        TEXT        NOT NULL,
    -- The log that wrote it, and the entry, for /board's descent into a seat (spec §10.3).
    conversation  TEXT        REFERENCES conversations (id),
    entry         INT,
    kind          TEXT        NOT NULL,
    title         TEXT,
    body          TEXT        NOT NULL,
    alert         BOOLEAN     NOT NULL DEFAULT FALSE,
    mentions      TEXT[]      NOT NULL DEFAULT '{}',
    posted_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT board_messages_author_kind_is_known
        CHECK (author_kind IN ('member', 'opener', 'person', 'harness')),
    CONSTRAINT board_messages_kind_is_known
        CHECK (kind IN ('post', 'pass', 'document', 'request', 'resolution', 'note', 'hook')),
    CONSTRAINT board_messages_say_something CHECK (body <> '' AND author <> ''),
    CONSTRAINT board_messages_a_document_or_request_is_titled
        CHECK ((kind IN ('document', 'request')) = (title IS NOT NULL)),
    CONSTRAINT board_messages_only_a_post_alerts CHECK (NOT alert OR kind = 'post')
);
CREATE INDEX board_messages_by_topic ON board_messages (topic, seq);

CREATE TABLE board_seats (
    topic          TEXT    NOT NULL REFERENCES board_topics (id),
    occupant       TEXT    NOT NULL,
    conversation   TEXT    NOT NULL UNIQUE REFERENCES conversations (id),
    passed         BOOLEAN NOT NULL DEFAULT FALSE,
    failed_ending  TEXT,
    silent_wakes   INT     NOT NULL DEFAULT 0,
    seen_through   TEXT    REFERENCES board_messages (id),
    alerts_used    INT     NOT NULL DEFAULT 0,
    PRIMARY KEY (topic, occupant),
    CONSTRAINT board_seats_counts_are_natural CHECK (silent_wakes >= 0 AND alerts_used >= 0)
);

ALTER TABLE firings ADD COLUMN topic TEXT REFERENCES board_topics (id);
ALTER TABLE firings DROP CONSTRAINT firings_unmatched_has_no_trigger;
ALTER TABLE firings ADD CONSTRAINT firings_unmatched_has_no_trigger
    CHECK ((status = 'unmatched') = (trigger IS NULL AND topic IS NULL));
ALTER TABLE firings ADD CONSTRAINT firings_a_wake_is_no_triggers
    CHECK (trigger IS NULL OR topic IS NULL);
CREATE INDEX firings_by_topic ON firings (topic) WHERE topic IS NOT NULL;

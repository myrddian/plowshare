-- Ownership shares the topic row's scoped generation, registration and retirement.
ALTER TABLE relay_topics
    ADD COLUMN topic_owner TEXT NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD COLUMN goal_feed BOOLEAN NOT NULL DEFAULT FALSE;

-- An inbox binding establishes ownership even when all publications have expired.
UPDATE relay_topics AS t
SET topic_owner = 'MESSAGING'
FROM message_agent_inboxes AS i
WHERE t.project_id = i.project_id AND t.name = i.topic AND t.payload_kind = 'TEXT';

UPDATE relay_topics
SET topic_owner = 'RELAY'
WHERE scope_key = 'system' AND payload_kind = 'WAKE_REQUESTED'
    AND name IN ('message.wake.requested', 'board.wake.requested');

-- These exact scoped names come from their producers. Prefixes cannot prove ownership.
UPDATE relay_topics
SET topic_owner = 'EVENTS'
WHERE payload_kind = 'SCHEDULE_DUE'
    AND name = 'schedule.occurrence.' ||
        encode(sha256(convert_to('plowshare.schedule.occurrence/1:' || scope_key, 'UTF8')), 'hex');

-- Raised events register their native subscription in the same transaction as the topic.
-- This backfill uses that binding and keeps the private address derivation inside Events.
UPDATE relay_topics AS t
SET topic_owner = 'EVENTS'
FROM relay_subscriptions AS s
WHERE t.scope_key = 'system' AND t.payload_kind = 'TEXT'
    AND s.scope_key = t.scope_key AND s.topic = t.name AND s.subscriber = 'builtin.events';

UPDATE relay_topics
SET topic_owner = 'INFORMATION'
WHERE topic_owner = 'UNCLASSIFIED' AND payload_kind = 'TEXT'
    AND name = 'information.events.' ||
        encode(sha256(convert_to('plowshare.information.events/1:' || scope_key, 'UTF8')), 'hex');

-- Exact identities from the typed source outbox's owning triggers, including historical
-- Messaging notices still retained on upgraded installations.
UPDATE relay_topics AS t
SET topic_owner = source.owner
FROM (VALUES
    ('job.started', 'JOBS', FALSE),
    ('job.ended', 'JOBS', FALSE),
    ('approval.requested', 'APPROVALS', FALSE),
    ('approval.resolved', 'APPROVALS', FALSE),
    ('orchestration.started', 'ORCHESTRATIONS', FALSE),
    ('orchestration.ended', 'ORCHESTRATIONS', FALSE),
    ('orchestration.resumed', 'ORCHESTRATIONS', FALSE),
    ('orchestration.child.completed', 'ORCHESTRATIONS', FALSE),
    ('orchestration.question.asked', 'ORCHESTRATIONS', FALSE),
    ('orchestration.question.answered', 'ORCHESTRATIONS', FALSE),
    ('board.opened', 'BOARD', FALSE),
    ('board.closed', 'BOARD', FALSE),
    ('board.resolved', 'BOARD', FALSE),
    ('board.message.posted', 'BOARD', FALSE),
    ('message.instance.changed', 'MESSAGING', TRUE),
    ('message.accepted', 'MESSAGING', TRUE),
    ('message.reply.recorded', 'MESSAGING', TRUE),
    ('message.handling.ended', 'MESSAGING', TRUE),
    ('mailbox.item.added', 'PERSONAL', FALSE),
    ('mailbox.application.item.added', 'PERSONAL', FALSE),
    ('application.inbox.item.added', 'APPLICATIONS', FALSE)
) AS source(name, owner, system_only)
WHERE t.payload_kind = 'LIFECYCLE' AND t.name = source.name
    AND (NOT source.system_only OR t.scope_key = 'system');

-- Ambiguous legacy topics retain their data and stay private to Goal Agent discovery
-- until an owning registration identifies them. A legacy row cannot inherit a feed opt-in.
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_owner_known CHECK (
    topic_owner IN ('UNCLASSIFIED', 'GENERAL_PURPOSE', 'RELAY', 'EVENTS', 'MESSAGING',
        'BOARD', 'JOBS', 'APPROVALS', 'ORCHESTRATIONS', 'INFORMATION', 'APPLICATIONS',
        'PERSONAL', 'SECURITY', 'HOSTED_TOOLS')
);
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_goal_feed_known CHECK (
    (topic_owner <> 'GENERAL_PURPOSE' OR (project_id IS NOT NULL AND payload_kind IN ('EMPTY', 'TEXT', 'BLOB')))
    AND (NOT goal_feed OR (
        topic_owner NOT IN ('UNCLASSIFIED', 'GENERAL_PURPOSE')
        AND payload_kind IN ('EMPTY', 'TEXT', 'BLOB', 'LIFECYCLE')
    ))
);

CREATE INDEX relay_goal_sources ON relay_topics (scope_key, name)
    WHERE topic_owner = 'GENERAL_PURPOSE' OR goal_feed;

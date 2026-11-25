-- The active file revision and broker position are separate from the user's Mailbox read state.
CREATE TABLE personal_mailbox_configs (
    project_id BIGINT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    revision TEXT NOT NULL CHECK (revision ~ '^[0-9a-f]{64}$')
);

CREATE TABLE personal_mailbox_subscriptions (
    project_id BIGINT NOT NULL REFERENCES personal_mailbox_configs(project_id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 64),
    consumer UUID NOT NULL UNIQUE,
    topic_generation UUID NOT NULL,
    source_project TEXT NOT NULL,
    schedule TEXT NOT NULL,
    ending TEXT NOT NULL,
    agent TEXT NOT NULL,
    skill TEXT NOT NULL,
    mode TEXT NOT NULL,
    skill_hash TEXT NOT NULL,
    enabled_at TIMESTAMPTZ NOT NULL,
    paused BOOLEAN NOT NULL,
    state TEXT NOT NULL DEFAULT 'idle'
        CHECK (state IN ('idle','starting','running','completed','source-unavailable','blocked','uncertain')),
    item TEXT,
    firing TEXT,
    causation JSONB,
    attempt UUID,
    claim UUID,
    job TEXT,
    detail TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(project_id,name),
    CHECK (state NOT IN ('starting','running') OR
        (item IS NOT NULL AND firing IS NOT NULL AND causation IS NOT NULL AND attempt IS NOT NULL AND claim IS NOT NULL)),
    CHECK (state <> 'running' OR job IS NOT NULL)
);
CREATE INDEX personal_mailbox_worker ON personal_mailbox_subscriptions(project_id,name)
    WHERE NOT paused AND state NOT IN ('blocked','uncertain');

-- Historical deliveries keep their identities. New result writes can converge on one item per firing.
ALTER TABLE user_inbox ADD COLUMN scheduled_delivery BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX user_inbox_scheduled_delivery_once ON user_inbox(handle,firing)
    WHERE scheduled_delivery;

CREATE FUNCTION relay_capture_personal_mailbox_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE personal_id BIGINT; schedule_name TEXT; cause JSONB;
BEGIN
    SELECT id INTO personal_id FROM projects WHERE personal_owner=NEW.handle;
    IF personal_id IS NULL THEN RETURN NEW; END IF;
    SELECT f.schedule INTO schedule_name FROM firings f WHERE f.id=NEW.firing;
    IF schedule_name IS NULL THEN RETURN NEW; END IF;
    -- Serialize the capture timestamp with activation. A committed pre-activation item remains
    -- historical even if the shared publisher appends its outbox entry after activation.
    PERFORM pg_advisory_xact_lock(hashtextextended('personal-mailbox:' || personal_id::text, 0));
    SELECT c.relay_causation INTO cause FROM conversations c WHERE c.id=NEW.conversation;
    INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,
                                    occurred_at,relay_causation)
    VALUES (personal_id,'mailbox.item.added','LIFECYCLE',NEW.id,NEW.ending,schedule_name,
            NEW.firing,clock_timestamp(),cause);
    RETURN NEW;
END $$;
CREATE TRIGGER personal_mailbox_result_added AFTER INSERT ON user_inbox
    FOR EACH ROW WHEN (NEW.scheduled_delivery AND NEW.application_project_id IS NULL)
    EXECUTE FUNCTION relay_capture_personal_mailbox_result();

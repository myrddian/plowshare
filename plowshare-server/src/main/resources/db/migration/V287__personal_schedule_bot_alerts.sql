CREATE TABLE personal_schedule_bot_alerts (
    item TEXT PRIMARY KEY REFERENCES user_inbox(id) ON DELETE CASCADE,
    owner TEXT NOT NULL,
    firing TEXT NOT NULL,
    route TEXT NOT NULL CHECK (route ~ '^[A-Za-z0-9_.-]{1,128}$' AND route NOT IN ('.', '..')),
    causation JSONB,
    next_attempt TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    message TEXT,
    refused BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (NOT refused OR message IS NULL)
);
CREATE INDEX personal_schedule_bot_alerts_pending
    ON personal_schedule_bot_alerts(next_attempt,item) WHERE message IS NULL AND NOT refused;

-- Use the active file projection at result delivery, and retain the exact route for retries.
-- The alert and its Mailbox item commit together. Existing or Application items do not enqueue.
CREATE FUNCTION capture_personal_schedule_bot_alert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO personal_schedule_bot_alerts(item,owner,firing,route,causation)
    SELECT NEW.id, NEW.handle, NEW.firing, sf.definition #>> '{notifyPersonalBot,route}',
           c.relay_causation
      FROM firings f
      JOIN schedule_files sf ON sf.internal_name=f.schedule
      JOIN schedule_sources ss ON ss.id=sf.source_id AND ss.account=NEW.handle
      JOIN projects p ON p.id=ss.project_id AND p.personal_owner=NEW.handle
      LEFT JOIN conversations c ON c.id=NEW.conversation
     WHERE f.id=NEW.firing AND sf.status='active'
       AND sf.definition #>> '{target,kind}'='mailbox'
       AND sf.definition #>> '{notifyPersonalBot,route}' IS NOT NULL;
    RETURN NEW;
END $$;
CREATE TRIGGER personal_schedule_bot_alert_added AFTER INSERT ON user_inbox
    FOR EACH ROW WHEN (NEW.scheduled_delivery AND NEW.application_project_id IS NULL)
    EXECUTE FUNCTION capture_personal_schedule_bot_alert();

ALTER TABLE personal_mailbox_subscriptions
    ADD COLUMN message_route TEXT,
    ALTER COLUMN agent DROP NOT NULL,
    ALTER COLUMN skill DROP NOT NULL,
    ALTER COLUMN mode DROP NOT NULL,
    ALTER COLUMN skill_hash DROP NOT NULL,
    ADD CONSTRAINT personal_subscription_handler CHECK (
       (message_route IS NULL AND num_nonnulls(agent,skill,mode,skill_hash)=4)
       OR
       (message_route IS NOT NULL AND message_route ~ '^[A-Za-z0-9_.-]{1,128}$'
        AND message_route NOT IN ('.','..') AND num_nonnulls(agent,skill,mode,skill_hash)=0)
    );

-- Only internal alert admission writes this capability reference. Ordinary ingress cannot set it.
ALTER TABLE message_external_tasks
    ADD COLUMN personal_alert_item TEXT,
    ADD COLUMN personal_alert_firing TEXT,
    ADD CONSTRAINT message_personal_alert_reference CHECK (
        (personal_alert_item IS NULL) = (personal_alert_firing IS NULL)
    );

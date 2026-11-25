ALTER TABLE personal_mailbox_subscriptions
    ADD COLUMN source_type TEXT,
    ADD COLUMN attention_project BIGINT;
UPDATE personal_mailbox_subscriptions
  SET source_type=CASE WHEN relay_topic IS NULL THEN 'MAILBOX' ELSE 'RELAY' END;
ALTER TABLE personal_mailbox_subscriptions
    ALTER COLUMN source_type SET NOT NULL,
    ADD CHECK (source_type IN ('MAILBOX','RELAY','APPLICATION_INBOX','ATTENTION_COPY','JOB','ORCHESTRATION')),
    ADD CHECK ((source_type='MAILBOX') = (relay_topic IS NULL)),
    ADD CHECK ((source_type='ATTENTION_COPY') = (attention_project IS NOT NULL)),
    ADD CHECK (attention_project IS NULL OR (attention_project>0 AND relay_project_id=project_id));

-- A personal attention copy and the project inbox row own independent item identities.
-- Preserve the origin identifiers even if the source item or project disappears later.
ALTER TABLE user_inbox ADD COLUMN attention_copy_project BIGINT, ADD COLUMN attention_copy_of TEXT,
    ADD CHECK ((attention_copy_project IS NULL) = (attention_copy_of IS NULL)),
    ADD CHECK (attention_copy_project IS NULL OR
        (attention_copy_project>0 AND application_project_id IS NULL AND NOT scheduled_delivery));

CREATE FUNCTION relay_capture_application_inbox_item() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE app BIGINT; scope_id BIGINT; topic_name TEXT; source_log TEXT; cause JSONB;
BEGIN
    IF NEW.application_project_id IS NOT NULL THEN
        app:=NEW.application_project_id;
        scope_id:=app;
        topic_name:='application.inbox.item.added';
        source_log:=NEW.conversation;
    ELSE
        app:=NEW.attention_copy_project;
        SELECT id INTO scope_id FROM projects WHERE personal_owner=NEW.handle;
        IF scope_id IS NULL THEN RETURN NEW; END IF;
        topic_name:='mailbox.application.item.added';
        SELECT conversation INTO source_log FROM user_inbox
            WHERE id=NEW.attention_copy_of AND application_project_id=app;
    END IF;
    -- Activation takes this same lock before choosing its cutoff, including for attention copies.
    PERFORM pg_advisory_xact_lock(hashtextextended('personal-application-inbox:'||app::text,0));
    IF source_log IS NOT NULL THEN
        SELECT relay_causation INTO cause FROM conversations WHERE id=source_log;
        cause:=COALESCE(cause,jsonb_build_object(
            'rootId','conversation:'||source_log,'parentId',NULL,'depth',-1));
    END IF;
    INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,
                                    occurred_at,relay_causation)
    VALUES (scope_id,topic_name,'LIFECYCLE',NEW.id,NEW.kind,app::text,NEW.attention_copy_of,
            clock_timestamp(),cause);
    RETURN NEW;
END $$;
CREATE TRIGGER relay_application_inbox_item_added AFTER INSERT ON user_inbox
    FOR EACH ROW WHEN (
       (NEW.application_project_id IS NOT NULL OR NEW.attention_copy_project IS NOT NULL)
       AND NEW.about IS NULL
       AND NEW.kind IN ('run','orchestration','finding','failure','attention','application'))
    EXECUTE FUNCTION relay_capture_application_inbox_item();

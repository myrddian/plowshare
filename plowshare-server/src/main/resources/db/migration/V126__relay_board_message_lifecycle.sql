-- Board remains the owning message store and swarm runtime. Notices expose references only.
-- Account-private messaging lifecycle uses system scope; project routing cannot subscribe to it.
CREATE FUNCTION relay_capture_board_topic() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT; event_topic TEXT;
BEGIN
    IF EXISTS(SELECT 1 FROM board_message_instances WHERE topic=NEW.id) THEN RETURN NEW; END IF;
    SELECT id INTO project_key FROM projects WHERE name=NEW.project;
    IF project_key IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN event_topic='board.opened';
    ELSIF OLD.state IS DISTINCT FROM NEW.state AND NEW.state='closed' THEN event_topic='board.closed';
    END IF;
    IF event_topic IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,event_topic,'LIFECYCLE',NEW.id,NEW.state,NEW.root,NEW.parent,
            COALESCE(NEW.closed_at,NEW.opened_at));
    END IF;
    IF TG_OP='UPDATE' AND OLD.resolution IS NULL AND NEW.resolution IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'board.resolved','LIFECYCLE',NEW.id,NEW.state,NEW.root,NEW.resolution,clock_timestamp());
    END IF;
    RETURN NEW;
END $$;
-- Mailbox topics are created before their instance row in the same transaction. Defer this
-- classification so that privacy is known before any public notice is captured.
CREATE CONSTRAINT TRIGGER relay_board_topic_changes AFTER INSERT OR UPDATE ON board_topics
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION relay_capture_board_topic();

CREATE FUNCTION relay_capture_board_message() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT; public_topic BOOLEAN;
BEGIN
    SELECT p.id,NOT EXISTS(SELECT 1 FROM board_message_instances i WHERE i.topic=t.id) INTO project_key,public_topic
      FROM board_topics t JOIN projects p ON p.name=t.project WHERE t.id=NEW.topic;
    IF public_topic AND project_key IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'board.message.posted','LIFECYCLE',NEW.id,NEW.kind,NEW.topic,NEW.reply_to,NEW.posted_at);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_board_message_changes AFTER INSERT ON board_messages
    FOR EACH ROW EXECUTE FUNCTION relay_capture_board_message();

CREATE FUNCTION relay_capture_message_instance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR OLD.active IS DISTINCT FROM NEW.active OR OLD.is_default IS DISTINCT FROM NEW.is_default THEN
        INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
        VALUES('message.instance.changed','LIFECYCLE',NEW.id,
            CASE WHEN NEW.active THEN 'active' ELSE 'inactive' END,NEW.conversation,NEW.topic,clock_timestamp());
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_message_instance_changes AFTER INSERT OR UPDATE OF active,is_default ON board_message_instances
    FOR EACH ROW EXECUTE FUNCTION relay_capture_message_instance();

CREATE FUNCTION relay_capture_message_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
        VALUES('message.accepted','LIFECYCLE',NEW.message,'accepted',NEW.recipient,NEW.sender,clock_timestamp());
        IF NEW.reply_to IS NOT NULL THEN
            INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
            VALUES('message.reply.recorded','LIFECYCLE',NEW.message,
                CASE WHEN NEW.final THEN 'final' ELSE 'reply' END,NEW.recipient,NEW.reply_to,clock_timestamp());
        END IF;
    ELSIF OLD.handled_at IS NULL AND NEW.handled_at IS NOT NULL THEN
        INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
        VALUES('message.handling.ended','LIFECYCLE',NEW.message,'handled',NEW.recipient,NEW.reply_to,NEW.handled_at);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_message_route_changes AFTER INSERT OR UPDATE OF handled_at ON board_message_routes
    FOR EACH ROW EXECUTE FUNCTION relay_capture_message_route();

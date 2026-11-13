-- V243 removed every private messaging forum topic. Public forum capture must not
-- consult historical message_instances.former_topic on every write thereafter.
CREATE OR REPLACE FUNCTION relay_capture_board_topic() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT; event_topic TEXT;
BEGIN
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

CREATE OR REPLACE FUNCTION relay_capture_board_message() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT;
BEGIN
    SELECT p.id INTO project_key FROM board_topics t JOIN projects p ON p.name=t.project
      WHERE t.id=NEW.topic;
    IF project_key IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'board.message.posted','LIFECYCLE',NEW.id,NEW.kind,NEW.topic,NEW.reply_to,NEW.posted_at);
    END IF;
    RETURN NEW;
END $$;

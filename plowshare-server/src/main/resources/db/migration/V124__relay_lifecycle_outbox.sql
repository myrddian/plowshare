-- Producers capture only committed owning-row changes. Socket notifications remain droppable.
-- This outbox is intentionally separate from publication retention: a paused publisher cannot
-- erase pending notices. Publishing and deletion occur together in one bounded transaction.
CREATE TABLE relay_source_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE,
    topic TEXT NOT NULL CHECK (length(topic)<=160 AND topic ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    family TEXT NOT NULL CHECK (family IN ('LIFECYCLE','WAKE_REQUESTED')),
    subject TEXT NOT NULL CHECK (length(subject) BETWEEN 1 AND 1024),
    state TEXT NOT NULL CHECK (length(state) BETWEEN 1 AND 256),
    context TEXT CHECK (context IS NULL OR length(context) BETWEEN 1 AND 1024),
    related TEXT CHECK (related IS NULL OR length(related) BETWEEN 1 AND 1024),
    occurred_at TIMESTAMPTZ NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (family <> 'WAKE_REQUESTED' OR
        (project_id IS NULL AND topic IN ('message.wake.requested','board.wake.requested')
            AND state IN ('MESSAGE','BOARD') AND context LIKE 'conversation:%' AND related IS NULL))
);
CREATE INDEX relay_source_events_partition ON relay_source_events(COALESCE(project_id,0),topic,sequence);

CREATE FUNCTION relay_capture_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,occurred_at)
        VALUES(NEW.project_id,'job.started','LIFECYCLE',NEW.id,'submitted',NEW.agent,NEW.started_at);
    ELSIF OLD.ending IS DISTINCT FROM NEW.ending THEN
        IF NEW.ending IS NOT NULL THEN
            INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,occurred_at)
            VALUES(NEW.project_id,'job.ended','LIFECYCLE',NEW.id,NEW.ending,NEW.agent,NEW.ended_at);
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_job_changes AFTER INSERT OR UPDATE OF ending,ended_at ON jobs
    FOR EACH ROW EXECUTE FUNCTION relay_capture_job();

CREATE FUNCTION relay_capture_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.state='asked' THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(NEW.project_id,'approval.requested','LIFECYCLE',NEW.id,NEW.state,NEW.conversation,NEW.asked_in,NEW.created_at);
    ELSIF (TG_OP='INSERT' AND NEW.state<>'asked') OR (TG_OP='UPDATE' AND OLD.state IS DISTINCT FROM NEW.state) THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(NEW.project_id,'approval.resolved','LIFECYCLE',NEW.id,NEW.state,NEW.conversation,NEW.asked_in,COALESCE(NEW.answered_at,clock_timestamp()));
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_approval_changes AFTER INSERT OR UPDATE OF state ON run_approvals
    FOR EACH ROW EXECUTE FUNCTION relay_capture_approval();

CREATE FUNCTION relay_capture_orchestration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT; event_topic TEXT;
BEGIN
    SELECT project_id INTO project_key FROM conversations WHERE id=NEW.conductor_conversation;
    IF TG_OP='INSERT' THEN event_topic='orchestration.started';
    ELSIF NEW.state IN ('finished','failed','capped','cancelled') AND OLD.state IS DISTINCT FROM NEW.state THEN
        event_topic='orchestration.ended';
    ELSIF OLD.state='failed' AND NEW.state='running' THEN event_topic='orchestration.resumed';
    END IF;
    IF event_topic IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,event_topic,'LIFECYCLE',NEW.id,NEW.state,NEW.conductor_conversation,NEW.parent,
            COALESCE(NEW.ended_at,CASE WHEN TG_OP='INSERT' THEN NEW.created_at ELSE clock_timestamp() END));
    END IF;
    IF TG_OP='UPDATE' AND OLD.state IS DISTINCT FROM NEW.state AND NEW.state='finished' AND NEW.parent IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'orchestration.child.completed','LIFECYCLE',NEW.id,NEW.state,NEW.conductor_conversation,NEW.parent,NEW.ended_at);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_orchestration_changes AFTER INSERT OR UPDATE OF state ON orchestrations
    FOR EACH ROW EXECUTE FUNCTION relay_capture_orchestration();

CREATE FUNCTION relay_capture_question() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE project_key BIGINT; conversation_key TEXT;
BEGIN
    SELECT c.project_id,o.conductor_conversation INTO project_key,conversation_key
      FROM orchestrations o JOIN conversations c ON c.id=o.conductor_conversation WHERE o.id=NEW.orchestration;
    INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
    VALUES(project_key,CASE NEW.kind WHEN 'question' THEN 'orchestration.question.asked' ELSE 'orchestration.question.answered' END,
        'LIFECYCLE',NEW.orchestration,NEW.kind,conversation_key,NEW.id,NEW.created_at);
    RETURN NEW;
END $$;
CREATE TRIGGER relay_question_changes AFTER INSERT ON orchestration_messages
    FOR EACH ROW EXECUTE FUNCTION relay_capture_question();

-- Wake capture is atomic with the message/Board write and native inbox admission. Private text,
-- approval command data and member prompts stay solely in their owning tables.
CREATE FUNCTION relay_capture_wake() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE wake_type TEXT; event_topic TEXT;
BEGIN
    wake_type=CASE WHEN NEW.data ? 'direct_message' THEN 'MESSAGE' ELSE 'BOARD' END;
    event_topic=CASE wake_type WHEN 'MESSAGE' THEN 'message.wake.requested' ELSE 'board.wake.requested' END;
    INSERT INTO relay_source_events(topic,family,subject,state,context,occurred_at)
    VALUES(event_topic,'WAKE_REQUESTED',NEW.id,wake_type,NEW.target,NEW.arrived_at);
    RETURN NEW;
END $$;
CREATE TRIGGER relay_wake_changes AFTER INSERT ON firings
    FOR EACH ROW WHEN (NEW.topic IS NOT NULL AND NEW.status='queued') EXECUTE FUNCTION relay_capture_wake();

-- Upgrade only retained queued work. Historical starts/endings are not manufactured events.
INSERT INTO relay_source_events(topic,family,subject,state,context,occurred_at)
SELECT CASE WHEN data ? 'direct_message' THEN 'message.wake.requested' ELSE 'board.wake.requested' END,
    'WAKE_REQUESTED',id,CASE WHEN data ? 'direct_message' THEN 'MESSAGE' ELSE 'BOARD' END,target,arrived_at
FROM firings WHERE topic IS NOT NULL AND status='queued' ORDER BY arrived_at,id;

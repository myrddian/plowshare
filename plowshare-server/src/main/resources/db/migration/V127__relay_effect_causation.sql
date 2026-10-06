-- One immutable effect depth survives lifecycle capture, topic TTL, routing and worker leases.
-- Unknown ancestry uses depth -1 explicitly; it must never become an independent root.
CREATE FUNCTION relay_valid_causation(value JSONB) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT value IS NULL OR (
      jsonb_typeof(value)='object' AND value ?& ARRAY['rootId','parentId','depth']
      AND (value - ARRAY['rootId','parentId','depth'])='{}'::jsonb
      AND jsonb_typeof(value->'rootId')='string'
      AND length(value->>'rootId') BETWEEN 1 AND 256
      AND value->>'rootId'=btrim(value->>'rootId')
      AND value->>'rootId' !~ '[[:cntrl:]]'
      AND jsonb_typeof(value->'depth')='number'
      AND (value->>'depth') ~ '^(-1|[0-9]|[12][0-9]|3[0-2])$'
      AND CASE WHEN (value->>'depth')::integer <= 0 THEN value->'parentId'='null'::jsonb
        ELSE jsonb_typeof(value->'parentId')='string'
          AND length(value->>'parentId') BETWEEN 1 AND 256
          AND value->>'parentId'=btrim(value->>'parentId')
          AND value->>'parentId' !~ '[[:cntrl:]]' END
    ) IS TRUE
$$;
ALTER TABLE relay_publications ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));
ALTER TABLE relay_admissions ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));
-- Lifecycle routes pin the same typed input as ordinary Relay routes.
ALTER TABLE relay_admissions DROP CONSTRAINT relay_admissions_payload_kind_check;
ALTER TABLE relay_admissions ADD CONSTRAINT relay_admissions_payload_kind_check
    CHECK(payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED'));
ALTER TABLE relay_source_events ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));
ALTER TABLE conversations ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));
ALTER TABLE jobs ADD COLUMN conversation_id TEXT REFERENCES conversations(id) ON DELETE SET NULL;
ALTER TABLE jobs ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));
ALTER TABLE orchestrations ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));

-- Existing work cannot prove its original input. Preserve inspection and completion, but refuse
-- another Relay effect from its notices. Independent jobs created after this upgrade get roots.
UPDATE jobs SET relay_causation=jsonb_build_object('rootId','job:'||id,'parentId',NULL,'depth',-1);
UPDATE orchestrations SET relay_causation=jsonb_build_object('rootId','orchestration:'||id,'parentId',NULL,'depth',-1);
UPDATE conversations SET relay_causation=jsonb_build_object('rootId','conversation:'||id,'parentId',NULL,'depth',-1);
UPDATE relay_source_events SET relay_causation=jsonb_build_object('rootId','source:'||id,'parentId',NULL,'depth',-1);

CREATE FUNCTION relay_inherit_conversation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND NEW.relay_causation IS NULL THEN
      SELECT relay_causation INTO NEW.relay_causation FROM conversations WHERE id=NEW.parent_id;
      IF NEW.relay_causation IS NULL THEN
        NEW.relay_causation=jsonb_build_object('rootId','conversation:'||NEW.parent_id,'parentId',NULL,'depth',-1);
      END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_conversation_causation BEFORE INSERT ON conversations
    FOR EACH ROW EXECUTE FUNCTION relay_inherit_conversation();

CREATE FUNCTION relay_inherit_job() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE inherited JSONB;
BEGIN
    IF NEW.conversation_id IS NOT NULL THEN
      -- Serialize the first job on a conversation; all later turns retain its ancestry.
      SELECT relay_causation INTO inherited FROM conversations WHERE id=NEW.conversation_id FOR UPDATE;
      IF inherited IS NOT NULL AND NEW.relay_causation IS NOT NULL AND inherited<>NEW.relay_causation THEN
        RAISE EXCEPTION 'Job causation conflicts with its conversation';
      END IF;
    END IF;
    NEW.relay_causation=COALESCE(NEW.relay_causation,inherited,
      jsonb_build_object('rootId','job:'||NEW.id,'parentId',NULL,'depth',0));
    IF NEW.conversation_id IS NOT NULL THEN
      UPDATE conversations SET relay_causation=NEW.relay_causation
        WHERE id=NEW.conversation_id AND relay_causation IS NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_job_causation BEFORE INSERT ON jobs FOR EACH ROW EXECUTE FUNCTION relay_inherit_job();

CREATE FUNCTION relay_inherit_orchestration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.relay_causation IS NULL AND NEW.parent IS NOT NULL THEN
      SELECT relay_causation INTO NEW.relay_causation FROM orchestrations WHERE id=NEW.parent;
    END IF;
    IF NEW.relay_causation IS NULL AND NEW.caller_conversation IS NOT NULL THEN
      SELECT relay_causation INTO NEW.relay_causation FROM conversations WHERE id=NEW.caller_conversation;
      IF NEW.relay_causation IS NULL THEN
        NEW.relay_causation=jsonb_build_object('rootId','conversation:'||NEW.caller_conversation,'parentId',NULL,'depth',-1);
      END IF;
    END IF;
    NEW.relay_causation=COALESCE(NEW.relay_causation,
      jsonb_build_object('rootId','orchestration:'||NEW.id,'parentId',NULL,'depth',0));
    UPDATE conversations SET relay_causation=NEW.relay_causation
      WHERE id=NEW.conductor_conversation AND relay_causation IS NULL;
    RETURN NEW;
END $$;
CREATE TRIGGER relay_orchestration_causation BEFORE INSERT ON orchestrations
    FOR EACH ROW EXECUTE FUNCTION relay_inherit_orchestration();

-- Capture from owning work inside the same transaction, including notices from fast jobs.
-- Copying depth does not consume an effect; only a receiver starting work/forwarding increments it.
CREATE FUNCTION relay_inherit_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE conversation_key TEXT; derived BOOLEAN=false;
BEGIN
    IF NEW.relay_causation IS NOT NULL THEN RETURN NEW; END IF;
    IF NEW.topic LIKE 'job.%' THEN
      derived=true;
      SELECT relay_causation INTO NEW.relay_causation FROM jobs WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'orchestration.%' THEN
      derived=true;
      SELECT relay_causation INTO NEW.relay_causation FROM orchestrations WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'approval.%' OR NEW.topic='message.instance.changed' THEN
      conversation_key=NEW.context;
    ELSIF NEW.topic='board.message.posted' THEN
      SELECT conversation INTO conversation_key FROM board_messages WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'board.%' THEN
      SELECT origin_conversation INTO conversation_key FROM board_topics WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'message.%' THEN
      SELECT conversation INTO conversation_key FROM board_messages WHERE id=NEW.subject;
    END IF;
    IF conversation_key IS NOT NULL THEN
      derived=true;
      SELECT relay_causation INTO NEW.relay_causation FROM conversations WHERE id=conversation_key;
    END IF;
    NEW.relay_causation=COALESCE(NEW.relay_causation,
      jsonb_build_object('rootId','source:'||NEW.id,'parentId',NULL,'depth',CASE WHEN derived THEN -1 ELSE 0 END));
    RETURN NEW;
END $$;
CREATE TRIGGER relay_source_causation BEFORE INSERT ON relay_source_events
    FOR EACH ROW EXECUTE FUNCTION relay_inherit_source();

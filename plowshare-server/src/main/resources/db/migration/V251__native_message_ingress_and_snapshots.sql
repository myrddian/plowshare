-- Preserve legacy task/context identifiers and source evidence. New trusted channels are explicit
-- namespaces; no operator mapping means a new Relay route cannot adopt legacy contexts.
DO $$
BEGIN
  -- Valid legacy DTOs persisted a normalized identity. Java counts supplementary codepoints as
  -- two UTF-16 units; PostgreSQL char_length alone would accept an over-bound historical value.
  IF EXISTS(SELECT 1 FROM message_external_tasks WHERE source<>'null'::jsonb AND (
      jsonb_typeof(source)<>'object' OR jsonb_typeof(source->'messageId') IS DISTINCT FROM 'string'
      OR source->>'messageId'=''
      OR char_length(source->>'messageId')
        + char_length(regexp_replace(source->>'messageId',U&'[^\+010000-\+10FFFF]','','g'))>256
      OR source->>'messageId' ~ U&'[\0001-\001F\007F-\009F\2028\2029]'
      OR source->>'messageId'<>btrim(source->>'messageId',U&' \1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\205F\3000')
  )) THEN
    RAISE EXCEPTION 'V251 native ingress preflight refused: invalid retained source identity; inspect and explicitly repair message_external_tasks.source before retrying; no records were removed or guessed';
  END IF;
END $$;
ALTER TABLE relay_deliveries ADD COLUMN work_reply_topic TEXT CHECK(length(work_reply_topic) BETWEEN 1 AND 1024);
ALTER TABLE relay_deliveries ADD COLUMN work_channel TEXT CHECK(length(work_channel) BETWEEN 1 AND 256);
ALTER TABLE relay_deliveries DROP CONSTRAINT relay_work_destination;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_work_destination CHECK (
  (receiver IN ('agent.run','script.run','orchestration.start')
    AND work_agent IS NOT NULL AND work_topic IS NULL AND work_swarm IS NULL
    AND work_reply_topic IS NULL AND work_channel IS NULL
    AND ((receiver='orchestration.start') = (work_definition IS NOT NULL))
    AND ((receiver='script.run') = (handler_path IS NOT NULL)))
  OR (receiver='board.open' AND work_agent IS NULL AND work_definition IS NULL
    AND work_project IS NULL AND work_topic IS NULL AND work_swarm IS NOT NULL
    AND work_reply_topic IS NULL AND work_channel IS NULL AND handler_path IS NULL)
  OR (receiver IN ('board.post','board.close') AND work_agent IS NULL AND work_definition IS NULL
    AND work_project IS NULL AND work_topic IS NOT NULL AND work_swarm IS NULL
    AND work_reply_topic IS NULL AND work_channel IS NULL AND handler_path IS NULL)
  OR (receiver IN ('message.receive','message.cancel','message.inspect') AND work_agent IS NOT NULL
    AND work_reply_topic IS NOT NULL AND work_channel IS NOT NULL AND work_project IS NULL
    AND work_definition IS NULL AND work_topic IS NULL AND work_swarm IS NULL
    AND handler_path IS NULL AND publish_to IS NULL)
  OR (receiver NOT IN ('agent.run','script.run','orchestration.start','board.open','board.post','board.close',
      'message.receive','message.cancel','message.inspect')
    AND work_agent IS NULL AND work_project IS NULL AND work_definition IS NULL
    AND work_topic IS NULL AND work_swarm IS NULL AND work_reply_topic IS NULL AND work_channel IS NULL)
);
ALTER TABLE message_external_contexts ADD COLUMN channel TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE message_external_tasks ADD COLUMN channel TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE message_external_tasks ADD COLUMN native_source JSONB;
ALTER TABLE message_external_tasks ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>0);
UPDATE message_external_tasks SET native_source=CASE WHEN source='null'::jsonb THEN 'null'::jsonb
  ELSE jsonb_build_object('kind','legacy-ws','identity',source->>'messageId',
    'fingerprint',encode(sha256(convert_to(source::text,'UTF8')),'hex')) END;
ALTER TABLE message_external_tasks ALTER COLUMN native_source SET NOT NULL;
ALTER TABLE message_external_tasks DROP CONSTRAINT message_external_tasks_account_project_client_request_id_key;
ALTER TABLE message_external_tasks ADD CONSTRAINT message_external_tasks_scoped_request
  UNIQUE(account,project,channel,client,request_id);
CREATE INDEX message_external_context_scope ON message_external_contexts(account,project,channel,client,id);
ALTER TABLE message_routes ADD COLUMN relay_causation JSONB CHECK(relay_valid_causation(relay_causation));

-- Changes are captured in the same owning transaction. Workers lock a task's revision row while
-- reading its native header and bounded immutable reply-body chunks; no route row lock is taken
-- in that read direction. Native writers hold their route before updating this revision, so a
-- snapshot sees the old committed state or the new committed state, never a partial transition.
CREATE TABLE message_relay_task_outbox (
  task UUID PRIMARY KEY REFERENCES message_external_tasks(id) ON DELETE CASCADE,
  revision BIGINT NOT NULL CHECK(revision>0), retry_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity'
);
CREATE FUNCTION message_capture_task_route() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed RECORD;
BEGIN
  IF TG_OP='UPDATE' AND ROW(NEW.started_at,NEW.handled_at,NEW.awaiting,NEW.termination,NEW.ending)
      IS NOT DISTINCT FROM ROW(OLD.started_at,OLD.handled_at,OLD.awaiting,OLD.termination,OLD.ending)
    THEN RETURN NEW; END IF;
  FOR changed IN UPDATE message_external_tasks SET revision=revision+1
    WHERE message=NEW.message OR message=NEW.reply_to RETURNING id,revision
  LOOP
    INSERT INTO message_relay_task_outbox(task,revision)
      SELECT changed.id,changed.revision WHERE EXISTS(SELECT 1 FROM message_relay_bindings WHERE task=changed.id AND NOT disabled)
      ON CONFLICT(task) DO UPDATE SET revision=EXCLUDED.revision;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER message_task_route_changes AFTER INSERT OR UPDATE ON message_routes
  FOR EACH ROW EXECUTE FUNCTION message_capture_task_route();
CREATE FUNCTION message_capture_task_firing() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed RECORD;
BEGIN
  IF TG_OP='UPDATE' AND ROW(NEW.status,NEW.job_id,NEW.started_at,NEW.finished_at,NEW.reason)
      IS NOT DISTINCT FROM ROW(OLD.status,OLD.job_id,OLD.started_at,OLD.finished_at,OLD.reason)
    THEN RETURN NEW; END IF;
  FOR changed IN UPDATE message_external_tasks SET revision=revision+1
    WHERE message=NEW.data->>'direct_message' RETURNING id,revision
  LOOP
    INSERT INTO message_relay_task_outbox(task,revision)
      SELECT changed.id,changed.revision WHERE EXISTS(SELECT 1 FROM message_relay_bindings WHERE task=changed.id AND NOT disabled)
      ON CONFLICT(task) DO UPDATE SET revision=EXCLUDED.revision;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER message_task_firing_changes AFTER INSERT OR UPDATE ON firings
  FOR EACH ROW WHEN (NEW.message_instance IS NOT NULL) EXECUTE FUNCTION message_capture_task_firing();

CREATE TABLE message_relay_families (
  namespace TEXT PRIMARY KEY, account TEXT NOT NULL REFERENCES admins(handle),
  project TEXT NOT NULL, project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  publisher TEXT NOT NULL, source_topic TEXT NOT NULL, subscriber TEXT NOT NULL,
  source_generation UUID NOT NULL, channel TEXT NOT NULL, execution_definition TEXT, agent TEXT NOT NULL,
  reply_topic TEXT NOT NULL, reply_generation UUID NOT NULL
);
CREATE TABLE message_relay_operations (
  identity TEXT PRIMARY KEY, delivery UUID UNIQUE, namespace TEXT NOT NULL REFERENCES message_relay_families(namespace),
  client TEXT NOT NULL, request_id UUID NOT NULL, source_event TEXT NOT NULL, receiver TEXT NOT NULL,
  fingerprint TEXT NOT NULL, snapshot TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
  relay_causation JSONB CHECK(relay_valid_causation(relay_causation)),
  published BOOLEAN NOT NULL DEFAULT false, destination_failure TEXT,
  retry_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity',
  CHECK(destination_failure IS NULL OR destination_failure IN ('MISSING','REPLACED','INVALID_KIND','TASK_UNAVAILABLE'))
);
CREATE UNIQUE INDEX message_relay_command_identity ON message_relay_operations(namespace,client,request_id)
  WHERE delivery IS NOT NULL AND receiver<>'message.conflict';
CREATE INDEX message_relay_operation_outbox ON message_relay_operations(retry_at,identity)
  WHERE NOT published AND destination_failure IS NULL;
-- A later body chunk must not pass an unpublished predecessor, including while it backs off.
CREATE INDEX message_relay_operation_binding_pending
  ON message_relay_operations(namespace,client,request_id,source_event) WHERE NOT published;
CREATE TABLE message_relay_delivery_receipts (
  delivery UUID PRIMARY KEY,operation TEXT NOT NULL REFERENCES message_relay_operations(identity)
);
CREATE TABLE message_relay_bindings (
  task UUID PRIMARY KEY REFERENCES message_external_tasks(id) ON DELETE CASCADE,
  namespace TEXT NOT NULL REFERENCES message_relay_families(namespace), client TEXT NOT NULL,
  request_id UUID NOT NULL, source_event TEXT NOT NULL, cursor TEXT NOT NULL,
  disabled BOOLEAN NOT NULL DEFAULT false,
  revision BIGINT NOT NULL CHECK(revision>0),
  relay_causation JSONB CHECK(relay_valid_causation(relay_causation))
);
CREATE INDEX message_routes_reply_history ON message_routes(reply_to,message) WHERE reply_to IS NOT NULL;
CREATE TABLE message_relay_legacy_migrations (
  namespace TEXT NOT NULL REFERENCES message_relay_families(namespace),client TEXT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(namespace,client)
);

CREATE INDEX message_relay_task_discovery ON message_relay_task_outbox(retry_at,task);

-- Typed board destinations are pinned with admission; the Relay envelope is unchanged.
ALTER TABLE relay_deliveries ADD COLUMN work_topic TEXT CHECK (work_topic ~ '^bdt_[A-Za-z0-9]+$' AND length(work_topic) <= 256);
ALTER TABLE relay_deliveries ADD COLUMN work_swarm TEXT CHECK (work_swarm ~ '^[a-z][a-z0-9]*([-_][a-z0-9]+)*$' AND length(work_swarm) <= 64);
ALTER TABLE relay_deliveries DROP CONSTRAINT relay_work_destination;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_work_destination CHECK (
  (receiver IN ('agent.run','script.run','orchestration.start')
    AND work_agent IS NOT NULL AND work_topic IS NULL AND work_swarm IS NULL
    AND ((receiver='orchestration.start') = (work_definition IS NOT NULL))
    AND ((receiver='script.run') = (handler_path IS NOT NULL)))
  OR (receiver='board.open' AND work_agent IS NULL AND work_definition IS NULL
    AND work_project IS NULL AND work_topic IS NULL AND work_swarm IS NOT NULL AND handler_path IS NULL)
  OR (receiver IN ('board.post','board.close') AND work_agent IS NULL AND work_definition IS NULL
    AND work_project IS NULL AND work_topic IS NOT NULL AND work_swarm IS NULL AND handler_path IS NULL)
  OR (receiver NOT IN ('agent.run','script.run','orchestration.start','board.open','board.post','board.close')
    AND work_agent IS NULL AND work_project IS NULL AND work_definition IS NULL
    AND work_topic IS NULL AND work_swarm IS NULL)
);

-- Authenticated port provenance is separate from the opaque broker envelope. Unmapped historical
-- publishers are refused by the new bridge, never guessed from a worker or a payload field.
CREATE TABLE relay_port_publishers (
  publisher TEXT PRIMARY KEY CHECK (publisher ~ '^sdk:[0-9a-f]{64}$'),
  account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256)
);

-- A retained request discloses only its reply thread and the terminal resolution. Owning board
-- mutation and this binding commit together, before after-commit wakes can write a response.
CREATE TABLE board_relay_requests (
  request_id UUID PRIMARY KEY,
  account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256),
  message_id TEXT NOT NULL UNIQUE REFERENCES board_messages(id) ON DELETE CASCADE,
  topic_id TEXT NOT NULL REFERENCES board_topics(id) ON DELETE CASCADE,
  reply_project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  reply_topic TEXT NOT NULL CHECK (length(reply_topic) BETWEEN 1 AND 1024),
  reply_to TEXT NOT NULL CHECK (length(reply_to) BETWEEN 1 AND 256),
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  causation_root TEXT,
  causation_parent TEXT,
  causation_depth INT,
  CHECK ((causation_root IS NULL AND causation_parent IS NULL AND causation_depth IS NULL)
    OR (causation_root IS NOT NULL AND causation_depth IS NOT NULL AND causation_depth BETWEEN -1 AND 32
      AND ((causation_depth <= 0) = (causation_parent IS NULL))))
);
CREATE INDEX board_relay_requests_topic ON board_relay_requests(topic_id);
CREATE INDEX board_relay_requests_project ON board_relay_requests(reply_project_id);
CREATE INDEX board_relay_requests_ready ON board_relay_requests(next_attempt_at);

-- Materialized thread membership avoids recursive ancestry reads for every answer. A message
-- belongs to a bridge request only if it replies to a message already in that request's thread.
CREATE TABLE board_relay_threads (
  message_id TEXT PRIMARY KEY REFERENCES board_messages(id) ON DELETE CASCADE,
  request_id UUID NOT NULL REFERENCES board_relay_requests(request_id) ON DELETE CASCADE
);
CREATE INDEX board_relay_threads_request ON board_relay_threads(request_id);
CREATE TABLE board_relay_outbox (
  request_id UUID NOT NULL REFERENCES board_relay_requests(request_id) ON DELETE CASCADE,
  message_id TEXT NOT NULL REFERENCES board_messages(id) ON DELETE CASCADE,
  terminal BOOLEAN NOT NULL,
  PRIMARY KEY(request_id,message_id)
);
CREATE INDEX board_relay_outbox_message ON board_relay_outbox(message_id);
CREATE UNIQUE INDEX board_relay_one_terminal ON board_relay_outbox(request_id) WHERE terminal;

CREATE FUNCTION board_relay_reply_added() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.reply_to IS NOT NULL THEN
    INSERT INTO board_relay_threads(message_id,request_id)
      SELECT NEW.id,t.request_id FROM board_relay_threads t
      JOIN board_relay_requests r ON r.request_id=t.request_id
      WHERE t.message_id=NEW.reply_to AND r.topic_id=NEW.topic
      ON CONFLICT DO NOTHING;
    INSERT INTO board_relay_outbox(request_id,message_id,terminal)
      SELECT request_id,NEW.id,false FROM board_relay_threads WHERE message_id=NEW.id
      ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER board_relay_reply_added AFTER INSERT ON board_messages
  FOR EACH ROW EXECUTE FUNCTION board_relay_reply_added();

CREATE FUNCTION board_relay_topic_closed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state <> 'closed' AND NEW.state='closed' AND NEW.resolution IS NOT NULL THEN
    INSERT INTO board_relay_outbox(request_id,message_id,terminal)
      SELECT request_id,NEW.resolution,true FROM board_relay_requests WHERE topic_id=NEW.id
      ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER board_relay_topic_closed AFTER UPDATE OF state ON board_topics
  FOR EACH ROW EXECUTE FUNCTION board_relay_topic_closed();

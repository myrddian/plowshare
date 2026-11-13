-- Bind disclosures to one topic incarnation. Older rows carry no proof of identity: leave their
-- generation null so the worker records UNVERIFIED rather than adopting a possibly recreated name.
ALTER TABLE board_relay_requests ADD COLUMN reply_generation UUID;
ALTER TABLE board_relay_requests ADD COLUMN reply_failure TEXT
  CHECK (reply_failure IN ('MISSING','REPLACED','UNVERIFIED','INVALID_KIND'));
ALTER TABLE board_relay_requests ADD COLUMN reply_failed_at TIMESTAMPTZ;
ALTER TABLE board_relay_requests ADD CONSTRAINT board_reply_failure_complete
  CHECK ((reply_failure IS NULL) = (reply_failed_at IS NULL));

-- Outstanding debt has its immutable board order alongside the request key, so the global
-- oldest-first LIMIT starts from this index without sorting all debt or visiting quiet requests.
ALTER TABLE board_relay_outbox ADD COLUMN message_seq BIGINT;
UPDATE board_relay_outbox o SET message_seq=b.seq FROM board_messages b WHERE b.id=o.message_id;
ALTER TABLE board_relay_outbox ALTER COLUMN message_seq SET NOT NULL;
CREATE INDEX board_relay_outbox_delivery_order ON board_relay_outbox(message_seq,request_id);

-- Failed debt leaves the hot pending queue but stays inspectable until normal forum retention.
CREATE TABLE board_relay_failed_outbox (
  request_id UUID NOT NULL REFERENCES board_relay_requests(request_id) ON DELETE CASCADE,
  message_id TEXT NOT NULL REFERENCES board_messages(id) ON DELETE CASCADE,
  terminal BOOLEAN NOT NULL,
  message_seq BIGINT NOT NULL,
  PRIMARY KEY(request_id,message_id)
);

CREATE OR REPLACE FUNCTION board_relay_reply_added() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.reply_to IS NOT NULL THEN
    -- The worker locks requests before settling debt. Locking qualifying requests here makes a
    -- concurrent failure finish before this trigger rechecks its predicate, or waits for this
    -- reply to join the debt archive. No active outbox row can appear after terminal settlement.
    WITH active AS MATERIALIZED (
      SELECT r.request_id FROM board_relay_threads t
      JOIN board_relay_requests r ON r.request_id=t.request_id
      WHERE t.message_id=NEW.reply_to AND r.topic_id=NEW.topic AND r.reply_failure IS NULL
      FOR SHARE OF r
    )
    INSERT INTO board_relay_threads(message_id,request_id)
      SELECT NEW.id,request_id FROM active ON CONFLICT DO NOTHING;
    INSERT INTO board_relay_outbox(request_id,message_id,terminal,message_seq)
      SELECT t.request_id,NEW.id,false,NEW.seq FROM board_relay_threads t
      JOIN board_relay_requests r ON r.request_id=t.request_id
      WHERE t.message_id=NEW.id AND r.reply_failure IS NULL
      ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION board_relay_topic_closed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.state <> 'closed' AND NEW.state='closed' AND NEW.resolution IS NOT NULL THEN
    WITH active AS MATERIALIZED (
      SELECT request_id FROM board_relay_requests
      WHERE topic_id=NEW.id AND reply_failure IS NULL FOR SHARE
    )
    INSERT INTO board_relay_outbox(request_id,message_id,terminal,message_seq)
      SELECT r.request_id,b.id,true,b.seq FROM active r
      JOIN board_messages b ON b.id=NEW.resolution ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;

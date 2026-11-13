-- Agent authorship is distinct from a person and from a forum member/opener seat.
ALTER TABLE board_messages DROP CONSTRAINT board_messages_author_kind_is_known;
ALTER TABLE board_messages ADD CONSTRAINT board_messages_author_kind_is_known
  CHECK(author_kind IN ('member','opener','person','harness','agent'));
ALTER TABLE board_messages ADD CONSTRAINT board_agent_has_no_private_log
  CHECK(author_kind<>'agent' OR (conversation IS NULL AND entry IS NULL));

CREATE TABLE message_board_exports (
  source_event UUID PRIMARY KEY REFERENCES relay_execution_publications(id) ON DELETE CASCADE,
  project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  project TEXT NOT NULL,
  request_topic TEXT NOT NULL,
  channel TEXT NOT NULL,
  receiver TEXT NOT NULL CHECK(receiver IN ('board.open','board.post','board.close')),
  definition TEXT NOT NULL,
  request_message TEXT NOT NULL UNIQUE REFERENCES message_records(id) ON DELETE CASCADE,
  requester TEXT NOT NULL REFERENCES message_instances(id),
  external_participant TEXT NOT NULL REFERENCES message_instances(id),
  account TEXT NOT NULL REFERENCES admins(handle),
  relay_causation JSONB NOT NULL CHECK(relay_valid_causation(relay_causation)),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The bridge owns this union; public forum topics/messages/seats do not resolve native state.
ALTER TABLE board_relay_requests ALTER COLUMN reply_project_id DROP NOT NULL;
ALTER TABLE board_relay_requests ALTER COLUMN reply_topic DROP NOT NULL;
ALTER TABLE board_relay_requests ADD COLUMN reply_target_kind TEXT NOT NULL DEFAULT 'topic';
ALTER TABLE board_relay_requests ADD COLUMN native_source_event UUID;
ALTER TABLE board_relay_requests ADD CONSTRAINT board_reply_destination_is_complete CHECK (
  (reply_target_kind='topic' AND reply_project_id IS NOT NULL AND reply_topic IS NOT NULL AND native_source_event IS NULL)
  OR (reply_target_kind='native' AND reply_project_id IS NULL AND reply_topic IS NULL
    AND reply_generation IS NULL AND native_source_event IS NOT NULL));
ALTER TABLE board_relay_requests DROP CONSTRAINT board_relay_requests_reply_failure_check;
ALTER TABLE board_relay_requests ADD CONSTRAINT board_relay_requests_reply_failure_check
  CHECK(reply_failure IN ('MISSING','REPLACED','UNVERIFIED','INVALID_KIND','NATIVE_UNAVAILABLE'));

-- Definite native refusal has durable return debt even though no forum message exists.
CREATE TABLE message_board_outcomes (
  delivery_id UUID PRIMARY KEY,
  source_event UUID NOT NULL UNIQUE REFERENCES message_board_exports(source_event) ON DELETE CASCADE,
  return_id UUID NOT NULL UNIQUE,
  code TEXT NOT NULL CHECK(code='board.native.refused'),
  state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','published','unavailable')),
  native_message TEXT REFERENCES message_records(id) ON DELETE SET NULL,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  settled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK((state='pending')=(settled_at IS NULL))
);
CREATE INDEX message_board_outcomes_pending ON message_board_outcomes(next_attempt_at,delivery_id)
  WHERE state='pending';

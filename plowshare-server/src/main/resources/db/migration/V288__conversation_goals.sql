-- A goal belongs to an existing ordinary turn root and its separately retained admission.
CREATE TABLE conversation_goals (
  id UUID PRIMARY KEY,
  conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  admission UUID NOT NULL REFERENCES operation_admissions(id),
  executor TEXT NOT NULL,
  agent TEXT NOT NULL,
  fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  allowance UUID NOT NULL,
  objective TEXT NOT NULL CHECK (char_length(objective) BETWEEN 1 AND 16000),
  criteria TEXT CHECK (char_length(criteria) BETWEEN 1 AND 8000),
  created_order BIGINT GENERATED ALWAYS AS IDENTITY,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
  state TEXT NOT NULL DEFAULT 'READY'
    CHECK (state IN ('READY','RUNNING','PAUSING','PAUSED','BLOCKED','STOPPING','COMPLETE','CANCELLED')),
  activations INTEGER NOT NULL DEFAULT 0 CHECK (activations >= 0),
  activation_limit INTEGER NOT NULL CHECK (activation_limit > 0 AND activation_limit >= activations),
  job TEXT UNIQUE,
  last_job TEXT,
  last_turn INTEGER CHECK (last_turn > 0),
  reason TEXT,
  detail TEXT,
  no_progress INTEGER NOT NULL DEFAULT 0 CHECK (no_progress >= 0),
  answer_digest TEXT,
  CHECK (job IS NULL OR job IS NOT DISTINCT FROM last_job),
  CHECK ((job IS NOT NULL) = (state IN ('RUNNING', 'PAUSING', 'STOPPING')))
);

CREATE UNIQUE INDEX conversation_goals_one_unfinished
  ON conversation_goals(conversation) WHERE state NOT IN ('COMPLETE','CANCELLED');
CREATE INDEX conversation_goals_history
  ON conversation_goals(conversation, created_order DESC, id DESC);
CREATE INDEX conversation_goals_scheduler
  ON conversation_goals(id) WHERE state IN ('READY','RUNNING','PAUSING','STOPPING');

-- The row is first claimed and populated in one transaction. An uncommitted claim has no receipt.
CREATE TABLE conversation_goal_requests (
  executor TEXT NOT NULL,
  request UUID NOT NULL,
  fingerprint TEXT NOT NULL,
  conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  receipt JSONB,
  PRIMARY KEY(executor, request)
);

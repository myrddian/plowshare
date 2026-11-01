-- Attempt metadata is independent of inference content. A pre-wire checkpoint is a replay
-- fence, never proof that the provider accepted a request. Accounting remains the call/attempt
-- owner; UUID links can precede its asynchronous SQL projection, so they are not foreign keys.
CREATE TABLE embedding_repair_attempts (
 id UUID PRIMARY KEY,
 slot TEXT NOT NULL REFERENCES embedding_slots(slot),
 space_id TEXT NOT NULL REFERENCES embedding_spaces(id),
 store TEXT NOT NULL CHECK(store IN ('memories','digests','documents','chunks','retrieval_passages')),
 source_type TEXT NOT NULL DEFAULT '', source_id TEXT NOT NULL,
 position INTEGER NOT NULL CHECK(position>=0), source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 profile_version BIGINT,
 phase TEXT NOT NULL CHECK(phase IN ('PREPARED','ACCOUNTED','QUEUED','RUNNING','ATTEMPT_STARTED',
   'RESPONSE_RECEIVED','RESULT_VALIDATED','PUBLISHING','PUBLISHED','NOT_DISPATCHED','FAILED',
   'BLOCKED','SUPERSEDED','OUTCOME_UNKNOWN')),
 failure_phase TEXT,
 reason TEXT NOT NULL CHECK(reason IN ('NONE','LEGACY_UNCORRELATED','CAPACITY_EXHAUSTED',
   'AUTHORIZATION_REJECTED','ACCOUNTING_UNAVAILABLE','DISPATCH_FAILED','TRANSPORT_OUTCOME_UNKNOWN',
   'PROVIDER_REFUSED','INVALID_RESULT','PUBLICATION_FAILED','SOURCE_OR_PROFILE_CHANGED',
   'INTERRUPTED','PROCESS_LOST')),
 failure_type TEXT CHECK(failure_type IS NULL OR
   (length(failure_type) BETWEEN 1 AND 256 AND failure_type ~ '^[A-Za-z0-9_.$]+$')),
 call_id UUID, wire_attempt_id UUID,
 http_status INTEGER CHECK(http_status BETWEEN 100 AND 599), dispatch_outcome TEXT,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 prepared_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 provider_started_at TIMESTAMPTZ, result_received_at TIMESTAMPTZ,
 legacy_imported BOOLEAN NOT NULL DEFAULT false,
 CHECK((store='retrieval_passages' AND source_type IN ('entry','digest'))
   OR (store<>'retrieval_passages' AND source_type='' AND position=0))
);
CREATE UNIQUE INDEX embedding_repair_live_attempt ON embedding_repair_attempts
 (slot,space_id,store,source_type,source_id,position,source_revision)
 WHERE phase IN ('PREPARED','ACCOUNTED','QUEUED','RUNNING','ATTEMPT_STARTED','RESPONSE_RECEIVED',
   'RESULT_VALIDATED','PUBLISHING');
CREATE INDEX embedding_repair_call ON embedding_repair_attempts(call_id) WHERE call_id IS NOT NULL;

-- Historical markers have neither a call ID nor an original timestamp. Preserve that absence:
-- recorded_at means migration/import time, not submission time. Never infer or reset an outcome.
DO $$ DECLARE source_store TEXT;
BEGIN
 FOREACH source_store IN ARRAY ARRAY['memories','digests','documents','chunks'] LOOP
  EXECUTE format('INSERT INTO embedding_repair_attempts
    (id,slot,space_id,store,source_id,position,source_revision,phase,reason,legacy_imported)
    SELECT gen_random_uuid(),slot,space_id,%L,source_id::text,0,source_revision,
      ''OUTCOME_UNKNOWN'',''LEGACY_UNCORRELATED'',true
    FROM %I WHERE category=''RepairInFlight'' AND retry_at=''infinity''::timestamptz',
    source_store,source_store||'_embedding_failures');
 END LOOP;
END $$;
INSERT INTO embedding_repair_attempts
 (id,slot,space_id,store,source_type,source_id,position,source_revision,phase,reason,legacy_imported)
 SELECT gen_random_uuid(),slot,space_id,'retrieval_passages',source_type,source_id,position,
 source_revision,'OUTCOME_UNKNOWN','LEGACY_UNCORRELATED',true
 FROM retrieval_passages_embedding_failures
 WHERE category='RepairInFlight' AND retry_at='infinity'::timestamptz;

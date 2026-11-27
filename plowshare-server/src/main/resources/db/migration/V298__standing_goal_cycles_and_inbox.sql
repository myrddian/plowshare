-- A null cycle identifies a parked dedicated goal even when the operator pauses it.
ALTER TABLE conversation_goals
    ADD COLUMN cycle UUID,
    ADD COLUMN cycle_start_spent INTEGER,
    ADD COLUMN cycle_calls INTEGER,
    ADD COLUMN last_automatic_at TIMESTAMPTZ,
    ADD CONSTRAINT conversation_goal_cycle_shape CHECK (
      (cycle IS NULL AND cycle_start_spent IS NULL AND cycle_calls IS NULL)
      OR (cycle IS NOT NULL AND instance IS NOT NULL AND cycle_start_spent >= 0 AND cycle_calls > 0
          AND cycle_start_spent IS NOT NULL AND cycle_calls IS NOT NULL));
-- Older PAUSED rows cannot distinguish a paused READY goal from a paused IDLE goal.
-- Preserve their prior READY resume semantics and original finite window and spending.
UPDATE conversation_goals g
SET cycle=gen_random_uuid(), cycle_start_spent=0, cycle_calls=a.call_limit
FROM execution_allowances a
WHERE g.allowance=a.id AND g.instance IS NOT NULL AND g.state NOT IN ('IDLE','COMPLETE','CANCELLED')
  AND a.call_limit>0;

ALTER TABLE goal_agent_listeners DROP CONSTRAINT goal_agent_listeners_goal_project_id_topic_generation_key;
ALTER TABLE goal_agent_listeners
    ADD COLUMN batch_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN max_delay_seconds INTEGER,
    ADD COLUMN removed_at TIMESTAMPTZ,
    ADD COLUMN cursor_generation UUID,
    ADD COLUMN retired_at TIMESTAMPTZ,
    ADD COLUMN expired_after BIGINT,
    ADD COLUMN expired_through BIGINT,
    ADD CONSTRAINT goal_agent_listener_gap CHECK (
      (expired_after IS NULL AND expired_through IS NULL)
      OR (failure IS NOT DISTINCT FROM 'RETENTION_GAP' AND expired_after IS NOT NULL
          AND expired_through IS NOT NULL AND expired_after>=0 AND expired_through>expired_after)),
    ADD CONSTRAINT goal_agent_listener_policy CHECK (
      (batch_count=1 AND max_delay_seconds IS NULL)
      OR (batch_count BETWEEN 2 AND 16 AND max_delay_seconds BETWEEN 1 AND 86400
          AND max_delay_seconds IS NOT NULL)),
    ADD CONSTRAINT goal_agent_listener_retirement CHECK (retired_at IS NULL OR removed_at IS NOT NULL);
UPDATE goal_agent_listeners l SET cursor_generation=s.generation
FROM relay_subscriptions s JOIN relay_topics t ON t.scope_key=s.scope_key AND t.name=s.topic
WHERE t.project_id=l.project_id AND t.name=l.topic AND t.generation=l.generation
  AND s.subscriber='goal.'||l.id::text AND s.reader_account IS NULL;
UPDATE goal_agent_listeners SET failure='SOURCE_CHANGED'
WHERE cursor_generation IS NULL AND failure IS NULL;
CREATE UNIQUE INDEX goal_agent_listener_incarnation
    ON goal_agent_listeners(goal,project_id,topic,generation) WHERE removed_at IS NULL;
CREATE INDEX goal_agent_listener_retirement_queue ON goal_agent_listeners(id)
    WHERE removed_at IS NOT NULL AND retired_at IS NULL;

CREATE TABLE goal_agent_relay_batches (
    id UUID PRIMARY KEY,
    listener UUID NOT NULL REFERENCES goal_agent_listeners(id) ON DELETE CASCADE,
    first_sequence BIGINT UNIQUE,
    first_at TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL CHECK (deadline >= first_at),
    member_count INTEGER NOT NULL DEFAULT 0 CHECK (member_count BETWEEN 0 AND 16),
    state TEXT NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','READY','CLAIMED','SETTLED','DISARMED')),
    job TEXT,
    UNIQUE (listener,id),
    CHECK ((member_count=0) = (first_sequence IS NULL)),
    CHECK (state='OPEN' OR member_count>0),
    CHECK ((state='CLAIMED' AND job IS NOT NULL)
      OR state='SETTLED' OR (state IN ('OPEN','READY','DISARMED') AND job IS NULL))
);
CREATE UNIQUE INDEX goal_agent_relay_open_batch ON goal_agent_relay_batches(listener) WHERE state='OPEN';
CREATE UNIQUE INDEX goal_agent_relay_claimed_job ON goal_agent_relay_batches(job) WHERE state='CLAIMED';
CREATE INDEX goal_agent_relay_due_batch ON goal_agent_relay_batches(deadline,id) WHERE state='OPEN';

ALTER TABLE goal_agent_inputs
    ADD COLUMN batch UUID,
    ADD COLUMN claimed_job TEXT,
    ADD COLUMN disposition TEXT,
    ADD CONSTRAINT goal_agent_input_batch_membership FOREIGN KEY (listener,batch)
        REFERENCES goal_agent_relay_batches(listener,id);
-- Each retained pre-batch Relay event keeps its original queue and settlement position.
INSERT INTO goal_agent_relay_batches(id,listener,first_sequence,first_at,deadline,member_count,state)
SELECT gen_random_uuid(),i.listener,i.sequence,transaction_timestamp(),transaction_timestamp(),1,
       CASE WHEN i.settled THEN 'SETTLED' WHEN l.failure IS NULL THEN 'READY' ELSE 'DISARMED' END
FROM goal_agent_inputs i JOIN goal_agent_listeners l ON l.id=i.listener;
UPDATE goal_agent_inputs i SET batch=b.id
FROM goal_agent_relay_batches b WHERE b.first_sequence=i.sequence;
UPDATE goal_agent_inputs
SET disposition=CASE WHEN schedule_refused THEN 'STALE_SCHEDULE' ELSE 'HANDLED' END
WHERE settled;
UPDATE goal_agent_inputs i SET settled=TRUE,disposition=CASE
    WHEN g.state IN ('RUNNING','PAUSING','STOPPING','COMPLETE','CANCELLED')
      OR (g.state='BLOCKED' AND g.reason='UNCONFIRMED_TURN') THEN 'UNCERTAIN'
    ELSE 'SOURCE_UNAVAILABLE' END
FROM goal_agent_relay_batches b, conversation_goals g
WHERE i.batch=b.id AND b.state='DISARMED' AND g.id=i.goal;
ALTER TABLE goal_agent_inputs
    ADD CONSTRAINT goal_agent_input_settlement CHECK (
      (settled=(disposition IS NOT NULL))
      AND (disposition IS NULL OR disposition IN (
        'HANDLED','STALE_SCHEDULE','DISARMED','GOAL_ENDED','SOURCE_UNAVAILABLE','TURN_FAILED','UNCERTAIN'))
      AND ((listener IS NULL) = (batch IS NULL)));
CREATE UNIQUE INDEX goal_agent_input_claim ON goal_agent_inputs(claimed_job) WHERE claimed_job IS NOT NULL;
CREATE INDEX goal_agent_input_batch_members ON goal_agent_inputs(batch,sequence) WHERE batch IS NOT NULL;
CREATE INDEX goal_agent_input_schedule_lookup ON goal_agent_inputs(goal,schedule_firing)
    WHERE NOT settled AND claimed_job IS NULL AND schedule_firing IS NOT NULL;

CREATE TABLE goal_agent_unlisten_requests (
    executor TEXT NOT NULL,
    request UUID NOT NULL,
    goal UUID NOT NULL REFERENCES conversation_goals(id) ON DELETE CASCADE,
    binding UUID NOT NULL REFERENCES goal_agent_listeners(id) ON DELETE CASCADE,
    prior_version INTEGER NOT NULL CHECK (prior_version>0),
    PRIMARY KEY (executor,request)
);

-- V297 recorded the job without the selected input. New direct steering could overtake
-- the old claimed item in the queue, so the exact input is unknowable for interrupted work.
-- Retain the old backlog as uncertain without redispatch or a successful receipt.
UPDATE goal_agent_inputs i SET settled=TRUE,disposition='UNCERTAIN'
FROM conversation_goals g
WHERE g.id=i.goal AND NOT i.settled
  AND (g.state IN ('RUNNING','PAUSING','STOPPING','COMPLETE','CANCELLED')
    OR (g.state='BLOCKED' AND g.reason='UNCONFIRMED_TURN'));
UPDATE goal_agent_relay_batches b SET state='DISARMED'
FROM goal_agent_inputs i
WHERE i.sequence=b.first_sequence AND i.disposition='UNCERTAIN' AND b.state='READY';
UPDATE goal_agent_listeners l SET removed_at=transaction_timestamp()
FROM conversation_goals g
WHERE g.id=l.goal AND g.state IN ('COMPLETE','CANCELLED','STOPPING');

-- Legacy listeners without their original broker group cannot safely adopt the broker head.
UPDATE conversation_goals g SET state='PAUSED',reason='AUTHORITY_OR_SOURCE_CHANGED',
    detail='A retained Relay listener lost its original subscription.',
    version=version+1
WHERE g.state IN ('IDLE','READY') AND EXISTS (
    SELECT 1 FROM goal_agent_listeners l WHERE l.goal=g.id AND l.removed_at IS NULL
      AND l.cursor_generation IS NULL AND l.failure='SOURCE_CHANGED');

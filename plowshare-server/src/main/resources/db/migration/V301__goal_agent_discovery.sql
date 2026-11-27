-- One explicit committed completion per dedicated Goal, independent of its next cycle.
-- V300 did not retain every standing completion or exact evidence. Leave those results absent.
CREATE TABLE goal_agent_latest_results (
    goal UUID PRIMARY KEY REFERENCES conversation_goals(id) ON DELETE CASCADE,
    cycle UUID,
    job TEXT NOT NULL CHECK (job <> ''),
    turn INTEGER NOT NULL CHECK (turn > 0),
    summary TEXT NOT NULL CHECK (char_length(summary) BETWEEN 1 AND 4000),
    evidence TEXT[] NOT NULL CHECK (cardinality(evidence) BETWEEN 1 AND 12
      AND array_position(evidence,NULL) IS NULL)
);

CREATE INDEX goal_agents_discovery_order ON conversation_goals(executor,created_order DESC)
    WHERE instance IS NOT NULL;
CREATE INDEX goal_agents_pending_batch ON goal_agent_relay_batches(listener,first_sequence)
    WHERE state IN ('OPEN','READY') AND member_count>0;

-- Java ZoneId keeps region names unchanged and canonicalizes only prefixed or bare fixed offsets.
-- Both stored inputs were validated by the Scheduler. Preserve that identity when projecting
-- a timer for a bound Goal. A same-owner legacy redefinition may change the runtime zone.
CREATE FUNCTION goal_schedule_timer_zone_matches(file_zone TEXT,timer_zone TEXT)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE AS $$
  WITH parsed AS (
    SELECT coalesce(
      regexp_match(file_zone,'^(UTC|GMT|UT)?([+-])([0-9]{2})(?:(?::([0-9]{2})(?::([0-9]{2}))?)|([0-9]{2})([0-9]{2})?)?$'),
      regexp_match(file_zone,'^(UTC|GMT|UT)?([+-])([0-9])$')) AS p
  ), offset_parts AS (
    SELECT coalesce(p[1],'') AS prefix,p[2] AS sign,lpad(p[3],2,'0') AS hours,
      coalesce(p[4],p[6],'00') AS minutes,coalesce(p[5],p[7],'00') AS seconds
    FROM parsed WHERE p IS NOT NULL
  )
  SELECT file_zone=timer_zone OR EXISTS (
    SELECT 1 FROM offset_parts
    WHERE timer_zone=CASE WHEN hours||minutes||seconds='000000'
      THEN CASE WHEN prefix='' THEN 'Z' ELSE prefix END
      ELSE prefix||sign||hours||':'||minutes||CASE WHEN seconds='00' THEN '' ELSE ':'||seconds END
    END);
$$;

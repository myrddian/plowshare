-- Historical topics and private messaging transports have no public swarm selection.
-- New public roots capture one definition; children copy it in the opening transaction.
ALTER TABLE board_topics ADD COLUMN swarm_selection jsonb;
ALTER TABLE board_topics ADD CONSTRAINT board_topic_swarm_selection CHECK (
  swarm_selection IS NULL OR COALESCE(
    jsonb_typeof(swarm_selection) = 'object'
    AND jsonb_typeof(swarm_selection->'name') = 'string'
    AND length(swarm_selection->>'name') <= 64
    AND (swarm_selection->>'name') ~ '^[a-z][a-z0-9]*([-_][a-z0-9]+)*$'
    AND (swarm_selection->>'revision') ~ '^[a-f0-9]{64}$'
    AND jsonb_typeof(swarm_selection->'description') = 'string'
    AND length(swarm_selection->>'description') <= 4096
    AND jsonb_typeof(swarm_selection->'members') = 'array'
    AND jsonb_array_length(swarm_selection->'members') BETWEEN 1 AND 64
    AND jsonb_typeof(swarm_selection->'budget') = 'number'
    AND (swarm_selection->>'budget') ~ '^[0-9]+$'
    AND (swarm_selection->>'budget')::numeric BETWEEN 2 AND 2147483647,
    false
  )
);

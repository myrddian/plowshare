-- The schedule owner retains the generation of an effective execution definition.
ALTER TABLE schedule_files ADD COLUMN generation UUID NOT NULL DEFAULT gen_random_uuid();

CREATE FUNCTION schedule_file_execution_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.definition - 'paused' IS DISTINCT FROM NEW.definition - 'paused'
      OR OLD.status IS DISTINCT FROM NEW.status
      OR COALESCE(OLD.paused_override, (OLD.definition->>'paused')::boolean)
          IS DISTINCT FROM COALESCE(NEW.paused_override, (NEW.definition->>'paused')::boolean) THEN
    NEW.generation := gen_random_uuid();
  ELSE
    NEW.generation := OLD.generation;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER schedule_file_execution_generation BEFORE UPDATE ON schedule_files
  FOR EACH ROW EXECUTE FUNCTION schedule_file_execution_generation();

-- The existing file projection is the binding. Pausing preserves the binding; it cannot fire.
-- Use the exact source owner, project, plain action and addressed persistent Goal instance.
CREATE INDEX schedule_files_goal_destination ON schedule_files ((definition #>> '{target,to}'))
  WHERE status = 'active' AND definition #>> '{action,kind}' = 'agent'
    AND definition #>> '{target,kind}' = 'message';
CREATE VIEW goal_schedule_bindings AS
  SELECT g.id AS goal, i.id AS instance, f.internal_name AS schedule, f.generation,
    COALESCE(f.paused_override, (f.definition->>'paused')::boolean) AS paused
  FROM schedule_files f
  JOIN schedule_sources s ON s.id = f.source_id
  JOIN projects p ON p.id = s.project_id
  JOIN message_instances i ON i.id = f.definition #>> '{target,to}'
    AND i.account = s.account AND i.project = p.name AND i.lifetime = 'persistent' AND i.active
  JOIN conversation_goals g ON g.instance = i.id AND g.conversation = i.conversation
    AND g.executor = i.account AND g.agent = i.agent
  WHERE f.status = 'active'
    AND g.state NOT IN ('STOPPING', 'COMPLETE', 'CANCELLED')
    AND f.definition #>> '{action,kind}' = 'agent'
    AND f.definition #>> '{action,agent}' = i.agent
    AND f.definition #>> '{target,kind}' = 'message'
    AND f.definition #>> '{target,route}' IS NULL
    AND COALESCE(f.definition #>> '{target,project}', p.name) = p.name;

-- This evidence stays private to the scheduler and Goal inbox. Legacy occurrences have none.
ALTER TABLE firings ADD COLUMN schedule_generation UUID CHECK
  (schedule_generation IS NULL OR (schedule IS NOT NULL AND trigger IS NOT NULL AND schedule = trigger));
ALTER TABLE goal_agent_inputs ADD COLUMN schedule_firing TEXT UNIQUE REFERENCES firings(id),
  ADD COLUMN schedule_refused BOOLEAN NOT NULL DEFAULT FALSE,
  ADD CONSTRAINT goal_schedule_input_shape CHECK
    ((schedule_firing IS NULL OR message IS NOT NULL)
      AND (NOT schedule_refused OR (schedule_firing IS NOT NULL AND settled)));

-- Direct steering precedes scheduled work, which shares the automatic input order with Relay.
DROP INDEX goal_agent_inputs_ready;
CREATE INDEX goal_agent_inputs_ready
  ON goal_agent_inputs (goal, ((message IS NULL OR schedule_firing IS NOT NULL)), sequence)
  WHERE NOT settled;

-- Schedule removal, listener removal and inbox disposal share one terminal transition.
-- The owner holds the Goal row lock. A retained cycle or already committed steering still owns work.
CREATE FUNCTION goal_finish_unbound_parked(goal_id UUID) RETURNS INTEGER
LANGUAGE sql AS $$
    WITH closed AS (
        UPDATE conversation_goals g SET state='COMPLETE',reason=NULL,version=version+1
        WHERE g.id=goal_id AND g.state IN ('IDLE','PAUSED') AND g.cycle IS NULL
          AND NOT EXISTS (SELECT 1 FROM goal_schedule_bindings b WHERE b.goal=g.id)
          AND NOT EXISTS (
            SELECT 1 FROM goal_agent_listeners l WHERE l.goal=g.id AND l.removed_at IS NULL)
          AND NOT EXISTS (
            SELECT 1 FROM goal_agent_inputs i WHERE i.goal=g.id AND NOT i.settled
              AND i.claimed_job IS NULL AND i.message IS NOT NULL AND i.schedule_firing IS NULL)
        RETURNING g.id,g.version
    ), settled AS (
        UPDATE goal_agent_inputs i SET settled=TRUE,disposition='GOAL_ENDED'
        FROM closed g WHERE i.goal=g.id AND NOT i.settled AND i.claimed_job IS NULL
          AND i.message IS NOT NULL
    ), staged AS (
        INSERT INTO goal_return_intents(id,goal,kind,goal_version,body)
        SELECT gen_random_uuid(),g.id,'RESULT',g.version,
               COALESCE(result.summary,'The Goal Agent completed after its final wake was removed.')
        FROM closed g JOIN goal_return_routes route ON route.goal=g.id
        LEFT JOIN goal_agent_latest_results result ON result.goal=g.id
    )
    SELECT version FROM closed
$$;

-- Older versions could complete these Goals without closing their passive launch receipts.
-- Recover pending bot outcomes while leaving already recorded returns and human notices alone.
INSERT INTO goal_return_intents(id,goal,kind,goal_version,body)
SELECT gen_random_uuid(),g.id,'RESULT',g.version,
       COALESCE(result.summary,'The Goal Agent completed after its final wake was removed.')
FROM goal_return_routes route
JOIN conversation_goals g ON g.id=route.goal
JOIN message_routes launch ON launch.message=route.launch_message
LEFT JOIN goal_agent_latest_results result ON result.goal=g.id
WHERE route.audience='BOT' AND g.state='COMPLETE' AND launch.handled_at IS NULL
  AND launch.termination IS NULL AND launch.reply_expected
  AND launch.reply_to IS NULL AND NOT launch.generated
  AND launch.sender=route.caller_instance AND launch.recipient=route.goal_instance
  AND launch.call_id LIKE 'goal-return:launch:%'
  AND NOT EXISTS (SELECT 1 FROM goal_return_intents i WHERE i.goal=g.id AND i.kind='RESULT');

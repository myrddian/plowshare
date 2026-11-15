-- A handling job may fail after its explicit final reply was sent. Preserve that final reply and
-- retain one separately correlated harness error-status notice. It never requests another reply.
ALTER TABLE message_routes DROP CONSTRAINT board_message_routes_check1;
ALTER TABLE message_routes ADD CONSTRAINT message_routes_generated_outcome
 CHECK (NOT generated OR (reply_to IS NOT NULL AND ending IS NOT NULL
   AND (final OR (NOT reply_expected AND ending IN
     ('UNAVAILABLE','SUB_AGENT_FAILED','SESSION_GONE','STUCK','CALL_FAILURES')))));
CREATE UNIQUE INDEX message_routes_failure_status_once
 ON message_routes(reply_to) WHERE generated AND NOT final;

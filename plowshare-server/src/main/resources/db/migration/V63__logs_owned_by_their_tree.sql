-- A log opened before V61 recorded no owner, and a turn the harness speaks into one (a run's
-- question, delivered to the bot that started the run) then acts for nobody: measured 2026-09-29,
-- a bot's answer to its own run's question was refused as "not owned by this account". The owner
-- is known wherever a run was started from or conducted in the log, so it is written there, and a
-- delegated child takes its root's -- the same inheritance ConversationStore's open gives it.
-- A log with no run anywhere in its tree stays unowned: there is nothing to read it from.

UPDATE conversations c SET owner_handle = o.caller_handle
FROM (SELECT DISTINCT ON (caller_conversation) caller_conversation, caller_handle
      FROM orchestrations
      WHERE caller_conversation IS NOT NULL AND caller_handle IS NOT NULL
      ORDER BY caller_conversation, created_at) o
WHERE c.id = o.caller_conversation AND c.owner_handle IS NULL;

UPDATE conversations c SET owner_handle = o.caller_handle
FROM orchestrations o
WHERE c.id = o.conductor_conversation AND c.owner_handle IS NULL AND o.caller_handle IS NOT NULL;

WITH RECURSIVE tree (id, owner) AS (
    SELECT id, owner_handle FROM conversations
    WHERE parent_id IS NULL AND owner_handle IS NOT NULL
    UNION ALL
    SELECT child.id, COALESCE(child.owner_handle, tree.owner)
    FROM conversations child JOIN tree ON child.parent_id = tree.id
)
UPDATE conversations c SET owner_handle = tree.owner
FROM tree
WHERE c.id = tree.id AND c.owner_handle IS NULL;

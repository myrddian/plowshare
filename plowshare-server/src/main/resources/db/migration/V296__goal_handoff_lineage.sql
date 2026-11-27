-- A chat can change speakers. The Goal Agent keeps the original caller and conversation as
-- historical provenance. Deleting the source log must not be blocked by that history.
ALTER TABLE conversation_goals
    ADD COLUMN calling_agent TEXT,
    ADD COLUMN calling_conversation TEXT,
    ADD CONSTRAINT conversation_goal_handoff_source_check CHECK (
        (calling_agent IS NULL AND calling_conversation IS NULL)
        OR (calling_agent IS NOT NULL AND calling_conversation IS NOT NULL
            AND instance IS NOT NULL AND calling_conversation <> conversation
            AND char_length(calling_agent) BETWEEN 1 AND 1024
            AND char_length(calling_conversation) BETWEEN 1 AND 1024)
    );

-- A clarification may be answered in a new turn with a different admission. Only the
-- original skill invocation's sealed admission and capability snapshot may authorize its goal.
ALTER TABLE skill_executions
    ADD COLUMN handoff_operation UUID REFERENCES operation_admissions(id),
    ADD COLUMN handoff_capabilities TEXT,
    ADD CONSTRAINT skill_goal_handoff_authority_check CHECK (
        (handoff_operation IS NULL AND handoff_capabilities IS NULL)
        OR (handoff_operation IS NOT NULL AND handoff_capabilities IS NOT NULL
            AND skill = 'goal' AND mode = 'DIRECT'
            AND handoff_capabilities ~ '^[0-9a-f]{64}$')
    );

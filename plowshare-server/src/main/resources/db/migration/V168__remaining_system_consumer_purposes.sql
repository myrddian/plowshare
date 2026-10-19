-- Internal bindings for existing ask and embedding-repair consumers. No public role or API.
ALTER TABLE system_operations DROP CONSTRAINT system_operations_purpose_check;
ALTER TABLE system_operations ADD CONSTRAINT system_operations_purpose_check CHECK(purpose IN ('DOCUMENT_PREPARATION','DOCUMENT_ASK','EMBEDDING_REPAIR','CONVERSATION_COMPACTION','SKILL_SUMMARY','SCRIBE','LEARNING','MEMORY_DIGEST','MEMORY_CURATION','STUCK_ADVISOR','CALL_VALIDATOR','COMMAND_JUDGE','SCHEDULE_READER','SEMANTIC_QUERY','MEMORY_WRITE'));

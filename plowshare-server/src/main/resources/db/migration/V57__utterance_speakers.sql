-- Who spoke an utterance: implementation rationale §2.
--
-- Two nullable columns and no data migration. An utterance written before this file has no
-- speaker and is read back as a person's (agents.Speaker.read), which is what every one of them
-- was taken for until now. No existing CHECK is rewritten; four new ones bound the two columns.

ALTER TABLE entries ADD COLUMN speaker TEXT;

ALTER TABLE entries ADD COLUMN speaker_name TEXT;

ALTER TABLE entries ADD CONSTRAINT entries_speaker_is_known
    CHECK (speaker IS NULL OR speaker IN ('person', 'harness'));

ALTER TABLE entries ADD CONSTRAINT entries_only_an_utterance_has_a_speaker
    CHECK (speaker IS NULL OR kind = 'utterance');

ALTER TABLE entries ADD CONSTRAINT entries_a_speaker_name_belongs_to_a_speaker
    CHECK (speaker_name IS NULL OR speaker IS NOT NULL);

ALTER TABLE entries ADD CONSTRAINT entries_the_harness_names_its_source
    CHECK (speaker IS DISTINCT FROM 'harness' OR speaker_name IS NOT NULL);

COMMENT ON COLUMN entries.speaker IS
    'Who spoke this utterance: person or harness. NULL on every other kind, and on an utterance '
    'written before V57, which is read as a person''s.';

COMMENT ON COLUMN entries.speaker_name IS
    'For a person, their handle (NULL when nobody knew it). For the harness, its source: '
    '"orchestration <id>", "approval <id>", "event <id>" or "harness".';

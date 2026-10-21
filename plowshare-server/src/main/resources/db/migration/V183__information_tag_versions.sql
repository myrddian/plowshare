-- User tags are resource state, independent of content revision and processing generation.
ALTER TABLE information_resources ADD COLUMN tags_version BIGINT NOT NULL DEFAULT 0
    CHECK (tags_version BETWEEN 0 AND 9007199254740991);

-- Legacy replacement and import remain supported writers. Every actual tag change must advance
-- the same version, so an old client cannot silently evade a new client's precondition.
CREATE FUNCTION information_advance_tags_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.tags_version = OLD.tags_version + CASE WHEN NEW.tags IS DISTINCT FROM OLD.tags THEN 1 ELSE 0 END;
    RETURN NEW;
END $$;
CREATE TRIGGER information_tags_version BEFORE UPDATE ON information_resources
    FOR EACH ROW EXECUTE FUNCTION information_advance_tags_version();

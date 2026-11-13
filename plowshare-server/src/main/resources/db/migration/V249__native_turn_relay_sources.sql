-- Different message definitions/roots may share one conversation. The conversation's first
-- root remains historical evidence; each causal native turn owns its actual immutable source.
CREATE TABLE turn_relay_sources (
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  turn_ordinal INTEGER NOT NULL CHECK(turn_ordinal>0),
  relay_causation JSONB NOT NULL CHECK(relay_valid_causation(relay_causation)),
  PRIMARY KEY(conversation_id,turn_ordinal)
);

CREATE OR REPLACE FUNCTION relay_inherit_job() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE inherited JSONB;
BEGIN
  IF NEW.conversation_id IS NOT NULL THEN
    SELECT relay_causation INTO inherited FROM conversations
      WHERE id=NEW.conversation_id FOR UPDATE;
    IF inherited IS NOT NULL AND NEW.relay_causation IS NOT NULL
       AND inherited<>NEW.relay_causation
       AND NOT EXISTS(SELECT 1 FROM turn_relay_sources s
         WHERE s.conversation_id=NEW.conversation_id AND s.relay_causation=NEW.relay_causation) THEN
      RAISE EXCEPTION 'Job causation conflicts with its conversation without a retained native turn source';
    END IF;
  END IF;
  NEW.relay_causation=COALESCE(NEW.relay_causation,inherited,
    jsonb_build_object('rootId','job:'||NEW.id,'parentId',NULL,'depth',0));
  IF NEW.conversation_id IS NOT NULL THEN
    UPDATE conversations SET relay_causation=NEW.relay_causation
      WHERE id=NEW.conversation_id AND relay_causation IS NULL;
  END IF;
  RETURN NEW;
END $$;

-- Retention may delete a source with its conversation; no caller can rewrite historical ancestry.
CREATE FUNCTION retain_turn_relay_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Native turn ancestry is immutable'; END $$;
CREATE TRIGGER retain_turn_relay_source BEFORE UPDATE ON turn_relay_sources
  FOR EACH ROW EXECUTE FUNCTION retain_turn_relay_source();

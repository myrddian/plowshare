ALTER TABLE digests ADD COLUMN embedding_generation text;
-- Derived text is invalidated in the same transaction as its source. No network
-- call occurs on the write path. A polling worker repairs committed sources.
CREATE TABLE retrieval_sources (
 source_type text NOT NULL CHECK (source_type IN ('entry','digest')),
 source_id text NOT NULL,
 conversation_id text,
 ordinal integer,
 source_hash text NOT NULL,
 generation text,
 status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','ready','failed')),
 next_position integer NOT NULL DEFAULT 0,
 attempts integer NOT NULL DEFAULT 0,
 retry_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error text,
 PRIMARY KEY(source_type,source_id)
);
CREATE INDEX retrieval_pending ON retrieval_sources(status,retry_at);
CREATE TABLE retrieval_passages (
 source_type text NOT NULL,
 source_id text NOT NULL,
 position integer NOT NULL,
 passage text NOT NULL,
 embedding vector(768) NOT NULL,
 PRIMARY KEY(source_type,source_id,position),
 FOREIGN KEY(source_type,source_id) REFERENCES retrieval_sources ON DELETE CASCADE
);
-- Exact filtered ranking deliberately has no ANN index: scope predicates are
-- applied before distance ranking, preserving filtered recall.
CREATE FUNCTION retrieval_entry_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key text;
BEGIN
 IF TG_OP='DELETE' THEN
  DELETE FROM retrieval_sources WHERE source_type='entry' AND source_id=OLD.conversation_id || ':' || OLD.ordinal;
  RETURN OLD;
 END IF;
 key := NEW.conversation_id || ':' || NEW.ordinal;
 IF TG_OP='UPDATE' AND ROW(OLD.content,OLD.role,OLD.ejected_at) IS NOT DISTINCT FROM ROW(NEW.content,NEW.role,NEW.ejected_at) THEN
  RETURN NEW;
 END IF;
 DELETE FROM retrieval_sources WHERE source_type='entry' AND source_id=key;
 IF NEW.role IS NOT NULL AND NEW.content IS NOT NULL AND btrim(NEW.content)<>'' AND NEW.ejected_at IS NULL THEN
  INSERT INTO retrieval_sources(source_type,source_id,conversation_id,ordinal,source_hash)
  VALUES('entry',key,NEW.conversation_id,NEW.ordinal,md5(NEW.content));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER retrieval_entry AFTER INSERT OR UPDATE OR DELETE ON entries FOR EACH ROW EXECUTE FUNCTION retrieval_entry_changed();
CREATE FUNCTION retrieval_digest_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  DELETE FROM retrieval_sources WHERE source_type='digest' AND source_id=OLD.id;
  RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' AND ROW(OLD.summary,OLD.revision,OLD.stale_at) IS NOT DISTINCT FROM ROW(NEW.summary,NEW.revision,NEW.stale_at) THEN
  RETURN NEW;
 END IF;
 DELETE FROM retrieval_sources WHERE source_type='digest' AND source_id=NEW.id;
 INSERT INTO retrieval_sources(source_type,source_id,source_hash) VALUES('digest',NEW.id,md5(NEW.summary || ':' || NEW.revision));
 RETURN NEW;
END $$;
CREATE TRIGGER retrieval_digest AFTER INSERT OR UPDATE OR DELETE ON digests FOR EACH ROW EXECUTE FUNCTION retrieval_digest_changed();
INSERT INTO retrieval_sources(source_type,source_id,conversation_id,ordinal,source_hash)
 SELECT 'entry',conversation_id || ':' || ordinal,conversation_id,ordinal,md5(content)
 FROM entries WHERE role IS NOT NULL AND content IS NOT NULL AND btrim(content)<>'' AND ejected_at IS NULL;
INSERT INTO retrieval_sources(source_type,source_id,source_hash)
 SELECT 'digest',id,md5(summary || ':' || revision) FROM digests;
CREATE FUNCTION retrieval_sources_truncated() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 DELETE FROM retrieval_sources WHERE source_type=CASE WHEN TG_TABLE_NAME='entries' THEN 'entry' ELSE 'digest' END;
 RETURN NULL;
END $$;
CREATE TRIGGER retrieval_entries_truncated AFTER TRUNCATE ON entries FOR EACH STATEMENT EXECUTE FUNCTION retrieval_sources_truncated();
CREATE TRIGGER retrieval_digests_truncated AFTER TRUNCATE ON digests FOR EACH STATEMENT EXECUTE FUNCTION retrieval_sources_truncated();

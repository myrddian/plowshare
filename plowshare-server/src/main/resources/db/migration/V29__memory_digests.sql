-- Durable navigation material. A fold and its archive copy commit together.
CREATE TABLE digests (
 id TEXT PRIMARY KEY,
 project_id BIGINT REFERENCES projects(id),
 depth INT NOT NULL CHECK (depth >= 0),
 summary TEXT NOT NULL CHECK (btrim(summary) <> ''),
 embedding vector(768),
 formed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 stale_at TIMESTAMPTZ,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX digests_home_depth ON digests(project_id, depth);
CREATE TABLE digest_memories (
 digest_id TEXT PRIMARY KEY REFERENCES digests(id),
 memory_id TEXT NOT NULL UNIQUE REFERENCES memories(id)
);
CREATE TABLE digest_children (
 parent_id TEXT NOT NULL REFERENCES digests(id),
 child_id TEXT NOT NULL UNIQUE REFERENCES digests(id),
 PRIMARY KEY(parent_id, child_id), CHECK(parent_id <> child_id)
);
CREATE TABLE digest_spans (
 digest_id TEXT PRIMARY KEY REFERENCES digests(id),
 conversation_id TEXT NOT NULL REFERENCES conversations(id),
 since_turn INT NOT NULL CHECK(since_turn >= 0),
 through_turn INT NOT NULL CHECK(through_turn > since_turn),
 summary_ordinal INT,
 UNIQUE(conversation_id, summary_ordinal)
);
CREATE TABLE memory_provenance (
 memory_id TEXT NOT NULL REFERENCES memories(id),
 conversation_id TEXT NOT NULL REFERENCES conversations(id),
 turn_ordinal INT NOT NULL CHECK(turn_ordinal >= 1),
 PRIMARY KEY(memory_id, conversation_id, turn_ordinal)
);
CREATE TABLE digest_revisions (
 digest_id TEXT NOT NULL REFERENCES digests(id),
 revision BIGINT NOT NULL,
 summary TEXT NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(digest_id, revision)
);
CREATE FUNCTION digest_validate_edge() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p digests; c digests;
BEGIN
 SELECT * INTO STRICT p FROM digests WHERE id=NEW.parent_id;
 SELECT * INTO STRICT c FROM digests WHERE id=NEW.child_id;
 IF p.project_id IS DISTINCT FROM c.project_id OR p.depth <= c.depth THEN
  RAISE EXCEPTION 'digest edges must descend within one home';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER digest_edge BEFORE INSERT OR UPDATE ON digest_children
 FOR EACH ROW EXECUTE FUNCTION digest_validate_edge();

CREATE FUNCTION digest_dirty(start_id TEXT) RETURNS void LANGUAGE sql AS $$
 WITH RECURSIVE ancestors(id) AS (
 SELECT start_id UNION SELECT e.parent_id FROM digest_children e JOIN ancestors a ON e.child_id=a.id
 ) UPDATE digests SET stale_at=CURRENT_TIMESTAMP, revision=revision+1
 WHERE id IN (SELECT id FROM ancestors);
$$;
CREATE FUNCTION digest_memory_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE leaf TEXT := 'dig_m_' || NEW.id;
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO digests(id,project_id,depth,summary) VALUES(leaf,NEW.project_id,0,NEW.summary || E'\n' || NEW.scope);
  INSERT INTO digest_memories VALUES(leaf,NEW.id);
 ELSIF ROW(OLD.summary,OLD.scope,OLD.body,OLD.state,OLD.project_id)
       IS DISTINCT FROM ROW(NEW.summary,NEW.scope,NEW.body,NEW.state,NEW.project_id) THEN
  PERFORM digest_dirty(leaf);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER digest_memory AFTER INSERT OR UPDATE ON memories
 FOR EACH ROW EXECUTE FUNCTION digest_memory_changed();
INSERT INTO digests(id,project_id,depth,summary)
 SELECT 'dig_m_'||id,project_id,0,summary||E'\n'||scope FROM memories;
INSERT INTO digest_memories SELECT 'dig_m_'||id,id FROM memories;

CREATE FUNCTION digest_fold_written() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE leaf TEXT := 'dig_f_'||md5(NEW.conversation_id||':'||NEW.ordinal);
DECLARE home BIGINT;
DECLARE lower_turn INT;
BEGIN
 IF NEW.kind <> 'summary' THEN RETURN NEW; END IF;
 SELECT project_id INTO home FROM conversations WHERE id=NEW.conversation_id;
 SELECT COALESCE(max(turn_ordinal),0) INTO lower_turn FROM entries
 WHERE conversation_id=NEW.conversation_id AND kind='summary'
 AND turn_ordinal < NEW.turn_ordinal AND superseded_by IS NULL;
 INSERT INTO digests(id,project_id,depth,summary) VALUES(leaf,home,0,NEW.content);
 INSERT INTO digest_spans VALUES(leaf,NEW.conversation_id,lower_turn,NEW.turn_ordinal,NEW.ordinal);
 RETURN NEW;
END $$;
CREATE TRIGGER digest_fold AFTER INSERT ON entries
 FOR EACH ROW EXECUTE FUNCTION digest_fold_written();
-- Existing folds are copied without another model call. Already-ejected summaries
-- remain explicitly uncovered; retention must never pretend their text survived.
INSERT INTO digests(id,project_id,depth,summary)
 SELECT 'dig_f_'||md5(e.conversation_id||':'||e.ordinal),c.project_id,0,e.content
 FROM entries e JOIN conversations c ON c.id=e.conversation_id
 WHERE e.kind='summary' AND e.content IS NOT NULL;
INSERT INTO digest_spans
 SELECT 'dig_f_'||md5(e.conversation_id||':'||e.ordinal),e.conversation_id,
 COALESCE((SELECT max(p.turn_ordinal) FROM entries p WHERE p.conversation_id=e.conversation_id
  AND p.kind='summary' AND p.turn_ordinal<e.turn_ordinal),0),e.turn_ordinal,e.ordinal
 FROM entries e WHERE e.kind='summary' AND e.content IS NOT NULL;

-- Memory operations own allowances, while their traces use the curator retention policy.
ALTER TABLE conversations DROP CONSTRAINT conversations_origin_is_known;
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
 CHECK(origin IN ('turn','delegation','curator','submission','memory'));
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_owned_or_shared;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared
 CHECK((origin IN ('turn','submission','memory')) = (budget_total IS NOT NULL));

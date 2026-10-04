-- Human curation belongs to a resource; generated navigation belongs to its exact revision.
ALTER TABLE information_resources ADD COLUMN tags JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(tags)='array' AND jsonb_array_length(tags)<=32);
ALTER TABLE information_revisions ADD COLUMN auto_tag JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(auto_tag)='array' AND jsonb_array_length(auto_tag)<=32);
ALTER TABLE information_revisions ADD COLUMN auto_tag_generated BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX information_resources_tags ON information_resources USING GIN(tags);
CREATE INDEX information_revisions_auto_tag ON information_revisions USING GIN(auto_tag);
ALTER TABLE information_steps DROP CONSTRAINT information_steps_stage_check;
ALTER TABLE information_steps ADD CONSTRAINT information_steps_stage_check
    CHECK (stage IN ('extract','derive','embed','summarise','summary_embed','autoTag'));
-- Migration only marks missing metadata; the background sweep can queue eligible untagged revisions.
INSERT INTO information_steps(revision_id,generation,stage,state)
    SELECT id,generation,'autoTag','skipped' FROM information_revisions;

ALTER TABLE information_resources ADD COLUMN tag_groups JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(tag_groups)='object');
ALTER TABLE information_resources ADD COLUMN tag_groups_manual BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE information_revisions ADD COLUMN auto_tag_groups JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(auto_tag_groups)='object');
ALTER TABLE information_revisions ADD COLUMN tag_groups_input_tags JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tag_groups_input_tags)='array');
ALTER TABLE information_revisions ADD COLUMN tag_groups_generated BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE information_steps DROP CONSTRAINT information_steps_stage_check;
ALTER TABLE information_steps ADD CONSTRAINT information_steps_stage_check
    CHECK (stage IN ('extract','derive','embed','summarise','summary_embed','autoTag','tagGroups'));
INSERT INTO information_steps(revision_id,generation,stage,state) SELECT id,generation,'tagGroups','skipped' FROM information_revisions;

-- Canonical tag sets make group checkpoints sensitive to owner tag edits.
CREATE FUNCTION information_visible_tags(manual JSONB, automatic JSONB) RETURNS JSONB
LANGUAGE SQL IMMUTABLE AS $$
 SELECT coalesce(jsonb_agg(tag ORDER BY tag),'[]'::jsonb)
 FROM (SELECT DISTINCT tag FROM jsonb_array_elements_text(manual || automatic) AS t(tag) WHERE tag IS NOT NULL) tags
$$;
-- Never expose an edge whose tag is no longer present on the readable document.
CREATE FUNCTION information_visible_tag_groups(groups JSONB, tags JSONB) RETURNS JSONB
LANGUAGE SQL IMMUTABLE AS $$
 SELECT coalesce(jsonb_object_agg(name,members),'{}'::jsonb)
 FROM (SELECT g.key AS name,jsonb_agg(DISTINCT t.tag ORDER BY t.tag) AS members
       FROM jsonb_each(groups) g CROSS JOIN LATERAL jsonb_array_elements_text(g.value) t(tag)
       WHERE tags ? t.tag GROUP BY g.key) retained
$$;

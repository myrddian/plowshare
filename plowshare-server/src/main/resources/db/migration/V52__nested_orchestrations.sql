-- Nesting: a run may be started by a conductor, so a row knows its parent, how deep it is, and
-- which child it is waiting on. See implementation rationale
--
-- state gains 'waiting': alive, not being asked anything, and with nothing to do until a child
-- reports. orchestrations_state_is_known is rewritten from V48__orchestrations.sql, the migration
-- that last defined it, so the four terminal names it also lists are unchanged.

ALTER TABLE orchestrations ADD COLUMN parent TEXT REFERENCES orchestrations (id);
ALTER TABLE orchestrations ADD COLUMN depth INT NOT NULL DEFAULT 0;
ALTER TABLE orchestrations ALTER COLUMN depth DROP DEFAULT;
ALTER TABLE orchestrations ADD COLUMN waiting_for TEXT REFERENCES orchestrations (id);

ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_state_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_state_is_known CHECK (state IN
    ('running', 'asking', 'waiting', 'finished', 'failed', 'capped', 'cancelled'));

ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_waiting_for_iff_waiting
    CHECK ((state = 'waiting') = (waiting_for IS NOT NULL));
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_depth_natural CHECK (depth >= 0);
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_parent_iff_nested
    CHECK ((parent IS NULL) = (depth = 0));
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_parent_is_not_itself
    CHECK (parent IS NULL OR parent <> id);

CREATE INDEX orchestrations_live_children ON orchestrations (parent) WHERE ended_at IS NULL;

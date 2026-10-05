-- Preserve every existing project log and dependent identity while introducing a genuine
-- server-wide scope. No synthetic project or sentinel project ID is used. The nullable project
-- FK still cascades real project deletion; system topics have no project and survive it.
DO $$
DECLARE relation RECORD;
BEGIN
    -- PostgreSQL generates/truncates composite FK names. Select only this fixed table allowlist
    -- and quote catalog identifiers rather than guessing the generated constraint names.
    FOR relation IN
        SELECT conrelid::regclass AS table_name, conname FROM pg_constraint
        WHERE contype='f' AND conrelid IN (
            'relay_topics'::regclass, 'relay_publications'::regclass,
            'relay_subscriptions'::regclass, 'relay_admissions'::regclass,
            'relay_deliveries'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', relation.table_name, relation.conname);
    END LOOP;
END $$;

ALTER TABLE relay_topics RENAME COLUMN project_id TO scope_key;
ALTER TABLE relay_publications RENAME COLUMN project_id TO scope_key;
ALTER TABLE relay_subscriptions RENAME COLUMN project_id TO scope_key;
ALTER TABLE relay_admissions RENAME COLUMN project_id TO scope_key;
ALTER TABLE relay_deliveries RENAME COLUMN project_id TO scope_key;

ALTER TABLE relay_topics ALTER COLUMN scope_key TYPE TEXT USING 'project:' || scope_key::text;
ALTER TABLE relay_publications ALTER COLUMN scope_key TYPE TEXT USING 'project:' || scope_key::text;
ALTER TABLE relay_subscriptions ALTER COLUMN scope_key TYPE TEXT USING 'project:' || scope_key::text;
ALTER TABLE relay_admissions ALTER COLUMN scope_key TYPE TEXT USING 'project:' || scope_key::text;
ALTER TABLE relay_deliveries ALTER COLUMN scope_key TYPE TEXT USING 'project:' || scope_key::text;

ALTER TABLE relay_topics ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
UPDATE relay_topics SET project_id=substring(scope_key FROM 9)::bigint;
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_scope_is_known CHECK (
    (project_id IS NULL AND scope_key='system')
    OR (project_id IS NOT NULL AND project_id > 0 AND scope_key='project:' || project_id::text)
);
ALTER TABLE relay_publications ADD CONSTRAINT relay_publication_topic
    FOREIGN KEY (scope_key,topic) REFERENCES relay_topics(scope_key,name) ON DELETE CASCADE;
ALTER TABLE relay_subscriptions ADD CONSTRAINT relay_subscription_topic
    FOREIGN KEY (scope_key,topic) REFERENCES relay_topics(scope_key,name) ON DELETE CASCADE;
ALTER TABLE relay_admissions ADD CONSTRAINT relay_admission_subscription
    FOREIGN KEY (scope_key,topic,subscriber) REFERENCES relay_subscriptions(scope_key,topic,subscriber) ON DELETE CASCADE;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_delivery_admission
    FOREIGN KEY (scope_key,topic,subscriber,publication_position)
    REFERENCES relay_admissions(scope_key,topic,subscriber,publication_position) ON DELETE CASCADE;

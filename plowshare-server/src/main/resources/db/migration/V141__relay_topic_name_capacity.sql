-- Longer hierarchical topic paths also carry full 256-character tool identifiers.
-- Widen only topic names/targets; subscriber, receiver and failure-code limits are independent.
ALTER TABLE relay_topics DROP CONSTRAINT relay_topics_name_check;
ALTER TABLE relay_topics ADD CONSTRAINT relay_topics_name_check
    CHECK (length(name) <= 1024 AND name ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$');

ALTER TABLE relay_deliveries DROP CONSTRAINT relay_publish_target_name;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_publish_target_name
    CHECK (publish_to IS NULL OR (length(publish_to) <= 1024
        AND publish_to ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'));

ALTER TABLE relay_source_events DROP CONSTRAINT relay_source_events_topic_check;
ALTER TABLE relay_source_events ADD CONSTRAINT relay_source_events_topic_check
    CHECK (length(topic) <= 1024 AND topic ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$');

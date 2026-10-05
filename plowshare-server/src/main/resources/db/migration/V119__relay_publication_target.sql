-- Target selection belongs to the admitted decision, not a mutable receiver configuration.
ALTER TABLE relay_deliveries ADD COLUMN publish_to TEXT;
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_publish_target_name
    CHECK (publish_to IS NULL OR (length(publish_to) <= 160
        AND publish_to ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'));
ALTER TABLE relay_deliveries ADD CONSTRAINT relay_publish_target_receiver
    CHECK ((receiver = 'relay.publish') = (publish_to IS NOT NULL));

-- Digest readers use the revision and staleness fields. The edge trigger reads
-- the whole row but checks only the home and depth. Memory provenance retains
-- its own formation timestamp.
ALTER TABLE digests DROP COLUMN formed_at RESTRICT;

-- The Relay outbox publishes the event's occurred_at and orders by sequence.
-- Its insertion timestamp has no reader in the publisher or retention paths.
ALTER TABLE relay_source_events DROP COLUMN captured_at RESTRICT;

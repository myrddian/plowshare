-- Remote discovery is adapter-reported metadata, scoped like its peer advertisement.
ALTER TABLE outgoing_peers ADD COLUMN agent_card JSONB;

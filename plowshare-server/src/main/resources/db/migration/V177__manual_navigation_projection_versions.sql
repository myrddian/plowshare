-- Related Manual pages/facets select membership through live navigation edges. Obligations
-- already invalidate document projections, but removing an edge retains its obligation, so the
-- edge itself must also advance the durable source fence. Reuse the existing every-writer,
-- transaction-local dirty marker and commit-only domain notification contract.
CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON information_relationships
 REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT
 EXECUTE FUNCTION snapshot_cache_source_changed('documents');
CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON information_relationships
 REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT
 EXECUTE FUNCTION snapshot_cache_source_changed('documents');
CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON information_relationships
 REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT
 EXECUTE FUNCTION snapshot_cache_source_changed('documents');
CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON information_relationships
 FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed('documents');

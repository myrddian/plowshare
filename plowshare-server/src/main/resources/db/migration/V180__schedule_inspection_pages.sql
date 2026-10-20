-- User-facing pages apply ownership/source selection before their exclusive name cursor.
-- The name PK alone can scan many other accounts' rows to fill a sparse owner's page.
CREATE INDEX schedules_owner_name ON schedules(defined_by, name);

-- File inspection admits lightweight sources without scanning every account's folders.
CREATE INDEX schedule_sources_owner_id ON schedule_sources(account, id);

-- A selected source can seek directly into its internal-name page; this complements the
-- existing unique internal-name index used for global ordering and keyed controls.
CREATE INDEX schedule_files_source_internal ON schedule_files(source_id, internal_name);

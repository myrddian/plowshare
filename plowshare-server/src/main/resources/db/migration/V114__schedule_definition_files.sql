-- Source ownership is established by authenticated enrollment, never by JSON content.
CREATE TABLE schedule_sources (
  id BIGSERIAL PRIMARY KEY,
  account TEXT NOT NULL REFERENCES admins(handle),
  project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('server','workspace'))
);
CREATE UNIQUE INDEX schedule_sources_project ON schedule_sources(project_id,source) WHERE project_id IS NOT NULL;
CREATE UNIQUE INDEX schedule_sources_global ON schedule_sources(source) WHERE project_id IS NULL;
CREATE TABLE schedule_files (
  source_id BIGINT NOT NULL REFERENCES schedule_sources(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  internal_name TEXT NOT NULL UNIQUE,
  definition JSONB,
  status TEXT NOT NULL CHECK (status IN ('active','refused')),
  error TEXT,
  PRIMARY KEY(source_id,name)
);

ALTER TABLE schedules ADD COLUMN file_managed BOOLEAN NOT NULL DEFAULT FALSE;

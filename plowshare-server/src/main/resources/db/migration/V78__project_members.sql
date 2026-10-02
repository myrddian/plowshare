CREATE TABLE project_members (
    project_id BIGINT NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    handle TEXT NOT NULL REFERENCES admins (handle),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, handle)
);

INSERT INTO project_members (project_id, handle)
SELECT p.id, a.handle FROM projects p CROSS JOIN admins a;

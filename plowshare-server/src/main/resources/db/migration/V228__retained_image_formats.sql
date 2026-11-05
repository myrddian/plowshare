-- Existing invocation and Trajectory references were admitted as PNG. Preserve that fact
-- during upgrade; future inserts must supply their server-validated type explicitly.
ALTER TABLE invocation_image_admissions ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'image/png'
    CHECK (mime_type IN ('image/png','image/jpeg','image/webp'));
ALTER TABLE trajectory_image_references ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'image/png'
    CHECK (mime_type IN ('image/png','image/jpeg','image/webp'));
ALTER TABLE invocation_image_admissions ALTER COLUMN mime_type DROP DEFAULT;
ALTER TABLE trajectory_image_references ALTER COLUMN mime_type DROP DEFAULT;

-- Existing password accounts retain their administrator privileges.
ALTER TABLE admins ADD COLUMN server_admin BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE admins ADD COLUMN bootstrap BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX admins_one_bootstrap ON admins (bootstrap) WHERE bootstrap;
ALTER TABLE admins ADD CONSTRAINT admins_bootstrap_restricted
    CHECK (NOT bootstrap OR (must_change_password AND NOT server_admin));

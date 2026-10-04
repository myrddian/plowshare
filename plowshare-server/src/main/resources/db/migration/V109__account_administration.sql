ALTER TABLE admins ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE admins ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE auth_session_chains ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE auth_session_chains ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE admin_audit (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    enabled BOOLEAN,
    server_admin BOOLEAN
);
CREATE INDEX admin_audit_target ON admin_audit(target, id DESC);

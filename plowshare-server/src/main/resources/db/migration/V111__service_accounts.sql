ALTER TABLE admins ADD COLUMN account_kind TEXT NOT NULL DEFAULT 'USER'
    CHECK (account_kind IN ('USER','SERVICE','SERVICE_TOKEN'));
ALTER TABLE admins ADD CONSTRAINT machine_accounts_are_not_administrators
    CHECK (account_kind='USER' OR (NOT server_admin AND NOT bootstrap AND NOT must_change_password));
CREATE TABLE service_tokens (
    id UUID PRIMARY KEY,
    owner_handle TEXT NOT NULL REFERENCES admins(handle),
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 64),
    principal_handle TEXT NOT NULL UNIQUE REFERENCES admins(handle),
    digest TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    UNIQUE(owner_handle,name)
);
CREATE TABLE service_token_scopes (
    token_id UUID NOT NULL REFERENCES service_tokens(id),
    project_id BIGINT NOT NULL REFERENCES projects(id),
    role TEXT NOT NULL CHECK (role IN ('VIEWER','CONTRIBUTOR','MANAGER')),
    PRIMARY KEY(token_id,project_id)
);
CREATE FUNCTION account_active(account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM admins a WHERE a.handle=account AND a.enabled AND (
        a.account_kind<>'SERVICE_TOKEN' OR EXISTS (
            SELECT 1 FROM service_tokens t JOIN admins owner ON owner.handle=t.owner_handle
            WHERE t.principal_handle=a.handle AND t.revoked_at IS NULL AND t.expires_at>now()
                AND owner.enabled AND owner.account_kind='SERVICE'
        )
    ));
$$;
-- A credential never inherits a broader role than either its ceiling or its owner's live grant.
CREATE FUNCTION service_project_role(project TEXT, account TEXT) RETURNS TEXT LANGUAGE SQL STABLE AS $$
    SELECT CASE WHEN m.role='VIEWER' OR s.role='VIEWER' THEN 'VIEWER'
        WHEN m.role='CONTRIBUTOR' OR s.role='CONTRIBUTOR' THEN 'CONTRIBUTOR' ELSE 'MANAGER' END
    FROM service_tokens t JOIN service_token_scopes s ON s.token_id=t.id
    JOIN projects p ON p.id=s.project_id AND p.name=project AND p.personal_owner IS NULL
        AND p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%'
    JOIN project_members m ON m.project_id=p.id AND m.handle=t.owner_handle
    WHERE t.principal_handle=account AND account_active(account);
$$;
CREATE OR REPLACE FUNCTION information_project_readable(target BIGINT, account TEXT)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM projects p JOIN admins a ON a.handle=account AND account_active(account) AND NOT a.bootstrap
        WHERE p.id=target AND (
            (a.account_kind='SERVICE_TOKEN' AND service_project_role(p.name,account) IS NOT NULL)
            OR (a.account_kind<>'SERVICE_TOKEN' AND (
                (p.personal_owner IS NOT NULL AND p.personal_owner=account)
                OR (p.personal_owner IS NULL AND (
                    (p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%' AND a.server_admin)
                    OR EXISTS (SELECT 1 FROM project_members m WHERE m.project_id=p.id AND m.handle=account)
                ))
            ))
        )
    );
$$;

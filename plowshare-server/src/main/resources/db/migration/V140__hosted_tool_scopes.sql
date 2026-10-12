-- Durable assignments extend the existing provider and Relay invocation authority.
CREATE TABLE hosted_tool_scopes (
    id UUID PRIMARY KEY,
    project_id BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    owner TEXT NOT NULL REFERENCES admins(handle),
    generation BIGINT NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),
    enabled BOOLEAN NOT NULL,
    deleted BOOLEAN NOT NULL,
    application BOOLEAN NOT NULL,
    scope TEXT NOT NULL,
    route TEXT NOT NULL UNIQUE,
    connection_id UUID NOT NULL REFERENCES provider_connections(id),
    credential_subject TEXT NOT NULL REFERENCES admins(handle),
    definition JSONB NOT NULL CHECK(jsonb_typeof(definition)='object'),
    CHECK(NOT deleted OR NOT enabled),
    CHECK(definition->>'scope'=scope),
    CHECK((definition->>'connection')::uuid=connection_id)
);
CREATE UNIQUE INDEX hosted_tool_scopes_owner_scope ON hosted_tool_scopes(project_id,owner,scope) WHERE NOT deleted;
CREATE TABLE hosted_tool_scope_workers (
    binding_id UUID PRIMARY KEY REFERENCES hosted_tool_scopes(id),
    registration UUID NOT NULL UNIQUE,
    binding_generation BIGINT NOT NULL,
    session TEXT NOT NULL,
    socket TEXT NOT NULL,
    server UUID NOT NULL,
    catalogue_after BIGINT NOT NULL CHECK(catalogue_after>=0),
    registered_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK(registered_at<expires_at)
);
CREATE TABLE hosted_tool_scope_mutations (
    request_id UUID PRIMARY KEY,
    binding_id UUID NOT NULL REFERENCES hosted_tool_scopes(id),
    actor TEXT NOT NULL REFERENCES admins(handle),
    operation TEXT NOT NULL,
    receipt JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE relay_tool_invocations ADD COLUMN agent TEXT;
ALTER TABLE relay_tool_invocations ADD COLUMN hosted JSONB CHECK(hosted IS NULL OR jsonb_typeof(hosted)='object');
-- Keep retired UUIDs while project deletion withdraws availability and worker fences atomically.
CREATE FUNCTION withdraw_deleted_project_tool_hosts() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM hosted_tool_scope_workers w USING hosted_tool_scopes b
        WHERE w.binding_id=b.id AND b.project_id=OLD.id;
    UPDATE hosted_tool_scopes SET enabled=FALSE,deleted=TRUE,generation=LEAST(generation+1,9007199254740991)
        WHERE project_id=OLD.id;
    RETURN OLD;
END;
$$;
CREATE TRIGGER project_host_withdrawal BEFORE DELETE ON projects
    FOR EACH ROW EXECUTE FUNCTION withdraw_deleted_project_tool_hosts();

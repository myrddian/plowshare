-- A retained route owns one durable instance per account and source-project alias.
-- Keep the binding when stopped/archived so a route cannot silently open a replacement log.
CREATE TABLE board_message_route_bindings (
    account TEXT NOT NULL REFERENCES admins(handle),
    source_project TEXT NOT NULL,
    route_name TEXT NOT NULL,
    instance TEXT NOT NULL REFERENCES board_message_instances(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account, source_project, route_name)
);

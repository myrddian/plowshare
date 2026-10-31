-- The containment relation's own record: one row per contained Application.
--
-- Containment itself lives in authority_scopes.container (V208, frozen after insert by V209) and
-- this table adds no second authority. What it holds is the Application-level facts that relation
-- cannot: the request UUID that makes a retry idempotent, the fingerprint of exactly what was
-- submitted under it, the allocated source area and revision, the ceiling the container set, and
-- whether the child is still live. Those are deployment facts, not authority facts, which is why
-- they are here and not as more columns on authority_scopes.
--
-- Additive: no existing row, constraint, trigger or function is changed.
CREATE TABLE contained_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The child's own CONTAINED scope. UNIQUE, because a scope is one Application and not a
    -- family of them. ON DELETE CASCADE from the scope: destroying the scope collects this record
    -- through the same path that removes the scope, with no second code path to forget. V208's
    -- RESTRICT on authority_scopes.container still orders child destruction before the parent's,
    -- so this cascade cannot be used to outrun that ordering.
    scope_id UUID NOT NULL UNIQUE REFERENCES authority_scopes(id) ON DELETE CASCADE,

    -- Denormalised from authority_scopes.container so that the breadth count and the idempotency
    -- key can be indexed and constrained here. It is RESTRICT for the same reason V208's column
    -- is: a container's rows must be collected by the capability that owns their lifecycle --
    -- projects, principals, source areas and allowances -- and never silently by the schema.
    container UUID NOT NULL REFERENCES authority_scopes(id) ON DELETE RESTRICT,

    -- The child project. UNIQUE, because a contained project hosts exactly one child.
    project_id BIGINT NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,

    -- ADR 0005's receipt identity, narrowed to this path: the container's request UUID plus the
    -- fingerprint of the exact submitted content and ceiling. The UNIQUE below is what makes a
    -- retried create read its receipt instead of making a second child, and what makes a changed
    -- submission under the same UUID a refusal rather than a new revision. It is keyed on the
    -- container and not on an account, because the authority for this path is the container's.
    request_id UUID NOT NULL,
    fingerprint TEXT NOT NULL,

    revision UUID NOT NULL,
    workspace TEXT NOT NULL,

    -- The child's OWN allowance, under the ceiling its container set. No foreign key into
    -- execution_allowances: that table's rows are created by the allowance repository inside the
    -- same transaction, and a constraint here would make this table an authority over another
    -- owner's lifecycle. The ceiling is stored alongside so a read of a child does not have to
    -- join an unrelated owner's table to report what its container set.
    --
    -- NOT NULL on ceiling is load-bearing: AllowanceSnapshot documents an absent ceiling as
    -- UNLIMITED, so a contained Application created without one would be unbounded. Zero is a
    -- permitted and useful value -- a child that cannot make a call.
    allowance UUID NOT NULL,
    ceiling INTEGER NOT NULL,

    live BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT contained_application_request UNIQUE (container, request_id),
    CONSTRAINT contained_application_ceiling CHECK (ceiling >= 0),
    CONSTRAINT contained_application_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT contained_application_workspace CHECK (workspace <> '' AND workspace LIKE '/%'),
    -- A record cannot be its own container. The scope relation already forbids a self-referencing
    -- scope; this says the same thing about the denormalised copy so the two cannot disagree.
    CONSTRAINT contained_application_not_its_own_container CHECK (container <> scope_id)
);

CREATE INDEX contained_application_container ON contained_applications(container);
CREATE INDEX contained_application_live ON contained_applications(container) WHERE live;

-- The denormalised container must agree with the authority record, or the breadth count and the
-- subtree walk would be answering about a different tree than the one authority resolves. A
-- trigger rather than a CHECK, because the fact being checked lives in another row.
CREATE OR REPLACE FUNCTION contained_application_agrees() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM authority_scopes s
        WHERE s.id = NEW.scope_id AND s.kind = 'CONTAINED'
          AND s.container = NEW.container AND s.project_id = NEW.project_id
    ) THEN
        RAISE EXCEPTION 'contained_applications row disagrees with its authority scope';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER contained_application_agreement
    BEFORE INSERT OR UPDATE OF scope_id, container, project_id ON contained_applications
    FOR EACH ROW EXECUTE FUNCTION contained_application_agrees();

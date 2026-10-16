-- Proof exchange records contain metadata only. Unrecognized Applications/grants must also be
-- auditable, so these labels deliberately have no foreign key to admitted source declarations.
CREATE TABLE acting_context_audit (
    id BIGSERIAL PRIMARY KEY,
    actor TEXT NOT NULL REFERENCES admins(handle),
    application TEXT NOT NULL CHECK (length(application) BETWEEN 1 AND 256),
    grant_name TEXT NOT NULL CHECK (grant_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    action TEXT NOT NULL CHECK (action IN ('OPEN','REFUSED','CLOSE')),
    context_id UUID,
    subject TEXT REFERENCES admins(handle),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((action='REFUSED') = (context_id IS NULL)),
    CHECK ((action='REFUSED') = (subject IS NULL))
);

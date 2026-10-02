-- A run's check: the command the harness runs whenever a checked stage is marked done
-- (implementation rationale). One row per run, so the
-- primary key is what makes it set once.
--
-- No foreign keys, on purpose: the run and its approval are written by the same engine and
-- neither is ever deleted, and a key here would make the store's own test build a whole
-- orchestration and conversation to write one row.
CREATE TABLE orchestration_checks (
    orchestration TEXT        PRIMARY KEY,
    argv          JSONB       NOT NULL,
    side          TEXT        NOT NULL,
    cwd           TEXT        NOT NULL,
    consent       TEXT        NOT NULL,
    approval      TEXT,
    set_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT orchestration_checks_side_is_known CHECK (side IN ('server', 'local')),
    CONSTRAINT orchestration_checks_consent_is_known CHECK (consent IN ('open', 'approval')),
    CONSTRAINT orchestration_checks_an_approval_is_named_iff_asked
        CHECK ((consent = 'approval') = (approval IS NOT NULL)),
    CONSTRAINT orchestration_checks_argv_is_a_command
        CHECK (jsonb_typeof(argv) = 'array' AND jsonb_array_length(argv) > 0)
);

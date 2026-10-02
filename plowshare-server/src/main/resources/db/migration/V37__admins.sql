-- One admin account, so a credential can reach this server from a machine that
-- is not its own host. Everything that already works keeps working: the
-- operator token in PLOWSHARE_TOKEN, the console's bootstrap-URL flow, and
-- POST /v1/auth and POST /v1/auth/refresh are all untouched by this table
-- existing. This is additive.
--
-- THE NEXT NUMBER WAS PLANNED AS V10 AND IS V37 INSTEAD. The slice's plan
-- named V10 as the next free migration; `master` at the time this file was
-- written already carries V1 through V36 (`MigrationsAreImmutableTest` freezes
-- against `master`, not against what happens to be on disk in a worktree, and
-- V10__conversation_turn_cap.sql through V36__buffer_purge_indexes.sql are
-- already there). Filing this as V10 would not merely be a stale filename --
-- Flyway resolves a migration by its version number, so a second file also
-- claiming version 10 is "Found more than one migration with version 10" at
-- every boot and every test that runs the chain, not a quiet rename. V37 is
-- the number nothing on `master` holds.
--
-- ONE ROW PER ADMIN, EVEN THOUGH SLICE 1 SEEDS EXACTLY ONE. The design is
-- explicit that this slice buys credential lifecycle and not authorisation --
-- one admin is the same privilege posture as today's operator token -- so
-- there is no role column and no second table for what an admin may do. A
-- table rather than a single-row singleton anyway, because "exactly one row"
-- is not a fact this schema is asked to enforce: `AdminSeed` creates the
-- account this repository ships with today, and the shape here does not need
-- to change on the day that stops being true.
CREATE TABLE admins (
    -- What a person types to say who they are, not a surrogate key: there is
    -- no second identifier anything in this server joins against yet, and
    -- inventing one now would be a key nothing reads. TEXT and not a bounded
    -- VARCHAR for the reason every other name column in this schema is TEXT --
    -- projects.name, memories.project -- a length limit here would be a rule
    -- about login handles this table has no reason to hold an opinion on.
    handle                TEXT        PRIMARY KEY,

    -- The Argon2id hash `auth.PasswordHasher.hash` produced, which already
    -- carries its own salt and cost parameters encoded in the string --
    -- $argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>. Nothing in this table
    -- stores a salt as a separate column because there is nothing left over to
    -- store: verifying is `PasswordHasher.matches` re-parsing this one string
    -- against the presented password, the same way `V6`'s
    -- `conversations.budget_total`/`budget_spent` pair is the whole of what
    -- `agents.Budget` needs and no more.
    password_hash         TEXT        NOT NULL,

    -- Whether the next successful login must set a new password before
    -- anything else is permitted. DEFAULT TRUE, and that default is the whole
    -- point of this column: `AdminSeed` mints this account from an operator's
    -- own environment variables at boot, and a seeded password that could go
    -- on being the real password indefinitely is worse than the operator
    -- token it is meant to retire, because it LOOKS solved. Enforcing the flag
    -- is task 2's; this table only has to make the fact storable and true from
    -- the first row.
    must_change_password  BOOLEAN     NOT NULL DEFAULT TRUE,

    -- DEFAULT now(), `projects.defined_at`'s reason and not
    -- `conversations.created_at`'s: nothing this task builds asserts on the
    -- exact instant an admin was created, so there is no caller that needs to
    -- choose one, unlike a conversation's clock-injected timestamp, which a
    -- test drives directly.
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A handle nothing can name is an account nobody can log in as: the same
    -- sentence `projects_name_named` and `memories_project_named` carry, and
    -- the primary key above already refuses NULL, so this closes the one gap
    -- a primary key does not -- the empty string, which is what an omitted
    -- request field arrives as.
    CONSTRAINT admins_handle_named CHECK (handle <> ''),

    -- A hash nothing can verify against is an account nobody can log in as
    -- either, and it is refused for the same reason: an empty string is not a
    -- hash `PasswordHasher` ever produces, so a row holding one could only have
    -- arrived by a caller that skipped hashing altogether.
    CONSTRAINT admins_password_hash_named CHECK (password_hash <> '')
);

COMMENT ON COLUMN admins.password_hash IS
    'The Argon2id encoded hash from auth.PasswordHasher.hash -- salt and cost '
    'parameters included in the string, nothing stored beside it. Never the '
    'plaintext password, and never logged: see auth.Tokens for why this '
    'server treats an accept-side secret as something that is only ever '
    'checked and not recovered.';

COMMENT ON COLUMN admins.must_change_password IS
    'True until a login sets a real password. A seeded account is created '
    'with this true and stays true across restarts -- it is cleared only by '
    'the password actually changing, never by time or by a later boot -- so '
    'a default password an operator forgot to rotate cannot quietly become '
    'the permanent one.';

-- The handful of configuration values that a running server may be told to
-- change, and the record of who told it.
--
-- WHAT THIS IS FOR. Nine `@ConfigurationProperties` classes carry this server's
-- configuration. Spring binds each from `application.yml` once, at boot, and a
-- `@Configuration` class reads the bound object and passes the numbers into
-- constructors that hold them as final fields. They are never read again, so a
-- running server has no path by which a value can change: every change is an
-- edit to a file and a restart. On 2026-09-06 that cost three restarts -- the
-- ingest budget raised 300 -> 1000 after a 30-page paper needed 496 calls, and
-- two model swaps -- which is what this table exists to stop.
--
-- ONE TABLE AND NOT NINE. The map is keyed by the property key Spring itself
-- binds, `plowshare.documents.ingest-budget` and its siblings, so a key that
-- goes live needs no schema change at all. The alternative -- a column per
-- setting -- makes every new live key a migration, and makes the set of live
-- keys a fact about the database rather than about the source that declares it.
--
-- TEXT, AND NO PARSING HERE. The properties classes already turn strings into
-- ints, durations and URLs, and they already carry the javadoc explaining what
-- each one means and what it costs. A typed column would be a second place that
-- knows what `ask-budget` is, and two places that know one thing is what drifts.
--
-- WHAT THIS TABLE DOES NOT KNOW. It does not know which keys are live: that is
-- declared on the accessor in the class that owns the value, so the boundary
-- between the two classes of configuration is visible where a reader already
-- is. A row for a key nothing reads is therefore possible and is inert -- the
-- controller refuses to write one, and a key that stops being live leaves a row
-- behind that no longer means anything. Refusing that in the schema would need
-- the database to hold a copy of a list that lives in Java, which is the drift
-- this file is otherwise arranged to avoid.
--
-- NOT AN AUDIT LOG. One row per key, replaced in place. The map answers "what
-- is this value now"; the history of a setting is a different question with a
-- different retention argument, and `jobs` in V24 is what happens when this
-- server decides to keep operational records -- a lifecycle and a pruning
-- policy, not a table that grows forever by accident.

CREATE TABLE runtime_config (

    -- The full property key as Spring binds it, and the primary key, because a
    -- key holding two values is not a configuration.
    key        TEXT        PRIMARY KEY,

    -- Never NULL. An absent key and a key set to nothing must stay different
    -- answers: absent means no operator has set it, which is what lets a
    -- packaged default seed the row on a later boot without overruling anybody.
    value      TEXT        NOT NULL,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The only column here that exists purely so a question can be answered
    -- later: a value that surprises somebody has a name against it. NOT NULL
    -- and CHECKed non-empty so the guarantee holds against whatever writes the
    -- row -- the boot seed, the operator API, a psql session at three in the
    -- morning -- and not only against the one Java method that is supposed to.
    updated_by TEXT        NOT NULL,

    CONSTRAINT runtime_config_key_is_named  CHECK (key <> ''),
    CONSTRAINT runtime_config_has_an_author CHECK (updated_by <> '')
);

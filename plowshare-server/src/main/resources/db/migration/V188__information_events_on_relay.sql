-- Information events move onto Relay, and their own delivery substrate is removed (decision 0037).
--
-- What stood here was a second durable log alongside Relay's: information_events with one global
-- BIGSERIAL, information_event_recipients holding a recipient set frozen at emit time, and
-- information_event_dispatch, a singleton cursor leased by a background drain. The writer took a
-- pg_advisory_xact_lock on one key hashed from a fixed name -- a single key for the whole
-- installation, held to the end of the transaction -- and then, still holding it, computed the
-- recipient fan-out by asking information_readable once per administrator and once per
-- administrator-project pair. Measured on the live installation with 13 administrators, one event
-- cost 3,996 ms, and because the fence is held to COMMIT every other event in the installation
-- waited behind it while the processing pipeline emitted them continuously.
--
-- The fence was not gratuitous: a BIGSERIAL is allocated at INSERT and visible at COMMIT, so a
-- reader scanning 'sequence > cursor' can advance past a lower sequence that commits later and lose
-- it -- a sequence maximum is not commit order. Relay buys the same guarantee with a row lock on
-- one relay_topics row, so its contention is one topic in one project. The fence was the right
-- answer to the wrong question, and the question was why a second log existed at all.
--
-- This is a clean break, not a shadowed one. No installation is being migrated, existing rows here
-- are instance history rather than a contract, and a dual-write release would keep the fence, the
-- global sequence and the cursor alive for exactly the release this record exists to end. Nothing
-- is copied forward: publication is now a Relay append whose audience is the revision's home scope,
-- and recipients come from that topic's subscriptions.

-- The payload family for an information event. relay_admissions carries the same family vocabulary
-- as relay_topics, so both CHECKs move together, as V125 and V127 did for the two before this.
ALTER TABLE relay_topics DROP CONSTRAINT relay_topic_payload_family;
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_payload_family CHECK (
    payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED','INFORMATION_CHANGED'));
-- The same agreement V117 established for schedule.due: this family has exactly one topic name, in
-- every project scope that homes information, so a topic cannot carry it under another name and
-- that name cannot carry another family.
ALTER TABLE relay_topics ADD CONSTRAINT relay_information_changed_family CHECK (
    (name='information.changed') = (payload_kind='INFORMATION_CHANGED'));

ALTER TABLE relay_admissions DROP CONSTRAINT relay_admissions_payload_kind_check;
ALTER TABLE relay_admissions ADD CONSTRAINT relay_admissions_payload_kind_check
    CHECK (payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED','INFORMATION_CHANGED'));

-- One definition of "this subscription's authority is still valid", so the consume gate and the
-- wake-hint audience cannot drift apart.
--
-- Decision 0038 resolves a subscription's read authority once, at establishment, and records it in
-- relay_subscription_authority; validity afterwards has three independent failure routes and all
-- three must be checked. The consume gate needed one row's verdict and the hint fan-out needs the
-- set of accounts whose verdict is VALID, and writing that condition twice is exactly the
-- duplication decision 0037 exists to remove -- one layer down, where a drift between the two
-- would mean hinting to an account the consume path refuses.
--
-- NULL inputs are NOT_ESTABLISHED rather than an error: an account that never established
-- authority is indistinguishable from one whose record was withdrawn, and both must be refused the
-- same way. The generation comparison is the real revocation; the age only caps the exposure when
-- a revocation was missed, which is why it is a separate branch and not a substitute.
CREATE FUNCTION relay_subscription_authority_validity(
    recorded_generation UUID, current_generation UUID, established_at TIMESTAMPTZ,
    maximum_age INTERVAL, now_at TIMESTAMPTZ) RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
    SELECT CASE
        WHEN $1 IS NULL OR $2 IS NULL OR $3 IS NULL THEN 'NOT_ESTABLISHED'
        WHEN $1 <> $2 THEN 'GENERATION_CHANGED'
        WHEN $3 + $4 <= $5 THEN 'EXPIRED'
        ELSE 'VALID' END;
$$;

COMMENT ON FUNCTION relay_subscription_authority_validity(UUID,UUID,TIMESTAMPTZ,INTERVAL,TIMESTAMPTZ) IS
    'Whether an established Relay subscription authority is still valid, and if not, why (0038).';

-- The revision's own event history, which is not delivery and not a per-subscriber inbox.
-- information.status shows an owner what happened to their document, and that has to outlive a
-- Relay topic's retention window -- four days by default -- and be answerable by revision, which a
-- log indexed by topic position cannot do. So the history is kept, deliberately stripped of
-- everything that made the old table a substrate: there is no sequence of its own (the position
-- stored is the one Relay allocated under its topic lock), no recipients, no dispatch cursor and
-- nothing that reads it by cursor. ON DELETE CASCADE because a revision's history is part of the
-- revision, not an independent record of it.
CREATE TABLE information_revision_events (
    revision_id UUID NOT NULL REFERENCES information_revisions(id) ON DELETE CASCADE,
    relay_project BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    relay_position BIGINT NOT NULL CHECK (relay_position > 0),
    generation BIGINT NOT NULL CHECK (generation > 0),
    actor_handle TEXT REFERENCES admins(handle),
    stage TEXT NOT NULL,
    action TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The audience is keyed by project, so a position is unique within one revision's home. The
    -- project is part of the key rather than assumed, so a revision whose home ever moves records
    -- the second home's positions instead of colliding with the first's.
    PRIMARY KEY (revision_id, relay_project, relay_position)
);

-- Dropped in dependency order: recipients and the dispatch cursor reference nothing that survives,
-- and information_events is the parent of the recipient rows. The dispatch row went with the drain
-- that leased it; no reader of any of the three remains in the server.
DROP TABLE IF EXISTS information_event_recipients;
DROP TABLE IF EXISTS information_event_dispatch;
DROP TABLE IF EXISTS information_events;

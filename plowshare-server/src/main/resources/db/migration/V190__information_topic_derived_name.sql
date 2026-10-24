-- The information topic's name becomes derived and opaque, and every row keyed on it is renamed.
--
-- Relay is a mechanism, not a policy, and it follows that a Relay topic name is private to the
-- system that owns it: the broker carries a contract, stores a publication opaquely, and
-- deliberately reserves no names for consumers. V188 published to the literal 'information.changed'
-- instead, which made the name an address. Anything able to publish to a project topic could spell
-- it; an operator could configure it as an external ingress port in plowshare.relay.ports.bindings,
-- because RelayPortProperties.permits is pure data and no code reserves topic names; and it became
-- part of a contract nobody intended to offer. V189 had already removed the payload family that
-- made a wrong publisher structurally impossible, which is what left the port grant as the only
-- thing standing between an external publisher and the topic.
--
-- The new name is 'information.events.' || sha256('plowshare.information.events/1:' || <scope>),
-- lowercase hex, derived only inside server/information (InformationTopics) by the publisher and
-- the consumer independently. It is 83 characters and every segment is lowercase alphanumeric.
-- The constraints it has to satisfy, read out of the migrations rather than assumed: every
-- topic-name column in the schema applies '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$', and the lengths
-- differ -- relay_topics.name allows 1024 (V117's 160, widened by V141), relay_deliveries.publish_to
-- 1024 (V119, likewise widened), and relay_source_events.topic 160 (V124), which is the tightest
-- bound a topic name faces. relay_subscriptions, relay_admissions, relay_consumer_leases and the
-- rest carry the name as a bare TEXT column under a foreign key, with their own 160-character rule
-- applying to subscriber rather than to topic. 83 clears all of them. Derived is not secret: the
-- digest is
-- recomputable by anyone who knows the recipe, and the structural barrier remains the publisher
-- check RelayInformationEvents applies before decoding. What is withdrawn is the name as a
-- published address.
--
-- <scope> is relay_topics.scope_key verbatim -- 'project:<id>' or 'system' (V120). The Java
-- derivation builds the same string from its typed scope and is documented as doing so on purpose,
-- so the expression below needs no reconstruction. HOW THE TWO WERE VERIFIED TO AGREE: not by
-- reading them side by side. RelayInformationEventsDatabaseTest
-- (the_topic_name_postgresql_derives_is_the_one_java_derives) evaluates exactly this expression in
-- PostgreSQL for several scopes and asserts the result equals InformationTopics.of(scope), and
-- InformationTopicRenameDatabaseTest migrates a V189 fixture through this file and asserts the
-- renamed rows carry the name Java derives. Changing either side alone fails both.
--
-- WHY THIS IS A RENAME IN PLACE AND NOT A RE-CREATION. The topic name is part of the key of nine
-- tables, and the rows keyed on it are not reconstructible: relay_subscriptions.seen_through is a
-- subscriber's committed cursor, relay_subscription_authority holds read authority established at
-- subscribe time together with the topic incarnation it was established against, and
-- relay_consumer_leases holds a fencing epoch. Dropping and recreating a subscription would reset
-- its cursor to zero or to the retention floor, and that is silent delivery loss or silent
-- redelivery, not a visible defect. So every row is UPDATEd and nothing is deleted or inserted --
-- in particular relay_topics.generation is untouched, which is what keeps every
-- relay_subscription_authority row valid: V188's relay_subscription_authority_validity() refuses a
-- record whose topic_generation no longer matches the topic's, so a recreated topic row would
-- revoke every established authority on it.
--
-- The composite foreign keys that key on the topic name are ON UPDATE NO ACTION and not
-- deferrable, so a parent-side UPDATE is checked at the end of its own statement and would find the
-- children still referencing the old name. They are therefore dropped and restored around the
-- updates, from their own pg_get_constraintdef text so that nothing about them -- column order,
-- ON DELETE CASCADE, name -- is retyped and possibly changed. Restoring a foreign key validates
-- the whole table, so an incomplete rename fails here loudly rather than stranding rows. V120 set
-- the precedent for dropping and restoring these constraints inside a migration.

-- The constraints to be restored afterwards, captured before anything is dropped. Only the keys
-- that could be violated by the updates below are touched: those whose referencing or referenced
-- rows change. relay_native_consumers also references relay_subscriptions, but its topic CHECK
-- admits only the two wake names, so its referencing rows cannot be ours and its check passes
-- unaided; it is left in place rather than dropped on a guess.
CREATE TEMP TABLE information_topic_rename_keys AS
SELECT c.conrelid::regclass::text AS child,
       c.conname::text AS name,
       pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
WHERE c.contype = 'f'
  AND c.conrelid IN (
        'relay_publications'::regclass, 'relay_subscriptions'::regclass,
        'relay_admissions'::regclass, 'relay_deliveries'::regclass,
        'relay_consumer_leases'::regclass, 'relay_sdk_batches'::regclass,
        'relay_approval_gap_recoveries'::regclass, 'relay_subscription_authority'::regclass)
  AND c.confrelid IN (
        'relay_topics'::regclass, 'relay_subscriptions'::regclass, 'relay_admissions'::regclass);

DO $$
DECLARE fk RECORD; captured BIGINT;
BEGIN
    SELECT count(*) INTO captured FROM information_topic_rename_keys;
    -- The eight keys this rename crosses: publications and subscriptions to topics; admissions,
    -- consumer leases, SDK batches, approval gap recoveries and subscription authority to
    -- subscriptions; deliveries to admissions. A schema that gained or lost one must be read again
    -- rather than migrated on an assumption that no longer holds.
    IF captured <> 8 THEN
        RAISE EXCEPTION
            'expected 8 topic-keyed Relay foreign keys to restore, found %; review the rename',
            captured;
    END IF;
    FOR fk IN SELECT * FROM information_topic_rename_keys LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.child, fk.name);
    END LOOP;
END $$;

-- The rename itself. Every table with a Relay topic column is listed, and each UPDATE is confined
-- to rows naming the old topic, so a table that never held one is a no-op rather than an omission.
-- The parent is renamed first only for readability; with the keys dropped the order is immaterial.
UPDATE relay_topics
SET name = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE name = 'information.changed';

UPDATE relay_publications
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

-- The subscriber's committed cursor travels with the row. seen_through, seen_at, reader_account
-- and the subscription's own generation are not written here and so cannot be lost.
UPDATE relay_subscriptions
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

-- Established read authority travels with the row, topic_generation included, which is what keeps
-- it valid against the topic row this migration did not recreate.
UPDATE relay_subscription_authority
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

UPDATE relay_admissions
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

UPDATE relay_deliveries
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

-- A lease's fencing epoch travels with the row; a reset epoch would let a restarted worker reuse
-- an old fence.
UPDATE relay_consumer_leases
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

UPDATE relay_sdk_batches
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

UPDATE relay_approval_gap_recoveries
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'information.changed';

-- No foreign key, and no row is expected: the lifecycle outbox carries only the LIFECYCLE and
-- WAKE_REQUESTED families and information events never entered it. Updated anyway, because the
-- column accepts any topic name and an UPDATE matching nothing cannot be wrong, while an omission
-- would leave a stale name behind if one ever were written there. project_id, not scope_key, is
-- this table's scope column (V124), so the scope string is rebuilt from it the way V120 wrote it.
UPDATE relay_source_events
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:'
            || COALESCE('project:' || project_id::text, 'system'))::bytea), 'hex')
WHERE topic = 'information.changed';

-- An operator receipt records the topic an operator acted on. It is an audit row with no foreign
-- key to the topic, and renaming it keeps the audit answerable about the same topic.
UPDATE relay_operator_receipts
SET topic = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:project:' || project_id::text)::bytea),
                  'hex')
WHERE topic = 'information.changed';

-- A routing branch that republishes into another topic names its destination here. No route
-- targets the information topic, and the CHECK ties publish_to to receiver='relay.publish', so this
-- is expected to match nothing; it is included for the same reason as the outbox above.
UPDATE relay_deliveries
SET publish_to = 'information.events.'
        || encode(sha256(('plowshare.information.events/1:' || scope_key)::bytea), 'hex')
WHERE publish_to = 'information.changed';

DO $$
DECLARE fk RECORD;
BEGIN
    FOR fk IN SELECT * FROM information_topic_rename_keys LOOP
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s', fk.child, fk.name, fk.definition);
    END LOOP;
END $$;

DROP TABLE information_topic_rename_keys;

-- The rename either completed everywhere or this migration fails. Each table is named explicitly
-- rather than discovered, so a table added later with a topic column is a compile-time-visible
-- omission in the next migration rather than a silently skipped row here. The two native-wake
-- tables are asserted rather than updated: their CHECKs forbid the value, and a row holding it
-- would mean a CHECK was dropped somewhere and the rename cannot be trusted.
DO $$
DECLARE remaining BIGINT;
BEGIN
    SELECT count(*) INTO remaining FROM (
        SELECT 1 FROM relay_topics WHERE name = 'information.changed'
        UNION ALL SELECT 1 FROM relay_publications WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_subscriptions WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_subscription_authority WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_admissions WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_deliveries WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_deliveries WHERE publish_to = 'information.changed'
        UNION ALL SELECT 1 FROM relay_consumer_leases WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_sdk_batches WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_approval_gap_recoveries WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_source_events WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_operator_receipts WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_native_consumers WHERE topic = 'information.changed'
        UNION ALL SELECT 1 FROM relay_native_gap_recoveries WHERE topic = 'information.changed'
    ) held;
    IF remaining > 0 THEN
        RAISE EXCEPTION 'the literal information topic name survives in % row(s)', remaining;
    END IF;
END $$;

-- The scheduled-occurrence topic's name becomes derived and opaque, and every row keyed on it is
-- renamed. This is V190 done for the second family, and its header is the fuller explanation.
--
-- Relay is a mechanism, not a policy, and it follows that a Relay topic name is private to the
-- system that owns it (decision 0040): the broker carries a contract, stores a publication
-- opaquely, and reserves no names for consumers. 'schedule.due' was declared inside the broker
-- instead -- Relay.java bound the literal to the SCHEDULE_DUE payload kind, RelayRouting.Subscription
-- repeated the same agreement for an operator's routing manifest, and this file drops the third
-- copy, the CHECK V117 wrote into relay_topics. schedule.define is the schedules family's public
-- contract; where its occurrences travel is not, and nothing outside server/events should be able
-- to name it.
--
-- SCHEDULE_DUE IS NOT REMOVED. It is a payload kind, it stays in the family vocabulary of
-- relay_topics and relay_admissions, and it stays in the manifest spelling RelayRouteCodec parses.
-- Whether schedules should publish the general message instead is a separate question that would
-- change the SDK surface. Only the topic name moves here.
--
-- The new name is 'schedule.occurrence.' || sha256('plowshare.schedule.occurrence/1:' || <scope>),
-- lowercase hex, derived only inside server/events (EventTopics.scheduled) by the publisher. It is
-- 84 characters and every segment is lowercase alphanumeric, so it satisfies the
-- '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$' rule every topic-name column applies. On length: V117, V119
-- and V124 each bounded a topic name at 160 characters and V141 widened all three to 1024, so 1024
-- is the real bound today and 84 clears it. (V190's header describes relay_source_events.topic as a
-- 160-character bound and "the tightest bound a topic name faces"; that was already superseded by
-- V141 when it was written. It makes no difference to either derivation.)
--
-- <scope> is relay_topics.scope_key verbatim -- 'project:<id>' or 'system' (V120) -- and BOTH CASES
-- ARE LIVE HERE, unlike V190's. JdbcScheduleStore.claimPublication returns the owning project for a
-- file-managed schedule and the server scope for a projectless one, so an installation can hold
-- schedule topics under either, and the expression below handles both without branching because the
-- Java derivation builds the same string from its typed scope on purpose.
--
-- HOW THE SQL AND THE JAVA WERE VERIFIED TO AGREE: not by reading them side by side.
-- ScheduleTopicRenameDatabaseTest evaluates this expression in PostgreSQL for a project scope and
-- for the server scope and asserts each result equals EventTopics.scheduled(scope), and it also
-- migrates a pre-rename fixture through this file and asserts the renamed rows carry the name Java
-- derives. Changing either side alone fails both.
--
-- WHY THIS IS A RENAME IN PLACE AND NOT A RE-CREATION, which matters more here than it did for
-- information events. The topic name is part of the key of nine tables and the rows keyed on it are
-- not reconstructible. relay_subscriptions.seen_through is the builtin.scheduling subscriber's
-- committed cursor, and that subscriber IS the firing inbox: RelayScheduledPublication asserts that
-- each publication lands at exactly seenThrough + 1, so a cursor reset to zero or to the retention
-- floor would make the next tick throw "scheduled Relay subscriber has an unadmitted publication"
-- and stop every schedule on the installation. relay_subscription_authority holds read authority
-- established at subscribe time together with the topic incarnation it was established against, and
-- relay_consumer_leases holds a fencing epoch. So every row is UPDATEd and nothing is deleted or
-- inserted -- in particular relay_topics.generation is untouched, which is what keeps every
-- relay_subscription_authority row valid, since V188's relay_subscription_authority_validity()
-- refuses a record whose topic_generation no longer matches the topic's.
--
-- The composite foreign keys that key on the topic name are ON UPDATE NO ACTION and not deferrable,
-- so a parent-side UPDATE is checked at the end of its own statement and would find the children
-- still referencing the old name. They are therefore dropped and restored around the updates, from
-- their own pg_get_constraintdef text so that nothing about them -- column order, ON DELETE
-- CASCADE, name -- is retyped and possibly changed. Restoring a foreign key validates the whole
-- table, so an incomplete rename fails here loudly rather than stranding rows. V120 set the
-- precedent and V190 followed it.

-- The agreement CHECK V117 wrote without a name. Found by its definition rather than by guessing
-- what PostgreSQL called it, and the count is asserted so that a schema which no longer carries it
-- -- or carries two -- is read again rather than migrated on an assumption.
DO $$
DECLARE agreement TEXT; found BIGINT;
BEGIN
    SELECT count(*), min(c.conname::text) INTO found, agreement
    FROM pg_constraint c
    WHERE c.contype = 'c'
      AND c.conrelid = 'relay_topics'::regclass
      AND pg_get_constraintdef(c.oid) LIKE '%schedule.due%';
    IF found <> 1 THEN
        RAISE EXCEPTION
            'expected exactly 1 schedule.due agreement CHECK on relay_topics, found %', found;
    END IF;
    EXECUTE format('ALTER TABLE relay_topics DROP CONSTRAINT %I', agreement);
END $$;

-- The constraints to be restored afterwards, captured before anything is dropped. The same eight
-- keys V190 crossed, and for the same reason: those whose referencing or referenced rows change.
-- relay_native_consumers also references relay_subscriptions, but its topic CHECK admits only the
-- two wake names, so its referencing rows cannot be ours and its check passes unaided; it is left
-- in place rather than dropped on a guess.
CREATE TEMP TABLE schedule_topic_rename_keys AS
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
    SELECT count(*) INTO captured FROM schedule_topic_rename_keys;
    IF captured <> 8 THEN
        RAISE EXCEPTION
            'expected 8 topic-keyed Relay foreign keys to restore, found %; review the rename',
            captured;
    END IF;
    FOR fk IN SELECT * FROM schedule_topic_rename_keys LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.child, fk.name);
    END LOOP;
END $$;

-- The rename itself. Every table with a Relay topic column is listed, and each UPDATE is confined
-- to rows naming the old topic, so a table that never held one is a no-op rather than an omission.
-- The parent is renamed first only for readability; with the keys dropped the order is immaterial.
UPDATE relay_topics
SET name = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE name = 'schedule.due';

UPDATE relay_publications
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

-- builtin.scheduling's committed cursor travels with the row. seen_through, seen_at, reader_account
-- and the subscription's own generation are not written here and so cannot be lost. This is the one
-- the installation's schedules depend on; see the header.
UPDATE relay_subscriptions
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

-- Established read authority travels with the row, topic_generation included, which is what keeps
-- it valid against the topic row this migration did not recreate.
UPDATE relay_subscription_authority
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

UPDATE relay_admissions
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

UPDATE relay_deliveries
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

-- A lease's fencing epoch travels with the row; a reset epoch would let a restarted worker reuse an
-- old fence.
UPDATE relay_consumer_leases
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

UPDATE relay_sdk_batches
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

UPDATE relay_approval_gap_recoveries
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE topic = 'schedule.due';

-- No foreign key, and no row is expected: the lifecycle outbox carries only the LIFECYCLE and
-- WAKE_REQUESTED families and scheduled occurrences never entered it. Updated anyway, because the
-- column accepts any topic name and an UPDATE matching nothing cannot be wrong, while an omission
-- would leave a stale name behind if one ever were written there. project_id, not scope_key, is this
-- table's scope column (V124), so the scope string is rebuilt from it the way V120 wrote it.
UPDATE relay_source_events
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:'
            || COALESCE('project:' || project_id::text, 'system'))::bytea), 'hex')
WHERE topic = 'schedule.due';

-- An operator receipt records the topic an operator acted on. It is an audit row with no foreign
-- key to the topic, and renaming it keeps the audit answerable about the same topic.
UPDATE relay_operator_receipts
SET topic = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:project:' || project_id::text)::bytea),
                  'hex')
WHERE topic = 'schedule.due';

-- A routing branch that republishes into another topic names its destination here. The CHECK ties
-- publish_to to receiver='relay.publish', so this is expected to match nothing; it is included for
-- the same reason as the outbox above.
UPDATE relay_deliveries
SET publish_to = 'schedule.occurrence.'
        || encode(sha256(('plowshare.schedule.occurrence/1:' || scope_key)::bytea), 'hex')
WHERE publish_to = 'schedule.due';

DO $$
DECLARE fk RECORD;
BEGIN
    FOR fk IN SELECT * FROM schedule_topic_rename_keys LOOP
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s', fk.child, fk.name, fk.definition);
    END LOOP;
END $$;

DROP TABLE schedule_topic_rename_keys;

-- The rename either completed everywhere or this migration fails. Each table is named explicitly
-- rather than discovered, so a table added later with a topic column is a visible omission in the
-- next migration rather than a silently skipped row here. The two native-wake tables are asserted
-- rather than updated: their CHECKs forbid the value, and a row holding it would mean a CHECK was
-- dropped somewhere and the rename cannot be trusted.
DO $$
DECLARE remaining BIGINT;
BEGIN
    SELECT count(*) INTO remaining FROM (
        SELECT 1 FROM relay_topics WHERE name = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_publications WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_subscriptions WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_subscription_authority WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_admissions WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_deliveries WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_deliveries WHERE publish_to = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_consumer_leases WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_sdk_batches WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_approval_gap_recoveries WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_source_events WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_operator_receipts WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_native_consumers WHERE topic = 'schedule.due'
        UNION ALL SELECT 1 FROM relay_native_gap_recoveries WHERE topic = 'schedule.due'
    ) held;
    IF remaining > 0 THEN
        RAISE EXCEPTION 'the literal schedule topic name survives in % row(s)', remaining;
    END IF;
END $$;

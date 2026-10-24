-- An information event is carried by Relay's general message, not by a payload family of its own.
--
-- V188 added INFORMATION_CHANGED to the two payload-family CHECKs and an agreement constraint
-- binding the name 'information.changed' to it, mirroring what V117 did for schedule.due. That was
-- the wrong shape. Relay is a publish/subscribe substrate: it carries a contract and stores a
-- publication's content opaquely. A family per consumer makes every new consumer a change to the
-- broker, its persisted vocabulary, its codec, the protocol's kind allowlist and five SDKs -- and
-- an un-updated client then rejects a log page it has no business interpreting.
--
-- The carrier changes, and two things leave the publication with it.
--
-- 'detail' is the stage's free-form text and was owner-only: information.events showed it to the
-- home resource's author and to nobody else. A Relay publication cannot hold such a field. The
-- topic's log is readable by the topic's audience -- relay.log serves a project's topics to any
-- project member and reports a general message's content verbatim -- so carrying detail through the
-- broker made that log a second, ungated read path for it. A publication is also immutable and
-- retained for the topic's policy window, so a detail published there outlived the deletion of the
-- revision it described by up to four days. Both problems are the same problem, and removing the
-- field is what makes them go away: detail now stays only in information_revision_events, which is
-- keyed by revision, cascades away when the revision is deleted, and is read for the owner at read
-- time. The consumer contract is unchanged -- the owner still receives detail, the non-owner still
-- receives the empty string -- because the reply shape never carried it from here.
--
-- 'publisher' becomes a server-owned namespace, 'information:' || sha256(actor), following the
-- convention RelayMessageReview established with 'filter:'. Relay is content-agnostic and does not
-- reserve a topic name for one consumer, so the read path verifies the publisher before decoding
-- and declines to interpret a publication this subsystem did not issue. Hashing the actor also
-- stops the log naming the account behind a change to every project member. The hash matches
-- RelayPort.hash: SHA-256 over UTF-8, lowercase hex.
--
-- The remaining seven fields and their validation are exactly as V188 left them.
--
-- Existing rows are migrated rather than deleted. An information.changed topic's publications are
-- a live four-day delivery window that readers still hold durable positions into, so dropping them
-- would turn a carrier change into unacknowledged retention loss for every subscriber. The rewrite
-- is faithful in everything it keeps: field names and values are unchanged, the envelope version is
-- added, and the whole object becomes the TEXT payload's 'text'. jsonb_build_object renders the
-- object with sorted keys and no whitespace, which the codec parses as readily as Jackson's own
-- output, and the codec requires exactly these eight fields.

-- Publications first, while the topic still declares the family that identifies them.
--
-- 'detail' is dropped here and not copied anywhere: a rewrite that preserved it would leave the
-- leak in every historical row, which is the whole point of removing the field. The history in
-- information_revision_events already holds it for the owner, written in the same transaction as
-- the publication, so nothing is lost by dropping it from the payload. The version is concatenated
-- on the right so it wins over any 'schema' key a payload somehow already held.
UPDATE relay_publications p
SET publisher = 'information:' || encode(sha256(p.publisher::bytea), 'hex'),
    payload = jsonb_build_object(
        'text',
        ((p.payload - 'detail') || jsonb_build_object('schema', 'plowshare-information-event/1'))::text)
WHERE EXISTS (
    SELECT 1 FROM relay_topics t
    WHERE t.scope_key = p.scope_key AND t.name = p.topic
      AND t.payload_kind = 'INFORMATION_CHANGED');

-- Admissions copy a publication's payload when work is admitted. No routing program consumes
-- information.changed, so this is expected to match nothing; it runs because the admission table
-- carries the same family vocabulary and must not be left holding a value the CHECK below forbids.
UPDATE relay_admissions
SET payload_kind = 'TEXT',
    publisher = 'information:' || encode(sha256(publisher::bytea), 'hex'),
    payload = jsonb_build_object(
        'text',
        ((payload - 'detail') || jsonb_build_object('schema', 'plowshare-information-event/1'))::text)
WHERE payload_kind = 'INFORMATION_CHANGED';

-- The agreement constraint goes before the family it constrains is changed, since the topic rows
-- below violate it the moment they stop declaring INFORMATION_CHANGED.
ALTER TABLE relay_topics DROP CONSTRAINT relay_information_changed_family;

UPDATE relay_topics SET payload_kind = 'TEXT' WHERE payload_kind = 'INFORMATION_CHANGED';

-- The family vocabulary returns to the five general families V125 and V127 established. As there,
-- both tables move together.
ALTER TABLE relay_topics DROP CONSTRAINT relay_topic_payload_family;
ALTER TABLE relay_topics ADD CONSTRAINT relay_topic_payload_family CHECK (
    payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED'));

ALTER TABLE relay_admissions DROP CONSTRAINT relay_admissions_payload_kind_check;
ALTER TABLE relay_admissions ADD CONSTRAINT relay_admissions_payload_kind_check
    CHECK (payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE','LIFECYCLE','WAKE_REQUESTED'));

-- An information event is carried by Relay's general message, not by a payload family of its own.
--
-- V188 added INFORMATION_CHANGED to the two payload-family CHECKs and an agreement constraint
-- binding the name 'information.changed' to it, mirroring what V117 did for schedule.due. That was
-- the wrong shape. Relay is a publish/subscribe substrate: it carries a contract and stores a
-- publication's content opaquely. A family per consumer makes every new consumer a change to the
-- broker, its persisted vocabulary, its codec, the protocol's kind allowlist and five SDKs -- and
-- an un-updated client then rejects a log page it has no business interpreting.
--
-- Nothing about the notice changes. Its eight fields, their validation, the owner-only redaction of
-- 'detail' on the read path and the audience are exactly as V188 left them. What changes is the
-- carrier: the notice is now encoded by io.aeyer.plowshare.server.information
-- .InformationEventCodec into a TEXT payload, the same arrangement RelayToolCodec has always used
-- for hosted tool envelopes and RelayMessageReview for moderation reviews.
--
-- Existing rows are migrated rather than deleted. An information.changed topic's publications are
-- a live four-day delivery window that readers still hold durable positions into, so dropping them
-- would turn a carrier change into unacknowledged retention loss for every subscriber. The rewrite
-- is faithful and total: the notice's field names and values are unchanged, the envelope version is
-- added, and the whole object becomes the TEXT payload's 'text'. jsonb_build_object renders the
-- object with sorted keys and no whitespace, which the codec parses as readily as Jackson's own
-- output, and the codec requires exactly these nine fields.

-- Publications first, while the topic still declares the family that identifies them. The notice
-- object is spread into a fresh object carrying the version, so a payload that somehow already
-- holds a 'schema' key would be rewritten with the version this codec requires rather than
-- silently preserved.
UPDATE relay_publications p
SET payload = jsonb_build_object(
        'text',
        (p.payload || jsonb_build_object('schema', 'plowshare-information-event/1'))::text)
WHERE EXISTS (
    SELECT 1 FROM relay_topics t
    WHERE t.scope_key = p.scope_key AND t.name = p.topic
      AND t.payload_kind = 'INFORMATION_CHANGED');

-- Admissions copy a publication's payload when work is admitted. No routing program consumes
-- information.changed, so this is expected to match nothing; it runs because the admission table
-- carries the same family vocabulary and must not be left holding a value the CHECK below forbids.
UPDATE relay_admissions
SET payload_kind = 'TEXT',
    payload = jsonb_build_object(
        'text',
        (payload || jsonb_build_object('schema', 'plowshare-information-event/1'))::text)
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

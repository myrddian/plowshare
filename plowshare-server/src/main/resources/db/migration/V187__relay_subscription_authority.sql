-- Relay read-tier subscribers (decision 0038).
--
-- Subscribing and consuming require read authority on the topic, not write authority on the
-- project, and a consumer's own lease/offset write is authorized by subscriber identity plus that
-- read authority. Authority is therefore resolved once, when a subscription is established, and
-- recorded here. Delivery never re-resolves it: the per-account fan-out that asked a permission
-- predicate per candidate cost 3,996 ms for a single information event, and
-- knowledge_readable_scopes (V184) is the precedent for hoisting such a resolution out of the
-- per-row path.
--
-- A row is keyed by account as well as by subscription because a project-declared subscriber is
-- shared: several accounts may consume one subscriber, and each establishes its own authority.
-- topic_generation is the topic's incarnation at establishment. A subscription whose recorded
-- generation no longer matches relay_topics.generation is not valid, so resetting a topic
-- invalidates every subscription against it without touching this table. established_at bounds
-- the trust placed in a row; the maximum age is deployment configuration
-- (plowshare.relay.subscription-maximum-age), not a property of the schema.
--
-- The ON DELETE CASCADE is itself invalidation: removing a subscription or a topic removes the
-- authority established against it.
CREATE TABLE relay_subscription_authority (
    scope_key TEXT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256),
    topic_generation UUID NOT NULL,
    established_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope_key, topic, subscriber, account),
    FOREIGN KEY (scope_key, topic, subscriber)
        REFERENCES relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE
);

-- Revocation sweeps by account within one project scope, which is the membership-change key.
CREATE INDEX relay_subscription_authority_account
    ON relay_subscription_authority(scope_key, account);

-- Explicit invalidation is the primary mechanism, and the maximum age only bounds its failure. A
-- membership or role change is the revocation that must not wait for an expiry window, so it
-- happens in the same transaction as the change itself rather than in an application listener:
-- project membership belongs to the archive module, which must not depend on Relay, and a listener
-- that is not wired or not reached would silently turn the configured window into the real
-- revocation latency for every change.
--
-- Any change to a membership row withdraws that account's established authority, including a role
-- change that still leaves read access: re-establishing costs one insert on the next subscribe,
-- while keeping a row whose authority was re-decided elsewhere is the failure this guards against.
-- Inserting a new member deletes nothing, which is correct and cheap.
CREATE FUNCTION relay_subscription_authority_revoked() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM relay_subscription_authority a
    USING changed_rows c
    WHERE a.scope_key = 'project:' || c.project_id::TEXT AND a.account = c.handle;
    RETURN NULL;
END $$;

COMMENT ON FUNCTION relay_subscription_authority_revoked() IS
    'Withdraws Relay subscription authority when project membership changes (decision 0038).';

CREATE TRIGGER relay_subscription_authority_member_insert AFTER INSERT ON project_members
    REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT
    EXECUTE FUNCTION relay_subscription_authority_revoked();
CREATE TRIGGER relay_subscription_authority_member_update AFTER UPDATE ON project_members
    REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT
    EXECUTE FUNCTION relay_subscription_authority_revoked();
CREATE TRIGGER relay_subscription_authority_member_delete AFTER DELETE ON project_members
    REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT
    EXECUTE FUNCTION relay_subscription_authority_revoked();

-- A consumer group's name decides whose group it is, so two tiers cannot share one.
--
-- relay_subscriptions.subscriber is a CONSUMER GROUP and seen_through is that group's committed
-- offset. Two independent consumers sharing one group name do not each receive the stream -- they
-- divide it, and either may commit an offset past events the other never read. A read-tier reader
-- must therefore get its OWN group, not a share of the project's pipeline group.
--
-- The namespaces are disjoint in the schema rather than adjudicated by each writer. A work-tier
-- (project-declared) group keeps exactly the name rule it has always had, which cannot contain a
-- colon. A read-tier group is 'reader:<account>:<name>', and the constraint requires the row's
-- reader_account to be the account spelled inside its own name. So:
--
--   * a read-tier caller cannot express a project-declared name -- the server builds the stored
--     name from the authenticated account, and a name without the prefix fails the reader branch;
--   * a work-tier registration cannot express a reader name -- it has no reader_account, and the
--     work-tier branch forbids the colon;
--   * reader_account cannot be set wrongly, because it must match the name. It is a projection of
--     the name for indexing and revocation, not an independent fact a writer has to get right.
--
-- An earlier attempt made reader_account the fence on its own. It required every writer to
-- adjudicate ownership correctly, and the work-tier writer -- INSERT ... ON CONFLICT DO NOTHING
-- followed by a read of the existing row -- silently adopted a reader-owned row instead. An
-- asymmetric fence is worse than none, because it reads as protected.
ALTER TABLE relay_subscriptions ADD COLUMN reader_account TEXT;

DO $$
DECLARE existing TEXT;
BEGIN
    -- The V117 inline column check is replaced, not weakened: its work-tier branch is carried
    -- across unchanged. Dropping it by predicate rather than by name survives its auto-generated
    -- identifier having been assigned by a column definition.
    FOR existing IN SELECT conname FROM pg_constraint
        WHERE conrelid='relay_subscriptions'::regclass AND contype='c'
          AND pg_get_constraintdef(oid) LIKE '%subscriber%'
    LOOP
        EXECUTE format('ALTER TABLE relay_subscriptions DROP CONSTRAINT %I', existing);
    END LOOP;
END $$;

ALTER TABLE relay_subscriptions ADD CONSTRAINT relay_subscription_group_namespace CHECK (
    CASE WHEN reader_account IS NULL THEN
        -- The work tier, exactly as V117 defined it. No colon, so no reader name is expressible.
        length(subscriber) <= 160
        AND subscriber ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'
    ELSE
        -- The read tier. The owner is spelled in the name, and the column must agree with it.
        length(reader_account) BETWEEN 1 AND 256
        AND position(':' in reader_account) = 0
        AND length(subscriber) <= 512
        -- starts_with, not LIKE: an account containing % or _ must not become a wildcard.
        AND starts_with(subscriber, 'reader:' || reader_account || ':')
        AND substring(subscriber from char_length('reader:' || reader_account || ':') + 1)
            ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'
    END
);

COMMENT ON COLUMN relay_subscriptions.reader_account IS
    'Read-tier owner, matching the account inside the group name; NULL for work-tier groups (0038).';

CREATE INDEX relay_subscriptions_reader ON relay_subscriptions(scope_key, reader_account)
    WHERE reader_account IS NOT NULL;

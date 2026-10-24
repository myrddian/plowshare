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

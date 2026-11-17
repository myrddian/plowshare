-- New, account-local external names, not grants or utterance idempotency tokens.
-- This migration only creates new empty objects; bounded waits must fail rather than stall startup.
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE conversation_id_map (
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    owner_account_id UUID NOT NULL REFERENCES authority_accounts(id) ON DELETE CASCADE,
    source TEXT COLLATE "C" NOT NULL,
    external_conversation_id TEXT COLLATE "C" NOT NULL,
    -- Deferred NO ACTION preserves direct-target protection at commit while permitting an
    -- explicit purge transaction to remove a target and its project namespace in either order.
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, owner_account_id, source, external_conversation_id),
    CONSTRAINT conversation_alias_source CHECK (
        octet_length(source) BETWEEN 1 AND 128
        AND source ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    CONSTRAINT conversation_alias_external_id CHECK (
        octet_length(external_conversation_id) BETWEEN 1 AND 256
        AND external_conversation_id ~ '^[!-~]+$')
);

CREATE INDEX conversation_alias_target ON conversation_id_map(conversation_id);
CREATE INDEX conversation_alias_owner ON conversation_id_map(owner_account_id);

CREATE FUNCTION conversation_alias_target_agrees() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM conversations c
        JOIN authority_scopes s ON s.project_id=c.project_id
        JOIN authority_accounts a ON a.id=NEW.owner_account_id
        WHERE c.id=NEW.conversation_id AND c.project_id=NEW.project_id
          AND c.origin='turn' AND c.parent_id IS NULL AND c.agent IS NULL
          AND c.lifecycle IS NOT NULL AND c.budget_id IS NOT NULL
          AND s.kind IN ('PROJECT','PERSONAL') AND a.builtin IS NULL
          AND c.owner_handle=a.legacy_handle
    ) THEN
        RAISE EXCEPTION 'Conversation alias target is unavailable or incompatible'
            USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (OLD.project_id,OLD.owner_account_id,OLD.source,
        OLD.external_conversation_id,OLD.conversation_id) IS DISTINCT FROM
        (NEW.project_id,NEW.owner_account_id,NEW.source,NEW.external_conversation_id,NEW.conversation_id) THEN
        RAISE EXCEPTION 'Conversation alias identities cannot be rebound' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER conversation_alias_agreement BEFORE INSERT OR UPDATE ON conversation_id_map
    FOR EACH ROW EXECUTE FUNCTION conversation_alias_target_agrees();

-- Lifecycle/title/spending updates remain ordinary; an aliased root cannot move to another home.
CREATE FUNCTION conversation_alias_root_stays_fixed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.project_id,OLD.origin,OLD.parent_id,OLD.agent) IS DISTINCT FROM
        (NEW.project_id,NEW.origin,NEW.parent_id,NEW.agent)
        AND EXISTS (SELECT 1 FROM conversation_id_map WHERE conversation_id=OLD.id) THEN
        RAISE EXCEPTION 'An aliased conversation cannot change its root or project' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER conversation_alias_root_fixed BEFORE UPDATE OF project_id,origin,parent_id,agent ON conversations
    FOR EACH ROW EXECUTE FUNCTION conversation_alias_root_stays_fixed();

COMMENT ON TABLE conversation_id_map IS
    'Persistent account-local external conversation aliases. Mapping grants no authority; no automatic expiry or rebind.';

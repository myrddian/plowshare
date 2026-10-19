-- One identity/counter for an existing durable conversation allowance, including asynchronous
-- child conductors. Historical inference receipts remain unchanged; no effects are replayed.
ALTER TABLE conversations ADD COLUMN budget_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX conversation_allowance_identity ON conversations(budget_id);
CREATE FUNCTION conversation_allowance_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.budget_id<>OLD.budget_id THEN RAISE EXCEPTION 'Conversation allowance identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER conversation_allowance_identity_immutable BEFORE UPDATE ON conversations
 FOR EACH ROW EXECUTE FUNCTION conversation_allowance_identity_immutable();

-- An approved request retains its exact plan across interruptions rather than reselecting outputs.
CREATE TABLE information_reuse_preparations (
    actor TEXT NOT NULL,
    request_id UUID NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY(actor,request_id),
    FOREIGN KEY(actor,request_id) REFERENCES information_write_gates(account,request_id)
);
CREATE FUNCTION information_reuse_preparation_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.actor=OLD.actor AND NEW.request_id=OLD.request_id
       AND NEW.payload=OLD.payload-'binding' AND NOT (NEW.payload ? 'binding')
       AND ((SELECT availability FROM information_revisions WHERE id=(OLD.payload->'manifest'->>'revision')::uuid)='deleted'
         OR EXISTS(SELECT 1 FROM information_write_gates g JOIN information_revisions r ON r.id=(g.response->>'revision')::uuid
             WHERE g.account=OLD.actor AND g.request_id=OLD.request_id AND r.availability='deleted')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'prepared reuse candidate is immutable except deletion redaction';
END $$;
CREATE TRIGGER information_reuse_preparation_immutable BEFORE UPDATE OR DELETE ON information_reuse_preparations
FOR EACH ROW EXECUTE FUNCTION information_reuse_preparation_immutable();

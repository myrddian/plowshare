-- Historical routes cannot reconstruct original selectors/lifetime/relative timeout. NULL is
-- deliberately unverified, never a fabricated fingerprint and never permission to send again.
ALTER TABLE board_message_routes ADD COLUMN send_fingerprint TEXT
    CHECK (send_fingerprint IS NULL OR send_fingerprint ~ '^[a-f0-9]{64}$');
CREATE INDEX board_message_legacy_send_turn ON board_message_routes(sender,call_id text_pattern_ops)
    WHERE send_fingerprint IS NULL;

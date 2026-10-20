-- Historical routes cannot reconstruct original selectors/lifetime/relative timeout. NULL is
-- deliberately unverified, never a fabricated fingerprint and never permission to send again.
ALTER TABLE board_message_routes ADD COLUMN send_fingerprint TEXT
    CHECK (send_fingerprint IS NULL OR send_fingerprint ~ '^[a-f0-9]{64}$');

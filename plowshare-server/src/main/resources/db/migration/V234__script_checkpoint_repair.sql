-- An explicit repair changes only the active source. The completed journal and the original
-- script stay available for audit, and ordinary resume receipts retain their existing shape.
ALTER TABLE orchestration_resume_receipts
  ADD COLUMN checkpoint_sequence integer,
  ADD COLUMN previous_script_hash text,
  ADD COLUMN replacement_script_hash text,
  ADD COLUMN previous_script_source text,
  ADD CONSTRAINT orchestration_resume_repair_complete CHECK (
    (checkpoint_sequence IS NULL AND previous_script_hash IS NULL
      AND replacement_script_hash IS NULL AND previous_script_source IS NULL)
    OR (checkpoint_sequence IS NOT NULL AND checkpoint_sequence >= 0
      AND previous_script_hash IS NOT NULL AND previous_script_hash ~ '^sha256:[a-f0-9]{64}$'
      AND replacement_script_hash IS NOT NULL AND replacement_script_hash ~ '^sha256:[a-f0-9]{64}$'
      AND previous_script_hash <> replacement_script_hash AND previous_script_source IS NOT NULL)
  );

CREATE UNIQUE INDEX orchestration_resume_one_checkpoint_repair
  ON orchestration_resume_receipts (run_id) WHERE checkpoint_sequence IS NOT NULL;

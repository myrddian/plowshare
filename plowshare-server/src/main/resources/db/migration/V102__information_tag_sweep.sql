-- An explicit metadata rebuild can bypass the automatic untagged-only policy.
ALTER TABLE information_revisions ADD COLUMN auto_tag_requested BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX information_tag_sweep_candidates ON information_revisions(created_at,id)
    WHERE availability='active' AND NOT excluded AND NOT auto_tag_generated
      AND jsonb_array_length(auto_tag)=0 AND allowance_spent<allowance_total
      AND extracted_text IS NOT NULL AND btrim(extracted_text)<>'';

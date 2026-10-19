-- One existing processing counter pays all preparation inference. Historical costs are untouched.
ALTER TABLE information_revisions ADD COLUMN processing_budget_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX information_processing_budget_identity ON information_revisions(processing_budget_id);
CREATE FUNCTION information_processing_budget_locked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.allowance_spent<OLD.allowance_spent THEN RAISE EXCEPTION 'Processing spending cannot be reset'; END IF;
 IF NEW.processing_budget_id<>OLD.processing_budget_id THEN RAISE EXCEPTION 'Processing allowance identity is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER information_processing_budget_locked BEFORE UPDATE ON information_revisions
 FOR EACH ROW EXECUTE FUNCTION information_processing_budget_locked();

-- Additional evidence on the owning claim store, not a second budget or effect ledger.
ALTER TABLE inference_admissions ADD COLUMN preparation_revision UUID REFERENCES information_revisions(id);
ALTER TABLE inference_admissions ADD COLUMN preparation_generation BIGINT;
ALTER TABLE inference_admissions ADD COLUMN preparation_targets TEXT[];
ALTER TABLE inference_admissions ADD COLUMN preparation_published BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE inference_admissions ADD CHECK (
 (preparation_revision IS NULL AND preparation_generation IS NULL AND preparation_targets IS NULL AND NOT preparation_published)
 OR (preparation_revision IS NOT NULL AND preparation_generation IS NOT NULL AND preparation_generation>0
     AND preparation_targets IS NOT NULL AND cardinality(preparation_targets)>0
     AND (NOT preparation_published OR submitted)));
CREATE INDEX inference_preparation_pending ON inference_admissions(preparation_revision,preparation_generation)
 WHERE preparation_revision IS NOT NULL AND NOT preparation_published;
CREATE FUNCTION protect_preparation_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.preparation_revision IS NOT NULL AND (
   NEW.preparation_revision IS DISTINCT FROM OLD.preparation_revision
   OR NEW.preparation_generation IS DISTINCT FROM OLD.preparation_generation
   OR NEW.preparation_targets IS DISTINCT FROM OLD.preparation_targets
   OR OLD.preparation_published AND NOT NEW.preparation_published) THEN
   RAISE EXCEPTION 'Preparation claim targets are immutable';
 END IF;
 IF NEW.preparation_revision IS NOT NULL AND NOT EXISTS(SELECT 1 FROM information_revisions r
   WHERE r.id=NEW.preparation_revision AND r.processing_budget_id=NEW.budget_id
     AND NEW.ordinal<=r.allowance_spent) THEN RAISE EXCEPTION 'Preparation claim has no charged source'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_preparation_claim BEFORE UPDATE ON inference_admissions
 FOR EACH ROW EXECUTE FUNCTION protect_preparation_claim();

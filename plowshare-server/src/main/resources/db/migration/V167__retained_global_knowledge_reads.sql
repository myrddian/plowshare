-- Explicitly agreed R7: new ordinary admissions retain read-only Global knowledge eligibility.
-- Existing admissions remain exactly bounded; no historical run is widened by migration.
-- The generic sealed-row immutability trigger protects this column without a new bypass.
ALTER TABLE operation_admissions ADD COLUMN global_read BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN operation_admissions.global_read IS
 'Captured Global knowledge READ ceiling, rechecked live. Never permits Global execution, writes, or reading Global conversations.';

CREATE FUNCTION operation_global_read_parent_valid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM operation_admissions c JOIN operation_admissions p ON p.id=NEW.parent
     WHERE c.id=NEW.child AND c.global_read AND NOT p.global_read) THEN
   RAISE EXCEPTION 'child cannot acquire Global read outside its retained parent';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operation_global_read_parent_valid BEFORE INSERT ON operation_admission_parents
 FOR EACH ROW EXECUTE FUNCTION operation_global_read_parent_valid();

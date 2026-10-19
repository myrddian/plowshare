-- Evidence of admitted authority, not a grant or a generic recoverable-job service.
CREATE TABLE operation_admissions (
 id UUID PRIMARY KEY,
 executor UUID NOT NULL REFERENCES authority_accounts(id),
 executor_handle TEXT NOT NULL,
 scope UUID NOT NULL REFERENCES authority_scopes(id),
 project_name TEXT NOT NULL,
 source_account UUID NOT NULL REFERENCES authority_accounts(id),
 job_id TEXT UNIQUE,
 sealed BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE operation_admission_support (
 operation UUID NOT NULL REFERENCES operation_admissions(id),
 action TEXT NOT NULL CHECK(action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL','SET_AUDIENCE','MANAGE_ACCESS','MANAGE_SCOPE','ADMINISTER')),
 grant_id UUID NOT NULL REFERENCES authority_grants(id),
 PRIMARY KEY(operation,action,grant_id)
);
CREATE FUNCTION operation_admission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF EXISTS(SELECT 1 FROM operation_admissions WHERE id=NEW.operation AND sealed) THEN
     RAISE EXCEPTION 'sealed operation authority cannot gain support';
   END IF;
   RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR TG_TABLE_NAME='operation_admission_support' THEN
   RAISE EXCEPTION 'operation authority is immutable';
 END IF;
 IF (to_jsonb(NEW)-'job_id'-'sealed') IS DISTINCT FROM (to_jsonb(OLD)-'job_id'-'sealed')
     OR (OLD.sealed AND NOT NEW.sealed)
     OR (OLD.job_id IS NOT NULL AND NEW.job_id IS DISTINCT FROM OLD.job_id) THEN
   RAISE EXCEPTION 'operation authority cannot be widened or rebound';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER operation_admission_immutable BEFORE UPDATE OR DELETE ON operation_admissions
 FOR EACH ROW EXECUTE FUNCTION operation_admission_immutable();
CREATE TRIGGER operation_support_immutable BEFORE INSERT OR UPDATE OR DELETE ON operation_admission_support
 FOR EACH ROW EXECUTE FUNCTION operation_admission_immutable();

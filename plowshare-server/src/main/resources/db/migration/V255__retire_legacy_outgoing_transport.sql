-- Stop old servers/adapters for this cutover. The locks precede the preflight so an
-- old writer cannot introduce unresolved work between inspection and archival.
LOCK TABLE outgoing_work, outgoing_peers IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM outgoing_work
    WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELED', 'REJECTED')
  ) THEN
    RAISE EXCEPTION 'V255 outgoing retirement preflight refused: unresolved external work remains; retain the matching server and journals, reconcile through the owning lifecycle, and retry only after evidence-supported settlement';
  END IF;
END $$;

-- Keep every original value, including unresolved positive peer project keys.
-- Only project_key=0 denotes global scope; a missing name never grants that scope.
ALTER TABLE outgoing_work ADD COLUMN project_name_snapshot TEXT;
UPDATE outgoing_work AS work
SET project_name_snapshot = project.name
FROM projects AS project WHERE project.id = work.project_id;

ALTER TABLE outgoing_peers ADD COLUMN project_name_snapshot TEXT;
UPDATE outgoing_peers AS peer
SET project_name_snapshot = project.name
FROM projects AS project WHERE project.id = peer.project_key;

ALTER TABLE outgoing_work RENAME TO retired_outgoing_work;
ALTER TABLE outgoing_peers RENAME TO retired_outgoing_peers;

-- An archive must neither lose evidence through CASCADE/SET NULL nor prevent
-- later scope deletion through NO ACTION. Remove every outgoing foreign key.
DO $$
DECLARE
  archived RECORD;
BEGIN
  FOR archived IN
    SELECT conrelid::regclass AS relation, conname
    FROM pg_constraint
    WHERE contype = 'f'
      AND conrelid IN ('retired_outgoing_work'::regclass, 'retired_outgoing_peers'::regclass)
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', archived.relation, archived.conname);
  END LOOP;
END $$;

CREATE FUNCTION refuse_retired_outgoing_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Retired outgoing history is immutable; use controlled backup/audit procedures';
END $$;

-- Statement triggers also fence empty-table writes and TRUNCATE. There is no
-- runtime decoder, public archive API or active queue behind these relations.
CREATE TRIGGER retired_outgoing_work_immutable
BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON retired_outgoing_work
FOR EACH STATEMENT EXECUTE FUNCTION refuse_retired_outgoing_write();

CREATE TRIGGER retired_outgoing_peers_immutable
BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON retired_outgoing_peers
FOR EACH STATEMENT EXECUTE FUNCTION refuse_retired_outgoing_write();

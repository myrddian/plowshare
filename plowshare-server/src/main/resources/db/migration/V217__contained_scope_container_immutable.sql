-- Containment ancestry is fixed when a contained scope is created, and never afterwards.
--
-- V216 refuses only the row-local self-reference, container<>id, which leaves a deeper cycle --
-- A contains B contains A -- to be refused by something else. A cycle is not merely wrong data:
-- the authority path walks the container chain to bound containment depth, so a cycle makes that
-- walk loop or overflow instead of refuse, and a fence that depends on the walk terminating cannot
-- also be what proves the walk terminates.
--
-- V155's authority_scope_locked already refuses a *direct* UPDATE of a scope row, so no cycle was
-- constructible from application SQL, and that was verified before this migration was written.
-- What was open is the one UPDATE route that lock deliberately permits: pg_trigger_depth()>=2,
-- the nested-trigger path that exists for the project-to-scope mapping upsert. Reached that way, a
-- repoint of container succeeded. Today nothing takes it -- V216 excludes contained projects from
-- authority_map_project -- so this closes a reachable-in-principle route rather than a live one,
-- and it makes the invariant local to containment instead of a side effect of a lock about scope
-- identity. The ADR already names that upsert as the surface through which containment could be
-- lost retroactively and silently; a later widening of it must not be able to repoint ancestry.
--
-- With the repoint refused, the relation is acyclic by construction rather than by inspection. An
-- INSERT can only name a container that already exists (the self-reference CHECK, the foreign key
-- and V216's shape trigger, which requires an existing container row), so every new node points
-- only at an older one and each node has at most one container: a forest ordered by insertion. The
-- only way to close a cycle is to repoint an existing node. No walk can therefore revisit a scope.
--
-- Immutability also carries the containment guarantee itself: a child whose container could be
-- moved could be re-parented under a scope holding authority its original container never
-- mediated, which is enlargement by UPDATE, invisible to every narrowing check. V159's
-- operation_admission_immutable() is the idiom -- a BEFORE trigger that raises rather than a
-- constraint, because the rule compares NEW against OLD.
--
-- Deliberately narrow: only the container column is frozen, and the trigger sorts before
-- authority_scope_locked so a caller meets the refusal that names containment. The mapping upsert
-- never writes container, so IS DISTINCT FROM leaves its permitted UPDATE path working. Nothing
-- else about a scope row is made immutable here.
CREATE FUNCTION authority_scope_container_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.container IS DISTINCT FROM OLD.container THEN
   RAISE EXCEPTION 'authority scope containment is immutable';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER authority_scope_container_immutable BEFORE UPDATE ON authority_scopes
 FOR EACH ROW EXECUTE FUNCTION authority_scope_container_immutable();

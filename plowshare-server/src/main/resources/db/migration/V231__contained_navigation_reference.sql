-- A contained principal sees the reference stubs inside its own space, and nothing else gains or
-- loses visibility.
--
-- information_navigation_reference_visible decides whether a neighbour that the actor may not read
-- is nonetheless offered as a redacted STUB: the locked label a navigation page shows in place of a
-- document. It is the only admission arm at JdbcInformationNavigationRepository.referenceVisible
-- and at the reference_visible column of that repository's neighbour page, so what it refuses is
-- simply not offered. Those are two of its THREE call sites; the third paints no stub at all and is
-- named under THE THIRD CONSUMER below, where the reach of the new arm along it is stated. V229 admitted a contained principal to its own subtree's reads; this gate was
-- not among the five functions it replaced, so a child could read its own document and still be
-- told nothing exists beside it. ContainedNavigationReferenceSchemaTest characterized exactly that
-- before this migration: refused its own home's stub, while an ancestor's visibility into the
-- child's home already worked.
--
-- WHY A DISJUNCT AND NOT A CONJUNCT. This is the whole shape of the change, and getting it backwards
-- is the one way to break callers while every new test still passes. The function's body is a single
-- EXISTS whose admission test is `information_global_read_member($2)` -- ONE arm, with nothing
-- beside it. A contained-actor condition added as a conjunct would therefore apply to EVERY caller:
-- the ordinary accounts and service principals that reach this gate today are not contained actors,
-- so the conjunct would be false for all of them and the gate would refuse every reference stub on
-- the installation. The ancestor reads that work today -- a container's member seeing a stub in a
-- descendant's home, which the characterization recorded PASSING beforehand -- are exactly what
-- that would destroy. The membership arm below is consequently left byte-for-byte as shipped, and
-- the containment condition is added BESIDE it under an OR, which is the shape V229's five read
-- arms use for the same reason.
--
-- WHAT THE NEW DISJUNCT ADMITS, AND WHAT IT CANNOT. `contained_knowledge_actor($2)`
-- (V229:30; safe on a non-UUID actor, which is what this gate is asked with on every ordinary
-- request) selects the arm, and `knowledge_scope_readable($2,q.home_scope_id)` bounds it. For a
-- contained actor that second call collapses to contained_scope_readable's self arm (V223:156):
-- it matches the single contained_principals row whose scope_id IS the resource's home, so the
-- admission is the principal's OWN scope and no other. It is not a walk, so it reaches neither an
-- ancestor's home nor a descendant's; it is not a name match, so naming another home in the
-- selection does not reach it either; and because the row must be live, a revoked principal loses
-- the arm on the next statement. Confinement here is the direction contained_scope_readable's walk
-- already has -- upward from the descendant -- not a filter applied after a wider admission.
--
-- THE THIRD CONSUMER, so that the reach above is the whole reach. Besides the two stub call sites
-- named at the top, this gate is also OR-ed into InformationSql.relationshipNeighbour
-- (InformationSql.java:118), whose only caller today is
-- JdbcInformationInterchangeRepository.relationships (:45) -- the portable-bundle export's
-- relationship listing -- and only with outgoing=true, so the neighbour the gate is asked about is
-- always e.target. That path paints no stub, so a reader who had only the first two call sites would
-- conclude the arm cannot reach an export, and that would be wrong.
--
-- It is nonetheless bounded on every side by conditions the arm does not touch. The statement also
-- requires information_readable(information_navigation_head(e.source),...) -- which is
-- information_root_readable AND knowledge_dependencies_readable (V172:75) on the SOURCE's current
-- head, a full read authority that V229 already confines to the actor's own subtree -- plus the
-- source boundary; both endpoints must be among the resources of the 1-16 revisions the caller
-- itself passed in, each admitted by information_revision_allowances; and on the neighbour side the
-- new arm requires the TARGET's home to be the contained principal's own scope, by the same
-- contained_scope_readable self arm as above. The projected row is e.source, e.target, e.relation
-- and e.pinned_revision and nothing else: no title, no bytes, no extracted text. So the marginal
-- exposure this arm adds there is link metadata between two resources inside the actor's own space,
-- which V229 already opened for reads, and no row leaves that space.
--
-- knowledge_scope_readable is called rather than contained_scope_readable directly, matching V229's
-- knowledge_revision_readable, so the two gates ask one shared rule and cannot drift. Its own
-- membership branch cannot widen anything from under this disjunct: that branch requires
-- information_global_read_member($2), which is the arm this statement already has beside it.
--
-- NOTHING HERE PUBLISHES A CONTAINED HOME OUTWARD. The arm is keyed on the acting principal and
-- grants it a stub in its own home; it writes nothing, so it is not a route to SET_AUDIENCE, which
-- V229 deliberately left refused and which stays refused. It does not touch
-- information_global_read_member, which V229 also deliberately left alone: no account gains
-- membership, no role or credential row is created or consulted, and a contained principal still
-- has no admins, authority_accounts, authority_roles or service_tokens row of any kind. The
-- coarseness of the pre-existing account arm -- every active non-bootstrap account sees any
-- non-RESTRICTED stub, with no scope test -- is untouched in both directions; anUnrelatedAccountSeesNeither
-- pins it as a control so a later reader does not mistake it for something this migration did.

-- Reproduced from V172:85 with ONE semantic edit and no others: the single admission conjunct
-- `information_global_read_member($2)` becomes that same call OR the contained arm. The name,
-- signature, language, volatility and every other condition in the body -- the active, non-excluded
-- head revision, the non-QUARANTINED audience, the non-RESTRICTED home kind and the referenceHidden
-- test -- are unchanged. The one non-semantic difference is line wrapping, so a reviewer
-- normalising whitespace should find exactly the one edit described above.
CREATE OR REPLACE FUNCTION information_navigation_reference_visible(resource UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM information_resources q JOIN information_revisions r ON r.id=information_navigation_head(q.id)
   JOIN information_resource_policies policy ON policy.resource_id=q.id
   JOIN authority_scopes s ON s.id=q.home_scope_id
   WHERE q.id=$1
   AND (information_global_read_member($2)
     OR (contained_knowledge_actor($2) AND knowledge_scope_readable($2,q.home_scope_id)))
   AND NOT r.excluded
   AND r.availability='active' AND policy.audience<>'QUARANTINED' AND s.kind<>'RESTRICTED'
   AND NOT coalesce((information_navigation_policy(q.id)->>'referenceHidden')::boolean,true));
$$;

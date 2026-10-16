# ADR 0028: Document navigation and configurable reference policy

Status: accepted implementation contract, 15 October 2026. The next-milestone
recommendations were adopted for local implementation. See the
[usage guide](../authored-navigation.md) for the exact controls and activation.
This status records source work; it does not establish deployment.

The [proposal](../authored-knowledge-design.md) describes the experience. The
[implementation plan](../authored-navigation-implementation.md) identifies owners,
dependencies and acceptance tests. Baseline: local schema milestone
`49fdab28834cdecf61b2f3cfe279ecdedce1247a`, inspected 15 October 2026. Public main
remained `a6d642a9c8a12d419e6bb3c680617c7e826cdc3e` when fetched for this plan.
These are source observations, not deployment evidence.

## Platform need and existing boundaries

This is general platform work under [ADR 0001](0001-integration-boundary.md).
An SDK-only graph or client-side permission filter cannot enforce restrictions
across catalogue, retrieval, tools, evidence, jobs and export. Existing public
operations do not supply authoritative document relationships, configurable
reference disclosure, resource-head navigation or durable saved paths.

`information.link` and `information.unlink` currently link a revision into a
project collection. They are not document-to-document relationships and retain
their meaning. Existing information lists/facets enumerate and count revisions;
the new document projection must not silently change those results.

Retained input dependencies already restrict derived outputs. That mechanism
must remain authoritative even when ordinary navigation references do not
propagate restrictions. A reference is not evidence, a model input, membership,
sharing or permission to execute an instruction.

## Agreed identities and opening behaviour

- Namespace plus source name remains the named admission locator. Resource UUIDs
  identify continuing documents; revision UUIDs identify immutable retained content.
- Ordinary document selections and relationships follow the continuing target
  and open its latest current revision. An explicitly pinned target opens the
  named revision. Neither mode pins permissions or bypasses retention.
- Relationships belong to continuing source documents. A new body revision does
  not lose the source's connections or its backlinks. Relationship changes have
  their own versioned audit; they do not require a document-processing model call.
- Tags denote document sets. Facets intersect those sets. Tag membership does not
  create pairwise relationships or spread reference restrictions between members.
- Paths combine scopes, document selections, relationship traversals and facet
  steps. Breadcrumbs restore earlier selections. There is no HTTP URL requirement,
  directory hierarchy, canonical parent or identity derived from a displayed path.

Adopted head rule: choose the highest committed ordinal of the resource,
then evaluate its lifecycle and policy. Do not choose an older readable revision
as a fallback. A withdrawn/deleted/hidden latest target is unavailable. Explicit
history selection remains possible under its own current checks. This is a new
navigation contract; existing published-report search/finalisation rules remain.

## Three policy dimensions and authority

Policy evaluation distinguishes content access, reference disclosure and ordinary
reference propagation. These settings are server-owned authority records, not
schema fields, tags or client preferences.

| Dimension | Contract |
| --- | --- |
| Content | Existing authenticated revision access and retained-input restrictions remain required; configured document/project restrictions may further constrain access |
| Reference disclosure | Either require content access or explicitly permit a bounded reference representation without the body; hidden references reveal no identity, label, edge, preview or count |
| Propagation | Either ordinary references do not constrain their source, or they impose required target-policy dependencies transitively |

Convenience profiles can offer content-locked/reference-visible/non-propagating
and hidden-reference/propagating behaviours. The dimensions remain explicit;
reference visibility alone must not accidentally decide propagation.

Server administrators manage platform constraints and project/document policies,
including personal-document policy. Current project managers manage defaults,
constraints and allowed document overrides in their projects, within platform
requirements. Ownership, contribution or read access alone cannot change policy.
Administrative authority does not automatically grant body-read authority.

Reuse `AdminStore.requireServerAdmin`, `ProjectMembers.requireRole(..., MANAGER)`
and `ProjectAuthorization`; check live roles again when applying a mutation.
Policy commands need stable request IDs, expected policy versions, atomic audit
and ordinary durable receipt recovery. Do not invent role grants in metadata or
reuse the migration-operator controls as general policy administration.

Project defaults apply unless an administrator/manager sets a permitted document
override. Mandatory platform/project constraints cannot be weakened by an
override. Personal resources require an explicit platform baseline. A source
project cannot select a permissive setting to override a protected target.

Adopted initial default: no additional disclosure and restrictive reference
handling until an administrator/manager explicitly configures a project. Preserve
legacy data and APIs; create no inferred relationships or public reference labels.
The adopted policy fields and combination rules below define the profile matrix.

## Explicit relationship contract

A relationship has its own UUID, source resource UUID, target resource UUID,
optional pinned target revision, bounded relation name, creator, control version
and timestamps. A pinned revision must belong to the named target resource.
Relation names label connections; they contain no executable traversal or grant.
There is no initial relation-type registry or arbitrary query language.

The source resource owner may maintain ordinary relationships under existing
editing authority. That does not permit changing document policy. Creation also
requires the target's reference authority; reference-visible policy may permit
that authority without body access. Concurrent updates/removals require the
relationship's expected control version and a stable request ID.

Backlinks are the permission-filtered inverse of stored outgoing relationships,
not independent authored edges. If A references B, A may depend on B; generating
B's backlink to A does not make B depend on A. Removing a navigation edge has a
retained audit and cannot erase actual evidence/input dependencies.

The first implementation uses reference-visible representations reached
through an authorised source relationship, rather than a new global directory of
otherwise private documents. A caller-supplied path or edge ID is a lookup hint,
not a capability: recheck the source and target policy on every disclosure.
The closed DTO/fixture matrix permits only the resource UUID and explicitly
approved label. A private title is never copied as a locked-reference label.

## Effective access and consistent disclosure

For a required reference A -> B, effective content access to A requires A's own
authority and satisfaction of B's applicable restrictions. Follow required
dependencies transitively. For non-propagating navigation links, A remains
readable under its own rules; B's body still requires B's content authority.
Existing real input dependencies always remain required, independent of this mode.

Cross-project checks must evaluate each target's durable scope, current membership,
grants and credential ceiling. The existing single-selection input predicate must
not be assumed to authorise a private target in another project, even when an
account belongs to both. Service tokens retain their project/no-shared ceilings.
No traversal silently changes an execution identity or grants project membership.

Use a bounded, cycle-aware policy evaluator over a consistent database snapshot
of policies, heads and relationships. A visited node is not automatically allowed;
all own-access requirements and required edges in the reachable closure must be
satisfied. Missing records, incomplete evaluation or exceeded bounds deny access
without leaking the dependency. Mutations recheck captured control versions and
target heads before commit; a changed candidate conflicts rather than substituting
a new target or replaying an uncertain mutation.

Apply decisions before counts, paging, ranking, graph edges or content disclosure.
Every existing public read surface must consume the policy contract: information
and document reads, retrieval, tools, evidence, jobs/results, history, events,
inventory and any export. New Library controls alone cannot establish enforcement.
Administrative audit has separate administration authority and no embedded bodies.

Use disjoint result variants: readable document, policy-approved locked reference,
or ordinary unavailable refusal. A locked reference contains only approved fields,
never a partial `Revision` with hidden fields accidentally retained. Legacy views
can omit reference-only entries; they must not acquire private discovery metadata.

Do not cache cross-request allow decisions initially. Reevaluate policy when
serving a cached corpus projection or reopening a saved path. New heads, policy
changes, membership changes, withdrawal and deletion must affect later reads.
Content already retained in a source body is not made safe by hiding its link.

## Tightening, unlinking and release

Live navigation and durable confidentiality obligations are distinct. Removing an
edge changes navigation but does not automatically release a source that acquired
a required restriction. Switching a target back to a non-propagating policy must
not silently erase previously incurred obligations or historical input dependencies.

Adopted first release: allow policy initialisation and tightening; reject a
change that could widen an affected retained audience until an explicit authorised
release path is designed and implemented. A reference removal may succeed while
the access obligation remains. Report that state clearly to authorised managers.
The authorised implementation adopts this limit; a release workflow remains
separate work.

The obligation lifecycle is a hard implementation gate. In particular, changing a
widely referenced target to restrictive and back cannot rely on whichever sources
happened to be read in between. Define durable obligations or another complete,
transactional equivalent covering all affected live references and policy history.
Do not publish a permissive implementation with a declassification TODO.

## Navigation and saved selections

New document browsing returns one current entry per resource, with document-set
facet counts. Resolve the head before applying filters; an old matching revision
must not make a currently nonmatching document appear. Preserve separate explicit
revision/history operations and existing evidence offsets.

Represent a path as a bounded, closed sequence of typed steps: select scope,
select document/current or pinned target, traverse relation/direction, apply
existing facets/search. Store UUIDs and typed criteria, not route strings, rendered
captions, SQL, scripts or fetched result lists. Validate limits and reject unknown
steps. Breadth beyond existing facets is a separate querying capability.

Adopted saved-view scope: account-owned durable records first, each with UUID,
label, typed path, control version and stable mutation receipt. Project-wide shared
view editing is deferred; a saved project's selection is still useful to its owner.
Reopening reevaluates access and current heads. Saving a view does not read all
target bodies, freeze results or grant access. The implementation plan records
these adopted choices and their verification.

## Public boundary, persistence and rollout

Add new names for document navigation, relationships, effective policy and saved
views. Do not repurpose `information.link`, legacy revision lists or schema
metadata revision operations. Register names, DTOs, errors, model policy and native
SDK fixtures together through the owning generators only when implemented.
Use WebSocket operations; existing login/upload/Git HTTP boundaries remain.

Introduce narrow policy, relationship, navigation and saved-view interfaces, with
JDBC repositories owning SQL. Add new Flyway migrations without allocating numbers
in this plan. Body bytes, immutable revisions, collection links, input dependencies
and projection ownership keep their existing contracts.

Policy mutations are human administration controls, excluded from model tools.
Relationship/read tools still enforce their caller's identity, grants and source
authority. Adapters remain external public SDK consumers. No model call is needed
for navigation, reference changes or policy evaluation; explicitly configured
transition hooks keep their existing separate authority/accounting.

Negotiate a new capability before clients use the operations. Enable only after
all public reads enforce the effective policy and every affected SDK/client has
the new closed variants. Disabling new writes must preserve enforcement and reads;
rollback to an unaware binary requires a write stop/compatibility release, not
removing policy tables. Neither new capabilities nor proposed operation names
belong in generated catalogues before implementation.

## Adopted implementation choices

The highest committed ordinal defines the current document. Initial reference
handling is hidden and propagating. Locked references disclose a resource UUID and
an explicitly approved label only through authorised source relationships. Saved
views are account-owned, with one initial scope and complete facet-selection
snapshots. No cross-request allow cache is used. Paths contain at most 32 steps;
policy evaluation denies beyond 1,024 revisions in a required-reference closure.
Policy transitions refuse more than 10,000 affected documents; the retained
relationship graph is capped at 10,000 records, including removed edges.

Policy booleans are `ownerOnly`, `referenceHidden` and `propagate`; settings have
`defaults`, `mandatory`, `allowOverrides` and `referenceLabel`. Mandatory controls
combine with OR. Initialisation may widen an unreferenced baseline; subsequent
widening requires a release workflow and is refused in this version. Project
managers administer their projects; server administrators also manage platform
and personal-document policy.

Durable obligations persist after unlink. A transactional fixed-point admission
also records incoming dependencies through an intermediary that already carries
an obligation, even when its own defaults are non-propagating. An intermediary
cannot waive inherited restrictions. Counts and existing revision reads use the
central SQL enforcement predicate; navigation reads use one repeatable snapshot.

Navigation control mutations use independent typed commands, optimistic versions,
atomic audit and request receipts. They are not body-revision transitions and do
not invoke revision-transition/model hooks or create target-body input records.
Actual readable metadata returned to a model still enters its retained input
ledger; approved locked references do not imply body consumption. Policy and
saved-view controls remain outside model tools. Configured revision hooks keep
their existing authority and accounting.

Writes require `plowshare.information.navigation-writes-enabled=true`; the default
is false. Disabling writes retains read enforcement. Existing schema writes and
legacy revision/collection operations retain their contracts. The implementation
adds V147 without changing earlier migrations.

## Consequences and review gate

The model permits visible references to locked content without ordinary-link
virality, while projects can require confidential references and propagated
restrictions. It also requires central, consistent read enforcement, a durable
obligation lifecycle, independent document-set projections and new SDK variants.

The adopted choices close the implementation gate. The linked plan records the
verification commands and results. Review the complete change and its activation
requirements before publication; local checks are not deployment evidence.

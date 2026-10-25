# ADR 0042: Nested Application containment

Status: accepted for slice one, 25 October 2026, following the user's
authorization to build the containment primitive alone; the remaining capability
is proposed. Written to satisfy
[the integration boundary decision](0001-integration-boundary.md) for the design
in [nested Applications](../superpowers/specs/2026-10-25-nested-applications-design.md).
That design is the binding authority; this record states its decisions in the
repository's decision form and does not add to them. The recursive
harness-improvement workflow that motivated the capability is a separate design
and is not authorized here.

## Platform need

An Application can be deployed, validated, versioned and activated as a file
tree, but only by a server administrator and only as the single Application that
owns a project. Nothing can create an Application from inside another one. The
general need is containment: an Application runs Applications inside itself,
under configuration it owns. A parent creates a child, writes the child's
manifest and resources, reads the child's work and destroys it. The child's
authority is a strict subset of the parent's at every surface and narrows across
each containment hop. The child cannot name, read, address or reach anything
outside its own subtree, including the parent. Every edge in or out belongs to
the parent, which must hold the permission it mediates.

The need is independent of any one consumer. Sandboxed evaluation of
agent-authored configuration, per-tenant variants under one owner, comparing two
skill or tool trees against the same task, and running a third-party Application
under an owner's grants without granting it any of them all require a
parent-owned, confined, disposable Application. A configuration an agent wrote
moments ago is untrusted input and must run with strictly less authority than its
author, which widening the parent's permissions or pre-installing every candidate
as an ordinary delegable definition both invert.

## Decision

A nested Application is a nested project. `InformationAccess` already records
that an account is an identity and not a container, and that projects in any form
are the resources identities reach; containment is a relation over existing
projects and existing authority records, with no second authoritative store. The
relation is explicit and persisted in a new Flyway migration. A child's project
name is opaque and server-generated and carries no containment path, because a
readable path is what [ADR 0021](0021-tool-and-relay-topic-name-capacity.md)
cannot absorb at depth. A namespace, where one is needed, travels as a resolution
context beside the name and does not prefix tool or Relay topic names.

Containment is expressed in the substrate of
[ADR 0030](0030-authority-substrate-and-explicit-scopes.md), not on `Home`. A
contained scope is a fifth kind of the sealed `AuthorityScope` interface,
introduced through `admit` as that decision requires so that each caller states
which authority concern it means. Sealed-interface exhaustiveness makes
confinement structural: the compiler locates every site that must decide what a
contained scope means, so containment does not depend on each call site
remembering a check. A child is contained by resolution and admission. A child
reaching above or beside itself is a resolution failure, not a denied
permission. `OperationAuthority.derive` is generalised across a containment hop
and keeps its guarantee that a derived authority never exceeds its parent's.
Read narrowing follows the monotonic model of `KnowledgeReadScope.intersect`,
which only ever intersects and grants nothing, extended to a hop that changes
home. New work does not depend on the null representation of global that
[the global null removal plan](../global-null-removal-plan.md) is dismantling.

Admission to create a child is a distinct rule derived from the parent's held
authority. The child receives its own ephemeral principal, created and owned by
the parent and reaching only the containment subtree. Its actions are neither
attributable to nor exercisable as the parent, which departs deliberately from
the downward inheritance of execution identity in
[ADR 0011](0011-application-scoped-agent-delegation.md). A child written after
its parent was admitted is not in the parent's declared `calls:` list, so
containment adds a resolution path for such a target without weakening that bound
for ordinary delegation.

Visibility is one way. The parent reads the child's execution trajectory,
deliverables and resources through typed server contracts. The child cannot read
outward. An external Application or SDK reaches a child only by addressing the
parent, and the parent proxies only permissions it holds, so authority narrows at
each hop. The operations this record adds are the parent's own typed ones:
create, configure, read trajectory, read resources and destroy. Every other
attempt to address a child is refused.

Three distinct inventories carry the confinement proof, and none stands in for
another. The first lists the surfaces a child manifest can declare, each reduced
to the parent's hold or refused and never honoured as written: Relay topic ports
and audiences ([ADR 0010](0010-relay-processing-and-topic-policy-authority.md),
[ADR 0039](0039-relay-audiences-beyond-a-project.md)), tool bindings and
provider declarations ([ADR 0006](0006-application-scoped-live-tools.md),
[ADR 0007](0007-provider-scopes-and-interactive-tool-policy.md)), FileStore
destinations and writable areas, named swarm types
([ADR 0004](0004-named-application-swarms.md)), schedule ownership
([ADR 0020](0020-application-schedule-operational-authority.md)) and service
account attribution and spending pools
([ADR 0025](0025-service-account-attribution-and-spending-pools.md)). Refusing a
surface is an acceptable first slice; honouring a child's declaration of one is
not. Task 5 owns this inventory. The second is the compiler-forced list: every
site the sealed `AuthorityScope` interface forces to decide what containment
means, each marked fenced or refused. It is recorded in the final section of this
record by Task 2. The third lists every place the server makes a project
addressable without anyone naming it. It consumes the second as one input but is
not equal to it, because it includes sites that never switch on `AuthorityScope`,
among them the `project.list` SQL and the union and git provisioning. Known
starting points are `project.list` and the kind and `routingIdentity` surface,
messaging `from_project` and `to_project`, Relay project-scoped subscribers
([ADR 0038](0038-relay-read-tier-subscribers-project-scoped.md)) and audiences
(ADR 0039), startup schedule reconciliation, conversation search, and the union
and git provisioning that ordinary project creation performs per
[personal space](../personal-space.md); a child must not silently acquire a
working tree and a git hub. Each entry receives a decision, fenced or refused.
Task 8 owns this inventory, which is the confinement proof.

Children are ephemeral. The parent creates and destroys them and a destroyed
child's source is collected. The server allocates a child's source area under a
server-configured nesting root rather than accepting a parent-supplied
destination, because a parent typically holds no FileStore `MANAGER` over its own
retained source and proxying could not yield a legal destination. Destroying or
cancelling a parent destroys every descendant, and revoking the parent's
authority mid-execution stops descendant work. An interrupted create must leave
no orphan project, uncollected revision directory or live principal; crash
recovery is part of the capability. A parent revision change refuses while any
child is live, so children never outlive the configuration that authorized them
and are never destroyed silently during an ordinary update. A child has its own
writable knowledge and memory home inside its containment, which the parent reads
into.

Depth and sibling breadth are bounded by server configuration and not by the
requesting agent, and the bounds refuse rather than degrade. The package limits
of [ADR 0005](0005-application-deployment.md) apply per child and the live
aggregate across a parent's descendants is bounded separately, since breadth and
depth each multiply the budget. Nesting is opt-in per Application, declared in
the manifest and validated at deployment, so an existing Application cannot
acquire the capability by upgrade alone.

A child allocates its own allowance under a ceiling its parent sets at creation
and may reduce to zero. Child spend is attributed to the child.

One-way visibility is a property of the questions, not of a filter on the
answers. `application.child.list` and `application.child.trajectory` take the
caller's own conversation and no container field, so the scope read is the scope
the caller's retained admission is already admitted as: a child asking lists what
*it* contains, which is nothing in the ordinary case, and there is no argument
through which it could ask about a sibling. `trajectory` names one child and
proves it by walking the stored containment relation upwards from that child to
the caller's scope, which is the one direction the rows record, so a grandparent
reads through the chain while a sibling, a container and an unrelated scope fail
to resolve rather than being denied a permission later. Neither read resolves a
scope, for the reason `application.child.ceiling` does not.

A child's read ceiling is its own home with Global knowledge read off, which is
where `contain` already leaves a contained admission, and
`KnowledgeReadScope.intersect` can only narrow it further, so a selection
arriving on the wire cannot lift it. Inside the SQL boundary that ceiling becomes
`knowledge_readable_scopes(account, 'contained:…')`, which resolves nothing above
or beside the subtree, and `knowledge_document_admitted` applies `bool_and` over
the whole dependency closure — so a child revision whose closure reaches a row
outside the subtree is refused entirely rather than admitted through a dependency
the child never named.

**What that leaves open.** `knowledge_scope_readable` admits
`ACCOUNT_KNOWLEDGE`, the `GLOBAL_KNOWLEDGE` builtin, `PROJECT` and `PERSONAL`,
and a `CONTAINED` scope is none of those — the SQL-side closure this slice
shipped deliberately. So a reader under a contained home resolves an *empty*
readable set: the fence holds in full, and a contained Application cannot read
its own writable home either. Writes into that home are not refused and land
under the child's own scope, which is what this slice decided; making those rows
readable *to the child* is an authority question for whatever executes a
contained child, and it is recorded here rather than widened by a read task.

A child's output is untrusted input to its parent. The parent reads trajectories
and deliverables in order to act on them, which makes those reads a
prompt-injection surface by construction. Child output is data, never
instruction, and any consumer that composes configuration or action from it must
treat it so. The first slice ships no consumer, so the obligation is recorded
here to travel with the capability: it binds every later consumer.

## What this does not do

It does not change the administrator-gated `application.deploy` path, its
retained release history, receipts or activation semantics for ordinary
top-level Applications. The child path has its own admission rule. It is not a
relaxation of the existing one, and top-level deployment keeps its gate and its
destination FileStore `MANAGER` check exactly as they are.

It does not build a parent/child allowance counter. No shared counter exists
today; [the lineage slice](../authority-substrate-slices/11-lineage-authority-specification.md)
records that nested and model-triggered starts remain refused until a supported
sharing or allocation contract exists, and nested orchestrations allocate fresh
budgets. A child allocates its own allowance under a parent-set ceiling, so
spend is bounded by the parent without that contract. ADR 0025's immutable
attribution and the rule that delegation cannot change the accounting account are
untouched. A true shared counter remains separate work with its own record.

It does not add a general request-forwarding surface. Messaging confinement is a
narrowing of existing Relay and Application scoping, so a child cannot message
anything its parent cannot. A forwarding path would require every forwarded call
to re-prove containment, which is confinement as a check and is rejected.

It does not promote a child to a retained top-level release; that is a separate
explicit act with its own authorization. It relaxes no `GLOBAL_READ`
eligibility, audience policy or Global knowledge custody for any existing
principal. It adds no persistence framework, generic SQL helper or second store,
and it does not make process or container isolation the confinement mechanism;
[command isolation](../command-isolation.md) is orthogonal. It does not build the
recursive harness workflow, and no contract may name harnesses, probes,
candidates or improvement.

## Alternatives and consequences

SDK-only alternatives fail. A client cannot create a project, principal and
source area atomically or fence them from the server side; it can only hold an
administrator credential, which grants more authority than the parent and not
less. Pre-installing candidates as ordinary delegable definitions requires the
administrator gate for every candidate and gives each the parent's inherited
identity. Widening the parent's grants so it can run candidates inverts the
requirement that generated configuration run with less authority than its author.
An integration or adapter that needs this would be a failed integration under
ADR 0001, which is why the capability is a separately authorized platform task.

Building containment on `Home` or on a path encoded in the project name was
rejected: `Home` is legacy for authority purposes and cannot spell the
difference between the non-project concerns, its null-global representation is
being removed, and a readable path exhausts ADR 0021 capacity at depth. Letting
children survive a parent revision change, or destroying them silently, was
rejected because both produce the orphans the design names as failure.
Accepting a parent-supplied source destination was rejected because it makes the
capability unavailable to the Applications that need it.

Authorization and lifecycle impact is as follows. Authority can only narrow
across a hop, and the parent cannot grant what it does not hold, tested per
surface. Ownership, cancellation and destruction cascade downward from the
parent, and accounting stays with the child under its parent-set ceiling.
Idempotency and recovery follow ADR 0005's request-UUID receipts for packaging
and add reliable collection with recovery for an interrupted create, inverting
ADR 0005's explicit non-collection of staged source for the case where
destruction is the normal path.

Compatibility: existing Applications, projects, receipts and the `Home` API are
unchanged, and nesting is opt-in, so no existing Application gains the capability
by upgrade. The schema change is a new migration and no shipped migration is
edited. The migration is additive and its tables and contracts are retained rather
than dropped. Returning to an older binary needs a schema-aware compatibility
release or a write stop, as this repository already requires elsewhere. Operation and DTO contracts are regenerated
across the Java, TypeScript/Node, Python, Go and .NET SDKs through their owning
scripts and affected clients change together. A sealed `AuthorityScope` kind is a
source-incompatible change inside the server that the compiler enumerates, and
it is not a wire change for existing scopes.

## Verification

Confinement is the product, so verification shows escape attempts failing as well
as the happy path working. A child naming its parent, a sibling, an uncontained
project or the Global tier is refused at resolution. A child reading knowledge
receives nothing from outside its subtree, including through audience-driven rows
it never named and through dependency closure. A child manifest declaring a
provider, Relay port, audience, swarm type, schedule or FileStore destination
beyond the parent's hold is reduced or refused. An external principal addressing
a child directly is refused, and addressing the parent succeeds only where the
parent holds the proxied permission. Narrowing holds at depth, and the depth and
breadth bounds refuse. Destroying a parent destroys descendants and revoking its
authority stops descendant work. An interrupted create leaves no orphan project,
revision directory or principal, and collection is observable. Siblings cannot
observe one another's resources, trajectories or deliverables.

Tests follow the database test policy in [AGENTS.md](../../AGENTS.md): mocked
repository interfaces and in-memory fakes by default. Real PostgreSQL behind
`@Tag("full-db")` and `-PfullDb` is reserved for the migration, the containment
constraint, concurrent create and destroy, and transaction rollback.

## Exhaustiveness inventory

This is the second of the three inventories above: every site the sealed
`AuthorityScope` interface *forced* to decide what containment means when
`AuthorityScope.Contained` was admitted as its fifth permitted implementation.
The list is exactly the set of exhaustiveness errors `javac` raised across
`plowshare-server`'s main and test sources, so it is produced by the compiler
rather than by inspection. It is neither the list of surfaces a child manifest
declares, which Task 5 owns, nor the list of places the server makes a project
addressable without anyone naming it, which Task 8 owns and which consumes this
list as one input.

| Site | Decision |
| --- | --- |
| `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcIdentityRepository.java:84`, the `switch (scope)` in `home(AuthorityScope)`; the added branch is line 94 | **Refused.** `CallerFault`: a contained scope has no addressable project home. |

The compiler forced exactly one site. That it forced one, and not many, qualifies
this record's claim that sealed-interface exhaustiveness makes confinement
structural. The substrate's other authority decisions are written as `instanceof`
tests over the kinds they admit, not as exhaustive switches:
`PinnedOperationAuthority.admit` admits only `Project` and `Personal`,
`LiveScopedAuthority.covers` widens `ORDINARY_PROJECT_CONTROL` only for
`Project`, and `LiveActingAuthorization` and `AccountRequestProjects` likewise
require a named kind. Such a test excludes a new kind silently rather than forcing
a decision about it. The exclusion is usually fail-safe, and in the Java
authority path it was: a contained scope is inadmissible, uncoverable and
unactable-upon there. It was not fail-safe in the database. V156 declares its own
`RESTRICTED`-only predicates, and three live ones admitted a contained scope until
V195 closed them — `authority_live_grants()`, which would have reported a grant
against a contained scope as live; `authority_grant_insert()`, the database's own
grant admission check; and `authority_membership_changed()`, which minted a
`MEMBERSHIP` grant against a contained scope from a row trigger on
`project_members`, with no application code involved and so beyond the reach of
any Java predicate. So the statement that holds is the narrow one: *after* V195
and the repository exclusion, a contained scope is inadmissible, uncoverable and
unactable-upon. Nothing about the sealed interface made that true, and the
compiler reported none of the three.

A silent fail-safe exclusion is not a proof, because
nothing distinguishes a site that excluded containment deliberately from one that
excluded it by accident, and nothing will force the question if such a site is
later widened.

Task 8's confinement proof must therefore come from an explicit audit of the
server's addressability surfaces, not from compiler output. This list is one
input to that audit and no more.

The most significant surface found so far, recorded here so Task 8 inherits it:
V155's `authority_map_project` is an upsert keyed on the UNIQUE
`authority_scopes.project_id`, driven by `authority_project_inserted`, an `AFTER
INSERT OR UPDATE` trigger on `projects`. It classifies any ordinary-looking
project as `PROJECT`, so without intervention a child project would acquire a
fully addressable project scope that no caller named, and — because the upsert
carries `DO UPDATE SET kind=EXCLUDED.kind` and `authority_scope_locked` permits a
nested-trigger update that leaves `project_id` unchanged — any later update of
that project row would convert an existing contained scope back into an ordinary
one. That is not an escape hatch but retroactive, silent loss of containment, and
it is the clearest example of the server making a project addressable without
anyone naming it. V195 closes it: a contained project carries a reserved
`contained:` name prefix and is excluded from the mapping by name, exactly as
`personal:%` and `client:%` already are, and `authority_scope_shape` gains a
`CONTAINED` branch requiring such a project and an existing container while its
`PROJECT` branch excludes the prefix. The prefix is a fixed-length classification
marker, constant at any depth; containment ancestry stays in the `container`
column, so no readable name grows with depth and
[ADR 0021](0021-tool-and-relay-topic-name-capacity.md)'s capacity bound is
untouched.

`authority_membership_changed` belongs beside it. An ordinary `project_members`
row for a child project minted a `MEMBERSHIP` grant against that project's scope
from inside an `AFTER INSERT OR UPDATE OR DELETE` row trigger, so a child could
have acquired real authority with no application call to refuse and nothing named
in a manifest. V195 makes the trigger resolve nothing for a contained scope, as
it already did for a restricted one. It is the clearest case of a confinement
decision living in SQL rather than in Java, and it is why this inventory cannot
be the proof.

Because the prefix is now load-bearing, the per-call-site prefix checks are a
list (c) surface in their own right. The following exclude `personal:` and
`client:` but not `contained:`, so each is a place that must decide whether a
contained project is in or out of its answer. `ProjectStore.ordinary` has been
reserved as part of this slice, since a project a caller named `contained:x`
would otherwise be created with no authority scope at all; the rest are recorded
for Task 8's audit and are not changed here.

| Site | Form |
| --- | --- |
| `plowshare-server/.../archive/JdbcProjectMembers.java:78` | `startsWith("personal:")` |
| `plowshare-server/.../archive/JdbcProjectMembers.java:137` | `startsWith("personal:")` |
| `plowshare-server/.../auth/ServiceAccounts.java:137-139` | `personal:`, `Personal:`, `client:` |
| `plowshare-server/.../agents/WorkspaceApplicationPolicy.java:47` | `startsWith("personal:")` |
| `plowshare-server/.../applications/SourceApplicationDeployments.java:63` | `startsWith("personal:")` |
| `plowshare-server/.../union/UnionFrames.java:166` | `startsWith("personal:")` |
| `plowshare-server/.../events/FileScheduleDefinitions.java:82-83` | `personal:`, `Personal:` |
| `plowshare-server/.../ws/ScheduleListHandler.java:37-38` | `personal:`, `Personal:` |
| `plowshare-protocol/.../ApplicationIdentity.java:100-102` | `personal:`, `Personal:`, `client:` |
| `plowshare-protocol/.../HostedToolScopes.java:309` | `startsWith("client:")` |
| `plowshare-server/.../archive/ProjectStore.java:1289` | SQL `name NOT LIKE 'client:%'` |
| `plowshare-server/.../archive/JdbcProjectMembers.java:59` | SQL `p.name LIKE 'client:%'` |
| `plowshare-server/.../archive/JdbcProjectMembers.java:175-176` | SQL `LIKE 'personal:%'`, `LIKE 'client:%'` |
| `plowshare-server/.../events/JdbcScheduleStore.java:38` | SQL `NOT LIKE 'client:%'`, `NOT LIKE 'personal:%'` |

Fourteen sites, found by grepping for both the `startsWith` and the SQL `LIKE`
forms of the two existing prefixes across `plowshare-server`, `plowshare-protocol`
and `sdk`. Three further matches are the prefix *definitions* rather than
exclusions and are listed separately for that reason:
`archive/ClientProjects.java:15` and `personal/PersonalSpaces.java:67` and `:74`.
A contained project is excluded from the authority mapping by name, so several of
these would hand back a project with no scope rather than refuse; which of the
fourteen should refuse, which should filter and which are already correct is
Task 8's decision, not this record's.

The refusal at `home(AuthorityScope)` is the consequential one. A contained scope
carries an ordinary `projects.id`, so answering `value.projectId()` there would
have compiled, returned a project name, and made a child project addressable
through every `Home`-keyed path in the server — the silent default this record
rejects. Whether a contained scope has an outwardly visible home, and to whom,
is decided where containment is resolved.

That question has since been decided, at the containment hop, which is where
containment is resolved. `IdentityRepository.containedHome(AuthorityScope.Contained)`
answers the home of a contained scope's own confined execution, and
`OperationAuthority.contain` is its only caller. The refusal at
`home(AuthorityScope)` is unchanged and remains load-bearing: every existing
caller holds a general `AuthorityScope`, so answering there would have handed a
child project a usable home through every `Home`-keyed path at once, whereas a
method whose parameter type is `Contained` cannot be reached without first
matching on containment. The reverse direction stays closed —
`project(String)` still refuses to resolve a contained scope from a project name
— so a contained admission knows its own home while nothing travels from that
name back to the scope. The consequence is that a contained admission is inert:
`require` proves an admission's retained home and scope still agree by resolving
the home's project name back to a scope, which a contained scope refuses, so no
live-authority surface accepts one. Teaching `require` to walk the container
chain to its container's live grants belongs to the task that makes a contained
scope resolvable, and must change the home/scope agreement check and the ancestry
walk together, since a hop changes both by design.

**That limitation is lifted for resolution and for every operation this slice
ships, and what remains limited is named below.** It was recorded here as: the effective depth limit is 1 whatever is
configured, because `contain` bounds depth by walking the container chain and the
walk read each enclosing scope through `IdentityRepository.scope(ScopeId)`, which
refuses a `CONTAINED` row — so the walk could only observe an uncontained parent
and compute depth 1. The three pieces this record said had to move together have
moved together. `IdentityRepository.contained(ScopeId)` answers "contained, or
nothing": its return type is `Optional<AuthorityScope.Contained>`, so a caller
that asks has already decided what containment means for it, every pre-existing
scope answers empty, and `scope(ScopeId)` and `project(String)` both keep
refusing a contained row — nothing travels from a child's project name back to
its scope. The depth walk follows that read instead, so a configured depth above
1 is reachable and is exercised at depth 2 by a hop that succeeds rather than by
one that refuses for the wrong reason. The home/scope agreement check and the
ancestry walk are the two gates below.

Lifting it in `contain` was not by itself enough, and the gap is worth recording
because it was invisible from the resolution side. `application.child.ceiling`
resolved its container with `IdentityRepository.scope(ScopeId)` — the same read
the depth walk had to stop using — so reconfiguring a child, including the
reduce-to-zero this slice requires, failed closed for every child whose container
was itself contained. It failed closed rather than open, so it was never a hole,
but a produced operation that does not work at depth makes a flat claim that the
limitation is lifted untrue. It now takes its container's authority from that
container's own live conversation, exactly as creation does, and resolves no scope
at all; `SourceContainedApplications` holds no `IdentityRepository`, which is the
structural form of that and is asserted.

What is still limited: the walks are bounded by the configured `maxDepth` and
refuse beyond it, which is the intended fence rather than a limitation; and
nothing in this slice *executes* a contained child, so narrowing at depth is
exercised by resolution, admission and reconfiguration but not yet by a running
child. Depth is proved here by `contain` succeeding at depth 2, by `require`
walking two containment edges to the nearest enclosing uncontained scope, and by
a ceiling change against a contained container — not by inference from the
one-hop case.

V196 freezes `authority_scopes.container` after insert, so containment ancestry
cannot be repointed and the relation is acyclic by construction: an insert can
only name an existing container, so the only way to close a cycle is to move an
existing node. V155's `authority_scope_locked` already refused a direct UPDATE of
a scope row; what was open, and is verified to have been open, is the one UPDATE
route that lock deliberately permits — `pg_trigger_depth()>=2`, the nested-trigger
path that exists for the mapping upsert recorded above. Nothing takes that route
today, so V196 closes a route reachable in principle rather than a live hole, and
it makes the invariant local to containment instead of a side effect of a lock
about scope identity. It matters because the depth bound walks the container
chain: a cycle would make that walk loop rather than refuse, and a fence that
depends on the walk terminating cannot also be what proves it terminates.

Two further refusals were added in the same change although the compiler did not
force them, and they are recorded here only so the inventory is not read as the
complete set of containment decisions:

- `JdbcIdentityRepository.read(ResultSet)` names `CONTAINED` explicitly and
  refuses, rather than letting a contained row fall into the generic `default`.
  Nothing creates such a row yet, so nothing may read one back into a resolved
  scope.
- `JdbcScopedGrantRepository`'s issuance lock excludes `CONTAINED` beside
  `RESTRICTED`. A child's access derives from its container and narrows across
  the hop, so a grant issued directly against a contained scope would be
  authority no container mediated.

Relay is untouched. [ADR 0037](0037-events-and-messaging-on-relay.md) already maps
`ADMINISTRATION` and `RESTRICTED` to a loud failure with no fallback topic and a
contained scope follows that; actually fencing Relay belongs to Task 8, and
[ADR 0039](0039-relay-audiences-beyond-a-project.md) defers whether Relay keys
topics on `authority_scopes.id` to a later slice, so no Relay scope kind is added.

## The declared-surface inventory

This is the first of the three inventories above, and Task 5 owns it: the
surfaces a child's own source can declare, each reduced to its container's hold
or refused, and never honoured as written. Both sides of every comparison are
read by one reader, `ContainedDeclarations.read`, through the runtime's existing
owning parsers — `ApplicationServerSettings.read` for `server/tools.json` and
`server/ports.json`, and the package's own paths for the directory-shaped
surfaces — so a child's declaration cannot be admitted because the two sides were
parsed differently.

| Surface | Declared as | Decision |
| --- | --- | --- |
| Tool provider | `server/tools.json` `providers[].provider` | **Reduced.** Refused unless the container declares the same provider. |
| Relay topic port | `server/ports.json` `bindings[].topic` | **Reduced.** Refused unless the container declares the same topic. |
| Relay audience | `server/ports.json` `bindings[].groups` | **Reduced.** Refused unless every group is one the container declares on that same topic. |
| Named swarm type | `swarm/<name>.{md,json}` | **Reduced.** Refused unless the container declares a swarm type of that name. |
| Schedule | `schedules/*.json` | **Refused** outright for a child in this slice. ADR 0020 gives a schedule operational authority over the server's own scheduler, and nothing here mediates that for a child; a container holding a schedule of its own does not change the answer. |
| FileStore destination | the creation request's `destination` | **Ignored.** The server allocates the child's source area under `plowshare.containment.root`; a container typically holds no FileStore `MANAGER` over its own retained source, so proxying could not yield a legal destination. |
| FileStore writable area | the creation request's `writableAreas` | **Refused** while non-empty. A child's writable area is allocated inside its own containment, so any requested area names a store outside its container's fence. |
| Service account attribution and spending pool | the child's own allowance | **Allocated, never shared.** The child allocates its own allowance under a ceiling its container sets at creation and may later raise or reduce — to zero — within its own remaining allowance. The creation bound and the reconfiguration bound are one rule applied at two call sites against the same pool, because a bound enforced only at creation would be decorative: create low, raise high. An absent ceiling is not representable, because `AllowanceSnapshot` documents absent as unlimited. Child spend is attributed to the child, so ADR 0025's immutable attribution and its rule that delegation cannot change the accounting account are untouched, and no parent/child shared counter is introduced. |
| Manifest `name` | `plowshare.json` `name` | **Rewritten** to the server-allocated opaque project name. `name` is identity and not authority, and the server owns the name a child cannot know in advance; every surface that *is* authority is in the rows above and is refused or reduced, never rewritten. |
| Nesting itself | `plowshare.json` `nesting: true` | **Required of the container.** Only a literal `true` counts, so no existing Application acquires the capability by upgrade, and a missing, null, string or object value is "not declared". |

Tool *bindings* (`server/tools.json` `bindings`) and tool grants are not in this
table and are not yet compared: a child declaring one is accepted today, and the
binding it declares reaches only its own project. That is the honest state and it
is a gap, recorded here so that Task 8's addressability audit inherits it rather
than discovering it.

## The two live-authority gates

A contained admission was inert, and making it live needed two gates opened
together; opening either alone leaves the other refusing and looks like a defect
in `contain`. Neither is widened for anything but a contained admission, and the
non-contained path through both is unchanged.

**Gate one, `requireOwn`'s home/scope agreement check.** It resolved the
admission's project name back to a scope and required it to equal the retained
scope id, which kills a stale admission whose project was renamed, or dropped and
recreated under the same name. `project(String)` refuses a contained scope, so
for a contained admission the same proof now runs the other way: the stored child
row must still resolve, through `containedHome`, to the exact home the admission
retained. A renamed, dropped or recreated child project still makes the two
disagree, so nothing the replaced check bought is lost. It then checks the
retained grants against the *nearest enclosing uncontained scope* rather than
against the contained scope, because a child's authority is a projection of its
container's: `contain` copies the container's own grant rows and issues none, and
a grant against a contained scope is excluded from issuance and from coverage by
design. It still refuses an unconfigured bound, a contained admission claiming
Global knowledge READ, a chain longer than the configured depth, and a container
whose role ceiling or live grant no longer covers the retained action.

**Gate two, `require`'s ancestry walk.** It demanded an ancestor with an
identical scope *and* home, both of which a hop changes by design. For a
contained admission that equality is replaced by the containment edge itself: the
child's own stored row must name this ancestor's scope as its container. Executor,
account, grant source, support containment and the `globalRead` rule are all
unchanged. It still refuses an ancestor that is not the recorded container — a
grandparent, a sibling subtree or an unrelated scope — and it now *also* refuses a
contained admission with no retained parent edge at all, since a contained
admission comes into being only through a hop and a freely constructible record
must not be accepted for want of a parent.

**On the misclassification risk recorded for the contained principal.** A
contained principal's identifier is a bare UUID, so
`ServiceCredentials.principal` is false for it and any path classifying a caller
by the `@service/` prefix routes it into the *user* branch — `RequestProjects`,
`ProjectAuthorization` and `ApplicationProjectMembers`. The creating path's
guarantee is that the identifier never becomes a handle: a hop keeps the parent's
executor and account unchanged, the principal is passed only to
`ContainedPrincipals.issue`, and the creating service resolves no account from any
string at all. The USER branch's fail-closed behaviour — `authority_project_role`
finds no `admins` and no `project_members` row for a UUID — remains the second
line and not the first.

## The child contract carries no internal value objects

`Child` is a boundary DTO and every component is a plain wire value, `scope`
included. `AuthorityIds.ScopeId` is an internal value object with no
`@JsonValue`, so a `Child` carrying one published `{"value": "..."}` as the
public contract shape, and would have carried that shape outward through five
generated SDKs the moment child read and destroy arrived. The conversion belongs
at the boundary and is done once, where the record is built; `ScopeId` itself is
unchanged, because annotating it would alter every other DTO that happens to
carry one.

## What makes collection observable

This record claimed collection was observable before anything made it so, which
was a claim no operator could check: a destroyed child's rows are removed by the
two-hop cascade — `projects` to `authority_scopes` (V155) to
`contained_applications` and `contained_principals` (V197, V198) — so after a
destruction there was nothing left to read, and the staged-source removal that
follows the transaction was silent. The claim now stands because two things were
added rather than because it was softened.

**A collection record, V199's `contained_collections`.** One row per collected
source area, written inside the destroying transaction before the rows go. It is
deliberately the one table in this relation with *no foreign key at all*:
everything it describes is being deleted as it is written, so a constraint into
any of it would delete the history along with the thing it is history of. It is
not a release — no digest, no file count, no destination — because
[ADR 0005](0005-application-deployment.md)'s `application_releases` records what
may be activated and a destroyed child may not be activated at all. **A destroyed
child is never promoted to a retained release**; promotion is a separate explicit
act and is not represented in this schema.

**`source_removed` as a separate column, not an implied consequence.** The
filesystem cannot join the transaction, so the order has to be chosen and the two
orders fail differently. Removing the bytes first and rolling back leaves a live
record pointing at source that no longer exists — an Application that cannot run
and that no sweep can repair, because its own record says it is live. Committing
first and being interrupted leaves a directory with an *unmarked* collection row.
Only the second is recoverable, so destruction commits first and marks the removal
only once the bytes are actually gone. An unmarked row is therefore precisely the
work an interrupted removal left behind.

`ContainedSourceCollector.sweep()` reads that as its first input and the
containment root's own directory listing as its second: a directory with no
committed containment record is a creation that was interrupted between staging
and committing, and only a committed record makes a stage live. Each sweep reports
what it collected and what it retained.

**This inverts ADR 0005 deliberately.** That record states that filesystem
staging cannot participate in PostgreSQL rollback, that a rejected or crashed
stage can remain on disk, and that the server does not garbage-collect it. That is
correct there, because a top-level staged revision may be a retained release a
human still wants. Here destruction is the *normal* path, so a stage with no
committed child is garbage rather than evidence.

**The collector cannot reach an ordinary Application's retained revisions.** That
is structural and not a check: the only directory it ever enumerates is a direct
child of the configured containment root, a server-owned directory that exists for
contained source and nothing else, and it holds no collaborator through which a
release, a deployment head or a FileStore destination could be reached. The
destroying `DELETE` carries the reserved `contained:` prefix in the statement
itself, so it cannot match an ordinary project whatever identifier it is given.

## A parent revision change refuses while a child is live

Letting children survive the change and destroying them silently during an
ordinary update were both rejected above, because each produces the orphan this
design names as failure. The refusal is asked by `ContainmentFence` from inside
`JdbcApplicationDeploymentStore.commit`, immediately after the project row lock
that already serializes activation — and creation takes that same container
project row lock, so the two contend on one row. Without the shared lock the
revision change would count zero live children a moment before a child row landed
and both would commit, which is the window the fence exists to close. The fence
holds the containment read and nothing else, so a refusal is the only thing it can
do: there is no collaborator there through which a destruction could be reached.

## Contained principal revocation is symmetric with issuance

`ContainedPrincipals.revoke` took a bare scope id and checked neither project nor
container while `issue` checked both. That was harmless only while nothing called
it. Destruction is its caller, so the asymmetry was closed rather than documented:
`revoke` now takes the same fully described `AuthorityScope.Contained` and its
statement checks kind, project and container against the authority record. The
asymmetry mattered because removing nothing is not an error — a caller holding a
mis-described scope would have retired another Application's principal and seen
the same silent success.

## When collection runs, and why a timer is acceptable here

Making collection observable was not enough: nothing invoked it, so an
interrupted create's directory stayed on disk until someone ran a sweep by hand,
which is the anti-goal "children that accumulate" wearing a different hat.
`ContainedCollectionSweeps` now triggers it two ways, because they answer
different crashes. **Startup reconciliation** answers the crash that happened
while the server was down — no request will ever arrive to clean that directory
up. **A repeating sweep** answers the crash that happens while it is up — a
destruction that committed and whose source removal did not finish leaves an
unmarked collection record that only a sweep acts on. It follows
`ApprovalContinuationRecovery`'s shape (a readiness-gated
`ApplicationReadyEvent` listener that also carries the `@Scheduled` method, on
the scheduler `SchedulingConfig` owns) rather than inventing a mechanism.

[ADR 0005](0005-application-deployment.md)'s retention sweep refused a timer for
three reasons, and each is answered rather than ignored. *A sweep deletes bytes
with nobody watching* — the only bytes this can delete are directories under the
operator's own containment root that no committed record accounts for, already
unreachable garbage rather than a misconfigured export directory, and an
unconfigured server has no root and sweeps nothing. *A boot timer fires in every
Spring test* — answered structurally, not by argument: the bean is conditional on
`plowshare.containment.root`, so a context that has not configured nesting does
not contain it. *Several servers on one database means several racing sweeps* —
real, and why every step is idempotent: removal is `deleteIfExists`, the mark is
an idempotent `UPDATE`, the collection insert is `ON CONFLICT DO NOTHING`.
Concurrent sweeps duplicate work; they cannot corrupt it.

**A sweep must never collect a stage a live create is still writing.** That would
be worse than the leak, because the caller would hold a receipt for an
Application whose files are gone. The window is real: creation stages before any
durable row exists, which is what refusal totality rests on, so for that interval
there is a directory no committed record accounts for — exactly the shape the
sweep collects. Two guards, in order of exactness. `ContainedStaging` holds a
claim from before the first byte until the unit of work has finished, released in
a `finally` so a refusal cannot exempt a project forever; the sweep skips a
claimed project whatever its record or age says. That is **exact, and exact only
within one process**: a durable claim would be a claim nothing will ever release
if its process dies, which is a permanent leak dressed as safety. Across
processes the bound is `plowshare.containment.sweep-grace`, a minimum directory
age below which an unaccounted stage is assumed to belong to a creation still
running. The grace defaults long and is floored at one minute, because the two
failure directions are not symmetric: too long leaks for one more sweep, too
short deletes live work.

**The residual window, which slice one accepts.** The in-process claim is
authoritative and total, but only for the server holding it: within one process
no sweep can collect a stage a creation is writing, whatever that stage's age.
Across processes there is no claim at all — only the floored directory age, which
rests on filesystem mtime and is therefore advisory rather than authoritative. So
in a **multi-server deployment sharing one containment root**, one server's sweep
can collect a stage another server is still writing once that stage is older than
the grace: a creation slower than `sweep-grace` — a large package on slow storage,
a long validation, a stalled transaction — is the case. A single-server
deployment does not have this window, because the claim covers it entirely. It
fails in the safe direction only in the sense that the grace floor keeps the
window from being immediate; the failure itself is not safe — the creating server
would return a receipt for an Application whose source another server deleted,
which is why the grace defaults to thirty minutes rather than to something a
creation could plausibly exceed. What closes it is a **durable staging claim with
an owner and a lease**, so a claim survives the process that made it and expires
if that process dies; that is new lifecycle machinery and is deliberately not in
this slice.

A sweep reports **what it looked at**, not only what it did. Its result carries
whether the whole root was enumerated, whether it could not be, or whether no root
is configured, because the counts alone cannot tell "looked, found nothing" from
"could not look" — both answer zero — and the scheduler logs a sweep that could
not look. Collection is this record's observability claim, so the component
carrying it cannot be the one that hides its own failure.

`ContainmentConfig` therefore now holds one value that defaults when absent
(`sweep-grace`) beside two that fail at startup when absent (the depth and
breadth bounds), and the asymmetry is deliberate rather than an oversight: a
tuning knob is not a deployment identity, and an absent bound would mean
*unlimited* whereas an absent grace merely means *long*.

## The ordering of the staged-source collection, and the orphan it must not make

Collection on a refused create is ordered by which failures are *certainly* a
rollback. Everything before the unit of work opens — staging, the declared-surface
comparison, the manifest, the validator — is certainly a rollback, because no
durable row can exist yet, so its bytes go. A failure thrown by the transactional
body is also certainly a rollback, so its bytes go.

A failure thrown by the **commit** is not. A commit that reports failure may
still have committed, and collecting on that would make the one orphan this
record calls unrecoverable: a live child row whose source is gone, for which
`committed(project)` answers true, so the sweep neither collects nor repairs it
and nothing says anything is wrong. Nothing therefore collects on a failure that
escapes the unit of work itself. Leaving the bytes is safe in both branches and
requires distinguishing nothing: if it committed they are the child's correct
source; if it did not, there is no row, so the sweep collects them.

## Destruction under delivery uncertainty

ADR 0005 requires delivery uncertainty to be resolved by inspection and never by
automatic replay, and `create` carries a request-UUID receipt for exactly that.
`destroy` resolves it the other way, through **demonstrated idempotency against
the collection record it already writes**. A second request UUID keyed on the
same event would have been a second source of truth for it; the collection row
already holds everything a receipt would have to: that the destruction happened,
whose child it was, and which records went with it.

A retry whose child has no live record therefore reads its collection record, and
the three answers are **distinguished**:

- **No record of any kind** — "No such contained Application".
- **A record whose `container` is not this caller's scope** — the same containment
  refusal a live child would get. The row retains the container as a plain value
  precisely so this stays answerable after the scope it names has been cascaded
  away.
- **A record that matches** — success, replaying the *same* answer. V200 records
  the destroyed child's own `scope`, which is what links a parent's collection row
  to its descendants': without it a retry could only answer about the one record
  it named, which is a *different* answer from the original and worse than a
  refusal. The subtree is rebuilt by descending the recorded containment edges,
  deepest first, bounded by the configured depth.

A retry also finishes an interrupted source removal, so calling twice is not
merely safe but useful: the second call collects the bytes the first could not.

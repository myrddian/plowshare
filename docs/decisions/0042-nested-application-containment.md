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

One forced site. That it is one, and not many, is itself the finding: the
substrate's other authority decisions are written as `instanceof` tests over the
kinds they admit, so they exclude a new kind by construction and the compiler has
nothing to report. `PinnedOperationAuthority.admit` admits only `Project` and
`Personal`, `LiveScopedAuthority.covers` widens `ORDINARY_PROJECT_CONTROL` only
for `Project`, and `LiveActingAuthorization` and `AccountRequestProjects` likewise
require a named kind. A contained scope is therefore inadmissible, uncoverable and
unactable-upon in this slice without any further change, which is the intended
first-slice posture. The corollary is that sealed-interface exhaustiveness is a
weaker locator here than the design assumes, and Task 8's inventory cannot be
derived from this list alone.

The refusal at `home(AuthorityScope)` is the consequential one. A contained scope
carries an ordinary `projects.id`, so answering `value.projectId()` there would
have compiled, returned a project name, and made a child project addressable
through every `Home`-keyed path in the server — the silent default this record
rejects. Whether a contained scope has an outwardly visible home, and to whom,
is decided where containment is resolved.

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

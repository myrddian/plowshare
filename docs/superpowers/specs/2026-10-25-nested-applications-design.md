# Nested Applications

Status: design, 25 October 2026. Written against branch
`claude/harness-2-subprocess-support-f0d061`. This document describes intent and
boundaries for a core platform capability. It authorizes no implementation; the
platform change needs its own decision record under
[the integration boundary decision](../../decisions/0001-integration-boundary.md).

## Intent

An Application can today be deployed, validated, versioned and activated as a
file tree, but only by a server administrator, and only as the single Application
that owns a project. Nothing can create an Application from inside another one.

The capability this design describes is containment: **an Application can run
Applications inside itself, under configuration it owns.** A parent creates a
child, writes the child's manifest and resources, reads the child's work, and
destroys it. The child's authority is a strict subset of the parent's. The child
cannot reach anything above or beside itself; every edge in or out is the
parent's to proxy, and the parent cannot proxy authority it does not itself hold.

### Why this, and why now

The immediate consumer is a recursive harness-improvement workflow after
[Harness^2](https://arxiv.org/abs/2610.14224), in which an agent writes candidate
agent configurations, runs each on the same task in isolation, contrasts the
executions, and composes a successor. That workflow needs several
parent-authored configurations alive at once, isolated from each other, with
their trajectories readable by their author and nothing else.

The capability is general and is not justified by that workflow. The same
primitive serves sandboxed evaluation of any agent-authored configuration,
per-tenant Application variants under one owner, comparing two skill or tool
trees against the same task, and running a third-party Application under an
owner's grants without granting it any of them. A parent-owned, confined,
disposable Application is the missing shape; the recursive workflow is its first
consumer.

Today the only way to approximate this is to widen the parent's permissions or
pre-install every candidate as an ordinary delegable definition. Both invert the
intent. A configuration the parent wrote moments ago is untrusted input and must
run with strictly less authority than its author, not more.

## Goals

- A parent Application creates, configures and destroys child Applications
  without a server administrator, under authority it already holds.
- A child's authority is a subset of its parent's at every surface, narrowing
  monotonically across each containment hop.
- The parent reads into the child through typed server contracts: the child's
  execution trajectory, its deliverables and its resources.
- A child cannot name, read, address or reach anything outside its own
  containment subtree, including the parent itself.
- Every inbound and outbound edge is bounded by the parent, which must hold the
  permission it mediates. For messaging this is a narrowing of existing Relay and
  Application scoping, not a new forwarding path.
- Children are ephemeral. The parent destroys them and their source is collected.
- A child's spend is bounded by a ceiling its parent sets at creation and can
  reduce to zero.
- Several sibling children can be alive at once, isolated from each other.
- Cancelling or destroying a parent destroys every descendant.

## Non-goals

- The recursive harness workflow itself. It is a separate design, built on
  public SDK contracts and the server capabilities here, and needs no further
  core change. It is not in scope and must not drive contract shape beyond the
  general needs above.
- Changing the existing administrator-gated `application.deploy` path, its
  retained release history, its receipts or its activation semantics for ordinary
  top-level Applications.
- Promoting a child to a retained top-level release. That is a separate explicit
  act with its own authorization, deliberately not a side effect of nesting.
- Any new persistence framework, generic SQL helper, or second authoritative
  store. Nesting is a relation over existing projects and existing authority
  records.
- Relaxing `GLOBAL_READ` eligibility, audience policy, or Global knowledge
  custody for any existing principal.
- Process-level or container isolation as the confinement mechanism. Command
  isolation ([bubblewrap](../../command-isolation.md)) remains what it is today
  and is orthogonal.

## Anti-goals

These would make the work a failure even if it shipped and passed its tests.

- **A primitive only the harness workflow can use.** If the contracts name
  harnesses, probes, candidates or improvement, the abstraction is wrong.
- **Confinement that is a check rather than a structure.** If a child is
  contained because every call site remembers to check, the next call site will
  forget. Containment must fall out of resolution and admission.
- **Any path by which a child obtains authority its parent lacks**, including
  through audience-driven reads the child never names, inherited execution
  identity, a provider binding in its own manifest, a Relay audience, a schedule
  it owns, or a FileStore destination outside the parent's fence.
- **Children that accumulate.** Retained source, abandoned stages, orphaned
  projects or uncollected revisions turn routine use into an unbounded disk and
  row leak.
- **Unbounded recursion or width.** A configuration that creates configurations
  that create configurations, with no depth or breadth bound, is a resource bomb
  reachable from ordinary agent work.

## The model

Four rules, in the user's terms.

**Containment.** A child Application is created by a parent, inside the parent,
and is not a peer. It is not a sibling row that happens to hold fewer grants. It
has no independent addressability: no external login as the child, no
authenticated session as the child, and no presence in any catalogue but its
parent's.

**One-way visibility.** The parent can peer in; the child cannot peer out. The
parent reads the child's resources, trajectory and deliverables. The child
cannot name anything above itself, so an outward reach is a resolution failure
rather than a denied permission.

**The parent is the only surface.** An external Application or SDK that wants to
reach a child addresses the parent, and the parent must itself hold the
permission it is proxying. This composes transitively: a grandchild proxies
through the child, which proxies through the parent, and authority narrows at
each hop.

**Ephemerality.** The parent creates and destroys children, and a destroyed
child's source is collected. Retention is the exception and must be asked for.

### Consequences the user confirmed or stated

- Memory and configuration of a child are managed by the parent.
- A child's spend is bounded by its parent (see the ceiling decision below).
- Destroying or cancelling a parent destroys its descendants.

### Consequences this document asserts as design, not as the user's words

- A child's writes are **untrusted input to its parent**. The parent reads child
  trajectories and deliverables in order to act on them, which makes those
  reads a prompt-injection surface by construction. Child output is data, never
  instruction, and any consumer that composes new configuration from it must
  treat it so. This is a contract on consumers, stated here because the
  primitive creates the exposure.
- Containment depth and sibling breadth are bounded, with the bound owned by
  server configuration rather than by the requesting agent.

## What already exists and is reusable

The design should reuse these rather than restate them. All references are to
the branch named above.

**Harness-as-artifact.** An Application already *is* a file tree in a workspace
root: a version 1 `plowshare.json` manifest with resource directories directly
in that root, holding agents, skills, orchestrations, `swarm/<name>.md` types,
hooks, `server/tools.json`, `server/ports.json` and `server/relay-workers.json`.
Parent-authored configuration needs no new artifact format.

**Validated, atomic, idempotent installation.**
[ADR 0005](../../decisions/0005-application-deployment.md) already provides
packed source over the authenticated WebSocket, every runtime parser validating
agents, skills, orchestrations, named swarms, hooks and Relay configuration
before publication, staging under `destination/revisions/<revision UUID>`, a
project row lock with `expectedRevision` compare-and-set, immutable receipts
keyed by caller-supplied request UUID, and resource caches keyed on source
directory identity so a revision change invalidates them. Package limits are 128
regular UTF-8 text files, 64 KiB per file, 128 KiB in total, and 16 directory
levels.

**Authority subsetting across a delegation hop.**
[ADR 0011](../../decisions/0011-application-scoped-agent-delegation.md)
establishes that a callee "cannot exceed the admitted caller's workspace grants",
that the child "retains its own tools and grants", that execution rechecks
session ownership and project work membership, that there is no fallback to
another account, project or SYSTEM identity, and that budget, cancellation,
child conversations and approval propagation are shared downward.

**Monotonic read narrowing.** `KnowledgeReadScope`
(`plowshare-server/.../server/access/KnowledgeReadScope.java`) is a server-owned
read ceiling that grants nothing and only ever narrows:

```java
public KnowledgeReadScope intersect(KnowledgeReadScope selected) {
  requireHome(selected.home());
  return new KnowledgeReadScope(home, globalRead && selected.globalRead());
}
```

`InformationAccess.within` folds each binding into the existing boundary by
intersection, `resolve` clamps the resolved context through it whatever selection
arrived on the wire, `forRun` derives the selection's `includeGlobal` from
`retained.globalRead()` rather than from the caller, and `InformationSql`
intersects every read path with the ceiling including dependency closure. Global
read is therefore default-on but switchable server-side by a ceiling the caller
cannot lift. This is the semantic model the containment hop should follow.

**Execution lineage, with an explicit budget owner.**
`OperationAuthority.Conversation` is "a durable execution envelope; provenance
and the budget owner are never inferred on resume". It already carries `parent`,
an `AllowanceId budget`, a retained 64-hex `capabilities` fingerprint, and the
invariant that "a child cannot parent itself". The parent/child execution
relation therefore exists; what it lacks is a budget-sharing contract (below).

**Projects are the container.** `InformationAccess` records the relevant
invariant directly: an account is an identity, not a container; a personal space
is fundamentally a project, as an Application is; projects in any form are the
resources and accounts are the identities that reach them. A nested Application
is therefore a nested project, not a new kind of thing.

## What has to change, and the obstacles

Each item is a constraint the builder must satisfy or an explicit collision to
resolve. None is a chosen implementation.

**The administrator gate.** `SourceApplicationDeployments.java:57` refuses
deployment without a server administrator, and a destination FileStore `MANAGER`
check follows it. The child path needs its own admission rule derived from the
parent's held authority. This must be a distinct rule, not a relaxation of the
existing one; ordinary top-level deployment keeps its gate exactly as it is.

**Containment belongs to the authority substrate, not to `Home`.** An earlier
draft of this document framed the narrowing problem around
`KnowledgeReadScope.intersect` and `Home`'s flatness. That was the wrong layer,
and a builder review caught it. The correct layer is
[ADR 0030](../../decisions/0030-authority-substrate-and-explicit-scopes.md)'s
substrate:

- `AuthorityScope` is a **sealed interface with four kinds** — `Project`,
  `Personal`, `AccountKnowledge`, `Administration`. Containment is plausibly a
  fifth kind, and sealed-interface exhaustiveness is what makes confinement
  structural rather than remembered: the compiler locates every site that must
  decide what a contained scope means. That directly serves the anti-goal
  against confinement-as-a-check.
- `OperationAuthority.derive(Admission parent, Set<AuthorityAction> actions)`
  already exists to narrow "a retained parent, preserving exact supporting
  grants and immutable ancestry". It narrows the action set while keeping scope,
  account and home, so it does not express a containment hop as written — but it
  is the right contract to generalise, and its no-enlargement guarantee is the
  one to preserve.
- `admit(String executor, AuthorityScope scope, Set<AuthorityAction> actions)`
  deliberately takes a resolved scope rather than a `Home` "so that each caller
  states which of the four authority concerns it means and a new concern cannot
  be absorbed into an existing one". A fifth concern must be introduced the same
  way.
- `OperationAuthority.scope(Home)` documents itself as existing "for the callers
  that still carry a request's project name", and states that a home "cannot
  spell the difference" between the non-project concerns. `Home` is legacy for
  authority purposes.
- `KnowledgeReadScope.intersect` remains the model for *read* narrowing and
  follows from the substrate decision rather than leading it. It cannot express
  the hop by itself: it calls `requireHome` and refuses any change of execution
  home, while a containment hop changes home by definition.

**Do not build containment on `Home`'s null-global representation.**
[Removing the null representation of global](../../global-null-removal-plan.md)
is an active plan to replace `null` scope with explicit scope UUIDs. An earlier
draft of this document cited `Home`'s null-means-global rationale as a durable
constraint. It is being dismantled, and new work must not depend on it.


**Name capacity.** [ADR 0021](../../decisions/0021-tool-and-relay-topic-name-capacity.md)
pins tool and Relay topic name capacity. If a containment namespace prefixes
those names, the prefix consumes that budget and grows with depth. A namespace
should travel as a resolution context alongside the name; baking it into the name
renegotiates a shipped decision and must be argued explicitly if chosen.

**Surfaces that lack narrowing.** Read ceilings narrow; these do not yet, and each
is an escape hatch if missed: Relay topic ports and audiences
([ADR 0010](../../decisions/0010-relay-processing-and-topic-policy-authority.md),
[ADR 0039](../../decisions/0039-relay-audiences-beyond-a-project.md)), tool
bindings and provider declarations in `server/tools.json`
([ADR 0006](../../decisions/0006-application-scoped-live-tools.md),
[ADR 0007](../../decisions/0007-provider-scopes-and-interactive-tool-policy.md)),
FileStore destinations and writable areas, named swarm types
([ADR 0004](../../decisions/0004-named-application-swarms.md)), schedule
ownership ([ADR 0020](../../decisions/0020-application-schedule-operational-authority.md))
and service-account attribution and spending pools
([ADR 0025](../../decisions/0025-service-account-attribution-and-spending-pools.md)).
A child manifest that declares any of these must be reduced to the parent's hold
or refused — never honoured as written.

Messaging specifically is **not** a new generic forwarding surface. Relay and
Application configuration already scope who may reach what; containment narrows
that configuration so a child cannot message anything its parent cannot. The
only new operations are the parent's own typed ones — create, configure, read
trajectory, read resources, destroy — and a refusal everywhere else. A general
request-forwarding path is explicitly rejected: every forwarded call would have
to re-prove containment, which is the confinement-as-a-check anti-goal.

**Execution identity.** ADR 0011's delegation hop inherits the execution
identity downward. A parent-authored child is not an installed specialist, and
its actions must not be attributable to, or exercisable as, its parent. The
child needs its own principal, ephemeral, created and owned by the parent, whose
reach is the containment subtree. This is the user's explicit decision.

**Late binding.** ADR 0011 makes the admitted caller's declared `calls:` list the
upper bound on delegable target names, resolved through the caller-aware
definition loader. A child written after the parent was admitted is in no such
list. Containment needs a resolution path for a target that did not exist at
parent load time, without weakening the bound for ordinary delegation.

**Parent-to-child budget sharing does not exist, and is deliberately refused.**
`AllowanceRepository` and `AllowanceSnapshot` model one ceiling and one spent
counter with no parent/child relation, and
[the lineage slice](../../authority-substrate-slices/11-lineage-authority-specification.md)
records that nested and model-triggered starts "remain explicitly refused until
an existing supported sharing/allocation" contract exists, with existing nested
orchestrations allocating fresh budgets instead. Rolling child spend into a parent's counter therefore names a
contract that does not exist. **It is out of scope here.** A child allocates its
own allowance, as nested starts already do, under a ceiling its parent sets at
creation and may reduce to zero. Child spend is attributed to the child, which
leaves ADR 0025's immutable attribution and the "delegation cannot change the
accounting account" rule untouched. A true shared counter remains separate work
with its own decision record.

**Disposal, which does not exist.** ADR 0005 retains every release, keeps the
most recent 100 in status with the active release always included, states plainly
that filesystem staging cannot participate in PostgreSQL rollback, and that
rejected or crashed stages can remain on disk with no garbage collection. For
ephemeral children this inverts: destruction is the normal path, collection must
be reliable, and an interrupted create must not leave an orphan project, an
uncollected revision directory or a live principal. Crash recovery is part of
the capability, not an afterthought.

**Resource bounds.** Depth and sibling breadth both need server-owned bounds.
Width multiplies the package budget — several live children, each up to 128 files
and 128 KiB — and recursion multiplies it again per level.

## Decisions already taken

Taken by the user, in conversation:

- Build the primitive alone; the workflow is a later, separate design.
- Children are ephemeral; the parent creates and destroys them; source is
  collected; promotion to a retained release is a separate explicit act.
- The child gets its own ephemeral principal, which the parent controls.
- The parent can peer in; the child cannot peer out.
- The parent proxies every edge and must hold the permission it proxies.
- A child is contained within the parent Application, not beside it.
- **A child allocates its own allowance under a ceiling its parent sets at
  creation.** No shared parent/child counter is built in this capability. This
  keeps spend bounded by the parent without the allowance-sharing contract the
  lineage slice is waiting for; that contract remains separate work.
- **A child has its own writable knowledge and memory home** inside its
  containment, which the parent reads into. Child writes are not refused.
- **Messaging is already scoped by Relay and Application configuration**, so
  confinement there is a narrowing of existing scoping rather than a new
  forwarding surface: the child cannot message anything its parent cannot.

Taken by the author of this document, and open to correction:

- A nested Application is a nested project, following the container invariant
  already recorded in `InformationAccess`.
- Containment is expressed in the ADR 0030 authority substrate — a scope concern
  resolved through `admit` and narrowed through a generalised `derive` — not as a
  relation over `Home` and not as a path encoded in a project name.
- Child output is untrusted input to the parent, and consumers must treat it as
  data rather than instruction.
- Depth and breadth bounds are server configuration, not agent arguments.

Settled by the author after a builder review, each a call a sensible builder
could have made differently:

- **A child's project name is opaque and server-generated**, with containment
  carried by an explicit relation rather than by the name. `personal:<hex>` plus
  the immutable `projects.personal_owner` column is the precedent for encoding
  identity in a project name; it is not the precedent to follow here, because a
  readable containment path is exactly what ADR 0021's name capacity cannot
  absorb at depth.
- **A parent revision change refuses while any child is live.** Fail closed.
  ADR 0005 activation switches source and invalidates caches keyed on source
  directory identity; letting children survive would outlive the configuration
  that authorized them, and destroying them silently would kill work during an
  ordinary update. Both produce the orphans the anti-goals name.
- **The server allocates a child's source area under a server-configured nesting
  root**, not a parent-supplied destination. A parent typically holds no
  FileStore `MANAGER` over its own retained source, so proxying cannot yield a
  legal destination; requiring one would make the capability unavailable to
  exactly the Applications that need it.
- **ADR 0005's package limits apply per child, and the live aggregate across a
  parent's descendants is separately bounded**, since breadth and depth each
  multiply the budget.
- **Nesting is opt-in per Application**, declared in the manifest and validated
  at deployment, so that an existing Application cannot acquire the capability
  by upgrade alone.

## Left to the builder

- How containment is represented: the project relation, its persistence in a new
  Flyway migration, and how resolution and admission consult it.
- The exact narrowing contract for a containment hop, replacing or generalising
  `KnowledgeReadScope.intersect` without losing its no-enlargement guarantee.
- How a namespace travels with a resolution request without consuming ADR 0021's
  name capacity.
- The admission rule for parent-authorized creation, and the public operation
  surface: creation, configuration, trajectory and resource reads, destruction,
  and the proxy path for inbound external traffic.
- The child principal's lifecycle, and how cancellation and destruction cascade.
- Collection: when a revision directory and project row are removed, how an
  interrupted create is recovered, and how this interacts with ADR 0005's
  explicit non-collection of staged source.
- Which surfaces are narrowed in the first slice and which are refused outright
  until narrowed. Refusing a surface is an acceptable first slice; honouring a
  child's declaration of one is not.
- **An authoritative inventory of every place the server makes a project
  addressable without anyone naming it**, and a decision per entry: fenced, or
  refused. This enumeration is the confinement proof, and it is a different list
  from the child-manifest surfaces above. Known starting points: `project.list`
  and the kind/`routingIdentity` surface, messaging `from_project`/`to_project`,
  Relay project-scoped subscribers
  ([ADR 0038](../../decisions/0038-relay-read-tier-subscribers-project-scoped.md))
  and audiences (ADR 0039), startup schedule reconciliation, conversation
  search, and the union/git provisioning that ordinary project creation performs
  per [personal space](../../personal-space.md) — a child must not silently
  acquire a working tree and a git hub.
- Where the shared operation and DTO graph regenerates Java, TypeScript/Node,
  Python, Go and .NET contracts, and which clients change with it.
- Test coverage. Mocked repositories and in-memory fakes by default per the
  [database test policy](../../../AGENTS.md); real PostgreSQL only for the
  migration, the containment constraint, concurrent create and destroy, and
  transaction rollback, behind `@Tag("full-db")` and `-PfullDb`.

## Verification this capability must demonstrate

Confinement is the product. The suite has to show escape attempts failing, not
only the happy path working:

- A child naming its parent, a sibling, an uncontained project, or the Global
  tier is refused at resolution.
- A child reading knowledge receives nothing from outside its subtree, including
  through audience-driven rows it never named and through dependency closure.
- A child manifest declaring a provider, Relay port, audience, swarm type,
  schedule or FileStore destination beyond the parent's hold is reduced or
  refused, and never honoured.
- A parent cannot grant a child any authority the parent does not hold, tested
  per surface.
- An external principal addressing a child directly is refused; addressing the
  parent succeeds only where the parent holds the proxied permission.
- Narrowing holds at depth, and the depth and breadth bounds refuse rather than
  degrade.
- Destroying a parent destroys descendants; revoking the parent's authority
  mid-execution stops descendant work.
- An interrupted create leaves no orphan project, revision directory or
  principal, and collection is observable.
- Sibling children cannot observe one another's resources, trajectories or
  deliverables.

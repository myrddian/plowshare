# ADR 0042: Nested Application containment

Status: accepted for slice one, 25 October 2026, following the user's
authorization to build the containment primitive alone; amended for slice two,
1 November 2026, which narrowed the knowledge-read fences slice one relied on and
added a metadata-only resource read; amended for slice three, 4 November 2026,
which **made a contained child execute** — a run of its own, under its own
allowance, stoppable three ways — and re-audited the three inventories against a
running child rather than against inserted rows. The remaining capability is
proposed.
Written to satisfy
[the integration boundary decision](0001-integration-boundary.md) for the design
in [nested Applications](../superpowers/specs/2026-10-25-nested-applications-design.md).
That design is the binding authority; this record states its decisions in the
repository's decision form and does not add to them. The recursive
harness-improvement workflow that motivated the capability is a separate design
and is not authorized here.

**Migration numbering.** Slice one's seven migrations are V216-V222, slice two
adds exactly one more, `V223__contained_knowledge_reads.sql`, and slice three adds
two: `V229__contained_actor_reads.sql`, which opens the
`information_global_read_member` baseline for a contained actor while keeping every
general arm shut and widens the two knowledge *write* gates, and
`V230__contained_operation_lineage.sql`, which lets a containment hop record its
lineage and adds the `contained` conversation origin. Those two carry every SQL
change in slice three. Slice one's seven and V223 are on the third set of numbers
these migrations have carried. They were authored as V195-V201 when V194 was the
highest shipped version, renumbered to V208-V214 after `main` independently took
V195-V207, and renumbered again to V216-V222 when `main` took V208-V211 as well;
V223 was authored as V215 and moved with them the second time. Slice three's two
were authored as V224 and V225 when V223 was the highest shipped version, and a
third renumber moved them to V229 and V230 after `main` advanced six commits and
took V224-V228 for invocation image admission, binary blob claims, relay blob
publications, trajectory image observations and retained image formats. Only
those two had to move: slice one's seven and V223 sit below `main`'s new range
and keep their numbers. Flyway refuses to start on
a duplicate version, so each collision was a hard failure rather than a cosmetic
one — and an invisible failure to the default build, because database tests sit
behind `-PfullDb`. The third collision was quieter still: `main`'s V224 and V225
and ours merged without a textual conflict at all, because a duplicate version
carried by two differently named files is not a conflict Git can see. Git history
before each of the three renumbers names the versions of the time, so a citation
read from an older commit will not match the current files — and for slice three's
pair there are now two such generations behind the current names.

A branch that holds unmerged migrations will collide again every time `main`
advances, and three collisions in three syncs is enough to call that the normal
case rather than bad luck.
Nothing here decides the repository's answer to it — a reserved high band per
long-lived branch, or renumbering as a step of every sync — because that is a
repository-wide convention with consequences beyond this work.

Each renumber is safe to read as pure renaming because none of the migrations
`main` added in any of the three ranges creates or replaces any function these
do: the five `CREATE OR REPLACE` targets in V216 are untouched by V195-V207,
`main`'s V208-V211 replace only the `snapshot_cache_*` trio, which these
migrations never touch, and `main`'s V224-V228 create only blob and image
functions and triggers of their own — they name none of the knowledge,
information or operation predicates V229 and V230 replace, and none of the
`conversations` constraints V230 reopens. No definition changed hands by running
later, in any direction.

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

Visibility is one way, and in all three slices so far it is narrower than the
direction allows. The parent reads the child's execution **trajectory** through a typed
server contract; the child cannot read outward. **Resource reads arrived in slice
two and are metadata only**; deliverable reads are still absent, and absence
remains a different fact from denial. `application.child.resources` answers a
live child's staged source as a list of `(path, kind, revision, bytes)` behind
the same ancestor gate `trajectory` uses, and carries **no file content**,
deliberately: a child's writes are untrusted input to its parent and a
prompt-injection surface by construction, so metadata means a child's **file
contents** cannot reach a parent's prompt unless the parent asks for a named
file, which nothing in either slice permits. The honest qualification is that
path names are themselves child-authored strings and *do* travel outward, so the
operation narrows the surface rather than removing it; the obligation below binds
them exactly as it binds any other child output. No operation exists through which a parent reads
a child's deliverables, so nothing about them is implemented, exercised or
refused. An external Application or SDK reaches a child only by addressing the
parent, and the parent proxies only permissions it holds, so authority narrows at
each hop. The operations this record adds are the parent's own typed ones:
create, configure ceiling, list children, read trajectory, list resources,
destroy, **run and cancel** — eight, the last two added by slice three. Every
other attempt to address a child is refused.

**`run` and `cancel` are container-only, and the asymmetry with the reads is the
decision rather than an oversight.** `trajectory` and `resources` walk the
containment chain upwards, so a grandparent reads through it. Starting and
stopping work do not: the caller's retained admission must be acting *as* the
scope the child is recorded under, which is `create`'s and `ceiling`'s rule. The
reason is accounting rather than secrecy. A run spends the child's own allowance
under a ceiling the **direct container** set, and occupies that container's own
breadth budget; a grandparent raising work inside a grandchild would spend under a
bound the intermediate container set and never agreed to lend. Reading a
descendant's history commits nobody's budget, so the wider ancestor gate is right
there and wrong here.

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
authority mid-execution stops descendant work.

**Two things a reader will merge, and they are different subjects.** That Goals
sentence is about a *container's own* lifecycle: destroying the container, or
cancelling the container's execution, reaches every descendant. It is **not**
`application.child.cancel`, which stops work inside one named child and its
descendants and **destroys nothing** — the child stays live and startable, and its
retained envelopes and source area are untouched. With eight `application.child.*`
operations shipped the verb "cancel" now appears on both sides of that distinction,
so it is drawn here as well as on the operation. An interrupted create must leave
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
and may reduce to zero. **The pool is the child's and the accounting subject is
not:** a child spends its own allowance, under its container's ceiling, **on its
container's account**. Slice three decided that axis and this record said the
opposite — "child spend is attributed to the child" — which is corrected here and
at each site that carried it. `Speaker.contained(child)` records the
authorship the account no longer carries.

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
`knowledge_readable_scopes(account, 'contained:…')`, which after V223 resolves
**the subtree rooted at that home and nothing above or beside it**, and
`knowledge_document_admitted` applies `bool_and` over the whole dependency
closure — so a child revision whose closure reaches a row outside the subtree is
refused entirely rather than admitted through a dependency the child never named.

**What slice one left closed, and what V223 opened.** Slice one shipped a
fence by kind: `knowledge_scope_readable` admitted `ACCOUNT_KNOWLEDGE`, the
`GLOBAL_KNOWLEDGE` builtin, `PROJECT` and `PERSONAL`, and a `CONTAINED` scope
was none of those, so a reader under a contained home resolved an *empty*
readable set and neither a child nor its container could read the child's home.
That was the SQL-side closure slice one decided, and it is the fence slice two
deliberately narrowed.

V223 replaces it with a **directional** admission rather than with no
admission. `contained_scope_readable(actor, scope, general_member)` is declared
once and consulted by both chokepoints — `knowledge_scope_readable` (V172) and
`knowledge_read_authority` (V178) — so the exact-target predicate and the
batched relation cannot drift. A `CONTAINED` scope is readable when the actor is
that scope's **own principal** in `contained_principals`, or when the actor holds
`authority_project_role` on the project of a scope that **contains** it,
transitively. Ancestry is resolved by `contained_scope_ancestors`, which walks
**upward from the descendant**, so one-way visibility is a property of the walk's
direction and not of a filter on its answer: an ancestor reads a descendant, and
a descendant, a sibling and an unrelated scope are each simply not on the other's
chain. `service_project_role` is excluded from the ancestor arm deliberately: a
token's project role is scoped to the project its token names, and letting it
ride a containment chain would hand every token on a container read access to
subtrees the token never named. The retained read ceiling is widened in the same
migration and downward only, through `contained_scope_in_home`, in all three
bodies that decide it — `knowledge_document_in_boundary` (V172),
`knowledge_readable_scopes` (V184) and `knowledge_readable_candidates`'
`in_boundary` term (V198) — and in each the readability conjunct is left in
place, so the ceiling can admit nothing scope admission has not already admitted
and still only ever narrows.

**Document-level reads of a descendant's home open with scope admission, not with
the ceiling.** The ceiling's home term is the **request's selected project**, not
a value derived from the actor, so an ancestor that addresses the descendant's
own `contained:…` project as its boundary home already satisfied the equality
before V223 touched it; what admitting the scope changed is the second conjunct.
What the widened ceiling adds is the **cross-boundary** case: an ancestor
executing under *its own* project as boundary home, reading a resource whose home
is a descendant, which a name equality refuses however readable the scope is.
Getting this the wrong way round makes the ceiling look like a second fence that
re-refuses what admission allows, which it is not.

**A child reading its own documents back was refused by a gate scope admission
could not reach, and V229 opened exactly that gate.** Scope admission is the
first of two. The child's own scope *is* admitted by V223's self arm, but
`knowledge_revision_readable` opened with `information_global_read_member(actor)`,
which requires an `admins` row carrying the `GLOBAL_READ` role, and a contained
principal holds no credential material by design (V218): its identifier is a bare
UUID with no row in `admins`, `authority_accounts` or `service_tokens`. (That
"carrying the `GLOBAL_READ` role" describes the conjunct's *effect* here — V229
widened exactly it — and not the predicate's body, which tests no grant of that
role and no scope; the amendment under the knowledge-read limitations below states
what it actually requires.) V223 left
that as the **named prerequisite of the execution slice**, and slice three is that
slice.

V229 adds one arm to the opening conjunct — `contained_knowledge_actor(actor)`,
true only for a TEXT that parses as a UUID present in `contained_principals` — and
creates no `admins`, `authority_accounts`, `authority_roles` or `service_tokens`
row and grants no role. **This is a baseline, not general-read membership, and the
distinction is load-bearing.** The statement's closing disjunct is
`knowledge_scope_readable(...) OR knowledge_general_readable(r.id)`, and
`s.kind<>'RESTRICTED'` admits `CONTAINED`, so widening only the baseline would have
handed a child every generally-readable revision on the installation — authority its
container need not hold, through exactly the audience-driven reads the spec names as
an anti-goal. So the general arm is **closed for a contained actor in the same
expression**: `NOT contained_knowledge_actor($2) AND knowledge_general_readable(r.id)`.
For an admitted contained actor the disjunct collapses to its scope arm alone, and
there is no reading of the statement under which it reaches the general arm. The
discovery half is pinned the same way and one step harder: `general_member` is
computed as `information_global_read_member($1) AND NOT contained_knowledge_actor($1)`,
so a contained actor carries it **false**, and the candidates body opens its
selection arms on a separate `(general_member OR contained)` baseline while every
global arm stays guarded by `general_member` and by `NOT contained`. **Containment
dominates membership:** a principal that is somehow both a live contained row and a
real `GLOBAL_READ` member loses the general arm entirely, which is fail-closed and
is what a ceiling that only ever narrows should do.

**The write side's answer was that no gate existed to widen for writes either —
two did, and neither mentioned containment.** Slice three's characterization found
`information_lock_destination` (where a new document may land) and
`information_home_allows` (whether an existing one may be written) each keyed on a
credentialed `admins` row and a `PROJECT`/`PERSONAL` scope kind, so a contained
principal was refused before any write began. V229 widens each by exactly one arm,
keyed on a live `contained_principals` row and on **that row's own scope**, resolved
from the selected project and then compared against the resource's home — so naming
one's own project never reaches another home. `SET_AUDIENCE` is deliberately **not**
admitted: a child never changes who may read its output, and in particular can never
publish it generally readable. The destination arm takes a post-lock recheck rather
than trusting its own first evaluation, because the scope lock can wait on a
concurrent destruction that cascades `contained_principals` away.

A child's output is untrusted input to its parent. The parent reads trajectories
and deliverables in order to act on them, which makes those reads a
prompt-injection surface by construction. Child output is data, never
instruction, and any consumer that composes configuration or action from it must
treat it so. **This is why `application.child.resources` returns metadata and no
content.** No slice ships a consumer, and slice three changes what is at stake
rather than that fact: a child now *produces* output — a trajectory a container can
read back, a conversation lineage, utterances in its own log — where before there
was nothing to be injected by. **Documents in its own home are not in that list**,
and the reason is the no-caller limitation below: the write path exists and no
production path makes the child the information actor, so a child writes no
knowledge today. Slice two shipped the operation a consumer would call; slice
three ships the execution that fills part of it. The obligation binds every
later consumer: a parent that
starts returning child bytes to a model has re-opened by consumption what the
operation's shape closes.

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
parent, and the child's own allowance is spent under its parent-set ceiling on the
parent's account, with the child recorded as the author.
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
as the happy path working. This list is split on purpose: a verification section
that reads as proof while some of its items cannot fire is the defect this record
has already been corrected for, so each claim says whether this slice exercises
it.

**Exercised in this slice.** A child naming its parent, a sibling, an uncontained
project or the Global tier is refused at resolution. A child manifest declaring a
provider, Relay port, audience, swarm type, schedule or FileStore destination
beyond the parent's hold is reduced or refused, and a caller-named source
destination is refused rather than accepted and dropped. An external principal
addressing a child directly is refused, and addressing the parent succeeds only
where the parent holds the proxied permission. Narrowing holds at depth, and the
depth and breadth bounds refuse. Destroying a parent destroys its descendants. An
interrupted create leaves no orphan project, revision directory or principal.
Collection is observable *in its durable half*: a destruction writes a collection
record per child and reports what it removed, a sweep reports whether it looked
at its whole root, and an interrupted source removal is finished and marked by a
later sweep.

**Exercised in slice three, which is the slice that executes one.** A contained
run opens a conversation carrying `Origin.CONTAINED` in the child's own home,
speaks its first turn through the one door `Runs` can open, spends the child's own
allowance on its container's account with the child recorded as author, and
completes — `ContainedStartDoorTest.aContainedRunCompletesItsFirstTurn`, with the
authority layer present rather than stubbed out. A running child's reach is
pinned at six surfaces by `ContainedRuntimeReachTest`. A run is stoppable three
ways: `application.child.cancel`, a destruction that stops the subtree before
collecting any source, and a mid-run revocation of the container's grant.

**Which of the two "cancel" claims this slice exercises, said exactly.** It
exercises `application.child.cancel` — stopping work inside a named child and its
descendants, erasing nothing — and the destruction cascade, which stops the subtree
and then removes it. It does **not** exercise the Goals sentence's other half,
*cancelling a container's own execution* reaching its descendants: nothing in this
slice cancels a container's run and observes a descendant stop, so that half of the
Goals line remains asserted by the spec and undemonstrated here. The multi-node
bound on all of it is recorded under process-local cancellation.

**Three of the four claims slice two recorded as vacuous are now results, and the
fourth is still not one.**

- *A child reading knowledge receives nothing from outside its subtree.* No longer
  vacuous for want of a reader either: V229 admits the child's own principal to its
  own subtree and `ContainedActorReadSchemaTest` asserts, for that principal, that
  it reads its own document, is refused its container's, a sibling's, an ancestor's
  and any generally readable row it never named, and that discovery agrees with the
  exact predicate scope by scope. `ContainedKnowledgeBoundaryDatabaseTest` asserts
  the same through the boundary ceiling.
- *Siblings cannot observe one another's resources, trajectories or deliverables.*
  Unchanged for resources and trajectories, asserted at the operation and in SQL.
  Deliverables remain absence rather than denial, because no operation reads them.
- *Revoking a parent's authority stops descendant work.* Now asserted against a
  descendant that **is** running, and the place it is noticed is recorded below
  along with the one model call it cannot prevent.
- *Collection is observable* against a running child. **Still not a result.** A
  destruction stops the subtree's runs before collecting any source, which is
  asserted; what no test shows is a collection *observed* while the collected child
  was executing, and on a multi-node deployment the stop is process-local (below).

Tests follow the database test policy in [AGENTS.md](../../AGENTS.md): mocked
repository interfaces and in-memory fakes by default. Real PostgreSQL behind
`@Tag("full-db")` and `-PfullDb` is reserved for the migration, the containment
constraint, concurrent create and destroy, transaction rollback, and from slice
three the read and write predicates V229 changes, the lineage trigger V230
changes, and the row cascade a destruction depends on.

**A pre-existing `-PfullDb` failure a later runner must not read as containment
damage.** The full-database **information** suite has 17 failures —
`InformationFacetsTest` 7, `DocumentPoliciesTest` 9 and
`ServiceInformationProcessingTest` 1 — the first reading `NotFoundFault:
information is unavailable in this selection`. This slice's author ran that suite
at the **pre-V229** commit and obtained identical classes and counts, so it
predates this branch entirely and nothing in these three slices caused it. It is
recorded here rather than diagnosed, because a reader who enables `-PfullDb` after
a containment change and sees seventeen information failures will otherwise spend
the afternoon on them.

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
V216 closed them — `authority_live_grants()`, which would have reported a grant
against a contained scope as live; `authority_grant_insert()`, the database's own
grant admission check; and `authority_membership_changed()`, which minted a
`MEMBERSHIP` grant against a contained scope from a row trigger on
`project_members`, with no application code involved and so beyond the reach of
any Java predicate. So the statement that holds is the narrow one: *after* V216
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
anyone naming it. V216 closes it: a contained project carries a reserved
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
in a manifest. V216 makes the trigger resolve nothing for a contained scope, as
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
these would hand back a project with no scope rather than refuse. Which of the
fourteen refuses, which filters and which was already correct is answered one by
one in the addressability inventory below, which also adds five SQL sites this
grep missed and two listings that excluded only `client:`.

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

V217 freezes `authority_scopes.container` after insert, so containment ancestry
cannot be repointed and the relation is acyclic by construction: an insert can
only name an existing container, so the only way to close a cycle is to move an
existing node. V155's `authority_scope_locked` already refused a direct UPDATE of
a scope row; what was open, and is verified to have been open, is the one UPDATE
route that lock deliberately permits — `pg_trigger_depth()>=2`, the nested-trigger
path that exists for the mapping upsert recorded above. Nothing takes that route
today, so V217 closes a route reachable in principle rather than a live hole, and
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

Relay is now fenced. [ADR 0037](0037-events-and-messaging-on-relay.md) maps
`ADMINISTRATION` and `RESTRICTED` to a loud failure with no fallback topic and a
contained scope follows that, named explicitly in the audience map rather than
left to its `default`; no Relay scope kind is added, because
[ADR 0039](0039-relay-audiences-beyond-a-project.md) defers whether Relay keys
topics on `authority_scopes.id` to a later slice. The entry is in the
addressability inventory below.

## The addressability inventory

This is the third of the three inventories above, and it is the confinement
proof: **every place the server makes a project addressable without anyone
naming it, each entry fenced or refused.** It cannot be derived from the
compiler, because the substrate's authority decisions are `instanceof` and
`LIKE` tests that exclude a new kind silently rather than forcing a choice, so
it is an explicit audit. It consumes the exhaustiveness inventory above as one
input and the fourteen per-call-site prefix checks as another, and is not
limited to either.

### How the audit was conducted

So that a reader can judge whether it is exhaustive rather than merely long:

1. **The prefix checks.** `grep -rnE "(personal:|client:|contained:|Personal:)"`
   over `plowshare-server/src/main`, `plowshare-protocol/src/main` and `sdk`,
   filtered to the `startsWith`, `regionMatches` and SQL `LIKE` forms. This
   reproduced the fourteen sites recorded above and found **five more in SQL that
   the earlier grep had missed**, all in migrations rather than Java:
   `V110:28`/`V111:50` (`information_project_readable`), `V111:37`/`V156:320`
   (`service_project_role`), `V156:296` (`authority_project_role`),
   `V156:327` and `V178:45`. Each was traced to its *latest* definition — several
   had been superseded — and each resolved, **at slice one**, through a scope-kind
   test that `CONTAINED` fails. They were entered below as fenced by kind, with
   the supersession named, because "already excluded" is only true of the live
   definition. **Slice two makes the same point against itself:** V223 replaced
   the live definitions of two of them — `knowledge_scope_readable`, and with it
   `information_project_readable`, which composes it, and `V178:45`'s
   `readable_scopes` — so "fenced by kind" stopped being true of exactly those
   rows and they are re-decided below on a different reason. `authority_project_role`
   (`V156:296`), `service_project_role` (`V156:320`) and `V156:327` are untouched
   by V223 and their rows are unchanged. The lesson a future reader should take is
   that an inventory whose reason is a predicate body is only as current as that
   body: every slice that edits one owes this inventory a re-audit of the rows
   that cite it.
2. **The listings.** `grep -n "FROM projects"` over `ProjectStore`, then the
   callers of the two methods that pass drew attention to
   (`grep -rn "\.allFor(\|projects\.all()"`). This is what found the two worst
   entries: `project.list` and the Relay worker enrollment pass. **What the axis
   actually covered was the two listings whose callers were installation-wide**,
   not every method returning more than one project: it initially missed
   `allForSession:1256`, which also returns a list and also has no prefix
   exclusion, and which was added to the inventory afterwards. The lesson for a
   future reader is that "every multi-project method" is the right axis and that
   `grep -n "FROM projects"` alone does not enumerate it — a method composing
   another's result does not repeat the clause.
3. **The spec's own starting points**, followed to the code that implements each:
   `project.list`, the kind/`routingIdentity` surface, messaging
   `from_project`/`to_project`, Relay project-scoped subscribers
   ([ADR 0038](0038-relay-read-tier-subscribers-project-scoped.md)) and audiences
   ([ADR 0039](0039-relay-audiences-beyond-a-project.md)), startup schedule
   reconciliation, conversation search, and the union/git provisioning that
   ordinary project creation performs.
4. **The one chokepoint.** `ProjectAuthorization.require` resolves a project name
   to a role for every named-project operation in the server — 287 operations at
   the SDK contract's count. Rather than audit each, the audit establishes that
   the chokepoint cannot be bypassed for a contained project and that the role it
   resolves is always absent, and proves both against a real row. That is what
   makes `conversation.search` and the other 286 one entry rather than 287.
5. **What a child's project row actually contains**, read from
   `JdbcContainedApplicationRepository.create`, because every listing predicate
   filters on columns and a child satisfying `workspace IS NOT NULL` is why the
   listings were reachable at all.

The audit's unit is the *decision site*, not the call site: where many call sites
resolve through one predicate, the predicate is the entry and the call sites are
named in it.

### The inventory

Line numbers are as of the slice that last decided the row — slice one unless the
row says otherwise, and the two re-decided knowledge-read rows and the
`ProjectAuthorization` row were re-checked against the code in slice two. They
drift, and the `ProjectAuthorization` row's had already drifted about fifteen
lines by then, so **a construct name accompanies the figure wherever one would
otherwise have to be trusted**; where the two disagree the construct is the
citation and the figure is stale. "Refused" means a check added or widened in the
slice named; "fenced by kind" means an existing predicate already excludes a
contained scope and is verified to, with the verification named.

| Site | Surface | Decision |
| --- | --- | --- |
| `plowshare-server/.../archive/ProjectStore.java:1294`, `allFor` | `project.list` and `GET /v1/projects` | **Refused.** The worst entry. A child's project row carries a `workspace` — its container-owned staging area — so the predicate's `workspace IS NOT NULL` admitted it and only `client:` was excluded: a server administrator's listing would have named every child on the installation. Excluded by the reserved prefix. |
| `plowshare-server/.../archive/ProjectStore.java:1337`, `all` | the project catalogue | **Refused.** `ProjectCatalogue.all()` is read by background reconciliation and by `relay/ApplicationRelayWorkerBindings.java:38`, which offers *every row it returns* for Relay worker enrollment. A child would have been offered on every pass, with nobody having named it. |
| `plowshare-server/.../archive/ProjectStore.java:1256`, `allForSession` | the client-session project listing | **Fenced by construction, and deliberately not given a prefix exclusion.** It returns `allFor(handle)` — fenced above — plus one further query whose only predicate is `name LIKE ?` with the pattern `ClientProjects.prefix(session, handle) + "%"`. That prefix is `client:` followed by the hex SHA-256 of `handle\0session` and a colon; it is computed, never caller-supplied, and cannot be steered. So the second query can only ever return `client:` names and a `contained:` name is unreachable by it. The justification is recorded here rather than left to be re-derived, because the safety is a property of how the pattern is built and not of anything visible at this line. **A caller-controlled pattern reaching this query would reopen it.** |
| `plowshare-server/.../archive/ProjectStore.java:1317`, `ordinary` | project create, rename, publish, administer | **Refused** (reserved in Task 2; now reads the shared predicate). |
| `plowshare-server/.../access/ProjectAuthorization.java`, `require` (`:355`) → its closing project loop (`:471`–`:479`) | every named-project operation that reaches the project loop, `conversation.search` among them | **Fenced by kind, with named exceptions — one of them re-sourced in slice two.** `members.requireRole(project, account, permission)` (`:477`) resolves no role for a contained project, and it is what the loop calls for every project in a non-empty set. Verified end to end against a real child row for the strongest external principal there is — a server administrator — and for the container's own MANAGER. **This is not a universal, and the difference matters:** five branches return before that loop and resolve no project at all — the `HostedToolScopes.WORKER_OPERATIONS` branch (`:359`), the `proc.*` set (`:364`), the `information.policy.set`/`.get` pair (`:372`), `ACCOUNT_OWNED` (`:415`) (`schedule.pause` resolves its own schedule-provenance projects; **`schedule.forget` resolves nothing**, because it is keyed on a schedule id rather than a project) and the `service && GENERAL_INFORMATION_READ` early return (`:439`). Each is named by its construct as well as its line, because the figures in this row were already stale by about fifteen lines when slice two re-decided it — inherited from `81bceaf1`, before this slice — and a construct name is what makes the next drift self-correcting. None is a hole today, but each is closed by an **upstream refusal rather than by this chokepoint**, which is the weaker of the two and is why they are named here: a reader who takes the chokepoint as universal would stop looking at exactly the paths it does not cover. For the schedule pair, the upstream refusals are Task 5's refusal of a child declaring a schedule, `FileScheduleDefinitions:214` `manage` (no source can be registered for a child) and `JdbcScheduleStore:39` (nothing fires for one), so no schedule id can resolve to a child. For the other four, the surfaces reach no project-keyed state a child has: the worker frames delegate to their typed route service, `proc.*` are metadata filters whose repository rechecks custody per read, `information.policy.*` resolves a durable reference target, and service `GENERAL_INFORMATION_READ` resolves through `knowledge_scope_readable` (its own row below). **Slice two re-decided that fifth exception, and its reason is now a different one.** It was "`knowledge_scope_readable` admits no `CONTAINED` scope", and V223 made that sentence false. The entry stays **fenced**, on the narrower reason that V223's containment arm resolves `authority_project_role` **only** and deliberately not the `coalesce(authority_project_role, service_project_role)` the pre-existing arm uses — and a token principal's project role comes *only* from `service_project_role`. The self arm cannot help it either: it matches a bare `contained_principals` UUID and additionally requires the actor to have no `admins` row, which every service principal has (`account_kind='SERVICE_TOKEN'`). So every operation in that set reads a container's own scope through the token and reaches no descendant of it. Evidence is a test that asserts the positive first — the token principal genuinely resolves `MANAGER` on the container through `service_project_role` and is admitted to the container's scope — and then that it is admitted to neither the child's nor the grandchild's, so the refusal is the arm's and not a dead fixture's. One honest caveat: a service principal that an administrator has made an *ordinary* project member of an ancestor and granted `GLOBAL_READ` does resolve `authority_project_role` there and is then admitted to that ancestor's subtree. That is the design's rule — an ancestor role holder reads its descendants — applied to a principal that holds an ordinary role, not the token route this arm excludes. **The task that makes a contained child execute must still re-audit all five**, because an upstream refusal that was true while nothing executed a child is not guaranteed to stay true. |
| `plowshare-server/.../archive/JdbcProjectMembers.java:136`, `role` | the role every call site asks for | **Fenced by kind.** `authority_project_role` requires a live grant whose scope is `PROJECT` or `PERSONAL`; `service_project_role` requires its owner's. Both answer null for a `CONTAINED` scope. `mayWork:200`, `mayManage:204`, `mayUse:422`, `access:239` and `isMember:50` are all built on it and are each asserted. |
| `plowshare-server/.../archive/JdbcProjectMembers.java:215`, `mayAdminister` | `project.access`, `project.member.*` | **Fenced by kind.** `authority_project_management_grant` admits either a live grant on the target scope — excluded — or `ORDINARY_PROJECT_CONTROL` coverage against `target.kind='PROJECT'`, which `CONTAINED` is not. |
| `plowshare-server/.../archive/JdbcProjectMembers.java:164`, `readableProjects` | batched candidate admission | **Fenced by kind.** Its SQL `ELSE` branch is `authority_project_role`; the `personal:`/`client:` `LIKE` arms are not reached. |
| `plowshare-server/.../archive/JdbcProjectMembers.java:84`, `ordinary` | `project.member.add` / `remove` | **Refused.** A membership row against a child mints nothing — V216 makes `authority_membership_changed` resolve no scope for a `CONTAINED` one — so the row would be authority-free, but it would still make a child look like a shared project to every membership listing. |
| `plowshare-server/.../archive/JdbcProjectMembers.java:64`, `members` | member hydration | **Deliberately unchanged, and recorded.** It has no prefix test and no authority test; its callers authorize first. It discloses nothing for a child because the write side above refuses, so no row can exist. A check here would be dead code; the honest statement is that this read is fenced by the write refusal and by nothing else. |
| `V216` `authority_membership_changed` | a grant minted inside the database | **Refused** (Task 2). Re-verified here against a real `project_members` insert made underneath the repository refusal, with the parent as the control. |
| `V216` `authority_map_project`, `authority_scope_shape` | the auto-mapping to an addressable `PROJECT` scope | **Refused** (Task 2). Re-verified: a child project acquires no scope, and `authority_scope_shape`'s `PROJECT` branch excludes the prefix. |
| `V216` `authority_live_grants`, `authority_grant_insert` | grant admission and liveness | **Refused** (Task 2). Covered by `ContainedScopeSchemaTest`. The `authority_live_grants` exclusion is a backstop with no reachable route, because `authority_grant_insert` refuses first; that test reaches it only by disabling the insert trigger, and it is recorded as defence in depth rather than as a live fence. |
| `V223` `knowledge_scope_readable` (was `V172:9`), and `V172:19` `information_project_readable`, which composes it | information and knowledge read admission | **Was fenced by kind; slice two deliberately opened it, one way; slice three admits the child's own principal to the baseline above it and re-decides the row against a running reader — see the re-audit below.** The slice-one reason — `s.kind IN ('PROJECT','PERSONAL')`, so a contained scope is excluded — is **no longer true**, and this row is the capability slice two set out to add rather than a hole it left. V223 adds one arm whose admission is `contained_scope_readable`: the scope's own `contained_principals` principal, or an actor holding `authority_project_role` on the project of a scope that transitively **contains** it, resolved by walking `contained_scope_ancestors` **upward from the descendant**. Confinement is therefore the walk's direction, not a filter: a descendant reading upward, a sibling, a nephew, a stranger, a server administrator with no role on an ancestor, and a service credential on the container are each asserted to get nothing, and a broken chain refuses at the break so nothing above it is an ancestor of anything below. `information_project_readable` is `EXISTS(… knowledge_scope_readable …)` over the scopes of one project, so it moved with the predicate and now answers true for an ancestor role holder asked about a child's project. **This inventory's own evidence for that row is `ContainedAddressabilityDatabaseTest.informationReadabilityAdmitsAnAncestorAndRefusesEveryoneElse`**, which asserts both directions in one test — an ancestor admitted, a server administrator that is a global-read member but holds no role on the container refused — so it fails whichever way the arm breaks; the opened behaviour in its own right (the self arm, the chain, siblings, the walk sentinel, a broken chain) is `ContainedKnowledgeReadSchemaTest`. The slice-one assertion that this predicate *refused* a child was obsolete the moment V223 landed and is gone; `everyRoleAndManagementPredicateRefusesAContainedProject` is what remains of that test and no longer names this predicate, because the role and management predicates are the ones that still refuse by kind. **Its consumers are fenced upstream, not by kind, and the fence is a property of the argument rather than of a closed caller list:** this predicate is only ever asked about the **request's selected project**, and a `contained:…` name cannot become one — `ProjectAuthorization`'s chokepoint resolves no role for it (the row above) and `IdentityRepository.project(String)` still refuses to resolve a contained scope from a project name. Stated that way the fence survives a new consumer; stated as a caller list it would not, which matters because the list is longer than two. There are **two Java call sites** — `MemoryStore.java:76` (`pin`, the memory-home authority check), which passes the selected project *by name*, and `JdbcQueryEmbeddingRepository.java:64` (`require`), which passes `(SELECT project_id FROM authority_scopes WHERE id=?)` for the **pin scope resolved from the selected home**, so it reaches the same project by a different step rather than by its name — and **one live SQL composition**: `information_root_readable` (latest definition `V185:27`) calls `information_project_readable(linked.id,$2)` at `V185:37`, inside an `information_links` arm constrained by `linked.name=$4`, which is that statement's `selected_project`. All three therefore turn on the same selected project, and none can be handed a contained one. That is a weaker fence than a predicate that refuses by kind, and it is recorded here for the same reason the chokepoint's five exceptions are. The superseded definitions at `V110:21`, `V111:41` and `V156:324`, whose `a.server_admin AND name NOT LIKE 'client:%'` arms *would* have admitted a child, remain dead. |
| `V229` `knowledge_read_authority` (was `V223`, was `V178:45`) | batched knowledge read authority | **Was fenced by kind; opened by the same rule, and by the same rule only. V229 reproduces it with `general_member` pinned false for a contained actor, which is a narrowing of this body and not a second opening — see the re-audit below.** The slice-one reason — `s.kind IN ('PROJECT','PERSONAL')` guarding the whole branch — is no longer the whole predicate. V223 adds `OR (s.kind='CONTAINED' AND contained_scope_readable($1,s.id,a.member))` as a disjunct beside the pre-existing `a.member AND (…)` gate, because a contained principal has no account to be a member of. Both chokepoints consult the one shared rule, and a test asserts the batched relation and the exact predicate agree scope by scope, so discovery cannot admit what an exact read refuses or the reverse. Everything else in the body — the live-grant, `project_reads`, token and Personal fences and the `MATERIALIZED` shape — is untouched, and `authority_live_grants` still honours no grant against a contained scope (V216), so no role is ever resolved *on* one; that slice-one exclusion is now load-bearing for this arm rather than only defence in depth. |
| `V223` `information_public_policy` (was `V172:21`), and the `general_readable` column of `V229` `knowledge_readable_candidates` (was `V223`, was `V198:5`) | general-read publication: the audience that makes a resource addressable to every account without anyone naming it | **Refused in slice two. A new row, and the only one in this inventory that runs outward rather than inward. Slice three opens the write path this row is about and does not wire its actor, so the row is one step nearer reachable rather than reachable — see the re-audit and the no-caller limitation below.** Every other knowledge-read row asks who may reach *into* a contained subtree; this one asks whether a contained home can be published *out* of it. Both bodies that decide general-read publication fenced on `s.kind<>'RESTRICTED'` / `f.scope_kind<>'RESTRICTED'` alone — the only scope kind that existed to be excluded when they were written — and V216, which added `CONTAINED` as a fifth kind, did not extend either test. The consequence was concrete rather than theoretical: `knowledge_revision_readable` (`V172:38`) is `… AND (knowledge_scope_readable($2,q.home_scope_id) OR knowledge_general_readable(r.id))` and the candidates body's `facts.readable` (`V223:436`) is `… OR f.general_readable`, so a revision whose home was a contained scope, if its policy audience were `GLOBAL_READ`, would have been admitted to **every global-read member — which is every active non-bootstrap account — with no containment test taken anywhere**. It was **pre-existing from slice one and not reachable**: the policy insert trigger (`V158:88`) writes `audience='HOME'`, the navigation default makes `NOT coalesce(ownerOnly,true)` the refusing value, and nothing executes a contained child, so no contained-home resource exists to publish. That is confinement resting on a default value rather than on a structure, which is the spec's named anti-goal, so V223 adds one conjunct — `s.kind<>'CONTAINED'`, and `f.scope_kind<>'CONTAINED'` in the hoisted twin — beside each existing `RESTRICTED` test, reproducing `information_public_policy` verbatim from `V172:21` otherwise. It only ever narrows: the conjunct can refuse a general publication, never admit one. The narrow body is the one edited on purpose, because `knowledge_general_readable` (`V172:30`) and the closure predicates (`V158:183`, `V158:194`) all resolve through it, so one conjunct fences the general arm of `knowledge_revision_readable`, of `information_root_readable` and of `knowledge_document_in_boundary`'s global arm, and additionally refuses a published resource whose closure merely *reaches* a contained home. Evidence is `ContainedKnowledgeReadSchemaTest.aContainedHomeIsNeverPublishedOutsideItsSubtree`, which asserts the baseline first — the stranger is a live general-read member and the identical publication in an ordinary home really does reach it through the general arm alone — then the refusal for the exact predicate, for discovery's own hoisted copy, for a general-read ceiling under both homes and for a closure that merely touches the subtree, and finally that the ancestor's inward read is untouched, so it fails whichever way the fence breaks. **`knowledge_revision_readable` (`V172:36`) and the candidates body's `readable` column (its kind test at `V223:433`) carry the same `RESTRICTED` test and deliberately do *not* gain this conjunct:** their test governs the whole read rather than the general arm, so excluding `CONTAINED` there would refuse an ancestor's read of a descendant's home — the capability slice two exists to open — and their general arm is already fenced through `knowledge_general_readable`. |
| `plowshare-server/.../board/ProjectMessageRouting.java:85` | messaging `from_project` and `to_project` | **Refused**, in both directions. Messaging is a narrowing of the existing Relay and Application scoping and not a forwarding surface. **The ordering is a finding, not a style choice:** `require` returns early when source equals destination, so with the check after that return a child addressing *its own* project was admitted with no policy consulted at all. A contained project is not a messaging endpoint, its own included. **Slice three re-decided this row against a running child and the decision did not move** — the refusal is keyed on the project name, so the bare-UUID principal a run carries is refused exactly as a fabricated handle was, while the same principal reaches an ordinary project unimpeded; what changed is that there is now a run being deprived of a self-addressed endpoint rather than a hypothetical one. The check also precedes the route policy, so the refusal names containment rather than an absent route. |
| `plowshare-server/.../board/ProjectMessageRouting.java:65`, `identify` | the `routingIdentity` surface | **Fenced upstream.** It maps a project to its personal-space address or answers the name unchanged; it decides nothing, and every caller of it has already passed `require`. |
| `plowshare-server/.../information/JdbcInformationEventRecords.java:160` | the Relay audience for an information event | **Refused, unchanged — and slice three found the refusal reachable from a constructed context, which is why `admit` now tolerates it for a declined home. Re-decided below; read that entry before this one.** The slice-two sentence that "a child's events never had a topic" is still true of production, because no production path makes a contained principal the information actor and so no contained-home resource exists to announce. What slice three established is the shape of the failure *when* one does: a contained-home write announces an intake event, and the real publisher resolves an audience before anything else, so the write aborts unless the announcement tolerates the refusal. The honest statement at slice two: `audienceProject`'s `default -> null` already produced `Unaddressable` for a `CONTAINED` kind before this change, so a child's events never had a topic, and ADR 0037's rule for `ADMINISTRATION` and `RESTRICTED` — fail loudly, no fallback topic — already applied by exclusion. What was added is `case "CONTAINED" -> null` named beside `PROJECT`, `PERSONAL` and `ACCOUNT_KNOWLEDGE`, which is **behaviour-identical** and exists so the decision is visible where the next kind is added rather than being a silent default. The mutation that proves the behaviour changes the `default` arm, not the added case. It is still a load-bearing entry, because a contained scope *does* carry a project key: an implementation that reached for it would have published a child's events to a topic whose audience is the child's project. Per ADR 0039 no Relay scope kind is added. |
| ADR 0038's project-scoped Relay subscribers | consumption, which takes a lease | **Fenced by kind.** Consumption gates on `members.mayWork`, which is the `role` entry above. |
| `plowshare-server/.../union/UnionStore.java:86` `find`, `:98` `enabled`, `:105` | the server-side working tree and the bare Git hub | **Fenced by `project_type`.** A child's row is `MANAGED`; every union read requires `STANDARD`. So a child is not a union *candidate* — it is not that one is created and then disabled — and `unionRecovery`, which iterates `unions.enabled()` at startup, never sees it. `union.status` answers `eligible: false`. Ordinary project creation provisions no union either; a union is created by `union.enable` or by Personal account creation, both of which go through the membership fence. Asserted directly: **creating a child provisions no working tree and no git hub.** |
| `plowshare-server/.../union/UnionFrames.java:334`, `union` | `union.*` frames | **Fenced upstream** by the two above: `claimed` requires the session to root the project, and `union(project)` refuses a project `UnionStore.find` does not return. |
| `plowshare-server/.../events/FileScheduleDefinitions.java:89`, `requireRead` | `schedule.list`, `schedule.read`, `schedule.files` | **Refused.** ADR 0020 gives a schedule operational authority over the server's own scheduler and nothing here mediates that for a child. Task 5 refuses a child *declaring* one; this refuses the surface that would name a child's project as a schedule source, so the two halves agree. |
| `plowshare-server/.../ws/ScheduleListHandler.java:42`, `project` | the frame-boundary schedule selection | **Refused**, the same decision made before any projection is read. |
| `plowshare-server/.../events/FileScheduleDefinitions.java:214`, `manage`, and `:233`, `reconcile` | schedule source enrollment and the reconciliation pass | **Fenced by kind.** `enroll` requires `mayManage`, so no source can be registered for a child; `reconcile`, which `poll()` runs over every source, requires `mayWork` of the source's owner and rejects the source's files otherwise. |
| `plowshare-server/.../events/JdbcScheduleStore.java:39`, `EMITTER_AUTHORIZED` | `due`, `claim`, `rollForward`, `claimPublication` — what the scheduler fires | **Refused.** The rule admitted a server administrator's file-managed schedule on any project that is not `client:` or `personal:`, and a child's project satisfied that. Unreachable today because enrollment refuses, but this is the server's own scheduler deciding to do work for a project nobody named, and a fence that depends on a separate refusal upstream is exactly what this record argues is not a proof. Asserted against real rows, with the parent's schedule as the control. |
| `plowshare-server/.../auth/ServiceAccounts.java:143`, `validateScopes` | service token project scopes | **Refused.** A token scope is an external principal's standing reach into a project; a child is reached only through its container, which must hold the permission it mediates. |
| `plowshare-server/.../applications/SourceApplicationDeployments.java:68`, `require` | `application.deploy`, `activate`, `status`, `receipt` | **Refused.** A child's source area is allocated and owned by its container under `plowshare.containment.root`; admitting a deployment would give an administrator a second writer for it and make the child an ordinary Application. |
| `plowshare-protocol/.../ApplicationDeployment.java:122`, `identity` | the deployment DTO boundary | **Refused.** So a request naming a child cannot be constructed at all, and the refusal does not depend on reaching the server-side check. |
| `plowshare-protocol/.../ApplicationIdentity.java:105`, `project` | Application identity and credential issuance | **Refused.** An Application identity is an externally addressable principal for a project. |
| `plowshare-protocol/.../HostedToolScopes.java:313`, `project` | durable hosted tool scopes | **Refused.** A durable host is reachable by its route for as long as it exists, independently of who created it — an address nobody has to name again. |
| `plowshare-server/.../agents/WorkspaceApplicationPolicy.java:53`, `read` | the agent file-configuration boundary | **Refused as `INVALID`, deliberately not `EXTERNAL`.** `EXTERNAL` passes a caller's project membership straight through, so any role a child project ever acquired would become file authority over its container's staged source; `INVALID` denies every account. A child is refused before any workspace or registration lookup, which also keeps a child's `MANAGED` row out of `registrations.require`. |
| `plowshare-server/.../applications/ContainedDeclarations.java:167`, tool bindings | `server/tools.json` `bindings` | **Reduced.** Task 5 recorded these as accepted-and-uncompared; that gap is closed. A binding is what makes a tool callable and its provider need not appear in the package's own `providers[]`, so the provider subset test did not cover it. A binding identical to one the container holds stays admissible, which keeps this a subset test. |
| `plowshare-server/.../applications/ContainedDeclarations.java:175`, Relay worker account | `server/relay-workers.json` | **Reduced.** The worker account is the identity that services a project's work; a child naming one its container does not declare would have its work serviced by an identity no container mediated. Enrollment separately refuses it, because `ApplicationRelayWorkerBindings` checks `members.mayWork` and a child project is now not in the catalogue at all. |
| `plowshare-protocol/.../ContainedProjects.java` | the prefix itself | **One definition**, case-insensitive, replacing the two copies at `ProjectStore.ordinary` and `SourceContainedApplications.PREFIX`. The SQL exclusions are case-sensitive `LIKE` and the Java test is not; that asymmetry is the safety belt rather than a discrepancy, because creation refuses `Contained:x` outright while SQL would have mapped it to an ordinary scope. |

Thirty-five entries, the last of which slice two added. **At slice one, thirty-four
entries: twenty refused or reduced here, fourteen
fenced by an existing predicate, by construction or by an upstream refusal and
verified** (counting the prefix definition itself as a refusal, and counting as
fenced the one read deliberately left unchanged, the session listing that is safe
by construction, and the Relay audience arm that was already refusing before that
change). The two that mattered most were not in either list this audit
inherited: `project.list` and the Relay worker enrollment pass,
both reachable because a child project carries a workspace path and both
excluded only `client:`.

**After slice three the table's verdicts are unchanged in every row but the Relay
audience one, whose refusal became reachable; the slice-three re-audit below
re-decides all thirty-five against a running child and states the arithmetic.**

**After slice two: twenty-one refused or reduced, twelve still fenced, two
deliberately opened.** The two opened are the knowledge-read rows above; the
twenty-first refusal is the outward general-read publication row, which is the
one entry the slice-two re-audit **added** rather than re-decided — a surface
slice one's audit did not have, because an outward direction only becomes a
question once an inward one is open. Every other row was re-decided
against the code and is unchanged, because the predicate each one names is
untouched by V223: `authority_project_role`, `service_project_role` and
`authority_project_management_grant` still answer nothing for a `CONTAINED`
scope, so `role`, `mayAdminister`, `readableProjects`, ADR 0038's Relay
subscribers (which gate on `mayWork`) and `FileScheduleDefinitions.manage` /
`reconcile` (which gate on `mayManage` and `mayWork`) all keep their reasons
intact; `authority_live_grants` and `authority_grant_insert` still refuse, which
is what makes the new ancestor arm resolve roles only on uncontained ancestors;
and the Relay audience arm, the messaging refusal, the union's `project_type`
filter and every prefix-based exclusion are untouched by a knowledge-read
change. **One row's verdict is unchanged but its reason is not** — the
`ProjectAuthorization` chokepoint's fifth named exception, service
`GENERAL_INFORMATION_READ`, which cited the kind fence by name and now rests on
the ancestor arm's exclusion of `service_project_role`.

### The slice-three re-audit: every row against a running child

Slice two's largest recorded limitation was that **every row above was proved
against inserted rows and never against a running child**. Slice three executes
one, so each row is re-decided here on the question execution actually poses:
*does a running child reach this surface, and if it does, is the row's answer
still the same one?* A restatement is not a re-audit, so the rows are split by
that answer rather than listed again, and every row execution could have changed
carries a test that names the live child or its own principal.

**The structural fact that decides most of the split.** A contained principal is
a bare UUID with no row in `admins`, `authority_accounts` or `service_tokens`
(V218), so it cannot sign in, holds no handle, and reaches **no frame**. Every row
whose surface is a WebSocket frame or an HTTP controller is therefore unreachable
*as the child*, not because a check refuses it but because there is no door. What
execution newly reaches is a short list: the authority substrate's resolution and
recheck path, the knowledge and information read and write predicates, the
tool-argument boundary that turns a built-in tool's arguments into typed resource
references, the definition registry and tool bindings of the child's own home, and
the conversation and allowance rows a run writes. **The knowledge and
information predicates are on that list as gates and not as traffic**: V229 opens
them for a contained actor and nothing in production presents one, which the
no-caller limitation states in full and which every row below that depends on it
says for itself. The information event a knowledge write would announce is
therefore not reached either. The rows below are grouped on that.

**The arithmetic, so the split is checkable rather than assertable.** The main
table keeps its thirty-five entries and gains none: **nineteen are reached by
execution** and re-decided individually below, and **sixteen are not** and are
listed with their one shared reason. **No row changes its answer.** An earlier
draft of this paragraph said one did — the Relay audience row, whose refusal slice
three found reachable — and that count rested on a claim this record has since
corrected: the refusal is reached from a constructed context and not from any
production path, because no production path makes a contained principal the
information actor, and nothing else can create a contained-home resource either (a
container holds no role on a `contained:` project, so the ordinary destination lock
refuses it). The row's verdict and its reachability are therefore both unchanged;
what the slice added is a write path waiting for an actor.

**Six rows change their reason while keeping their verdict**, and they are the ones
that do not depend on that actor: the `ProjectAuthorization` chokepoint, the three
`role`-family predicates and the two project listings, each measured for the first
time against the **contained principal the hop really does create** —
`principals.issue(scope)` writes a `contained_principals` row in production, so the
principal is real even though it is never the information actor, and the project
surfaces can be and are asked about it. The remaining thirteen keep both verdict
and reason and gain a test that names the live child or that principal. Beside them the re-audit adds **two new
write-side rows** (V229's two gates, which did not exist to be audited before) and
**one inventory gap** it names rather than closes. The exhaustiveness inventory's
single row is re-decided in the same table.

#### Rows execution reaches, each re-decided

| Row | Slice-three decision | Evidence naming the live child |
| --- | --- | --- |
| `ProjectAuthorization.require` and its five named exceptions | **Fenced, and for the first time measured against the actor a run carries rather than against ordinary accounts.** Slice one proved the chokepoint for a server administrator and the container's own MANAGER; the actor slice three adds is the child's own principal, which the misclassification note below predicted would be routed into the *user* branch. It resolves no role on any project — not its container's, and not its own. The five exceptions are each frame-keyed, so the handle and not this chokepoint is what closes them for a child; the service `GENERAL_INFORMATION_READ` exception is additionally unreachable because a contained principal is not a service token. **What is unchanged is the honest part: the container's own authenticated calls still reach this chokepoint exactly as before.** | `ContainedAddressabilityDatabaseTest.theRunningChildsOwnPrincipalResolvesNoRoleOrListingAnywhere` |
| `JdbcProjectMembers:136` `role`, `:215` `mayAdminister`, `:164` `readableProjects` | **Fenced by kind, re-measured with the principal as actor.** `authority_project_role` and `authority_project_management_grant` answer nothing for it on either project, and the repository reads built on them — `role`, `mayUse`, `mayWork`, `mayManage`, `mayAdminister`, `isMember`, `readableProjects` — all answer empty. | the same test |
| `ProjectStore:1294` `allFor`, `:1337` `all` | **Refused, re-measured.** The child's own principal names nothing in either listing, **including its own project** — reading a home is not addressing a project, and that distinction is what keeps the knowledge capability from leaking into the project surface. | the same test |
| `V223`/`V229` `knowledge_scope_readable`, `information_project_readable` | **Deliberately open, one way, and now open to a real reader.** Slice two could admit the scope and not the principal; V229 admits the principal to the baseline above it. The refusals that matter are asserted *for that principal*: its container's document, a sibling's, an ancestor's, a generally readable row it never named, and a dependency closure leaving its subtree. The ancestor's inward read is unchanged. | `ContainedActorReadSchemaTest` (`aChildReadsItsOwnDocument`, `aChildIsRefusedItsContainersDocument`, `aChildIsRefusedAGenerallyReadableRowItNeverNamed`, `aChildsDependencyClosureLeavingItsSubtreeRefuses`); `ContainedKnowledgeBoundaryDatabaseTest` |
| `V229` `knowledge_read_authority`, `knowledge_readable_candidates` | **Narrowed, not opened again.** `general_member` is pinned false for a contained actor and the candidates baseline is a separate `(general_member OR contained)` term, so discovery admits the child's own subtree and reaches no general arm. Discovery and the exact predicate are asserted to agree scope by scope, so one cannot admit what the other refuses. | `ContainedActorReadSchemaTest.theDiscoveryPathAdmitsTheChildsOwnScopeAndNotGeneralMembership`, `aChildCannotDiscoverGenerallyReadableRows`, `aChildIsRefusedGeneralReadThroughTheBoundaryCeiling` |
| `V223` `information_public_policy` and `V229` `knowledge_readable_candidates`' `general_readable` | **Refused; the row's reachability moved one step and not two.** Slice two recorded this surface as unreachable because "nothing executes a contained child, so no contained-home resource exists to publish". Slice three opens the gates that would let a child produce one and **does not wire the actor that would reach them** (see the limitation below), so no contained-home resource exists in production yet either. What changed is that the write path now exists and is proved against a constructed context, so the conjunct V223 added is the thing standing between that path and an outward publication — defence in depth that has acquired a consumer in waiting rather than a live fence. Two fences stand in front of it: the write side cannot even ask — `SET_AUDIENCE` is not admitted by V229's write arm — and the read side refuses the publication if one somehow existed. | `ContainedActorWriteSchemaTest.aChildCannotPublishGenerallyReadable`, `aChildCannotSetAnAudienceEvenOnItsOwnDocument`; `ContainedKnowledgeReadSchemaTest.aContainedHomeIsNeverPublishedOutsideItsSubtree` |
| V229's two write gates, `information_lock_destination` and `information_home_allows` | **New rows, inward and write-side.** Each admits a live contained principal to **its own scope's** home and nothing else, resolved from the selected project and compared against the resource's home. A child writing in its container's home, a sibling's, an ancestor's, another home while naming its own project, or after revocation are each refused. | `ContainedActorWriteSchemaTest` (nine cases); `ContainedExecutionWriteDatabaseTest.aChildWritesKnowledgeAsItselfAccountedToItsContainer` for the Java path |
| `JdbcInformationEventRecords:160`, the Relay audience | **Refused, and the refusal turned out to be reachable from a constructed context — as an exception thrown inside the child's own write, which it aborted. Its answer does not change under execution as shipped**, because the write path has no production actor (see the no-caller limitation), so nothing in production asks it. What was wrong was the belief that nothing *could* ask. The intake announcement now tolerates the refusal for a declined home only, so the write completes and the audience stays absent. Recorded as a found defect with its narrow fix below rather than as a fence working, and the arithmetic above counts no row as answer-changed because of it. | `ContainedExecutionWriteDatabaseTest.aChildsOwnWriteHasNoRelayAudienceAndTheContainersDoes` for the audience; `ContainedIntakeAnnouncementDatabaseTest` for the fix, composed with the real publisher |
| `ProjectMessageRouting:85`, messaging in both directions | **Refused; the decision does not move and the consequence does.** The refusal is keyed on the project *name*, so the actor's shape is immaterial to it, and the bare-UUID principal a run carries is refused for the child's own project exactly as a fabricated handle was — while the same principal reaches an ordinary project's routing unimpeded, which is what makes the refusal containment's. What changes is that there is now a run being deprived of a self-addressed endpoint. Separately, and structurally: no messaging tool is in `RetainedToolAccess.SYNCHRONOUS` at all, so retained work of any kind — contained or not — cannot reach messaging through a tool. | `ContainedAddressabilityTest.aRunningChildsOwnPrincipalIsNoMessagingEndpointEither` |
| `ContainedDeclarations:167` tool bindings, `:175` Relay worker account | **Reduced at declaration, and the runtime half is now asserted.** A contained run's tool call resolves the snapshot of its *own* project's `server/tools.json` and nothing else, so a binding of the container's is absent from the set the child's home resolves rather than merely ungranted. | `ContainedRuntimeReachTest.aRunningChildsToolCallResolvesOnlyItsOwnBindings` |
| `UnionStore:86`/`:98`/`:105`, the working tree and git hub | **Fenced by `project_type`, and starting a run provisions neither.** The at-rest half was already asserted; this is the runtime half. | `ContainedRuntimeReachTest.aRunningChildAcquiresNoWorkingTreeOrGitHub` |
| `JdbcScheduleStore:39`, `FileScheduleDefinitions:89`/`:214`/`:233`, `ScheduleListHandler:42` | **Refused and fenced, with no runtime door beside them.** A child cannot declare a schedule, holds no handle to reach a schedule frame, and no tool in the set a contained run is offered defines one. The bound on that third leg is stated in the test's own Javadoc and is real: it is the set that fixture's runtime serves, not every built-in a deployment wires. | `ContainedRuntimeReachTest.aRunningChildCannotDefineASchedule`; `ContainedAddressabilityDatabaseTest.startupScheduleReconciliationSkipsContainedProjects` |
| `WorkspaceApplicationPolicy:53`, the agent file boundary | **Still `INVALID`, and a run does not reach it.** A run resolves its definitions through `DefinitionResolver`/`Callers.find` on the child's own home and its tools through `ApplicationToolRegistry`'s snapshot of the child's own project, not through the agent file boundary. So `INVALID` describes no boundary for a child and nothing needs it to: a child's own file configuration remains unexpressible, which is the same limitation slice one recorded, now with the resolution paths a run actually takes pinned beside it. | `ContainedRuntimeReachTest.aRunningChildCannotDelegateToAnAncestorsDefinition`, `aRunningChildCannotDelegateToASiblingsDefinition`, `aRunningChildDelegatesWithinItsOwnSubtree` |
| The exhaustiveness inventory's single row, `JdbcIdentityRepository:84` `home(AuthorityScope)` | **Refused, unchanged, and now exercised by a running child.** A run needs the child's home and gets it from `IdentityRepository.containedHome(AuthorityScope.Contained)`, whose parameter type cannot be reached without first matching on containment; `home(AuthorityScope)` still refuses, so no `Home`-keyed path in the server acquires a child project by accident. The hop's own admission asserts the home it produced is the child's. | `ContainedRuntimeReachTest` (`assertEquals(fixture.childHome, admission.home())`), `ContainedStartDoorTest` |

#### Rows execution does not reach, and why

Each of these is a frame, an HTTP boundary, a DTO constructor or a row trigger on
a table a run does not write. The reason is the same shape every time and is
stated once rather than eleven times: **a contained principal holds no credential,
so it opens no session and reaches no frame; and a run's only durable writes are
its conversation, its allowance claims and the admission lineage V230 records.** A
run inserts no `projects` row, no `project_members` row and no `authority_grants`
row, because `contain` copies its container's grant rows rather than issuing any.

- `ProjectStore:1256` `allForSession` — needs a client session to compute its
  `client:` prefix from; a contained principal cannot sign in to have one.
- `ProjectStore:1317` `ordinary` — project create, rename, publish and administer
  are frames.
- `JdbcProjectMembers:84` `ordinary` and `:64` `members` — membership frames, and
  the read is fenced by the write refusal exactly as recorded.
- V216's `authority_membership_changed`, `authority_map_project`/
  `authority_scope_shape`, `authority_live_grants`/`authority_grant_insert` — row
  triggers on `projects`, `project_members` and `authority_grants`. A run fires
  none of them. The one durable write a hop *does* make is the admission lineage,
  governed by `operation_parent_valid`, which V230 replaces the no-escalation
  conjunct in; that trigger is the fence for the thing a run actually writes and is
  asserted un-mocked.
- `ServiceAccounts:143` `validateScopes` — token issuance is a frame, and a
  contained principal is not a service token.
- `SourceApplicationDeployments:68`, `ApplicationDeployment:122`,
  `ApplicationIdentity:105`, `HostedToolScopes:313` — deployment, identity and
  durable-tool-host surfaces, refused at the frame and again at the DTO boundary. A
  run constructs none of these requests; it has no door to.
- `ProjectMessageRouting:65` `identify` — fenced upstream by `require`, which the
  row above re-decides.
- ADR 0038's project-scoped Relay subscribers — consumption takes a lease gated on
  `mayWork`, which the `role` row above re-measures for the child's own principal.
- `UnionFrames:334` `union` — frames, fenced upstream by the two union rows.
- `ContainedProjects` — the prefix definition itself, a constant.

#### One inventory gap this re-audit adds rather than closes

**The mapping from a tool's arguments to the `Resource` references the authority
predicate then confines is unpinned.** Slice three pinned the predicate —
`PinnedOperationAuthority.requireReadTargets` compares every reference's projects
against the admission's own home, and a contained run cannot read or write a
conversation or memory outside it, including an audience-driven row that several
projects can see. What is *not* pinned is the step before it:
`RetainedToolAccess.resources(name, arguments)` is what turns a built-in tool's
JSON arguments into those references, and it maps **exactly two tools**:
`conversation_chat` to a `CONVERSATION` reference from the `conversation`
argument, and `memory_read` to `MEMORY` references from `ids`. Its second line is
`if (!name.equals("memory_read") && !name.equals("conversation_chat")) return
List.of();` — so **every other supported tool hands the predicate nothing**:
`search`, `fetch`, `memory_index`, `conversation_list`, `result_read`,
`result_list`, `run`, `todo_read`, `todo_write`, `agent_run`, `get_date` and the
`FileTools` set. That is eleven of thirteen plus the file tools, not the two this
row first named, and the breadth is the point: `requireReadTargets` can only
confine what it is handed, so for those tools it is handed an empty list and
confines nothing. A tool that named the wrong argument, or named none where it
reads something keyed, would not be caught by anything in this slice.

**What that does and does not mean, because the number invites an overreading.**
An empty reference list is not an open door by itself — a tool reaching
project-keyed state has its own authority path, and several of these are stateless
(`get_date`), or resolve through a repository that rechecks custody per read, or
are bounded to the run's own job. The gap is that *nothing structural* connects a
tool's arguments to the fence, so whether each of those is actually confined rests
on its own implementation rather than on this boundary. Measuring which of them
reach project-keyed state, and by what path, is the work this row defers. It belongs in this
inventory rather than in a loose list of worries because it sits squarely on the
spec's audience-driven-reads anti-goal: the predicate is the fence, and this is
the only thing that decides what the fence is asked about. Closing it needs a test
per supported tool asserting the references its arguments produce, which is work
in `agents` (the set is private to that package) and is not attempted here.

### What is refused rather than fenced, and why that is safe

Three surfaces are refused because they cannot be fenced in any slice so far, and
slice three changes the *reason* for two of them, because the "nothing executes a
child" clause each rested on is gone:

- **A schedule, in both directions.** A schedule has operational authority over
  the server's own scheduler (ADR 0020) and nothing mediates that for a child.
  Refusing is safe because a child holds no schedule to lose: the declaration is
  refused, so there is no behaviour a reader could expect and not get. Slice three
  adds that a running child reaches no schedule surface either — no frame, and no
  scheduling tool in the set it is offered.
- **Messaging, including a child's own project.** Admitting the self-addressed
  case would create an endpoint nobody mediates. **A child now does execute, so
  this is a running child being deprived of it rather than a hypothetical one**,
  and the deprivation is accepted for the same reason: there is no forwarding path
  in this capability and a child's reach is not its container's. The ordering of
  the check — before `require`'s source-equals-destination early return — is still
  the thing to preserve, and is still what makes the self-addressed case refuse.
  Nothing in the retained-work tool set reaches messaging in any case.
- **The agent file boundary.** `INVALID` denies every account rather than
  describing the child's real boundary. **That boundary would now be describable,
  because the child's own execution is configured** — and it is still not described,
  because a run resolves its definitions and tools from its own project's resource
  root and never through this boundary, so nothing a run does needs it. Describing
  it would be a widening with no consumer, which is the kind of change this record
  argues against making at the edge of a slice.

### Known limitations this inventory does not close

**A container reading its child's home is no longer a limitation.** V223 admits
it, at scope level and at document level, within the descendant's own boundary
and across the container's, and a grandparent reads through the chain. That half
of the slice-one limitation is lifted rather than softened.

**A child reading its own documents back is no longer a limitation.** V229
admits the child's own principal to the baseline `knowledge_revision_readable`
opens with, without creating any credential row and without granting it
general-read membership — the general arm is closed for a contained actor in the
same expression, and `general_member` is pinned false for it on the discovery
side. **The gate is open and the actor is not wired**: a contained principal
presented to these predicates reads its own documents and its own memories and
nothing else, which is asserted against inserted rows and constructed contexts —
and no production path presents one, so no running child reads anything today.
The authority decision slice two named as a prerequisite was made here (a
baseline, not a membership); the wiring it needs to have an effect is named as the
next one, in its own entry below.

**"And its own memories" was over-claimed, and it is true now rather than then.**
The sentence above was correct for documents after V229 and wrong for memories.
V229 widened five knowledge functions and deliberately touched nothing in the
archive: `MemoryStore`'s read pin, its write pin and the write authority behind it
each opened on `authority_accounts` — by `legacy_handle` on the read side, by `id`
on the write side — and a contained principal has no row there by design (V218),
so each statement returned no row and the store refused before any predicate
beside that join decided anything.

**That account join is what closed the memory door — not general-read membership,
and not scope.** `information_global_read_member` (V158:16) does answer false for a
contained principal, but its body is `account_active` plus an `admins`-to-
`authority_accounts` join and a `CROSS JOIN` onto the `GLOBAL_READ` role row: it
never tests that the *account* holds a grant of that role and it names no scope at
all, so it means "is this an ordinary active account" and its refusal here is the
same missing-rows fact as the join's. Scope was never the barrier either —
`information_project_readable`, the only conjunct that is about scope, already
answered TRUE for the child's own contained project through V223's contained arm
(V172:19 resolves it to `knowledge_scope_readable`). Recording global-read as the
fence would have put a false statement in this record.

V232 therefore adds one arm, `contained_memory_actor_allows`, beside each of the
three statements: a contained principal reaches the memories of its own home and
nothing else, bounded by `contained_own_home` to that one scope rather than by a
walk, so neither an ancestor's nor a sibling's nor its own descendant's. An
ancestor's read of a descendant's memories is the pre-existing upward walk and
worked before this slice. No credential, role or grant row is created or consulted
and `information_global_read_member` is still untouched. The `server_admin`
requirement on the write path is *conditional* — reached only when the home has no
project, which is the Global tier — and is left in force for every writer it
already applied to; the Global tier and `SET_AUDIENCE` stay refused for a contained
actor. `ContainedMemorySchemaTest` is the characterization: it records which
predicate refused first on each path and holds as regressions the five assertions
that passed before the widening. **The gate is open and the actor is still not
wired**: no production path presents a contained principal to the archive.

**"The actor is not wired" is no longer true, and every statement in this record
that rests on it is listed below.** Slice four wired it: `JobRuntime` builds
`InformationActor.contained` for a run whose own durable conversation record
carries `Origin.CONTAINED` on a `contained:` home, resolves the acting principal
through `ContainedPrincipals.principalOfProject`, and
`InformationAccess.forRun` resolves that actor through a contained branch
delegating to `InformationAccess.contained`. A production path therefore
presents a contained principal to the knowledge predicates and to `MemoryStore`,
and `contained_knowledge_actor` is true for an actor a running child presents.
Every statement in this record that rests on "no production path presents one"
describes slice three and not the current code. They are, besides the two
paragraphs above: the `JdbcInformationEventRecords` inventory row and the
paragraph introducing that inventory, the audience-defect paragraph's "a
constructed context, which is the only kind there is", and the
documents-not-processed gap. The slice-three *behaviour* each of them reasons
about is unchanged — the audience is still absent, the write path still declines
processing, the refusals still refuse — and it is only the reachability claim
that has moved: each of those paths now has the caller it was described as
waiting for. The write path's own defect was closed in slice three before it
acquired that caller, which is why wiring the actor did not turn it into an
outage.

**What a running child reaches is narrower than "the information tool", and the
boundary is a Java one rather than a SQL one.** The SQL arms (V223, V229, V231,
V232) admit the child to its own scope. One layer above them,
`InformationAccess.requireSelection`, `requireWork` and `requireAudience` ask
`ScopedPermissions` for a live project role, and a contained project carries no
`PROJECT` scope and so grants none — to the principal or to its container. Every
`InformationCatalogue` operation that calls one of those still refuses a
contained context, and `BoundMemoryQueries.requireScope` and
`BoundMemoryWrites` ask the same question with the same answer. So a running
child reaches the navigation read path, the catalogue repository's write path and
`MemoryStore` directly; it does not drive `InformationCatalogue` or the memory
query entry points. `SET_AUDIENCE` is refused by exactly that absence, which is
the outcome this record already requires. Whether those Java layers should admit
a contained principal is an open authority decision and was not made in slice
four; `ContainedInformationReachDatabaseTest` carries the characterization,
asserting the refusals rather than leaving them implied.

**The guarded set of catalogue mutators is closed over one criterion, and that
criterion is narrower than "every write a confined Application could reach". It
has been declared closed and been wrong three times, so here is what it covers.**

The criterion is: the mutators of `JdbcInformationCatalogueRepository` that take an
`InformationContext`. Each refuses a contained actor before anything writes, each
with an ordinary actor passing the same guard beside it, in one list that one test
drives — and the list rather than a count is the enumeration, because a count here
is a thing the next mutator falsifies. Three things the criterion excludes:

- **`replaceTags`** had no context in its signature, so the criterion could not
  reach it — the guard reads its actor off the context, so a mutator without one is
  outside the criterion by construction. It is a tags mutator, reached through
  `InformationCatalogue.replaceTags` → `requireWrite` → `lockResource`, which
  V229's WRITE arm admits for a contained actor's own resource. It now takes the
  context and is guarded, and it is in the list.
- **Five sibling repositories** — `JdbcInformationNavigationRepository`,
  `JdbcInformationProjectionRepository`, `JdbcAcquisitionRepository`,
  `JdbcInformationContentRepository` and `JdbcInformationInterchangeRepository` —
  each take an `InformationContext` and write, and **none carries the guard**.
  Nothing presents a contained actor to them today, by the no-caller fact below,
  and **whether their service layer would refuse one is unverified**. Guarding five
  more repositories at the edge of a slice is new scope with its own risk and is
  deliberately not done; this entry is the open item instead, and it is the honest
  size of the surface rather than the size of the part that was audited.
- **A mutator added to the catalogue class without a guard** is caught only by the
  test's list gaining a row for it, which is the same enumeration rather than an
  independent check. The structural alternative — the check at `lockResource` and
  `requireDestination` with an explicit per-operation allowance — is costed in that
  class's own Javadoc and not built.

**The pattern across those three failures is the one worth keeping.** Each time the
set was declared closed, the thing outside it was outside the *criterion* rather
than overlooked within it: first two mutators the list and its test both omitted,
then a mutator whose signature the criterion could not see, then five classes the
criterion was never about. A closed set is only as closed as its membership rule,
and stating the rule is what lets the next reader find the next gap.

**The knowledge gates are open and nothing in production is the actor they are
keyed on. This is the limitation slice three actually leaves, and it reframes the
two rows and the position below.**

`InformationAccess.contained(principal, container, project)` is the only place the
two-axis context is built, and **it has no production caller**: every reference to
it is in two test classes. At runtime the information actor for a contained run is
`callerHandle` — the *container's* executor — because `contain` keeps the parent's
executor and account unchanged, and `SourceContainedApplications.run` issues the
child's principal and deliberately discards it ("Issued, and deliberately not
passed as the caller"). So `contained_knowledge_actor(actor)` is **false for every
actor a production path can present**, and with it: V229's read baseline, both of
V229's write arms, the `attribution` axis, and every contained-actor guard in the
catalogue repository are reachable only from a hand-built context. **A contained run today reads no
knowledge and writes no knowledge or memory** — not because a fence refuses it,
but because nothing makes the child the information actor.

It fails closed, which is why it is a limitation and not a hole: the unreached
state is "a child has no knowledge capability", which is strictly narrower than
what was authorized. But the honest statement of what slice three proved is
**gates, not behaviour**: V229's arms are proved at the SQL layer against inserted
rows and at the service layer against constructed contexts, and the Java write path
is proved to work when handed such a context. Whether a running child's reads and
writes land where V229 admits them is unproved, because no run produces that
actor.

**Wiring the contained principal as the information actor is the next slice's
named prerequisite**, in exactly the form V223 used to name V229's gate. It is an
authority decision rather than a wiring task, which is why it is not done here:
the account axis is the container's by the user's own ruling, so making the child
the *information* actor means deciding that one subsystem reads a different
identity from the one the run executes as, and threading it from `Runs.Ask` through
the job runtime to every `InformationContext` a tool builds. The pieces that
decision needs are all present and recorded — the two axes, `accountedTo`, the
guards, and the Relay-audience tolerance — which is the useful half of what this
slice did here.

**`information_navigation_reference_visible` carries the same unextended
`RESTRICTED` fence and is deliberately left alone.** Slice two's outward fence
covers the two bodies that decide general-read *publication*; the third body with
the same shape is `information_navigation_reference_visible` (`V172:91`), whose
`s.kind<>'RESTRICTED'` was likewise never extended to `CONTAINED`. It is a
different capability — it discloses that a *navigation reference* to a resource
exists, not the resource's content — and it is unreachable for the same reason
plus one more: the navigation default for `referenceHidden` is `true` (`V147:73`),
and `NOT coalesce(referenceHidden,true)` is therefore the refusing value, so a
contained home would additionally need an explicit navigation policy written
against it. It was **not** given the conjunct because, unlike the publication
bodies, it is the *only* admission arm at one of its two call sites:
`JdbcInformationNavigationRepository.referenceVisible` (`:617`) evaluates
`information_navigation_reference_visible(…) AND <boundary>` with nothing beside
it, so excluding `CONTAINED` there would refuse an **ancestor's own** reference
visibility into its descendant — a narrowing of the capability slice two opens,
which this slice can neither justify nor test. (At the other call site,
`InformationSql`'s `visible` filter, it is a disjunct beside
`own_readable AND reference_readable`, which does route through the widened
`knowledge_scope_readable`, so the ancestor would keep its visibility there.) The
honest statement is that one body of this class remains fenced by a default rather
than by a structure.

**Slice three is the slice that was supposed to have the consumer, and it does not
close this.** The reason the paragraph above gave for deferring — "it is
unreachable for the same reason plus one more", the first reason being that nothing
executes a child — **has not in fact lost its first half, and an earlier draft of
this paragraph said it had.** Slice three opens the write path but wires no actor
to it, so no contained-home resource exists in production for a navigation
reference to be written against, and the first reason still holds — on a narrower
basis than before, because it now rests on an absent caller rather than on absent
execution, and wiring that caller is named below as the next slice's prerequisite.
The second reason is unchanged: the navigation
default for `referenceHidden` is `true` (`V147:73`) and `NOT
coalesce(referenceHidden,true)` is the refusing value, so a contained home would
additionally need an explicit navigation policy written against it — and nothing
writes one, because V229's write arm admits `WRITE` only and not `SET_AUDIENCE`,
and a contained actor holds no role on the ordinary path a navigation policy is
set through. So it is still unreachable, by **two** facts and not one: an absent
information actor and a default that refuses. The position is weaker than slice
two's only in that the first fact is now an absent caller rather than absent
execution — a caller the next slice is expected to wire — so this paragraph will
need re-deciding then and not before. Closing it still needs the one-way
direction worked out for reference visibility in its own right, because the body is
the *only* admission arm at `JdbcInformationNavigationRepository.referenceVisible`
and excluding `CONTAINED` there would refuse an ancestor's own reference visibility
into its descendant.

**"Nothing executes a contained child" is retired.** It was the largest false
claim this record could carry forward, and it is false now in every part: a
contained child runs, opens a conversation in its own home carrying
`Origin.CONTAINED`, speaks a turn, spends **its own** allowance under its
container's ceiling on its container's account with itself recorded as author, and
is stoppable three ways — `application.child.cancel`, a destruction that stops the
subtree before collecting any source, and a mid-run revocation of the container's
grant.

**What the inventories rest on instead.** Not "no child runs", but three narrower
facts, each of which a future slice could falsify and should therefore check:

1. **A contained principal holds no credential (V218), so it reaches no frame.**
   This is what closes the eleven rows above whose surface is a frame, an HTTP
   boundary or a DTO a request constructs. It is a *property of the principal*, so
   it fails the moment anything issues a contained principal a handle — which is
   also why the absence of a door to `application.child.run` for a running child is
   recorded below as both a property and a limitation.
2. **A run resolves everything it uses from the child's own home.** Definitions
   through `DefinitionResolver`/`Callers.find` on that home, tools through
   `ApplicationToolRegistry`'s snapshot of that project, resource references
   through `requireReadTargets` against the admission's own home. These are lookups
   failing rather than permissions denying, which is the shape the spec asks for —
   and the one measured exception is the inherited global definition tier, recorded
   below.
3. **A run's only durable writes are its conversation, its allowance claims and
   the admission lineage.** It inserts no project, membership or grant row, which
   is what keeps V216's row triggers out of a run's path.

The rows are re-decided individually in the re-audit above; this is the
replacement for the one sentence that used to carry all of them.

**`SourceContainedApplications` answers whether an arbitrary child UUID exists
before it checks containment.** Both `trajectory` (lookup at `:500`) and
`resources` (`:539`) open with `records.find(child).orElseThrow(new
CallerFault("No such contained Application"))` and only then prove the caller's
authority through `parent(account, conversation)` and walk the chain in
`requireAncestor`, so a caller that contains nothing can distinguish a child UUID
that exists on the installation from one that does not — a one-bit existence
oracle on a scope it may not name. It is inherited verbatim from `trajectory`,
which slice one shipped, and it is recorded as affecting both methods. Closing it
means proving the caller's authority before the lookup, which changes which fault
a nonexistent child raises, so it is a contract change rather than a reordering,
and it stays deferred.

The whole-branch review found that `trajectory`'s Javadoc **contradicted** this
entry: it claimed a refused read learns "not even that its conversations exist",
which is precisely the bit the lookup beside it leaks. **The code was not
changed** — closing the oracle is the contract change above — and the Javadoc
was corrected instead: it now claims only what is true, that a refused read
discloses no *conversation* of the child's, states plainly that it does not hide
the child itself, and points here for the deferred fix. `resources`' Javadoc
never made the claim and is unchanged on that point. The correction is the
reason this limitation is no longer also a documentation defect; the oracle
itself is unchanged and still live.

**Planner cost for the widened reads is argued, not measured.** The widened
listing path and the new `boundary_descendants` CTE are hoisted so that
containment costs one walk per contained scope per statement rather than one per
row, which is the shape V184's own six-second measurement forbids, and the
restated `kind` tests keep the call off ordinary scope rows. No `EXPLAIN ANALYZE`
on a realistic corpus backs any of it, so the claim is a reading of the plan's
shape and not a number.

**A running child resolves the server's global definition tier, and a global
callee's `tools:` list is compared against nothing.** Measured against a
running child rather than argued: a contained child's agent declaring `calls:
[global_agent]` **keeps that edge** — `DefinitionResolver.refusalsFor` answers
`{}` for it, because `withholdUnreachableCallees` checks a declared callee
against `defined`, which `readProject` builds from the project's own names
*plus the inherited tier's*. The whole global tier is present in the contained
project's registry, including agents the child never named, and
`Callers.find(childHome, …)` — the same call `AgentRunTool` makes before
starting a delegate — resolves one from inside the contained home. The callee
arrives **carrying its own `tools:` list**, and no subset test compares that
list against the child's package or its container's, because
`ContainedDeclarations` compares only what the two *packages* declare and a
global definition is on neither side of that comparison. The one partial fence
is `AgentRegistry.escalatingGrant`, which refuses a callee whose **workspace
scopes** exceed the caller's; it covers workspace scopes only and says nothing
about `tools:`.

This is **not an escalation above the container.** `DefinitionResolver` builds
every project's registry as `inherited.replacing(merged)`, so an ordinary
project inherits the same tier; the tier is operator-installed at boot; and
the container can delegate to the same global agent and obtain the same tools.
The parent does hold what the child reaches, which is why this is documented
rather than blocking.

It is **still a limitation, and the design's claim is as written literally
untrue for the global tier.** The model says a child "cannot name, read,
address or reach anything outside its own subtree, including the parent", and
a global agent is outside the subtree. The residual risk is not pedantic: it
falls on the design's third use case, running a third-party Application under
an owner's grants *without granting it any of them*. There, a child reaching
the operator's global tier — and that tier's unchecked `tools:` — is a lend
the owner may not have intended, even though the owner could reach it
themselves.

Two candidate fixes, recorded so the follow-up does not re-derive them. The
narrower one is to subset-check a global callee's `tools:` against the
container's package at the hop; `escalatingGrant` is the precedent for the
shape, and it leaves the tier reachable. The stronger one is to narrow the
inherited tier for a contained project, which is a core change to the path
every project's registry is built through, and it may leave a child unable to
do useful work if built-ins live in the boot tier — which would have to be
discovered before it could be chosen. The runtime-reach test **deliberately
does not assert the current behaviour**: a passing test over this would bake
in an answer nobody has given, so the measurement is recorded here and in that
class's Javadoc instead.

**A running child has no door of its own to `application.child.run`, so every
containment hop needs an authenticated driver.** The operation exists only as
a frame behind `Asking.requireHandle`, and a contained principal is a bare
UUID with no credential material (V218), so a child cannot start a grandchild
on its own initiative. A hop is therefore driven by an authenticated ancestor
naming the **child's** contained conversation, whose retained admission is the
scope the container-only gate reads; transitive containment needs such a
driver per hop.

This is recorded as both a confinement property and a capability limitation,
and it is **deliberately left closed**. The spec's anti-goal fears "unbounded
recursion or width … reachable from ordinary agent work", and requiring an
authenticated driver per hop is exactly that brake. Opening a frame door to a
credential-less principal would need the authority decision V218 forbids, so
the limitation and the property are the same fact and neither is closed here.

**A running child's own knowledge write had no Relay audience and the production
publisher refused it. Fixed narrowly; the deep fix is still ADR 0039's question.**

V229 opened the knowledge write gates and `ContainedExecutionWriteDatabaseTest`
proved a child's write works through the real Java path — **against
`RecordedInformationEvents`, a fake that appends announcements to a list.** Under
production wiring the same write failed, and the chain was four plain calls and one
database fact: `JdbcInformationCatalogueRepository.admit` announces the intake, and
for a contained home announces the processing decline beside it; both go through
`InformationEvents.publish`; `RelayInformationEvents.publish` opens with
`records.resolve(revision)`; and `resolve` raises
`InformationEventRecords.Unaddressable` when the home scope has no audience, which
`InformationEvents`' own contract documents as **deliberately caught nowhere** —
"a refused publication is a visible defect, while one delivered to the wrong
audience, or silently dropped, is not". A contained scope has no audience by this
record's own decision, so every knowledge write a child made raised an
`IllegalStateException` and rolled back. **Said exactly: every such write made from
a constructed context, which is the only kind there is** — no production path
presents a contained actor to the information layer, so this was never a live
outage. It was a defect in the capability's own write path, found and closed before
that path acquired a caller, and it is recorded rather than deleted because the
lesson travels and the next slice wires the caller.

**So the claim that a contained principal writes its own home must be read for what
it is: a gate, not a behaviour.** What is real and proved is the gate — V229's
write arms admit a contained actor to its own home and nothing else, and handed
such a context the Java path writes the provenance, the home and the skipped
stages exactly as described. What is not real is any production write, because no
production path makes a contained principal the information actor. The record
asserted this as an established execution fact in several places; each of those is
now scoped to the constructed context that proves it, and the no-caller limitation
states the absence in full.

**That is the second time in this capability that a fake carried a claim, and the
lesson is worth more than either fix.** The first was a mocked `OperationRecords`
hiding that a containment hop had never persisted, recorded one task earlier in
this same record, with the lesson written down: *a mocked repository can prove a
rule and cannot prove that the rule is reachable.* The lesson was in the record and
the same class of defect was shipped again, by a different author, on a different
collaborator, in the next task. What that says is that writing the lesson down does
not transmit it; what transmits it is composing the system under test the way
production composes it, which is what the regression test now does. A fake that
satisfies an interface answers the question it was told to answer.

**The narrow fix, and why it is principled rather than expedient.**
`JdbcInformationCatalogueRepository.announceIntake` tolerates a missing audience
**only for a home the processing pipeline has already declined** — which in this
slice means a contained home and nothing else. The reason it is right rather than
convenient: for such a home there is no processing to announce and no audience to
notify. Every stage is already written `skipped`, so the announcement is a notice to
nobody about work that will not happen, and the operator-legible record of the
decline is the `skipped` stage rows, which the fix does not touch. What is lost is
the Relay half, and for a home with no audience there was never a Relay half to
lose. The honest cost is that a contained home's intake does not appear in the
event history at all, which a test asserts rather than leaves to be discovered.

**It is keyed on the home, not on the exception, and that is the part worth
guarding.** A blanket `catch` here would sit on the path every knowledge write in
this server takes, and would convert a real defect — an *ordinary* home that lost
its audience through misconfiguration, such as an account knowledge home whose
owner has no personal project, a shape
`InformationEventAudienceMappingTest` already pins — into silence. That trade is
worse than the bug it hides, because it is invisible. The flag is therefore the
caller's own `declinedHome`, one predicate (`ContainedProjects.name`) decided once
per admission, and not a fifth way to recognise a contained scope.

**The evidence is two links, stated as two.** The schema will not let a fixture
manufacture an ordinary unaddressable home — `information_knowledge_identity`
refuses a home transfer outright, and every project home is addressable by
construction — so the narrowing is shown as: *behaviourally*, that on a real home
with no audience the refusal surfaces when the flag is false and is tolerated when
it is true, nothing differing between the two calls but the flag; and
*structurally*, that `declinedHome` is false for every selection whose project is
not a contained one, so an ordinary home can only ever supply `false`. Making the
tolerance unconditional fails the first. The positive controls are an ordinary
write still announcing its intake, observed as the Relay history row it writes, and
a contained write completing with its home, provenance and skipped stages asserted
rather than the absence of a throw.

**The deep fix is not taken and is not this slice's to take.** Giving a contained
scope a real Relay audience means deciding whether Relay keys topics on
`authority_scopes.id` — ADR 0039's deferred question, and the thing the
addressability inventory's Relay row refuses, because it would hand a child project
an addressable topic nobody named. Nothing here answers it, and a later reader must
not "fix" `announceIntake` by reaching for that change.

**`contained_applications.live` is vestigial, and two refusals that read as
liveness checks are unreachable.** V219 declares the column `NOT NULL DEFAULT
TRUE` and nothing in this server ever writes it `false`. A destroyed child's
refusal therefore works by **row cascade** — the project row goes, `projects` →
`authority_scopes` (V155) → `contained_applications` (V219) — and not by the flag:
`records.find` answers empty, and `run`, `trajectory`, `resources` and `cancel`
each open by resolving that record. So `run`'s `!retained.live()` guard is
unreachable in production, as is `resources`'. Both are kept as guards and both now
say so in their own comments. The one test that exercises the `live=false` state
constructs it by hand and says in its own Javadoc that production never produces
it. Nothing is changed here: a guard that cannot fire is cheap, and removing it
would make the column's only remaining reader the `WHERE c.live` predicates in the
listing and subtree counts, which would then be the thing to reason about instead.

**A cancelled contained run cannot be resumed.** `Turn.originIsResumable` admits
`Origin.TURN` and nothing else, so a `contained` conversation is not resumable from
`job.status`, from a grant continuation or from anywhere else. That follows from the
origin decision rather than from a separate rule, and it is the right default — a
resume would need a door for a credential-less principal, which V218 forbids — but
it means a container's only way to continue work it stopped is to start a new run.
Stated here because "cancellable" and "resumable" are easy to read as a pair.

**Two lookup orderings exist on purpose, and must not be harmonised in the wrong
direction.** `trajectory` and `resources` resolve the named child *before* proving
the caller's authority, which is the existence oracle below. `run` and `cancel`
prove authority *first* and resolve the child after, so a refusal from either
discloses nothing about whether the named UUID is a contained Application on this
installation. Both orders are deliberate: the reads inherited theirs from slice
one and changing it is a contract change, while the two new operations had no
inherited contract to preserve and were written the better way. The inconsistency
is therefore a known cost of deferring the oracle a third time, not a drift — and
the direction to harmonise in, when it is decided, is the writes'. A reader who
"fixes" the inconsistency by making `run` and `cancel` look up first has made four
operations leak instead of two.

**The existence oracle in `trajectory` and `resources` is unchanged, still live,
and still a contract change.** Both open with
`records.find(child).orElseThrow(new CallerFault("No such contained Application"))`
before proving the caller's authority, so a caller that contains nothing can still
distinguish a child UUID that exists on this installation from one that does not.
Slice three had the opportunity and did not take it; the two orderings it leaves
behind are the entry above. Closing it in the reads still changes which fault a
nonexistent child raises, so it is still a contract change rather than a
reordering. It is also the thing standing in the way of the first candidate fix to
the retention question below, which is a reason to decide it rather than inherit it
a third time.

**Planner cost for the widened reads is still argued and not measured.** V229 adds
no `EXPLAIN ANALYZE` either. What it adds is one more function call per actor per
statement — `contained_knowledge_actor`, a regex test guarding a single-row
`EXISTS` — on paths V223 already hoisted, plus the same call inside the pinned
`general_member` term. The claim remains a reading of the plan's shape, now with one
more term in it, and the honest position is unchanged: no number backs any of it.

**The SDK limitations are unchanged in kind and larger in degree, and one of them
was described wrongly for two slices.** The Java SDK is curated and exposes
**none** of these operations — eight now rather than six. `bytes` and `child`
remain unenforced in the Go, Python and .NET generated schemas, and slice three
adds `task` to that list while finding that `maxTurns` and `agent` are **not** in
it: both are bounded in all four SDKs, and the SDK section below has the keywords
and the tests. And the .NET round-trip for `run` and `cancel` was **written and
never compiled**, for the third slice running, because the authoring machine has no
`dotnet`.

**The reason those three are unenforced is not that the generator cannot express
them.** Slices one and two called it "a semantic constraint the schema generator
cannot express" and said the remedy was "a core contract change with its own
decision". That is false, and it matters because a reader would defer the fix on
the strength of it: `scripts/native-sdk-constraints.mjs` emits a UUID `pattern` for
`requestId` globally and for `expectedRevision`, `revision`, `resource`, `source`
and half a dozen other fields per operation, and emits `integer(...)` bounds
wherever a field name asks for one. The three unenforced fields are simply
**undeclared in that file** — `child` is in none of its UUID lists, `bytes` is on a
reply shape nobody has enriched (though reply shapes *are* enriched elsewhere in
it), and `task` is excluded from the generic non-blank rule by name. The remedy is
a declaration per field in one generator input, which is a small change that
someone must still decide to make, review across four SDKs and regenerate — not a
contract redesign. Recording it as impossible made it look larger than it is, which
is how a one-line gap survives three slices.

Whether Relay keys topics on `authority_scopes.id`, which is what would give a
contained scope a real audience, is ADR 0039's deferred question.

## The structural walk sentinel is not the policy depth bound

`contained_scope_ancestors` stops at 64 links. That number is a **structural
sentinel and not the configured containment depth**, and the two must not be read
as one.

The policy bound is `ContainmentBounds.maxDepth()`, supplied by server
configuration, validated at startup — an absent bound fails rather than meaning
unlimited — and enforced in Java when a child is created and when `require`
walks the chain. It is deliberately **not reachable from SQL**: duplicating it
there as a literal would let an operator change the configuration and leave a
predicate silently enforcing the old value, which is worse than not enforcing it
in SQL at all.

The sentinel exists for a different question: what a recursive walk does with
data that should be impossible. It is the same device as
`knowledge_dependency_revisions`'s `LIMIT 1025` — the walk fails closed instead
of running unbounded. It is **not** a cycle guard either, because V217 freezes
`container` after insert and an insert can only name an existing container, so
the relation is acyclic by construction and a cycle is one of the impossible
shapes the sentinel catches rather than the reason it is there. Both sides of the
boundary are asserted — distance 64 admitted, distance 65 refused — so the
sentinel cannot drift unnoticed, and the deepest scope still reads its own home
through the self arm, which never walks the chain at all. A chain that cannot be
completed stops at the break, and only an existing scope row can be the ancestry
a read resolves through, so a dangling container refuses rather than admits.

A configuration whose `maxDepth` exceeds 64 would therefore be enforced by Java
and silently truncated in SQL — in the refusing direction, so it fails closed,
but it is a disagreement an operator could create and nothing reports it. That is
recorded as a consequence of keeping the policy bound out of SQL, not as a
property anyone should rely on.

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
| FileStore destination | the creation request's `destination` | **Refused** while non-null. The server allocates the child's source area under `plowshare.containment.root`; a container typically holds no FileStore `MANAGER` over its own retained source, so proxying could not yield a legal destination. The field stays in the operation's shape for compatibility with `application.deploy`, so every SDK sends the key, but a *named* destination is refused at the frame and again in the service rather than validated into a value nothing reads — a field accepted and dropped reads to a caller as supported. |
| FileStore writable area | the creation request's `writableAreas` | **Refused** while non-empty. A child's writable area is allocated inside its own containment, so any requested area names a store outside its container's fence. |
| Service account attribution and spending pool | the child's own allowance | **Allocated, never shared.** The child allocates its own allowance under a ceiling its container sets at creation and may later raise or reduce — to zero — within its own remaining allowance. The creation bound and the reconfiguration bound are one rule applied at two call sites against the same pool, because a bound enforced only at creation would be decorative: create low, raise high. An absent ceiling is not representable, because `AllowanceSnapshot` documents absent as unlimited. **The pool is the child's and the accounting account is its container's** — a hop keeps the parent's executor and account unchanged, so ADR 0025's immutable attribution and its rule that delegation cannot change the accounting account are untouched for the reason that nothing invents a second accounting subject, not because spend is attributed to the child. No parent/child shared counter is introduced, and the child is recorded as the work's author instead. |
| Manifest `name` | `plowshare.json` `name` | **Rewritten** to the server-allocated opaque project name. `name` is identity and not authority, and the server owns the name a child cannot know in advance; every surface that *is* authority is in the rows above and is refused or reduced, never rewritten. |
| Nesting itself | `plowshare.json` `nesting: true` | **Required of the container.** Only a literal `true` counts, so no existing Application acquires the capability by upgrade, and a missing, null, string or object value is "not declared". |

**The slice-three re-audit of this inventory.** Every row here is decided when a
child's source is *read*, at creation, so execution does not re-ask any of them:
`ContainedDeclarations.read` runs once per create and a run reads no declaration.
What execution adds is whether the **reduced** surface is the one a run actually
resolves, which is a different question and is answered for the three rows that
have a runtime counterpart. *Tool provider* and *tool binding*: a contained run's
tool call resolves the snapshot of its own project's `server/tools.json` and
nothing else, so the container's bindings are absent from the set rather than
merely ungranted
(`ContainedRuntimeReachTest.aRunningChildsToolCallResolvesOnlyItsOwnBindings`).
*Schedule*: refused as a declaration, and a run reaches no schedule frame and is
offered no scheduling tool
(`aRunningChildCannotDefineASchedule`). *Service account attribution and spending
pool*: the ceiling stops being a stored number and becomes spend — a run's
conversation carries the child's **own** budget, which V230 enforces by putting
`contained` on the owned side of
`conversations_an_allowance_is_owned_or_shared`, and a budget-less contained row
is refused by the database rather than fixed up by the service
(`ContainedConversationDoorDatabaseTest`); a zero ceiling refuses a run outright
rather than starting it to starve. The remaining rows — Relay topic port and
audience, swarm type, FileStore destination and writable areas, the rewritten
manifest `name`, and `nesting` itself — have no runtime surface a run can name,
because a run's payload names no project, no destination and no port, and its home
comes from the child's own stored row.

Tool *bindings* (`server/tools.json` `bindings`) and the Relay worker account
(`server/relay-workers.json`) were recorded here as accepted-and-uncompared, which
was the honest state and a gap. **That gap is closed**, in the addressability
inventory above rather than in this table, because a binding's reach is an
addressability question and not only a declaration one: both are now subset tests
against the container's own declarations, refusing anything the container does not
itself hold and admitting an identical declaration unchanged.

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

**Correction: this section's liveness claim was resting on mocked persistence,
and that was a defect that shipped.** Both gates above were described as live
across two slices while `contain` had never recorded a hop in any database.
`ContainmentHopTest`, the suite that proves the hop's rules, mocks
`OperationRecords`, so `retainChild` was asserted and never executed — and the
first time a hop reached real PostgreSQL, `operation_parent_valid` (V162) refused
it outright with `child authority exceeds retained parent`. Gate two's Java
replacement was therefore correct and unreachable: no contained admission could
exist durably for it to admit. This is stated as a defect rather than as a
nuance because the two slices' claims about a *live* contained admission were,
in the strict sense, untested in the one place that decides them. The lesson is
narrow and worth keeping: a mocked repository can prove a rule and cannot prove
that the rule is reachable. The regression test for it is the un-mocked path,
`ContainedOperationLineageSchemaTest.aContainmentHopRecordsItsLineage`, and not
a tighter assertion against the mock.

**V230 is the SQL counterpart of gate two's replacement, not a new relaxation.**
`operation_parent_valid` requires a child admission to carry `c.scope=p.scope AND
c.project_name=p.project_name`, which is the identical demand gate two already
replaced in Java — and a containment hop changes both by design. V230 replaces
that conjunct with the same equality *or* the containment edge itself, keyed on
the child's stored `authority_scopes` row: the row exists, it is `CONTAINED`, its
`container` is the parent admission's own scope, and it maps to the project the
child admission retained. Those are the four facts `containedHome` binds, bound
again here, so the trigger admits exactly what the Java gate already admitted and
nothing more. The non-contained path keeps the original equality untouched, which
is asserted byte-for-byte. Everything else the trigger compares is unchanged —
executor, executor handle, grant source, the support-subset check, the recursion
bound and the cycle check — so a hop still cannot change who is executing or
whose grants are spent, and ADR 0025's immutable attribution is untouched. The
conjunct is the substrate's no-escalation invariant and the migration says so: a
grandparent, a sibling subtree and an unrelated scope are each refused at the
trigger as well as in Java, because a Java check is a check and the trigger is
the fence.

**A contained run's conversation carries its own origin, `contained`, and the
reason is the allowance.** `operation_conversation_valid` (V162) requires a
conversation row's `agent` to equal its bound envelope's, while
`conversations_a_person_s_conversation_names_no_agent` (V17) forbids an agent on
an `origin='turn'` row — so a contained run's conversation, whose envelope always
names an agent, cannot be a turn. None of the eight existing kinds describes
harness-started work inside a confined child: `submission` is V17's caller's-own
parentless run and would make a contained run indistinguishable from an external
one in every listing, `memory` is digest construction, `event` is a trigger
answering a tick, and `orchestration` brings lifecycle special-cases a contained
run has no use for.

The kind that decided it is `delegation`, and it fails on **design** rather than
on taste: a delegation shares its parent's allowance by reference and its row
therefore holds no budget at all, so a contained conversation carried on it would
spend its **container's** pool. That contradicts this record's own requirement
that a child allocates its own allowance under a ceiling its parent sets — the
**pool** is the child's own and is never shared, which is a separate question from
whose **account** it is spent on, and it is the pool the kind exists to protect —
so reusing the nearest existing kind would have reintroduced the exact defect a
separate kind exists to prevent. V230
therefore adds `contained` to the **owned** side of
`conversations_an_allowance_is_owned_or_shared`, which is the line that makes
"allocated, never shared" true of a running child, and a test asserts that a
budget-less contained row is refused rather than trusting the service to supply
one. The agent pairing needs no new constraint: V17's rule is an equality, so it
already requires a `contained` row to name its agent, and both directions are
asserted instead of restated.

**A contained conversation has no ejection age, and the reason originally given
for that was wrong. The behaviour is unchanged and the justification is void.**

Adding the kind made `RetentionPolicy`'s sealed switch over `Origin` fail to
compile, which is the exhaustiveness this record relies on elsewhere working here:
the retention question had to be answered rather than defaulted. It was answered
with no age, and the argument was that a contained child's retained conversation
lineage is the evidence its container reads through
`application.child.trajectory`, that this record makes the lineage survive the
child's destruction deliberately, and that `trajectory` therefore **answers for a
destroyed child where `resources` refuses one**. The code was also commented that
way, and a reader was told not to harmonise the two.

**`trajectory` does not answer for a destroyed child, and never did.** It resolves
the child's record first — `records.find(child).orElseThrow(new CallerFault("No
such contained Application"))` — and destruction deletes the child's project row,
which cascade-deletes that record (`projects` → `authority_scopes` (V155) →
`contained_applications`, V219). `resources` opens with the identical lookup. So
after a real destruction the two refuse **identically**, with the same sentence,
and the message difference the old paragraph described belongs to a `live=false`
branch neither of them reaches. `ContainedResourceReadTest.aRealDestructionMakesBothReadsRefuseIdentically`
asserts both refusals and that the two messages are equal, with both reads
answering for the live child first as the control;
`ContainedApplicationSchemaTest.deletingTheProjectRowCascadesTheRecordAway`
asserts the cascade itself. The "do not harmonise the two" instruction is
withdrawn: there is nothing to harmonise.

**Four comments carried the claim and all four are named rather than counted**,
because the first attempt at this correction reported them all fixed while the
most important one was untouched: `SourceContainedApplications.resources` (the
"do not harmonise the two" block itself), `SourceContainedApplications.run` (the
liveness guard beside it), `ContainedApplications.run`'s Javadoc (which cited the
pair as the precedent for its own container-only asymmetry), and
`RetentionPolicy.ejectAfter`'s `CONTAINED` arm — **which is the retention
justification, so it was the one carrier that mattered most and the one a sweep
for the phrase in `applications` could not see.** A claim lives wherever it was
convenient to repeat it; naming the sites is what makes the next reader able to
check this sentence rather than trust it.

**Voiding the justification explicitly, because the retention it justified is
still in force.** The envelopes do survive the destruction — that part was true —
but **nothing can read them**, because the row that resolves the child is gone.
The retention therefore currently keeps conversation rows no operation can reach,
for ever, which is the opposite of what was argued for it. No behaviour is changed
here: changing a retention policy on the strength of a correction, at the end of a
slice, is how a durable-data decision gets made by accident. The two candidate
follow-ups are named so the next slice does not re-derive them:

- **Make `trajectory` answer after a destruction**, which would make the retention
  mean what it was supposed to mean. It requires reading the destroyed child's
  lineage from something that survives — the collection record (V220-V222) retains
  the container and the scope precisely so a destroyed child stays answerable — and
  it **reopens the deferred existence-oracle question**, because the lookup-before-
  authority ordering is exactly what would have to change: a `trajectory` that
  answers for a child whose record is gone must decide what it says about a child
  that never existed, which is the contract change the oracle entry defers.
- **Drop the retention**, giving `contained` a real ejection age, on the ground
  that evidence nothing reads is not evidence. The age to copy is
  **`SUBMISSION`'s**, not `DELEGATION`'s: `RetentionPolicy.ejectAfter` answers
  `null` for `TURN`, `DELEGATION`, `ORCHESTRATION`, `BOARD` and `CONTAINED` alike,
  so "an ejection age like `DELEGATION`'s" — which this paragraph said in its first
  draft — describes no change at all. `SUBMISSION` and `EVENT` are the only origins
  that carry one. This is the cheaper change and the easier one to reverse, and it
  forecloses the first.

Either is a decision about durable data and belongs to a slice that is asked for
it.

**The contract cost is one hand-written line and no generated one.** The generated
SDKs type a conversation's `origin` as a plain string rather than a union of
literals, so a new value changes no generated type — but the hooks contract,
`plowshare-hooks/index.ts`, does carry a literal union, and
`HookContractMirrorTest.the_origins_are_the_servers` fails until it is updated.
That test is the mechanism working as designed, and the earlier claim that the
kind had no contract reach at all was too strong by exactly that one declaration.
Both constraints the kind needs an arm on have been restated by later migrations
already (V74 and V169), so restating them is this table's ordinary maintenance.
The retention the kind carries is decided in its own paragraph above.

**A confined Application's documents are not processed, and that is a recorded
capability gap rather than an oversight.** V229's write arms admit a contained
actor's knowledge write into its own home — against a constructed context, since
no production path presents such an actor — and the processing pipeline —
extraction, embedding, summarising, tagging — declines those documents at the
enqueue boundary: every stage is written `skipped`, the intake announces the
decline with its reason, and the claim scan's work list (a `pending` or
expired-`running` step) therefore never contains them. Nothing is lost silently
and nothing is retried.

**What an operator would actually see, which is narrower than "the steps or the
event stream".** The `skipped` stage rows are the whole of it. The decline's
announcement does **not** reach the event stream, because a contained home has no
Relay audience and the intake announcement tolerates that refusal rather than
failing the write — so for a contained home the announcement is attempted and
lands nowhere. The two statements are reconciled this way round, with the stage
rows carrying the observability, and the narrow fix's own entry says the same.

The deferred question is **ownership, and it is three-way**. Processing a
document needs a conversation and an allowance, and for a contained home neither
has an owner. The pipeline's own log belongs to neither the child nor its
container: it is the pipeline's, started on a lease whose owner is a third party
to the containment relation, and it would spend a third budget inside a confined
home — not the child's pool, which its container bounded for the child's own
work, and not the container's, which the child may not spend. No part of this
capability has designed that shape, and inventing one at the edge of a slice is
how an authority surface acquires an owner nobody chose. Nothing in the design's
goals is lost by refusing: a container reads its child's trajectory, resources
and deliverables, which are the child's own output, and never an enriched
derivative of it. Refusing a surface is an acceptable first slice; honouring one
nobody authorized is not.

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
`contained_applications` and `contained_principals` (V218, V219) — so after a
destruction there was nothing left to read, and the staged-source removal that
follows the transaction was silent. The claim now stands because two things were
added rather than because it was softened.

**A collection record, V220's `contained_collections`, with V221's `scope` and
V222's `root`.** One row per collected source area, written inside the destroying
transaction before the rows go; the two added columns are what make a retry's
answer identical to the original's, and are explained under delivery uncertainty
below. It is
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
processes the bound is `plowshare.containment.sweep-grace`, a minimum age below
which an unaccounted stage is assumed to belong to a creation still running. The
age measured is the **newest write anywhere in the stage**, not the workspace
directory's own mtime: a directory's mtime changes with its entries, so the
workspace's stops moving as soon as `revisions/` exists while the creation goes on
writing underneath it, and measuring that ran the grace from the start of staging
instead of from the last byte — shortening the only protection the window below
has. The grace defaults long and is floored at one minute, because the two
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
whether the whole root was enumerated, whether it could not be, whether the
configured root does not exist yet, or whether no root is configured at all,
because the counts alone cannot tell "looked, found nothing" from "could not
look" — both answer zero — and the scheduler logs a sweep that could not look.
Collection is this record's observability claim, so the component carrying it
cannot be the one that hides its own failure. A root that does not exist yet is
distinguished from one that could not be read, and is **not** logged: the root's
entries are made by creation, so its absence is the steady state of a server that
has never created a contained Application, and warning about that every interval
is what teaches an operator to ignore the one warning that matters.

`ContainmentConfig` therefore now holds one value that defaults when absent
(`sweep-grace`) beside two that fail at startup when absent (the depth and
breadth bounds), and the asymmetry is deliberate rather than an oversight: a
tuning knob is not a deployment identity, and an absent bound would mean
*unlimited* whereas an absent grace merely means *long*.

## The narrow door a contained run starts through

`ConversationStore.openContained` writes `Origin.CONTAINED`, and `Turn.speak`'s
admissibility guard `requireSpeakable` accepts `Origin.TURN` and nothing else. So
for the whole of the slice that made a child executable, `application.child.run`
could open a child's conversation and then never spend a turn in it: the first
utterance was refused with "conversation … is the log of a contained run". The
guard was right and the caller was reaching the wrong door.

**Widening `requireSpeakable` to accept `CONTAINED` would have been a confinement
hole, not a fix.** That guard is the one a *caller-supplied* conversation id
reaches, from `POST /v1/agents/{name}/runs` and the `agent.run` frame alike, and
neither compares the row's own `agent` against the definition it was handed. A
wider set would therefore have let `agent.run` post an utterance into a child's
log, driven as whatever agent the caller named, and spend the child's allowance.
`requireSpeakable`'s own record already states this trap for `Origin.ORCHESTRATION`
and answers it the same way: not a wider guard, but a second and narrower method
that nothing routes a caller-supplied id to. `requireConductor` and
`requireApprovedRun` are the two precedents; `requireContainedLog` is the third.
Making a contained run speak as `Origin.TURN` instead is not available either:
V17's `(origin='turn') = (agent IS NULL)` requires a TURN row to name no agent,
and a contained run's row always names one.

**The safety property is the door's reachability, and it is enforced by the
compiler rather than by a convention.** `Turn.speakContained` is package-private,
so nothing outside `io.aeyer.plowshare.server.agents` can name it, and its first
parameter is `Runs.ContainedStart` — a `sealed` interface whose single permitted
implementation is a `private` record nested in `Runs`. No class but `Runs` can
implement the interface or construct the implementation, so **no code outside
`Runs` can produce the argument the door requires**. The conversation spoken to is
read off that key rather than taken as a parameter of its own, so a caller cannot
pair the key with a different conversation.

What the compiler enforces is "only `Runs`". The step from there to "only
`Runs.start`'s contained branch" rests on two facts tests pin instead: the key is
constructed at exactly one site, and that site sits inside
`if (ask.contained() != null)` — a branch no wire shape can reach, because
`api.RunAgentRequest` carries no such component — what `ContainedRunTest` pins by
reflection is that the request record declares no component *named* `contained`
or `workProject`, which is adequate because names are what Jackson binds, and
that test asserts `task` and `project` are present so the absence is not an empty
reflection result. That is two enforced links and one pinned one. It is stated this way so a later reader does
not read a stronger claim into it than it makes.

**What must not be simplified.** A boolean parameter, a flag on a context object,
or a public overload would make the door wide with a convention attached: any
caller could then ask for the contained guard. A package-private key would be
constructible by every class in this package. And `requireContainedLog` must stay
*disjoint* from `requireSpeakable` rather than a weaker version of it — one accepts
`TURN` alone, the other `CONTAINED` alone, and the two sets must never meet. It
checks the reserved project prefix as well as the origin, so it is sound on its
own rather than on `openContained`'s behalf.

## Where the mid-run revocation is noticed, and the one call it cannot prevent

This record requires that revoking a container's authority mid-execution stops
descendant work, and that happens — but not where a reader would guess, and the
accounting consequence belongs here rather than in a report.

The recheck is **on the model call itself**. Every call a contained run makes
carries the operation's own `CallAuthorization` (`() -> operations.require(…)`, or
the equivalent through `InferenceCalls`), and `JobRuntime.streamed` invokes it
*after the completion comes back and before that completion's output is published
or any further effect is started*; the real `LlmDispatcher` asks the same
authorization again before each submission. The turn loop's own recheck at the top
of each pass and after each tool result are later boundaries. Whichever a run
reaches first after the grant is withdrawn is the one that fires, and for a
revocation landing during inference that is the post-inference check — established
by mutation, not by reading: removing the loop-top recheck changes nothing, and
removing the post-settle recheck makes the refusal stop escaping.

It sees a *container's* revocation because `contain` copies the container's own
grant rows rather than issuing any, so every support row on a child's admission
carries the container's grant id and `requireOwn` checks them against the nearest
enclosing **uncontained** scope. Revoking the container's grant therefore kills
the descendant's admission with nothing about the child's row changing and nobody
telling the run.

**The consequence to record: a revoked container's ceiling can absorb one more
model call.** The run stops before publishing that completion's output and never
starts another turn, but the call that was already in flight when the grant went
has been claimed and spent. Spend after revocation is bounded by **one
completion, not zero**. No recheck placed anywhere could make it zero without
interrupting an in-flight request, which `LlmPool.submit` records as the thing not
to do. A reader reconciling a container's allowance against the moment of
revocation should expect that one call.

## Cancellation is process-local

`ContainedApplications.cancel`, and the stop a destruction performs over the
subtree before collecting any source, both reach runs through
`CancellableRuns.cancelIn`, which `JobStore` implements over the same flag
`JobStore.cancel` has always set. That flag is **a request honoured at a run's next
turn boundary, not an interrupt**, and the jobs it can reach are the ones *this
process* holds — `JobStore.cancel` has always said so ("this process knows …").
Both properties are inherited deliberately rather than improved on.

The verification list states without qualification that destroying a container
stops every descendant's work. On a single-node deployment it does. On a
multi-node one, a `destroy` served by one node does not set the flag on a
descendant executing on another, so the bound is this: that run cannot make
another model call, because the destroying transaction zeroes every descendant's
allowance before its rows go, but it *can* still read files until its current turn
ends — and the source area it is reading may be collected underneath it. The
exposure is therefore file reads during one in-flight turn, never unbounded
spending. Closing it properly needs a durable cancellation signal, which is a
separate capability and not represented in this schema. A caller must likewise not
read a zero from `cancelIn` as "nothing is running anywhere".

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
- **A record that matches** — success, replaying the *same* answer. Two columns
  are what make "the same" true rather than nearly true, and this claim is the
  reason a request-UUID receipt was not added, so neither is optional. V221
  records the destroyed child's own `scope`, which links a parent's collection row
  to its descendants': without it a retry could only answer about the one record
  it named, a *different* answer from the original and worse than a refusal. V222
  records the `root` — the child a single `destroy` named, written on every record
  that destruction wrote. A containment edge says which rows are *beneath* a
  scope, not which destruction collected them, so a rebuild walking the edges
  alone also picked up a descendant destroyed earlier in its own separate
  destruction, and answered a retry with a longer `collected` list and a larger
  `sources` count than the destruction being replayed had produced. The subtree is
  therefore rebuilt by descending the recorded containment edges, restricted at
  every edge to the records of that one destruction, deepest first, bounded by the
  configured depth.

A retry also finishes an interrupted source removal, so calling twice is not
merely safe but useful: the second call collects the bytes the first could not.

## Which SDKs expose the eight operations

`application.child.create`, `.ceiling`, `.destroy`, `.list`, `.trajectory`,
`.resources`, `.run` and `.cancel` are exposed in the TypeScript/Node, Python, Go
and .NET SDKs. The
**Java SDK is curated** (it exposes a subset of the shared operations) and does
**not** expose them: it has no child methods on its Application client and no
public raw request path. That matches its existing omission of the
`application.file.*` operations; extending a curated client is a product decision
no slice has scoped. A Java consumer that needs these operations has no
supported route to them today.

Request-side semantic validation (UUIDs, explicit call counts, the source package,
and from slice three the agent name, the task and the turn cap) is performed by the
TypeScript/Node request gate before sending. Reply correlation, which refuses a
`ceiling`, `destroy`, `cancel` or `run` answer about a different child than the one
asked of, is implemented in TypeScript, Python, Go and .NET. `resources`' reply
carries no child field, so there is nothing for reply correlation to correlate and
none is implemented for it.

**`run` is the first contained operation whose reply is not an `OK`.** It answers
`ACCEPTED` with a handle — `{child, job, conversation, agent}` — so it is typed
beside the started-job shapes rather than in the conversation reply family, and it
is deliberately **not** a `JOB_SUBMISSION`: those carry a `StartedJob` whose handle
is `id`, and reading this reply through that path would have required the server to
answer a shape it does not. It has its own branch in `resultOf`, which refuses an
`OK`, `CREATED` or `NO_CONTENT` answer as a reply the SDK does not understand, and
returns `accepted` with the job and the **child's own** conversation. The
conversation in the reply is never the container's that the request named, and the
round-trip tests assert that distinction in all four languages. `run` and `cancel`
are both classified as mutations, so neither is replayed after uncertain delivery.

**One of `run`'s three request-side bounds is TypeScript's alone, and it is
`task`.** The first draft of this paragraph said all three were, by restating what
slices one and two said about `bytes` and `child`; that was wrong, and checking it
is what found the mechanism the two older entries also describe incorrectly.

- **`maxTurns` is bounded in all four SDKs.** The native schemas carry `"integer":
  true, "minimum": 1, "maximum": 2147483647` on it, and the three native validators
  enforce those keywords (`sdk/go/validation.go`'s numeric arm,
  `_validation.py`'s `integer`/`minimum`/`maximum` terms, `Validation.cs`'s
  `Flag(schema,"integer")` and `Bound(...)`). A zero, a fraction, a negative and an
  overflow are each refused client-side in Go, Python and .NET, which the
  round-trip tests now assert rather than describe.
- **`agent` is bounded in all four**, by `minLength`, `maxLength: 1024`, a
  control-character-excluding `pattern` and `trimmed` in the native schemas. A
  blank agent name is refused in all three native SDKs, also asserted.
- **`task` is bounded in TypeScript only.** It stays a bare string shape, so a
  blank task reaches the wire from Go, Python and .NET. The server's frame refuses
  it — `text(payload, "task")` is where — so this is **defence in depth that is
  missing, not a reachable defect.**

**The mechanism, because the two older entries state it wrongly and a reader would
act on it.** These bounds are not something the generator infers from a type. They
are **declared by field name** in `scripts/native-sdk-constraints.mjs`, which
enriches the three native schema files only — `operation-schemas.ts` and the shared
`sdk-protocol.json` carry none of them, so TypeScript's own structural check bounds
nothing here and its hand-written gate is doing all of its work. `maxTurns` is in
that file's integer-bound name list; `agent` is in its identity name list; and
`task` is in its **explicit exclusion** from the generic non-blank rule, beside
`answer`, `text`, `body` and `definition` — prose fields, where a blank is
legitimate somewhere else in the surface. So `task` is unbounded natively *on
purpose, by name*, not because the generator could not say it.

**The .NET round-trip tests for `.run` and `.cancel` were written and never
compiled, and `./gradlew sdkCheck` could not complete, for the third slice
running.** It failed at `:sdkBuild` with "dotnet is required; install it or set
`PLOWSHARE_SDK_DOTNET`" before the cross-language conformance step ran, so the
Node, Python, C# and Go conformance pass did not execute. The TypeScript client
suite ran and passed before that point, and the Python and Go round-trips were run
directly. **The .NET path is not covered** and is not described as covered.

**The .NET round-trip test for `.resources` was written and never compiled, and
`./gradlew sdkCheck` could not complete, because the authoring machine has no
`dotnet`.** `sdkCheck` failed at `:sdkBuild` ("dotnet is required") before the
cross-language conformance step ran, so the Node, Python, C# and Go conformance
pass did not execute; the TypeScript client suite ran and passed before that
point, and the Python and Go round-trips were run directly. This is the **same
unverified .NET gap slice one shipped**, not a new one, and it is stated plainly
rather than described as covered.

**`bytes` is unenforced in the Go, Python and .NET SDKs.** The schema generator
emits a plain `number` for it, so those three accept `-1` and `1.5`; only the
TypeScript SDK's hand-written semantic check rejects them, and `kind` is
enforced structurally in all four. The server's `Resource` record validates
`bytes` on the producing side, so this is **defence in depth that is missing, not
a reachable defect** — no server reply can carry an invalid value. **Fixing it does
not mean teaching the generator anything**, which is what slices one and two
recorded and is corrected below: `scripts/native-sdk-constraints.mjs` already emits
integer bounds by field name, and enriches reply shapes elsewhere. `bytes` is
undeclared in it, so the remedy is a declaration and a regeneration rather than a
contract redesign — still a change someone must decide and review across four
SDKs, and still not patched into generated output here.

**`child` is unenforced as a UUID in the Go, Python and .NET SDKs — the same
undeclared-constraint gap, on the request side.** In the three native schema files
the request shapes that carry it are structurally identical and each types `child`
as `{"$ref":"#/$defs/shape1","minLength":1,"nonblank":true,"noNul":true}`, where
`shape1` is a bare `{"type":"string"}`: no UUID pattern anywhere, so all of them
accept a non-UUID string client-side and only TypeScript's hand-written
`containedProblem` check rejects it. The shapes are now **six** — `ceiling`,
`destroy`, `trajectory`, `resources`, `cancel` and `run`. The first draft of this
sentence said five and omitted `ceiling`, which is the oldest of the six: counting
by hand what a listing can answer is how the shape citations went wrong too.

**The shape numbers these three rows used to cite are gone, and citing them was the
mistake.** Slice two named `shape56`, `shape59` and `shape58`; adding two
operations renumbered them to `shape57`, `shape62` and `shape59`, so every figure
was wrong the moment this slice regenerated — and `shape59` was wrong in the worst
way, by still resolving to a real shape that is no longer the one meant. A `$defs`
key is allocated in traversal order by the schema generator, so **any** addition
anywhere can move it: it is the least stable identifier in the artifact and must
not be cited in prose. The stable references are the **operation name** and the
**field name**, which is what this row now uses. The same correction applies to the
`bytes` row above, which cites no shape for the same reason.

The server parses and validates `child`, so again this is **defence in depth that
is missing, not a reachable defect**, and the remedy is the same one: a declaration
in `scripts/native-sdk-constraints.mjs` — `child` added to the UUID-pattern list
that `requestId` is already in — and a regeneration, not an edit to generated
output. `bytes`, `child` and now `task` are recorded together because they are
**one class of limitation**: a constraint nobody has declared in the one generator
input that declares them, enforced by hand in TypeScript and by the server on both
sides of it. Three entries, not three independent SDK bugs, and not — as slices one
and two had it — a constraint the generator cannot express. Any future entry of
this shape belongs beside these three.

**One frame cost four pin bumps, and the pins prove nothing about correctness.**
Adding `application.child.resources` required the contract generators to re-render
the TypeScript, Python, Go and .NET artifacts and the shared
`test-support/contracts/sdk-protocol.json`, and then four bare family-size counts
and hashes to be bumped by hand (`contracts.test.ts`,
`conversation-replies.test.ts`, `inspection-replies.test.ts` and the legacy CLI
audit's catalog hash). Those pins detect an **omission** — a family that gained
or lost a member without anyone noticing — and say nothing whatever about whether
the member they now count is right. Derived artifacts are regenerated through
their owning scripts and never edited by hand.

**Two frames cost six, and two of the six are regenerated rather than typed.**
Slice three bumped the same three TypeScript counts (275 → 277 CLI operations,
265 → 267 one-shot validated operations) and the conversation reply family's
operation count in `test-support/contracts/build_manifest.py` (85 → 86 — the
*type* count stays 42, because the two new reply DTOs live in
`application-deployments.ts` rather than in the replies file the count scans), and
then regenerated `client-capabilities.json` and `legacy-cli-audit.json` through
their owning scripts rather than editing either. `application.child.cancel` also
needed a fixture in `test-support/contracts/ws-conversation-fixtures.json`, because
that file's keys must equal the conversation family exactly;
`application.child.run` needs none there, because it is not in that family. The
count that matters is the one no pin states: **292 registered operations**, which
is what `FrameRouterTest` and `:clientManifestCheck` compare against the server's
own `FrameTypes`, and which is the only one of these numbers that would have caught
the frames being declared on one side and not the other.

**The bare counts also failed to notice a merge.** Slice three bumped the two
TypeScript pins from 275 to 277; `main` independently added `tool.invocation.read`
and `artifact.admission.status` and bumped the same two pins from 275 to 277 in its
own commits. Both sides therefore wrote the identical literal, Git merged it
without a conflict, and the merged tree's real figures are 279 and 269. A count
that both branches move to the same wrong number is worse than no count: the
merge itself flagged nothing, and the pins had to be read against both parents
and then against the regenerated manifest to see it. The registered total went
from 290 to 292 in the same merge.

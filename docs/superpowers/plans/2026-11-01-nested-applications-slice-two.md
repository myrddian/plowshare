# Nested Applications Slice Two: Child Knowledge Reads — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a contained Application's knowledge home readable by its
ancestors, so that a container can see what it confined produced, and admit the
child's own scope to the read predicates.

**What this goal does not include, decided during Task 1.** A child reading its
own *documents* back is **not** in this slice. Scope admission is only the first
of two gates: `knowledge_revision_readable` opens with
`information_global_read_member(actor)`, which requires an `admins` row, and a
contained principal deliberately holds no credential material (V210). Granting a
credential-less principal general-read membership is the authority question
[ADR 0042](../../decisions/0042-nested-application-containment.md) scoped out,
and it belongs with the slice that makes a child execute — because nothing
executes a contained child yet, **nothing in this slice reads as the child**, so
the capability has no consumer here. It fails closed. Task 1 pins the refusal
with a test that identifies that gate rather than guessing at it, and Task 5
records it.

**Architecture:** Slice one made every read predicate refuse a `CONTAINED` scope
by kind, which fails closed and leaves a child unable to read even itself. This
slice adds one arm to each of the two SQL admission chokepoints — the child
itself, or an ancestor of the child — and widens the retained read ceiling
downward only, so a boundary home admits its descendants and never its
ancestors. On top of that it adds one typed operation, `application.child.resources`,
authorized by the same ancestor rule `trajectory` already uses.

**Tech Stack:** Java 21 / Spring Boot, Spring JDBC, Flyway, PostgreSQL;
generated contracts for TypeScript/Node, Python, Go and .NET.

**Spec:** [docs/superpowers/specs/2026-10-25-nested-applications-design.md](../specs/2026-10-25-nested-applications-design.md)

**Prior slice:** [ADR 0042](../../decisions/0042-nested-application-containment.md)
is the authorizing record and states the limitation this slice lifts. Read its
"Known limitations this inventory does not close" and its addressability
inventory before Task 1; Task 5 amends both.

## Global Constraints

- **A read ceiling grants nothing and only ever narrows.** `KnowledgeReadScope.intersect`
  is the model: preserve its no-enlargement guarantee at every new call site.
- **One-way visibility.** An ancestor reads a descendant. A descendant must never
  read an ancestor, and a sibling must never read a sibling. Every widening in
  this slice is directional, and the test that proves it is the refusal, not the
  success.
- **Child output is untrusted input to its parent.** These reads are a
  prompt-injection surface by construction; child content is data, never
  instruction. Nothing in this slice may compose child content into a
  server-side decision.
- **New Flyway migration only; never edit a shipped one.** The next free version
  is **V215** — the highest shipped is V214. Verify with
  `ls plowshare-server/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1`
  before creating it, because `main` advances this number independently and a
  duplicate version makes Flyway refuse to start.
- **Repositories own persistence.** Only repositories/stores may touch
  `JdbcTemplate`. Services, tools and frames must not execute SQL or receive one.
- **Typed DTOs at boundaries.** No `Map<String, Object>`, `JsonNode` or generic
  payload crosses into an internal contract.
- **Depth and breadth bounds are server configuration** (`ContainmentBounds`),
  never caller-supplied, and absence is never unlimited.
- **No hard-coded deployment values**: no paths, hosts, ports or account
  identities in runtime code.
- After Java edits run `./gradlew format` then `./gradlew formatCheck`.
- **Database tests** stay behind `@Tag("full-db")` and `-PfullDb`. This slice
  qualifies for them only where the assertion *is* PostgreSQL behavior — the
  migration applying, and the predicate bodies admitting and refusing. Behavior
  above SQL is tested through mocked repositories.

## Review Focus

Five conditions the spec implies that no task's happy path exercises. Each has
its test named in the task that owns the code.

1. **A child whose container chain is broken mid-read** — a `CONTAINED` scope
   whose `container` no longer resolves. The read must refuse, not fall back to
   admitting the scope. (Task 1)
2. **A dependency closure that leaves the subtree** — a child resource deriving
   from a revision outside its own home. The whole read must refuse, as
   `knowledge_dependencies_readable` already makes it for every other kind.
   (Task 2)
3. **A service account reaching a child** — `service_project_role` must not
   become a route into a contained project, or any service principal could read
   every child on the server. (Task 1)
4. **A sibling reading a sibling** — two children under one container. Neither
   is an ancestor of the other, so each must be refused the other's home even
   though both are `CONTAINED` and both sit inside the same ancestor's subtree.
   (Task 1)
5. **A child receiving a generally-readable row it never named** — the spec
   requires a child to receive nothing from outside its subtree "including
   through audience-driven rows it never named". `contain` pins the child's
   ceiling `globalRead` to `false`, which closes the general arm; Task 2 touches
   that arm, so the closure needs a test rather than an argument. (Task 2)

A sixth condition has a test without a line here: a containment chain longer
than the structural walk sentinel must refuse rather than return the rows it
managed to walk (`aChainBeyondTheWalkSentinelRefuses`, Task 1).

---

### Task 1: Containment ancestry in SQL, and the two scope-admission arms

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V215__contained_knowledge_reads.sql`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedKnowledgeReadSchemaTest.java`

**Interfaces:**
- Consumes: `authority_scopes(id, kind, project_id, container)` as V208 defines it,
  with `container` frozen after insert by V209; `contained_principals` (V210) for
  the child-self arm; `knowledge_scope_readable(actor TEXT, scope UUID)` from V172
  and `knowledge_read_authority(actor TEXT)` from V178.
- Produces: `contained_scope_ancestors(descendant UUID) RETURNS SETOF UUID`, the
  upward chain resolved once, which Task 2 joins. (This task first produced a
  boolean `contained_scope_within(descendant, ancestor)`; Task 2 found a better
  shape, nothing called the boolean, and it was removed by ruling.) Both widened
  predicates keep their exact existing signatures, because PostgreSQL retains
  function signatures on replace and every existing caller must keep resolving.

**The two chokepoints, and why both.** `knowledge_scope_readable` is the
exact-target predicate. `knowledge_read_authority` returns the materialized
readable-scope set that `knowledge_readable_candidates` (V198) joins against for
discovery. They are independent: widening only the first leaves discovery blind,
and widening only the second leaves exact reads refused. Both currently fence by
kind — `s.kind IN ('PROJECT','PERSONAL')` and `s.kind='PROJECT' OR (s.kind='PERSONAL' AND …)`
respectively.

**The walk's bound.** `contained_scope_within` recurses over
`authority_scopes.container`. The configured `max-depth` is not reachable from
SQL and must not be duplicated there as a literal policy value; it stays in
`ContainmentBounds` and is enforced on create. The SQL walk instead carries a
**structural sentinel of 64 levels**, in the idiom of
`knowledge_dependency_revisions`'s `LIMIT 1025`: a safety net that fails closed
on data that should be impossible, not a second copy of the policy bound. Say so
in the migration's comment, because a reader will otherwise take it for the
depth bound.

- [ ] **Step 1: Write the failing schema test**

New `@Tag("full-db")` test class, structured like
`ContainedScopeSchemaTest`. Real PostgreSQL because every assertion is a
predicate body's behavior. Insert a container scope, a child, a grandchild and a
sibling directly, then assert:

```java
@Test void aChildReadsItsOwnHome()                      // knowledge_scope_readable(child principal, child scope) is true
@Test void aContainerReadsItsChildsHome()               // (container role holder, child scope) is true
@Test void aGrandparentReadsThroughTheChain()           // (container role holder, grandchild scope) is true
@Test void aChildCannotReadItsContainersHome()          // (child principal, container scope) is false
@Test void aSiblingCannotReadASiblingsHome()            // (sibling A principal, sibling B scope) is false
@Test void anUnrelatedAccountReadsNoContainedHome()     // (stranger, child scope) is false
@Test void aServerAdministratorReadsNoContainedHome()   // server_admin is not an ancestor
@Test void aServiceAccountReadsNoContainedHome()        // service_project_role is not a route  [Review Focus 3]
@Test void aBrokenContainerChainRefusesRatherThanAdmits()  // container points at a missing scope  [Review Focus 1]
@Test void aChainBeyondTheWalkSentinelRefuses()         // 65 nested scopes: the 65th is not readable from the root  [Review Focus 5]
@Test void theReadAuthorityRelationAgreesWithTheExactPredicate()
```

The last one is the one that catches a half-done widening: for each (actor,
scope) pair above, assert that `scope_id` appearing in
`SELECT scope_id FROM knowledge_read_authority(actor)` matches
`knowledge_scope_readable(actor, scope)` exactly.

- [ ] **Step 2: Run it to verify every assertion fails closed**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedKnowledgeReadSchemaTest'`
Expected: the **seven** refusal tests PASS already (slice one fails closed); the
**three** admission tests and the agreement test FAIL. Record which in the commit
message — a test that was green before the implementation proves nothing about
it, and slice one shipped a task whose whole suite passed with the
implementation removed.

- [ ] **Step 3: Write V215**

`contained_scope_within(descendant, ancestor)` walks `container` upward from
`descendant` with the 64-level sentinel and returns whether `ancestor` is on the
chain, exclusive of `descendant` itself. Then one new arm in each predicate,
admitting a `CONTAINED` scope when either:
- the actor is that scope's own principal in `contained_principals`, or
- the actor resolves a role through `authority_project_role` on the project of
  some scope `a` where `contained_scope_within(scope, a)`.

Keep `service_project_role` out of the new arm, deliberately and with a comment:
the existing arm's `coalesce(authority_project_role, service_project_role)` is
what Review Focus 3 would otherwise ride in on.

- [ ] **Step 4: Run the test to verify it passes**

Run: the command from Step 2. Expected: all PASS.

- [ ] **Step 5: Run the slice-one schema suites to verify nothing regressed**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedScopeSchemaTest' --tests 'io.aeyer.plowshare.server.applications.ContainedApplicationSchemaTest'`
Expected: BUILD SUCCESSFUL. These assert the refusals this task deliberately
narrows, so a failure here means the widening went too far.

- [ ] **Step 6: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration/V215__contained_knowledge_reads.sql plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedKnowledgeReadSchemaTest.java
git commit -m "Admit a contained scope to its own and its ancestors' reads"
```

---

### Task 2: The retained ceiling, widened downward only

**Files:**
- Modify: `plowshare-server/src/main/resources/db/migration/V215__contained_knowledge_reads.sql` (same migration, still unshipped)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedKnowledgeReadSchemaTest.java` (extend)

**Interfaces:**
- Consumes: `contained_scope_ancestors(descendant)` from Task 1. (Task 1 also
  produced `contained_scope_within(descendant, ancestor)` as this task's declared
  interface; the task found a better shape joining the SETOF once, nothing called
  the boolean, and it was removed by ruling.)
- Produces: no new signature.
- **Three bodies, not two.** `knowledge_document_in_boundary` (V172) has no
  caller in `src/main` outside migrations; `InformationSql.java:79` calls
  `knowledge_readable_scopes` (V184), and `knowledge_readable_candidates` (V198)
  carries the discovery `in_boundary` term. All three are widened, because two of
  them would not have reached production and V184 documents an "identical
  admitted sets" invariant with V172 that a contained descendant would otherwise
  violate.

**Why this is a second task, and what Task 1 already opened.** Task 1 decides
*whether a scope is readable at all*. This decides *whether a readable scope is
inside the retained ceiling the caller is executing under*. They are different
gates, and Task 1's review established exactly where the line falls — correcting
this plan's earlier claim, which said a descendant stays out of every ceiling
until this task.

The boundary home (`$3` in `knowledge_document_in_boundary`, `$2` in
`knowledge_readable_scopes`, `$5` in `knowledge_readable_candidates`) is the
*request's selected project*, not a value derived from the actor. So an ancestor
that addresses the child's own `contained:…` project as its boundary home
already satisfies the equality, and Task 1 alone opens document-level reads of a
descendant's home. **That case is Task 1's blast radius and Task 1's tests own
it.**

What remains for this task is the **cross-boundary** case: an ancestor executing
under *its own* project as boundary home, reading a resource whose home is a
descendant. Equality refuses that today. The reverse — a descendant reaching an
ancestor's resource — must stay refused, and a child cannot reach it anyway
because `KnowledgeReadScope` is server-owned and `contain` pins a child's home to
its own.

- [ ] **Step 1: Write the failing tests**

```java
@Test void aContainersCeilingAdmitsAChildsResource()      // boundary_home = container project, resource in child
@Test void aChildsCeilingDoesNotAdmitItsContainersResource()  // the same pair, inverted
@Test void aChildsCeilingAdmitsItsOwnResource()
@Test void aCeilingDoesNotAdmitASiblingsResource()
@Test void aClosureLeavingTheSubtreeRefusesTheWholeRead()  // child resource deriving from an outside revision  [Review Focus 2]
@Test void aChildReceivesNoGenerallyReadableRowItNeverNamed()  // global_read false closes the general arm  [Review Focus 5]
```

The last one asserts the predicate with `global_read => false` against a
`GLOBAL_READ`-audience revision outside the child's subtree, which is the shape
`contain` always produces for a child.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedKnowledgeReadSchemaTest'`
Expected: the four refusals PASS, and so does
`aChildsCeilingAdmitsItsOwnResource` — `in_boundary` already compares the project
name by equality, so Task 1 alone makes a child's own resource in-boundary. The
one genuine red is `aContainersCeilingAdmitsAChildsResource`.

- [ ] **Step 3: Replace the boundary comparison in both places**

In `knowledge_document_in_boundary` and in `knowledge_readable_candidates`'s
`in_boundary` term, the boundary test becomes "the resource's home project is the
boundary home, **or** the resource's scope is contained within the boundary
home's scope". Leave the account-knowledge and global arms exactly as they are.
Both functions are `CREATE OR REPLACE` with their existing argument names, which
V172 already warns is load-bearing.

- [ ] **Step 4: Run the test to verify it passes**

Run: the command from Step 2. Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration/V215__contained_knowledge_reads.sql plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedKnowledgeReadSchemaTest.java
git commit -m "Extend a retained read ceiling to its contained descendants"
```

---

### Task 3: `application.child.resources`

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedApplications.java`
  — `Resource` goes here beside `Child` and `Step`. There is no
  `plowshare-protocol` DTO file for the child operations and this slice does not
  add one: the existing records are declared on this interface.
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java`
- Modify: `sdk/typescript/src/operations/catalog.ts` — the request/reply types and
  the spoken-name alias, beside the other `application.child.*` entries
- Regenerate: `test-support/contracts/client-capabilities.json`, via
  `python3 test-support/contracts/build_manifest.py`

**Why the TypeScript declaration is in this task and not in Task 4.** The
capability manifest is generated from `FrameTypes.java` and the routing areas,
but `build_manifest.py:275` also asserts `typed_frames == set(areas)` — the
TypeScript catalogs must declare exactly the registered WS surface, or the
generator refuses to run at all. So registering a frame server-side without
declaring it in `catalog.ts` leaves three `FrameRouterTest` consistency tests
red and the manifest unbuildable. The declaration is part of registering an
operation, not part of testing the SDKs, and a task that cannot leave the branch
green is bounded in the wrong place.
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedResourceReadTest.java`

**Interfaces:**
- Consumes: the ancestor refusal already in `SourceContainedApplications`
  (`Refuses unless ancestor is this child's container or a container of one`,
  bounded by the configured depth) — the same gate `trajectory` uses. Reuse it;
  do not write a second one.
- Produces:
  ```java
  record Resource(String path, String kind, UUID revision, long bytes) {}
  List<Resource> resources(String account, String conversation, UUID child);
  ```
  on `ContainedApplications`, and `APPLICATION_CHILD_RESOURCES = "application.child.resources"`.

**The sendability trap, and who owns it.** Slice one shipped all five child
operations unsendable, because the generic `application.*` file check demanded a
`project` field no child request carries, and nothing caught it until a
set-level audit. That check is `containedProblem` in
`sdk/typescript/src/operations/application-deployments.ts`, routed from
`payload-validation.ts`. **It is TypeScript, so a Java test cannot reach it, and
Task 4 owns its test.** This task's tests are the server-side ones only.

- [ ] **Step 1: Write the failing tests**

```java
@Test void aContainerListsItsChildsResources()
@Test void aGrandparentListsThroughTheChain()
@Test void aChildCannotListItsContainersResources()
@Test void aSiblingCannotListASiblingsResources()
@Test void aDestroyedChildRefusesRatherThanReturningHistory()
@Test void anUnconfiguredContainmentRootRefuses()
```

Mocked repositories throughout: no assertion here is PostgreSQL behavior.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedResourceReadTest'`
Expected: FAIL — `resources` undefined.

- [ ] **Step 3: Implement the operation**

Register the frame, add the DTO, add `resources` to the interface and implement
it in `SourceContainedApplications` behind the existing ancestor gate. The read
resolves the child's revision through the repository and lists the staged source
area's entries; it returns metadata only, never file content, so this slice adds
no path by which child bytes reach a parent's prompt without the parent asking
for them by name.

- [ ] **Step 4: Run the test to verify it passes, then format**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedResourceReadTest'` then `./gradlew format formatCheck`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add application.child.resources behind the ancestor gate"
```

---

### Task 4: Regenerate the contracts and exercise them per language

**Files:**
- Modify: `sdk/typescript/src/operations/catalog.ts` (the request/reply type map
  and the spoken-name alias) — and *only* that file by hand
- Test: `sdk/typescript/test/` for the sendability test this task inherits from
  Task 3
- Regenerate: every path listed in `.gitattributes`
- Test: `sdk/typescript/test/`, `sdk/python/tests/`, `sdk/go/`, `sdk/dotnet/ContractTests/`

**Interfaces:**
- Consumes: `APPLICATION_CHILD_RESOURCES` and the `Resource` DTO from Task 3.

- [ ] **Step 1: Confirm the catalog declaration Task 3 added**

Task 3 added the `catalog.ts` entry and its spoken-name alias, because
`build_manifest.py` refuses to generate the capability manifest unless the
TypeScript catalogs declare exactly the registered WS surface. Verify it is
present and shaped like its neighbours; do not add a second one.

**Add no validation branch.** `payload-validation.ts` routes on
`type.startsWith('application.child.')`, and `containedProblem`'s
non-`create` branch already requires `conversation` plus a UUID `child`, which
is this operation's exact shape. A second branch saying the same thing is
duplication, not safety.

- [ ] **Step 1a: Write the sendability test (inherited from Task 3)**

In the TypeScript suite, assert that `application.child.resources` validates
with a `conversation` and a UUID `child`, and is rejected without them — the
check slice one shipped broken for all five child operations.

- [ ] **Step 2: Regenerate and verify the catalogs**

```bash
./gradlew :plowshare-client-ts:clientBuild
node scripts/generate-sdk-contracts.mjs
```
Expected: `SDK contracts verified: <N> operations, <M> outcome codes` with N one
higher than before. Never hand-edit a generated catalog; if a merge conflicted
one, re-render it (see "Regenerate catalogs" in `docs/sdks.md`).

- [ ] **Step 3: Write and run a per-language round-trip test**

One test per SDK asserting the operation encodes and its reply decodes. The .NET
one must actually be compiled — slice one shipped a .NET guard that was never
built, and it is still unverified.

Run: `./gradlew check` then `./gradlew sdkCheck`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Carry application.child.resources into every SDK"
```

---

### Task 5: Re-audit what the widening reopened, and amend ADR 0042

**Files:**
- Modify: `docs/decisions/0042-nested-application-containment.md`

**Interfaces:**
- Consumes: ADR 0042's addressability inventory and its known-limitations section.

**Why this is a task and not a docs chore.** Slice one's inventory records
several entries as **fenced by kind** at exactly the two predicates Task 1
widens — the `V172` rows, and service `GENERAL_INFORMATION_READ`, whose entry
says in so many words that it "resolves through `knowledge_scope_readable`,
which admits no `CONTAINED` scope". Those fences are now narrower. Every entry
whose stated reason was the kind fence has to be re-decided and re-evidenced,
or the inventory is a false proof.

- [ ] **Step 1: Enumerate the entries whose fence this slice narrowed**

```bash
grep -n "Fenced by kind\|knowledge_scope_readable\|knowledge_read_authority\|CONTAINED" docs/decisions/0042-nested-application-containment.md
```
Every hit is a candidate. Write the list before deciding any of it.

- [ ] **Step 2: Re-decide each entry, with a test naming its new reason**

An entry stays **fenced** only if something other than the kind fence closes it
now. Otherwise it is **refused** by a new predicate, and that refusal gets a
test in Task 1's class. Service `GENERAL_INFORMATION_READ` is the one to settle
first: Review Focus 3's test is its evidence.

- [ ] **Step 3: Amend the ADR**

Update the inventory rows, replace the knowledge-read limitation with what now
holds, and record the three decisions this slice took that the spec left open:
the 64-level structural sentinel as distinct from the policy depth bound,
`resources` returning metadata without content, and the one below.

**The limitation this slice only half lifts, which the ADR must state exactly.**
A contained scope is now admitted to the read predicates, and an ancestor reads
a descendant's documents within the descendant's boundary. But a child reading
its **own documents** back is still refused, and not by the kind fence this
slice removed: `knowledge_revision_readable` opens with
`information_global_read_member(actor)`, which requires an `admins` row, and a
contained principal holds no credential material by design (V210). Scope
admission cannot reach that gate. Granting a credential-less principal
general-read membership is an authority decision, it belongs with the slice that
makes a child execute, and nothing in this slice reads as the child — so it has
no consumer here. Task 1 pins the refusal with a test that identifies the gate.
Record it as a named prerequisite of the execution slice, not as an oversight.

State plainly what still does not ship — nothing executes a contained child, so
every read here is proved against inserted rows rather than against a running
one.

- [ ] **Step 4: Commit**

```bash
git add docs/decisions/0042-nested-application-containment.md
git commit -m "Re-audit the read fences this slice narrowed"
```

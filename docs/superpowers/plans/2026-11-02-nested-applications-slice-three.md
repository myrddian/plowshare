# Nested Applications Slice Three: A Contained Application Executes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a contained Application run — its container starts one declared
agent of the child's own package inside the child's scope, as the child's own
principal, under the ceiling the container already set — and re-prove the
confinement inventory against a child that is executing rather than inserted.

**What this goal does not include.** No streaming or follow surface for a child's
run: `application.child.trajectory` stays a poll, and `conversation.follow` is not
widened to a contained scope. No promotion of a child to a retained release, no
schedule for a child (ADR 0042 refuses it outright and this slice does not revisit
it), no shared parent/child allowance counter, and no closing of the existence
oracle — that is still the contract change ADR 0042 defers. The recursive
harness workflow itself remains a separate design and no contract added here may
name harnesses, probes, candidates or improvement.

**Architecture:** Three gaps separate a contained admission from a running one.
First, every knowledge revision read opens with `information_global_read_member`,
which a credential-less bare-UUID principal cannot pass, so a child cannot read
even its own documents — V224 adds a second, strictly narrower way past that
baseline and *closes* the general-readable arm for the actors it admits, because
the baseline's existing disjunct would otherwise hand a child every
generally-readable row on the server. Second, nothing starts work in a contained
scope: `application.child.run` is added, authorized by the container-only rule
`ceiling` uses rather than the ancestor rule the reads use, resolving the child's
home through `containedHome` because `project(String)` refuses a contained scope
by design. Third, a running child reaches tools, delegation and provisioning that
slice one proved only against inserted rows; Task 4 fences those and Task 6
re-audits every inventory row against a live child.

**Tech Stack:** Java 21 / Spring Boot, Spring JDBC, Flyway, PostgreSQL; generated
contracts for TypeScript/Node, Python, Go and .NET.

**Spec:** [docs/superpowers/specs/2026-10-25-nested-applications-design.md](../specs/2026-10-25-nested-applications-design.md)

**Prior slices:** [ADR 0042](../../decisions/0042-nested-application-containment.md)
is the authorizing record. Read "Known limitations this inventory does not close",
"The two live-authority gates" and the addressability inventory before Task 1 —
this slice is named in three of those limitations as the place they are answered,
and Task 6 amends all three. Slice two's plan is
[2026-11-01-nested-applications-slice-two.md](2026-11-01-nested-applications-slice-two.md).

## Global Constraints

- **The baseline read gate is an authority decision, not a read one.** ADR 0042
  scoped out granting a credential-less principal general-read membership.
  This slice does not grant it: no `admins`, `authority_accounts` or
  `service_tokens` row is created for a contained principal, and V218's
  no-credential-material design is preserved exactly. What Task 1 adds is a
  second arm to the baseline that admits *only* a persisted contained principal
  reading *within its own subtree*. Any implementation that gives a child a
  `GLOBAL_READ` role row has failed this task.
- **A read ceiling grants nothing and only ever narrows.** `KnowledgeReadScope.intersect`
  remains the model at every new call site.
- **One-way visibility, now under execution.** A running descendant must never
  read, name, address, message or delegate to an ancestor or a sibling. Every
  widening here is directional and the test that proves it is the refusal.
- **Child output is untrusted input to its container** and a prompt-injection
  surface by construction. Nothing on the run path may compose child-authored
  content into a server-side decision, and no new operation may return child
  bytes to a container.
- **A child's authority never exceeds its container's at any surface**, including
  through a tool binding, a provider, a Relay audience, a swarm type, a FileStore
  destination, an inherited execution identity or an audience-driven read it
  never named.
- **The child is the executor and the accounting subject.** A run's execution
  identity is the contained principal, never the container's; spend is attributed
  to the child's own allowance under the ceiling its container set. ADR 0025's
  immutable attribution and its rule that delegation cannot change the accounting
  account are untouched, and no shared parent/child counter is introduced.
- **No new `Speaker.Kind`.** A contained run speaks as `Speaker.Kind.HARNESS`
  with source `contained <child-uuid>`, which is the documented idiom beside
  `orchestration <id>`, `approval <id>` and `event <id>`. Adding a third kind
  would change a shipped wire enum for five SDKs.
- **New Flyway migration only; never edit a shipped one.** The next free version
  is **V224** — the highest shipped is V223. Verify with
  `ls plowshare-server/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1`
  before creating it, because `main` advances this number independently and a
  duplicate version makes Flyway refuse to start. ADR 0042 records that these
  migrations have been renumbered twice already.
- **Repositories own persistence.** Only repositories/stores may touch
  `JdbcTemplate`. Services, tools, handlers and frames must not execute SQL or
  receive one.
- **Typed DTOs at boundaries.** No `Map<String, Object>`, `JsonNode` or generic
  payload crosses into an internal contract. A new boundary DTO carries plain
  wire values only — no `ScopeId`, for the reason ADR 0042 records of `Child`.
- **Depth and breadth bounds are server configuration** (`ContainmentBounds`),
  never caller-supplied, and absence is never unlimited. A running child that
  creates a child is bounded by the same two values as an idle one.
- **No hard-coded deployment values**: no paths, hosts, ports or account
  identities in runtime code or SDKs.
- After Java edits run `./gradlew format` then `./gradlew formatCheck`.
- **Database tests** stay behind `@Tag("full-db")` and `-PfullDb`, and only where
  the assertion *is* PostgreSQL behavior — V224 applying, and the predicate
  bodies admitting and refusing. Everything above SQL is tested through mocked
  repositories and in-memory fakes.

## Review Focus

Six conditions the spec requires that no happy path exercises. Each names the
task that owns its test.

1. **A running child receives a generally-readable row it never named.**
   `knowledge_revision_readable` admits on `knowledge_scope_readable(...) OR
   knowledge_general_readable(r.id)`. The child's ceiling pins `globalRead` to
   `false`, but that ceiling is not what this disjunct consults, so the moment
   Task 1 lets a contained actor past the baseline the general arm becomes a
   server-wide read. It must be closed for contained actors in the same
   migration, and the test is the refusal. (Task 1)
2. **A running child delegates upward or sideways to escape.** `agent_run` is a
   tool a child's own agent can call, and ADR 0011's delegation hop inherits
   execution identity downward. A child calling it must not reach an ancestor's
   or a sibling's definition, and must not acquire its container's identity.
   (Task 4)
3. **A container's authority is revoked while its child is mid-run.** The spec
   requires descendant work to stop. A run that completes after its authority
   is gone has spent the container's ceiling under an admission that no longer
   exists. (Task 5)
4. **A grandparent starts work in a grandchild.** Reads walk the whole chain, but
   starting work spends allowance and consumes breadth, both of which are the
   *direct* container's accounting. A grandparent must be refused, as it is for
   `ceiling`. (Task 3)
5. **Two running siblings observe each other.** Neither is the other's ancestor,
   so each must be refused the other's conversations, resources and job handles
   even while both are live under one container. (Task 3)
6. **A child's run outlives its source.** Destruction collects the source area;
   a run still executing against a collected revision directory must be
   cancelled rather than left reading a directory that is being removed. (Task 5)

---

### Task 1: The read baseline a contained actor passes, and the general arm it must not

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V224__contained_actor_reads.sql`
- Modify: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedKnowledgeReadSchemaTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedActorReadSchemaTest.java`

**Interfaces:**
- Consumes: `contained_principals` (V218); `contained_scope_ancestors(descendant UUID) RETURNS SETOF UUID`
  and the widened `knowledge_scope_readable(actor TEXT, scope UUID)` from V223;
  `information_global_read_member(account TEXT)` and `knowledge_revision_readable(revision UUID, actor TEXT)`
  from V172; `knowledge_general_readable(revision UUID)`.
- Produces: `contained_knowledge_actor(actor TEXT) RETURNS BOOLEAN`, true only for a
  `TEXT` that parses as a UUID present in `contained_principals`. `knowledge_revision_readable`
  keeps its exact signature, because PostgreSQL retains function signatures on
  replace and every existing caller must keep resolving.

**Why the baseline and not the ceiling.** Slice two proved the ceiling is not what
refuses a child its own document: `inBoundary(childPrincipal, document, home)` is
already true, and `knowledge_scope_readable` already admits the child's own scope
through V223's self arm. The single refusing term is the opening
`information_global_read_member($2)` conjunct, which requires an `admins` row
joined to `authority_accounts` carrying the `GLOBAL_READ` role. A contained
principal has none of those rows by design. The fix is a second arm on that
conjunct, not a widened ceiling and not a membership row.

**The general arm is the escape, and it opens in the same statement.** The
predicate reads

```sql
information_global_read_member($2) AND r.availability='active'
  AND p.audience IN ('HOME','GLOBAL_READ') AND s.kind<>'RESTRICTED'
  AND information_privacy_allows(q.id,$2)
  AND (knowledge_scope_readable($2,q.home_scope_id) OR knowledge_general_readable(r.id))
```

`s.kind<>'RESTRICTED'` already admits `CONTAINED`, and the final disjunct admits
*any* generally-readable revision regardless of scope. Widening only the opening
conjunct therefore grants a child every generally-readable row on the
installation — the exact anti-goal of a child obtaining authority its container
lacks through "audience-driven reads the child never names". For an actor admitted
by `contained_knowledge_actor` the disjunct must collapse to its scope arm alone.
Write it as one predicate, so there is no reading of the SQL under which a
contained actor reaches the general arm.

- [ ] **Step 1: Write the failing schema test**

New `@Tag("full-db")` class `ContainedActorReadSchemaTest`, with the fixture
shape `ContainedKnowledgeReadSchemaTest` already builds (container scope, child
scope, sibling scope, grandchild scope, each with a principal and a home). Assert,
with these names:

- `aChildReadsItsOwnDocument()` — `revisionReadable(childPrincipal, document)` is
  true for a document retained in the child's own home.
- `aChildIsRefusedAGenerallyReadableRowItNeverNamed()` — a revision in an
  *uncontained* home whose policy audience is `GLOBAL_READ` and for which
  `knowledge_general_readable` is true: `revisionReadable(childPrincipal, it)` is
  false, and `revisionReadable(HOLDER, it)` is true, so the row is proved
  generally readable and proved refused to the child in one test.
- `aChildIsRefusedItsContainersDocument()` — false, the direction slice two
  pinned, unchanged.
- `aChildIsRefusedASiblingsDocument()` — false.
- `aGrandchildIsRefusedItsAncestorsDocument()` — false.
- `aContainerStillReadsItsDescendantsDocument()` — true, V223's capability
  unbroken.
- `anOrdinaryHolderIsUnaffectedByTheNewArm()` — `revisionReadable(HOLDER, document)`
  and `revisionReadable(STRANGER, document)` keep the values slice two asserts.
- `aRevokedPrincipalLosesTheArm()` — delete the `contained_principals` row and the
  child's own document read turns false, so the arm is keyed on a live principal
  and not on the UUID's shape.
- `aNonUuidActorDoesNotCrashThePredicate()` — `contained_knowledge_actor('holder')`
  returns false rather than raising an invalid-input error, because every
  knowledge read on the server now evaluates it.
- `aChildsDependencyClosureLeavingItsSubtreeRefuses()` — a child document deriving
  from a revision in its container's home: `knowledge_dependencies_readable` is
  false for the child even though the derived revision alone is readable.
- `theDiscoveryPathAdmitsTheChildsOwnScopeAndNotGeneralMembership()` — select from
  `knowledge_read_authority(childPrincipal)`: the child's own `scope_id` is in the
  returned set, and `general_member` is **false** on every returned row. This is
  the discovery half of the general-arm closure: `knowledge_read_authority`
  computes `general_member` from `information_global_read_member($1)` and
  `knowledge_readable_candidates` (V198) joins against it, so a contained actor
  admitted with `general_member` true would discover generally-readable rows even
  with the exact-read disjunct closed. Widening only the exact predicate leaves
  discovery blind; widening both without pinning `general_member` false opens the
  arm in the other place. Both predicates, one test each.

- [ ] **Step 2: Run it to verify every assertion fails closed**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedActorReadSchemaTest'`
Expected: FAIL. `contained_knowledge_actor` does not exist, and the reads that
should now be admitted return false.

- [ ] **Step 3: Write V224**

Create `contained_knowledge_actor` and `CREATE OR REPLACE` the two predicates
`knowledge_revision_readable` and `knowledge_read_authority` that consult the
baseline. `contained_knowledge_actor` must not raise on a non-UUID actor — guard
the cast, since the predicate is now on every knowledge read. Comment why the
general arm is closed for these actors, naming the anti-goal, because a later
reader will otherwise simplify the disjunct back.

- [ ] **Step 4: Run the test to verify it passes**

Run: the Step 2 command. Expected: PASS.

- [ ] **Step 5: Flip the pinned refusal slice two left behind**

In `ContainedKnowledgeReadSchemaTest.anAncestorReadsADescendantsDocumentWithinThatDescendantsBoundary`,
`assertFalse(revisionReadable(childPrincipal, document))` is now wrong. Change it
to `assertTrue` and replace the comment block above it — which explains the
`information_global_read_member` refusal — with one sentence saying V224 admits
it and pointing at `ContainedActorReadSchemaTest` for the direction and the
general-arm closure. Leave every other assertion in that method untouched.

- [ ] **Step 6: Run the slice-one and slice-two schema suites to verify nothing regressed**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.Contained*' --tests 'io.aeyer.plowshare.server.applications.Contained*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration/V224__contained_actor_reads.sql plowshare-server/src/test/java/io/aeyer/plowshare/server/access/
git commit -m "Admit a contained principal to its own reads without the general arm"
```

---

### Task 2: The child's own writes

**Files:**
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedActorWriteSchemaTest.java`
- Modify: whichever gate Step 1 names — expected `plowshare-server/src/main/resources/db/migration/V224__contained_actor_reads.sql`
  if the gate is SQL, otherwise the write-authority service the test identifies.

**Interfaces:**
- Consumes: Task 1's `contained_knowledge_actor`; `information_write_gates` (V83)
  and the write authority V157 establishes.
- Produces: no new contract if the gate is SQL. If the refusal is above SQL, this
  task produces the narrow interface the fix needs and names it here before
  Task 3 consumes it.

**Why this task starts by identifying the gate rather than fixing one.** ADR 0042
records that a child has its own writable knowledge and memory home and that child
writes are not refused — a decision, not a demonstration. Nothing has ever
written as a contained principal, so which gate refuses first is unknown, and
slice two's Task 1 established the house answer to that: pin the refusal with a
test that identifies the gate instead of guessing at it. Do not widen anything
until Step 2 has named what refuses.

- [ ] **Step 1: Write the characterization test**

New `@Tag("full-db")` class asserting, for the same fixture as Task 1:

- `aChildWritesADocumentInItsOwnHome()` — the write the spec promises.
- `aChildCannotWriteInItsContainersHome()` — refused.
- `aChildCannotWriteInASiblingsHome()` — refused.
- `aChildCannotPublishGenerallyReadable()` — a child-authored policy with
  audience `GLOBAL_READ` is refused, since the child's ceiling pins `globalRead`
  false and slice two fenced general-read *publication* against a contained home.

- [ ] **Step 2: Run it and record what refuses**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedActorWriteSchemaTest'`
Write the failing gate's name and location into the test's class Javadoc before
changing any code. If the first assertion already passes, say so there: the write
side needs no widening and this task is the three refusals plus that note.

- [ ] **Step 3: Admit the first assertion, if and only if Step 2 found a gate**

Widen exactly the gate Step 2 named, for a `contained_knowledge_actor` writing
within its own home and nothing else. If the gate is in V224, extend that file —
it is unshipped until this branch merges. If a second gate appears behind the
first, repeat Steps 2 and 3 rather than widening both at once.

- [ ] **Step 4: Run the test and the Task 1 suites**

Run: the Step 2 command, then
`./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.Contained*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedActorWriteSchemaTest.java plowshare-server/src/main/resources/db/migration/
git commit -m "Let a contained principal write its own home, and nothing else"
```

---

### Task 3: `application.child.run`

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ContainedApplicationFrames.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedRunTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ContainedApplicationFramesTest.java` (extend)

**Interfaces:**
- Consumes: `OperationAuthority.contain` and the two live-authority gates ADR 0042
  describes; `ContainedApplicationRepository.find(UUID)`; `ContainedPrincipals.issue`;
  the container-only authority path `ceiling(String, String, UUID, Integer)` already
  uses; `Runs.start(Runs.Ask, Runs.Shown)` and `Speaker.harness(String)`.
- Produces:
  - `record Run(String conversation, UUID child, String agent, String task, Integer maxTurns)`
    on `ContainedApplications`, with the same validation idiom `Create` uses:
    non-blank `conversation` under 1024 chars, non-null `child`, non-blank `agent`,
    non-null `task`, and `maxTurns` null-or-positive.
  - `record Running(UUID child, String job, String conversation, String agent)` —
    a boundary DTO of plain wire values, no `ScopeId`.
  - `Running run(String account, Run request)` on `ContainedApplications`.

**Container-only, and why it differs from the reads.** `trajectory` and
`resources` use the ancestor gate, so a grandparent reads through the chain.
`run` must use the *direct container* rule `create` and `ceiling` use instead.
Starting work spends the child's allowance and occupies the container's breadth
accounting, both of which belong to the direct container; a grandparent raising
work in a grandchild would spend under a ceiling the intermediate container set
and never agreed to lend. State this in the method's Javadoc beside the
comparison to `trajectory`, because the asymmetry will otherwise read as an
oversight — this is the same kind of deliberate asymmetry ADR 0042 records
between `trajectory` and `resources` on destroyed children.

**Resolving the child's home.** `project(String)` refuses a contained scope by
design, so the run's project must come from the child's stored row through
`containedHome`, never from a name in the request. The request names the child by
UUID only; there is no field through which a caller could name a project.

**The executor.** Build the `Runs.Ask` with `Speaker.harness("contained " + child)`
and the contained principal as the caller identity, so the run's conversation
envelope records the child as the executor and its spend lands on the child's own
allowance. The container's handle must not appear as the run's caller; a test
asserts that.

- [ ] **Step 1: Write the failing tests**

`ContainedRunTest`, mocked repositories in the idiom of
`ContainedApplicationCreationTest`:

- `aContainerRunsItsOwnChild()` — returns a `Running` naming the child, a job id
  and the conversation; the recorded `Runs.Ask` carries the child's contained
  project, `Speaker.Kind.HARNESS` with source `contained <uuid>`, and the
  contained principal as caller.
- `aGrandparentIsRefused()` — `CallerFault`; Review Focus 4. Assert no
  `Runs.start` call was made.
- `aSiblingIsRefused()` — `CallerFault`; Review Focus 5.
- `aChildRunningItsContainerIsRefused()` — `CallerFault`.
- `aDestroyedChildIsRefused()` — `CallerFault`, as `resources` refuses one.
- `aChildWithAZeroCeilingIsRefused()` — a child that cannot make a call is not
  started, and the refusal names the ceiling.
- `anUnconfiguredContainmentRefuses()` — `CallerFault` before any lookup.
- `anAgentTheChildDoesNotDeclareIsRefused()` — the agent name is resolved against
  the *child's* own package and nothing else, so a container cannot name its own
  agent and have it run inside the child.
- `theBreadthBoundCountsRunningChildren()` — with `maxLiveDescendants` reached,
  a run of an already-live child still succeeds, because breadth bounds live
  children and not concurrent runs. Pin the chosen meaning either way; a reader
  will assume the other one.
- `aRunningChildCreatingAChildIsStillBounded()` — a child started by this
  operation calls `create` itself: the depth bound refuses at `maxDepth` and the
  breadth bound refuses at `maxLiveDescendants`, from the server's configuration
  and not from any argument the running child supplies. The spec requires the
  bounds to refuse rather than degrade, and execution is the first path on which
  a child could reach `create` at all.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedRunTest'`
Expected: FAIL, `run` is not defined.

- [ ] **Step 3: Implement `run` in `SourceContainedApplications`**

Order is the contract, and it is `ceiling`'s order, not `trajectory`'s: prove the
caller's live authority, resolve the child row, require the caller's scope to *be*
the child's recorded container, refuse a destroyed child, refuse a zero ceiling,
resolve the home through `containedHome`, resolve the agent against the child's
package, and only then call `Runs.start`. Nothing before the authority proof may
read the child's package or touch its source area.

- [ ] **Step 4: Declare the frame and the operation**

Add `APPLICATION_CHILD_RUN = "application.child.run"` to `FrameTypes` beside the
other six, and a handler arm in `ContainedApplicationFrames` converting the
payload to `Run` and answering `Code.ACCEPTED` with `Running` — `ACCEPTED` for
`AgentRunHandler`'s reason: a run is minutes, and `OK` would tell a client its
work had finished. Extend `ContainedApplicationFramesTest` with the sendability
and correlation assertions the other six have, including that a reply about a
different child than the one asked of is refused.

- [ ] **Step 5: Run the tests and format**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedRunTest' --tests 'io.aeyer.plowshare.server.ws.ContainedApplicationFramesTest'`
then `./gradlew format && ./gradlew formatCheck`.
Expected: PASS, clean.

- [ ] **Step 6: Commit**

```bash
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ plowshare-server/src/test/java/io/aeyer/plowshare/server/
git commit -m "Add application.child.run behind the container-only gate"
```

---

### Task 3.5: Let a containment hop record its lineage

**Authorized by the user on 3 November 2026**, after Task 3 found two shipped
constraints behind the conversation door. It was not in this plan's original
scope; this section is the plan catching up with the work, not a prediction.

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V225__contained_operation_lineage.sql`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedOperationLineageSchemaTest.java`
- Modify: `docs/decisions/0042-nested-application-containment.md`

**Interfaces:**
- Consumes: `operation_parent_valid` (V162:35-36) and `operation_conversation_valid`
  (V162:62) as shipped; `authority_scopes(id, kind, project_id, container)` from
  V216 with `container` frozen by V217; `contained_scope_ancestors` from V223;
  Task 3's `openContained` and `OperationAuthority.bind`.
- Produces: a persisted containment hop, so `contain` records lineage and
  `Runs` can stop being mocked.

**The shape, and why it is not a new invention.** ADR 0042's "two live-authority
gates" already made exactly this replacement in Java: for a contained admission,
`require`'s demand for an ancestor with an identical scope *and* home is replaced
by the containment edge itself — "the child's own stored row must name this
ancestor's scope as its container". `operation_parent_valid` is the SQL
counterpart of the same demand and has never been given the same treatment,
because no hop was ever persisted to fail it. V225 applies the documented
replacement at the SQL level. A reviewer should hold the migration to that
claim: anything broader than the Java gate already permits is out of scope.

**What must still refuse.** The constraint being changed is the substrate's
no-escalation invariant, so the tests that matter are the refusals:
- a contained child admission whose scope's `container` is **not** the parent
  admission's scope — a grandparent, a sibling subtree, an unrelated scope;
- a child admission whose actions are not a subset of the parent's;
- a **non**-contained child admission, which keeps the exact scope/project
  equality unchanged, asserted byte-for-byte;
- a chain longer than the configured depth;
- a contained admission with no parent edge at all.

- [ ] **Step 1: Write the failing schema test, including the persisted hop**

New `@Tag("full-db")` class. The central test is `aContainmentHopRecordsItsLineage` —
a real `contain` whose lineage persists, **with `OperationRecords` unmocked**.
That mock is why this defect survived two shipped slices, so the regression test
is the un-mocked path, not a tightened assertion. Add every refusal above, each
with a positive control proving the same shape is admitted for a legitimate hop.

- [ ] **Step 2: Run it to verify it fails closed**

Expected: `ERROR: child authority exceeds retained parent` on the hop, and the
refusals passing already.

- [ ] **Step 3: Write V225 for `operation_parent_valid`**

`CREATE OR REPLACE` with the signature unchanged, reproduced from V162 with the
one documented edit and no others, in this repo's "reproduced from VNNN with
these edits and no others" header convention. Say in the comment that the arm is
the SQL counterpart of ADR 0042's gate-two replacement, and that it is the
no-escalation invariant, so a later reader does not relax it further.

- [ ] **Step 4: Resolve gate six — the contained turn's `Origin`**

`operation_conversation_valid` (V162:62) requires the row's agent to match the
envelope's, while V17:212 forbids an agent on the `Origin.TURN` row. A contained
run is harness-initiated agent work and not a person's turn, so prefer an
existing non-`TURN` origin that already permits an agent. **Add a new `Origin`
kind only if none fits, and report before doing so** — an origin kind is a
shipped enum with contract reach.

- [ ] **Step 5: Un-mock `Runs` and assert the envelope**

Task 3 wrote these assertions and gate five prevented them running: a real run
starts in a contained scope, its envelope's `budget` is the child's own allowance
and its admission account is the container's. This is the first evidence the
slice's goal is met.

- [ ] **Step 6: Amend ADR 0042 for the constraint and the corrected claim**

Two things, and the second is about shipped slices rather than this one:
the V225 decision and why it is the documented replacement rather than a
widening; and the correction that the "two live-authority gates" section's
liveness claim was resting on mocked persistence, since no hop could be recorded.
State it plainly as a defect that shipped, not as a nuance.

- [ ] **Step 7: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration/V225__contained_operation_lineage.sql plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ plowshare-server/src/main/java/ docs/decisions/0042-nested-application-containment.md
git commit -m "Let a containment hop record its lineage"
```

---

### Task 4: What a running child may reach

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedToolAccess.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedDeclarations.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedRuntimeReachTest.java`

**Interfaces:**
- Consumes: Task 3's `run`; `AgentRegistry.AGENT_RUN`; `ApplicationToolScopes`;
  `ContainedDeclarations.read` and its subset tests against the container's own
  declarations; the personal-space provisioning ADR 0042's addressability
  inventory covers.
- Produces: no new public contract. Produces the refusals Task 6 cites.

**What this task is for.** Slice one reduced or refused every surface a child's
*source* can declare, checked at creation. A running child reaches surfaces at
*runtime* that no declaration mentions: the tools its agent calls, the
definitions `agent_run` can name, and whatever ordinary project creation
provisions. ADR 0042's own largest limitation is that nothing executed, so none
of this was reachable to test. It is now.

- [ ] **Step 1: Write the failing tests**

- `aRunningChildCannotDelegateToAnAncestorsDefinition()` — the child's agent calls
  `agent_run` naming a definition in its container's package: refused, and the
  refusal is a resolution failure rather than a permission denial, because the
  name is not resolvable from inside the subtree. Review Focus 2.
- `aRunningChildCannotDelegateToASiblingsDefinition()` — refused.
- `aRunningChildDelegatesWithinItsOwnSubtree()` — a child's own grandchild
  definition resolves, and the delegated run's execution identity is the
  grandchild's principal, not the child's and not the container's, so ADR 0011's
  downward inheritance does not carry an ancestor identity across a hop.
- `aRunningChildsToolCallResolvesOnlyItsOwnBindings()` — a tool binding the
  container holds and the child did not declare is not callable by the child.
- `aRunningChildAcquiresNoWorkingTreeOrGitHub()` — the child's project has
  neither, so a child does not silently gain the union/git provisioning ordinary
  project creation performs.
- `aRunningChildCannotDefineASchedule()` — refused, as ADR 0042 refuses a
  declared one; a runtime path must not become the way in.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedRuntimeReachTest'`
Expected: FAIL. Record in the test class's Javadoc which assertions already pass
before touching code — a surface slice one's structural confinement already closes
needs a test, not a change, and this task's value is partly in distinguishing the
two.

- [ ] **Step 3: Close only the surfaces Step 2 found open**

For each failing assertion, fence at resolution rather than by a check at the call
site, per the spec's anti-goal: a child's reach should fall out of what its scope
can resolve. A check added at a tool call site is the wrong answer and will be
rejected in review.

- [ ] **Step 4: Run the test and the full contained suite, then format**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.Contained*' --tests 'io.aeyer.plowshare.server.access.Contained*'`
then `./gradlew format && ./gradlew formatCheck`.
Expected: PASS, clean.

- [ ] **Step 5: Commit**

```bash
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/ plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedRuntimeReachTest.java
git commit -m "Fence what a running child can resolve"
```

---

### Task 5: Cancellation, revocation and destruction under live work

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceContainedApplications.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedCancellationTest.java`
- Modify: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedDestructionTest.java`

**Interfaces:**
- Consumes: Task 3's `Running`; the existing `destroy` and its `Destroyed` DTO;
  `ContainedSourceCollector`; the job lifecycle `job.status` reports.
- Produces: `void cancel(String account, String conversation, UUID child)` on
  `ContainedApplications` — container-only, idempotent, and cancelling nothing is
  not an error, in the idiom `ContainedPrincipals.revoke` already sets.

**The three ways work must stop.** The spec requires all three and none is
proved today: a container cancels a child's run explicitly; destroying a container
stops every descendant's work before collecting its source; and revoking a
container's authority mid-run stops descendant work without anyone asking. The
third is the one a reviewer should look at hardest, because it has no caller — the
run must notice that the admission it holds no longer resolves, which is the
recheck `OperationAuthority.Conversation` already documents as the caller's duty.

- [ ] **Step 1: Write the failing tests**

- `aContainerCancelsItsChildsRun()` — the job reaches a cancelled terminal state
  and no further turn is spent.
- `cancellingNothingIsNotAnError()` — a live child with no run.
- `aGrandparentCannotCancel()` and `aSiblingCannotCancel()` — `CallerFault`.
- `destroyingAContainerStopsDescendantRunsBeforeCollection()` — the ordering is
  the assertion: every descendant run is cancelled before any source area is
  removed, so no run reads a directory being collected. Review Focus 6.
- `aRevokedContainerStopsItsDescendantsWork()` — revoke the container's live grant
  mid-run; the run stops rather than completing, and the refusal names the
  admission rather than the child. Review Focus 3.
- `aDestroyedChildsTrajectorySurvives()` — unchanged from slice one: history
  survives destruction, so cancellation must not remove retained envelopes.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedCancellationTest'`
Expected: FAIL, `cancel` is not defined.

- [ ] **Step 3: Implement `cancel`, and the cascade in `destroy`**

`destroy` already collects deepest-first; cancellation runs over the same ordered
set before the first removal. Keep the existing `Destroyed` contract unchanged —
`collected` and `sources` still mean what ADR 0042 says they mean — and do not add
a cancellation count to it unless a test requires one, because `Destroyed` travels
through five SDKs.

- [ ] **Step 4: Declare the frame**

Add `application.child.cancel` to `FrameTypes` and a handler arm beside `run`,
with the same correlation refusal the other mutating operations carry.

- [ ] **Step 5: Run the tests and format**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.Contained*'`
then `./gradlew format && ./gradlew formatCheck`.
Expected: PASS, clean.

- [ ] **Step 6: Commit**

```bash
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/ plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/
git commit -m "Stop descendant work on cancel, destroy and revocation"
```

---

### Task 6: Regenerate the contracts, and re-audit the inventories against a running child

**Files:**
- Modify: the SDK catalog and capability manifest the owning script regenerates,
  as slice two's Task 4 did for `application.child.resources`
- Modify: `sdk/typescript/src/operations/contained-applications.test.ts` and the
  Python, Go and .NET round-trip tests beside it
- Modify: `docs/decisions/0042-nested-application-containment.md`

**Interfaces:**
- Consumes: Tasks 3 and 5's `run` and `cancel` frame declarations and their DTOs.
- Produces: the eight-operation contract in TypeScript/Node, Python, Go and .NET,
  and the amended ADR.

**Regenerate, never hand-edit.** Generated catalogs and schema files are produced
by their owning scripts. Slice two resolved generated-contract conflicts by
re-rendering rather than merging, and the same rule holds here. Update the SDK
operation-count pins and fixtures that slice two had to update for a seventh
operation; there are now eight.

- [ ] **Step 1: Declare the two operations in the catalog and regenerate**

Then confirm the generated TypeScript, Python, Go and .NET catalogs all carry
`application.child.run` and `application.child.cancel`, and that the count pins
and `test-support/contracts/ws-conversation-fixtures.json` agree.

- [ ] **Step 2: Write and run a per-language round-trip test for each new operation**

Request-side semantic validation (the child UUID, the agent name, the task) stays
where slice two put it: the TypeScript/Node request gate. Reply correlation —
refusing a `run` or `cancel` answer about a different child than the one asked of —
is implemented in TypeScript, Python, Go and .NET, as it is for `ceiling` and
`destroy`.

Run: `./gradlew sdkCheck`
**Expected: this will fail at `:sdkBuild` if the machine has no `dotnet`.** ADR
0042 records that exact failure for slice one and slice two. If it fails that way
again, run the TypeScript suite and the Python and Go round-trips directly, and
record in Step 4 that the .NET round-trip was written and never compiled. Do not
describe the .NET path as covered.

- [ ] **Step 3: Re-audit every inventory row against a running child**

ADR 0042's addressability inventory, declared-surface inventory and exhaustiveness
inventory are all proved against inserted rows. For each row, decide whether
execution changes its answer, and give every row whose answer execution *could*
have changed a test that names the live child. Rows this slice's Tasks 1, 2 and 4
touched are re-decided with their new reason, in the form slice two's Task 5 used.

- [ ] **Step 4: Amend ADR 0042**

Amend, do not rewrite. State for slice three:
- The baseline read arm, why it is not general-read membership, and that the
  general arm is closed for contained actors.
- The write-side answer Task 2 found, including "no gate existed" if that is what
  it found.
- `run` and `cancel`, the container-only rule, and why it differs from the
  ancestor rule the reads use.
- That "Nothing executes a contained child" is **no longer a limitation**, and
  what the inventories now rest on instead.
- The limitations that remain, unsoftened: the existence oracle, still live and
  still a contract change; `information_navigation_reference_visible`, still
  fenced by a default rather than a structure; planner cost for the widened
  reads, still argued and not measured; the Java SDK, still exposing none of
  these operations; `bytes` and `child`, still unenforced in three SDKs as a
  generator gap; and whatever the .NET toolchain could not verify in Step 2.
- A new limitation if this slice leaves one, named as plainly as the others.
  No streaming trajectory is a scope decision and belongs in the goal statement
  rather than here.

- [ ] **Step 5: Run the repository-wide check**

Run: `./gradlew check`, then
`./gradlew :plowshare-server:test -PfullDb --tests '*Contained*'`
Expected: PASS. `--tests` selects within a test task and is not a selector on the
aggregate `check`, so the database pass is spelled against `:plowshare-server:test`.
Its database scope is justified by this slice's own migration and predicate
changes; do not run the whole database suite.

- [ ] **Step 6: Commit**

```bash
git add sdk/ test-support/ docs/decisions/0042-nested-application-containment.md
git commit -m "Expose run and cancel in every SDK, and re-audit against a live child"
```

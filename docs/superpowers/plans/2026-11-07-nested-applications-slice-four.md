# Nested Applications Slice Four: The Contained Information Actor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the contained principal the identity the information system
resolves for a run inside a contained Application — acting as the child, attributed
to its container — and close or deliberately open every read and write gate that
identity now reaches, so that a contained child reads and writes its own documents
and its own memories and nothing else.

**Architecture:** Three facts make this one slice rather than a one-line change.
First, `InformationAccess.contained(principal, container, project)` already exists
and already builds the two axes differing, with `includeGlobal` false, and its own
Javadoc says no production path calls it — so the wiring is a branch, not a design.
Second, twelve write sites in `JdbcInformationCatalogueRepository` carry
`requireUncontainedActor` and five sibling repositories that also take an
`InformationContext` and also write carry nothing; the only reason that is
survivable today is that nothing presents a contained actor, which is the exact
fact this slice removes. So the guards go in *before* the branch is turned on, and
the ordering of the tasks below is the safety argument. Third, V229 widened five
knowledge functions and did not touch `information_global_read_member`, which
`MemoryStore` requires on its read path alongside an `authority_accounts` row the
child does not have — so memory is closed for a contained actor by two predicates
V229 never reached, and opening it is its own gate with its own authority argument
rather than a consequence of the wiring.

**What this goal does not include.** Not the namespace itself. Copy-on-write
references, materialization, tombstones, the `M`/`+`/`-` change report and the
parent's write into a child's space are the next plan; see **The proposed cut**
below. Not the lifecycle (`ready`, `stop`, `kill`, and `live` becoming real written
state). Not the in-model `application.child.*` tools, nor the escalated content
read. Not the mutable staged source area, which the design names as the part most
likely to need its own slice. Delivering this slice leaves a contained child with a
confined world of its own that is largely empty, because nothing has seeded it yet
— that is the intended end state here, and it is what makes the namespace plan
safe to build.

No contract added here may name harnesses, probes, candidates or improvement.

**Tech Stack:** Java 21 / Spring Boot, Spring JDBC, Flyway, PostgreSQL; JUnit 5
with Testcontainers for the gate assertions.

**Spec:** [docs/superpowers/specs/2026-11-07-contained-namespaces-design.md](../specs/2026-11-07-contained-namespaces-design.md)

**Prior slices:** [ADR 0042](../../decisions/0042-nested-application-containment.md)
is the authorizing record. Before Task 1, read its "Known limitations this
inventory does not close" and the addressability inventory. Two of its statements
are amended by this slice and the amendment is part of the work: its claim that a
contained principal "reads its own documents and its own memories" is correct for
documents and over-claimed for memories (Task 4), and its "a running child has no
door of its own" brake is *not* lifted here — it is lifted in the driver slice, so
nothing in this plan may issue a contained principal a handle or a credential.

---

## Global Constraints

Every task's requirements implicitly include this section. Values are copied from
the spec and from the repository's standing rules.

- **Repositories own persistence.** Nothing outside a repository, store or database
  configuration touches `JdbcTemplate`. Actor resolution lives behind a typed
  interface.
- **Typed DTOs at boundaries.** No raw JSON type and no `Map<String, Object>`
  crosses into an internal contract.
- **New Flyway migrations only; a shipped migration is never edited.** The newest
  migration on both `main` and this branch is `V230__contained_operation_lineage.sql`,
  so this slice allocates from `V231`. **Assume the numbering collides.** Three
  renumberings have already happened on this line of work; re-read the migration
  directory immediately before adding a file and number above `main`'s actual tail.
- **Database tests sit behind `@Tag("full-db")` and `-PfullDb`.** The gates this
  slice touches are SQL functions, and their refusals cannot be proved any other
  way — that is the explicit database-specific reason. Keep the selection narrow
  with `--tests`; never run the full database suite for this work.
- **Characterize before widening.** The house convention in
  `ContainedActorWriteSchemaTest` is to assert the current refusal first, watch it
  fail or pass, and record in the test's Javadoc which gate refused and in what
  order. Every gate task below follows it. A widening with no recorded
  characterization is unfinished.
- **A contained principal holds no credential material.** No row in `admins`,
  `authority_accounts` or `service_tokens`, and no handle. Eleven surfaces in ADR
  0042's inventory are closed by that fact alone. Nothing in this slice may add
  one.
- **Containment resolves upward from the descendant** through
  `contained_scope_ancestors`. Confinement is the direction of the walk, not a
  filter applied after it.
- **Writes stay container-only where they carry consequence.** `SET_AUDIENCE` is
  refused for a contained actor and stays refused; nothing here may become the
  route by which a contained home is published outward.
- **No hard-coded deployment values** — no path, host, port or account identity in
  runtime code.
- **Google Java Style**, applied with `./gradlew format`, including tests. Run
  `./gradlew formatCheck` after any Java edit.

---

## The proposed cut

The design states it is more than one slice and asks the builder to propose the
cut. This is it. Each plan produces working, testable software and the order is a
dependency order, not a preference.

| Plan | Scope | Depends on |
|---|---|---|
| **A — this plan** | The contained information actor: the two-axis carrier, the five unguarded sibling repositories, the navigation fence, the memory gate, the wiring, the blob collection safety. | slices 1–3 |
| **B — the namespace** | Reference and tombstone representation, copy-on-write resolution and materialization on the information and memory doors, the `M`/`+`/`-` change report, the parent's container-only write into a child's space, chained resolution at depth, per-operation consistency, and the tool-argument to `Resource`-reference mapping the spec records as unpinned for three slices. | A |
| **C — the lifecycle** | `ready`, `stop`, `kill`; `contained_applications.live` becoming real written state; `run`, `trajectory`, `resources` and `cancel` re-decided against it; namespace destruction separated from child destruction; the `CONTAINED` retention. | A (independent of B) |
| **D — the driver** | In-model `application.child.*` tools reaching the operations through the run's own authority; the named-discipline content read and its separately-authorized escalation on the existing approval machinery. | B, C |
| **E — the file door** | Staged source made mutable: the `writableAreas` refusal, `WorkspaceApplicationPolicy:53`, ADR 0005's immutable revisions and budgets, revision identity and the source-directory caches, and the live-child revision-change refusal. | B |

B and C are independent of each other and can run concurrently. E is the one the
design singles out as likely to need its own slice, and this cut takes that
advice.

---

## Review Focus

Five input classes the spec implies that no task's own happy path exercises, most
likely to bite first. Each has a test pinned to the task that owns the code.

1. **An ordinary, uncontained run regressing.** The two-axis carrier touches the
   binding path every agent run uses. `InformationContext.attribution()` falls back
   to `account`, so a carrier that sets both axes to the same handle must leave an
   ordinary run's resolved context equal to today's, field for field. Pinned to Task 1.
2. **A contained actor reaching a sibling repository's write method.** Five
   repositories take an `InformationContext`, write, and carry no guard. Whether
   their service layer would refuse is unverified. Pinned to Task 2.
3. **An ancestor's own reference visibility into its descendant.**
   `information_navigation_reference_visible` is the *only* admission arm at
   `JdbcInformationNavigationRepository.referenceVisible`, so adding the contained
   conjunct naively refuses the ancestor read that works today. Pinned to Task 3.
4. **A contained actor's memory read and write.** Closed today by
   `information_global_read_member` and the `authority_accounts` join, neither of
   which V229 reached, plus a `server_admin` `admins` row on the write path. The
   failure mode is opening it by accident while widening something adjacent.
   Pinned to Task 4.
5. **A child's unclaimed binary output swept before its parent collects it.** V225
   keys `binary_blob_claims` on a TEXT scope with an `unclaimed_at` collection
   index, deliberately so bytes survive project deletion. A sweep that takes a
   child's output is the spec's "silent loss" anti-goal caused by the platform
   rather than the parent. Pinned to Task 6.

---

### Task 1: The two-axis run actor, inert

Carry both axes from a run into the information and memory tool bindings instead of
the single `String callerHandle` they take today, with no behaviour change for any
existing run. Nothing presents a contained actor yet; this task only makes it
possible to.

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationActor.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java` (add the `forRun` overload beside the existing one at `:110`)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java:~4425-4445` (the information tool and `MemoryTools.Write` bindings)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/InformationTool.java:91-101, 355-370`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationActorTest.java`

**Interfaces:**
- Consumes: `InformationContext.acting()`, `InformationContext.attribution()`, `InformationAccess.forRun(String, Home)`.
- Produces:
  - `record InformationActor(String acting, String attribution)` with a static
    `InformationActor of(String handle)` for the ordinary case (both axes the same
    handle) and a static `InformationActor contained(String principal, String container)`.
    The constructor rejects a null or blank `acting`; a null `attribution` is
    rejected, because the fallback belongs to `InformationContext` and a second
    place that means "same as acting" is a place the two can disagree.
  - `InformationAccess.forRun(InformationActor actor, Home home)` — the existing
    single-argument `forRun(String, Home)` stays and delegates to it with
    `InformationActor.of(account)`, so no caller outside this task changes.
  - `InformationTool.forRun(InformationAccess, InformationJobs, InformationActor, String log)`
    beside the existing `String owner` overload.

- [ ] **Step 1: Write the failing tests**

```java
// InformationActorTest
@Test void ofSetsBothAxesToTheSameHandle()            // of("alice").acting()=="alice" && .attribution()=="alice"
@Test void containedSeparatesTheAxes()                 // contained(uuid, "parent").acting()==uuid, .attribution()=="parent"
@Test void blankActingIsRejected()                     // of("") and of(null) throw IllegalArgumentException
@Test void nullAttributionIsRejected()                 // new InformationActor("alice", null) throws IllegalArgumentException

// Review Focus 1 — the regression this task's happy path does not exercise.
@Test void anOrdinaryRunResolvesExactlyAsItDidBefore()
// Resolve the same (account, home) through forRun(String, Home) and
// forRun(InformationActor.of(account), Home) and assert the two
// InformationContext values are equal, including acting(), attribution(),
// selection scope, selection project and includeGlobal.
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.information.InformationActorTest'`
Expected: FAIL — `InformationActor` does not exist.

- [ ] **Step 3: Implement `InformationActor` and the `forRun` overloads**

The record and its two factories. `InformationAccess.forRun(InformationActor, Home)`
is the existing body with `actor.acting()` where `account` was and
`.accountedTo(actor.attribution())` applied when the two axes differ; when they are
equal it must not call `accountedTo` at all, so an ordinary context stays
byte-identical rather than merely equivalent.

- [ ] **Step 4: Thread the carrier through the two tool bindings**

`JobRuntime` builds `InformationActor.of(callerHandle)` at the single site that
feeds both the information tool and `MemoryTools.Write`, and passes it where
`callerHandle` went. `InformationTool` holds an `InformationActor` instead of
`String owner` and calls `access.forRun(actor, home)`; `inputs.requireLog(log, ...)`
keeps taking the **attribution** handle, because a log is owned by the account that
is accountable for it. Leave the `String owner` overloads in place and deprecated-
by-comment rather than removed, so no unrelated caller is touched in this task.

- [ ] **Step 5: Run the tests and the existing agent suites**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.information.*' --tests 'io.aeyer.plowshare.server.agents.*'`
Expected: PASS, with no existing test modified. A changed existing assertion in this
task means the carrier is not inert; fix the carrier, not the assertion.

- [ ] **Step 6: Format and commit**

```bash
./gradlew format && ./gradlew formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationActor.java plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/InformationTool.java plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationActorTest.java
git commit -m "Carry both information axes from a run, with no change for an uncontained one"
```

---

### Task 2: Close the five unguarded sibling repositories

`JdbcInformationCatalogueRepository` carries `requireUncontainedActor` at twelve
write sites. Five sibling repositories take an `InformationContext`, write, and
carry nothing. Decide each one and prove it, while nothing yet presents a contained
actor.

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationNavigationRepository.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProjectionRepository.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcAcquisitionRepository.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationContentRepository.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationInterchangeRepository.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java:1415` (extract the guard)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/information/ContainedActorWriteReachDatabaseTest.java`

**Interfaces:**
- Consumes: `contained_knowledge_actor(TEXT)` (V229:30), `InformationContext.acting()`.
- Produces: `ContainedActorGuard`, a package-private collaborator in
  `io.aeyer.plowshare.server.information` with
  `void requireUncontained(InformationContext context, String operation)`, taking a
  `JdbcTemplate` by constructor injection. It raises `CallerFault("A confined
  Application may not " + operation)` — the existing message, unchanged, because its
  wording is already asserted. The twelve existing call sites in the catalogue
  repository delegate to it; the guard's reasoning Javadoc at `:1403-1414` moves with
  it, including the sentence that it must not be weakened to a comparison of the two
  axes.

- [ ] **Step 1: Write the failing characterization test**

```java
// ContainedActorWriteReachDatabaseTest — @Tag("full-db"), @Testcontainers,
// pgvector/pgvector:pg16, Flyway-migrated, in the house shape of
// ContainedActorWriteSchemaTest.
//
// Review Focus 2. One test per repository, each driving the repository directly
// with an InformationContext whose acting() is a bare contained-principal UUID
// and whose attribution() is the container's handle.
@Test void navigationRefusesAContainedActorsWrite()
@Test void projectionRefusesAContainedActorsWrite()
@Test void acquisitionRefusesAContainedActorsWrite()
@Test void contentRefusesAContainedActorsWrite()
@Test void interchangeRefusesAContainedActorsWrite()
// Each asserts CallerFault with the message "A confined Application may not ".
//
// And the pair that keeps the guard honest:
@Test void anUncontainedActorsWriteIsUnaffected()      // one write per repository, succeeds
```

- [ ] **Step 2: Run the characterization and record what it says**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.information.ContainedActorWriteReachDatabaseTest'`
Expected: the five refusal tests FAIL — a contained actor's write is admitted, or
fails with some other fault. Record in the test's Javadoc, per repository, what
actually happened: admitted, or refused and by which predicate. **That record is the
deliverable of this step**; a repository that already refuses for a sound reason
needs no guard, and the Javadoc is where that is argued.

- [ ] **Step 3: Extract `ContainedActorGuard` and repoint the twelve call sites**

Behaviour-preserving. The catalogue repository's tests must pass unchanged.

- [ ] **Step 4: Guard each repository the characterization showed admitting a write**

One `requireUncontained` call at the top of each admitting write method, naming the
operation in the same voice as the existing twelve ("link a document into another
project"). A repository the characterization showed already refusing gets a Javadoc
sentence naming the refusing predicate instead of a guard — a second refusal in
front of a sound one is a second thing to keep in step.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.information.ContainedActorWriteReachDatabaseTest' --tests 'io.aeyer.plowshare.server.information.*Catalogue*'`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
./gradlew format && ./gradlew formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/information plowshare-server/src/test/java/io/aeyer/plowshare/server/information/ContainedActorWriteReachDatabaseTest.java
git commit -m "Guard every information write a contained actor can reach"
```

---

### Task 3: The navigation reference-visibility fence

`information_navigation_reference_visible` carries the unextended `RESTRICTED`
fence and is the only admission arm at
`JdbcInformationNavigationRepository.referenceVisible`. A contained actor must see
references inside its own space; an ancestor must keep the reference visibility into
its descendant that works today. Those two are the whole task.

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V231__contained_navigation_reference.sql` (renumber above `main`'s tail at the moment of writing)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationNavigationRepository.java` (only if the Java-side selection needs the actor; prefer SQL)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedNavigationReferenceSchemaTest.java`

**Interfaces:**
- Consumes: `contained_knowledge_actor(TEXT)`, `contained_scope_ancestors`,
  `information_navigation_reference_visible`.
- Produces: `information_navigation_reference_visible` replaced, with one added
  disjunct keyed on `contained_knowledge_actor` and the actor's own scope, in the
  shape V229's read arms use. No other function is touched.

- [ ] **Step 1: Write the failing characterization test**

```java
// @Tag("full-db"), @Testcontainers — the house shape.
@Test void aContainedActorSeesAReferenceInsideItsOwnSpace()
@Test void aContainedActorDoesNotSeeASiblingsReference()
// Review Focus 3 — the one that breaks if the conjunct is added naively:
@Test void anAncestorStillSeesAReferenceIntoItsDescendant()
@Test void anUnrelatedAccountSeesNeither()
```

- [ ] **Step 2: Run the characterization and record what it says**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedNavigationReferenceSchemaTest'`
Expected: the first test FAILS (a contained actor is refused) and
`anAncestorStillSeesAReferenceIntoItsDescendant` PASSES. Record both in the test's
Javadoc — the second passing *before* the change is what makes it a regression test
afterwards.

- [ ] **Step 3: Write the migration**

A `CREATE OR REPLACE FUNCTION` adding the disjunct. The file's header comment states
why a disjunct and not a conjunct: this is the only admission arm, so a conjunct
would narrow every existing caller. Nothing in it widens `SET_AUDIENCE` and nothing
reaches `information_global_read_member`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedNavigationReferenceSchemaTest' --tests 'io.aeyer.plowshare.server.access.ContainedActorReadSchemaTest'`
Expected: PASS, including the previously-passing ancestor test.

- [ ] **Step 5: Commit**

```bash
git add plowshare-server/src/main/resources/db/migration plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedNavigationReferenceSchemaTest.java
git commit -m "Admit a contained actor's own reference visibility without narrowing an ancestor's"
```

---

### Task 4: The memory door's own gate

ADR 0042 says a contained principal "reads its own documents and its own memories".
That is correct for documents and over-claimed for memories: `MemoryStore`'s read
path requires `authority_accounts.legacy_handle = ?` **and**
`information_global_read_member(?)`, and its write path additionally requires an
`admins` row with `server_admin`. A contained principal has neither row, and V229
reached neither predicate. Open the door deliberately, and amend the record.

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V232__contained_memory_reads.sql`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java` (read path and write path predicates)
- Modify: `docs/decisions/0042-nested-application-containment.md` (amend the over-claim, in that record's own amendment style)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ContainedMemorySchemaTest.java`

**Interfaces:**
- Consumes: `contained_knowledge_actor(TEXT)`, `contained_scope_ancestors`,
  `InformationActor` from Task 1.
- Produces: `contained_memory_actor_allows(actor TEXT, project TEXT, writing BOOLEAN)
  RETURNS BOOLEAN`, a V229-shaped arm that admits a contained actor to the memories
  of its own contained project and nothing else. `MemoryStore`'s two SQL predicates
  gain it as a disjunct beside the existing `authority_accounts` /
  `information_global_read_member` / `admins` arms, which are left intact for every
  other principal type.

- [ ] **Step 1: Write the failing characterization test**

```java
// @Tag("full-db"), @Testcontainers — the house shape.
// Review Focus 4.
@Test void aContainedActorReadsItsOwnMemories()
@Test void aContainedActorWritesItsOwnMemories()
@Test void aContainedActorReadsNoOtherProjectsMemories()
@Test void aContainedActorDoesNotReachAGloballyReadableMemory()  // information_global_read_member stays closed to it
@Test void anAncestorReadsItsDescendantsMemories()
@Test void aSiblingReadsNothingOfItsSiblings()
@Test void anOrdinaryAccountsMemoryAccessIsUnchanged()
```

- [ ] **Step 2: Run the characterization and record which predicate refuses, in order**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.archive.ContainedMemorySchemaTest'`
Expected: the first three FAIL. Record in the test's Javadoc which predicate refused
first on the read path and which on the write path — this is the evidence that
corrects ADR 0042, so the Javadoc must name both, not just say "refused".

- [ ] **Step 3: Write the migration and widen `MemoryStore`'s two predicates**

The function admits on the actor's **own** scope's project only. Ancestor reads come
from the ancestry walk, not from widening this arm. The `admins`/`server_admin`
conjunct on the write path stays for every non-contained writer: a contained actor
is admitted by the new disjunct, not by relaxing the administrator requirement.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.archive.ContainedMemorySchemaTest' --tests 'io.aeyer.plowshare.server.archive.*Memory*'`
Expected: PASS, with `aContainedActorDoesNotReachAGloballyReadableMemory` and
`anOrdinaryAccountsMemoryAccessIsUnchanged` still passing.

- [ ] **Step 5: Amend ADR 0042**

One amendment noting that the "own documents and own memories" statement was correct
for documents and over-claimed for memories, which predicates closed the memory door,
and that this slice opened it with its own arm. Cite the characterization test by
name.

- [ ] **Step 6: Commit**

```bash
./gradlew format && ./gradlew formatCheck
git add plowshare-server/src/main/resources/db/migration plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/ContainedMemorySchemaTest.java docs/decisions/0042-nested-application-containment.md
git commit -m "Open the memory door for a contained actor, and correct the record"
```

---

### Task 5: Wire the contained principal as the information actor

Turn it on. Every gate the actor reaches is now decided, so this task is the branch
and its proof.

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java:110-131` (the `forRun` contained branch) and `:172` (remove the "No production path calls this" sentence, which stops being true here)
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java` (build the contained actor at the binding site)
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/ContainedInformationActorTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedInformationReachDatabaseTest.java`

**Interfaces:**
- Consumes: `InformationActor` and `InformationAccess.forRun(InformationActor, Home)`
  (Task 1), `InformationAccess.contained(String, String, String)` at `:172`,
  `ContainedProjects.name(String)`, `ContainedProjects.PREFIX`, and the conversation's
  `Origin.CONTAINED`.
- Produces: `ContainedPrincipals` gains
  `Optional<UUID> principalOfProject(String project)` if it has no equivalent — the
  resolution from a contained home's project name to the principal UUID that is the
  acting axis. It resolves through the repository that already owns
  `contained_principals`; no new SQL in a service.

**The decision this task encodes.** A run is contained when its conversation's
origin is `Origin.CONTAINED` **and** its home project satisfies
`ContainedProjects.name(...)` — the same two-part test `Turn.requireContainedLog`
already makes, and for the same reason: a test that depended on one of them alone
would be a test a later change could quietly open. The acting axis is the child's
principal UUID; the attribution axis is the container's handle, which is the
`callerHandle` the binding already has. Deciding it from the durable conversation
record and not from anything caller-supplied is the whole of the safety argument.

- [ ] **Step 1: Write the failing tests**

```java
// ContainedInformationActorTest — mocked, no database.
@Test void aContainedRunActsAsItsPrincipalAndIsAttributedToItsContainer()
@Test void anUncontainedRunActsAndIsAttributedAsItsCaller()
@Test void aContainedOriginWithAnUncontainedProjectIsRefused()   // the two-part test, half of it
@Test void anUncontainedOriginWithAContainedProjectIsRefused()   // the other half
@Test void aContainedRunsSelectionIsItsOwnProjectWithGlobalExcluded()  // includeGlobal == false

// ContainedInformationReachDatabaseTest — @Tag("full-db"), the end-to-end proof.
@Test void aContainedChildReadsAndWritesItsOwnDocuments()
@Test void aContainedChildReadsAndWritesItsOwnMemories()
@Test void aContainedChildReachesNoDocumentOfItsContainer()
@Test void aContainedChildReachesNoGenerallyReadableDocument()
@Test void twoSiblingChildrenReachNothingOfEachOther()
@Test void aContainedChildCannotSetAnAudience()                  // SET_AUDIENCE stays refused
@Test void aWriteByAContainedChildIsAttributedToItsContainer()   // the two axes, observed in a stored row
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.agents.ContainedInformationActorTest'` then the same with `-PfullDb --tests 'io.aeyer.plowshare.server.applications.ContainedInformationReachDatabaseTest'`
Expected: FAIL — a contained run resolves as its container today.

- [ ] **Step 3: Add the contained branch to `forRun` and build the actor at the binding site**

`forRun(InformationActor, Home)` branches on the actor already carrying differing
axes; the decision of *which* actor to build belongs at the `JobRuntime` binding
site, which is where the conversation record is in hand. Keep the two-part test in
one named private method so it reads as one rule rather than two conditions.

- [ ] **Step 4: Run both suites to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.agents.*' --tests 'io.aeyer.plowshare.server.information.*'`
then: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.applications.ContainedInformationReachDatabaseTest' --tests 'io.aeyer.plowshare.server.applications.ContainedKnowledgeBoundaryDatabaseTest' --tests 'io.aeyer.plowshare.server.applications.ContainedExecutionWriteDatabaseTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew format && ./gradlew formatCheck
git add plowshare-server/src/main/java plowshare-server/src/test/java
git commit -m "Resolve a contained run's information as the child, attributed to its container"
```

---

### Task 6: The binary blob claim path and the collection sweep

V225 keys `binary_blobs` and `binary_blob_claims` on a TEXT scope with a state and an
`unclaimed_at` collection index, deliberately so bytes survive project deletion.
Whether a contained principal has an admitted claim path, and whether an unclaimed
child blob can be swept while its namespace is still meant to be readable, is
unestablished. Establish it.

**Files:**
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/information/ContainedBlobClaimDatabaseTest.java`
- Modify: whichever of the blob claim path, the sweep predicate or a new migration the
  characterization shows is wrong. **It may be none**, in which case the deliverable is
  the test plus the Javadoc that records why the current behaviour is already correct.

**Interfaces:**
- Consumes: V225's `binary_blobs`, `binary_blob_claims`, `unclaimed_at`;
  `ContainedCollectionSweeps`; `ContainmentConfig`'s sweep grace.
- Produces: no new public contract unless the characterization demands one. If a fix
  is needed, it is a migration in this slice's numbering and the reason goes in the
  file header.

- [ ] **Step 1: Write the characterization test**

```java
// @Tag("full-db"), @Testcontainers.
// Review Focus 5.
@Test void aContainedActorCanClaimABlobItWrote()
@Test void aContainedChildsClaimedBlobSurvivesTheSweep()
@Test void aContainedChildsUnclaimedBlobIsNotSweptWhileItsSpaceIsReadable()
@Test void anOrdinaryUnclaimedBlobIsStillSweptAfterItsGrace()
```

- [ ] **Step 2: Run it and record the answer**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.information.ContainedBlobClaimDatabaseTest'`
Expected: unknown, which is the point. Record in the test's Javadoc, for each test,
what happened and why — naming the predicate that admitted or refused the claim, and
the predicate the sweep selects on.

- [ ] **Step 3: Fix only what the characterization showed broken**

A sweep that takes a child's unclaimed output before the parent has had the chance to
collect it is the spec's "silent loss" anti-goal, caused by the platform rather than
the parent, so it is a defect and is fixed here. A claim path that refuses a contained
actor is a gap to fix. Anything else is out of scope: write it down and move on.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.information.ContainedBlobClaimDatabaseTest' --tests 'io.aeyer.plowshare.server.applications.*Collection*'`
Expected: PASS.

- [ ] **Step 5: Run the repository-wide check and commit**

```bash
./gradlew format && ./gradlew formatCheck && ./gradlew check
git add plowshare-server docs
git commit -m "Prove a contained child's bytes survive until its parent collects them"
```

---

## Handover to Plan B

Plan B's first task starts from these facts, which this plan establishes rather than
assumes:

- A contained run's `InformationContext` acts as the child's principal UUID and is
  attributed to its container, decided from the conversation's durable origin.
- Every information write path a contained actor can reach is guarded or deliberately
  admitted, with the characterization recorded per repository.
- The memory door has its own arm, and ADR 0042's over-claim is corrected in the
  record.
- A child's binary output's survival until collection is proved rather than hoped.

What Plan B must decide and this plan deliberately does not: the namespace's
representation and addressing, where reference resolution sits on each door, and
whether the reference and tombstone representation needs SQL at all.

## Open decisions for the user

Unchanged from the spec; none blocks this plan. Recorded here so an executor does not
rediscover them:

1. Is the information-actor divergence information-only, or a general facility? **This
   plan assumes information-only** — `InformationActor` lives in the information
   package and no other subsystem is given it. The next subsystem that wants it asks
   again.
2. The `CONTAINED` retention — Plan C.
3. The existence oracle in `SourceContainedApplications.trajectory` and `resources` —
   a contract change, Plan C.
4. The inherited global agent tier's `tools:` subset test — parity, previously deferred.

# Nested Applications, Slice One — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A parent Application can create, read into and destroy confined child
Applications inside itself, with authority that cannot exceed its own, and every
surface not yet narrowed refused outright.

**Architecture:** Containment is a new `CONTAINED` kind in the existing
`authority_scopes` table and in the `AuthorityScope` sealed interface, so the
compiler locates every site that must decide what a contained scope means. A
containment hop is a new narrowing on `OperationAuthority` beside `derive`,
which cannot enlarge and cannot change account. The child is an ephemeral,
structurally non-authenticating principal; its source lives under a
server-configured nesting root; the parent reads in through typed operations and
the child can name nothing above itself.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC, Flyway, PostgreSQL, JUnit 5
with Mockito; TypeScript operation schemas as the SDK source of truth with
`scripts/generate-sdk-contracts.mjs` emitting Python, Go and .NET.

**Spec:** [docs/superpowers/specs/2026-10-25-nested-applications-design.md](../specs/2026-10-25-nested-applications-design.md)

## Global Constraints

- Java 21; Node.js 22.13+, pnpm and Python 3 for SDK codegen.
- `./gradlew format` then `./gradlew formatCheck` after every Java edit. Google
  Java Style; no hand alignment, no AOSP.
- Repositories own persistence. No `JdbcTemplate` in controllers, tools,
  frames, orchestration or domain services. No JPA, Hibernate or generic SQL
  helpers.
- Typed DTOs internally. No `Map<String, Object>` or raw JSON past the frame
  boundary; convert and validate at the transport edge before business logic.
- Schema changes go in new Flyway migrations only. Never edit a shipped
  migration. The next number is **V195** (highest shipped is V194).
- Database tests carry `@Tag("full-db")` and run only under `-PfullDb`, with
  `--tests` naming the smallest relevant set. Everything else uses mocked
  repository interfaces or small in-memory fakes.
- No hard-coded `localhost`, ports, paths, hosts, accounts or credentials in
  runtime code. The nesting root is explicit operator configuration; a missing
  required value fails clearly and never falls back.
- ADR 0005 package limits, verbatim and **per child**: at most 128 regular
  UTF-8 text files, 64 KiB per file, 128 KiB in total, at most 16 directory
  levels. The live aggregate across a parent's descendants is bounded
  separately.
- ADR 0021 pins tool and Relay topic name capacity. A containment namespace
  travels as resolution context; it is never prefixed into a tool or topic name.
- Child project names are opaque and server-generated. Containment is carried by
  the scope relation, never by a readable path in a project name.
- Slice one builds **no** parent/child allowance counter. A child allocates its
  own allowance under a parent-set ceiling.

## Review Focus

Five conditions the spec implies that no task's happy path exercises. Each has
its pinning test named in the owning task.

1. A child manifest declaring a provider, Relay port, audience, swarm type,
   schedule or FileStore destination beyond the parent's hold must be **refused**
   — never honoured, never silently reduced to nothing and accepted. (Task 5)
2. A contained scope reaching Relay must fail loudly with no fallback topic, per
   ADR 0037's table and ADR 0039's two-valued scope space, and must be excluded
   from grant issuance the way `RESTRICTED` already is at
   `JdbcScopedGrantRepository.java:246`. (Task 8)
3. Depth or breadth at its bound must refuse before anything is created, leaving
   no partial child. (Task 3 for depth, Task 5 for breadth)
4. A create interrupted between source staging and the database transaction must
   leave no orphan project row, revision directory or live principal, and
   collection must be observable. ADR 0005 states that filesystem staging cannot
   participate in PostgreSQL rollback, so this is explicit recovery, not
   rollback. (Task 7)
5. A parent revision activation concurrent with a live child must refuse rather
   than interleave, and must not destroy the child. (Task 7)

---

### Task 1: Decision record

**Files:**
- Create: `docs/decisions/0042-nested-application-containment.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the authorization artifact every later task cites.

ADR 0001 requires a core platform change to carry its own decision record
explaining the general platform need, the SDK-only alternatives, the
authorization and lifecycle impact, and compatibility. The spec supplies all
four; this task records them in the repository's decision form.

- [ ] **Step 1: Write the record**

Follow the form of `docs/decisions/0005-application-deployment.md`: a status
line, the platform need, the decision, what it does not do, alternatives and
consequences, and verification. State explicitly that it does not change the
administrator-gated `application.deploy` path, does not build a parent/child
allowance counter, and does not add a general request-forwarding surface.

- [ ] **Step 2: Verify the cross-references resolve**

Run: `cd docs/decisions && grep -o '](\.\.\?[^)]*)' 0042-nested-application-containment.md | sed 's/](//;s/)$//' | while read f; do [ -e "$f" ] || echo "MISSING: $f"; done`
Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add docs/decisions/0042-nested-application-containment.md
git commit -m "Record the nested Application containment decision"
```

---

### Task 2: The `CONTAINED` scope kind

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V195__contained_authority_scopes.sql`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/AuthorityScope.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java:246`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedScopeTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainedScopeSchemaTest.java`

**Interfaces:**
- Consumes: Task 1.
- Produces: `AuthorityScope.Contained(ScopeId id, long projectId, ScopeId container)` — a
  new permitted implementation of the sealed `AuthorityScope`. `container` is the
  enclosing scope, which may itself be `Contained`.

The migration adds `CONTAINED` to the `authority_scopes.kind` CHECK and a
`container UUID REFERENCES authority_scopes(id)` column, with CHECK clauses
mirroring the existing per-kind shape: `CONTAINED` requires `project_id` and
`container` non-null and `account_id` null, and every other kind requires
`container` null. Add a self-reference guard so a scope cannot contain itself.
Do not alter any existing row or kind.

- [ ] **Step 1: Write the failing unit test**

```java
class ContainedScopeTest {
  @Test void containedScopeRequiresAContainer() { /* null container -> NullPointerException */ }
  @Test void containedScopeRejectsItsOwnIdAsContainer() { /* IllegalArgumentException */ }
  @Test void containedScopeRequiresAnExistingProjectKey() { /* projectId 0 -> IllegalArgumentException */ }
  @Test void grantIssuanceExcludesContainedScopes() { /* repository refuses a CONTAINED target */ }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.access.ContainedScopeTest'`
Expected: FAIL — `AuthorityScope.Contained` does not exist.

- [ ] **Step 3: Add the `Contained` record to `AuthorityScope` and exclude it from grant issuance**

Add the record to the sealed interface. Then compile the whole server and work
through every resulting exhaustiveness error: that list is the authoritative
inventory of sites that must decide what containment means. Where a site has no
defined behaviour yet, refuse loudly rather than defaulting. Widen the
`kind<>'RESTRICTED'` predicate at `JdbcScopedGrantRepository.java:246` to
exclude `CONTAINED` as well.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.access.ContainedScopeTest'`
Expected: PASS.

- [ ] **Step 5: Record the exhaustiveness inventory**

Append to the ADR from Task 1 the list of switch sites the compiler surfaced in
Step 3, each marked fenced or refused. Task 8 consumes this list.

- [ ] **Step 6: Write the failing schema test**

```java
@Tag("full-db")
class ContainedScopeSchemaTest {
  @Test void migrationAcceptsAContainedScopeWithAContainer() {}
  @Test void containedScopeWithoutAContainerViolatesTheCheck() {}
  @Test void nonContainedKindsRejectAContainer() {}
  @Test void aScopeCannotContainItself() {}
}
```

Database-specific reason: these assert actual PostgreSQL CHECK constraints and
Flyway migration behaviour, which is exactly what the policy's exception covers.

- [ ] **Step 7: Run the schema test to verify it fails, write V195, run it to verify it passes**

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.access.ContainedScopeSchemaTest'`
Expected: FAIL before the migration, PASS after.

- [ ] **Step 8: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main plowshare-server/src/test docs/decisions
git commit -m "Add a CONTAINED authority scope kind"
```

---

### Task 3: The containment hop

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/OperationAuthority.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/PinnedOperationAuthority.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ContainmentBounds.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/access/ContainmentHopTest.java`

**Interfaces:**
- Consumes: `AuthorityScope.Contained` from Task 2.
- Produces: `Admission contain(Admission parent, AuthorityScope.Contained child, Set<AuthorityAction> actions)`
  on `OperationAuthority`, and the record
  `ContainmentBounds(int maxDepth, int maxLiveDescendants)`. Task 5's
  `ContainmentConfig` is what binds it to operator configuration under
  `plowshare.containment`; this task defines the type and consumes an instance.

`derive` narrows actions while holding scope, account and home fixed, so it
cannot express this hop. `contain` is its sibling: the child's action set must be
a subset of the parent's retained support, the account never changes, the child's
scope must name the parent's scope as its container, and the resulting admission
retains the parent's supporting grants as its ancestry. There is no path by which
`contain` returns an admission holding an action the parent does not hold.

- [ ] **Step 1: Write the failing test**

```java
class ContainmentHopTest {
  @Test void containNarrowsTheActionSetToASubsetOfTheParent() {}
  @Test void containRefusesAnActionTheParentDoesNotHold() {}
  @Test void containRefusesAChildScopeThatNamesADifferentContainer() {}
  @Test void containNeverChangesTheAccount() {}
  @Test void containRetainsTheParentSupportingGrantsAsAncestry() {}
  @Test void containRefusesBeyondTheConfiguredDepth() {}
  @Test void containRefusesWhenTheConfiguredDepthIsAbsent() {}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.access.ContainmentHopTest'`
Expected: FAIL — `contain` is not defined.

- [ ] **Step 3: Implement `contain` and `ContainmentBounds`**

Add `contain` to the interface and to `PinnedOperationAuthority`. Depth is the
length of the container chain; refuse at the bound before computing anything
else. An absent or non-positive configured bound is a refusal, never an
unlimited default.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.access.ContainmentHopTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src
git commit -m "Narrow a parent admission across a containment hop"
```

---

### Task 4: The ephemeral child principal

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedPrincipals.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcContainedPrincipalRepository.java`
- Create: `plowshare-server/src/main/resources/db/migration/V196__contained_principals.sql`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedPrincipalsTest.java`

**Interfaces:**
- Consumes: Task 2's `Contained`, Task 3's `contain`.
- Produces: `ContainedPrincipals.issue(AuthorityScope.Contained scope)` returning
  `ContainedPrincipal(UUID id, ScopeId scope)`, and `revoke(ScopeId scope)`.

The anti-goal against confinement-as-a-check means "no external login as the
child" must be structural. A contained principal therefore holds **no credential
material of any kind** — no token UUID, no handle in the authentication
namespace — so no authentication path can resolve it even in principle. This is
the opposite of ADR 0017's Application-owned service identity, whose
reservations survive deletion; a contained principal is deleted with its scope.

- [ ] **Step 1: Write the failing test**

```java
class ContainedPrincipalsTest {
  @Test void anIssuedPrincipalCarriesNoCredentialMaterial() {}
  @Test void authenticationCannotResolveAContainedPrincipal() {}
  @Test void issuingTwiceForOneScopeReturnsTheSamePrincipal() {}
  @Test void revokingTheScopeRemovesThePrincipal() {}
  @Test void aContainedPrincipalIsNotAServiceCredentialPrincipal() {}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedPrincipalsTest'`
Expected: FAIL — `ContainedPrincipals` does not exist.

- [ ] **Step 3: Implement `ContainedPrincipals`, its repository and V196**

V196 holds the principal rows with `ON DELETE CASCADE` from `authority_scopes`,
so scope removal collects principals without a second code path. The last test
asserts `ServiceCredentials.principal(...)` is false for a contained principal,
so the Personal-selection refusal and service-token rules do not accidentally
classify it.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedPrincipalsTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main plowshare-server/src/test
git commit -m "Issue ephemeral principals for contained scopes"
```

---

### Task 5: Parent-authorized child creation

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedApplications.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceContainedApplications.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainmentConfig.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ContainedApplicationFrames.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedApplicationCreationTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedManifestRefusalTest.java`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces: `ContainedApplications.create(String account, Create request)` returning
  `Child(UUID id, ScopeId scope, String project, UUID revision)`;
  `ceiling(String account, UUID child, Integer calls)`; frames
  `application.child.create` and `application.child.ceiling`; configuration key
  `plowshare.containment.root`.
- `ContainmentConfig` binds the whole `plowshare.containment` prefix and supplies
  the `ContainmentBounds` record Task 3 defined; it does not re-declare those
  fields.

Creation reuses ADR 0005's validation and staging and replaces only its
admission rule and its destination. There is no server-administrator check and
no destination FileStore `MANAGER` check: the parent's own admission, narrowed
by `contain`, is the authority, and the destination is allocated by the server
under `plowshare.containment.root`. Nesting is opt-in: the parent's manifest must
declare it, or creation is refused.

- [ ] **Step 1: Write the failing creation test**

```java
class ContainedApplicationCreationTest {
  @Test void aParentCreatesAChildWithoutServerAdministratorAuthority() {}
  @Test void creationRefusesWhenTheParentManifestDoesNotDeclareNesting() {}
  @Test void creationRefusesWhenTheNestingRootIsNotConfigured() {}
  @Test void creationAllocatesTheDestinationAndIgnoresAnyRequestedOne() {}
  @Test void theChildProjectNameIsOpaqueAndCarriesNoContainmentPath() {}
  @Test void creationAppliesPackageLimitsPerChild() {}
  @Test void creationRefusesAtTheLiveDescendantBound() {}            // Review Focus 3
  @Test void creationRefusesAnIdenticalRequestUuidWithChangedContent() {}
  @Test void anIdenticalRetryReadsItsReceiptBeforeTouchingFiles() {}
  @Test void creationAllocatesTheChildOwnAllowanceUnderAParentSetCeiling() {}
  @Test void creationRefusesACeilingAboveTheParentRemainingAllowance() {}
  @Test void theParentCanReduceAChildCeilingToZero() {}
  @Test void aChildCallIsRefusedOnceItsCeilingIsReached() {}
  @Test void childSpendIsAttributedToTheChildNotTheParent() {}
}
```

The last five pin the approved budget decision. The child allocates its **own**
allowance, as nested starts already do, under a ceiling the parent sets and may
reduce to zero. `AllowanceSnapshot` already documents that an absent ceiling
means unlimited and zero disables calls, so a contained child must never be
created with an absent ceiling. Attribution stays with the child, which leaves
ADR 0025's immutable attribution and its rule that delegation cannot change the
accounting account untouched.

- [ ] **Step 2: Write the failing manifest-refusal test**

```java
class ContainedManifestRefusalTest {                                  // Review Focus 1
  @Test void aChildProviderDeclarationBeyondTheParentIsRefused() {}
  @Test void aChildRelayPortBeyondTheParentIsRefused() {}
  @Test void aChildRelayAudienceBeyondTheParentIsRefused() {}
  @Test void aChildSwarmTypeBeyondTheParentIsRefused() {}
  @Test void aChildScheduleDeclarationIsRefusedInSliceOne() {}
  @Test void aChildFileStoreDestinationOutsideTheParentFenceIsRefused() {}
  @Test void aRefusedManifestCreatesNoProjectScopeOrPrincipal() {}
}
```

The last assertion is the one that matters: a refusal must be total. Honouring a
declaration, or accepting the package while dropping the declaration, both fail
the spec's anti-goal.

- [ ] **Step 3: Run both to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.Contained*Test'`
Expected: FAIL — `ContainedApplications` does not exist.

- [ ] **Step 4: Implement the contract, the source implementation, the configuration and the frame**

`ContainmentConfig` binds `plowshare.containment.root`, `max-depth` and
`max-live-descendants`, refusing absent or invalid values at startup rather than
defaulting. The frame converts the payload to a typed `Create` DTO before any
durable write, following `ApplicationDeploymentFrames.deploy`. Reuse the existing
Application resource parsers for validation; add only the narrowing comparison
against the parent's hold.

- [ ] **Step 5: Run both to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.Contained*Test'`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src
git commit -m "Create contained Applications under parent authority"
```

---

### Task 6: Reading into a child

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ContainedApplicationFrames.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedVisibilityTest.java`

**Interfaces:**
- Consumes: Task 5's `Child`.
- Produces: `list(String account, String project)` returning `List<Child>`;
  `trajectory(String account, UUID child)` returning the child's conversation
  lineage evidence; frames `application.child.list` and
  `application.child.trajectory`.

Trajectory reads use `OperationAuthority.Conversation`, which already carries
`parent`, the budget owner and a capability fingerprint. The parent reads
immutable evidence; the contract notes that callers must still recheck live
authority, as that record's own documentation requires.

- [ ] **Step 1: Write the failing test**

```java
class ContainedVisibilityTest {
  @Test void aParentReadsItsChildTrajectory() {}
  @Test void aParentReadsItsChildResources() {}
  @Test void aParentCannotReadAChildItDoesNotContain() {}
  @Test void aChildCannotReadItsParent() {}
  @Test void aChildCannotReadASibling() {}
  @Test void aSiblingCannotObserveAnotherSiblingDeliverables() {}
  @Test void aGrandparentReadsThroughTheContainerChain() {}
  @Test void aChildKnowledgeReadReturnsNothingFromOutsideItsSubtree() {}
  @Test void aChildKnowledgeReadCannotReachOutwardThroughDependencyClosure() {}
  @Test void aChildWritesKnowledgeInsideItsOwnHome() {}
}
```

The last three pin the approved decision that a child has its own writable
knowledge home: the read ceiling must exclude everything above it, including
rows reached through `InformationSql`'s dependency closure that the child never
named, while writes inside its own home succeed.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedVisibilityTest'`
Expected: FAIL — `list` and `trajectory` are not defined.

- [ ] **Step 3: Implement the reads**

Bind the child's `KnowledgeReadScope` to its own home with `globalRead` false, so
the existing intersection in `InformationAccess.within` can only narrow further.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedVisibilityTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src
git commit -m "Read into contained Applications one way"
```

---

### Task 7: Destruction, collection and recovery

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceContainedApplications.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceApplicationDeployments.java`
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ContainedSourceCollector.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedDestructionTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedRecoveryTest.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedConcurrencyTest.java`

**Interfaces:**
- Consumes: Tasks 4–6.
- Produces: `destroy(String account, UUID child)`; frame
  `application.child.destroy`; `ContainedSourceCollector.sweep()` for
  orphaned stages.

- [ ] **Step 1: Write the failing destruction test**

```java
class ContainedDestructionTest {
  @Test void destroyingAParentDestroysItsDescendants() {}
  @Test void destroyingAChildRevokesItsPrincipal() {}
  @Test void destroyingAChildCollectsItsSourceDirectory() {}
  @Test void revokingParentAuthorityMidExecutionStopsDescendantWork() {}
  @Test void parentActivationRefusesWhileAChildIsLive() {}            // Review Focus 5
  @Test void parentActivationDoesNotDestroyALiveChild() {}            // Review Focus 5
  @Test void aDestroyedChildIsNotPromotedToARetainedRelease() {}
}
```

- [ ] **Step 2: Write the failing recovery test**

```java
class ContainedRecoveryTest {                                         // Review Focus 4
  @Test void anInterruptedCreateLeavesNoProjectRow() {}
  @Test void anInterruptedCreateLeavesNoLivePrincipal() {}
  @Test void sweepCollectsAStageWithNoCommittedChild() {}
  @Test void sweepLeavesACommittedChildSourceAlone() {}
  @Test void sweepLeavesTopLevelRetainedRevisionsAlone() {}
}
```

The last assertion guards ADR 0005's retained release history: collection is for
contained stages only and must never reach an ordinary Application's revisions.

- [ ] **Step 3: Run both to verify they fail**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedDestructionTest' --tests 'io.aeyer.plowshare.server.applications.ContainedRecoveryTest'`
Expected: FAIL — `destroy` and `ContainedSourceCollector` do not exist.

- [ ] **Step 4: Implement destruction, the collector and the activation guard**

Add the live-child check to the existing project row lock in
`SourceApplicationDeployments` so activation and creation cannot interleave.

- [ ] **Step 5: Run both to verify they pass**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedDestructionTest' --tests 'io.aeyer.plowshare.server.applications.ContainedRecoveryTest'`
Expected: PASS.

- [ ] **Step 6: Write the failing concurrency test and make it pass**

```java
@Tag("full-db")
class ContainedConcurrencyTest {
  @Test void concurrentCreateAndActivationCannotBothCommit() {}
  @Test void cascadeRemovesScopesPrincipalsAndRowsInOneTransaction() {}
}
```

Database-specific reason: row locking, cascade behaviour and transaction
rollback under real concurrency, which mocks cannot establish.

Run: `./gradlew :plowshare-server:test -PfullDb --tests 'io.aeyer.plowshare.server.applications.ContainedConcurrencyTest'`
Expected: FAIL, then PASS.

- [ ] **Step 7: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src
git commit -m "Destroy and collect contained Applications"
```

---

### Task 8: Fencing every addressable surface

**Files:**
- Modify: the sites recorded in Task 2 Step 5.
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/applications/ContainedAddressabilityTest.java`

**Interfaces:**
- Consumes: Task 2's exhaustiveness inventory, Tasks 5–7.
- Produces: no new contract; a decision at every site.

This task is the confinement proof. The spec requires an authoritative inventory
of every place the server makes a project addressable **without anyone naming
it**, each entry fenced or refused.

- [ ] **Step 1: Write the failing test, one case per inventory entry**

```java
class ContainedAddressabilityTest {
  @Test void projectListOmitsContainedProjectsFromEveryCallerButTheirParent() {}
  @Test void messagingRefusesAContainedProjectAsFromProject() {}
  @Test void messagingRefusesAContainedProjectAsToProject() {}
  @Test void aChildCannotMessageAnythingItsParentCannot() {}
  @Test void relayRefusesAContainedScopeLoudlyWithNoFallbackTopic() {}  // Review Focus 2
  @Test void relayProjectScopedSubscribersExcludeContainedProjects() {}
  @Test void startupScheduleReconciliationSkipsContainedProjects() {}
  @Test void conversationSearchExcludesContainedProjects() {}
  @Test void creatingAChildProvisionsNoWorkingTreeOrGitHub() {}
  @Test void anExternalPrincipalAddressingAChildDirectlyIsRefused() {}
  @Test void theParentMediatesOnlyWhatItHolds() {}
}
```

Messaging is a narrowing of existing Relay and Application scoping, not a new
forwarding surface — the child cannot message anything its parent cannot. The
git/working-tree case guards against a child acquiring the union provisioning
that ordinary project creation performs.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.ContainedAddressabilityTest'`
Expected: FAIL.

- [ ] **Step 3: Fence or refuse at each site**

Work the inventory from Task 2. Relay follows ADR 0037's table for
`ADMINISTRATION` and `RESTRICTED` — fail loudly, no fallback topic — and does
**not** gain a new scope kind, per ADR 0039. Where a surface cannot be fenced in
this slice, refuse it and record that in the ADR.

- [ ] **Step 4: Run it to verify it passes, then run the server suite**

Run: `./gradlew :plowshare-server:test`
Expected: PASS, with no pre-existing test newly failing.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src docs/decisions
git commit -m "Fence contained projects at every addressable surface"
```

---

### Task 9: SDK contracts

**Files:**
- Create: `sdk/typescript/src/operations/contained-applications.ts`
- Create: `sdk/typescript/src/operations/contained-applications.test.ts`
- Modify: generated outputs via `scripts/generate-sdk-contracts.mjs` and
  `scripts/generate-native-sdk-contracts.mjs`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ContainedApplicationFramesTest.java`

**Interfaces:**
- Consumes: the frame names from Tasks 5–7.
- Produces: equivalent typed contracts in Java, TypeScript/Node, Python, Go
  and .NET.

- [ ] **Step 1: Write the failing operation-schema test**

Follow `sdk/typescript/src/operations/application-deployments.test.ts`. Cover
every new operation's input and output schema, and that a malformed payload is
rejected at the boundary.

- [ ] **Step 2: Run it to verify it fails**

Run: `pnpm --filter @plowshare/client test -- contained-applications`
Expected: FAIL.

- [ ] **Step 3: Declare the schemas, then regenerate**

The generator cross-checks the SDK catalogue against registered server frames
and throws `SDK catalog differs from registered server frames` on a mismatch, so
`FrameTypes.java` and the schemas must agree exactly.

```bash
node scripts/generate-sdk-contracts.mjs
node scripts/generate-native-sdk-contracts.mjs
```

- [ ] **Step 4: Verify generated outputs are committed, not hand-edited**

Run: `git diff --stat` and confirm every changed generated file is one the
scripts wrote.

- [ ] **Step 5: Write and run the frame conformance test**

```java
class ContainedApplicationFramesTest {
  @Test void everyContainedFrameIsRegistered() {}
  @Test void aPayloadWithUnknownFieldsIsRefused() {}
  @Test void aPayloadMissingRequiredFieldsIsRefused() {}
  @Test void conversionCompletesBeforeAnyDurableWrite() {}
}
```

Run: `./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.ws.ContainedApplicationFramesTest'`
Expected: PASS.

- [ ] **Step 6: Run the repository check and commit**

Run: `./gradlew check`
Expected: PASS.

```bash
./gradlew format formatCheck
git add sdk test-support plowshare-server/src scripts
git commit -m "Expose contained Application operations in every SDK"
```

---

## Deferred to later slices

Named here so no task silently absorbs them:

- A parent/child allowance counter. Slice one uses a parent-set ceiling; the
  shared-counter contract the lineage slice awaits is separate work with its own
  decision record.
- Narrowing, rather than refusing, child-declared schedules, providers and
  Relay ports.
- Promoting a contained Application to a retained top-level release.
- Keying Relay topics on `authority_scopes.id`, which ADR 0039 identifies as the
  right question for Relay's two-valued scope space.
- The recursive harness-improvement workflow, which is a separate design built
  on these public contracts and needs no further core change.

One spec item is deliberately not a task here. The rule that **a child's output
is untrusted input to its parent** — data, never instruction — is a contract on
consumers, and slice one ships no consumer that composes new configuration from
a child's trajectory. It binds the workflow design, and the ADR from Task 1
states it so the obligation travels with the capability rather than being
rediscovered later.

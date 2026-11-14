# Operation pipeline, permission checks and deployment telemetry: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove repeated permission resolution and refetching within one operation, make the
permission checks that remain honest about what they guard, and ship telemetry for both the server
and PostgreSQL so that every slice can be measured before and after it is deployed.

**Architecture:** Telemetry lands first (phase A) so phases B–D each have a before/after on BETA and
PROD. Phase B removes pure repetition with no new mechanism. Phase C fixes the permission checks:
reusing fetched grants, an honest after-tool refusal, and explicit in-transaction fences for
mutating tools. Phase D introduces the per-tool-call operation context and replaces the relay wait
poll. Each task is a local change behind existing interfaces and is proven by a call-count or
statement-count test.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC, Micrometer/Prometheus, JFR, PostgreSQL 16
(pgvector image) with `pg_stat_statements` and `auto_explain`, Docker Compose, JUnit 5 + Mockito.

**Spec:** [docs/query-efficiency-operation-pipeline-design.md](../../query-efficiency-operation-pipeline-design.md),
with [AGENTS.md](../../../AGENTS.md), [coding standards](../../coding-standards.md) and
[server administration](../../server-administration.md).

Paths below abbreviate `plowshare-server/src/main/java/io/aeyer/plowshare/server/` as `M/` and
`plowshare-server/src/test/java/io/aeyer/plowshare/server/` as `T/`.

## Global Constraints

- Google Java Style through `./gradlew format`; run `./gradlew formatCheck` after every Java edit.
- Import Java types; no fully qualified names outside imports unless resolving a clash.
- No hard-coded hosts, ports, paths or credentials in runtime code. Unset optional telemetry config
  means "off", never a local default.
- Raw JSON and `Map<String, Object>` never cross into internal contracts.
- Schema changes only in new Flyway migrations; the next free number is `V259`.
- Mock database dependencies by default. `-PfullDb` only where a task says so, with the reason, and
  limited by `--tests`.
- Bounded staleness for any reused authority decision: `plowshare.projection-cache.notified.staleness`
  (default 2 s, valid range (0, 1 min]). A refusal is never reused. Inside a write transaction that
  may hold its own writes, nothing is reused.
- Meter tags are low-cardinality: never a SQL hash, id, account, project or topic name.
- Repositories own SQL. Controllers, tools and services get interfaces, not `JdbcTemplate`.

## Review Focus

1. **Revocation during a long tool call.** A grant revoked while a tool runs must still stop the
   result reaching the model and must stop any write that has not yet committed. Pinned by Task 9
   (`revokedDuringToolWithholdsResultAndSaysWhy`) and Task 10 (`revocationBeforeCommitRollsBackPublish`).
2. **A relay topic deleted by another process after this one memoised it.** Publish must re-register
   and succeed once, not fail or loop. Pinned by Task 6 (`topicDeletedElsewhereIsReRegisteredOnce`).
3. **A socket session revoked mid-request.** A request must stop delivering within the 2 s bound even
   though its session check is reused. Pinned by Task 7 (`revokedSessionStopsDeliveryWithinBound`).
4. **Telemetry with no registry or no management port.** The server must start with metrics absent and
   no listener bound. Pinned by Task 1 (`noRegistryRegistersNothingAndStillTraces`) and Task 3's compose
   check.
5. **Hosted tool with two connections for the same provider.** The hoisted lookup must keep today's
   first-match result. Pinned by Task 4 (`firstConnectionPerProviderWinsAfterHoist`).

---

## Phase A: telemetry

### Task 1: Server meters for statements and authority checks

**Files:**
- Create: `M/pool/MicrometerJdbcExecutionTrace.java`, `M/pool/CompositeJdbcExecutionTrace.java`
- Create: `M/access/AuthorityCheckTelemetry.java`, `M/access/MicrometerAuthorityCheckTelemetry.java`
- Modify: `M/pool/PoolAdmissionConfiguration.java:108-113`, `M/cache/ProjectionCacheConfiguration.java:71-107`
- Modify: `M/access/BoundedVerifiedAdmissions.java`, `M/access/AuthoritySnapshotConfiguration.java:84-88`
- Modify: `M/auth/CachedSessionAuthority.java:40-45`, `M/ws/SocketSessionConfiguration.java:18-26`
- Modify: `plowshare-server/src/main/resources/application.yml` (Hikari `pool-name`)
- Modify: `docs/server-administration.md` (meter reference sections)
- Test: `T/pool/MicrometerJdbcExecutionTraceTest.java`, `T/access/VerifiedAdmissionsTest.java`, `T/auth/CachedSessionAuthorityTest.java`

**Interfaces:**
- Produces: `CompositeJdbcExecutionTrace(List<JdbcExecutionTrace> traces) implements JdbcExecutionTrace`;
  `MicrometerJdbcExecutionTrace(MeterRegistry registry, String pool) implements JdbcExecutionTrace`.
- Produces: `interface AuthorityCheckTelemetry { void admission(AdmissionOutcome outcome); void session(SessionOutcome outcome); static AuthorityCheckTelemetry none(); }`
  with `enum AdmissionOutcome { VERIFIED_HIT, WALKED, TRANSACTION_WALK, NO_SNAPSHOT_WALK, REFUSED }`
  and `enum SessionOutcome { SNAPSHOT, SQL, REFUSED }`.

Meters:

| Meter | Type | Tags |
| --- | --- | --- |
| `plowshare.jdbc.statements` | Timer | `pool` (`main`, `projection-versions`, `listen`), `method`, `outcome` (`ok`, `failed`) |
| `plowshare.authority.admission.checks` | Counter | `outcome` |
| `plowshare.authority.session.checks` | Counter | `outcome` |

- [ ] **Step 1: Write the failing tests.**
  `MicrometerJdbcExecutionTraceTest.countsEachExecuteByPoolMethodAndOutcome`: wrap an H2-free fake
  `DataSource` (Mockito `Connection`/`PreparedStatement`) in `TracedDataSource` with the composite of a
  mocked JFR trace and a `MicrometerJdbcExecutionTrace(registry, "main")`, run two `executeQuery` and one
  failing `executeUpdate`, then assert `registry.timer("plowshare.jdbc.statements","pool","main","method","executeQuery","outcome","ok").count() == 2`,
  the failed one `== 1`, and the JFR mock still saw 3 `begin` calls.
  `noRegistryRegistersNothingAndStillTraces`: with `ObjectProvider` empty, configuration builds only
  the JFR trace and statements still execute.
  `VerifiedAdmissionsTest.countsEachOutcome`: one walk, one hit, one in-transaction walk, one refusal →
  counters `WALKED=1, VERIFIED_HIT=1, TRANSACTION_WALK=1, REFUSED=1`.
  `CachedSessionAuthorityTest.countsSnapshotAndSqlAnswers`.
- [ ] **Step 2: Run them and confirm they fail.**
  `./gradlew :plowshare-server:test --tests '*MicrometerJdbcExecutionTraceTest' --tests '*VerifiedAdmissionsTest' --tests '*CachedSessionAuthorityTest'`
  Expected: compilation failure (types missing).
- [ ] **Step 3: Implement.** The Micrometer span records with `Timer.Sample` and tags the outcome
  at `end`. Configuration composes the traces only when a `MeterRegistry` exists. The projection-versions
  pool and LISTEN source get their own `pool` tag. Set `spring.datasource.hikari.pool-name: plowshare-main`
  and give the projection-versions `HikariDataSource` `setPoolName("plowshare-projection-versions")` and
  `setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry))` when a registry exists.
  `BoundedVerifiedAdmissions` and `CachedSessionAuthority` take `AuthorityCheckTelemetry` through their
  constructors (default `none()`).
- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Document the three meters and the pool names in `docs/server-administration.md`,
  run `./gradlew format formatCheck`, and commit** ("Count statements and authority checks as meters").

### Task 2: Per-operation statement counts

**Files:**
- Create: `M/pool/OperationStatements.java`
- Modify: `M/pool/MicrometerJdbcExecutionTrace.java`, `M/agents/JobRuntime.java` (tool dispatch at :3773 and step top at :3058), `M/ws/EventChannelHandler.java:733` and `:895`
- Test: `T/pool/OperationStatementsTest.java`

**Interfaces:**
- Produces: `final class OperationStatements { static Scope open(Kind kind); enum Kind { TOOL_CALL, JOB_STEP, SOCKET_REQUEST } interface Scope extends AutoCloseable { void close(); } }`
  backed by a `ThreadLocal` counter that `MicrometerJdbcExecutionTrace.begin` increments.
- Meter: `plowshare.operation.statements` DistributionSummary tagged `kind`, recorded at `close()`,
  with percentiles histogram enabled.

The count is per thread. Work a tool hands to another thread is not counted in its operation; the
Javadoc says so. This is telemetry, not the operation context of Task 11, which is passed explicitly.

- [ ] **Step 1: Write the failing test.** `OperationStatementsTest.recordsStatementsExecutedInsideTheScope`:
  open a `TOOL_CALL` scope, run 3 traced statements, close → summary count 1, total 3. A statement
  outside any scope records nothing. Nested scopes: the inner closes first and both record.
- [ ] **Step 2: Run it and confirm it fails.**
- [ ] **Step 3: Implement and wrap the three entry points in try-with-resources.**
- [ ] **Step 4: Run it and confirm it passes;** also run `--tests '*EventChannelTest' --tests '*OperationExecutionTest'`.
- [ ] **Step 5: Format and commit** ("Record statements per tool call, job step and socket request").

### Task 3: Database telemetry and deployment wiring

**Files:**
- Create: `plowshare-server/src/main/resources/db/migration/V259__pg_stat_statements.sql`
- Modify: `deploy/docker/compose.yaml:3-27`, `deploy/docker/deployment.env.example`, `deploy/docker/server-entrypoint.sh`
- Create: `deploy/monitoring/postgres-exporter-queries.yaml`, `deploy/monitoring/prometheus-scrape.example.yaml`,
  `deploy/monitoring/grafana-plowshare.json`, `deploy/monitoring/plowshare.jfc`, `deploy/monitoring/pg-stat-snapshot.sh`
- Modify: `deploy/docker/README.md` (new "Monitoring" section), `docs/server-administration.md:410-436`

Decisions:
- PostgreSQL command gains `-c shared_preload_libraries=pg_stat_statements,auto_explain`,
  `-c pg_stat_statements.track=top`, `-c pg_stat_statements.max=10000`, `-c track_io_timing=on`,
  `-c auto_explain.log_min_duration=${PLOWSHARE_PG_AUTO_EXPLAIN_MS:-1000}`,
  `-c auto_explain.log_format=json`, `-c log_min_duration_statement=${PLOWSHARE_PG_SLOW_MS:-1000}`.
- `V259`: `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` (PROD already has it; idempotent).
- `postgres-exporter` service under compose profile `monitoring`, image pinned by exact version,
  bound to `${PLOWSHARE_MONITORING_BIND:?}` only when the profile is enabled; its custom queries expose
  the top statements by total time as `queryid` (never query text) plus `pg_stat_database` and
  `pg_stat_user_tables` scans.
- Server management listener: the entrypoint passes `management.server.port`/`address` and adds
  `prometheus` to the exposure **only** when both `PLOWSHARE_MANAGEMENT_PORT` and
  `PLOWSHARE_MANAGEMENT_BIND` are set; one set without the other exits non-zero with a message.
- JFR: when `PLOWSHARE_JFR_DIR` is set, the entrypoint adds
  `-XX:StartFlightRecording=settings=/opt/plowshare/plowshare.jfc,filename=${PLOWSHARE_JFR_DIR}/plowshare-%t.jfr,maxage=6h,maxsize=512m,dumponexit=true`.
  `plowshare.jfc` is the JDK `profile` settings plus `plowshare.JdbcExecute` enabled, stack traces on,
  threshold 0. The Dockerfile copies it in.
- `pg-stat-snapshot.sh` writes a timestamped CSV of `pg_stat_statements` (queryid, calls,
  total/mean time, rows, shared blocks) and a `delta` mode comparing two snapshots by `queryid`.
- The Grafana dashboard has rows: statements per operation (Task 2), JDBC rate and latency by pool,
  authority check outcomes, projection cache hit ratio by line, read admission waits, Hikari pools,
  PostgreSQL top `queryid` by total time, and scans.

- [ ] **Step 1: Add `deploy/docker/tests/test_monitoring.py`** (Python `unittest`, no Docker, in the
  style of `test_filestores.py`) asserting: the PostgreSQL command in `compose.yaml` preloads
  `pg_stat_statements,auto_explain`; `postgres-exporter` declares `profiles: [monitoring]`; running
  `server-entrypoint.sh` with a stub `java` on `PATH` exits non-zero when only one of
  `PLOWSHARE_MANAGEMENT_PORT`/`PLOWSHARE_MANAGEMENT_BIND` is set, passes no management flags when
  neither is set, and adds the JFR option with `plowshare-%t.jfr` only when `PLOWSHARE_JFR_DIR` is set.
  Run with `python3 -m unittest deploy/docker/tests/test_monitoring.py`.
- [ ] **Step 2: Run it and confirm it fails.**
- [ ] **Step 3: Implement the files above.**
- [ ] **Step 4: Run the compose test and confirm it passes.** Then, because this task adds a migration,
  add `T/PgStatStatementsMigrationTest.java` tagged `@Tag("full-db")` asserting
  `SELECT count(*) FROM pg_extension WHERE extname='pg_stat_statements'` is 1 after Flyway, and run only
  it with real PostgreSQL. Reason: only real PostgreSQL proves the extension installs from the pgvector
  image. Command: `./gradlew :plowshare-server:test -PfullDb --tests '*PgStatStatementsMigrationTest'`
  (follow `RelayScopeMigrationTest` for container setup). Expected: PASS.
- [ ] **Step 5: Format and commit** ("Ship PostgreSQL and server telemetry for deployments").

### Task 3a: Baseline measurement (operational, no code)

- [ ] Deploy phase A to BETA with `PLOWSHARE_JFR_DIR` and the management listener set. Run one
  representative job. Save a `pg-stat-snapshot.sh` before/after delta, the JFR file and a Grafana
  screenshot under the scratchpad. Repeat on PROD without JFR. These numbers are the baseline every
  later task is compared with.

---

## Phase B: pure repetition removal (pipeline slice 1)

### Task 4: Hosted tool list once per snapshot

**Files:**
- Modify: `M/relay/tools/ApplicationToolRegistry.java:189-247` (`snapshot`), `:282-288` (`revision`)
- Test: `T/relay/tools/ApplicationToolRegistryTest.java`

**Interfaces:**
- Produces: `Snapshot` gains `List<ToolScopes.Connection> hosted()`; `revision` uses it.

- [ ] **Step 1: Write the failing tests.** `hostedListIsReadOnceForAllCatalogueTools`: a mock
  `ToolScopeConnections` with one provider whose catalogue has 3 tools; `revision(projectId)` →
  `verify(connections, times(1)).connections(project, null)`.
  `firstConnectionPerProviderWinsAfterHoist`: two connections for one provider → each entry carries
  the first.
- [ ] **Step 2: Run and confirm failure** (`times(4)` today).
- [ ] **Step 3: Read `connections(project, null)` once before the provider loop, index first match per
  provider into a `LinkedHashMap`, and carry the list on `Snapshot`.**
- [ ] **Step 4: Run `--tests '*ApplicationToolRegistryTest' --tests '*HostedMcpGrantTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

### Task 5: Application root once per `forCaller`

**Files:**
- Create: `M/agents/ResolvedApplicationRoots.java`
- Modify: `M/agents/DefinitionResolver.java:574-822,1000-1004`, `M/agents/ScopedTools.java:32`,
  `M/relay/tools/ApplicationToolRegistry.java:91-99,282-288`
- Test: `T/agents/DefinitionResolverTest.java`, `T/relay/tools/ApplicationToolRegistryTest.java`

**Interfaces:**
- Produces: `final class ResolvedApplicationRoots implements ApplicationResources` — wraps a delegate
  and memoises `root(Long)` per project id for its own lifetime; `directory` delegates to the
  delegate's `directory` but feeds it the memoised root (the directory walk's own re-check at
  `WorkspaceApplicationResources:74-76` stays).
- Produces: `ScopedTools.revision(Long projectId, ApplicationResources resources)`; the old
  single-argument method becomes a default that passes the registry's own resources.

A `ResolvedApplicationRoots` lives for one `forCaller` call and is never stored in a field.

- [ ] **Step 1: Write the failing test.** `DefinitionResolverTest.oneRootResolutionPerTierOnACacheHit`:
  counting `ApplicationResources` fake; a cache-hit `forCaller` with project and personal tiers →
  `root` called exactly twice (once per tier); today it is 7.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement; thread the per-call instance through `keyFor`, `stamp`, `agentsDirectory`,
  `botsDirectory` and `scopedTools.revision`.**
- [ ] **Step 4: Run `--tests '*DefinitionResolverTest' --tests '*ApplicationToolRegistryTest' --tests '*WorkspaceApplicationResourcesTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

### Task 6: Register each relay topic and publisher once per process

**Files:**
- Create: `M/relay/RegisteredTopics.java`, `M/relay/RelayTopicMissingException.java`
- Modify: `M/relay/ProjectRelayPorts.java:185-231`, `M/relay/DurableRelay.java:43-51`,
  `M/relay/JdbcRelayRepository.java:229` (throw the typed exception), deletion paths
  `M/relay/JdbcRelayOperationRepository.java:216`, `M/relay/JdbcRelayRetirementRepository.java:55`
- Test: `T/relay/ProjectRelayPortsTest.java`, `T/relay/RegisteredTopicsTest.java`

**Interfaces:**
- Produces: `interface RegisteredTopics { boolean known(Relay.TopicKey key); void remember(Relay.TopicKey key); void forget(Relay.TopicKey key); }`
  with a bounded LRU implementation (4,096 keys; same for publisher accounts).
- Produces: `RelayTopicMissingException extends IllegalStateException`, thrown by `appendChecked`
  when the locked topic row is absent, before any append.

`publish` registers only when `known` is false, then `remember`s. On `RelayTopicMissingException` it
`forget`s, registers and publishes once more; a second miss propagates. The retry is safe because the
failed attempt rolled back before appending. In-process deletion paths call `forget`. The two
`require(INGRESS)` calls stay: the second is a deliberate recheck after the ancestry lookup
(`authority_or_project_identity_change_during_parent_lookup_prevents_publication`).
`rememberPublisher` uses the same memo for accounts.

- [ ] **Step 1: Write the failing tests.** `secondPublishToSameTopicDoesNotRegisterAgain`
  (`verify(relay, times(1)).registerTopic`, `verify(repository, times(1)).rememberPublisher`);
  `topicDeletedElsewhereIsReRegisteredOnce` (first `publish` throws `RelayTopicMissingException` →
  one re-register, one successful publish); `secondMissPropagates`.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `--tests '*ProjectRelayPortsTest' --tests '*RegisteredTopicsTest' --tests '*JdbcRelayRepositoryTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

### Task 7: One session check per socket request within the bound

**Files:**
- Create: `M/ws/RequestSessionValidity.java`
- Modify: `M/ws/EventChannelHandler.java:733,834-840,895,914`, `M/ws/Asking.java:55`, `M/ws/FrameRouter.java:284,334-335`
- Test: `T/ws/RequestSessionValidityTest.java`, `T/ws/ConcurrentEventChannelTest.java`

**Interfaces:**
- Produces: `final class RequestSessionValidity implements Runnable` constructed with
  `(SocketAuthorization authorization, WebSocketSession socket, LongSupplier nanos, Duration bound, long verifiedAtNanos)`.
  `run()` rechecks only when the last success is older than `bound`; a refusal throws as today and is
  never remembered. `bound` is `plowshare.projection-cache.notified.staleness`.

The check at frame receipt (733) seeds it; `routeConcurrent` (895), the `Asking.queryValidity`
lambda (914), `delivery.validity` and `FrameRouter` (284, 335) all use the same instance, so a
request inside the bound costs one check instead of four.

- [ ] **Step 1: Write the failing tests.** `oneCheckForARequestInsideTheBound` (mock `SessionAuthority`,
  four `run()` calls within 1 s → `sessionCurrent` once); `recheckAfterTheBound`;
  `revokedSessionStopsDeliveryWithinBound` (revoke, advance the clock past 2 s → `run()` throws);
  `refusalIsNeverRemembered`.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement and thread through the handler.**
- [ ] **Step 4: Run `--tests '*RequestSessionValidityTest' --tests '*EventChannelTest' --tests '*ConcurrentEventChannelTest' --tests '*ConcurrentRequestFailureTest' --tests '*SocketAuthorizationBatchTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

---

## Phase C: permission checks

### Task 8: Reuse fetched grants in admission and the walk

**Files:**
- Modify: `M/access/ScopedAuthority.java:8`, `M/access/LiveScopedAuthority.java:50-93`,
  `M/access/PinnedOperationAuthority.java:166-209,423-501`, `M/access/ApplicationGrantLineage.java:36-38`
- Test: `T/access/PinnedOperationAuthorityTest.java`; create `T/access/LiveScopedAuthorityTest.java`

**Interfaces:**
- Produces: `boolean allowsUsing(GrantRecipient recipient, AuthorityScope target, AuthorityAction action, ScopedGrant binding)`
  on `ScopedAuthority` — the caller supplies the live binding it already fetched; the id-only
  overload stays for the other five callers and delegates after one `live` read.
- Produces: within one `requireOwn`, `grants.live(source)` is read once and each support item's
  binding is looked up from that list; `lineage` and `applications.read(project)` are memoised per
  walk in a local map.

- [ ] **Step 1: Write the failing test.** `PinnedOperationAuthorityTest.walkReadsLiveGrantsOncePerAdmission`:
  an admission with 3 support items → `verify(grants, times(1)).live(source)` and
  `times(1)` `lineage` per distinct grant; `admitReadsLiveGrantsOnce` for `admit`.
  `LiveScopedAuthorityTest.bindingOverloadMatchesIdOverload` for allowed, revoked and wrong-scope bindings.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `--tests '*PinnedOperationAuthorityTest' --tests '*LiveScopedAuthorityTest' --tests '*VerifiedAdmissionsTest' --tests '*ContainmentHopTest' --tests '*OperationRecordsReadBudgetTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

### Task 9: The after-tool check is a disclosure gate and says so

**Files:**
- Create: `M/access/AuthorityWithdrawnException.java`
- Modify: `M/access/PinnedOperationAuthority.java` (throw it from `require` refusals), `M/agents/JobRuntime.java:4042,4102,4157-4164`, `M/access/VerifiedAdmissions.java:10-12` (Javadoc)
- Modify: `docs/coding-standards.md:258-262`
- Test: `T/agents/OperationExecutionTest.java`

**Interfaces:**
- Produces: `AuthorityWithdrawnException extends CallerFault` (keeps every existing catch working).

When the after-tool check refuses, the result is withheld, tool.post is skipped and the model is told:
`"the result of '<tool>' was withheld because this run's authority changed while it ran. Any change the tool made has already happened."`
with `told = ToolLines.ERROR`. The run then ends at the next step's `require`, as today.

Coding standards: append to the paragraph at :262:
"The one bounded exception is a verified operation admission: the same admission, under the same
authority snapshot, may be answered from memory for at most the notified staleness bound (2 s by
default) outside a write transaction. A refusal is never reused, and checks inside a write
transaction always re-verify."

- [ ] **Step 1: Write the failing test.** `revokedDuringToolWithholdsResultAndSaysWhy`: a fake tool
  whose `run` revokes the grant; assert the model-facing line contains `withheld because this run's authority changed`,
  does not contain `fault in the tool`, and tool.post was not called.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement and edit the docs.**
- [ ] **Step 4: Run `--tests '*OperationExecutionTest' --tests '*RunGateWiringTest' --tests '*PinnedOperationAuthorityTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

### Task 10: In-transaction fences for mutating tools

**Files:**
- Create: `M/access/OperationFence.java`
- Modify: `M/agents/JobRuntime.java:4670-4975` (build fences per tool), `M/agents/RelayPublishTool.java`,
  `M/relay/GrantedRelayPublishing.java:45-108`, `M/relay/JdbcRelayPublishingRepository.java:59-61`,
  `M/information/InformationCatalogue.java` (admit, revise, evidence, report, relationship writes)
- Create: `docs/operation-write-fences.md` (the audit table: every mutating tool, its transaction and its fence)
- Test: `T/relay/GrantedRelayPublishingTest.java`, `T/information/InformationCatalogueFenceTest.java`

**Interfaces:**
- Produces: `@FunctionalInterface interface OperationFence { void require(); static OperationFence none(); }` —
  a fence built by `JobRuntime` as `() -> { admittedAuthority.run(); operations.require(operation); }`.
  It runs inside the write transaction, where `BoundedVerifiedAdmissions` already bypasses itself, so it
  always walks.
- Consumes: the memory write fence shape in `embedding/BoundMemoryWrites.mutation` (:444-446) as precedent.

Relay publish and information writes take an `OperationFence` and run it first inside their
transaction, after taking the row lock they need. Socket and system callers pass `OperationFence.none()`
(their own checks are unchanged). File, todo, board, studio and messaging tools have no database
transaction to fence; the audit doc records that and that only the JobRuntime checks apply to them.

- [ ] **Step 1: Write the failing tests.** `revocationBeforeCommitRollsBackPublish`: a fence that
  throws → `JdbcRelayPublishingRepository` sees the transaction roll back and nothing is appended
  (mocked `UnitOfWork` that records commit/rollback). `informationReviseRunsFenceInsideTransaction`
  for `InformationCatalogue`, with fence order asserted by `InOrder`.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement and write the audit doc.**
- [ ] **Step 4: Run `--tests '*GrantedRelayPublishingTest' --tests '*InformationCatalogueFenceTest' --tests '*MemoryWriteEntryPointsTest' --tests '*RelayPublishTool*'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

---

## Phase D: operation context and relay waits (pipeline slices 2 and 3)

### Task 11: One context per agent tool call

**Files:**
- Create: `M/relay/tools/ToolCallContext.java`
- Modify: `M/relay/tools/ApplicationToolRegistry.java:160-187,297-307,416-471`
- Test: `T/relay/tools/ApplicationToolRegistryTest.java`

**Interfaces:**
- Produces: `record ToolCallContext(long projectId, String project, ApplicationServerSettings settings, Snapshot snapshot, Entry entry, Instant resolvedAt)`.
  Built once at listing in `tools(...)` and held by `LiveTool`.

`LiveTool.run` reuses the context when it is younger than the staleness bound and the registry's
current `revision(projectId)` equals the context's snapshot revision; it then calls `assigned` once
(the authority recheck that must stay) and invokes. Otherwise it rebuilds the context once, as today.
`projects.id`, settings, `connections` and `snapshot` are no longer recomputed on the fast path.

- [ ] **Step 1: Write the failing tests.** `runReusesListingContextInsideTheBound`
  (`verify(connections, never()).connections(any(), any())` during `run`, `assigned` once, `projects.id` once);
  `runRebuildsAfterRevisionChange`; existing `every_call_checks_account_and_agent_permissions` still passes.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `--tests '*ApplicationToolRegistryTest' --tests '*HostedMcpGrantTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

### Task 12: Relay tool waits wake on signals, not 100 ms polls

**Files:**
- Modify: `M/relay/tools/ProjectRelayToolInvocations.java:58-259`
- Test: `T/relay/tools/ProjectRelayToolInvocationsTest.java`

**Interfaces:**
- Consumes: `ProjectionChangeSignals` (`Domain.RELAY`, `Domain.AUTHORITY`) as `ProjectRelayPorts.observe` (:240-290) does.

The loop waits on relay/authority signals with a ceiling of 1 s per wait. On a relay wake it checks
`relay.retained`. It re-authorizes on an authority wake or when the last authorization is older than
the staleness bound, and calls `hosting.permits` once per authorization (it is inside `authorize` for
hosted bindings; the separate call at :147 goes).

- [ ] **Step 1: Write the failing tests.** `hostedPermitsOncePerAuthorization`;
  `waitReauthorizesOnAuthoritySignal`; `waitReauthorizesAfterTheBoundWithoutSignals` (fake clock);
  existing cancellation and membership-refusal tests pass.
- [ ] **Step 2: Run and confirm failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `--tests '*ProjectRelayToolInvocationsTest'`; expect PASS.**
- [ ] **Step 5: Format and commit.**

---

## Finish

- [ ] Run `./gradlew check` (no `-PfullDb`).
- [ ] Update `docs/query-efficiency-operation-pipeline-design.md`: mark slices 1–3 implemented and
  link this plan.
- [ ] Deploy to BETA, then PROD, and repeat Task 3a. Report per pattern: the `pg_stat_statements`
  call delta, `plowshare.operation.statements` p50/p95 by kind, and authority check outcomes, against
  the phase A baseline.

Pipeline slice 4 (list reads through the pipeline) and ADR 0068 are separate plans.

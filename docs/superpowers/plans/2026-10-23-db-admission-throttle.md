# Wait-Only Database Admission Throttle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a graduated, wait-only throttle in front of every database connection acquisition, so pool pressure becomes paced and attributable instead of surfacing as an opaque timeout.

**Architecture:** A `DataSource` decorator over the primary Hikari pool acquires a re-entrant per-thread permit on `getConnection()` and releases it when the returned `Connection` is closed. Below a threshold the permit is free; between threshold and ceiling the acquire is delayed on a geometric curve driven by observed acquire latency; at the ceiling delay stops growing and the acquire passes through to Hikari. It never refuses. The primary `DataSource` bean stays the raw `HikariDataSource`; the gated source is a separate non-primary bean, with `JdbcTemplate` and the transaction manager explicitly wired onto it so all ~135 repository beans are covered without changing any of them.

**Tech Stack:** Java 21, Spring Boot (JDBC + autoconfiguration), HikariCP, Micrometer (via the actuator added in #105), JUnit 5.

**Spec:** [docs/superpowers/specs/2026-10-23-db-admission-and-board-cache-design.md](../specs/2026-10-23-db-admission-and-board-cache-design.md) — Parts B and C only. **Part A (board cache) is deferred and must not be built.**

## Global Constraints

- **The throttle waits; it never refuses.** No code path may throw, reject, or emit `Code.CAPACITY_EXHAUSTED` from the database layer. A mid-handler refusal would let the TypeScript SDK replay a partially-committed operation under the same request id.
- The throttle ceiling must be **less than** Hikari's `connection-timeout`, with a gap of **at least 1s** so the two are distinguishable in logs. 3s against the shipped 5000ms leaves 2s. **Validated at boot**, not documented — startup fails clearly, naming both values, if the relationship does not hold. The number is an example; the relationship is the requirement.
- Every bound is **derived from the pool size**, never configured as an independent number that can silently disagree with it.
- Every parameter appears in `application.yml` with its default written out and an environment override. No value governing dispatch, cache or pool behaviour may exist only as a Java annotation default.
- The primary `DataSource` bean must remain a raw `HikariDataSource`. `ProjectionCacheConfiguration` does `if (primary instanceof HikariDataSource configured)` to copy JDBC URL, credentials, driver and init SQL into the `projection-versions` pool; a decorator in that position fails the check silently and misconfigures that pool.
- `AGENTS.md`: only repositories/stores and database configuration may touch JDBC infrastructure. Controllers, tools, adapters and domain services must not receive a `JdbcTemplate`.
- Google Java Style via the Gradle formatter, including tests. `./gradlew format` then `./gradlew formatCheck`.
- **No database tests.** Do not pass `-PfullDb`, do not start PostgreSQL, Docker or Testcontainers. Every task here is testable with fakes; the throttle policy must be unit-testable with an injected clock, sleeper and random source.
- Deployment values are configuration. No hard-coded pool sizes, timeouts or hosts in runtime code.
- New Flyway migrations only; none is expected in this plan.

## Review Focus

Failure modes the spec implies that no happy path exercises. Each has its test pinned to the owning task.

1. **Re-entrant acquisition deadlock** — a non-transactional path holding a connection that issues another query (a `RowMapper` that queries) must not take a second permit; N such paths would otherwise deadlock a semaphore of N. (Task 1)
2. **A leaked permit latches the throttle shut** — a `Connection` that is never closed, or closed twice, must not leave a permit held forever or release one it does not own. (Task 3)
3. **A transaction must hold exactly one permit for its whole span** — released once at commit or rollback, not per statement and not twice. (Task 4)
4. **A ceiling at or above `connection-timeout` must fail startup** with a message naming both values, rather than booting into an indistinguishable configuration. (Task 2)
5. **Wiring must not break the second pool** — after the gated source is introduced, `ProjectionCacheConfiguration`'s `instanceof HikariDataSource` on the primary must still match. (Task 4)

---

### Task 1: The throttle policy

Pure logic, no JDBC. This is the heart of the change and must be fully testable without a database.

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/AdmissionThrottle.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/AdmissionThrottleTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `AdmissionThrottle(int permits, int threshold, Duration baseQuantum, Duration ceiling, LongSupplier nanoClock, DoubleSupplier random, Sleeper sleeper)`
  - `interface Sleeper { void sleep(Duration d) throws InterruptedException; }`
  - `Admission acquire() throws InterruptedException` where `Admission` is `AutoCloseable` and `close()` is idempotent
  - `int held()` — permits currently held, for tests and metrics
  - `Duration lastDelay()` — the delay applied to the most recent acquire, for tests and metrics
  - `void observed(Duration acquireLatency)` — feeds the pressure signal

- [ ] **Step 1: Write the failing tests**

```java
@Test void below_the_threshold_grants_immediately()        // held() < threshold -> lastDelay() is ZERO
@Test void above_the_threshold_delays_on_a_geometric_curve()
  // with random() = 0 and threshold 10, permits 20, base 20ms, ceiling 3s:
  // the delay at each successive occupancy strictly increases and never exceeds the ceiling
@Test void the_delay_never_reaches_the_ceiling_below_full_occupancy()
@Test void at_full_occupancy_the_delay_stops_growing_and_the_acquire_still_proceeds()
  // the throttle NEVER refuses: acquire() returns an Admission, it does not throw
@Test void jitter_is_proportional_to_the_quantum()
  // random() = 0 gives q/2, random() = 1 approaches q; never outside [q/2, q]
@Test void a_reentrant_acquire_on_the_same_thread_takes_no_second_permit()
  // Review Focus 1: held() stays 1 across a nested acquire, and only the outer close releases
@Test void a_reentrant_acquire_does_not_deadlock_at_full_occupancy()
  // Review Focus 1: fill every permit from one thread's nesting; the nested acquire returns
@Test void close_is_idempotent()                            // double close does not drive held() negative
@Test void the_pressure_level_decays_when_latency_falls()    // observed(small) after observed(large) reduces the delay
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*AdmissionThrottleTest'`
Expected: FAIL — `AdmissionThrottle` does not exist.

- [ ] **Step 3: Implement `AdmissionThrottle`**

Permits are re-entrant per thread: hold a `ThreadLocal<int>` depth and take a real permit only on the 0→1 transition, releasing on 1→0. That is what makes the unit "one slot per logical unit of work" and what prevents Review Focus 1.

The delay for occupancy `n` above the threshold is `base * ratio^(n - threshold)` where
`ratio = (ceiling / base) ^ (1 / (permits - threshold - 1))`, computed once from the
configured values rather than hard-coded — a literal ×2 breaks silently when the pool is
resized, which is how `requestGlobal(8)` became an unwritten proxy for a pool of 10. The
actual wait is drawn from `[q/2, q]` using the injected `random`.

The pressure signal is observed acquire latency, not occupancy: `observed(Duration)` feeds
a decaying level that scales the curve. Choose the smoothing (a CoDel-style interval or an
EWMA) and the window; the constraint is that it must recover — the level must fall again
once latency falls, which `the_pressure_level_decays_when_latency_falls` pins.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*AdmissionThrottleTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/AdmissionThrottle.java plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/AdmissionThrottleTest.java
git commit -m "feat: add a wait-only database admission throttle policy"
```

---

### Task 2: Properties, defaults and boot validation

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionProperties.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/PoolAdmissionPropertiesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `@ConfigurationProperties("plowshare.pool-admission")` record
  `PoolAdmissionProperties(boolean enabled, int permitMargin, double thresholdFraction, Duration baseQuantum, Duration ceiling, Duration pressureWindow)`
  with `@DefaultValue`s: `enabled` true, `permitMargin` 2, `thresholdFraction` 0.5, `baseQuantum` 20ms, `ceiling` 3s, `pressureWindow` — builder's choice, stated.
  Plus `int permits(int poolSize)` and `int threshold(int poolSize)`, both **derived** from the pool size.

- [ ] **Step 1: Write the failing tests**

```java
@Test void permits_and_threshold_are_derived_from_the_pool_size()
  // poolSize 20, margin 2, fraction 0.5 -> permits 18, threshold 9
@Test void the_ceiling_must_be_below_the_pool_acquire_timeout()
  // Review Focus 4: validating against a 5000ms connection-timeout, a 5s ceiling throws,
  // and the message names both values
@Test void the_ceiling_must_be_distinguishable_from_the_acquire_timeout()
  // the rule: ceiling < acquireTimeout AND (acquireTimeout - ceiling) >= 1s,
  // so the two are far enough apart to tell apart in logs.
  // 4500ms against a 5000ms timeout throws; the message names both values and the gap.
@Test void a_three_second_ceiling_against_the_shipped_timeout_is_accepted()
  // 3000ms against 5000ms leaves a 2s gap
@Test void invalid_bounds_are_rejected()
  // margin negative, fraction outside (0,1), non-positive base or ceiling, base >= ceiling
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*PoolAdmissionPropertiesTest'`
Expected: FAIL — the type does not exist.

- [ ] **Step 3: Implement the record with a compact-constructor validation and a `validateAgainst(Duration acquireTimeout)` method**

The ceiling check cannot live in the compact constructor, because the acquire timeout
comes from `spring.datasource.hikari`, not from this record — hence the separate method,
called at wiring time in Task 4 so a bad combination fails the boot.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*PoolAdmissionPropertiesTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionProperties.java plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/PoolAdmissionPropertiesTest.java
git commit -m "feat: add validated pool admission properties derived from the pool size"
```

---

### Task 3: The DataSource decorator and its Connection proxy

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/ThrottledDataSource.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/ThrottledDataSourceTest.java`

**Interfaces:**
- Consumes: `AdmissionThrottle` (Task 1).
- Produces: `ThrottledDataSource(DataSource delegate, AdmissionThrottle throttle)` extending `AbstractDataSource`; `getConnection()` and `getConnection(String, String)` return a proxy whose `close()` releases the permit.

Note `FreshConnections` in `JdbcProjectionVersions` is the nearest precedent but a weaker
one than it looks: it only *configures* the returned `Connection` and never wraps it.
Releasing a permit requires a real proxy, so unclosed connections, double closes and
`unwrap()` callers all become live concerns — hence the tests below.

- [ ] **Step 1: Write the failing tests**

```java
@Test void a_connection_is_borrowed_from_the_delegate_and_the_permit_is_held()
@Test void closing_the_connection_releases_the_permit()         // held() back to 0
@Test void closing_twice_releases_once()                        // Review Focus 2
@Test void a_failure_to_borrow_releases_the_permit()
  // the delegate throws SQLException -> held() is 0, and the SQLException propagates
  // (it is NOT converted into a refusal: the throttle never refuses)
@Test void the_observed_acquire_latency_is_fed_to_the_throttle()
@Test void unwrap_and_isWrapperFor_reach_the_underlying_connection()
@Test void the_proxy_delegates_every_other_call()               // a statement call reaches the delegate
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*ThrottledDataSourceTest'`
Expected: FAIL — the type does not exist.

- [ ] **Step 3: Implement `ThrottledDataSource`**

Acquire the permit, time the delegate's `getConnection()`, feed that duration to
`observed(...)`, and return a proxy. Use a JDK dynamic proxy or an explicit delegating
wrapper — the builder's choice; what matters is that `close()` releases exactly once and
that a borrow failure does not leak the permit.

Document the one accepted hazard: a caller that never closes its connection holds a permit
until the pool's own leak detection or the process ends. That is the same contract Hikari
itself has, so it is not made worse here, but the throttle makes the consequence wider —
state it where the proxy is written.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*ThrottledDataSourceTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/ThrottledDataSource.java plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/ThrottledDataSourceTest.java
git commit -m "feat: throttle connection acquisition through a DataSource decorator"
```

---

### Task 4: Wiring — gated template and transaction manager, raw primary

The structurally delicate task. `JdbcTemplate` and `PlatformTransactionManager` are currently **Boot-autoconfigured**; no explicit bean for either exists, and ~135 beans inject them. Both autoconfigurations are `@ConditionalOnMissingBean`, so declaring our own over the gated source wins and every repository is covered without being touched.

**Files:**
- Create: `plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionConfiguration.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/PoolAdmissionConfigurationTest.java`

**Interfaces:**
- Consumes: `ThrottledDataSource` (Task 3), `PoolAdmissionProperties` (Task 2), `AdmissionThrottle` (Task 1).
- Produces: a non-`@Primary` gated `DataSource` bean, plus `JdbcTemplate` and `DataSourceTransactionManager` beans built on it. The primary `DataSource` bean is left untouched.

- [ ] **Step 1: Write the failing tests**

```java
@Test void the_primary_data_source_is_still_a_raw_hikari_data_source()
  // Review Focus 5: ProjectionCacheConfiguration's `instanceof HikariDataSource` must still match
@Test void the_jdbc_template_and_transaction_manager_use_the_gated_source()
@Test void flyway_and_the_health_indicator_keep_the_ungated_primary()
  // asserted through bean identity: the primary bean is not the ThrottledDataSource
@Test void a_transaction_holds_one_permit_for_its_whole_span()
  // Review Focus 3: with a fake delegate, held() is 1 across two statements inside one
  // TransactionTemplate.execute, and 0 after it returns
@Test void permits_are_derived_from_the_configured_pool_size()
@Test void a_ceiling_at_or_above_the_acquire_timeout_fails_the_boot()
  // Review Focus 4 at the wiring level: validateAgainst is actually called
@Test void disabling_the_throttle_leaves_the_autoconfigured_wiring_alone()
  // enabled=false -> the JdbcTemplate's DataSource is the raw primary
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*PoolAdmissionConfigurationTest'`
Expected: FAIL — the configuration does not exist.

- [ ] **Step 3: Implement `PoolAdmissionConfiguration`**

Read the pool size and acquire timeout from the primary `HikariDataSource` so the derived
bounds and the boot validation use the values actually in force, not a second copy of them.
Call `properties.validateAgainst(acquireTimeout)` here, so a bad relationship stops the
boot. Gate the whole configuration on `enabled`, defaulting to on.

- [ ] **Step 4: Run them, plus the suites most likely to notice a wiring change**

Run: `./gradlew :plowshare-server:test --tests '*PoolAdmission*' --tests '*ProjectionCache*' --tests '*DatasourceConfigurationTest'`
Expected: PASS. `ProjectionCachesTest` is the one that would catch a broken second pool.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionConfiguration.java plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/PoolAdmissionConfigurationTest.java
git commit -m "feat: route the shared JdbcTemplate through the throttled data source"
```

---

### Task 5: Metrics and attribution

Without this the change does not close the original complaint: the whole point of a wait-only throttle is that pressure is legible to an operator, since no client-visible outcome exists.

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionConfiguration.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/PoolAdmissionMetricsTest.java`

**Interfaces:**
- Consumes: `AdmissionThrottle.held()`, `lastDelay()` (Task 1); Micrometer's `MeterRegistry`, available via the actuator added in #105.
- Produces: meters named under `plowshare.pool.admission` — permits held, permits configured, applied delay, and a counter of acquires that were delayed at all.

- [ ] **Step 1: Write the failing tests**

```java
@Test void permit_occupancy_and_capacity_are_published_as_gauges()
@Test void applied_delay_is_published_and_records_a_delayed_acquire()
@Test void meter_cardinality_is_fixed()
  // no unbounded tag: attribution labels come from a bounded set, never a raw call site
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*PoolAdmissionMetricsTest'`
Expected: FAIL — no meters registered.

- [ ] **Step 3: Register the meters against a `SimpleMeterRegistry` in tests and the injected registry in production**

Keep tags fixed-cardinality. `DispatchTelemetry` is the cautionary example: it is
package-private with no binding, which is why dispatch pressure is still unobservable
after #105 — do not repeat that here.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*PoolAdmission*'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add -u plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/ plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/
git commit -m "feat: publish pool admission pressure as metrics"
```

---

### Task 6: Part C — every parameter visible in configuration

**Files:**
- Modify: `plowshare-server/src/main/resources/application.yml`
- Modify: `docs/server-administration.md`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/ConfigurationVisibilityTest.java`

**Interfaces:**
- Consumes: `PoolAdmissionProperties` (Task 2).
- Produces: a `plowshare.pool-admission` block, and the previously invisible keys surfaced.

- [ ] **Step 1: Write the failing test**

Follow `DatasourceConfigurationTest`, which already binds the **real shipped**
`application.yml` with a `YamlPropertySourceLoader` and a real `Binder` — reuse that
approach rather than an inline fixture, or the test cannot catch drift.

```java
@Test void every_pool_admission_key_is_declared_in_application_yml()
@Test void every_transport_dispatch_key_is_declared_in_application_yml()
  // request-depth, request-per-connection, request-global, request-observers,
  // event-read-concurrency, event-read-global-concurrency, packet-memory-bytes,
  // request-shutdown-drain
@Test void every_projection_cache_key_is_declared_in_application_yml()
  // the 14 keys of ProjectionCacheProperties
@Test void each_declared_key_carries_an_environment_override()
  // the ${ENV:default} form, as the keepalive block already uses
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests '*ConfigurationVisibilityTest'`
Expected: FAIL — `plowshare.transport` currently contains only `keepalive`, and
`plowshare.projection-cache` has no block at all.

- [ ] **Step 3: Add the blocks to `application.yml`**

Write each key with its current default and an environment override, following the
`keepalive` block's `key: ${ENV_VAR:default}` style and the file's habit of explaining
*why* a value is what it is. Defaults must match the Java ones exactly — the test above
is what holds them together. The dispatch keys have no environment variable today; give
them one, consistently named. Show derived values as derived: the admission permits and
threshold come from the pool size, and the comment must say so rather than inviting an
operator to set two numbers that can disagree.

- [ ] **Step 4: Extend `docs/server-administration.md`**

It already documents the `request-*` and pool keys from #105. Add the `pool-admission`
block, the projection-cache budgets, and the ceiling-below-acquire-timeout invariant with
the consequence of breaking it (the boot fails, by design).

- [ ] **Step 5: Run the test and the datasource binding test**

Run: `./gradlew :plowshare-server:test --tests '*ConfigurationVisibilityTest' --tests '*DatasourceConfigurationTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew format formatCheck
git add plowshare-server/src/main/resources/application.yml docs/server-administration.md plowshare-server/src/test/java/io/aeyer/plowshare/server/ConfigurationVisibilityTest.java
git commit -m "feat: surface dispatch, cache and admission settings in configuration"
```

---

### Task 7: Whole-change verification

**Files:** none.

- [ ] **Step 1: Run the repository check**

Run: `./gradlew check`
Expected: PASS, with no Docker, Testcontainers or `-PfullDb` invoked. Note this includes
an eslint task that per-task verification does not cover.

- [ ] **Step 2: Confirm no refusal path was introduced**

Run: `grep -rn "CAPACITY_EXHAUSTED" plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/`
Expected: no matches. The throttle waits; it never refuses.

- [ ] **Step 3: Confirm the second pool is still configured correctly**

Run: `./gradlew :plowshare-server:test --tests '*ProjectionCaches*'`
Expected: PASS — this is the test that would notice the `instanceof HikariDataSource`
check silently failing.

- [ ] **Step 4: Report what passed, and what remains unverified**

State plainly that the throttle's parameters are reasoned defaults rather than measured
ones: the deployment's actuator was unreachable and `max_connections` could not be read,
so the pool size this derives from rests on PostgreSQL's stock default. Say that the
metrics added in Task 5 are what make a later re-tune possible, and that nothing here was
exercised against a real database.

# Dispatch Capacity and Honest Backpressure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make dispatch congestion block rather than go silent — the server advertises its real per-connection depth and always answers, and the SDK waits for a slot instead of erroring or timing out.

**Architecture:** Three independent changes that compose. The server stops capping request execution with a single global number and instead advertises a per-connection queue depth it will honour, answering `CAPACITY_EXHAUSTED` when that depth is reached rather than tearing down the connection. The TypeScript SDK turns its window bound into a wait, and moves the request deadline from `ask()` to `launch()` so queued time is flow control rather than timeout budget. Three hot-path reads are reclassified off the stop-the-world barrier default so they can overlap at all.

**Tech Stack:** Java 21 / Spring Boot (server, protocol), TypeScript + vitest (SDK), Spring JDBC + HikariCP, Gradle.

**Spec:** [docs/superpowers/specs/2026-10-23-dispatch-capacity-design.md](../specs/2026-10-23-dispatch-capacity-design.md)

## Global Constraints

- Google Java Style via the Gradle formatter. Run `./gradlew format` and `./gradlew formatCheck` after Java edits; `./gradlew check` before handing off.
- Database tests stay behind `@Tag("full-db")` and the explicit `-PfullDb` flag. The only expected `full-db` case here is Task 9's pool behaviour; do not enable it for other tasks.
- No shipped Flyway migration is edited. No schema change is expected in this plan.
- Deployment values stay configuration. No hard-coded hosts, ports, pool sizes or credentials in runtime code.
- Raw JSON types must not cross into internal contracts; convert at the transport boundary with validation before business logic.
- `ConcurrentDispatch.MAX_PENDING = 64` remains the protocol ceiling and is not raised. Advertised depth is a value within `[1, 64]`.
- Wire-visible changes must keep an older client working: the client's `transport.dispatch` selection payload stays exactly `{mode}` (the server asserts `request.payload().keySet().equals(Set.of("mode"))`).
- Ordered/legacy-mode connections are out of scope; do not change `EventReadCapacity`'s waiting behaviour.

## Review Focus

Failure modes the spec implies but which no single task's happy path exercises. Each has its test pinned to the owning task.

1. **A server advertising a depth below what the client already has in flight** — a reconnect or renegotiation must not leave `admitted > maximum`; the client should stop admitting, not corrupt its counter. (Task 5)
2. **A slot freed by a failure path rather than a reply** — `release()` runs from strand, abort, writer failure and deadline expiry; a waiter must be woken on every one, or blocking becomes a permanent hang. (Task 5)
3. **Deadline moved to `launch()` leaving a never-launched request with no timer** — a request waiting on a slot on a connection that then closes must still settle, via strand rather than deadline. (Task 6)
4. **A refusal reply racing the one-handoff invariant** — `Connection.handingOff` throws `IllegalStateException` on a second settling handoff, and refusals are most frequent exactly under load. (Task 4)
5. **Reclassified reads overlapping a barrier on the same connection** — `board.topics` as `FINITE` must still be excluded while a barrier holds that connection, or classification breaks the barrier contract. (Task 8)

---

### Task 1: A transport-origin congestion code

**Files:**
- Modify: `plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/Code.java`
- Test: `plowshare-protocol/src/test/java/io/aeyer/plowshare/protocol/frames/CodeTest.java` (create if absent)

**Interfaces:**
- Consumes: nothing.
- Produces: `Code.CAPACITY_EXHAUSTED` — HTTP status `429`, slug `"capacity_exhausted"`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void capacity_exhausted_is_a_transport_origin_code() {
  assertEquals(429, Code.CAPACITY_EXHAUSTED.status());
  assertEquals("capacity_exhausted", Code.CAPACITY_EXHAUSTED.slug());
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-protocol:test --tests '*CodeTest'`
Expected: FAIL — cannot find symbol `CAPACITY_EXHAUSTED`.

- [ ] **Step 3: Add `CAPACITY_EXHAUSTED(429, "capacity_exhausted")` to `Code`**

Place it after `MODEL_UNAVAILABLE`. Document it in that constant's style: it came from outside `ApiExceptionHandler`'s table and has no HTTP twin, because per-connection dispatch depth is a transport condition no controller can raise. State that nothing was started and the request is safe to retry.

- [ ] **Step 4: Run the protocol tests and the frame shape test**

Run: `./gradlew :plowshare-protocol:test --tests '*CodeTest'` then `./gradlew :plowshare-server:test --tests '*FrameShapeTest'`
Expected: both PASS. `FrameShapeTest` reads controllers to police *success* codes, so a new failure code must not trip it.

- [ ] **Step 5: Commit**

```bash
git add plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/Code.java plowshare-protocol/src/test/java/io/aeyer/plowshare/protocol/frames/CodeTest.java
git commit -m "feat: add CAPACITY_EXHAUSTED for dispatch backpressure"
```

---

### Task 2: The server advertises its own per-connection depth

**Files:**
- Modify: `plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/transport/ConcurrentDispatch.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java:829`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelConfig.java`
- Test: `plowshare-protocol/src/test/java/io/aeyer/plowshare/protocol/transport/ConcurrentDispatchTest.java` (create if absent), `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ConcurrentRequestsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ConcurrentDispatch.Selected(String mode, int maxPending, int maxIdBytes)` accepting `maxPending` in `[1, MAX_PENDING]`; `EventChannelHandler.useRequestDepth(int depth)`; property `plowshare.transport.request-depth` defaulting to `8`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void selected_accepts_any_depth_within_the_ceiling() {
  assertEquals(8, new ConcurrentDispatch.Selected("concurrent-v1", 8, 128).maxPending());
}

@Test
void selected_rejects_depth_outside_the_ceiling() {
  assertThrows(IllegalArgumentException.class,
      () -> new ConcurrentDispatch.Selected("concurrent-v1", 0, 128));
  assertThrows(IllegalArgumentException.class,
      () -> new ConcurrentDispatch.Selected("concurrent-v1", 65, 128));
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-protocol:test --tests '*ConcurrentDispatchTest'`
Expected: FAIL — the depth-8 case throws "Invalid dispatch selection".

- [ ] **Step 3: Relax `Selected`'s validation to the range**

Replace the `maxPending != MAX_PENDING` equality with `maxPending < 1 || maxPending > MAX_PENDING`. Keep `mode` and `maxIdBytes` exact. The no-arg `Selected()` convenience still yields `MAX_PENDING` and stays for callers that want the ceiling.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-protocol:test --tests '*ConcurrentDispatchTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing server test**

Add to `ConcurrentRequestsTest`:

```java
@Test
void the_handshake_reply_carries_the_configured_depth() // asserts the transport.dispatch
  // acknowledgement payload's maxPending equals the configured depth, not 64
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentRequestsTest'`
Expected: FAIL — reply carries 64.

- [ ] **Step 7: Make the server advertise the configured depth**

Add a `requestDepth` field to `EventChannelHandler` defaulting to `8`, with `synchronized void useRequestDepth(int depth)` rejecting values outside `[1, ConcurrentDispatch.MAX_PENDING]` and refusing change after `readDispatchStarted`, matching `useRequestDrain`'s guard style. At line 829 construct `new ConcurrentDispatch.Selected(ConcurrentDispatch.MODE, requestDepth, ConcurrentDispatch.MAX_ID_BYTES)` in place of `new ConcurrentDispatch.Selected()`. In `EventChannelConfig`, add an `@Value("${plowshare.transport.request-depth:8}")` field and call `channel.useRequestDepth(...)` beside the existing `useRequestDrain` call.

- [ ] **Step 8: Run the server dispatch tests**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentRequestsTest' --tests '*ConcurrentResourceOrderingTest' --tests '*ConcurrentHandoffTest'`
Expected: PASS.

- [ ] **Step 9: Format and commit**

```bash
./gradlew format formatCheck
git add -A plowshare-protocol plowshare-server
git commit -m "feat: advertise the server's own per-connection dispatch depth"
```

---

### Task 3: The Java SDK honours an advertised depth

**Files:**
- Modify: `sdk/java/src/main/java/io/aeyer/plowshare/sdk/Plowshare.java:395,529`
- Test: `sdk/java/src/test/java/io/aeyer/plowshare/sdk/AsyncOpeningTest.java` (the existing owner of handshake negotiation)

Note the Gradle project for this module is `:plowshare-sdk`, not `:sdk:java`.

**Interfaces:**
- Consumes: Task 2's ranged `Selected`.
- Produces: a negotiated window field on the client, gating admission instead of the `MAX_PENDING` constant.

- [ ] **Step 1: Write the failing test**

```java
@Test
void a_lower_advertised_depth_is_accepted_and_bounds_admission()
  // handshake acknowledges maxPending = 4; the client connects, and the fifth
  // concurrent request is not admitted while four are outstanding
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plowshare-sdk:test --tests '*AsyncOpeningTest'`
Expected: FAIL — the client rejects the handshake because `asInt() != MAX_PENDING`.

- [ ] **Step 3: Accept the range and store the negotiated window**

At line 395 replace the `!= MAX_PENDING` comparison with a `[1, MAX_PENDING]` range check, and record the acknowledged value in a new field (default `MAX_PENDING` until negotiated). At line 529 gate on that field rather than the constant.

Leave `outgoing` (`ArrayBlockingQueue<>(MAX_PENDING)`) and `completions` (`Semaphore(MAX_PENDING)`) sized at the protocol ceiling — they are built before negotiation, and the negotiated value is enforced by the counter check, not by resizing them.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :plowshare-sdk:test --tests '*AsyncOpeningTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add sdk/java
git commit -m "feat: honour the server's advertised dispatch depth in the Java SDK"
```

---

### Task 4: The server answers instead of disconnecting

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java:788-794`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ConcurrentRequestFailureTest.java`

**Interfaces:**
- Consumes: `Code.CAPACITY_EXHAUSTED` (Task 1).
- Produces: a correlated refusal reply on admission capacity refusal, with the connection left open.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void a_full_connection_queue_is_answered_not_disconnected()
  // offer past the advertised depth; assert a reply correlated to that request id
  // carries CAPACITY_EXHAUSTED, and the socket is still open

@Test
void a_refusal_does_not_disturb_an_in_flight_reply_handoff()
  // with one request mid-handoff, a refused admission still replies and does not
  // throw "A reply handoff is already settling"

@Test
void a_malformed_frame_still_disconnects()
  // genuine protocol violations keep today's teardown
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentRequestFailureTest'`
Expected: FAIL — the connection is torn down instead of answered.

- [ ] **Step 3: Separate congestion from protocol violation in `concurrentFrame`**

`Connection.offer` throws `IllegalArgumentException("Request admission capacity exceeded")` for congestion and `IllegalStateException("Request admission closed")` when shutting down, versus validation failures for malformed input. Distinguish the capacity case and reply to it; keep `delivery.stop()` + `disconnect` for the rest.

The refusal is written on the delivery's own output path, not through the dispatcher: the request was never admitted, so it holds no `PacketBudget` reservation and no `Connection.handingOff` slot, and must not take one. Document that exemption where the reply is written.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentRequestFailureTest' --tests '*ConcurrentHandoffTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server
git commit -m "fix: answer a full dispatch queue instead of closing the connection"
```

---

### Task 5: The TypeScript client waits for a slot

**Files:**
- Modify: `sdk/typescript/src/binding/connection.ts:396,483`
- Test: `sdk/typescript/src/binding/concurrent.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: an internal FIFO waiter queue drained by `release()`; `admitted >= maximum` and `dispatched >= maximum` become waits rather than faults.

- [ ] **Step 1: Write the failing tests**

```ts
it('holds a request past the window until a slot frees', async () => {
  // negotiate maxPending 2, issue 3 requests; the third has not been sent
  // answer the first; the third is then sent
});

it('wakes a waiter when a slot frees through failure, not a reply', async () => {
  // negotiate maxPending 1, issue 2; fail the first via socket close
  // (strand) and assert the second is released rather than hanging
});

it('stops admitting when a renegotiated depth is below what is in flight', async () => {
  // with 4 in flight and a depth of 2, no new request is admitted and
  // `admitted` is never driven negative
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd sdk/typescript && pnpm vitest run src/binding/concurrent.test.ts`
Expected: FAIL — the third request rejects immediately with `NOT_SUBMITTED`.

- [ ] **Step 3: Replace the two capacity faults with waits**

At line 396 drop `admitted >= maximum` from the fault condition, keeping `signal?.aborted` and the closed-connection check, and await a slot before reserving. At line 483 replace `if (dispatched >= maximum) detached(...)` with the same wait.

Resolve waiters FIFO from inside `release()`, which already runs on every terminal path — reply, strand, abort, writer failure and deadline expiry — so a slot freed by failure wakes a waiter too. A waiter must also settle when the connection closes: `strand()` has to reject or release parked waiters rather than leave them parked.

- [ ] **Step 4: Run them to verify they pass**

Run: `cd sdk/typescript && pnpm vitest run src/binding/concurrent.test.ts src/binding/packets.test.ts`
Expected: PASS.

- [ ] **Step 5: Typecheck and commit**

```bash
cd sdk/typescript && pnpm typecheck
git add sdk/typescript
git commit -m "feat: block on a full dispatch window instead of failing"
```

---

### Task 6: The request deadline starts at launch

**Files:**
- Modify: `sdk/typescript/src/binding/connection.ts:454-462`
- Test: `sdk/typescript/src/binding/concurrent.test.ts`

**Interfaces:**
- Consumes: Task 5's waiter queue.
- Produces: `asker.cancelDeadline` armed inside `launch()` rather than before it.

- [ ] **Step 1: Write the failing tests**

```ts
it('does not spend the deadline while waiting for a slot', async () => {
  // negotiate maxPending 1, issue 2, advance fake timers past the deadline
  // the second request has not rejected; answer the first, then advance again
  // and only now does it expire
});

it('settles a never-launched request when the connection closes', async () => {
  // a request parked on a full window must reject via strand with
  // NOT_SUBMITTED, not hang once its deadline no longer runs
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd sdk/typescript && pnpm vitest run src/binding/concurrent.test.ts`
Expected: FAIL — the queued request expires while waiting.

- [ ] **Step 3: Arm the deadline inside `launch()`**

Move the `deadline.schedule(...)` block from the promise body into `launch()`, after the `asker.terminal`/`open` guards and before the submit. The abort-signal wiring stays where it is: cancellation must work on a request that has not launched.

Add a comment stating the contract — the deadline governs an in-flight request, and time spent waiting for a slot is flow control, not timeout budget.

- [ ] **Step 4: Run the full binding suite**

Run: `cd sdk/typescript && pnpm vitest run src/binding/`
Expected: PASS. Existing deadline tests measure from submission, which is now when the timer starts; update any that assumed otherwise and note the change in the commit.

- [ ] **Step 5: Typecheck and commit**

```bash
cd sdk/typescript && pnpm typecheck
git add sdk/typescript
git commit -m "fix: start the request deadline at launch, not at admission"
```

---

### Task 7: The client blocks on a server refusal

**Files:**
- Modify: `sdk/typescript/src/binding/connection.ts`
- Test: `sdk/typescript/src/binding/concurrent.test.ts`

**Interfaces:**
- Consumes: `CAPACITY_EXHAUSTED` from Task 4; Task 5's waiter queue.
- Produces: a `CAPACITY_EXHAUSTED` reply is consumed as flow control — the request waits and resubmits rather than settling to the caller.

- [ ] **Step 1: Write the failing tests**

```ts
it('resubmits after a capacity refusal instead of surfacing it', async () => {
  // answer the first submission with CAPACITY_EXHAUSTED, then answer the
  // resubmission with OK; the caller sees one successful Reply and never
  // sees the refusal
});

it('stops resubmitting once the connection closes', async () => {
  // a request waiting to resubmit after a refusal settles via strand
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd sdk/typescript && pnpm vitest run src/binding/concurrent.test.ts`
Expected: FAIL — the refusal resolves to the caller as a non-OK `Reply`.

- [ ] **Step 3: Interpret a capacity refusal as backpressure**

On an answer whose outcome code is `CAPACITY_EXHAUSTED`, do not settle the caller's promise: wait for a slot, then resubmit the same frame under the same `id`. Reuse the request's existing `id` — it is still the correlation for work the server confirmed it never started, so this is not a mutation replay.

The deadline is already running from the first launch (Task 6) and is not re-armed, which bounds the resubmission loop without a separate attempt counter.

- [ ] **Step 4: Run them to verify they pass**

Run: `cd sdk/typescript && pnpm vitest run src/binding/`
Expected: PASS.

- [ ] **Step 5: Typecheck and commit**

```bash
cd sdk/typescript && pnpm typecheck
git add sdk/typescript
git commit -m "feat: treat a capacity refusal as backpressure in the SDK"
```

---

### Task 8: Classify the desktop's read-only hot path

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DispatchPolicy.java`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ConcurrentResourceOrderingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `board.topics`, `swarm.status` and `admin.status` decode to `Kind.FINITE`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void the_desktop_read_hot_path_is_finite()
  // decode returns Kind.FINITE for board.topics, swarm.status and admin.status

@Test
void two_finite_reads_overlap_on_one_connection()
  // both execute concurrently where today they serialize

@Test
void a_finite_read_still_waits_behind_a_barrier_on_its_connection()
  // Review Focus 5: classification must not break the barrier contract
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentResourceOrderingTest'`
Expected: FAIL — all three decode to `BARRIER`.

- [ ] **Step 3: Add the three types to `FINITE`**

Add them to `DispatchPolicy.FINITE`. Record beside the set why each is safe: all three are reads, they declare no resource key, and barrier ordering was never what made them correct — `PostgreSQL transactions/locks/version fences remain the cross-process authority` per slice 04. Note that the `barrier()` fall-through default is deliberately unchanged and that the remaining operations await slice 04's audit.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentResourceOrderingTest' --tests '*ConcurrentRequestsTest'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew format formatCheck
git add plowshare-server
git commit -m "perf: classify the desktop read hot path as finite dispatch"
```

---

### Task 9: Bound the database where the database is

**Files:**
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java:292-293,353-359`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelConfig.java`
- Modify: `plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConcurrentRequests.java`
- Modify: `plowshare-server/src/main/resources/application.yml`
- Test: `plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ConcurrentRequestsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: request dispatch bounds configured independently of event reads; a global bound of `0` meaning unlimited.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void request_bounds_are_independent_of_event_read_concurrency()
  // setting event-read concurrency does not change request dispatch bounds

@Test
void a_global_bound_of_zero_is_unlimited()
  // more connections execute concurrently than any global cap would allow
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentRequestsTest'`
Expected: FAIL — `useReadConcurrency` still sets both, and `0` is rejected by the `global < perConnection` guard.

- [ ] **Step 3: Split the configuration and make zero unlimited**

Stop setting `requestPerConnection`/`requestGlobal` from `useReadConcurrency`; leave that method owning `readCapacity` only. Add `synchronized void useRequestBounds(int perConnection, int global)` with the same post-start guard. In `EventChannelConfig` add `plowshare.transport.request-per-connection` (default `2`) and `plowshare.transport.request-global` (default `0`), and call the new setter.

In `ConcurrentRequests`, accept `global == 0` as unlimited: relax the constructor guard and treat the `finite < finiteLimit` eligibility test as always true when unlimited. Keep `GLOBAL_PENDING` and `CONNECTION_BYTES` admission limits unchanged — depth and byte budgets still bound admission; only the execution-concurrency cap is lifted.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :plowshare-server:test --tests '*ConcurrentRequest*' --tests '*ConcurrentResourceOrderingTest' --tests '*EventChannel*'`
Expected: PASS.

- [ ] **Step 5: Size the connection pool and make it refuse quickly**

In `application.yml` under `spring.datasource`, add a `hikari` block setting `maximum-pool-size: ${PLOWSHARE_DB_POOL:20}` and `connection-timeout: ${PLOWSHARE_DB_CONNECTION_TIMEOUT:5s}`. Comment the reason in the file's established style: 20 is 2× the framework default this previously inherited, 20 plus `projection-versions`' 4 fits PostgreSQL's default `max_connections` of 100 with margin, and a 5s acquire timeout keeps pool starvation a fast, honest failure instead of a 30s wait that imitates a lost response.

- [ ] **Step 6: Add the observability the configuration already assumes**

`application.yml` already declares `management.endpoints.web.exposure.include: health,info,metrics` while `build.gradle.kts` has no actuator dependency, so that configuration is dead and `/actuator/health` 404s. Add `implementation("org.springframework.boot:spring-boot-starter-actuator")` to `plowshare-server/build.gradle.kts`. This exposes `hikaricp.connections.pending` and `hikaricp.connections.active` — the pressure signal this whole change is reasoned from.

- [ ] **Step 7: Verify the build and the pool configuration**

Run: `./gradlew :plowshare-server:test --tests '*EventChannel*'` then `./gradlew format formatCheck`
Expected: PASS.

Do not run `-PfullDb` for this task unless asserting live pool-timeout behaviour; if that assertion is added, tag it `@Tag("full-db")` and state the reason — it needs real PostgreSQL to exhaust a pool and observe the acquire timeout.

- [ ] **Step 8: State the holding-time rule the pool size depends on**

The pool number assumes short connection holds, and nothing currently defends that.
Add a rule to `docs/coding-standards.md` under the query-efficiency material: no
provider, model, embedding or other network call inside a database transaction,
because it holds a pooled connection for a connect-plus-read timeout. Cite
`Archive.java`'s existing defence as the worked example — it embeds *before* opening
its unit of work and explains why. Note that enforcement across the 23 files using
`@Transactional`/`TransactionTemplate` is follow-up work, so this sizing currently
rests on a documented convention rather than a check.

- [ ] **Step 9: Commit**

```bash
git add plowshare-server docs/coding-standards.md
git commit -m "fix: bound the database at the pool and split request dispatch bounds"
```

---

### Task 10: Whole-change verification

**Files:** none.

- [ ] **Step 1: Run the repository check**

Run: `./gradlew check`
Expected: PASS, with no Docker/database tests invoked.

- [ ] **Step 2: Run the TypeScript SDK suite**

Run: `cd sdk/typescript && pnpm test && pnpm typecheck`
Expected: PASS.

- [ ] **Step 3: Confirm `max_connections` on the deployment before trusting the pool value**

The spec records this as unverified. If the deployment's `max_connections` or an existing `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` override cannot be read, report that the pool number rests on PostgreSQL's default of 100 rather than on a measurement.

- [ ] **Step 4: Report what passed and what remains**

State plainly which suites ran, which did not, and that the reported fault was not reproduced end-to-end against the live deployment — the fix is argued from the code path, not from a reproduction.

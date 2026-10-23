# SDK Dispatch Backpressure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Java, Python, Go and .NET SDKs to one dispatch backpressure contract — a waiting
default, an explicit non-blocking form, and the request deadline armed at transmit — and verify
`node` inherits it.

**Architecture:** The contract is defined once in the shared WebSocket conformance harness
(`scripts/sdk-conformance.mjs` plus `test-support/contracts/sdk-conformance.json`), which already
drives the Python, .NET, Go and Node SDKs against one Node server from one fixture. Task 1 adds the
scenarios; Tasks 2-4 and 6 make each SDK pass them. Java is not in that harness and gets equivalent
JUnit tests in Task 5. One contract, four implementations, one place that says what it means.

**Tech Stack:** Java 21 / JUnit, Python 3.11+ / asyncio / pytest, Go 1.23+ / `go test -race`,
.NET 8 / xunit, Node 22.13+ for the harness.

**Spec:** `docs/superpowers/specs/2026-10-23-sdk-dispatch-backpressure-design.md`

## Global Constraints

- The waiting form is the default in every SDK; the non-blocking form is explicit.
- Stated as "the call does not complete until the request is transmitted" — a blocked thread and a
  pending task both satisfy it.
- The deadline arms when the frame is written to the socket, exactly once, and a resubmission never
  re-arms or extends it.
- Backoff: base 100ms, cap 5000ms, doubling on the refusal count, half fixed and half random,
  randomness injectable so tests are deterministic.
- The loop's bound is the deadline armed at first transmit. Do not add a retry ceiling.
- A refused request releases its dispatch count while backing off but keeps its admission slot, so
  it rejoins the back of the dispatch queue and not of the admission queue. The earlier wording here
  said the opposite; see the spec's corrected slot-release paragraph.
- The `control` dispatch negotiation is never resubmitted.
- `maxPending` from the handshake is authoritative, validated within `[1, 64]`.
- Every SDK honours cancellation while waiting for local capacity; a cancelled wait is
  `NOT_SUBMITTED`, reported in that language's own cancellation currency, and leaks no window slot.
- No application request is replayed and no mutation of unknown delivery is retried. A resubmission
  after `CAPACITY_EXHAUSTED` is safe only because the server refused the work outright.
- Deployment values stay configuration: no hard-coded origins, hosts or ports.
- Each SDK keeps its own idioms, test framework and build. This is not a rewrite.
- The shape of the two forms (two methods, a flag, an options field) is the implementer's call
  within each SDK's conventions. The contract is what must match, not the spelling.
- **The fixture states delivery values in SDK vocabulary, not wire codes — no driver maps them.**
  This note previously said the opposite and told each driver to translate. It was wrong: every
  `delivery` and `waitingDelivery` in the fixture is already SDK vocabulary (`UNKNOWN`,
  `NOT_SUBMITTED`), and the one outlier, `deliveryAlternates: ["RESPONSE_LOST"]`, was a defect — the
  TypeScript `ConnectionFault.delivery` getter maps `RESPONSE_LOST` to `UNKNOWN`, so node's
  tolerance of it was unreachable and the flake it was added to fix was still live. The fixture now
  says `["UNKNOWN"]`. Compare against the fixture's value directly.
- **Arm the deadline after acquiring the writer lock, not before it.** The spec says "after any wait
  for the conversion gate, the writer lock or the send lock". Go arms before its packet writer
  semaphore, which is acceptable there because that semaphore is held across one bounded write — but
  Python's `_send_lock` is the case the spec calls out by name, so Python must arm *after* acquiring
  it or it reintroduces the fault in a smaller window.
- **An SDK whose slot release is not synchronous needs a short allowance before closing** in
  `capacity-deadline-survives-waiting`. Node transmits the released sibling synchronously inside the
  expiring request's rejection path, so it needs none; Go's released caller is a separate goroutine
  and closing immediately after the state check loses the third frame. Expect the same for .NET's
  continuations and Python's tasks, and document any pause as a timing argument rather than a signal.
- **Each conformance driver opens its connection with a 5000ms connection timeout.** This is not
  cosmetic and the fixture cannot enforce it: `capacity-deadline-survives-waiting` separates "the
  request carries its own 300ms armed at transmit" from "it silently fell back to the connection's
  timeout" by probing at 2500ms. A driver whose connection timeout is below ~2500ms makes those two
  cases indistinguishable, so the scenario would pass for an implementation that dropped the
  per-request timeout entirely. The number is recorded in the fixture's `capacityContract`.

## Review Focus

Seven failure modes the spec implies that no task's happy path exercises. Each has a test assigned to
the task that owns the code. Items 1, 4, 5 and 6 are driven by harness scenarios from Task 1;
items 2 and 3 are **per-SDK cases every one of Tasks 2-5 must add**, because each language's
cancellation, transmit point and close path differ. Items 6 and 7 were added by the review of
Task 1: each names a way a scenario can report success without the behaviour being present.

1. **A backing-off request starved by later arrivals.** It keeps its admission slot precisely so
   this cannot happen; an implementation that releases admission on refusal lets every new arrival
   overtake it, and a refused caller can then wait out its whole deadline behind work that arrived
   later. → Task 1 `capacity-slot-order`, asserted in Tasks 2-5.
2. **Cancellation racing the socket write.** Cancel firing as the frame goes out must not report
   `NOT_SUBMITTED` for something that was sent, double-release a slot, or leave accounting skewed.
   → Tasks 2-5.
3. **A resubmission re-arming the deadline.** The retry path re-enters transmit, which is exactly
   where the deadline is armed; "exactly once" has to survive that. → Tasks 2-5.
4. **A server that refuses forever.** The caller must get its original timeout, not a hang and not
   an extended deadline. → Task 1 `capacity-refuse-always`, asserted in Tasks 2-5. The scenario also
   bounds the observed attempt count, because a client that resubmits instantly and forever
   satisfies "ends in a timeout" too; the bound separates pacing from a hot loop without asserting
   any duration.
5. **Connection close while callers are parked waiting for capacity.** Every parked caller must be
   released as `NOT_SUBMITTED` rather than hanging until its deadline, which never started. →
   Tasks 2-5.
6. **A fail-fast client passing a parking scenario by coincidence.** `capacity-close-while-parked`'s
   three facts — one frame, `UNKNOWN` held, `NOT_SUBMITTED` parked — are all produced by a client
   that refuses locally and immediately, which is how the Go baseline passed it while never parking.
   The scenario therefore also asserts the parked request is *still pending* before the close. →
   Task 1, asserted in Tasks 2-5.
7. **A deadline charged for the wait for capacity.** The originally reported bug, and the one no SDK
   has a test for. → Task 1 `capacity-deadline-survives-waiting`, asserted in Tasks 2-5.

---

### Task 1: Contract scenarios in the shared conformance harness

Defines the contract once. Tasks 2-4 and 6 consume these scenario names; Task 5 mirrors the same
behaviours in JUnit.

**Files:**
- Modify: `test-support/contracts/sdk-conformance.json`
- Modify: `scripts/sdk-conformance.mjs`
- Modify: `scripts/sdk-node-conformance.mjs` (the Node driver proves the scenarios are drivable)

**Interfaces:**
- Consumes: the existing fixture shape `{name, response:{code, payload}}`, selected by the driver
  sending `payload.scenario`, and the existing handshake reply that advertises
  `mode:'concurrent-v1', maxPending:64` (`sdk-conformance.mjs:58`).
- Produces: seven scenario names, and a handshake override so a case can advertise a small window.
  Tasks 2-5 depend on these exact strings:
  - `capacity-refuse-once` — refuses the first attempt with `CAPACITY_EXHAUSTED`, then answers `OK`.
    Proves a refusal is absorbed and the caller sees a reply.
  - `capacity-refuse-always` — refuses every attempt. Proves the loop ends in the caller's own
    timeout rather than hanging.
  - `capacity-window-one` — advertises `maxPending:1`, then holds the first request open until a
    second has been submitted. Proves the window is honoured and the second call waits.
  - `capacity-slot-order` — with `maxPending:1`, request B parks for admission, then request A is
    refused once. The order is `A, A, B`: a refusal releases the dispatch count but keeps the
    admission slot, so A is not overtaken by B even though B parked first (Review Focus 1). See the
    spec's corrected slot-release paragraph; an implementation that releases admission on refusal
    produces `A, B, A` and fails this.
  - `capacity-handshake-refuse` — refuses the `control` negotiation with `CAPACITY_EXHAUSTED`.
    Proves no SDK resubmits a handshake; the harness fails the case if it sees a second selection.
  - `capacity-deadline-survives-waiting` — with `maxPending:1`, holds the first request for longer
    than the second request's own timeout, then frees the window. The second must still complete:
    the deadline arms at transmit, so the wait is not charged against it. This is the regression test
    for the originally reported bug (Review Focus 7).
  - `capacity-close-while-parked` — with `maxPending:1`, holds the first request and closes the
    socket while a second is parked. Proves parked callers are released (Review Focus 5), and
    asserts the parked request is still pending before the close so a fail-fast client cannot pass
    it by coincidence (Review Focus 6).

- [ ] **Step 1: Add the seven cases to the fixture**

Each case carries what the harness needs to drive it: a `response` (every case, including the
refusing ones, so an unexpected request id never produces a frame with no payload), and where
relevant `refusals` (integer), `refuseAlways` (boolean), `maxPending` (integer), `holdMs` (integer —
presence makes it a holding case), `waitingTimeoutMs` (integer — the per-request timeout of the
request that parks), `expectedSubmissions` (integer — upper bound on total submissions) and
`closeWhileParked` (boolean). A `capacityContract` block in the fixture documents the fields and the
refusal narrowing, because `"refusals": 1` applies only to the connection's first request id and a
driver author would otherwise model it per-request. Keep the existing 11 cases untouched.

- [ ] **Step 2: Implement the scenarios in `scripts/sdk-conformance.mjs`**

The harness already dispatches on `sent.payload.scenario` (`:142`) and counts per session
(`counts`, `packetCounts`). Extend it to honour the new fixture fields, to reply
`{code:'CAPACITY_EXHAUSTED'}` for the first `refusals` attempts of a given request id, and to fail
the case if a `control` selection arrives twice on one connection.

Count attempts by request id, not by session: a resubmission reuses the id, and that is the property
being tested.

- [ ] **Step 3: Drive all seven from the Node driver**

Add the cases to `scripts/sdk-node-conformance.mjs`, following the existing driver style — plain
assertions, and `assert(!first.done())`-style checks for "B completes while A is held" as
`sdk/python/tests/conformance.py:77` already does.

- [ ] **Step 4: Run the harness against Node only**

Run: `./gradlew sdkBuild && node scripts/sdk-conformance.mjs --languages=node`
Expected: PASS. Node inherits the TypeScript behaviour, so it should satisfy every scenario without
any SDK change. If it does not, that is a genuine finding about the reference and belongs in the
task report before anything else is built on it.

- [ ] **Step 5: Confirm the scenarios fail for the right reason elsewhere**

Run: `node scripts/sdk-conformance.mjs --languages=go`
Expected: FAIL on the new scenarios, since Go has none of the behaviour yet. This is the red step for
Tasks 2-5 collectively: record which scenarios fail and how, because that is the baseline each later
task is measured against.

- [ ] **Step 6: Commit**

```bash
git add test-support/contracts/sdk-conformance.json scripts/sdk-conformance.mjs scripts/sdk-node-conformance.mjs
git commit -m "test: define the dispatch backpressure contract in the SDK conformance harness"
```

---

### Task 2: Go SDK

**Files:**
- Modify: `sdk/go/client.go` (`admit` at `:109-120`, `convert` at `:123-145`, `request` at `:278`)
- Modify: `sdk/go/cmd/conformance` (the driver)

**Interfaces:**
- Consumes: Task 1's scenario names.
- Produces: nothing other SDKs depend on. Go's two forms are its own call.

- [ ] **Step 0: Skip the capacity cases in the generic loop first**

The fixture marks the backpressure cases `capacity: true` so a driver's generic case loop can skip
them and drive them deliberately instead. Until that skip is added this driver aborts on the first
one and every unrelated assertion after it stops running. Add the skip before anything else, exactly
as the node driver does, and confirm the rest of the existing suite still passes.

- [ ] **Step 1: Move the deadline to transmit**

`admit()` currently returns `&admission{owner: c, deadline: time.Now().Add(c.timeout)}` (`:120`), so
the deadline covers the conversion-gate wait. Arm it where the frame is written instead, once, and
leave the pre-transmit wait bounded only by the caller's `context.Context`.

- [ ] **Step 2: Add the waiting form and keep the non-blocking one**

`admit()` returns `NotSubmitted`/`packetCapacity` when `c.admitted >= c.maximum` (`:113-115`). The
waiting form waits for a slot instead, selecting on the caller's `ctx.Done()`; the existing
fail-fast behaviour stays reachable explicitly. Cancellation while waiting returns `NotSubmitted`
with `ctx.Err()` as the cause, consistent with the existing `TransportError` shape.

- [ ] **Step 3: Absorb `CAPACITY_EXHAUSTED`**

On that outcome, release the **dispatch** count so other already-admitted work can transmit, but
**keep the admission slot** — see the spec's slot-release paragraph, and note that releasing
admission here produces `A, B, A` and fails `capacity-slot-order`. Then back off (base 100ms, cap
5000ms, doubling, half fixed half random) and resubmit under the same id against the already-armed
deadline. The harness's `expectedSubmissions` is an observation ceiling on what a conforming client
does, not licence to add a retry ceiling of your own; the Global Constraints forbid one. Take the randomness
from an injectable source so the driver is deterministic. Never for the `control` negotiation.

- [ ] **Step 4: Drive the seven scenarios**

Add them to `sdk/go/cmd/conformance`, plus the per-SDK Review Focus cases: cancellation during the
wait (2), a resubmission not re-arming the deadline (3), parked callers released on close (5), the
parked request observably still pending before close rather than refused locally (6), and a request
that waited longer than its own timeout still carrying that timeout from transmit — not no deadline
at all (7).

- [ ] **Step 5: Run**

Run: `cd sdk/go && go test -race ./... && cd - && node scripts/sdk-conformance.mjs --languages=go`
Expected: PASS, including the scenarios recorded as failing in Task 1 Step 5.

- [ ] **Step 6: Commit**

```bash
git add sdk/go
git commit -m "fix: Go SDK waits for dispatch capacity and arms the deadline at transmit"
```

---

### Task 3: .NET SDK

**Files:**
- Modify: `sdk/dotnet/Plowshare.Sdk/Client.cs` (admission at `:55-75`, `RequestTypedAsync` at `:175`)
- Modify: `sdk/dotnet/Conformance` (the driver)

**Interfaces:**
- Consumes: Task 1's scenario names.

- [ ] **Step 0: Skip the capacity cases in the generic loop first**

The fixture marks the backpressure cases `capacity: true`. Add the skip to this driver's generic case
loop before anything else, as the node driver does, or the driver aborts on the first one and every
unrelated assertion after it stops running.

- [ ] **Step 1: Move the deadline to transmit**

`:55` starts a `Stopwatch` when the admission is constructed and `:61` throws "Request deadline
expired before conversion", so the conversion wait consumes the deadline. Start it at the socket
write instead, once.

- [ ] **Step 2: Add the waiting form and keep the non-blocking one**

`RequestTypedAsync` already takes a `CancellationToken` and an optional `RequestOptions`, which is
where the mode belongs. Waiting is a continuation here, not a held thread. A cancelled wait throws
`OperationCanceledException` and reports `Delivery.NotSubmitted`.

- [ ] **Step 3: Absorb `CAPACITY_EXHAUSTED`**

As Task 2 Step 3, with injectable randomness.

- [ ] **Step 4: Drive the seven scenarios; cover Review Focus 2, 3, 5, 6 and 7**

The seven scenarios belong in the conformance driver. The Review Focus cases may live in the SDK's
own unit tests instead where that makes them deterministic — a cancellation race run a few hundred
times under a race detector is something a driver cannot do — provided each is actually covered
somewhere and the report says where.

Cancellation during the wait, a resubmission not re-arming the deadline, and parked callers released
on close — expressed in .NET terms: `OperationCanceledException`, the `Stopwatch` started once, and
every pending `Task` completing on socket close.

- [ ] **Step 5: Run**

Run: `node scripts/sdk-conformance.mjs --languages=dotnet`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add sdk/dotnet
git commit -m "fix: .NET SDK waits for dispatch capacity and arms the deadline at transmit"
```

---

### Task 4: Python SDK

**Files:**
- Modify: `sdk/python/src/plowshare/client.py` (`request` at `:243`, `_exchange` deadline at `:279-282`,
  `asyncio.timeout_at` at `:330`)
- Modify: `sdk/python/tests/conformance.py`

**Interfaces:**
- Consumes: Task 1's scenario names.

- [ ] **Step 0: Skip the capacity cases in the generic loop first**

The fixture marks the backpressure cases `capacity: true`. Add the skip to this driver's generic case
loop before anything else, as the node driver does, or the driver aborts on the first one and every
unrelated assertion after it stops running.

- [ ] **Step 1: Move the deadline to transmit**

`end` is computed at `:282`, before the capacity check, and `asyncio.timeout_at(end)` (`:330`) wraps
acquisition of `_send_lock`, so waiting for the writer consumes the deadline. Compute it after the
send lock is held instead, once.

- [ ] **Step 2: Add the waiting form and keep the non-blocking one**

`request` takes keyword-only arguments (`:243-249`), which is where the mode belongs. Do **not** add
a synchronous entry point: the SDK is `asyncio`-only and the spec leaves that question out of scope.
Task cancellation is already the native signal; a cancelled wait raises `CancelledError` and the
`TransportError` path reports `Delivery.NOT_SUBMITTED`.

- [ ] **Step 3: Absorb `CAPACITY_EXHAUSTED`**

As Task 2 Step 3, with injectable randomness.

- [ ] **Step 4: Drive the seven scenarios; cover Review Focus 2, 3, 5, 6 and 7**

The seven scenarios belong in the conformance driver. The Review Focus cases may live in the SDK's
own unit tests instead where that makes them deterministic — a cancellation race run a few hundred
times under a race detector is something a driver cannot do — provided each is actually covered
somewhere and the report says where.

Cancellation during the wait, a resubmission not re-arming the deadline, and parked callers released
on close — expressed in Python terms: `CancelledError` while awaiting capacity, `end` computed once,
and every pending future resolving on socket close.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add sdk/python
git commit -m "fix: Python SDK waits for dispatch capacity and arms the deadline at transmit"
```

---

### Task 5: Java SDK

Java is not in the conformance harness, which runs `node,python,dotnet,go`. It gets the same
behaviours proved by JUnit instead. Do not add Java to the harness as part of this task: that is a
larger change to the build and is not what this plan is for.

**Files:**
- Modify: `sdk/java/src/main/java/io/aeyer/plowshare/sdk/Plowshare.java` (`requestAsync` at `:529`,
  the clock at `:534`, the capacity check at `:540`, the writer loop at `:632-645`)
- Modify: `sdk/java/src/main/java/io/aeyer/plowshare/sdk/RequestOptions.java`
- Test: `sdk/java/src/test/java/io/aeyer/plowshare/sdk/AsyncExchangeTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks. The contract is the Global Constraints above and the seven
  behaviours Task 1's scenario names describe.
- Produces: a third component on `RequestOptions`, whose existing shape is
  `RequestOptions(Duration timeout, RequestCancellation cancellation)` with a
  `defaults(Duration)` factory. Keep `defaults` meaning the waiting form.

- [ ] **Step 1: Write the failing tests**

In `AsyncExchangeTest`, following its existing style:

```java
@Test void a_request_at_the_window_bound_waits_instead_of_failing()
@Test void a_refused_request_is_resubmitted_under_the_same_id_and_the_caller_sees_a_reply()
@Test void repeated_refusals_end_in_the_callers_own_timeout_and_never_re_arm_the_deadline()
@Test void the_deadline_is_not_consumed_by_waiting_for_capacity()
@Test void the_non_blocking_form_still_fails_immediately_with_NOT_SUBMITTED()
@Test void a_cancelled_wait_for_capacity_reports_NOT_SUBMITTED_and_frees_its_slot()
@Test void the_dispatch_negotiation_is_not_resubmitted_when_refused()
@Test void callers_parked_for_capacity_are_released_when_the_connection_closes()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plowshare-sdk:test --tests '*AsyncExchangeTest'`
Expected: FAIL. State in the report which failed for which reason; a test that passes before the
change is a characterisation test and must be called one.

- [ ] **Step 3: Move the deadline to transmit**

`:534` starts the clock at the top of `requestAsync`, before the capacity check and before
serialization, and the writer loop fails a still-queued request with "Queued request deadline
expired" (`:641`). Arm it in the writer loop at the write instead, once, and delete that queued
expiry path — a request that has not been transmitted has no deadline to expire.

- [ ] **Step 4: Add the mode to `RequestOptions` and make the window bound wait**

`:540` is `if (!connected() || reserved >= window || !completions.tryAcquire())`, and the comment at
`:120` says callers "never await their own permit" — that comment becomes wrong and must change with
the code. The waiting form awaits a permit, observing the existing `RequestCancellation`; the
non-blocking form keeps today's behaviour.

- [ ] **Step 5: Absorb `CAPACITY_EXHAUSTED`**

As Task 2 Step 3, with injectable randomness, never for the `control` negotiation.

- [ ] **Step 6: Run the tests and the SDK check**

Run: `./gradlew :plowshare-sdk:check`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
./gradlew format formatCheck
git add sdk/java
git commit -m "fix: Java SDK waits for dispatch capacity and arms the deadline at transmit"
```

---

### Task 6: Confirm `node` inherits the contract

**Files:**
- Modify: `scripts/sdk-node-conformance.mjs` only if a gap is found
- Modify: `sdk/typescript/src/binding/connection.ts` only if a gap is found

**Interfaces:**
- Consumes: Task 1's scenario names, already driven from the Node driver in Task 1 Step 3.

- [ ] **Step 1: Run the full harness**

Run: `./gradlew sdkCheck`
Expected: PASS for all four languages.

- [ ] **Step 2: Establish that `node` exercises the inherited path**

`sdk/node` imports `plowshare-client-ts/binding/connection`, so the scenarios should pass with no
`sdk/node` change. Confirm the Node driver reaches that code rather than a local copy, and say so in
the report with the import that proves it.

- [ ] **Step 3: If a gap is found, fix it in `typescript`**

`sdk/typescript` owns the shared WebSocket operations and contracts. A gap is corrected there so
`node` inherits the fix; `sdk/node` does not grow its own copy. If no gap is found, say that plainly
and change nothing.

- [ ] **Step 4: Commit, if anything changed**

```bash
git add sdk/typescript scripts/sdk-node-conformance.mjs
git commit -m "fix: close a dispatch backpressure gap in the shared TypeScript binding"
```

# CORE-03A independent review

Reviewer: `/root/ha_sql_budget_reviewer`.
Implementer: `/root/ha_sql_budget_implementer`.
Candidate: `ha-core-03a-v1`.
Manifest: `docs/reviews/home-assistant-core-03a-v1-candidate.json`.
Manifest SHA-256: `4b9b318c95657e9b2eb604ecb995421563a6b3385264b1d29ca0852c52330143`.
Verdict: **CHANGES_REQUIRED**.

## Scope and evidence

Verified the exact manifest, every bound source/document/snapshot hash and the
compressed working-tree diff hash. Rechecked bound source hashes after the
standalone probe; the reviewed candidate remained unchanged. Read the scoped
budget, JDBC wrappers, PostgreSQL session controls, throttle/trace leases,
configuration, cache changes, implementation/design documents and relevant tests.

Inspected the current coordinator evidence: 152 passing non-database invocations
across eleven classes (130 owning cases plus 22 Faults cases), six actual PostgreSQL cases with zero failures/errors/skips, and the separate
successful repository formatCheck. The PostgreSQL cases include pre-alias
identity SQL, independent version leases, transaction/backend cancellation and
row-lock release, materialization, acquisition and clean settings reuse.
No Gradle, PostgreSQL, live/model request or implementation change was performed
by this reviewer. With coordinator authorization, compiled and ran one bounded
standalone fake-JDBC barrier probe against the already compiled candidate.
Existing passing checks do not waive the additional failure reproduced below.

Repositories retain SQL ownership; the internal scope exposes no SQL/JDBC API.
The raw primary pool and shared template/transaction source identity are
preserved, including admission-disabled composition. Scoped timeout failures
retain uncertain-effect guidance rather than claiming no write occurred.
The SQL-only scope does not establish whole-call hook/file completion; CORE-03
still owns that integration and its separate acceptance.

## Earlier pre-flight dispositions

- **PRE1 — resolved in this candidate:** live JDBC calls and fetches now run
  Query.check/networkDeadline against the current query and operation remainder.
  The occupied-cleanup-worker/late-query and late-fetch fixtures exercise the
  original stale-checkout-timeout concern. This correction introduces the
  distinct alarm-monitor coupling recorded as CORE03A-R1 below.
- **PRE2 — resolved:** stored-token confirmation uses readExisting and the bounded
  await/local-cancellation path in scoped mode. The known-token/evicted-resident/
  ordinary-refill fixture covers the original interrupt-insensitive join gap.
  Warm hits and existing shared-fill ownership remain intact; private misses do
  not elect or cancel an ordinary shared loader.
- **COORD1 — resolved within its documented physical-work limits:** expiration
  dispatches Thread.interrupt on separately reserved workers, not on alarm
  threads or under the scope monitor. Each active scope reserves capacity;
  blocked interruptors retain their slot and completion fence, preventing late
  interruption of a reused caller. The candidate does not claim a hard physical
  stop for arbitrary blocking interruptors/driver code.

## Required correction

**CORE03A-R1 — P2: A blocked deadline setter can stall both shared alarm threads.**

Locations: `ScopedSqlWorkBudget.java:322–336`, `:459–466` and `:518–526`.

Lease.networkDeadline holds the lease's `state` monitor while invoking the JDBC
connection's setNetworkTimeout. Expiration runs owned.forEach(Lease::abort)
synchronously on an alarm thread. Lease.abort/release then waits for that same
monitor before cancellation can be queued or caller interruption dispatched.
Consequently, driver deadline-setting callbacks can hold the shared alarm pool,
even though physical driver work is supposed to remain isolated from alarms.
Two blocked setters exhaust the two alarm threads and delay unrelated scopes.
The same call path can also delay budget.close before its bounded executor wait.

The authorized standalone probe creates two scoped connections whose timeout
setters block behind a barrier, then admits a third cooperative scope with an
80 ms budget. After both first deadlines are due, the third scope remains
unfinished after 350 ms. Releasing the setters lets all three calls settle as
SqlWorkUnavailableException:

```text
third_unfinished_after_350ms=true
settled=SqlWorkUnavailableException
settled=SqlWorkUnavailableException
settled=SqlWorkUnavailableException
```

Probe: `/private/tmp/core03a-review-probe/ClampAlarmProbe.java`.
Probe source SHA-256:
`8978ed9ada19ad876ecf12c1c4f30a2e68e0f30e05b35c1908bc1e852f26a079`.
This is a deterministic fake-driver structural reproduction, not a claim that
PostgreSQL 42.7.4 commonly blocks its timeout setter. The implementation must
nevertheless preserve unrelated alarm progress when one owned driver callback
has not physically settled; its documented no-hard-stop assumption does not
justify coupling that callback to every other scope's deadline.

Make logical expiration and cancellation/wakeup dispatch independent of any
monitor held across a JDBC call. Preserve the existing no-late-clamp/no-late-abort
pool-reuse fences and retained physical ownership; removing the monitor without
an equivalent ownership protocol would be unsafe. Add a bounded barrier
regression with two blocked setters and a third cooperative scope, asserting
that the third deadline is enforced before the setters are released. Also cover
bounded shutdown progress and safe settlement/reuse after release.

Disposition: **open**. The implementer must author the correction and supply a
new exact candidate with current relevant verification. No other blocking
finding was established in this review; final approval remains pending R1.

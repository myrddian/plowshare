# CORE-03A independent re-review

Reviewer: `/root/ha_sql_budget_reviewer`.
Implementer: `/root/ha_sql_budget_implementer`.
Candidate: `ha-core-03a-v2`.
Manifest: `docs/reviews/home-assistant-core-03a-v2-candidate.json`.
Manifest SHA-256: `ebf322a572498875df1c9866a34f4c1556d972fbe5fe344851b4b83145dc2914`.
Verdict: **APPROVE**.

## Exact scope

Verified the manifest, every bound file/snapshot hash and compressed diff hash.
Only three authored files differ from v1: ScopedSqlWorkBudget, its focused test,
and the implementation report. The new frozen ledger snapshot is bookkeeping.
Rechecked all bound hashes after the independent probe; they remained unchanged.
Disjoint CORE-03 facade work is outside this candidate and this approval.

The unchanged JDBC/session, cache, pool/throttle, composition, error mapping,
guide and design material retains the owning-source review recorded in the v1
report. No implementation or ledger changes were made by this reviewer.

## CORE03A-R1 resolution

**Resolved.** Logical retirement now uses one AtomicBoolean claim before queuing
its single bounded cleanup task. Alarm expiration no longer acquires the lease
monitor held by a driver timeout setter, and no caller-depth callback runs on
that alarm retirement path. Physical cleanup acquires the original lease fence
on its worker, so a blocked in-flight setter still prevents abort, close or pool
return until it settles. Caller close and scope completion detach logical depth;
physical permit/lease occupancy is retained until successful cleanup. Duplicate
retirement cannot enqueue another task or act on a reused handle.

The new two-branch barrier regression checks an unrelated deadline while both
setters remain blocked, bounded shutdown, absence of premature abort/close,
retained occupancy, settlement after release and reuse of the same fake pool
handle. Inspected the actual implementation and both test branches rather than
relying only on their result.

Independently reran the original standalone compiled-candidate probe, unchanged
from the v1 reproduction. It now reports:

```text
third_unfinished_after_350ms=false
settled=SqlWorkUnavailableException
settled=SqlWorkUnavailableException
settled=SqlWorkUnavailableException
```

Thus the third 80 ms scope settles before the blocked setters are released.
Probe source: `/private/tmp/core03a-review-probe/ClampAlarmProbe.java`;
SHA-256 `8978ed9ada19ad876ecf12c1c4f30a2e68e0f30e05b35c1908bc1e852f26a079`.
This is a bounded fake-driver alarm-isolation check, not a claim that PostgreSQL
normally blocks its setter or that arbitrary driver code can be forcibly stopped.

## Other finding dispositions and evidence

- PRE1 remains resolved: current query/fetch remainder clamps and the occupied-
  cleanup-worker tests are unchanged; R1 now preserves alarm isolation too.
- PRE2 remains resolved: stored-token confirmation uses bounded local joins;
  warm cache reuse, private misses and another caller's shared fill retain their
  original authority and ownership.
- COORD1 remains resolved within its documented physical-work limits: reserved
  interruption execution and completion fences isolate alarm/bookkeeping work,
  retain blocked physical slots and prevent interruption of a reused caller.

Inspected current coordinator logs: **154 passing non-database invocations in
11 classes**, comprising 132 owning cases and 22 Faults cases, plus **six passing
PostgreSQL scenarios**. These cover backend/row-lock release, rollback, earlier
server settings and clean unscoped reuse, materialization, identity admission and
independent projection acquisition. The separate final formatCheck passed in
four seconds. No Gradle or PostgreSQL rerun was performed by this reviewer; the
only independent execution was the bounded standalone probe above.

Logs: `/private/tmp/core03a-v2-focused-check.log`,
`/private/tmp/core03a-v2-postgres-check.log`, and
`/private/tmp/core03a-v2-format-check.log`.

No blocking findings remain for this exact CORE-03A candidate. This approval
covers the explicit SQL completion capability and its scoped cache/pool
composition. It does not establish universal enforcement for unscoped work,
whole-call hook/file/CPU completion, Ex facade integration, deployment or live
validation. Those retain their owning deliverables and checks.

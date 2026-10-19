# JdbcSuspendedRunCalls

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/JdbcSuspendedRunCalls.java#L43) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunCallResumption.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V154__suspended_run_calls.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`RunCallResumption` and continuations load suspended calls joined to approval decision fields. `pending` selects unacknowledged calls ordered by sequence; `recoverable` selects globally unacknowledged, answered calls. `claim` atomically changes waiting to started only when released and currently allowed. Completion and acknowledgement preserve uncertain-effect recovery. The retained argument/result columns can be large; no latency measurement isolates this owner.

Preserve the typed `Call` across resumption helpers instead of finding the approval again merely to recover the same arguments. Separate lightweight discovery identities from payload hydration if recovery scans show excessive bytes, then batch hydration; avoid converting that split into per-row N+1. V154's partial `(conversation,sequence)` index fits pending reads, but global answered recovery needs its own measured join/ordering plan. A partial global unacknowledged index is only a candidate after cardinality analysis. `UPDATE ... RETURNING` may supply a claimed DTO when it removes a proven follow-up read.

Never cache claim/release eligibility, pending recovery state or an allowed decision as authority. Immutable original call payloads may use database/approval/conversation/retained-payload version keys under strict byte-bounded LRU, with current ownership and decision checks. Retain/release/complete/acknowledged, approval answer/revoke and conversation deletion invalidate call and pending/recovery collections after commit. Durable generations and reconnect reconciliation are prerequisites. Notifications may wake recovery, retaining the ledger and no-replay rule.

Mock one payload load, late-answer handling and uncertain started calls. Focused PostgreSQL tests cover competing claims, joins and commit/rollback invalidation when implemented.

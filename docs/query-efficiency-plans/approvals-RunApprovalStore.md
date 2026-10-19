# RunApprovalStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunApprovalStore.java#L232) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ApprovalFrames.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V50__run_approvals.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

This store owns approval creation, exact lookup, open-by-conversation/account lists, undelivered lists, standing project prefixes and conditional answer/consume transitions. `openFor` includes legacy conversation ownership; `consume` loads exact allowed candidates, CAS-consumes one-shot approvals, then scans standing project prefixes. Client discovery may request overlapping scopes, so query count and duplicate rows should be traced through `ApprovalFrames`.

Reuse the `RunApproval` already resolved during answering/delivery rather than refetching fields inside one valid operation. Batch or unify authorized list scopes while retaining legacy owner fallback and deduplication. Bound discovery and payload bytes; keep full command sets out of identity-only notifications. V50 has conversation/state and project/side/state indexes; V152 adds asked project/ID. Compare actual `openFor` ownership-OR and answered-at ordering plans before proposing partial/composite indexes. A typed consume routine could reduce exact-match/CAS round trips, but must preserve stdin equivalence, prefix matching, preference order and one-shot single-winner semantics.

Do not share-cache consumable consent or pending/allowed decisions for execution. Historical or display DTOs may use database/account/project/approval/version keys only after adding durable versions and current disclosure fences. Ask/allow/deny/revoke/consume/delivered and ownership changes invalidate approval plus account/conversation/project list membership after commit. Existing event callbacks do not yet prove cross-process cache invalidation; bridge all writers to durable change evidence and scoped wake hints before bounded LRU admission.

Mock overlapping-scope query budgets, stale UI responses and no replay after uncertainty. Focused PostgreSQL tests cover any revised consume routine, indexes and concurrent one-shot consumption.

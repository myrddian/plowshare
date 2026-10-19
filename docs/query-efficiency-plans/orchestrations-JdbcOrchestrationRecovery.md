# JdbcOrchestrationRecovery

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationRecovery.java#L24) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V113__orchestration_resume.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`Orchestrations` first reads an account/request resume receipt and claims only an exact failed run. Claim holds an account/request advisory transaction lock, verifies a prior receipt, conditionally changes the run from its observed failure and writes the receipt atomically. This is a side-effecting resume protocol even when one branch only returns an existing receipt.

Retain the known request/run DTO across request decoding and recovery helpers so the same receipt is not independently fetched multiple times before the locking boundary. Keep the receipt check inside the lock: a pre-lock lookup is an optimization hint, not proof. If traces show material round-trip overhead, a typed stored routine can combine receipt reconciliation, expected-failure transition and receipt insertion while preserving current ownership and exact idempotency. V113's `(account,request_id)` receipt key and orchestration ID lookup already fit these queries; another index is not indicated.

Do not share-cache recovery eligibility, receipt absence, failed state or claim success. A completed immutable receipt can support display/reconciliation keyed by database/account/request UUID/run ID, with current run disclosure and durable identity fences, but never trigger another resume. Claim commits invalidate run and account/run receipt collections; other lifecycle writers participate in the run generation. Shared bounded LRU admission requires durable change recovery, and listener failure falls back to authoritative receipt reads.

Mock matching/mismatched receipt paths and one claim attempt per authorized request. Focused PostgreSQL checks cover concurrent identical resumes, expected failure timestamps and transaction rollback if the routine changes.

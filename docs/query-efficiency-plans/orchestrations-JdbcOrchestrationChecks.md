# JdbcOrchestrationChecks

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationChecks.java#L31) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/StageChecks.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V54__orchestration_checks.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`StageChecks`, `StageHooks` and `AcceptanceGate` retain a single orchestration check, clear it by exact approval and read a typed `Check`. SQL is a one-row insert with conflict protection, conditional delete and primary-key lookup. The DTO retains argv, side, cwd, consent/approval and set time. No polling loop or measured slowness is established in this owner.

Trace a stage transition and reuse the loaded `Check` between verification/rendering helpers where the same operation already holds it. Keep a fresh check when consent or stage state may have changed. A narrow `RETURNING` projection may remove a caller's post-set reload, but preserve false/conflict outcomes and exact approval-conditioned clear. V54's orchestration primary key serves current reads; no additional index or stored procedure is justified without a new query shape.

Prefer request-local reuse initially. Shared cache entries may represent check display snapshots keyed by database/orchestration/check generation and authenticated scope, never permission to run the command. The set timestamp alone is not a durable revision. Set/clear, acceptance replacement and orchestration deletion invalidate both entity and run-check collection dependencies after commit; approval answer/revocation also invalidates any consent-derived display. Introduce durable generation/change recovery before bounded expected O(1) LRU admission. During listener loss bypass current-check cache entries.

Mock stage-level duplicate read suppression and approval-isolated clear behavior. Focused PostgreSQL tests are appropriate for changed conflict/RETURNING semantics, not for this documentation-only plan.

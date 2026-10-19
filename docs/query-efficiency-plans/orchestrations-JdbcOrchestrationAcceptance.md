# JdbcOrchestrationAcceptance

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationAcceptance.java#L47) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/AcceptanceGate.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V60__verifier_caps_and_rules.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`AcceptanceGate` reads registered acceptance commands ordered by position, requirements text and an approval-bound registration. `replace` upserts requirements, deletes all commands and inserts each registration inside `UnitOfWork`. V60 supplies the orchestration/position key and partial approval index. There is no demonstrated slow query, but replacing many commands costs one insert round trip per registration.

Batch command insertion within the existing replacement transaction; retain exact position, argv, cwd, side, consent and approval mapping. Provide a typed acceptance-set snapshot combining requirements and commands when a caller currently invokes both reads, preserving empty sets and requirements-only state. Pass that snapshot through gate/check helpers instead of finding the same set repeatedly. Existing keys already serve orchestration ordering and approval lookup; measure before adding indexes. A table-returning function could expose the complete set in one trip if it beats a bounded join/two-query loader.

A complete immutable acceptance-set DTO is eligible for database/orchestration/set-generation/projection cache keys under bounded LRU, after adding a durable set version. Current approval and run lifecycle remain authoritative: a cached registered command cannot imply consent. Replace and orchestration deletion invalidate requirements, command, approval-locator and whole-set dependencies after commit; approval changes separately invalidate consent displays. Shared admission requires all writer coverage, durable change evidence and reconnect bypass. Publish the replacement only after the enclosing transaction commits.

Mock constant replacement/query budgets and preserve command ordering. Focused PostgreSQL tests cover batch replacement atomicity, approval indexing and rollback when implemented.

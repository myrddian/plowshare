# JdbcSystemOperationRecords

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcSystemOperationRecords.java#L18) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/BoundSystemOperations.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V161__bounded_system_operations.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`BoundSystemOperations` writes one immutable SYSTEM envelope and verifies it with `SELECT EXISTS` matching ID, purpose, scope, grant, agent, model and binding hash. V161 protects retained evidence from mutation. There is no enumeration, polling or demonstrated slow scan here; the ID primary key bounds verification.

Carry the typed `Envelope` already supplied by the caller through dispatch helpers. Count repeated `require` calls within one verified operation and remove only duplicates whose consistency boundary is unchanged. Keep verification on rehydration/restart and retain current SYSTEM authority checks at their separate owner. A stored procedure or another index offers no established benefit for this single-row write/read pair; preserve the existing boolean projection.

Initial cache integration should be request-local evidence reuse. A shared immutable-envelope cache could later key by database identity, operation UUID, projection and exact binding hash, admitting only committed retained envelopes. It must never turn retained purpose into permission to execute. Durable deletion/version evidence and listener recovery must be specified before global admission; administrative repair and owning scope/grant lifecycle invalidate dependencies after commit. The shared bounded expected O(1) LRU can evict evidence without changing the caller's immutable DTO. Missing evidence is not negatively cached initially.

Mock repeated verification budgets and mismatched envelope refusal. A focused PostgreSQL test is appropriate only if changing the envelope query or immutability trigger; no live database work is needed for this plan.

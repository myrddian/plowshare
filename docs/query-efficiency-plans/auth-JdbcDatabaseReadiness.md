# JdbcDatabaseReadiness

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcDatabaseReadiness.java#L17) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ReadinessController.java#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ReadinessController` calls `DatabaseReadiness.available`, whose entire query is `SELECT 1`; a database access exception becomes unavailable. This probe deliberately checks the current JDBC path. It returns no domain object, has no table scan and needs no index or stored procedure.

Keep the probe uncached. A remembered success would conceal a lost database connection, and a remembered failure would delay recovery reporting. Measure probe duration and connection-pool wait separately from SQL execution if readiness becomes slow. Inspect upstream probe frequency and pool timeout configuration before altering semantics; an overloaded pool is operational evidence rather than a reason to return a cached healthy result. Reuse within a single HTTP response only if the controller actually invokes the probe twice, which is not established here.

Integration with the cache layer is an explicit bypass: no LRU node, negative-result caching, listener-based availability or cached DTO is appropriate. Listener health and cache recovery are separate readiness dimensions; neither replaces this database probe. A future cache infrastructure component may expose its own typed health status alongside database readiness, but this repository remains the authoritative database availability owner. There are no mutation or collection invalidation hooks to add here.

Mock success, JDBC failure and one invocation per readiness response. No PostgreSQL test is justified for the unchanged constant query. If pool/readiness behavior changes, test timeouts with an appropriately bounded integration fixture and report connection availability separately from application query performance.

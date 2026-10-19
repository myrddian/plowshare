# RuntimeConfig

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/config/RuntimeConfig.java#L195) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/config/RuntimeConfigController.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V28__runtime_config.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

Despite its name, `RuntimeConfig` owns JDBC reads/writes of `runtime_config`. Every `get` calls `entry`, loading value/author/time; `authorOf` calls it again. Live numeric accessors use `currentOrLastKnownGood`, whose concurrent map is outage fallback memory, not a healthy-read cache. `all` already returns authors beside values and avoids per-key N+1. Frequent scheduler/search property access can therefore generate unchanged reads; call frequency needs instrumentation.

Extract a narrow typed configuration repository/capability while retaining typed accessor behavior. Reuse one `Entry` when a response needs both value and author. Coalesce identical in-flight reads and distinguish authoritative absence, invalid numeric content and unreachable store exactly as current comments require. The V28 key primary index serves lookups; another index or stored procedure is unnecessary. Upsert can `RETURNING` the committed row if callers need it.

Non-secret config entries are good bounded LRU candidates keyed by database/key/projection and durable monotonic generation. `updated_at` alone is not a sufficient universal change order; introduce generation/change evidence through a new migration. Put/seed/direct SQL writers invalidate individual keys and all-config collection after commit. Preserve declared last-known-good outage behavior separately from cache misses, listener recovery and ordinary absence; evicting an LRU entry must not silently redefine fallback policy. Sensitive settings require restricted admission/redaction. Require a consistency contract for settings controlling authority or paid work.

Mock repeated accessor query budgets, absent-versus-unreachable semantics and fallback after eviction. Focused PostgreSQL checks cover generation/upsert/notification rollback when implemented.

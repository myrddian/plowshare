# ProviderStore

Status: Proposed; no implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[Source](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/search/ProviderStore.java#L179) serves `SearchRegistrar` and `SearchLadder`. `find/all` read registrations and health; `upsert` replaces deployment facts while preserving real health history and resetting failure streak; `remove` deletes; `recordOutcome` changes health atomically in one update. `SearchRegistrar` explicitly reads back after upsert, an avoidable second round trip when the same committed projection can be returned.

Expose a narrow repository interface as part of changing this legacy concrete-store consumer boundary. Return the registration via upsert `RETURNING`, retaining health columns exactly. Reuse each registration during one ladder attempt instead of fetching facts again. [V33](../../plowshare-server/src/main/resources/db/migration/V33__search_providers.sql#L32) already keys registrations by provider key; ordered full-list reads need no speculative index. No stored procedure is needed for these atomic statements.

Cache immutable registration DTOs by database, provider key and durable configuration version. Separate configuration from frequently changing health, or include both source versions explicitly; never serve stale failure counters where they control provider selection. Upsert/remove/outcome plus external administrative writers invalidate old/new key and all-list dependencies after commit. Return versions with DTOs; notification hints require durable catch-up and generation-fenced admission. This uses the proposed bounded tracked DTO/LRU foundation, not a new provider registry. Retain the current policy/configuration admission before contacting provider origins.

Mock budgets should assert one registration write/result and one lookup per provider attempt, preserving terminal outcome accounting. Later focused PostgreSQL tests cover racing registration/outcome, atomic counter behavior, arrays and notification rollback. Client health displays may tolerate documented staleness; routing decisions need their explicit freshness contract.

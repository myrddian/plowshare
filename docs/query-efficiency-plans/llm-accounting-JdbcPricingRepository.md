# JdbcPricingRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcPricingRepository.java#L21) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/ManagedPricing.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V112__admin_pricing.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ManagedPricing` loads a `(billing_route,model)` override as typed `RateCard` plus update time. Save upserts the current override and appends pricing history/admin audit under the shared administration lock. The exact composite-key read is small, but repeated model calls can repeatedly decode the same JSON; frequency and payload size should be recorded.

Pass the resolved immutable rate card through call setup, accounting and retry handling instead of reloading/redecoding the override. Preserve the pinned rate-card version for a call; a new override must not retroactively reprice retained usage. Measure the existing composite key path before adding indexes. `save ... RETURNING` can supply the updated typed entry if an administrative response otherwise reloads it. No new routine is needed unless it combines proven redundant read/write trips while retaining audit atomicity.

This is a strong cache candidate for immutable card versions, keyed by database/route/model/card version/projection. The mutable route/model head requires durable generation and a documented freshness boundary for new paid calls. Save, default pricing source changes and administrative SQL writers invalidate head and route/model list dependencies after commit; retain immutable historical card entries. `updated_at` should not be assumed a universal commit-order cursor. Listener recovery bypasses unresolved heads; coalesce misses under bounded expected O(1) LRU, with byte limits and no cached decode failures.

Mock one lookup/decode per selected version, fresh override selection and historical pinning. Focused PostgreSQL tests cover version/head generation and audit/notification rollback when implemented.

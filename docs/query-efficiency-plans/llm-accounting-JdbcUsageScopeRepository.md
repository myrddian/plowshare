# JdbcUsageScopeRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcUsageScopeRepository.java#L18) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingConfig.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V48__orchestrations.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`AccountingConfig` adapts scope resolution to this two-method owner. `project(Home)` ensures a project with `INSERT ... ON CONFLICT DO UPDATE SET name=EXCLUDED.name RETURNING id::text`; `conductorConversation` performs an exact orchestration lookup. The former is a mutation even on the existing-project path, potentially taking a row lock and firing update triggers for a value already present.

Trace repeated attribution setup with the same Home and pass the already known typed project identity from the operation context. Consider a lookup-first fast path for existing projects with an insert/reconcile fallback on absence, comparing added miss trips against avoided no-op writes and preserving concurrent creation behavior. Alternatively use a carefully designed existing-row/insert SQL routine whose concurrency semantics are tested; do not assume a single CTE sees a concurrent conflict row. Project name uniqueness already supplies lookup access; no extra index is indicated. Retain the conductor conversation DTO when orchestration metadata is already loaded.

Cache stable project/conductor mappings only after defining durable identity/generation. Key by database/Home or orchestration ID/projection, never treating names as proof after deletion/recreation. Project rename/delete, orchestration creation/conductor changes and all direct writers invalidate mappings and corresponding collection dependencies after commit. Current membership/lifecycle fences stay authoritative. Bounded LRU admission and reconnect bypass depend on durable change recovery; absent mappings should initially bypass negative caching.

Mock repeated-scope lookup/write budgets and known-identity reuse. Focused PostgreSQL tests are necessary for any replacement upsert/routine, especially concurrent creation and trigger behavior.

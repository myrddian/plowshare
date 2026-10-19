# JdbcResourceScopeRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcResourceScopeRepository.java#L20) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V88__orchestration_start_receipts.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ProjectAuthorization` resolves a resource kind to projects through an allowlisted query: conversation/job/memory IDs, proposal-to-memory joins, message routes, schedule source, trigger owner, approval and outgoing work. Receipt lookup binds both authenticated account and request UUID. `triggerOwnedBy` separately fetches an owner list. These are authorization inputs rather than user-facing cached content.

Trace resource resolution across one authorized operation and retain its typed resolved-scope snapshot instead of resolving identifiers again downstream. Consolidate trigger scope plus owner into a narrow typed projection when the caller needs both. For messages, examine the `i.id IN (r.sender,r.recipient)` join and message-route index with representative fan-out; preserve both scopes and global null semantics. Receipt uniqueness in the orchestration receipt schema should serve the account/request predicate; do not add an index merely because many resource kinds exist. A batch resolver must retain allowlisted branches and exact ownership binding.

Default to request-local reuse. Shared scope caching needs a durable resource generation plus current ownership/authority fence: keys include database, resource kind/identity and account for receipts. Resource moves, conversation/project deletion, route changes, trigger edits and schedule-source changes invalidate both resource and affected collection dependencies after commit across every writer. Until that cross-owner coverage exists, leave shared admission disabled. Use the shared bounded LRU only for proven versioned mappings; notifications alone cannot authorize access.

Mock repeated resolution budgets, receipt account isolation and global scope handling. PostgreSQL checks are warranted only for changed join/index semantics.

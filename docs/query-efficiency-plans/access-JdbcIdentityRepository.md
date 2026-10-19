# JdbcIdentityRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcIdentityRepository.java#L17) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/LiveScopedAuthority.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V155__explicit_authority_identities.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`IdentityRepository` resolves accounts by legacy handle, roles/scopes by UUID, projects by name and Personal/knowledge scopes by account. Scope mapping joins accounts/admins and uses `s.*`; `verifyBuiltIns` calls the registry-validation function. `LiveScopedAuthority` and operation admission can reuse the same mapping repeatedly. No latency measurement establishes this owner as slow.

First instrument one admission's mapping calls, pass its typed `Account`/`AuthorityScope` snapshots between helpers, and replace wildcard scope projection with mapper-required fields. V155 already supplies unique handle, project and partial account/kind paths; validate those plans before considering another index. Keep the startup registry probe authoritative. A multi-mapping SQL function is justified only if traced admissions repeatedly require the same set.

Candidate cache values are immutable mapping DTOs, keyed by database identity, locator kind/value and mapping generation, never an allow decision. Personal scope lookup must preserve account-kind restrictions. Account lifecycle, project rename/deletion, scope classification and registry migrations invalidate affected identities and locator collections after commit, including database-trigger writers. There is no universal source-version column today: add a durable mapping generation before shared admission. During listener recovery bypass mappings whose generation is uncertain. Use bounded expected O(1) LRU; failed/restricted lookups initially bypass admission.

Mock an operation-level lookup budget and account/scope separation. Focused PostgreSQL tests belong to any changed registry SQL or generation migration, including deletion/recreation and rollback.

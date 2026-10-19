# JdbcApplicationIdentityRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcApplicationIdentityRepository.java#L23) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/OwnedApplicationIdentities.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V138__application_owned_identities.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`OwnedApplicationIdentities` reads project bindings joined to token status, optionally `FOR UPDATE OF i,t`, reserves identities, activates the exact project row, and retains one-time credential receipts. Activation resolves project ID, updates identity and inserts token scope; issuance updates token then identity and receipt within the caller transaction. These reads protect uniqueness and interrupted credential recovery rather than ordinary document browsing.

Keep the `Binding` already loaded under lock through validation and activation helpers. Measure repeated project lookup and receipt lookup within a single issuance; return required retained fields with `RETURNING` instead of a success-path reload where safe. V138's project primary key, token identity uniqueness and receipt request key already serve exact predicates. Preserve deletion/recreation refusal and credential-issued preconditions. A cohesive issuance routine could reduce round trips only if it retains lock order, exact receipt fencing and secret-handling boundaries.

Shared cache eligibility is limited to non-secret immutable identity descriptors. Key by database/project identity/token UUID and a new durable binding generation; active state, expiry, revoked state and credential issuance/recovery checks always bypass cache. Never cache digests or credentials globally. Reserve/activate/issued, service-token changes and project/account lifecycle invalidate identity and owner/project collections after commit, including other token repositories. Listener loss forces generation validation or bypass, before any expected O(1) LRU use.

Mock first issue, recovery and changed-declaration query budgets. Focused PostgreSQL tests cover optional locking, duplicate issuance and rollback if SQL changes; do not interpret a cached descriptor as current Application authority.

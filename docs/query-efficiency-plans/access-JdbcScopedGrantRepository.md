# JdbcScopedGrantRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java#L35) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/LiveScopedAuthority.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V156__scoped_rbac_grants.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`live` and `projectBindings` join the complete `authority_live_grants()` recursive function; `lineage` also computes live grants before following at most 32 ancestors. Issuance fetches every live issuer binding to select one UUID, then inserts and refetches its record. Revocation reads, locks project then scope/lineage, checks manager counts and performs expected-version mutation. Live authority cost is a credible cross-owner multiplier, not independently measured here.

Prioritize a targeted live-chain routine for an exact issuing grant/recipient rather than enumerating unrelated grants. Reuse scope classification and binding DTOs inside the same locked transaction. Compare a set-based authority snapshot for callers evaluating several actions. Preserve expiration, token ceilings, root policy, depth bound and last-manager protection. V156 already indexes recipient and issuing parent; inspect recursive function plans and action-array construction before adding indexes. Consider typed `RETURNING` plus bounded joins for the resulting `Binding`, without bypassing trigger-derived fields.

Never share-cache `live`, eligibility, manager counts or mutation lock decisions as authorization. Retained historical binding projections may use database/grant UUID/version/role-version keys under bounded LRU, with fresh authorization for disclosure. Issue/revoke, membership/admin/token/account/project changes invalidate binding and recipient/scope/descendant collections after commit; expiry also changes liveness without a write. Durable generation and recovery are prerequisites for any cached display lists.

Mock one issuing-chain resolution and no redundant hydration. Focused PostgreSQL tests must cover recursive equivalence, expiry/revocation, lineage concurrency and manager lock order for changed SQL.

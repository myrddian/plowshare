# JdbcServiceAccountRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceAccountRepository.java#L35) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServiceAccounts.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ServiceAccounts` manages SERVICE accounts and owner-bound tokens/scopes, sharing administrator/project lock order. Confirmed N+1: `tokens(handle)` selects IDs, then loads each token and its scopes: 1+2N reads. `revokeOwnedTokens` already uses `UPDATE ... RETURNING principal_handle`, a pattern to retain. Token issue loops over scope insertion and project lookup.

Replace token listing with a bounded typed token-plus-scope projection or two bulk queries, preserving owner filtering, creation/ID order and tokens with no scopes. Reuse loaded account/project DTOs in a transaction; batch scope writes after validated project locking without relaxing per-project checks. V111 supplies token owner/name uniqueness and scope keys; inspect listing order and scope joins before another index. Optional `FOR UPDATE` paths must continue to acquire actual row locks. A cohesive issuance routine is useful only if it measurably removes trips while preserving audit and principal creation atomicity.

Never cache credential digests, mutable revocation/expiry state or locked account/token reads as authority. Non-secret token descriptors could use database/owner/token UUID/version keys with fresh disclosure checks and bounded LRU, after adding durable source generations. Issue/rotate/revoke/revokeOwnedTokens, account enablement/retirement, Application identity and project/scope changes invalidate tokens plus owner/scope list dependencies after commit. All these writers must participate in durable invalidation and listener recovery.

Mock constant listing query count for increasing token count, owner isolation and preserved bulk revocation. Focused PostgreSQL tests cover set projection, optional locks and issue/revoke concurrency when implemented.

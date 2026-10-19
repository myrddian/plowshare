# JdbcDurableSessions

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcDurableSessions.java#L48) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/TokenStore.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V81__durable_login_sessions.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`TokenStore` delegates durable login/access/refresh/revoke to this owner. Access authentication is one joined digest/chain/admin query with expiration and session-version fences; legacy `valid`, `handle` and `restricted` each call that loader. Refresh locks the chain, checks spent refresh grants, rotates grants and cleans expired rows. Login issuance deletes all expired chains on each issue, a potential foreground cleanup cost rather than measured slowness.

Use the existing typed `authentication` result once at each transport admission instead of calling three boolean/string accessors. Preserve a fresh check for each new protected operation. Measure login cleanup rows/lock time; move bounded expiry cleanup to its owning maintenance path if it causes latency, retaining expiration predicates on authentication. V81 already indexes chain expiry and grant chain, with digest primary keys; compare refresh/cleanup plans before new indexes. Refresh/revocation remain transactional, single-use and serialized; do not weaken replay detection to save a read.

No shared LRU for presented tokens, digests, refresh state or authentication allows. Reuse only the current operation's immutable authentication DTO. A non-secret session-history display projection belongs to an explicitly versioned cache under the administration owner, keyed by database/account/chain/version. Issue/refresh/revoke and account password/session-version/enable changes invalidate its entity and session collection dependencies after commit; recovery bypass and durable generations are mandatory. Cache notifications never substitute for current authentication.

Mock one authentication lookup per admission and revoked-token rejection. Focused PostgreSQL checks apply to changed rotation/cleanup SQL, competing refresh and rollback.

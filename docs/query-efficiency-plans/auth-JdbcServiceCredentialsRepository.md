# JdbcServiceCredentialsRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceCredentialsRepository.java#L21) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/TokenStore.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ServiceCredentials.authentication` hashes/validates a presented service credential, then performs one digest-bound join from `service_tokens` to its principal account. The query checks expiry and `account_active` through the existing lifecycle conditions. `TokenStore` combines this with ordinary session authentication. This is a narrow authentication lookup, not a candidate for caching away required revocation checks.

Trace transport admission to ensure callers consume the returned typed `Authentication` once instead of separately asking for handle and validity. Distinguish principal authentication from project/token scope authorization; do not merge them into a permissive cached result. V111's unique digest path already addresses the lookup. Profile `account_active` and parent/service lifecycle checks if authentication is measurable at high frame rate, then consider a set-based live snapshot or targeted function while preserving every authority intersection. A new index or procedure has no established benefit for the present exact-digest query.

Shared cache admission is explicitly disabled for presented credentials, digests and allow decisions. Request-local decoded identity can be retained for the same admitted operation, never used as an indefinite authorization lease. Token issue/rotation/revocation, Application credential changes and account lifecycle must invalidate any separate non-secret display descriptor cache after commit at their owning repositories. Durable version recovery and bounded LRU apply there; database notifications cannot guarantee immediate authentication revocation by themselves.

Mock one credential lookup per admission, invalid input, expiry and disabled-owner refusal. Focused database testing is only needed if changing the authentication predicate/function or digest constraint.

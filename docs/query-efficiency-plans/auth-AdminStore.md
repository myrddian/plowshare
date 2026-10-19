# AdminStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java#L35) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketAuthorization.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V37__admins.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`AdminStore` performs exact handle/password lookups, enabled/admin checks, session-version checks and bootstrap mutations under the shared administration lock. It is consumed by login, HTTP/WebSocket authorization and administration. Multiple entrypoints can separately ask enabled/admin/session-current for the same account; trace one frame before declaring those checks redundant. `byHandle` includes a password hash, so it is unsuitable as the universal profile projection.

Introduce a narrow typed current-account snapshot where callers need several non-secret fields, and carry it through a single admission boundary instead of independent booleans. Preserve current session version, account-active semantics, bootstrap exclusion and USER-only administrator checks. Exact handle predicates already use the V37 key; startup bootstrap scans are low-frequency and do not justify speculative indexes. Keep expected-password updates conditional and setup mutations serialized; no new routine is needed for a single indexed lookup.

Do not globally cache password hashes, session-current decisions or enabled/admin authority. Request-local snapshots are reusable only within their declared consistency boundary. Non-secret display profiles could use database/account/version keys after durable lifecycle generations exist; profile listing and account-kind dependencies must also be tracked. Password changes, setup completion, account enable/admin changes, session revocation and token-principal retirement invalidate appropriate identities and collection generations after commit across all owning repositories. Listener recovery must bypass uncertain entries before bounded LRU admission.

Mock a single-admission query budget and ensure new operations recheck revocation. Focused PostgreSQL tests cover only changed snapshot predicates, expected-hash updates or setup locking.

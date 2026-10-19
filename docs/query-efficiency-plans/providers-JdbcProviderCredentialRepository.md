# JdbcProviderCredentialRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/JdbcProviderCredentialRepository.java#L59) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/OwnedProviderConnections.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V139__provider_credentials.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`OwnedProviderConnections` lists up to 257 project connections, loads optional locked connection/credential rows and writes revision/generation-CAS credentials. Authorization looks up a state hash; receipts are request-owned. Connection metadata includes JSON definitions, while credential DTOs contain encrypted material, nonce/key IDs and authorization intent. V139 already indexes project/connection ID and constrains credential identity/state lookup paths.

Reuse the connection DTO resolved under project/connection lock through mutation validation; avoid reacquiring it for name/revision fields. Replace wildcard reads with explicit fields needed by each typed projection, separating list-safe descriptor data from credential storage. Compare generation-CAS `RETURNING` for response construction rather than reloading credentials; preserve expected-generation conflict refusal and receipt reconciliation. Measure bounded list decode/bytes and state-hash selectivity before indexes. A stored routine is only warranted for a cohesive measured transition and must retain project lock order and secret boundaries.

Do not globally cache credential ciphertext, secrets, authorization state hashes, expiry or live grants. Non-secret connection descriptors can use database/project/connection UUID/revision/projection keys under bounded LRU with current access fences. Create/update/revokeAll/store and receipt/connection lifecycle writers invalidate descriptor and project-list dependencies after commit; credential mutations need not expose private data in notification payloads. Existing connection revision and credential generation are useful identities but not automatically durable commit-order recovery. Add change evidence and listener bypass before shared admission.

Mock one connection hydration per mutation and strict account/project isolation. Focused PostgreSQL tests cover CAS, authorization-state uniqueness and rollback when SQL changes.

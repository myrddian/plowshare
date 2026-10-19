# JdbcAccountAdministrationRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcAccountAdministrationRepository.java#L25) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServerAdministration.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V81__durable_login_sessions.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`ServerAdministration` reads account/list/session/audit projections and writes accounts, resets passwords, changes administrator status and revokes session chains. Account lookup and list already exclude bootstrap/non-USER identities. Audit history uses optional-filter OR predicates with descending ID pagination; session lists select live chains by handle and created order. These paths are administrative, with no measured latency.

Pass the `Account` loaded under the administration lock into validation/audit helpers; avoid rereading it solely to construct the audit record. Use changed-row projections with `RETURNING` if callers need post-update state. Split audit history into a small allowlisted set of SQL shapes for global versus target history, so target selectivity is visible to PostgreSQL. V81's expiry index addresses cleanup, not automatically handle/live/order listing; compare a handle/created-at path and audit target/ID index with realistic history before a new migration. Keep last-enabled-administrator checks authoritative under the existing lock.

Immutable audit pages bounded by an ID watermark are possible cache values; keys include database, authenticated administrator, target, cursor, limit and watermark. Account/session status stays uncached for authority; display snapshots need durable versions. Create/update/reset/revokeSessions plus AdminStore/service-account writers invalidate account, session-list and audit collection generations after commit. Cache admission depends on current administrator disclosure checks, durable change evidence and recovery bypass, with expected O(1) LRU and byte bounds.

Mock snapshot reuse and audit pagination query budgets. Focused PostgreSQL checks cover revised predicates/indexes, transaction rollback and last-administrator races only when SQL changes.

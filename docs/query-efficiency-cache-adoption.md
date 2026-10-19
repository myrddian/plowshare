# Versioned document, search and schedule caches

This slice connects the [tracked DTO cache foundation](query-efficiency-cache-foundation.md)
to document metadata/extraction, admitted search snapshots and schedule file UI
reads. The [following Relay slice](query-efficiency-relay-slice.md) adds a fifth
version domain and two inspection caches; this receipt describes the original
five-cache delivery. Deployment is separate. PostgreSQL remains authoritative; cached objects
do not retain permission to read, execute or mutate anything.

## Reads and transaction ownership

The typed `ProjectionCache<T>` capability runs an elected loader inline on the
caller's thread. Coalesced readers share its pure projection, preserving the
existing caller-bound disclosure/profile locks and transaction connection.
Current authorization admission and per-reader audit callbacks are outside load
coalescing. The concurrent maps and sets support parallel/reentrant callers; the
short compound lock still atomically maintains LRU links and resource budgets.
Same-cache recursive loader cycles fail immediately instead of waiting on
themselves until the deadline.

- Document metadata and extraction check current SQL readability on every read.
  Metadata's warm check selects existence only, without computing and discarding
  the metadata projection. Cache misses bound projected bytes in SQL before
  transferring and decoding content. Larger explicit reads retain their ordinary
  uncached semantics.
- Search keys retain the complete authenticated context/read ceiling, question,
  exact copied vector, depth, projection and captured embedding profile or legacy
  fingerprint. Only the admitted, final-coverage path participates; legacy reads
  retaining pre-provider coverage remain direct. Cache hits repeat each caller's
  selection admission and audit for every returned candidate. SQL bounds text in
  the selected candidate projection with `CASE`, preserving membership, ranking
  and limits; oversized candidates trigger a full ordinary read, never truncation
  or a different ranking.
- Schedule UI file and collection reads refresh only source identity before
  consulting the cache. Warm reads do not scan definitions or collection sizes;
  cold SQL enforces the size/count bounds. Collection admission is limited to
  256 files. Empty collections and missing files preserve their contracts.
  Execution-facing `managed()`/`requiresDefinition()` and mutation decisions stay
  direct. Apply/pause retain fresh snapshots under their existing source locks;
  `RETURNING` results are never published from an uncommitted mutation.

`V175__projection_cache_version_fences.sql` marks relevant writes transaction-local
using `set_config(..., true)`. Shared admission refuses dirty transactions,
historical isolation levels and read transactions spanning an authority expiry.
The guard runs again after a loader. Commit, rollback and savepoint rollback
restore the local marker with PostgreSQL's transaction semantics.

## Durable versions and late commits

The migration installs a stable database namespace and four fixed dependency
domains: documents, search, schedules and authority. Statement triggers cover
the source, projection, collection and authority tables used by these reads,
including cascades and direct SQL writers. Expiry indexes support the authority
window fence. New dependent relations must extend this explicit trigger inventory.

Each nonempty changed statement allocates domain sequence values with `CACHE 1`
and `NO CYCLE`. A transaction ID is established before allocation. Zero-row
statements, including an unchanged source registration's `ON CONFLICT DO NOTHING`,
do not invalidate the cache. Truncation invalidates its domain as well.

A sequence allocation is **not commit order**. Reading its maximum alone would
miss an earlier writer committing after a later writer. Version capture therefore
uses a PostgreSQL stored function to read counters first and then assign a probe
transaction ID. That autocommit statement completes before a second, fresh READ
COMMITTED snapshot. This completion barrier matters: the snapshot's explicit
pending-ID list can omit the newest allocated IDs beyond its completed-transaction
boundary. Completing the later probe brings earlier allocated writers inside the
snapshot's enumerable range. Capture consumes one short-lived transaction ID;
it performs no source-row write or shared epoch-row lock.

The captured pending top-level transaction IDs fence any allocation
whose commit was not yet visible. Current validation rejects a captured pending
transaction that committed or whose status is unknown, even if every counter is
unchanged. Rollbacks conservatively consume counter values but expose no source
changes. Pending evidence is bounded; overflow bypasses sharing.

The actual loader token stays beside its DTO. Its pending transaction evidence
is checked on every hit; a freshly captured token cannot make an older DTO fresh.
Pending IDs are not part of the cache key, so concurrent readers do not create
distinct identities merely because their transactions differ. Only the safe
caller's own transaction ID is excluded from its pending evidence: prior watched
writes make its dirty guard fail, and future watched writes change the counters.

Authority can expire without a write. Tokens include the elapsed/next expiry
window from grants and service tokens, and validation checks the database clock.
Fresh tokens from a different window cannot join an older visibility projection.
Capture additionally requires the caller's SQL transaction timestamp to belong to
the completed token's window, including an expiry racing capture itself.
Current permissions and disclosure fences still run; neither a token nor a
healthy listener is an authorization lease.

## Notifications, recovery and lifecycle

The database triggers emit fixed domain hints on one fixed channel. Payloads
contain no object identifiers or private content. PostgreSQL delivers them only
after commit and discards them on rollback, following the documented
[NOTIFY transaction semantics](https://www.postgresql.org/docs/16/sql-notify.html).
The listener coalesces retained hints
into a fixed domain set; it owns one dedicated unpooled connection.

[LISTEN registration commits before the recovery baseline](https://www.postgresql.org/docs/16/sql-listen.html).
Cache namespaces start
blocked; reconnect/outage advances their recovery fences and clears residency.
Only the current recovered listener generation reopens reuse. Stale or reordered
state callbacks cannot reopen a newer outage. Notifications promptly invalidate
resident/pending reads, while authoritative per-read token validation handles
delayed, coalesced or missed notifications. No sequence watermark is treated as a
durable notification cursor.

Version checks use a separate bounded connection pool, so reads already holding
main-pool disclosure connections cannot exhaust that pool while waiting for a
second lease. All connection addresses, credentials and driver configuration come
from the configured database. Shutdown closes subscriptions, caches, the listener
session and its version pool. Notification retry re-establishes infrastructure;
it never replays a mutation or provider call.

An ambient Spring transaction can retain its independent version-pool lease until
transaction completion. Each version statement still commits in autocommit mode
and gets a fresh snapshot; physical leases need not differ between statements.
Pool sizing and enclosing read-transaction duration therefore affect throughput
and should be measured alongside saved projection work. Capture also compares the
caller and authoritative database namespaces, refusing sharing if they differ.

## Configuration and performance scope

`plowshare.projection-cache` configures per-projection entry/weight/load/waiter
limits, value reservations, pending-XID bounds, listener timing, projection count
and independent version-pool capacity. Defaults allow 256 entries and 32 MiB of
resident declared weight per projection, 64 MiB of loading reservations, eight
physical loads, 256 waiters and 8 MiB per DTO. Token and cache metadata are
additionally charged; actual simultaneous loads may be fewer when reservations
fill the weight budget. The five adopted caches share four authoritative-version
connections and one listener connection by default.

Warm reads save projection hydration, decoding and ranking SQL; they still pay
for live visibility/source checks and authoritative freshness probes. These are
explicit consistency costs, not repeated content reads. This slice makes no
deployed latency claim. Domain invalidation is deliberately broader than an
entity-only cache and can cause conservative misses during processing or
concurrent transaction completion.

For the shared-cache boundary itself, a warm hit uses three capture statements
(caller eligibility, counter/completion barrier, pending/expiry snapshot) and one
stored-token validation statement. Document and schedule owners add one cheap
live readability/source query. Search skips its final coverage and both hybrid
ranking queries on a hit; its pre-provider gate and enclosing disclosure/profile
checks retain their separate contracts. A cold elected loader adds its projection
SQL, one caller dirty-state check and one pre-publication token validation.
These are query budgets, not PostgreSQL latency measurements.

## Verification

Verification passed locally on 2026-10-20:

- `./gradlew format` and `./gradlew formatCheck`.
- 87 non-database tests selected with `:plowshare-server:test`, covering the cache
  package, `SearchCacheTest`, `ScheduleProjectionCacheTest` and
  `InformationProjectionCacheTest`. These verify bounded concurrent/reentrant
  loads, cycle rejection, stored-token validation, lifecycle races, authorization,
  per-reader audits and avoided warm projection queries.
- 27 existing search snapshot/configuration tests selected from
  `SearchSnapshotTest`, `DocumentsConfigTest` and `InformationFeatureConfigTest`.
  Their fixtures initialize the real repository/factory contracts while keeping
  persistence mocked; admission and query-budget assertions remain intact.
- 40 PostgreSQL tests selected with `:plowshare-server:test -PfullDb --tests` for
  `ProjectionVersionsDatabaseTest`, `InformationCatalogueReadPersistenceTest`,
  `ScheduleFilesDatabaseTest` and `SearchCacheDatabaseTest`. Real database coverage
  is required for migration/row mapping, MVCC and sequence late-commit races,
  transactional notifications, expiry racing capture, dirty/savepoint rollback,
  bounded SQL and Boot datasource/listener composition. Both an active tail writer
  without a newer commit and a separate database namespace are explicitly covered.

- `./gradlew check --continue --max-workers=1`: complete default repository check
  passed, including all 6,093 server tests, formatting, documentation, client/SDK
  and boundary checks. The default run does not enable database tests.

No production database benchmark or deployment is claimed by these local tests.

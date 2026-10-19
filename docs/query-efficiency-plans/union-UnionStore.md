# UnionStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/union/UnionStore.java#L82) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/union/UnionRouting.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V46__project_union.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`UnionRouting` and `UnionFrames` load union-enabled client/Personal project metadata, list enabled projects, manage hidden sync paths and retain ordered open conflicts. Routing helpers independently call `mirrored(project)`, each reaching `find`; building providers, mirror descriptions and conflict notes can therefore reload the same project snapshot. Conflict insertion already uses `MAX(n)+1 ... RETURNING` with bounded duplicate retries. No timing establishes this owner as a major cost.

Resolve a typed `Union`/mirror context once per request and pass it across routing helpers, while checking current gate/session state at its owner. Bound conflict lists and select only note fields when full blob IDs are unused. V46 has a partial open-conflict project index and composite conflict key; compare `(project_id,n)` open ordering before another index. Enabled-project scans are likely small; measure before a partial enabled/name index. Confirm `disable` deletion plus state update is covered by the caller transaction; if not, make that ownership explicit. A procedure is optional only for a measured atomic transition benefit.

Cache immutable union descriptor snapshots keyed by database/project ID/generation/projection, with scope authorization before disclosure. Machine/workspace/hidden/exclusions are sensitive metadata; session LIVE state and sync/write routing fences remain uncached. Enable/disable/setHidden, conflict open/resolve and ProjectStore rename/workspace/exclusion/delete writers invalidate descriptor, enabled-project and open-conflict collection dependencies after commit. Durable project/conflict generations and listener recovery precede bounded expected O(1) LRU admission; empty conflict lists need insertion invalidation too.

Mock one metadata load across routing helpers and current gate changes. Focused PostgreSQL tests cover conflict allocation, disable atomicity and new indexes only when implemented.

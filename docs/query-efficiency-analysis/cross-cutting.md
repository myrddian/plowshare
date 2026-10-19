# Cross-cutting latency and cache analysis

Status: analysis only. Source checkout `7bc567c2282188925ac07c81e6fcae81b41a0a40`;
runtime base `fc36351f239b62984363ababaa1de1139402aafb`. No query, schema,
cache or dispatch optimization was implemented. See [measurements](measurements.json)
for samples and provenance and the [analysis index](README.md) for owner coverage.

## What the refreshed measurements establish

The running image reports revision `b96f260203bc880441a0c7709918926e5e642f1d`.
Its production-source difference from the runtime base is `Definitions.java` from
PR72; JDBC implementations are unchanged. PostgreSQL is 16.15, migrations through 174.
These are measurements of that deployment, not a deployment of the planning branch.

The probe used the already configured account through the Node SDK's segmented
WebSocket transport. Its first raw-socket attempt could not authenticate; normal
SDK renewal under the existing credential lock established the successful session.
No domain mutation was submitted. Database probes used READ ONLY with a 20-second
statement limit and 2-second lock limit. Private account/project names, addresses,
credentials, resource IDs and raw operational dumps are excluded from this report.

| Read or comparison | Samples | Observed duration |
| --- | --- | --- |
| Manual global list, returning 0 rows | 3 | 91.951–100.016ms |
| Manual global typed facets, total 0 | 3 | 1104.811–1166.503ms |
| Manual personal typed facets, total 37 | 3 | 1885.978–1968.771ms |
| Manual chapter lookup / status / text | 1 each | 12.769 / 31.568 / 11.601ms |
| Relay topics in isolation | 3 | 24.278–26.854ms |
| Relay messages in isolation | 3 | 27.636–29.566ms |
| Relay topics behind facets on the same socket | 3 | 1840.808–2042.069ms |
| Relay messages behind facets on the same socket | 3 | 1866.097–2073.920ms |
| Relay topics on a separate socket while facets run | 3 | 22.094–25.487ms |
| Relay messages on that second socket | 3 | 49.001–52.913ms |
| Schedule list, returning 2 rows | 1 | 2.492ms |
| Schedule file list, returning 1 row | 1 | 62.860ms |

These durations include transport, server queuing, decoding and client scheduling;
they do not measure UI rendering or isolate SQL time. Three samples provide ranges,
not a p95 or load-test distribution. The second-socket topics/messages themselves
remain ordered, so their durations are not two simultaneous SQL timings.

The Manual still has distinct global and personal result sets. Historical intake,
publication state and ownership must be resolved through their own lifecycle;
sharing personal results into a global cache would be a disclosure defect.

## C01: slow facets block independent work on a shared connection

[EventRequests.dispatch](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventRequests.java#L69)
drains one request and executes `route.accept` synchronously before the next.
[EventChannelHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java#L572)
offers assembled requests to that queue and rechecks socket authority at execution.
The [Desktop client](../../plowshare-desktop/src/client.ts#L773) routes both
information and Relay operations through its current connection.

The same/separate-socket comparison demonstrates a per-connection blocking
mechanism: a roughly 1.9-second facet can hold a roughly 25-millisecond Relay read
for its duration. It does not establish that every slow UI screen has this one
cause. JDBC tuning alone cannot remove this queue dependency, and unbounded
parallelism could exhaust the same database pool.

Commit `2018378cfdf954a3be0c2c8e0711d5781a467a03` introduced this explicit
segmented dispatcher to keep packet credits responsive during slow operations.
Its parent routed completed domain frames synchronously too, so this is not
evidence that the commit introduced serialization as a new regression.

The justified next change is an explicit scheduling policy for bounded independent
reads, with ordered unknown operations and mutation/read-after-write barriers.
Consume/subscribe/claim/cursor operations cannot be classified from their names.
Retain current authority admission, request correlation, byte/task/pool bounds,
disconnect semantics and uncertain-completion rules. A dedicated read connection
was a diagnostic control, not a proposed client workaround or new API boundary.

## C02: the information view waits for facets and repeats unchanged work

[reload](../../plowshare-desktop/src/renderer/information.ts#L470) awaits a
`Promise.all` containing list and facets before it renders either. A facet refusal
also enters the shared failure path and replaces the collection with an error.
[The timer](../../plowshare-desktop/src/renderer/information.ts#L1647) calls reload
every 4 seconds while active, connected, idle and outside a focused form control.
The `reloading` guard prevents overlapping reloads; it does not reuse unchanged
list/facet results. At the nominal period, a typed view can request 12 facet
statements every 4 seconds, plus its list and any selected detail work. This is a
source-derived scenario, not a measured sustained query rate.

For a selected active authored revision,
[inspect](../../plowshare-desktop/src/renderer/information.ts#L554) requests status
and then content on each successful refresh. The view already retains rendered
text under a key containing scope/revision/generation/extraction/offset, and
capability/type discovery has identity guards. Do not describe all client data as
uncached or propose refetching valid text again.

The list/facet join is present in public snapshot `f78e4f04`; that provenance is
not proof of its original introduction before the snapshot. Separate rendering
and error states, operation-scoped DTO reuse and identical in-flight read sharing
are supported by the source. Long-lived list/facet reuse additionally needs
selection membership versions and scoped server invalidation. Preserve the
existing connection/selection generation checks and clear private state across
identity changes.

## C03: readability work multiplies inside each aggregate

Identical Manual count queries, differing only by `information_readable`, returned
37 rows in both cases. Without the predicate they took 3.504,0.715 and 0.711ms;
with it they took 172.360,159.326 and159.646ms. These matched controls isolate
the predicate more carefully than comparing queries with different projections
or other filters. They do not show that removing authorization is acceptable.

The separate profiled Manual predicate evaluation invoked revision readability 74
times and account activity 148 times for 37 rows. One event-recipient readability
evaluation with a single dependency took 758.838ms and invoked
`authority_project_role` 78 times and `account_active` 6547 times. Parent function
total times include descendants and must not be summed as independent costs.

The source explains both multipliers:
[catalogue facets](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java#L274)
repeat authorized selection 10 times ordinarily or 12 with typed dimensions, while
[event readability](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationEventRepository.java#L63)
tests alternative selections and projects through nested functions.
[V172](../../plowshare-server/src/main/resources/db/migration/V172__substrate_knowledge_reads.sql#L34)
checks root and dependency readability; live roles descend through grant lineage
and account/token lifecycle checks.

The priority is one evaluated authorized candidate set for the aggregates and
set-based actor/scope/dependency facts within the valid operation snapshot.
PostgreSQL already contains many stored functions: wrapping the same scalar loop
in another routine would preserve the cost. A table-returning function or cohesive
query must actually share evaluated work. It must retain lineage depth, dependency
overflow/cycle handling, root privacy/audience, service-owner restrictions and
current revocation. SQL STABLE is not automatic memoization. The
[readability workstream](../query-efficiency-plans/readability-functions.md) records
the required invariants; D01/D04 and the event analyses provide owner evidence.

## C04: index candidates have different measured urgency

Revision history returned 93 rows after filtering out 54976 in a sequential scan,
then sorting by sequence; its measured execution was 3.974ms with 834 shared-hit
scan buffers. The only observed event index was the sequence primary key.
`(revision_id,sequence)` matches the read, but the measured cost is milliseconds,
not the dominant second-long Manual delay. A later count was 55117 events because
the live dataset continued to change; these are not one atomic database snapshot.

The Relay outbox has a `COALESCE(project_id,0),topic,sequence` index while the
query uses `project_id IS NOT DISTINCT FROM ?`. The live outbox was empty:
single-pending and partition-discovery plans took 0.016 and 0.029ms. That cannot
establish a benefit from changing its index at scale. Likewise, a partial index
whose predicate covers fewer states than a query is a candidate for plan review,
not proof that PostgreSQL never uses it or that another existing index cannot help.

Prioritize measured authorization/aggregate work and proven N+1/overfetch first.
Use representative read plans and result-equivalence checks before new composite,
partial, expression, GIN or search projections. Index/routine changes belong in new
Flyway migrations; no index was created for this analysis.

## C05: a database DTO is not automatically a cacheable current object

The owner reports inspect actual DTO fields and writers. Two concrete examples
show why existing generation fields cannot be adopted mechanically:

- [Workspace publish](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/JdbcCodeWorkspaceStore.java#L148)
  changes state, issues and sources at the claimed ticket's same generation.
  Checking and ready projections can therefore have the same generation.
- [Outgoing cancellation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/JdbcOutgoingRepository.java#L238)
  changes cancel/state fields without advancing the worker revision. A separate
  display/change generation must preserve existing worker/report fencing.

Some records also contain live budget behavior, mutable collections or derived
states that change after append. Other repositories, triggers, cascades and
background workers write the same dependencies. New rows must invalidate a
collection even when the old result never contained them. Existing local reuse,
including retained hook pinning and client rendered text, should be measured and
preserved rather than replaced blindly.

The resulting admission order is:

1. Reuse a typed authorized snapshot inside its actual operation/transaction
   boundary. This can remove needless reads without creating a shared freshness
   protocol. A pre-provider read does not automatically remain current after IO.
2. Track proven immutable content/descriptors by exact identity and content/version,
   admitting only committed, deeply immutable values under byte/entry/in-flight
   bounds. Retention and current disclosure still require their own fences.
3. Add mutable heads/lists/facets only after complete source/change generations,
   all-writer commit invalidation, collection dependencies and recovery exist.
4. Keep grants, claims, leases, token authentication, readiness and executable
   eligibility authoritative. Cache notification lag cannot guarantee immediate
   revocation. An authority check may remain on a warm content hit; batch it for
   the operation rather than repeating it for every DTO.

The [tracked DTO/LRU strategy](../query-efficiency-and-cache-design.md) supplies
expected O(1) lookup/recency/individual eviction; evicting or invalidating k entries
costs O(k). It is still proposed infrastructure. LISTEN/NOTIFY can wake discovery
and invalidate committed versions, but notifications are neither a durable ledger
nor proof of current allow decisions. Commit-order gaps, reconnect, stale fills,
time-based expiry and lease deadlines still need explicit handling.

## C06: status rebuilds the complete fingerprint configuration for each step

The independent count review exposed work outside the immediate catalogue SQL:
[status mapping](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java#L833)
calls `fingerprint` for each visible step, and
[fingerprint](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java#L48)
calls the configuration supplier each time. The
[production supplier](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationConfig.java#L337)
reconstructs the whole map, including the global agent snapshot. When dual
embeddings and both targets are configured,
[dual fingerprint](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DispatchingDualEmbeddings.java#L65)
does two target lookups, and each
[profile mapping](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcEmbeddingWorkRepository.java#L163)
queries both its slot and space descriptor.

Thus S visible step rows add 4S JDBC statements under that configuration, beyond
the catalogue-owned budget `7 + O + 2R + d` for a readable revision. O indicates
the owner's history read, R indicates a report, and d counts configured derive
rows that reload revision text. Seven step rows, one derive row, an owning caller
and a non-report yield37 statements in this source model, excluding outer access
checks, transaction commands and function-internal work. This is a conditional
count, not a traced count of the measured 31.568ms status request. Stored steps
include old generations, so S need not remain seven.

Compute a fresh fingerprint map once for the status operation and reuse immutable
space descriptors; carry already loaded metadata through derive/owner checks.
This has an operation-local consistency contract and does not require starting
with a shared cache. Independent review also corrected transcript byte assumptions:
narrative entry kinds return full content; an 8000-character cap applies only to
other kinds. The owner analyses retain these distinctions.

## Decision supported by this analysis

Implement separately reviewable work for facet/permission consolidation, narrow
snapshot reuse and confirmed N+1 paths, client loading and bounded dispatch, then
shared version/change infrastructure and repository cache adoption. Source-derived
before/after budgets in the owner reports are hypotheses to verify during those
changes, not measured performance improvements. Several apparent duplicates are
necessary after locking, provider IO, cancellation or hooks; retain them until an
equivalent consistency boundary is proved. Database tests were not run: the live
SELECT diagnostics and source analysis do not establish migration, concurrency or
cache correctness.

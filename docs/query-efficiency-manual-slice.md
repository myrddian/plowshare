# Manual and facet read caches

This slice follows the [Relay work](query-efficiency-relay-slice.md). It connects
catalogue revision lists, navigation head pages and both facet projections to the
[bounded tracked DTO cache](query-efficiency-cache-foundation.md), using the
existing [durable version and listener bridge](query-efficiency-cache-adoption.md).
It changes read composition and query work, with account and scope policy
preserved. Deployment and representative production timing remain separate.

## Projection ownership and saved work

| Repository read | Cached immutable value | Selection retained in its identity |
| --- | --- | --- |
| Catalogue list | `List<Information.Revision>` | Complete information context, kind, applied limit and offset |
| Catalogue facets | `Information.Facets` | Complete context, kind and exact pinned revision selection, including null versus empty |
| Navigation documents | `InformationNavigation.Documents` | Complete context, related resource, relation, direction, limit and offset |
| Navigation facets | `Information.Facets` | Complete context, related resource, relation and direction |

The information context includes the authenticated account, exact scope/project
and Global inclusion flag, corpus, all metadata/type filters, typed view and the
retained read ceiling. Keys use a length-delimited hash of the complete typed
selection. Navigation heads, catalogue revisions and their facet projections
remain different identities: a newer head does not erase an older catalogue
revision or change an explicitly pinned selection.

A warm read skips the owning candidate, permission and projection SQL altogether;
it does not fetch an authorized collection merely to discard it before a cache
hit. Each cache still performs the existing three authoritative capture statements
and one validation of the token retained alongside the actual DTO. A cold elected
loader executes one owning statement, with the existing post-load version and
dirty-transaction guards. Concurrent loads use the foundation's coalescing,
resource limits and version fences. Retained DTOs remain immutable historical
values when their resident cache entries are invalidated.

Cold facets continue to materialize the authorized candidates once and feed every
dimension, graph and total from that same relation. Related navigation queries
now evaluate the invariant parent readability and read ceiling in one
uncorrelated scalar subquery. They also evaluate the candidate dependency closure
once: the two former own/reference functions have identical definitions in
V172. This removes duplicated permission traversal without changing those
functions' policy, cycle termination or fail-closed dependency bound.

Ordinary cold lists still evaluate complete readability for each candidate.
Set-based reuse of live grant/scope facts across an entire corpus remains a
separate [readability workstream](query-efficiency-plans/readability-functions.md).
Cache adoption does not establish that this cold-query cost has disappeared.

## Authority and transaction boundaries

Selection admission remains outside coalescing and runs on every operation.
Navigation also checks the active account on every operation. Per-candidate
readability, current root audience, dependency visibility and retained read ceilings
remain in the cold SQL before ordering, paging or aggregation. Both DOCUMENTS and
AUTHORITY source versions fence every shared result. Membership removal, account
or credential changes, policy changes and dependency updates therefore prevent
reuse even when notification delivery is delayed. The shared expiry window
continues to cover grant/token expiration without a database mutation.

Navigation DocumentList and DocumentFacets each produce their complete collection
in a single SQL statement. Their dedicated read entry point sets the transaction
read-only and preserves its configured isolation, ordinarily READ COMMITTED.
Multi-statement navigation operations retain the existing repeatable-read
snapshot. An ambient historical transaction keeps its isolation and bypasses
sharing; no current version is attached to historical SQL results.

Immediate duplicate selection admission is removed from code outline/symbol reads,
and code projection admits once inside its existing transaction. Permission checks
separated by database work, callbacks, changed selections or mutation phases are
retained. Application role resolution can consult filesystem policy as well as
database state, so a database snapshot alone does not justify retaining its
permission decision across such work.

## Admission, invalidation and capacity

All four caches use bounded SQL projection admission before Java hydrates variable
text or JSON. Pages retain their original ordering and limit before size checking;
facets retain their sentinel counts, graph limit and database collation. Oversize
results use one ordinary uncached fallback with complete output. They are never
truncated or cached as an empty result. Source failures propagate without replay.
Catalogue facet selections above 1024 pinned revisions also use direct reads.

V177 adds DOCUMENTS statement triggers for relationship insert, update, delete and
truncate. Removing a relationship can change related page membership while its
retained reference obligation survives; watching obligations alone was
insufficient. The triggers reuse V175's local dirty marker, source allocation and
commit-only fixed-domain NOTIFY payload. Existing document/authority watches cover
resources, revisions, heads, reports, stages, type bindings, navigation/root
policies, inputs, links, obligations, budgets, accounts, grants, credentials and
projects. Recovery uses the existing LISTEN-commit/baseline protocol and epoch
fences; missed notifications are handled by durable token validation.

Production now registers eleven projection caches. The default
`plowshare.projection-cache.max-projections` increases from eight to twelve,
while per-cache budgets remain unchanged. Custom lower limits fail explicitly at
registration. With default resident budgets, eleven fully occupied caches can
retain 352 MiB of declared weight, plus the separately bounded loading and cache
metadata budgets. This is a configured upper bound, not measured heap usage.
Broad document/authority invalidation remains conservative during concurrent
processing; further entity-specific invalidation needs its own complete dependency
and recovery design.

## Verification

The focused tests cover exact cache identities, warm query budgets, bounded
hydration, complete fallback results and operation admission. The tagged PostgreSQL
tests exercise actual row mapping, ordering, relationship invalidation, tenant and
read-ceiling isolation, authority revocation, dirty rollback and historical
snapshot bypass. Notification hints are deliberately withheld in cache adoption
tests to establish that durable fences alone prevent stale reuse.

Validation passed locally on 2026-10-20:

- `./gradlew format formatCheck`.
- 69 focused non-database tests covering catalogue/navigation caches, admission,
  facets, existing document projections and service authorization.
- 35 distinct PostgreSQL cases across `ManualProjectionDatabaseTest`,
  `InformationFacetAggregationPersistenceTest`,
  `InformationCatalogueReadPersistenceTest` and `DocumentNavigationPersistenceTest`.
  These were selected with `:plowshare-server:test -PfullDb --tests ...` because
  the new trigger, SQL mapping and isolation assertions require PostgreSQL. After
  correcting actor attribution in the new publication fixture, its six new cases
  were rerun successfully; the other 29 had already passed.
- `./gradlew check --continue --max-workers=1`: 6,131 server tests, zero failures,
  errors or skips, with repository client/SDK, documentation and boundary checks
  passing. Database tests were disabled for this full check.

The PostgreSQL warm-read budget is one owning statement for each cold admitted
projection and zero additional owning statements for the next identical read of
all four projections. Current admission and version probes remain outside that
count. Query counts establish avoided work; they do not establish deployed latency
or resolve the historical Manual import/publication provenance issue.

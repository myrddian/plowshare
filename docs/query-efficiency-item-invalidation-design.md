# Item-level cache invalidation and shared read data

Status: proposed design, not implemented. It extends the
[query and cache strategy](query-efficiency-and-cache-design.md) and the
[deployment follow-ups](query-efficiency-deployment-followups.md). The current projection caches,
FETCH lane and notification freshness stay in place until each slice below replaces them. An
independent review of the first draft is reflected throughout; its corrections are summarised in
[Review changes](#review-changes).

## Problem

### One write empties a whole projection

The projection caches are bounded LRU stores, which only pay off when an item is read many times.
Today a single write discards every item in the affected projections:

- **Notifications name a domain, not an item.** Triggers send only the domain name, for example
  `pg_notify('plowshare_readability_changes','readability')` (`V202`).
- **A domain change clears the projection.** `VersionedProjectionCache` calls `cache.invalidateAll()`
  for any change in its domains.
- **Versions are domain-wide.** An entry's version is a hash of whole-domain counters
  (`ProjectionVersions.Token.sourceVersion()`), so even without the clear, any write in the domain
  makes every stored entry fail validation.

READABILITY moves on every insert into `conversations`, `orchestrations` and
`information_job_inputs` (`V202`). Every agent step therefore empties every account's log
visibility entry, and all accounts reload together. That reload was refused for capacity and ended
a PROD agent job.

The in-memory store already supports the right behaviour: `SnapshotCache.invalidate(Key)` and
`invalidate(Dependency)` drop only the affected entries, in time proportional to them. What is
missing is the information to use them: which items a write affected.

### Values are authorized per account

Most projections hold results already filtered for one account, because authorization runs inside
the SQL that fills them. The same revisions, resources and scopes are re-read, and the same SQL
permission functions re-evaluated, once per account. The targeted readability check was about half
of database time while PROD agent jobs ran.

## Principles

1. **An invalid item is a miss for that item only.** A write invalidates exactly the cached items
   that read the changed rows. Their slots are refilled on the next read; every other item stays.
   Clearing a whole projection is reserved for recovery (listener loss, `TRUNCATE`, version-store
   reset).
2. **Every cached item declares what it read.** Its dependencies name rows or row groups, such as
   `conversation:<root>` or `revision:<id>`, and its version records those items' counters, not a
   domain's.
3. **Shared data is cached once, authorization is applied in memory.** Where the data is small and
   tenant-independent, the cache holds it without an account filter, and per-account views are
   derived in Java from it and the authority snapshot.
4. **The cache owns database capacity.** Fills use the cache's FETCH slots. Concurrent misses for one
   item wait on a single reload.
5. **A request has one deadline.** The wait for a slot and the wait on a shared reload draw on the
   same budget.

## Slice 0: item versions and keyed notifications

This is the foundation; the later slices depend on it.

### Version store

A new Flyway migration adds an item version table, for example
`snapshot_cache_item_versions(domain, item, counter)`, with `item` a bounded opaque address such as
`conversation:<root id>`. Triggers on the watched tables compute the affected items for each row
change, increment their counters and record them for notification. Domain counters remain, so
current projections keep working while owners migrate.

- **Item addresses** are owner-defined and bounded: an address names a row group the cache reads as
  a unit, never content or an allow decision. The existing `SnapshotCache.Dependency` record already
  has this shape.
- **Fan-out is bounded.** A trigger that would touch more than a fixed number of items in one
  statement (a bulk update, a cascade, `TRUNCATE`) bumps the domain counter instead, which
  invalidates the whole projection as today.

### Keyed notifications

After commit, the listener receives the changed items, for example
`pg_notify('plowshare_item_changes', 'readability|conversation:<root>')`. PostgreSQL limits a
payload to 8,000 bytes, so a transaction that changes many items sends several notifications, or
one domain notification when it exceeds the fan-out bound.

On a notification, the cache invalidates the matching dependency in memory
(`SnapshotCache.invalidate(Dependency)`) and retires trusted hits for those items only. Listener
loss, recovery and domain notifications still clear the projection, because they cannot say which
items changed.

### Validation

- **Versioned hit.** A hit compares the counters of its own items, read with one indexed statement,
  instead of capturing every domain. This keeps read-your-writes: a reader sees a write once it has
  committed, on any process.
- **Trusted hit.** For notification-trusted projections, a hit is served without SQL while delivery
  proof is within `plowshare.projection-cache.notified.staleness` (2 s) and none of its items was
  notified since it was stored. With several server processes, a read on one process may lag a
  write committed on another by up to that bound; projections that must not lag stay versioned.
- **Trusted-hit bookkeeping** is keyed by item, so one change no longer clears the trusted map. The
  map's 256-entry limit is sized per projection.

## Slice 1: targeted readability from shared data

- **Shared data.** Revisions, resources, scopes, root and navigation policies, dependency closures,
  reference obligations, links and contained principals, each cached by item (`revision:<id>`,
  `resource:<id>`, `scope:<id>`). PROD holds 118 revisions, 84 resources, 328 retained inputs and
  8 scopes.
- **Per-account view.** `readable(account, selection, revision)`, evaluated in Java against the
  shared items and the authority snapshot, with dependencies on every item it read.
- **Scope.** Only the targeted check (`InformationCatalogueRepository.readable`) at first. Catalogue
  lists, facets and readiness follow once the view has matched SQL in production.
- **Rules to mirror.** These are much larger than the authority snapshot:
  - `knowledge_read_authority`, including token-owner limits and name-pattern fences (`V229`);
  - `knowledge_readable_candidates`, with fourteen parameters, three predicates per node, the
    1,024-node closure cap, the related-resource bypass and the boundary-descendant walk (`V229`);
  - `information_readable_input_selections` (`V235`);
  - `information_navigation_policy`, `information_public_dependencies`,
    `knowledge_dependency_revisions`, `information_navigation_head` and `information_privacy_allows`.
- **Prerequisite.** `contained_principals` has no version trigger, so a direct principal revocation
  would never invalidate a cached copy. Slice 0's migration adds it.

## Slice 2: log visibility per log

The cached item becomes the verdict for one account and one log tree, not the account's whole deny
set:

- **Item.** `log-readable(account, root log)`, with dependencies on that tree's conversations and
  orchestrations, its retained job inputs, the revisions they name (through slice 1) and the
  authority snapshot.
- **Invalidation.** A new conversation, orchestration or job input invalidates only the verdicts for
  its own tree. Agent steps in one tree no longer touch any other tree or account.
- **Fill.** The tree's edges and inputs for that root, one indexed read through a FETCH slot,
  coalesced across accounts asking about the same tree.

This replaces the per-account deny set whose reload ended the PROD job.

## Capacity and waits

- **One deadline.** A request's budget starts when it reaches the cache, and both the slot wait and
  the reload wait draw on it. Today these are two separate 5 s timers
  (`plowshare.read-scheduling.fetch-wait-millis` and `plowshare.projection-cache.timeout`). The
  target budget is 2 s.
- **Inline loading.** The elected loader runs on the requester's own thread and connection
  (`BoundedSnapshotCache.readInline`). A loader whose deadline passes finishes its load, which
  then serves the next request, and its own caller is told the read is busy. Waiting followers stop
  at the deadline. Background refresh is out of scope: it would need its own executor and
  connections.
- **Slots.** FETCH slots stay per cache. Item invalidation makes reloads small and rare, so the
  per-tenant fill limits proposed in unmerged commit `f0309298` are not needed.
- **At the deadline.** The requester receives `ReadUnavailableException` ("busy, retry"). The agent
  job runtime treats that as transient for side-effect-free checks, such as its per-step log check,
  and retries them a bounded number of times. Checks inside a callback that may hold locks keep
  refusing without retry (`JdbcInformationLogVisibility.read`).
- **Listener not ready.** Unchanged: the cache falls back to its SQL path, as now. Removing that
  fallback waits until the SQL paths themselves are deleted.

## Correctness

Each per-account view mirrors its SQL rules in Java and adds a differential database test that
compares the Java view with the SQL function, as `AuthoritySnapshotDifferentialDatabaseTest` does
for authority. For readability, fixtures are generated to cover every selection arm, contained and
ordinary actors, ceiling on and off, the closure cap at 1,024 and 1,025 nodes, cycles, pinned
revisions and heads, NULL policies, the UUID guard on contained actors, and service tokens. The SQL
functions remain the oracle and the path for own-write transactions.

Item invalidation adds its own tests: a write to one conversation tree leaves another tree's entries
in place and serves them as hits; a fan-out above the bound falls back to domain invalidation; and a
two-process test shows a write on one process invalidating the item on the other.

## Repository rules

- Shared data reaches the cache through typed repository interfaces and DTOs; nothing outside a
  repository receives a `JdbcTemplate`.
- About 21 of the 24 allowed projection caches (`plowshare.projection-cache.max-projections`) are in
  use, so each slice states the projections it adds or removes and raises the limit if needed.
- Each slice names its migrations and the `full-db` tests its change requires.

## Order

1. **Slice 0:** the item version store and keyed notifications, the `contained_principals` trigger,
   the single deadline, the job retry rule, and a two-process invalidation test.
2. **Slice 1:** targeted readability from shared data, behind generated differential tests.
3. **Slice 2:** log visibility per log tree.
4. **Later:** catalogue lists, facets and readiness on slice 1's view; PROC queues and usage
   reports reconsidered on item invalidation before any redesign.

## Superseded work

- Unmerged commit `f0309298` (per-tenant FETCH limits and a 2 s cap) is dropped.
- #245's REPORT-seat nesting stays on `main` until PROC and usage are reconsidered. Reverting it now
  would reopen the tenant-fairness problem it addresses.

## Review changes

The first draft proposed one global cache of tenant-independent rows. Review against the code found:

- A global entry of all conversation and job-input edges would reload on almost every agent step,
  because invalidation is domain-wide. Item invalidation is now the foundation.
- Read-your-writes cannot come from synchronous self-invalidation: there is no after-commit hook, and
  writes arrive through triggers, cascades and many repositories. It comes from the item version
  check.
- The current timers are 5 s and 5 s, and `ReadAdmission.fetch(cache, tenant, …)` exists only in
  unmerged work.
- Waiting for a not-ready listener would turn a working fallback into an outage.
- Usage aggregates per account and day were tenant data in the "global" layer.

## Open questions

- **Item granularity.** Which address each domain uses: per row, per tree or per scope.
- **Version store growth.** Whether counters for deleted items are pruned, and how a pruned item
  that reappears is versioned.
- **Notification volume.** The fan-out bound and batching rule per domain.
- **Several server processes.** Which projections may lag up to the staleness bound across
  processes, and which must stay versioned.

# Item-level cache invalidation and shared read data

Status: slices 0, 1 and 2 implemented (see [Slice 0 status](#slice-0-status),
[Slice 1 status](#slice-1-status) and [Slice 2 status](#slice-2-status)); later slices proposed. It
extends the [query and cache strategy](query-efficiency-and-cache-design.md) and the
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

### Slice 0 status

Implemented: migration `V257__item_projection_versions.sql`, `ProjectionItemVersions`,
`ItemProjectionCache` (`ProjectionCaches.createItemScoped`), keyed delivery on
`plowshare_item_changes`, the `contained_principals` AUTHORITY trigger, the single read deadline
(`plowshare.projection-cache.timeout`, now 2 s) and the job runtime's bounded retry of its per-step
log check (`BusyReadRetry`). No existing projection was moved to item scope.

- **Domain.** Only READABILITY is item-tracked. Its domain counter, writer record and domain hint
  are unchanged, so domain-scoped projections behave as before.
- **Addresses.** `conversation:<root>` (each root of the changed log's ancestry under
  `information_log_readable`'s edges, old and new endpoints), `revision:<id>`, `resource:<id>`,
  `scope:<id>`, and `*` for the fallback. Platform and project navigation policies and
  `information_document_policies` have no address and take the fallback.
- **Bounds.** More than 1,024 rows in a statement, more than 256 logs in an ancestry walk, more than
  64 items in a transaction, or `TRUNCATE` bump `*`.
- **Counters move at commit.** Triggers accumulate items in a transaction-local setting; a deferred
  constraint trigger bumps them in one sorted statement just before commit, so item rows are never
  held for a transaction's lifetime and flushes cannot deadlock on them. Values come from one
  sequence and are never reused. Because the rows are transactional, a versioned check needs no
  pending-transaction evidence.
- **Items are declared before the load**, as `SnapshotCache.Request` requires. Slice 1 resolves
  this without recording discovered items: it caches one fact per item, whose item is known before
  its load, and walks the closure over those facts.
- **Not done.** Counter rows are never pruned (open question above); nothing yet sizes the
  trusted map per projection.

## Slice 1: targeted readability from shared data

[ADR 0068](decisions/0068-document-level-readability.md) later decides readability per document,
with cross-boundary content enforced at write time; it replaces this slice's per-revision closure
walk once implemented.

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

### Slice 1 status

Implemented: `InformationCatalogueRepository.readable` answers from shared facts and the authority
snapshot (`CachedKnowledgeReadability`, `KnowledgeReadability`), and runs its SQL statement only
when that declines. No migration; no catalogue list, facet, readiness or log-visibility read moved.

- **Facts.** One item-scoped, versioned projection, `knowledge-facts-v1` (22 of the 24 allowed
  projections are now in use, so the limit is unchanged). Each fact depends on exactly its own item
  and is loaded with one indexed statement (`JdbcKnowledgeFactRepository`) through the projection's
  FETCH slots:
  - `revision:<id>`: resource, availability, whether `information_revision_allowances` has the
    row, typed content and `explicit_only` (both immutable once the revision commits, V143/V144),
    inputs (`information_inputs`) and linked projects (`information_links`);
  - `resource:<id>`: home scope, root policy (audience, `private_only`, privacy principal), the
    navigation policy converted to a typed `ownerOnly` at the repository boundary (platform and
    project policies move `*`), the navigation head, and the reference obligations it is the
    source of;
  - absent and oversized rows (more than 1,024 edges of one kind) are cached as such.
- **Deviation: scopes.** Scope kind, builtin, account, project, container ancestry, project names,
  personal owners and contained principals come from the authority snapshot, not from `scope:<id>`
  facts: `authority_scopes`, `projects` and `contained_principals` move the AUTHORITY version, and
  V257's `scope:<id>` only tracks a resource's home scope, so a `scope:<id>` fact would not be
  invalidated by a scope change. The snapshot gained `authority_accounts.builtin`,
  `authority_scopes.container` and `contained_principals`, and Java mirrors of
  `knowledge_read_authority`, `information_global_read_member`, `contained_knowledge_actor`,
  `contained_scope_readable`, `contained_scope_ancestors` and `contained_scope_in_home`.
- **Consistency.** An evaluation reads its facts unconfirmed
  (`ItemProjectionCache.readUnconfirmed`: a stored fact costs no SQL, a missing one is captured
  and loaded) and then confirms every item it read with one statement
  (`ProjectionItemVersions.staleItems`). An empty result proves no fact changed after its load, so
  data-side writes (availability, quarantine, `private_only`, policies, inputs, links, heads) are
  seen as soon as they commit, on any process. A stale item is invalidated alone and the check
  falls back to SQL. The authority half keeps the snapshot's notification-trusted contract: a
  membership, grant, token or principal change may take up to the staleness bound (2 s) to apply.
  A warm check therefore issues one statement and no permission SQL.
- **Fallback to SQL.** A caller in a transaction that may hold its own writes (anything but none or
  a read-only READ COMMITTED one); no authority snapshot; the fact cache not serving (listener not
  ready, ineligible caller, refused load); an oversized fact; more facts than the projection's
  entry limit in one evaluation; or a failed confirmation. A closure past 1,024 nodes is answered
  `false` in Java, exactly as SQL does, when the walk fits the fact budget.
- **Rules.** All arms of the targeted check are mirrored; none is left on SQL. The targeted check
  binds no related resource, kind or heads filter and includes excluded revisions, so those
  `knowledge_readable_candidates` arms do not apply. `information_readable_input_selections` (V235)
  is log visibility (slice 2) and is not on this path. V256 added discovery routines only.
- **Evidence.** `KnowledgeReadabilityDifferentialDatabaseTest` compares Java with the SQL statement
  for 10 actors (users with and without roles, a server administrator, a service owner and its
  token principal, a contained principal, its upper-case spelling, an unknown UUID and handle) ×
  16 selections × 7 ceilings × typed view on and off (2,240 contexts), over 32–33 revisions, in 11
  fixture states (801,920 comparisons), plus closures of exactly 1,024 and 1,025 nodes and a
  41-revision mixed chain in four more states (1,440 comparisons).
  `KnowledgeReadabilityInvalidationDatabaseTest` covers item invalidation and the confirmation;
  `CachedKnowledgeReadabilityTest` covers each fallback with mocked repositories.

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

### Slice 2 status

Implemented: `InformationLogVisibility.readable` (the targeted check behind
`JdbcJobInformationRepository.logAllowed` and `EntryStore`'s transcript guard) answers from
per-tree facts, slice 1's knowledge facts and the authority snapshot (`CachedLogReadability`,
`LogReadability`), and runs `information_log_readable` only when that declines. Migration
`V258__log_tree_edge_addresses.sql`.

- **Deviation: the item is the tree, not the verdict.** The design named an item
  `log-readable(account, root)`. The cached value is instead the tree's facts with no account,
  `LogTreeFact` under `conversation:<root>` in one item-scoped, versioned projection,
  `log-tree-facts-v1` (23 of the 24 allowed projections are now in use). Verdicts are computed per
  check in Java, so accounts share one fill per tree and no per-account entry exists to invalidate.
- **Tree facts.** Every log reachable from the root down the four edges of V235's descendants CTE
  (`snapshot_cache_log_children`, V258, the inverse of V257's `snapshot_cache_log_parents`), each
  with all of its parents (inside the tree or not), its quarantine row and its retained inputs
  (`revision_id`, `scope`, `project_name`, `include_shared`; `owner_handle` is attribution and not
  read, since V235). One statement (`JdbcLogTreeFactRepository`) through the projection's FETCH
  slots; more than 1,024 logs, 8,192 inputs or a log id over 4,096 characters is cached as
  oversized and answered by SQL. V258 adds the index the caller edge's downward step needs.
- **Roots.** A log's ancestry lies in the trees of its roots, which are resolved with V257's own
  walk (`snapshot_cache_log_roots`) and remembered per log in a bounded map. The map is a hint: the
  evaluation walks the ancestry over the loaded trees and, when a visited log is in none of them,
  resolves once more and otherwise goes to SQL.
- **Correction to V257's addresses.** V257 walks a changed edge's endpoints after the change, so
  when an edge gives a former root a parent (an orchestration attaching an existing conductor to a
  caller or parent run, a conversation inserted under another), only the new roots were named and
  the old root's cached tree stayed confirmable. V258 also names the child endpoint of every new
  edge (`conversation:<child>`). Without it the differential test serves a stale `true` after such
  a join. `ItemProjectionVersionsDatabaseTest` now expects the new child's own item to move once.
- **Rules.** `information_log_readable` (V235; V240 only set `jit=off`; V256 added discovery
  routines only; V257 and V258 change triggers, not the rule), `information_readable_input_selections`
  (V235: its `selections` join and prune, `facts.readable` with general membership required,
  `general_readable` without V229's contained-home conjunct, the 1,024-node `accepted` bound and the
  selection arms, compared literally) on slice 1's facts (`KnowledgeReadability.retainedInputs`).
  Input attribution plays no part (V235 removed V186's owner equality). A NULL account reads no
  retained input; the entry point still sends a NULL account or log to SQL. Nothing is left on SQL
  for a rule reason.
- **Consistency.** As slice 1: trees and facts are read unconfirmed and every item one check read
  is confirmed with one `staleItems` statement; a stale item is invalidated alone and the check
  goes to SQL. Authority keeps the snapshot's notification-trusted 2 s bound, which the deny set's
  NOTIFIED projection also had.
- **Fallback to SQL.** A writing caller transaction; no authority snapshot; unaddressable roots
  (an ancestry over 256 logs, a cycle without a root, an id outside the item grammar) or more than
  16 of them; a cache that cannot serve; an oversized tree; a retained input the slice 1 rule cannot
  serve (oversized fact, spent fact budget); roots that still do not cover the log after a fresh
  resolution; a failed confirmation.
- **Collection reads keep the deny set.** `read(account, reader)` feeds `NOT IN (deny set)` filters
  over arbitrary pages of conversations, orchestrations and inbox items before ordering and LIMIT
  (`EntryStore`, `OrchestrationStore`, `JdbcInboxStore`), so the logs a read will touch are not
  known in advance; deriving the set from trees would load every tree. Those reads keep
  `information-log-visibility-v1` (per account, READABILITY and AUTHORITY, its fence after the
  consumer) unchanged. Targeted checks no longer load it.
- **Evidence.** `LogReadabilityDifferentialDatabaseTest` compares Java with
  `information_log_readable` for 11 accounts (including NULL) × 39–41 logs in 16 fixture states
  (6,941 comparisons), and the cached check, persisting across states with no notifications, served
  12,248 answers, all equal to SQL. `LogReadabilityInvalidationDatabaseTest` covers per-tree
  invalidation, the confirmation and writing transactions; `CachedLogReadabilityTest` covers each
  fallback with mocks.

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
- Each projection is one cache line; the number of lines is not limited. Memory is the only
  control: `resident-bytes` per line and `total-resident-bytes` across lines (see
  `ResidentBudget`). The earlier `max-projections` count cap was removed.
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

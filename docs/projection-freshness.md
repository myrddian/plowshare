# Projection freshness and invalidation

The server shares immutable repository DTOs across requests, feeds and sockets. A
notification is a hint; it never grants authority or proves a cached value current.
Repository SQL, live caller admission, principal/selection keys, source identity,
transaction eligibility and expiry/recovery fences remain mandatory.

## Transaction fence

V175 introduced allocation counters plus pending-transaction validation. Its
completed reader probe made tail writers visible, but every captured transaction
that subsequently committed retired the DTO. Unrelated read-only transactions,
including simultaneous cache probes, could therefore invalidate one another.

V205 records domain-specific writer evidence transactionally. V206 captures the
counter values and PostgreSQL snapshot in **one independent read-only statement**.
It records both snapshot xip and snapshot xmax, not just active transaction IDs.
Readers no longer allocate a transaction ID, write journal rows or need a second
probe statement. PostgreSQL may omit an assigned tail transaction from xip; the
inclusive future-XID boundary at xmax still covers that writer.

A stored token is current only when all of these hold:

1. Its database identity, selected counters and authority/lease window match.
2. Its pending identities and future boundary have not crossed the journal's
   retention floor; unknown pending transaction status fails closed.
3. The validation statement's committed journal rows contain no selected-domain
   writer with an explicitly pending XID or an XID at/above the future boundary.

A writer allocating after capture changes a counter. A writer allocating before
capture but committing late is covered by xip or xmax, and its journal record
commits atomically with its facts. It therefore cannot hide behind an unchanged
counter. A read-only commit has no writer row; an unrelated-domain commit has no
matching domain. Rollback removes writer evidence and transaction-local markers,
including at savepoints; sequence allocations remain conservatively visible.

Journal rows and the retention floor use the **same MVCC snapshot** during
validation. Do not combine absent snapshot-visible evidence with a later observed
commit status to conclude that a transaction was read-only. A commit after that
snapshot may linearize after the read; the next validation observes it. Always
validate the loader token stored with a DTO, never replace it with fresh evidence.
See PostgreSQL's [READ COMMITTED semantics](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED)
and [snapshot functions](https://www.postgresql.org/docs/16/functions-info.html#FUNCTIONS-PG-SNAPSHOT).

Each writer records newly touched domains once per top-level transaction. Normal
maintenance targets 8192 recent committed writer-domain records, runs at most once
per 64 new writer transactions, and removes at most 1024 rows per attempt. It
uses a nonblocking maintenance lock and skips locked writer rows. Concurrent or
long transactions can temporarily exceed the retention target; maintenance never
blocks another writer on the maintenance lock. Deletion and floor advancement
commit or roll back together. Old/uncertain evidence causes a direct authorized
read, never a stale allow decision. Read traffic does not grow this journal. Domain-first `(domain, xid)` index probes
separate pending identities from the future range; a warm authority/type check
must not scan unrelated accounting history merely because its token is old.

The migration first drains existing watched writers under table locks. V206
changes dependency vocabulary and capture semantics: **stop/restart all server
binaries together**, rather than leaving old DOCUMENTS-only metadata caches in a
mixed fleet. Clients remain wire compatible. Reconnect clears process-local
cache state and establishes the existing durable baseline before sharing resumes.

## Dependency ownership

| Projection | Relevant domains and clocks |
| --- | --- |
| PROC queue aggregates | QUEUES, PROGRESS, DOCUMENTS, SEARCH, TRANSCRIPTS, AUTHORITY; running lease expiry |
| Accounting reports | Existing OBSERVATIONS, TRANSCRIPTS, AUTHORITY |
| Type registry list/version | TYPES, AUTHORITY; current caller admission remains outside |
| Attached type contract | TYPES, DOCUMENTS, READABILITY, AUTHORITY |
| Extracted text DTO | EXTRACTIONS, READABILITY, AUTHORITY |
| Catalogue metadata/list | DOCUMENTS, PROGRESS, READABILITY, AUTHORITY |
| Catalogue facets/navigation | DOCUMENTS, READABILITY, AUTHORITY |
| Extraction readiness | DOCUMENTS, PROGRESS, READABILITY, AUTHORITY |

Budget spending is progress and affects queue eligibility; it does not change
text, types, facets or navigation. Lease deadlines affect queue counts and their
clock window. Attempts, errors and timestamps affect progress. Stage identity,
state and fingerprint can affect search eligibility; generated metadata readiness
also affects visible facets, so those changes retain their relevant invalidation.
New extraction columns/dependencies require explicit review of the text DTO fence.

`information.invalidated` optionally carries `progressOnly: true`. It contains no
resource, project, account or authority facts. Legacy clients conservatively use a
full refresh. Updated Desktop clients coalesce progress reads without hiding
content, clearing registry/navigation state or cancelling useful reads. Content,
READABILITY, AUTHORITY and recovery changes retain immediate protected-surface
fencing. A full hint atomically upgrades a queued progress hint and cannot be
downgraded by subsequent progress. Every resulting read still passes server
admission and authorization; correctness does not depend on client cooperation.

## Semantic no-ops and manifests

Relay reader registration retains its atomic ownership-checked conflict clause
and RETURNING result. Equal rows no longer advance RELAY or wake its consumers.
Schedule refusal/source upserts similarly distinguish a touched row from changed
facts. A no-op delivery renewal returns its already locked, live-fenced snapshot;
expired, stale or foreign owners still fail before this path.

Workspace publication first wins and locks its generation/session/mutation fence,
then applies a typed manifest as one set-based SQL statement. It deletes absent
keys, inserts new sources and updates changed facts. Unchanged rows retain their
physical identity. Workspace checked_at records the latest scan; source observed_at
changes when accepted source facts change. Manifest failure rolls back status and
all source changes together. Revocation still clears unavailable sources.

## Regression coverage

Focused PostgreSQL tests exercise tail and out-of-order commits, rollback,
savepoints, read-only concurrency, pruning, expiry, historical/dirty callers,
accounting/queue isolation, generated facets, text changes, atomic Relay ownership,
renewal, schedule no-ops and generation-fenced manifest publication. Mocked and
client tests cover cache sharing, overload refusal, hint priority/coalescing,
malformed progress flags, logout/reconnect and cancellation of stale callbacks.
Live verification additionally records CPU, version/notification rates and mixed
segmented/legacy multi-socket read outcomes while monitoring server logs. Activity
samples are query shapes, not exact query counts.

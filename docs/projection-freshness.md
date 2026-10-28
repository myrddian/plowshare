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
| Attached type contract | TYPES, DOCUMENTS, KNOWLEDGE_ACCESS, AUTHORITY |
| Extracted text DTO | EXTRACTIONS, KNOWLEDGE_ACCESS, AUTHORITY |
| Catalogue metadata/list | DOCUMENTS, PROGRESS, KNOWLEDGE_ACCESS, AUTHORITY |
| Catalogue facets/navigation | DOCUMENTS, KNOWLEDGE_ACCESS, AUTHORITY |
| Extraction readiness | DOCUMENTS, PROGRESS, KNOWLEDGE_ACCESS, AUTHORITY |

Budget spending is progress and affects queue eligibility; it does not change
text, types, facets or navigation. Lease deadlines affect queue counts and their
clock window. Attempts, errors and timestamps affect progress. Stage identity,
state and fingerprint can affect search eligibility; generated metadata readiness
also affects visible facets, so those changes retain their relevant invalidation.
New extraction columns/dependencies require explicit review of the text DTO fence.

V207 separates catalogue access facts (`KNOWLEDGE_ACCESS`) from log `READABILITY`.
V208 separates membership changes from possible revocation (`KNOWLEDGE_REVOCATIONS`).
Every relevant source change still advances the existing access/cache fences. New
resources, revisions belonging to those resources, and their initial policies and
inputs cannot revoke a previously delivered graph. Fixed transaction-local birth
markers cover that case without looking up audiences or permission graphs. They
retain at most 128 identities per relation, roll back at savepoints and reset at
commit. Evicted/unknown evidence is conservative and produces a hard hint. A new
head of an existing resource remains hard: it can redirect unpinned dependencies
of a previously visible report. Updates/deletes of existing policy or dependency
facts, TRUNCATE, authority changes and listener recovery remain hard. All writers
retain transactional journal evidence and pending/future-XID checks; the markers
never authorize a read or replace freshness evidence.

`information.invalidated` optionally carries `refreshOnly: true` for nonrevoking
membership/content changes or `progressOnly: true` for processing progress. It
contains no resource, project, account or authority facts. Soft hints share one
global one-second deadline and do not hide retained content or cancel useful
reads. Hard hints immediately hide/fence protected surfaces, dominate queued soft
hints and reject late replies. Identity changes always fence. Every resulting
read still passes server admission and authorization; correctness does not depend
on client cooperation. Legacy peers conservatively use the hard form.

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

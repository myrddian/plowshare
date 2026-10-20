# Decision 0033: Project typed instance metadata with document pages

Status: separately authorized platform capability, 21 October 2026.

## General platform need

Schema-defined document tables, inventories, review queues and agent retrieval
need current instance fields without downloading source bodies or repeating a
schema definition per row. The existing document list returns only discovery
metadata. Following it with content.get for every row creates N+1 SDK reads and
also loads processing/reuse state that these consumers do not use.

The user explicitly authorized this generic platform change separately from
the external Excalibur Application. Core contains no ticket types, statuses,
epics, board limits or workflow decisions.

## Alternatives and decision

SDK-only concurrency reduces round-trip waiting but preserves N per-item
requests and repeated authorization/projection work. Revision-keyed application
caches help warm refreshes but require bounded storage and fresh disclosure
guards; cold loads retain the gap. Interchange export unnecessarily transfers
bodies and definitions. A batch wrapper that loops over content.get moves the
same N+1 work into core.

Add optional `projection:"metadata-v1"` to `information.documents.list`, requiring
the existing `view:"content-types-v1"`. Existing selections, type/tag filters,
traversal, ordering and limit/offset semantics remain unchanged. The response
echoes `projection:"metadata-v1"`, including for an empty page. Each typed row
may carry `contentMetadata` with compact `type` identity, `metadataHash` and the
exact immutable metadata object. The type identity contains type/version UUIDs,
ordinal, slug, label and definition hash. Schema definitions, presentation,
source bodies and processing state remain separate public reads.

One specialist-repository SQL statement materializes the currently authorized
head page before joining instance bindings and their type identities. It reuses
the existing live readable-selection routine, dependency closure, retained
execution boundary and scope/facet predicates. No per-row content.get,
repository callback, schema validation query or cached access decision occurs.
Persisted metadata is decoded to the existing immutable SchemaValue contract,
checked against its bounded document profile and verified against its digest.
The schema was validated at binding admission; this read does not refetch it.

## Authorization, bounds and consistency

Account/project admission still occurs on every request. The statement uses the
exact highest committed revision, never an older readable or typed revision.
Non-readable roots and dependency-restricted documents are excluded before
paging. Redacted bindings are excluded from the join. A missing contentMetadata
deliberately does not distinguish an untyped revision from redacted typing.
The projection grants no schema-assignment, mutation or body-read authority.
Model-facing information_read retains each returned revision as consumed input
through its existing navigation path.

The page remains at most 100 rows. Each metadata instance retains its existing
16-KiB compact JSON bound. Aggregate PostgreSQL JSONB text for bindings in one
page is capped at 512 KiB, conservatively including JSONB formatting overhead.
SQL suppresses oversized metadata text before it reaches JDBC; the operation
fails explicitly and directs the caller to reduce limit. It never truncates
fields, silently drops rows or advances past omitted metadata. One page uses
one database statement/snapshot; separate pages can observe concurrent edits
under the existing list semantics.

Projected pages initially use direct reads and do not enter the existing shared
header-page cache. This avoids introducing a second metadata cache, weight
accounting or mixed projection keys. Existing default/header cache behavior is
unchanged. Existing document/authority fences already cover content tables,
but any future projected-page cache must independently prove its full key,
dependency, memory and live-admission contract.

## Compatibility and verification

Omitting projection preserves the old response shape: no marker or metadata
fields. Legacy Java constructors remain available; shared TypeScript and native
SDK contracts gain optional fields through their owning generators. Explicit
null/unknown projections and missing supporting view are rejected. Old servers
may reject the new option; consumers must require support rather than silently
fall back to per-item hydration. This is a read-only capability with no request
receipt, background execution, lifecycle transition or migration.

Mocked tests cover request validation, old wire shape, immutable/hash-checked
DTOs, one-statement budgets and service admission/revocation. Focused PostgreSQL
tests verify real mapping/ordering, absent/redacted bindings, current heads,
dependency and execution ceilings, membership revocation and aggregate overflow.
Shared native boundary fixtures cover the option, old/new responses, nullable
instance fields, invalid identities/digests and instance bounds. Query-count
fixtures establish statement/request budgets, not production latency.

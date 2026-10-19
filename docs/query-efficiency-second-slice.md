# Document, search and schedule snapshot reuse

Status: implemented locally on 2026-10-20; deployment is separate. This follows the
[first slice](query-efficiency-first-slice.md) and the
[query and cache strategy](query-efficiency-and-cache-design.md).
The before/after source comparison uses the first-slice commit
`8acbf8c51242174214f00855c08a1e6b418786a5` as its baseline. The
[owner analysis](query-efficiency-analysis/discovery.md) retains the earlier
composition and commit provenance; this work does not attribute the inefficiency
to the latest release.

## Document reads

Readability and the requested document projection now share one repository read.
Status reuses authorized metadata for ownership and classification; source windows
reuse one authorized extraction with its classification. Neither path needs a
separate full-content row just to inspect metadata. Configuration fingerprints
are captured once into an immutable map rather than reconstructed for each
visible processing step. Hidden status and empty step lists need no configuration
read.

Code-passage lookup uses an internal locator before authorizing the returned
projection. A missing locator still requires a current readability check before
returning unmatched, and does not load extracted text. A present locator uses the
authorized extraction query before returning any content. Java UTF-16 offsets
remain unchanged; PostgreSQL substring offsets are not substituted for them.

Current selection admission and the existing unreadable-owner status fallback
remain authoritative. Write locks, command admission and worker snapshots retain
their separate contracts. Reusing a DTO inside one operation does not grant a
later read or control action.

## Search

The empty-corpus check before embedding avoids a paid call that cannot produce
results. After provider work, current disclosure must validate the caller and
captured embedding configuration before assembling results and coverage.
The admitted dual-embedding path no longer recursively repeats the coverage gate
between those boundaries. A prepared-vector path reads the final coverage and
candidate projections inside disclosure after provider work. Final empty
coverage skips ranking queries. Legacy nondual reads retain their documented
preflight coverage behavior, and admitted nondual reads retain final coverage
refresh. Provider work remains outside database locks.

## Schedule reconciliation

One complete source scan establishes removal candidates. Each file operation
then reads its own persisted projection through the existing indexed identity,
instead of decoding the source's entire collection again. The raw definition
and operational pause override share one snapshot, so an unchanged file returns
its effective definition without another read. Changed upserts return their
persisted projection through `RETURNING`. Managed-file inspection uses one
owner-bound join, pause uses a keyed snapshot under its source lock, and save
uses a fresh keyed read after reconciliation.

Reconciliation keeps each file's source lock and transaction. It must not carry
an unlocked pre-scan DTO into a later write as proof that the definition or
authority is unchanged. Incomplete or unauthorized scans still cannot establish
deletion, and rollback must preserve the durable schedule/trigger/file unit.

## Linking DTOs to the later cache

The repository owns construction and validation of each immutable snapshot:
[`AuthorizedMetadata` and `AuthorizedText`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogueRepository.java),
[`SearchSnapshot`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentStore.java)
and [`FileSnapshot`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ScheduleDefinitionStore.java).
Callers carry it through the operation without reducing it to an ID and fetching
it again. Future cache loaders can use the same projection contracts and must
add the database/server namespace at composition time.

| Projection | Identity and existing version evidence | Dependencies and shared-admission gate |
| --- | --- | --- |
| Authorized document metadata | Revision/resource identity and exact authenticated selection; processing generation has its existing processing meaning | Resource metadata, content-type binding, policy, reports and processing state need complete change generations and all-writer invalidation; operation-local for now |
| Authorized extraction | Revision/resource identity, retained content and extraction fingerprints, classification and processing generation | Current readability, deletion/redaction and extraction replacement must be fenced; a content identity never grants disclosure |
| Search result/coverage | Exact selection, corpus/read boundary and captured embedding configuration; returned document/paragraph/chunk identities | Corpus membership, source/vector changes and current policy need durable generations and invalidation before shared result admission |
| Schedule file | Source/account/project/file identity with raw definition and pause override | The definition's schema version is not a mutation version; file/source/control changes need a monotonic generation and complete writer coverage before shared admission |

These contracts prepare typed loader inputs and outputs; this slice does not
install a shared cache. An LRU entry's residency or a healthy notification
listener cannot replace current authority. Mutable snapshots remain
operation-local until their complete version/invalidation contract is available.
The future cache must also bound DTO weight, keys, dependencies, pending loads
and waiters, and must reject stale fills after an invalidation.

## Verification

The 76 focused mocked tests, `format` and `formatCheck` passed. These budgets describe source
paths verified by mock interactions; they are not latency measurements.
The complete non-database `check --continue --max-workers=1` passed, including
6,006 server tests and the required client, Javadoc and contract inventory checks.

| Operation | Before | After |
| --- | --- | --- |
| Authorized text / ordinary passage match | 2 catalogue statements | 1 |
| Bounded source window | 3 catalogue statements | 1 |
| Located code passage | 5 catalogue statements | 2; missing locators remain 2 without extraction |
| Visible status | `7 + d + O + 2R` catalogue statements | `5 + O + 2R` |
| Status configuration for S visible steps | S supplier invocations | 1 for a nonempty visible status, otherwise 0 |
| Admitted dual-embedding search | 3 coverage reads | 2: pre-provider and final disclosure |
| Unchanged N-file reconciliation | `1 + 4N` statements; `N + 2N²` file rows decoded | `1 + 3N` statements; `2N` file rows decoded |
| Managed-file lookup | Source lookup plus whole-source file read | 1 owner-bound joined row |

For status, d counts configured derive-step row reloads, O is one for owner event
history and zero otherwise, and R is one for a readable report and zero otherwise.
The status budget includes the two statements inside progress, but excludes
selection-admission SQL. Capturing configuration once also removes repeated
embedding-profile and filesystem work in the configured supplier.

The schedule reconciliation formula assumes N existing unchanged active files
with valid triggers, and excludes additional authority validation. It includes
the initial removal scan, per-file source lock and trigger lookup. A public sync
still reads the final collection to return its current result. Changed files
retain the required runtime schedule/trigger mutations.

Fourteen focused PostgreSQL tests passed under `-PfullDb`: three document-read
projection/authorization tests and eleven schedule persistence tests. They cover
typed/explicit document visibility, scope boundaries, revocation, nullable
extraction, same-generation metadata changes, persisted pause overrides, keyed
mapping, `RETURNING`, restart, ownership and rollback. No schema migration was
needed. These tests do not establish deployed latency.

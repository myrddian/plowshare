# Excalibur query-efficiency review

Reviewed against public main `2cf4db4c2d72e9f99e43e343a38ff603278e8329`
and the repository's [query rules](../../docs/coding-standards.md#query-efficiency-and-reuse).
All application changes stay in this directory. The generic platform capability
is a separate change described by [ADR 0033](../../docs/decisions/0033-paged-document-metadata-projection.md).

## Public SDK projection

Excalibur requests `projection:"metadata-v1"` and `view:"content-types-v1"` on
`information.documents.list`, with its configured project, `includeGlobal:false`,
content-type UUID and optional AND tags. Each page contains current resource and
revision identities, owner, tags and compact `contentMetadata`. The application
checks the page's projection marker, scope, identities, pagination, type UUID,
slug and complete ticket/epic field contract before returning documents.
Extraction title never substitutes for the schema's title.

Discovery no longer calls `information.content.get` for every entry. It retains
no application field cache or shared fill tasks: every refresh receives current
fields and authority from the public projection. Old servers or damaged replies
fail explicitly; there is no implicit per-item hydration or HTTP fallback.
The matching SDK and a server implementing metadata-v1 must be installed before
running this version of Excalibur.

The platform page joins only authorized selected heads to non-redacted bindings.
It transfers no bodies or schema definitions. Its 100-row and 512-KiB aggregate
metadata limits are generic; Excalibur separately enforces its own smaller
100-ticket/20-epic board and metadata contracts. A full 100-ticket selection
still probes one extra page to detect overflow; epics request up to 21 entries
and refuse overflow. Duplicate resources and invalid page boundaries also fail.

## Request shapes and waste removed

| Interaction | Before | Current behavior |
| --- | --- | --- |
| Board discovery | P list pages + N full content bindings | P projected pages; no per-item reads |
| Open details from the board | head + binding reads | reuse visible metadata; zero SDK calls |
| Explicit detail Reload | head + binding | fresh head + binding |
| Read original Markdown for editing | head + binding + readable export | head + readable export, including its typed sidecar binding |
| Replace topic tags | ticket head/binding fetched twice | one fresh ticket snapshot, preserving epic tags |
| Validate K selected epics | K heads + K bindings | one complete projected epic page |
| Replace epic membership | ticket fetched twice after epic checks | one fresh ticket snapshot after epic validation |
| Confirmed create/edit/status/tag/epic save | reload the whole board | read only the saved document; patch validated visible snapshot |
| CLI list/show | unused capabilities before actual reads | actual authorized reads only |
| Write readiness, health, watchdog, explicit check | typed and unused navigation capabilities | one typed-capability read |
| Concurrent identical browser GETs | independent requests | share the pending request; discard completed entry |

One metadata save followed by a current document refresh takes **6 SDK calls**,
independent of board size: typed-write readiness, head, binding, revision
mutation, current head, current binding. Previously it took 67 on a
50-ticket/10-epic board or 128 on a 100-ticket/20-epic board. Independent health
probes are excluded. Receipts contain identities rather than actual fields/tags,
so the post-write read is deliberate current-state evidence. A later external
edit may already be visible; submitted intent or a historical recovery receipt
cannot replace that read.

## Browser consistency and lifecycle

The browser shares at most 16 pending GETs and keeps no completed-response cache.
Confirmed writes abort pending board and same-resource reads before the narrow
replacement read. Selection identities and generations reject superseded
responses, including closing and reopening the same resource. Sign-in/sign-out
invalidate pending reads and clear visible workspace state. Aborts never apply
to mutations. Initial board loading must finish before a new save can begin.

Board metadata already visible can populate a detail form. Writes, original body
reads and explicit Reload still use current server authorization. Dirty forms
retain their displayed revision and must resolve conflicts explicitly. The
native tag control still lacks an atomic expected-head CAS, so the existing
single-maintainer limitation remains.

Explicit Refresh and receipt recovery fetch current collection state. The
30-second watchdog and health requests deliberately recheck upstream readiness;
there is no public durable capability-change subscription to replace them.
The watchdog alone owns serve's startup probe. Unknown mutation outcomes remain
in the private journal for explicit recovery; no write is auto-replayed.

## Verification and measurement limits

`tests/test_queries.py` asserts cold/repeated request budgets, fresh fields,
projection/type/scope fences, single-read tag actions and batched epic validation.
`tests/test_socket.py` measures generated public SDK traffic against a disposable
ordered WebSocket fixture. Tests using ordered dispatch are fixture-specific;
runtime connection negotiation remains the SDK default.

| Tickets + epics | Previous unprojected calls | Projected calls, cold or repeated | Projected UTF-8 response bytes | Rows each pass |
| --- | ---: | ---: | ---: | ---: |
| 50 + 10 | 62 | 2 | 45,703 | 60 |
| 100 + 20 | 123 | 3 | 91,181 | 120 |

Bytes include JSON response envelopes and compact type identities; they exclude
socket framing, compression, negotiation and bodies. Fixtures are synthetic.
Each refresh transfers typed fields again rather than retaining a field cache;
this costs more bytes than a header-only warm-cache refresh, but removes cache
storage and consistency complexity. Full schema definitions are not repeated.
These counts establish SDK request/row/byte budgets, not production database or
network latency. Queue, rendering and production database timings are unmeasured.

The separate platform tests verify one repository statement for 50 typed
instances with real PostgreSQL ordering/mapping, scope/dependency restrictions,
redaction, exact heads and oversized-page refusal, including a read-only EXPLAIN.
The app gate uses no database. Node tests cover GET coalescing, failure,
admission/cancellation/session bounds and validated snapshot replacement. The
local browser fixture verified zero detail-opening reads and one targeted GET
after each metadata, topic and epic-membership save. Deployment acceptance
against an updated server remains separate from these source/fixture checks.

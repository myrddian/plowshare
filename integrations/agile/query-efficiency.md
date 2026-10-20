# Excalibur query-efficiency review

Reviewed against public main `2cf4db4c2d72e9f99e43e343a38ff603278e8329`
and the repository's [query rules](../../docs/coding-standards.md#query-efficiency-and-reuse).
All changes belong to this external Application. No core runtime, database,
operation contract or SDK implementation changes are required by these fixes.

## Request shapes and waste removed

`information.documents.list` performs bounded current-head discovery in the
configured project with `includeGlobal:false`, content-type UUID and optional
AND tags. It returns resource, revision, owner, tags and extraction title. It
does **not** return typed metadata. Excalibur must obtain fields from
`information.content.get(revision)` on the first encounter with a revision.
Extraction title is not a substitute for the schema's title.

| Interaction | Before | After |
| --- | --- | --- |
| Open details from the board | head + binding reads | reuse visible metadata; zero SDK calls |
| Explicit detail Reload | head + binding | unchanged: fresh head + binding |
| Read original Markdown for editing | head + binding + readable export | head + readable export, including its typed sidecar binding |
| Replace topic tags | ticket head/binding fetched twice | one fresh ticket snapshot, preserving epic tags |
| Validate K selected epics | K head reads + K bindings | one bounded epic discovery; hydrate only missing selected revision fields |
| Replace epic membership | ticket fetched twice after epic checks | one fresh ticket snapshot after epic validation |
| Confirmed create/edit/status/tag/epic save | reload every board field | fetch only the saved document; patch validated visible snapshot |
| CLI list/show | two unused capability probes before the actual reads | actual authorized read operations only |
| Write readiness, health, watchdog, explicit check | typed capabilities plus unused navigation capabilities | one typed-capability read |
| Concurrent identical browser GETs | independent requests | share pending request; discard completed response entry |

The exact-full 100-ticket selection still probes a second page with limit 1 to
detect overflow. Epics use a limit of 21 to enforce the 20-epic board bound.
Membership validation uses that same complete bounded epic selection; it fails
explicitly if the selection exceeds capacity or a requested epic is absent.

One metadata save followed by a current document refresh now takes **6 SDK
calls**, independent of board size: typed-write readiness, head, binding,
revision mutation, current head, current binding. Previously it took 67 calls
on a 50-ticket/10-epic board and 128 on a 100-ticket/20-epic board, including
the unconditional collection refresh. Independent health probes are excluded.
The mutation receipt carries identities, not committed fields/tags, so the
narrow post-write read is required. It can observe a later external edit;
submitted intent or a historical recovery receipt cannot replace that evidence.

## Immutable field reuse and authorization

The per-`SdkBoardPort` store retains only frozen `TicketFields`/`EpicFields` DTOs.
Its implicit namespace is the SDK connection identity and immutable origin,
project and schema configuration; the explicit key is `(kind, resource, revision)`.
It retains no bodies, type definitions, tags, owners, session state or access
decisions. Tags and owner always come from the new discovery page.

A hit is usable **only after a fresh successful `documents.list`** with the
configured content-type filter and project fence. The current server's
`InformationSelectionSql`/`JdbcInformationNavigationRepository` enforce current
readability; `InformationFacetSql`'s content-type predicate excludes redacted
bindings. `JdbcInformationContentRepository` owns immutable revision bindings.
Both guarantees are required: if the public contract changes, reuse must be
reassessed. Cached resources absent from the fresh selection are never served.
Foreign scope, duplicate resources and invalid page boundaries fail before
hydration. Generic `documents.get` does not establish the non-redacted typed
filter, so direct get **always** fetches its current binding, including on a
cache hit. Original Markdown is independently authorized and hash/identity/type
validated from the public readable projection.

Admission is limited to **120 entries and 1 MiB of accounted weight**, with at
most one retained revision per resource. Each entry is charged 4096 bytes
plus four times the UTF-8 lengths of key and field strings. This conservative
DTO budget is not an RSS measurement. Oversized entries are returned to the
authorized caller but not retained. OrderedDict lookup, recency changes and
individual eviction are expected O(1); removing K entries costs O(K).
New revisions miss; unchanged revisions need no TTL because fields are immutable.
Revocation/redaction is guarded by fresh filtered discovery, never by TTL.
Admitting another revision evicts the previous one for that resource through an
O(1) secondary index. This avoids a single edit churning an entire full board's
LRU on its next sequential refresh. Other resources age out under both limits.
No invalidation notification is needed.

At most **128 distinct listed-field fills** may be pending. Concurrent callers
share only the same revision's field fill, while each still obtains its own
authorized discovery pages. A cancelled waiter cannot cancel another's fill.
Failed loads leave no reusable result and do not retry themselves. A new SDK
connection constructs a new port and empty store; process restart also clears it.
The CLI starts a new connection per invocation, so its lists remain cold.
The cache is an optimization of this typed SDK boundary, not a source of truth.

The browser shares at most **16 pending GETs**, with no completed-response cache.
Confirmed writes abort pending board and same-resource reads before the narrow
replacement read. Selection identity/generation checks reject superseded results,
including closing and reopening the same resource. Sign-in/sign-out invalidate
pending reads and clear the visible workspace. Aborts never apply to mutations.
Board metadata already visible can populate a detail form; every write, body
read and explicit Reload still uses current server authorization. Dirty forms
retain their displayed revision and must resolve conflicts explicitly.

## Verification and measurement limits

`tests/test_queries.py` asserts SDK request sequences, exact-revision reuse,
fresh tags/owners, refusal/scope fences, selected-epic batching, cold/warm bounds,
LRU/byte admission, connection separation and concurrent-fill cancellation.
`tests/test_socket.py` measures generated public SDK traffic against a disposable
ordered WebSocket fixture. Response bytes below count UTF-8 JSON envelopes;
they exclude WebSocket/TCP framing, compression, dispatch negotiation and bodies.
Each pass reads the stated number of current-document rows; warm passes read
zero metadata bindings. Cold passes read one binding per row. Fixture schemas
are deliberately small and do not represent production schema sizes.

| Tickets + epics | Cold calls | Unchanged calls | Cold response bytes | Unchanged response bytes | Discovery rows each pass |
| --- | ---: | ---: | ---: | ---: | ---: |
| 50 + 10 | 62 | 2 | 69,049 | 18,679 | 60 |
| 100 + 20 | 123 | 3 | 137,900 | 37,160 | 120 |

These are transport request/row/byte budgets, **not PostgreSQL query counts or
latency measurements**. No database tests or `EXPLAIN` were required: no query
implementation changed. Queue, database execution, network latency and rendering
timings have not been measured. The request-count tests are the current cache
observability mechanism; no protected content or per-document cache keys are
logged by production code.

The disposable browser fixture verified one initial board GET, zero GETs when
opening a listed ticket, and one targeted GET after each metadata, topic and
epic-membership save. Node tests cover pending-read coalescing, rejection,
bounded admission, cancellation/session invalidation and safe snapshot replacement.
Explicit Refresh/recovery still fetch current collection state. The 30-second
watchdog and health requests deliberately recheck upstream readiness; Excalibur
has no public durable capability-change subscription to replace them. The
watchdog alone owns the serve command's startup probe. Fixtures predating SDK
commit `e91181ef` now explicitly choose ordered dispatch; runtime connection
negotiation remains the SDK default.

## Remaining public SDK gap

Cold board discovery still has **P + N SDK reads**: P discovery pages and N
typed-binding projections. Current public operations offer no batch projection
of authorized typed fields for selected current documents. Neither revision
history listing nor extraction text supplies those fields. Exporting all source
documents would transfer unnecessary bodies and schemas. A future general public
projection could return the exact resource/revision, typed fields and current
authority/redaction result in bounded pages. That is a separate platform design
decision. Excalibur does not add a private endpoint, import server internals or
query database tables to hide the gap.

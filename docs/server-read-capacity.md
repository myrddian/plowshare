# Server read work and capacity

The server owns policy and disclosure. PostgreSQL owns durable facts, constraints and atomic
state transitions. SQL predicates are one implementation of server policy, not a requirement
that every internal fact fetched from PostgreSQL already be visible to the end user.

A feed, socket or request is not a distinct budget owner. The same authenticated principal
shares capacity across all of its connections. Server limits do not depend on client polling,
retry, cancellation or connection behavior.

## Execution and connection backpressure

`plowshare.read-capacity` adds logical work budgets before database acquisition. The existing
pool-admission throttle remains unchanged: it applies connection backpressure to JDBC work.
Hikari and PostgreSQL connection limits remain independent bounds. Configure instance budgets
against the total database capacity when running multiple server processes.

| Property | Default | Meaning |
| --- | ---: | --- |
| `interactive` / `interactive-per-principal` | 16 / 4 | Executing audited socket reads and legacy HTTP GET requests |
| `reports` / `reports-per-principal` | 2 / 1 | Physical PROC and usage repository loads |
| `authority` / `authority-per-principal` | 2 / 1 | Physical visibility graph loads and retained foreground code reads, including non-shareable reads |
| `maintenance` / `maintenance-per-principal` | 1 / 1 | Embedding discovery and background code metadata batches |
| `observers` / `observers-per-principal` | 64 / 4 | Parked information readiness requests |
| `sockets` / `sockets-per-principal` | 1024 / 32 | Attached event and file sockets combined |
| `subscriptions` / `subscriptions-per-principal` | 512 / 32 | Usage subscriptions across sockets |

Environment overrides use `PLOWSHARE_READ_CAPACITY_` plus the property in upper snake case.
Each per-principal limit must be positive and no greater than its process limit. These are
initial configurable defaults, not a measured fleet capacity promise. Work leases settle on
physical completion; asynchronous routing retains a lease until its handler completes.
Disconnecting a client does not make still-running read work free. Admission retains state
only for admitted work and has no additional unbounded waiting queue.

Mutating operations retain dispatcher barriers/resource ordering. The shared read admission
policy does not coalesce mutations, leases, cursor advances, acknowledgements or read receipts.
HTTP and WebSocket failures use the same fault mapping. Read saturation is service unavailable
(`ARCHIVE_UNAVAILABLE`, HTTP 503), not `CAPACITY_EXHAUSTED`: the latter promises that an entire
request was not started and can trigger transport retry. An enclosing mutation may have
completed before its final projection encounters read saturation; never replay that mutation.

## Shared projections

PROC queues, usage reports and readiness fact batches reuse the bounded versioned projection cache. Keys contain the
actual authenticated principal and complete typed selection, independently of socket/feed IDs.
Only the elected loader takes report capacity. Followers wait without holding a report slot or
a database connection. Runtime pool and accounting-recorder health is attached outside cached
persistent projections.

Observation versions cover calls, attempts, ownership, accounting journals/instances/pricing,
jobs, acting metadata, acquisitions and section/chapter checkpoints. Queue projections also
track corpus, search, transcript and authority versions. Membership, grants, deletion, lifecycle,
collection membership, pending writer commits and rollback use the existing durable version
protocol. Clock-only running-lease expiry is included in the observation validity window.
Notification delivery is a wakeup hint, never freshness or permission evidence.

Cache load/waiter exhaustion refuses the read; it must not start another uncached SQL load.
Recovery, dirty/historical transactions and non-shareable values retain ordinary read semantics
through the owner's execution budget. Cache budgets are per projection; the configured maximum
number of projections bounds their aggregate allocation. Account identifiers never become
metric labels. `plowshare.read.active{work=...}` and `plowshare.read.rejected` expose capacity.

## Query ownership

PROC summary and page use one materialized counter set and one statement-local SYSTEM
eligibility set. Stage candidates are fixed before expensive eligibility expressions. Full
selection totals survive an empty cursor page. Narrow metadata projections avoid carrying
extracted document bodies through the counter pipeline. JIT is disabled only within the PROC
read transaction and a five-second transaction timeout bounds SQL work.

Conversation pages load selected inference-call facts once and reuse them for totals, activity
and repetition groups. A direct `conversation_id` index supports individual/delegated conversations;
the older root-conversation index serves a different predicate. Unrelated retained calls must not
be scanned again for each displayed conversation.

Worker discovery uses the same statement-local eligibility set. Targeted claims retain their
locking, generation, authority and allowance checks. There is no cross-request worker grant cache.

Project list role facts resolve live grant lineage once per batch. The server then applies
Application/issuance ceilings, which remain live. Rooting, Personal ownership and member metadata
are fetched in bounded batches instead of per rendered row. Board/orchestration output filters
batch relevant effective project roles at their existing disclosure boundaries. Session sweeps
batch distinct account/version facts and still close each stale socket independently.

## Information and authority discovery

Embedding maintenance uses `embedding_repair_work` rather than repeating corpus-wide missing-vector
queries on every timer tick. One indexed due-work probe skips profile hydration, store discovery
and activation checks while idle. Source changes enqueue exact keys and revisions in the source writer's
transaction. Staging publication removes completed work; failure timestamps defer discovery,
including infinite deferral for an uncertain retained submission. A discovery reservation expires
after five minutes without releasing that separate submission fence. Duplicate processes reserve
work with `SKIP LOCKED`; provider calls hold neither the discovery connection nor its budget lease.
The existing source, target, lease, authority and accounting checks still govern submission and
publication. Background activation skips full coverage scans while initial discovery is incomplete
or known repair/retry keys remain; actual activation still establishes coverage under its original
slot/source locks. No client event can authorize an embedding repair.

A durable keyset scan covers at most 1,000 source identities per profile/store/pass. Once caught up,
it waits five minutes before bounded reconciliation. This covers eligibility-only edits and
missed notifications without using sequence allocation as commit order. New target spaces receive
their own scan; changing search policy alone reuses vectors. Claims, source keys and cursor updates
roll back together. The scan is a recovery mechanism, not a replacement for current eligibility.
Discovery alone scopes JIT off and a two-second SQL timeout, restoring previous settings on success
even within an enclosing transaction; rollback restores them on failure. Activation and index DDL
retain their own lifecycle and do not inherit the discovery timeout.

Background code tracking resolves up to 100 exact source/hash/parser references per metadata page
and evaluates one current authorized target set. Unchanged sources load neither extracted text nor
symbols. Foreground navigation loads the requested projection lazily, sharing one authorized SQL
relation between text and symbols and retaining its final current READ fence. Provider roots,
agent definition, session ownership and generation/mutation fences remain independent. Maintenance
saturation defers a scan without replacing its retained manifest with an empty one.

Inbox pages and unread counts use one narrow authorized ID relation before pagination and counting;
only the selected page hydrates notice bodies. Cached deny identities use an uncorrelated membership
subquery so PostgreSQL hashes the set once, including for generic prepared plans, instead of
comparing an entire parameter array per result row. Inbox,
account-scoped transcript collections and orchestration lists reuse the same bounded deny-set
projection per effective account across sockets and feeds. Every consuming read has a fresh access
and authority fence around it. A changed fence refuses disclosure without replaying the query.
Dirty/historical callers retain statement-authoritative reads under the same physical authority
budget. A deny set that exceeds 32,768 identities or the cache byte budget uses a versioned fallback marker
and the original statement-authoritative SQL under the same physical budget. It is never hydrated
past the byte bound or truncated into an allow result. No mutation or acknowledgement shares this
projection as mutation authority.

The `READABILITY` version covers retained job inputs, quarantine, all four log ancestry edges,
resource homes, revision identity/ordinal/availability/exclusion, links, retained dependencies,
root policies, navigation policies and reference obligations. `AUTHORITY` independently covers live
accounts, tokens, scopes, roles, grants and membership; clock-only expiry is fenced too. Ordinary
entry appends, processing leases and unrelated revision metadata do not invalidate READABILITY.
All writers, cascades and TRUNCATE participate; pending-XID, rollback and reconnect rules are the
existing projection protocol. READABILITY uses a separate notification channel so older listeners
in a rolling deployment never receive an unknown domain payload. Notifications remain hints.
The visibility projection occupies one of the configured 20 named projection slots, with the existing per-projection resident, in-flight
and waiter budgets; it does not create another unbounded map or increase the registry maximum.

Application project-role checks fetch live account bindings and their ancestry in one SQL statement.
The server applies the current Application manifest to that typed snapshot, including every issuing
MANAGER, once per project per invocation. This removes per-binding SQL lineage reloads without
retaining manifests or permission decisions between invocations.

## Verification and remaining rollout

Regression coverage includes actual PostgreSQL materialization and authorization equivalence,
clock-only expiry, pending-writer/version recovery, and in-memory 1,000-request/socket bursts.
Mocks cover orchestration and capacity; PostgreSQL tests remain behind `-PfullDb` and selected
classes. Readiness batches at most 100 exact revision targets through the same policy routine as catalogue
selection, retaining targeted-read visibility for excluded/historical revisions. It fetches only
current extraction facts and shares equivalent batches across feeds. Acquisition ticket identities
are resolved separately under their owning policy.

The earlier PROC/read-capacity rollout included an isolated full-backup rehearsal without changing sampled authority,
retained dependency and conversation fingerprints. On that restored dataset, a cold queue load took
616 ms. Simultaneous bursts of 100 and 1,000 equivalent requests admitted four and refused the excess;
warm bursts performed no queue loads and cold bursts performed exactly one. Revoking the account
refused its already-warm projection. These measurements validate reuse and admission behavior,
not a promise to execute 1,000 requests simultaneously or a fleet throughput rating.

Broader owner snapshot reuse remains incremental work. Ordinary authority checks at execution and
physical disclosure still run; sharing a projection does not turn an earlier check into a permission
lease. Fleet operators must size the sum of process budgets against database CPU and connection
capacity. A bounded server does not imply every read owner has completed its query-efficiency adoption.

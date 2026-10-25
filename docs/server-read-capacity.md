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

## Verification and remaining rollout

Regression coverage includes actual PostgreSQL materialization and authorization equivalence,
clock-only expiry, pending-writer/version recovery, and in-memory 1,000-request/socket bursts.
Mocks cover orchestration and capacity; PostgreSQL tests remain behind `-PfullDb` and selected
classes. Readiness batches at most 100 exact revision targets through the same policy routine as catalogue
selection, retaining targeted-read visibility for excluded/historical revisions. It fetches only
current extraction facts and shares equivalent batches across feeds. Acquisition ticket identities
are resolved separately under their owning policy.

An isolated full-backup rehearsal applied both migrations without changing sampled authority,
retained dependency and conversation fingerprints. On that restored dataset, a cold queue load took
616 ms. Simultaneous bursts of 100 and 1,000 equivalent requests admitted four and refused the excess;
warm bursts performed no queue loads and cold bursts performed exactly one. Revoking the account
refused its already-warm projection. These measurements validate reuse and admission behavior,
not a promise to execute 1,000 requests simultaneously or a fleet throughput rating.

Broader owner snapshot reuse remains incremental work. Ordinary authority checks at execution and
physical disclosure still run; sharing a projection does not turn an earlier check into a permission
lease. Fleet operators must size the sum of process budgets against database CPU and connection
capacity. A bounded server does not imply every read owner has completed its query-efficiency adoption.

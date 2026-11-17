# Backpressure: what waits, what refuses and what stays occupied

Backpressure limits admitted work, retained bytes or access to a scarce resource. It is separate from
authentication, permission, a completion deadline and a durable job's lifecycle. This is a current source map,
not a live capacity measurement. The [bounded-work
standard](coding-standards.md#bound-active-work-in-every-subsystem) gives actual query/transaction units a configurable two-second default. Whole-request or workflow
duration above that threshold warns without cancelling accepted work. Existing explicit transport/provider
timeouts retain their owning contracts; they do not prove remote abort.

## Several owners, not one queue

A cache hit, HTTP read, socket push, mutation and provider call take different paths. The rows below are
overlapping controls, not a universal execution order. Limits are generally per
process/connection/principal/resource; adding servers can multiply pressure on the same PostgreSQL instance or
provider endpoint.

| Owner | What is bounded | Under pressure | When capacity becomes free |
| --- | --- | --- | --- |
| [SDK connection] | Admitted requests/encoded bytes; negotiated depth | Ordinary callers park; nonblocking callers refuse before submission; explicit outer capacity refusals may be paced | Local terminal settlement **and** physical writer settlement; this never frees server work |
| [Event channel] | Pending IDs/bodies/bytes plus execution/resource/observer seats | Pre-handler `CAPACITY_EXHAUSTED`; execution rechecks authority | Execution seats settle at handler completion; body/ID admission also waits for physical reply settlement |
| [Read admission] | Process/principal work seats and selected waiting queues | Eligible interactive, pure authoritative, foreground report and FETCH reads wait; other lifetime/maintenance seats refuse | Owning physical work closes its lease |
| [Socket sessions] | Queued plus physically executing session checks | Full intake/deadline produces unavailable, not revocation | Actual batch settlement; caller timeout does not erase physical work |
| [Projection cache] | Residents, physical loads, estimated loading bytes, dependency links and waiters | Coalesce one key; evict or refuse over bounds; accepted inline reads keep physical ownership; explicit async waits have their own timeout | Physical load/reservation retirement and waiter cleanup are distinct |
| [DB throttle] + Hikari | Primary-pool permits, then physical connections | Throttle waits; Hikari has a separate acquire timeout | Connection close returns its admission handle; borrow failure also releases it |
| [LLM pool] | Running chat/embedding lanes per configured inference resource | Queue until start budget expires; queue count itself is unbounded | Physical task completion; queued work shed before dispatch is removed |
| [Event output] + [packet transport] | Queued pushes/replies, logical bytes, physical segments/credits | Drop lossy progress; response failure/disconnect remains uncertain | Drain/transfer completion or explicit retirement, not frontend disappearance |
| [Accounting journal] | Durable metadata bytes and terminal-event retry buffer | When capture is enabled, refuse new tracked inference admissions if unhealthy/full | Projector commit/ack permits reclamation; recovery replays metadata only |

## Request and transport intake

The TypeScript [SDK connection] reserves local admission before encoding, then waits for negotiated dispatch
depth. Count/byte bounds apply to admitted encoded work, **not every producer promise parked outside
admission**. Its waiting queues are not an application workload scheduler. Other SDK implementations have
their own owners; do not infer identical queue/timer behavior from a language binding.

`CAPACITY_EXHAUSTED` from the event channel is an explicit outer refusal: the handler did not start. The SDK
can pace that same identity/body with jitter when its retry contract permits it, retaining the original
deadline. Nonblocking calls surface refusal; dispatch negotiation is not automatically replayed. A missing
reply, timeout, socket close or packet failure supplies no equivalent proof. [ConcurrentRequests] keeps
body/ID admission through execution and physical reply settlement. Independent eligible work may overtake blocked requests
while preserving process-wide conflicting resource-key order; barriers order work on their own connection. This is not global
FIFO. Disconnect retires unstarted work, not still-running physical ownership.

The TypeScript request timer currently starts at transmission, excluding local admission/negotiation/depth
parking; it is not renewed across capacity refusals. A frame handed to `socket.submit` may still wait in its
writer queue. [Packet transport] separately has credit/idle and total-transfer bounds. Closing or aborting
local observation does not prove a remote mutation or job was cancelled. See the [transport
guide](../.agents/guides/transport-sdks.md).

## Read admission and cache fills

[Read admission] separates interactive, report, authority, observer, subscription, socket, maintenance and
FETCH seats. A principal shares its budget across feeds and sockets. HTTP GETs use [HTTP read admission]; WS
routing and individual cache owners choose their corresponding work class. Not every mutation is a pure read.

Interactive admission waits in a bounded eligible FIFO queue before dispatch. Only
queue acquisition uses that wait budget (2s default); it does not time out a whole
handler or provider workflow. Its lease remains owned by the physical source
stage after reply cancellation. HTTP uses bounded acquisition, with its
execution/async-settlement gap unchanged. A separately declared pure authoritative
read may wait for AUTHORITY capacity only without a caller transaction or Spring
synchronization. Neither queue replays a handler or increases active capacity.

Foreground REPORT waiting is bounded and FIFO **among eligible principals**: an already busy principal does
not block all others. REPORT_REFRESH shares the report total/per-principal budget, yields to queued foreground
work and leaves a foreground seat when the total exceeds one. Transaction/synchronization holders do not join
the ordinary report waiting queue. Waiting owns no JDBC connection.

FETCH waiting is bounded and chooses the oldest eligible cache; its principal is the cache, not the account.
Account-shaped report keys additionally retain the caller's report seat. A FETCH runs inline on the
requester's thread, using that caller's bound connection when applicable; do not move it to another thread and
then wait while retaining the caller's connection/permit.

[ReadDeadline] carries only an existing actual SQL-unit deadline into FETCH acquisition;
outside one, acquisition uses `fetch-wait-millis`. It does not create a cache-workflow timer or
cross worker threads. A miss coalesces onto one fill rather than duplicating SQL. Accepted inline
loads and joiners wait for that physical load without an unrelated two-second caller timer.
Slow composed reads warn only. The explicit asynchronous cache API retains its documented wait
timeout, and an existing SQL deadline can still fail the owning SQL unit. Physical loading
reservations remain occupied until the load exits.

`ReadUnavailableException` maps to `ARCHIVE_UNAVAILABLE` on WS and HTTP 503. It is **not**
`CAPACITY_EXHAUSTED`: an enclosing operation may have committed a mutation before attempting a projection.
Retry only the independently safe read under its owning policy; do not replay that operation. Capacity/timeout
failures also must not become a permission denial, an empty result or a stale cached allow. See [server read
capacity](server-read-capacity.md) for class defaults and routing.

## Database throttle: delay ceiling is not acquisition deadline

[AdmissionThrottle] is WAIT-only. Below its occupancy threshold it proceeds; above it, an
occupancy/acquire-latency pressure curve applies jittered delay. The configured `ceiling` (default 3s) bounds
this **added delay only**. Once every permit is held, ordinary callers enter a fair semaphore wait with no
timeout. A caller may therefore wait longer than 3s before even reaching Hikari. Thread interruption is
surfaced; it is not an invented mid-handler capacity refusal that would license replay of partially applied
work.

Permits derive from Hikari maximum pool size minus `permit-margin`; the threshold is derived from that permit
count and `threshold-fraction`. The margin reserves room for bypass consumers such as Flyway and health
probes. Same-thread nested borrows take a permit if immediately available; at saturation they proceed
permit-free as an observable overdraft to avoid waiting on their own outer hold. The margin is **not** a bound
on every nested borrow or a fleet-wide reserve.

[ThrottledDataSource] acquires admission before asking Hikari for a connection. Hikari's connection timeout
starts at that later acquire; it does not include the preceding semaphore wait. Close the connection to
release the admission handle, including after JDBC abort; leak detection logs, it does not reclaim. Borrow
failure/close failure paths must still return owned permits exactly once.

Never hold a connection/permit while joining another thread that may need one: that holder/dependent-worker
shape can deadlock under full admission. This is a documented structural hazard, not a claim that a live
installation is deadlocked. Retain the same-thread nested exception; it does not fix cross-thread cycles.
[Pool configuration] checks the delay ceiling stays at least 1s below Hikari's acquire timeout for
attribution. That check does not create an overall deadline.

## Provider capacity and physical effects

[LLM pool] has separate fixed chat/embedding lanes per inference resource. Its `submit-timeout` limits waiting
**to start**, not execution after a worker starts. A start-versus-shed fence ensures a timed-out queued call
is NOT_DISPATCHED and cannot later run. Once started, provider/transport cancellation and execution bounds own
the physical call; a caller interrupt does not instantly free a lane.

Its executor queue is unbounded in count. `NO_DEADLINE` is an existing special scheduled-stream wait mode,
polled for abandonment; it is a current exception/gap to the finite-work policy, not a pattern for new
callers. Routing observes queued plus running work relative to capacity. Neither a capacity pool nor a
model-call budget is a spending pool, permission grant or guarantee of provider throughput. Keep provider IO
outside JDBC transactions; see [runtime/inference](../.agents/guides/runtime-and-inference.md).

## Output and durable capture

[Event output] separates lossy progress from request responses. A full progress queue can drop hints/deltas;
clients must poll durable job/trajectory state for completion. A response that cannot be retained/delivered
cannot be called a safe pre-handler refusal: it may follow an effect. Packet credit failures close the
transport and preserve uncertainty. Bytes and request counts are separate bounds; large responses need byte
admission even when few requests are pending.

[AccountingProperties] bounds journal bytes, segment size, IO lock wait and a terminal metadata retry buffer.
With capture enabled, tracked inference requires healthy capture, including ZERO_RATE/INCLUDED calls. An
answer already obtained is not discarded or reissued because its final metadata append failed:
[AccountingRecorder] buffers bounded terminal metadata, records loss/conflict when necessary and blocks new
tracked inference admissions while unsafe. The [Accounting journal] fsyncs acknowledged appends; the projector acknowledges
only a committed database prefix. Reclamation/recovery is metadata work, never model-call replay. Its lock
timeout cannot forcibly bound an in-progress kernel fsync. The OpenAI producer has a separate
response-character cap, not end-to-end subscriber demand ([OpenAiTransport]). Other durable queues keep their
own contracts.

## Configuration, observation and tuning

Defaults below come from source/configuration, not from a deployment measurement.

| Owner/settings | Current examples |
| --- | --- |
| [Event channel] / [application configuration] | Advertised pending depth 8; 2 finite execution slots/connection, global 0 (no configured global execution-count cap), observers 256; separate coordinator/byte limits remain |
| [Read capacity settings] / [read scheduling settings] | REPORT 2 total/1 principal; foreground waiters 64/4; report/FETCH wait defaults 2000ms |
| [Socket session settings] | Pending 256/32 principal, batch 128, gather 2ms, caller wait 2000ms |
| [Projection settings] | Explicit async read wait 2s; accepted inline work warns only; independent resident/load/byte/waiter limits |
| [Pool settings] / [application configuration] | Margin 2, threshold fraction 0.5, delay quantum 20ms/ceiling 3s; Hikari acquire default 5000ms |
| [Provider settings] | Per-pool concurrency and submit/transport timeouts; do not confuse queue-start and execution budgets |
| [AccountingProperties] | Journal 256MiB, segments 4MiB, IO wait 2s, retry buffer 128 metadata events |

Observe the layer that owns the wait: `plowshare.read.active`, `.waiting`, `.fetch.waiting`,
`.interactive.waiting`, `.authoritative.waiting`, `.rejected`;
`plowshare.socket.sessions.*`; cache load/coalescing, resident/loading bytes and refusal reasons; Hikari
active/pending/acquire latency; provider queue/running counts; output pressure; accounting
health/pending/loss. [Pool metrics] distinguishes `.delay` from `.saturation.wait`; `.overdraft` is a
**counter of permit-free nested admissions**, not a gauge of current overdraft. Dispatch snapshots/logs and
PROC accounting health are not automatically Prometheus gauges. [AccountingProperties] and queue bounds do not
establish globally bounded memory: SDK producer waiters and provider job queues remain count-unbounded.

Before raising limits, identify slow SQL/long connection holds, nested transactions, blocked output, duplicate
fills, retry amplification or stuck provider work. Do not first enlarge pool/margin to conceal a
held-connection cycle. Tune related logical bounds against actual downstream capacity across instances;
increasing queue/count limits alone can move overload into heap, latency or PostgreSQL. Keep labels bounded;
never add account/job/query text as pressure metric labels.

## Deadline gap and change checks

The [completion rule](coding-standards.md#bound-active-work-in-every-subsystem) requires an inherited
remaining active-work budget from admission through execution/IO. Expiry cleanup/drain has a separate finite
budget; physical occupancy remains accounted for until settlement. Current request transmit timers,
report/session waits, cache waits, DB semaphore/Hikari
acquisition, provider queue budgets and packet timers cover different intervals. They neither add up to one
guaranteed two-second request nor universally cancel physical SQL/provider work. Follow the [SQL-specific
rule](coding-standards.md#bound-sql-work-by-a-completion-deadline) when changing database boundaries; do not
silently change retry/transaction semantics.

For changes, identify the admission unit, capacity owner, deadline start, refusal proof, physical release and
safe recovery. Review saturation, slow peers, late results, cancellation failure, nested holds and close/error
paths. Relevant non-DB tests include [BoundedReadAdmissionTest], [AdmissionThrottleTest],
[VersionedProjectionCacheTest], [ConcurrentEventChannelTest], [LlmPoolTest], [AccountingRecorderTest] and the
SDK [concurrent tests]. Docs-only changes need link/source review, not PostgreSQL or a live provider call.

[SDK connection]: ../sdk/typescript/src/binding/connection.ts
[concurrent tests]: ../sdk/typescript/src/binding/concurrent.test.ts
[application configuration]: ../plowshare-server/src/main/resources/application.yml
[Event channel]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java
[ConcurrentRequests]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConcurrentRequests.java
[Read admission]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/BoundedReadAdmission.java
[HTTP read admission]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/HttpReadAdmission.java
[Socket sessions]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/QueuedSocketSessions.java
[Projection cache]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/VersionedProjectionCache.java
[ReadDeadline]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/ReadDeadline.java
[DB throttle]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/AdmissionThrottle.java
[AdmissionThrottle]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/AdmissionThrottle.java
[Pool configuration]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionConfiguration.java
[ThrottledDataSource]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/ThrottledDataSource.java
[LLM pool]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmPool.java
[Event output]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java
[packet transport]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/PacketEventTransport.java
[Accounting journal]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingJournal.java
[AccountingProperties]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/AccountingProperties.java
[AccountingRecorder]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingRecorder.java
[Read capacity settings]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/ReadCapacityProperties.java
[read scheduling settings]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/ReadSchedulingProperties.java
[Socket session settings]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketSessionProperties.java
[Projection settings]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/ProjectionCacheProperties.java
[Pool settings]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionProperties.java
[Provider settings]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/PoolProperties.java
[Pool metrics]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/pool/PoolAdmissionMetrics.java
[OpenAiTransport]: ../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/openai/OpenAiTransport.java
[BoundedReadAdmissionTest]: ../plowshare-server/src/test/java/io/aeyer/plowshare/server/reads/BoundedReadAdmissionTest.java
[AdmissionThrottleTest]: ../plowshare-server/src/test/java/io/aeyer/plowshare/server/pool/AdmissionThrottleTest.java
[VersionedProjectionCacheTest]: ../plowshare-server/src/test/java/io/aeyer/plowshare/server/cache/VersionedProjectionCacheTest.java
[ConcurrentEventChannelTest]: ../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ConcurrentEventChannelTest.java
[LlmPoolTest]: ../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/dispatch/LlmPoolTest.java
[AccountingRecorderTest]: ../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/AccountingRecorderTest.java

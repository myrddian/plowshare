# Server administration

A Plowshare administrator manages accounts and projects on the server. Project
roles determine what a regular account can do in each project. The administrator role
controls server management; granting that role does not automatically grant access
to another account's Personal space. Server administrators have Manager access to
ordinary server projects; private client projects keep their client ownership boundary.

## WebSocket connection keepalive

The server sends a native WebSocket Ping after an event or file channel has been
idle for 30 seconds. The peer must echo its eight-byte payload in a Pong within
30 seconds. Checks run every five seconds, so detection can lag a deadline by
one check interval. Active traffic postpones an idle probe; once sent, only the
matching Pong satisfies it. A missed deadline closes that transport with the
reason `WebSocket heartbeat pong deadline expired`. Each channel is checked
independently, including idle file channels.

These are WebSocket control frames, outside JSON operations and segmented
messages. They require no agent, database query, application `PING` command or
new SDK operation. They preserve the existing authentication, request timeouts,
file-operation deadlines and disconnect/recovery rules; they never replay work
or renew a login. A Pong establishes transport responsiveness, not completion of
an application request.

Startup settings live under `plowshare.transport.keepalive`:

| Setting | Environment variable | Default |
| --- | --- | --- |
| `enabled` | `PLOWSHARE_WS_KEEPALIVE_ENABLED` | `true` |
| `interval` | `PLOWSHARE_WS_KEEPALIVE_INTERVAL` | `PT30S` |
| `pong-timeout` | `PLOWSHARE_WS_KEEPALIVE_PONG_TIMEOUT` | `PT30S` |
| `check-interval` | `PLOWSHARE_WS_KEEPALIVE_CHECK_INTERVAL` | `PT5S` |

Durations must be between one second and one hour. The check interval must not
exceed either the idle interval or the Pong timeout. Missing/wrong Pongs, failed
writes and shutdown close or detach through the channel's ordinary lifecycle.
Control-frame writes share serialization with application writes; probes never
queue behind a busy writer or block the shared scheduler.

## Request dispatch and capacity

Each event connection that selects concurrent dispatch is given a per-connection
request depth in the handshake, and the server enforces it. A request beyond that
depth, or beyond a server-wide pending or byte bound, is answered with
`CAPACITY_EXHAUSTED` rather than closing the connection; nothing was started, so
the client may send it again under the same id. The TypeScript SDK treats that
answer as backpressure and waits; the other SDKs fail the call with
`NOT_SUBMITTED`.

Startup settings live under `plowshare.transport`:

| Property | Default | Effect |
| --- | --- | --- |
| `request-depth` | `8` | Per-connection admission window (requests queued, including those executing) advertised to clients, between 1 and 64. |
| `request-per-connection` | `2` | Requests of one connection executing at once. Inherited, not chosen; see below. |
| `request-global` | `0` | Requests executing at once across all connections; `0` is unlimited. Admission stays bounded by the depth, the server-wide pending bound and the byte budgets, and the database pool bounds actual work. |
| `request-observers` | `256` | Long parked waits (such as `information.await`) across all connections; must be at least `request-per-connection`. |

`request-depth` defaults to 8 for honest backpressure: a shallow queue refuses
sooner and more visibly instead of letting 64 requests pile up behind two
executing ones. It bounds queueing, not execution, so it does not affect the
database pool; `request-per-connection` governs that. At the defaults, 8 are
admitted, 2 execute and 6 wait. The Java, Go, Python and .NET SDKs adopt the
advertised depth and fail locally with `NOT_SUBMITTED` at that bound instead of
waiting, so their consumers are refused at the 9th concurrent request. Only the
TypeScript SDK blocks, and every first-party client uses it. A deployment serving
the other four SDKs may want a higher value. Teaching those SDKs to block is
separate work.

`request-per-connection` was not chosen for request dispatch. Request dispatch
originally shared the event-read setting (`event-read-concurrency`, default 2),
and 2 was kept when the properties were split, purely to preserve behaviour. It
is now more load-bearing: with `request-global` at 0 it is the only transport
execution bound. Raising it is what would pressure the connection pool, and that
should wait until Hikari starvation is expressed as `CAPACITY_EXHAUSTED`
capacity, which is not done yet.

The main database pool is sized by environment variables:

| Environment variable | Default | Effect |
| --- | --- | --- |
| `PLOWSHARE_DB_POOL` | `20` | Maximum size of the main connection pool. Raise it only together with PostgreSQL `max_connections`. |
| `PLOWSHARE_DB_CONNECTION_TIMEOUT` | `5000` | How long a request waits for a pooled connection, in **milliseconds**. |

`PLOWSHARE_DB_CONNECTION_TIMEOUT` is a plain millisecond count, not a duration. A
duration string such as `5s` fails to bind and the server will not start. The
timeout is short on purpose: starvation then fails fast and names the pool instead
of looking like a lost response at the client's request deadline.

All `plowshare.transport` dispatch keys are written out in `application.yml` with
their defaults and can be overridden by the environment variable formed from the
key: `PLOWSHARE_TRANSPORT_` plus the key in upper snake case, for example
`PLOWSHARE_TRANSPORT_REQUEST_DEPTH`. The remaining keys are
`event-read-concurrency` (`2`, per connection), `event-read-global-concurrency`
(`8`), `packet-memory-bytes` (`2147483648`; it must hold one portable message with
its conversion copies or startup fails), `request-shutdown-drain` (`10s`, a
Spring duration), `request-metrics-interval` (`PT30S`, how often the dispatch
measurements are logged at TRACE when changed) and
`request-metrics-summary-interval` (`PT10M`, how often the cumulative INFO
summary is logged). The keepalive block keeps its existing `PLOWSHARE_WS_KEEPALIVE_*`
names.

### Logical read capacity

[Server read work and capacity](server-read-capacity.md) describes shared per-principal and
process budgets across HTTP requests, event/file sockets, report loaders and subscriptions.
These bounds apply before database acquisition and complement the connection throttle below.

### Database admission throttle

A wait-only throttle sits in front of connection acquisition on the main pool. It
delays work as the pool fills and never refuses it, so pool exhaustion remains
distinguishable in logs. Settings live under `plowshare.pool-admission`; each is
overridden by `PLOWSHARE_POOL_ADMISSION_` plus the key in upper snake case.

| Property | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | `false` removes the throttle; acquisitions go straight to the pool. |
| `permit-margin` | `2` | Connections never handed out by the throttle, for consumers that bypass it (Flyway at boot, the actuator health probe). |
| `threshold-fraction` | `0.5` | Fraction of the permits at which delay begins, strictly between 0 and 1. |
| `base-quantum` | `20ms` | Delay unit just past the threshold; must be below `ceiling`. |
| `ceiling` | `3s` | Longest delay applied to one acquisition. |
| `pressure-window` | `10s` | Half-life over which observed acquire-latency pressure decays. |

These are Spring durations (`20ms`, `3s`), unlike the pool timeout above, which is a
plain millisecond count.

The permit count and the delay threshold are **derived** from the pool size and are
not settable: `permits = pool size - permit-margin` and `threshold =
floor(permits * threshold-fraction)`, at least 1. Resizing `PLOWSHARE_DB_POOL`
resizes the throttle, so the two cannot disagree. Startup fails if fewer than two
permits lie above the threshold.

**Invariant.** `ceiling` must be at least one second below the pool acquire timeout
(`PLOWSHARE_DB_CONNECTION_TIMEOUT`, default 5000 ms). Otherwise a throttle wait and
an exhausted-pool timeout look the same in the logs and the throttle's attribution
is lost. The server enforces this at startup and **refuses to boot** when it is
broken; that is by design. Raise the acquire timeout before raising the ceiling.

#### What the ceiling does not bound

`ceiling` bounds only the **pacing** delay: the per-acquisition wait the curve
applies as the pool fills. It is not a bound on how long an acquisition can take.
Once every permit is held, the next caller blocks until a permit is freed, and that
wait has **no bound at all**. The throttle only ever waits, so a saturated pool now
produces unbounded request latency instead of the 5-second pool-acquire failure it
produced before. This is deliberate — a capacity refusal raised mid-request would
let an SDK replay a partially applied operation — but it changes what an incident
looks like: requests queue and slow down rather than failing, and nothing times out
on the server's side.

Watch `plowshare.pool.admission.saturation.wait` for it. It records only the
semaphore wait, separately from the pacing delay, so it is the one meter that shows
this stall. As a rule of thumb at the shipped settings, a maximum of a few hundred
milliseconds is ordinary burst queuing; sustained waits past roughly one second mean
callers are queueing for connections rather than being paced, and a maximum of
several seconds means requests are being held for the duration of someone else's
work. The usual cause is a connection held across a network or model call rather
than too little pool; `PLOWSHARE_DB_POOL` is what to raise once that is ruled out.
Compare it with `plowshare.pool.admission.delay` to tell the two apart: pacing rising
while the saturation wait stays near zero is the throttle doing its job.

#### Meters

All under `/actuator/metrics`, and the only place pool pressure is visible, since the
throttle never produces a client-visible outcome.

| Meter | Type | What it shows |
| --- | --- | --- |
| `plowshare.pool.admission.held` | gauge | Permits currently held. |
| `plowshare.pool.admission.permits` | gauge | Permits configured (pool size less `permit-margin`). |
| `plowshare.pool.admission.threshold` | gauge | Occupancy at which delay begins. |
| `plowshare.pool.admission.delayed` | counter | Acquisitions the curve delayed at all. |
| `plowshare.pool.admission.delay` | timer | Pacing delay applied per delayed acquisition. |
| `plowshare.pool.admission.saturated` | counter | Acquisitions that blocked because every permit was held. |
| `plowshare.pool.admission.saturation.wait` | timer | Time those acquisitions spent blocked; see above. |
| `plowshare.pool.admission.overdraft` | counter | Nested acquisitions admitted without a permit: none was free, a waiter was queued ahead, or the thread was already interrupted. |

None of them carries a tag, so the set is constant whatever the traffic.

An `overdraft` is a thread holding a second physical connection against a single
permit: a `PROPAGATION_REQUIRES_NEW` transaction inside another, or a nested query
inside a row mapper. Such a borrow normally takes a permit of its own, but at
saturation it is admitted without one, because making it wait would deadlock the
thread against itself. The same happens when a waiter is already queued for a permit
(the nested borrow does not jump the queue) or the thread was already interrupted. A
sustained non-zero count therefore means real pool use is running above `permits`, and
the pool — not `permit-margin` — is what to raise.

This is the price of the no-deadlock guarantee, and it has a limit. Each overdraft is
a real connection the throttle did not count, so enough simultaneous overdrafts use up
the `permit-margin` connections and then the pool itself. At that point a borrow fails
on the pool's own acquire timeout, the same indistinguishable pool-named error the
throttle otherwise removes. Watch `overdraft` together with `held` at or near
`permits`: a rising `overdraft` while `held` stays pinned at the ceiling is the
warning. The remedy is to raise the pool size, which raises `permits` with it; raising
`permit-margin` does not help, since it only reserves connections the throttle never
hands out.

### Statement and authority-check meters

Present only when a meter registry exists (it does, with actuator). Each `plowshare.jdbc.statements` timer registers on the first statement of its `pool`, `method` and `outcome` combination, so a combination that has never occurred (for example `outcome=failed`) is absent rather than zero; the two authority counters register every outcome at startup, so they read zero until used.

| Meter | Type | Tags | What it shows |
| --- | --- | --- | --- |
| `plowshare.jdbc.statements` | timer | `pool`, `method`, `outcome` | Every physical JDBC execute (a batch is one) and its duration. `pool` is `main`, `projection-versions` or `listen`; `method` is the JDBC execute method; `outcome` is `ok` or `failed`. |
| `plowshare.authority.admission.checks` | counter | `outcome` | How each operation admission recheck was answered: `verified_hit` (remembered, no walk), `walked` (walked and recorded), `transaction_walk` (caller's transaction may hold writes, never reused), `no_snapshot_walk` (no usable authority snapshot, never reused), `refused` (the walk threw; never recorded). |
| `plowshare.authority.session.checks` | counter | `outcome` | How each socket session was decided: `snapshot` (accepted from memory), `sql` (accepted by the stored authority), `refused` (the final answer was not current). |
| `plowshare.operation.statements` | distribution summary (percentile histogram) | `kind` | JDBC statements one operation executed, recorded when it ends. `kind` is `tool_call`, `job_step` or `socket_request`; a job step includes its tool calls. Counted per thread: work a tool hands to another thread, or a concurrent socket handler completes elsewhere, is not in its operation. Registered with the first statement meter, zero until used. |

Socket session checks are reused within one request: after a request's first successful
session check, the handler, the post-handler fence, the disclosure fence and wait loops
reuse it only while an authority snapshot is present, it is the same snapshot instance
that was current when the success was recorded, and the success is younger than
`plowshare.projection-cache.notified.staleness` (2 s by default). A new AUTHORITY version
(including a session revocation), a lost snapshot (for example a down notification
listener) or the bound ending triggers a real check; a refusal is never reused. Revocation
therefore reaches a running request or a socket's delivery fence within the snapshot's own
lag, with no extra stacking. With notifications disabled every check is exact. New frames
and the one-second sweep still check on their own.

The SQL text, its hash, identities and project or topic names are never tags, so each
set is constant whatever the traffic; statement attribution stays in the opt-in JFR
`plowshare.JdbcExecute` event. Comparing the `statements` count before and after a
change measures round trips directly.

The two Hikari pools are named `plowshare-main` and `plowshare-projection-versions`, so
the `hikaricp.*` pool meters carry that name in their `pool` tag and the two pools can
be told apart.

### Projection cache budgets

Projection caches are bounded by explicit budgets under `plowshare.projection-cache`,
all written out in `application.yml` and overridden by
`PLOWSHARE_PROJECTION_CACHE_` plus the key in upper snake case. Invalid or
inconsistent values fail startup.

| Property | Default | Effect |
| --- | --- | --- |
| `entries` | `256` | Cached entries. |
| `resident-bytes` | `33554432` | Bytes held by one projection line's cached values. |
| `loads` | `8` | Loads in flight at once. |
| `loading-bytes` | `67108864` | Bytes of values being loaded. |
| `waiters` | `256` | Callers waiting on loads, in total. |
| `waiters-per-load` | `64` | Callers waiting on one load. |
| `dependency-links` | `8192` | Dependency links between entries. |
| `max-value-bytes` | `8388608` | Largest single value; must fit within `loading-bytes` with headroom. |
| `timeout` | `2s` | The single deadline of one cached read, from when it reaches the cache. The FETCH slot wait of its loader and its wait on another request's reload both draw on it; past it the read is refused as busy (retry) and no uncached load is started. An elected loader whose load outlives it still stores the value for the next read. |
| `notification-poll` | `1s` | Version-notification poll cadence, at most 30s. |
| `reconnect-delay` | `1s` | Delay before reconnecting a lost notification session, at most 1m. |
| `max-pending-transactions` | `128` | Pending transactions tracked (1..4096). |
| `total-resident-bytes` | `67108864` | Bytes held by all projection lines together (at least `resident-bytes`). Each projection is one line with its own LRU; past this total the coldest entries of the line furthest above its share are evicted, never a whole line. The number of projections is not configured or limited. |
| `notified.enabled` | `true` | Delivery probes, and notification-trusted hits for caches that opt in. |
| `notified.staleness` | `2s` | Most a trusted hit may lag a committed permission change (at most 1m). |
| `notified.heartbeat` | `500ms` | Probe interval; must be shorter than `staleness`. |
| `notified.heartbeat-timeout` | `3s` | Unanswered-probe limit before the listener reconnects; also its network timeout. |
| `version-connections` | `4` | Connections of the separate `projection-versions` pool (1..32). |

### Projection cache and listener metrics

These meters read server memory only. Scraping does not acquire database connections,
validate versions or send probes. Projection labels are the fixed names registered
by server composition. No account, project, cache key,
SQL or exception message becomes a label. Counters restart with the server; compare
rates or interval deltas across deployments.

`plowshare.projection.cache.events` is a counter tagged by `projection` and `event`:

| Event | Meaning |
| --- | --- |
| `trusted_hit` | Value returned after notification freshness, transaction and expiry checks, without version SQL. |
| `versioned_hit` | Previously loaded or coalesced value returned after validating its stored version. |
| `loaded_return` | Elected loader's caller returned its newly loaded value after all freshness checks passed. |
| `load` | Actual bounded-loader invocation, including attempts later rejected or failed. Joiners and failed trusted lookups do not increment it. |
| `bypass_not_ready` | Ordinary-loader attempt while the listener/cache is recovering or closed. |
| `bypass_not_cacheable` | Ordinary-loader attempt for an explicitly uncacheable selection. |
| `bypass_ineligible` | Version capture refused sharing, for example because of transaction state or an authority expiry window. |
| `bypass_load_refused` | Explicit load bypass, oversized value, or failed post-load eligibility/version check led to an ordinary-loader attempt. |
| `bypass_cache_refused` | Recoverable backing-cache refusal led to an ordinary-loader attempt. |
| `bypass_stale` | Retrieved candidate failed the final readiness/version check and led to an ordinary-loader attempt. |
| `capacity_rejected` | Capacity exhaustion refused the read without an ordinary-loader fallback. |
| `deadline_rejected` | The read's `timeout` passed before a value was available (waiting for another request's reload, or the caller's own load outlived it); refused as busy without an ordinary-loader fallback. |

The first three events count successful cache returns and are mutually exclusive per
read. `load` and bypasses count attempts; adding them to returns double-counts work.
A bypass is not a SQL execution count: some owners return an unavailable marker and
decide their SQL fallback later. SQL failures retain their original error behavior
and do not replay a load. Authority snapshot load attempts use
`projection="authority-snapshot-v1",event="load"`; `loaded_return` counts the subset
that could be returned through the cache.

`knowledge-facts-v1` holds the shared revision and resource facts of the targeted
readability check. Its reads are confirmed together, one statement per check, after
the check has read every fact it needs: there `versioned_hit` and `loaded_return`
count facts returned before that confirmation. A failed confirmation invalidates the
stale facts and the check falls back to its SQL statement.

`log-tree-facts-v1` holds one log tree per root for the targeted log check, read and
confirmed the same way together with the knowledge facts of its retained inputs. A
write in one tree reloads only that tree. `information-log-visibility-v1` now serves
only collection reads (transcript lists, orchestration lists and inbox feeds).

`plowshare.projection.cache.backing` exports existing backing-cache counters with
`projection` and `event` tags: `hits`, `misses`, `coalesced`, `evictions`, `bypassed`,
`invalidations` and `load_failures`. These describe activity before final freshness
checks. A backing hit can subsequently be rejected, and an internal trusted lookup
can miss without loading data. Use projection outcomes to assess useful reuse.
Gauges tagged only by `projection` use the `plowshare.projection.cache` prefix:
`.entries`, `.resident.bytes`, `.loads.active`, `.loading.bytes` and `.waiters`.

Listener meters use the `plowshare.projection.listener` prefix and have no
application-level tags:

| Suffix | Type | Meaning |
| --- | --- | --- |
| `.ready` | gauge | 1 after LISTEN and the authoritative baseline complete; 0 during startup, outage or shutdown. |
| `.proof.available` | gauge | 1 when an answered probe proves delivery in the current ready generation; otherwise 0. |
| `.proof.age` | seconds gauge | Monotonic-clock age of the send time of the newest answered probe; `NaN` when proof is unavailable. |
| `.notified.enabled` | gauge | Whether trusted reads and delivery probes are configured on (1) or off (0). |
| `.staleness` | seconds gauge | Configured maximum proof age for trusted reads. |
| `.reconnects` | counter | Session-open attempts after the initial attempt, including failed recovery attempts. Initial startup and probes do not increment it. |

Ready does not imply fresh: recovery can finish before a probe echo, and proof can
age while the session remains ready. Disconnection clears proof availability instead
of reporting a misleading zero age. With probes disabled, absent proof is expected
and reads remain versioned. Trusted reads already stop when proof is missing or too
old; telemetry does not enforce that fence.

Prometheus names replace dots with underscores. Counters append `_total`; duration
gauges append `_seconds`. For example, this expression identifies a listener unable
to support configured trusted reads:

```promql
(plowshare_projection_listener_notified_enabled == 1)
and
(
  (plowshare_projection_listener_ready == 0)
  or (plowshare_projection_listener_proof_available == 0)
  or (plowshare_projection_listener_proof_age_seconds
      > plowshare_projection_listener_staleness_seconds)
)
```

Allow an alert hold period for startup/recovery and monitor scrape failure separately.
Scrapes sample independent gauges and can race a listener transition. An interval
longer than the staleness bound cannot observe every brief loss of freshness; the
cache's per-read fence still applies.

### Token usage and inference metrics

The default-on durable inference accounting path also exports live aggregates under
`plowshare.inference`. Labels are fixed enums: `lane` (`chat` or `embedding`) and
`operation` (such as `agent_chat`, `fold`, `document_summary` or `embedding_query`).
There are no account, project, job, model, pool or provider-request identifiers.
These meters are absent when `plowshare.llm.accounting.enabled=false`, and standalone
contexts without a registry keep their existing behavior.

| Meter suffix | Type | Additional labels and meaning |
| --- | --- | --- |
| `.admitted` | counter | Calls whose canonical admission succeeded. Failed admission is excluded. |
| `.active` | gauge | Admitted calls not yet finished, including calls waiting in a lane queue. |
| `.calls` | counter | `outcome`: terminal call outcomes. Calls with no upstream attempt are `not_dispatched`. |
| `.attempts` | counter | `outcome`: each completed upstream attempt, including retries and failures. |
| `.tokens` | counter | `kind`: valid provider-reported `input`, `output`, `provider_total`, `cache_read`, `cache_write`, or `reasoning` tokens. |
| `.tokens.measured` | counter | `kind`: number of attempts supplying that valid provider field, including explicit zeroes. |
| `.usage` | counter | `coverage` and `source`: completeness and provenance of each attempt's final usage observation. |
| `.duration` | function timer | Admission-to-finish call duration, including queueing and terminal accounting. |
| `.attempt.duration` | function timer | Attempt duration measured by the existing inference observer. |
| `.queue.duration` | function timer | Measured queue waits; absent queue time does not add a zero sample. |
| `.first.output` | function timer | Time to first observed output, only for attempts that produced output. |

Token observations are counted once when an attempt ends. Repeated streaming usage
updates replace that attempt's observation; they are not added together. Every retry
is a separate attempt and may incur additional tokens. A failed/cancelled attempt's
reported usage still counts. Journal retries and projector replay never increment
these metrics. A buffered terminal accounting record does not undo spent inference.

Unknown counts are not filled with zero or derived from other fields. Invalid usage
increments `coverage="invalid"` and contributes no token quantities. Partial valid
observations contribute only known fields. Tokenizer/estimate observations remain
visible through `source` on `.usage`, but never enter provider-token totals. An
embedding can have complete input usage without any output measurement. Check
`.tokens.measured` and coverage alongside totals to distinguish measured zero from
missing data.

**Do not sum all token kinds.** Reasoning is already included in output, cache-read
quantities may be subsets of input, and `provider_total` overlaps input/output.
For example, this query keeps input and output rates separate:

```promql
sum by (kind) (
  rate(plowshare_inference_tokens_total{kind=~"input|output"}[5m])
)
```

These are process-local monitoring counters, not a billing ledger. They reset at
restart, do not backfill history, and use floating-point totals. The
[durable usage reports](model-usage-accounting.md) remain authoritative for exact
counts, ownership, pricing, incompleteness and historical queries. Scrapes only read
memory and perform no accounting/database queries.

### Job lifecycle metrics

JobStore exports process-local executor-task metrics under `plowshare.jobs`:

| Meter suffix | Type | Meaning |
| --- | --- | --- |
| `.started` | counter | Actual task starts after executor acceptance. |
| `.active` | gauge | Tasks still executing, including completion callbacks. |
| `.completed` | counter | Task exits tagged by the fixed `ending` enum, such as `answered`, `cancelled`, `unavailable` or `call_budget`. |
| `.rejected` | counter | Executor refusals during shutdown; these do not invent starts or completions. |
| `.duration` | function timer | Task lifetime by `ending`, including completion callbacks. |

Cancellation requests do not count as completed jobs until their task exits. A
runtime failure is counted as `unavailable`; an exit without a recorded outcome also
uses that ending. Dropped WebSocket events do not affect these measurements. A job
may already be visible as DONE while its final callback is still executing and
`.active` still includes it. Direct inline/delegated runs outside JobStore are not
additional JobStore tasks; their inference still passes through inference accounting.
These metrics do not scan durable jobs or reconstruct active work after restart.

All function timers export cumulative count and seconds sum, without histogram or
percentile buckets. Prometheus counters append `_total`; timers append
`_seconds_count` and `_seconds_sum`. For example, mean completed job duration over
five minutes is:

```promql
sum(rate(plowshare_jobs_duration_seconds_sum[5m]))
/
sum(rate(plowshare_jobs_duration_seconds_count[5m]))
```

The inference and job recorders update only bounded in-memory totals on execution
threads. Scraping or a failing export backend cannot replay work or change admission,
authorization, cancellation or durable lifecycle decisions. Use the restricted
management listener described below for exporting all of these meters.

### Actuator endpoints

The `health`, `info` and `metrics` actuator endpoints are exposed on the main API
port **without authentication**; no security starter is present. They reveal JVM,
connection-pool and request metrics, and the health probe borrows a database
connection. A deployment must restrict them with `management.server.port` and
`management.server.address`, or by a front proxy that blocks `/actuator`.

The Prometheus registry is bundled and receives the same meters. Its HTTP endpoint,
`/actuator/prometheus`, is **not exposed by default**. To enable it later, configure
an explicitly restricted management listener and include `prometheus`, for example
in the operator's private server configuration:

```yaml
management:
  server:
    port: ${PLOWSHARE_MANAGEMENT_PORT}
    address: ${PLOWSHARE_MANAGEMENT_BIND}
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

Supply an unused port distinct from the API port and a bind address reachable only
by the intended monitoring system. A separate port does not add authentication;
restrict network/proxy access as well. A configured Prometheus scraper and persistent
time-series storage retain samples across server restarts; Grafana can query that
history. Bundling the registry alone does not collect history. Container publishing,
ingress, scrapes and alerts remain deployment work.

The Docker deployment wires this up without defaults: when
`PLOWSHARE_MANAGEMENT_PORT` and `PLOWSHARE_MANAGEMENT_BIND` are both set, the
entrypoint passes the settings above (one without the other stops startup), and
`PLOWSHARE_JFR_DIR` starts a bounded flight recording that includes the opt-in
`plowshare.JdbcExecute` event. PostgreSQL preloads `pg_stat_statements` and
`auto_explain`, both slow-statement logging thresholds are off by default (enabling them
writes SQL statement text, not parameters, to the PostgreSQL container log), and an optional
exporter profile, using its own `pg_monitor` role, exposes top statements by `queryid`.
The statement-count meters `plowshare.jdbc.statements`,
`plowshare.authority.admission.checks`, `plowshare.authority.session.checks` and
`plowshare.operation.statements` are charted in `deploy/monitoring/grafana-plowshare.json`.
See the [Docker README](../deploy/docker/README.md#monitoring).

## Information feature switches

Authored content and document-navigation writes are **enabled by default**.
Authentication, project roles, service-token scope ceilings, document access,
reference visibility/propagation and model-tool grants continue to determine which
operations each caller may perform. Enabling a feature never grants authority.

The shipped [application.yml](../plowshare-server/src/main/resources/application.yml)
lists both startup controls with comments. Operators can override them in a private
Spring configuration overlay or through the environment variables below. They apply
across the server, require a server restart to change, and are not user/project
settings or live configuration commands. They remain available for incident
response or a controlled suspension of new writes.

| Property | Environment variable | Default | Effect of explicit `false` |
| --- | --- | --- | --- |
| `plowshare.information.content-types-enabled` | `PLOWSHARE_INFORMATION_CONTENT_TYPES_ENABLED` | `true` | Refuses schema-version creation/activation/deprecation, metadata-only revisions, admissions with explicit typing directives and all portable imports |
| `plowshare.information.navigation-writes-enabled` | `PLOWSHARE_INFORMATION_NAVIGATION_WRITES_ENABLED` | `true` | Refuses relationship creation/removal, reference-policy changes and saved-view creation/update/deletion |

Both switches must be on to restore relationships during portable import.
Import with `restoreRelationships:false` requires only authored-content writes.
The switches do not trigger processing or change model allowances. Ordinary
untyped ingestion and legacy revision/collection operations retain their contracts.

For example, this private overlay suspends graph writes while leaving authored
content available:

```yaml
plowshare:
  information:
    content-types-enabled: true
    navigation-writes-enabled: false
```

Turning either switch off preserves retained content, bindings, links and views.
Authorized reads, navigation resolution, evidence/input dependency checks, export,
metadata-only processing fences and stored access obligations remain enforced.
It does not remove links, declassify a document, stop unrelated processing or make
an older binary safe against newer retained state. Re-enabling resumes operation
availability under current authority; clients must reconcile uncertain submissions
rather than replay them automatically.

Reference policy is a separate decision. Defaults are existing content authority,
hidden references and propagation; project managers/server administrators can
initialise the intended project policy within mandatory constraints. Incurred
obligations can survive unlinking, and this release has no general release or
declassification workflow. See [navigation policy](authored-navigation.md#configure-policy).

**Upgrade behavior:** older builds defaulted navigation writes off. With this
build, an installation that has no explicit override starts with writes on. An
existing explicit `false` stays off. Operators who intend to keep writes suspended
must retain that override before upgrading. Installing a new binary/configuration
is separate from changing an already running server.

Clients can inspect effective availability with authenticated
`information.types.capabilities` (`typedWrites`) and
`information.navigation.capabilities` (`writesEnabled`) in their allowed scope.
Capabilities report operation availability, not permission to write every document.

## First administrator

The first server start prints a temporary `admin` login with a random password.
Run `plowshare-cli setup --url http://your-server:8091`, or open the desktop connection
dialog and expand **Set up the first administrator**. Choose your permanent account
name and password. Setup consumes the temporary account and provisions your Personal
space. See [first server setup](../README.md#first-server-setup).

After setup, sign in with `plowshare-cli login --url http://your-server:8091`.
Administrative commands use this saved account's authenticated WebSocket.

## Accounts and roles

List accounts:

```sh
plowshare-cli admin accounts
```

Create a regular user account, or explicitly give a new account administrator privileges:

```sh
plowshare-cli admin account create '{"handle":"sam"}'
plowshare-cli admin account create '{"handle":"second-admin","serverAdmin":true}'
```

Handles contain 1–64 letters, digits, dots, underscores or hyphens and start with a
letter or digit. Handles are unique and case sensitive. Creation provisions the
account's own Personal space, identified as `Personal:<account-name>` for routing.

The response includes a generated `temporaryPassword`. Give it to the account owner.
They sign in using the normal login command, which walks them through the required
password change. A temporary login can change its password but cannot use projects,
admin commands or either WebSocket until that change is complete. The server stores
only a password hash. The response contains the password once; it cannot be read back.

Change a role or disable and re-enable an account:

```sh
plowshare-cli admin account update '{"handle":"sam","serverAdmin":true}'
plowshare-cli admin account update '{"handle":"sam","serverAdmin":false}'
plowshare-cli admin account update '{"handle":"sam","enabled":false}'
plowshare-cli admin account update '{"handle":"sam","enabled":true}'
```

Disabling preserves the account, its Personal space, project memberships, conversations
and other retained work. It blocks login and signs out existing clients. Re-enabling
allows a fresh login; it does not revive the old credentials. Changing an administrator
role also signs that account out, so each client reconnects under its current role.
The server checks the persisted role on every administrative request.

At least one enabled permanent administrator must remain. Before disabling or demoting
the last administrator who has completed password setup, have another administrator
finish their password setup. Concurrent requests cannot bypass these safeguards.

## Password recovery

Reset another account's password:

```sh
plowshare-cli admin account reset '{"handle":"sam"}'
```

This returns a new temporary password, requires a password change at the next login,
and revokes all of the account's sessions. Disabled accounts remain disabled. Enable
the account separately if it should be able to sign in.

Use the normal authenticated password change flow for your own account. Administrators
cannot use this reset command on themselves, because revoking their socket would prevent
reliable delivery of the new credential.

If a create or reset response is lost, inspect `admin accounts` and `admin audit` before
retrying. If the account exists but its temporary password was lost, reset it explicitly
to obtain a new one. Clients do not automatically replay these mutations.

## Service accounts and scoped tokens

A **service account is an identity inside Plowshare** for an integration, SDK client,
or deployment pipeline. It is independent of the operating system account running
that integration and independent of credentials for Home Assistant or another external
service. It has no password login, administrator role or Personal space. Human server
administrators create and manage these identities.

Create the identity and grant its server project access:

```sh
plowshare-cli admin service account create '{"handle":"ha-integration"}'
plowshare-cli project member-add '{"project":"automation","handle":"ha-integration","role":"CONTRIBUTOR"}'
plowshare-cli admin service accounts
```

Issue a named credential with a separate ceiling for each project:

```sh
plowshare-cli admin service token create '{"handle":"ha-integration","name":"production","scopes":[{"project":"automation","role":"CONTRIBUTOR"}],"expiresInDays":30}'
```

The response contains `token` metadata and a one-time `credential` beginning with
`pss_`. Save the credential in your integration's secret store. The server persists
its digest; subsequent listings reveal metadata and scopes, never the credential.
Each token needs 1–100 distinct ordinary server projects. Personal spaces and private
client projects cannot be token scopes. Expiry defaults to 30 days and can be set from
1 to 365 days; credentials do not refresh themselves.

Effective access is the lower of the service account's **current project grant** and
the token's **project ceiling**. A Viewer token cannot run agents even if its account
is a Manager. A Contributor token loses write access immediately when its account is
downgraded to Viewer. Removing the account's project membership removes that token's
access. Adding a new project to the account does not add it to existing tokens.
Existing workspace `writePaths` restrictions still apply.

These limits follow work into queued jobs, information tasks and internal message
routing. Supply an explicit server project or a resource ID belonging to an allowed
project. Service tokens cannot fall back to Personal or Global execution, administer the
server, or root/sync a local client checkout. Authenticated service credentials
can read knowledge whose current root audience is GLOBAL_READ. Project searches
include that knowledge by default. To narrow information discovery to one project:

```json
{"scope":{"kind":"project","project":"automation","includeGlobal":false}}
```

Each token has a durable execution identity, shown as `principal` in its metadata:
`@service/<token UUID>`. Work, ownership and retained route conversations use this
identity. Different tokens have distinct execution identities; reuse or rotate the
same token when the integration needs to retain its ownership and conversation
continuity. Rotation keeps its ID, scopes and principal while replacing the secret.
Token scopes are fixed: issue a different token to change its scope ceiling.

Inspect, rotate and revoke tokens using the returned token UUID:

```sh
plowshare-cli admin service tokens '{"handle":"ha-integration"}'
plowshare-cli admin service token rotate '{"handle":"ha-integration","id":"<token UUID>","expiresInDays":30}'
plowshare-cli admin service token revoke '{"handle":"ha-integration","id":"<token UUID>"}'
plowshare-cli admin service account update '{"handle":"ha-integration","enabled":false}'
```

Rotation immediately invalidates the previous credential and its outstanding tickets
and socket sessions. Revocation and expiry also remove the execution identity's
project access, including later background work permission checks. Disabling a service
account revokes all its tokens while retaining its history. Re-enabling does not revive
those credentials; issue or explicitly rotate a token after checking its project
grants. An expired or revoked token can be rotated if the account is enabled and its
current grants still allow all the original scopes.

If issuance or rotation loses its response, inspect token metadata first. Credentials
cannot be recovered; rotate the existing token explicitly to obtain a replacement.
Clients never automatically replay a token mutation. Service account and token changes
appear in `admin audit`, with no secrets or digests. Token audit targets identify both
the service account and token UUID.

Use the credential as `Authorization: Bearer <credential>` with an SDK. In the CLI,
supply it as `PLOWSHARE_TOKEN` from your process environment or secret injection, then
run ordinary project commands:

```sh
plowshare-cli --url http://your-server:8091 --project automation agent list
```

With `PLOWSHARE_TOKEN` set, the CLI obtains a short-lived socket ticket using that bearer.
It performs no password login, refresh or credential-file write and does not fall back
to a human login if the token fails. The environment token takes precedence over saved
logins and password environment variables. Clear it before running human administrator
commands. An explicit token that does not begin with `pss_` is refused by this CLI flow.

In the TUI, use the same administrative commands with `/admin service ...`. In the
desktop, open **Server administration → Service accounts**. Create/select an account,
choose a project and role, and select **Grant access**. Add each desired token scope,
choose a token name and expiry, and issue the token. The token list offers rotation and
revocation. The credential field is cleared on dismissal, account changes, refresh or
dialog closure, and credentials are excluded from state snapshots and saved preferences.

## Sessions

Inspect an account's active login sessions and sign it out everywhere:

```sh
plowshare-cli admin sessions '{"handle":"sam"}'
plowshare-cli admin session revoke '{"handle":"sam"}'
```

The list contains opaque login session IDs, creation and expiry times, and whether a
session is restricted to changing its password. It contains no access tokens, refresh
tokens or token digests. Revocation applies to **all login sessions of the named account**;
individual session revocation is not offered by this command.

Revocation invalidates durable access and refresh credentials, outstanding upgrade
tickets, and event and file sockets. Credentials remain revoked after server restart.
The server handling the change closes its sockets after the database commits. Other
server processes sharing the database check socket revocation every second and before
handling an incoming frame. Reconnect and sign in again to establish fresh sessions.
Revocation does not cancel retained server jobs or delete their history.

Explicit server operator/bootstrap tokens are separate deployment credentials, not
account login sessions. They are not included in this list; rotate those credentials
through the server's deployment configuration.

## Audit history

Successful administrative changes are recorded durably with their actor, target,
time and resulting account status. Each mutation and its audit entry commit together.
Passwords, tokens and token digests are excluded.

```sh
plowshare-cli admin audit '{"limit":25}'
plowshare-cli admin audit '{"handle":"sam","limit":25}'
```

Entries are newest first. To read older entries, pass the response's nonzero `before`
cursor unchanged:

```sh
plowshare-cli admin audit '{"handle":"sam","limit":25,"before":42}'
```

Use a limit from 1 to 100. A `before` value of zero means the newest page. A response
cursor of zero means there are no further pages to request. A full final page may
return a cursor whose next page is empty. The audit records `account.create`,
`account.update`, `account.password.reset`, `session.revoke`,
`service.account.create`, `service.account.update`, `service.token.create`,
`service.token.rotate` and `service.token.revoke`. It is an administrative
change history, rather than a login-attempt or agent activity log.

## TUI and desktop

In the TUI, prefix the same commands with `/`, for example:

```text
/admin accounts
/admin account update {"handle":"sam","enabled":false}
/admin sessions {"handle":"sam"}
/admin session revoke {"handle":"sam"}
/admin audit {"limit":25}
```

These commands manage the server directly and do not open a conversation or invoke
an agent. Account creation and password reset reveal their temporary password in the
command result; treat that terminal output as a credential.

In the desktop GUI, connect as an administrator and choose **Server administration**
in the sidebar. Select an account to change its role or enabled status, reset its
password, inspect sessions, or sign it out. The dialog also provides account creation,
audit history, and older audit pages. Temporary passwords are displayed in a transient
field and cleared when dismissed, when the account selection changes, or when the
dialog closes. They are excluded from desktop state snapshots and saved connection
preferences. Nonadministrators cannot open this dialog or call its backing operations.

## Server projects

Administrators can create server projects with `project create`, including MANAGED
projects and externally maintained DISJOINT workspaces with limited writable areas.
Project Managers can manage membership without being server administrators.
See [projects](projects.md) for workspace ownership and discovery and
[internal messaging](internal-messaging.md) for project routes and Personal addresses.

## Project access and Personal scopes

An account needs an explicit project data role to use an ordinary project.
Server-administrator control is separate from reading or executing project work.
Each account has one private Personal space, addressed as `Personal:<ACCOUNT_NAME>`.
Its owner can read, run work and manage their own agents there. Other accounts,
including server administrators, cannot enter that Personal space or change its membership.
Personal routing defaults still allow incoming and outgoing routes; sending work across
projects also requires Contributor access to the destination project and the route policy.

| Project role | Permissions |
| --- | --- |
| Viewer | Read project conversations, memories, information and available agents; inspect project access. |
| Contributor | Viewer permissions plus start/resume/cancel work, create conversations, post messages and update project information. |
| Manager | Contributor permissions plus define project agents and manage project membership and roles. |

Server workspace provisioning, project moves, writable-area configuration, account
administration, server-wide schedule creation/event emission and resource maintenance
require a server administrator. Listings of schedules, triggers and firings remain
account-owned; project data in unscoped work listings is filtered by current access.
A retained global schedule stops emitting while its owner is disabled or no longer an
administrator. Regular users may pause or remove their own retained schedules; resuming
a global schedule requires administrator authority.
Per-document ownership/publication and private client-session checks continue to apply.
Roles never widen a workspace's exclusions, read-only setting or permitted `writePaths`.
Existing non-admin memberships migrate to Contributor. Project creators and each
Personal-space owner receive Manager. The scoped-grant migration records existing
administrators' effective ordinary-project data authority as explicit compatibility
grants. New administrators and new projects receive no implicit data grant.
Administrators retain scoped membership-management control through the existing
operations; `project access` can report `permissions:["manage"]` without data access.
VIEWER remains the wire name of the internal READER helper.

Inspect the current account's effective access and explicit grants:

```sh
plowshare-cli project access '{"project":"home-assistant"}'
```

Add a known account (omitting `role` defaults to Contributor), change its role, or
remove it:

```sh
plowshare-cli project member-add '{"project":"home-assistant","handle":"sam","role":"VIEWER"}'
plowshare-cli project member-role '{"project":"home-assistant","handle":"sam","role":"CONTRIBUTOR"}'
plowshare-cli project member-remove '{"project":"home-assistant","handle":"sam"}'
```

Adding an existing member is refused; use `member-role` to change their grant.
A project Manager cannot remove or demote the last enabled project Manager. A server
administrator can recover a project and replace its grants. Grant changes and removals
are saved transactionally with project access and immutable grant history; Managers see the latest 25 entries.
Bindings retain their issuing authority, scope, version and any expiry. Revoking
or losing an issuing grant invalidates its descendants. A manager replacing the
membership that supplies their own issuing authority needs another manager or
independent administrative control to authorize it. Application manifest and
service-token ceilings are checked live along the issuing chain.
Removing or downgrading a grant applies to subsequent requests, job inspection and live
project delivery. Existing resource IDs do not grant access or override their stored project.
Runs check Contributor access again before obtaining project file providers. Git sync
fetches require Viewer access and pushes require Contributor access, rechecked before
applying a received pack.

In the TUI, prefix these same commands with `/`, for example `/project access`.
When a current project is selected, the command can omit `project` from its JSON.
In the desktop, click **Access** beside a project. All members can inspect their role;
Managers can add accounts, save roles, remove grants and view access history.
Viewer access disables starting conversations and sending messages in that project.
Use the server administration dialog to create regular accounts first; creating an
account alone does not grant access to other projects.

The legacy HTTP surface uses the same project-role admission policy as WebSocket
commands. CLI, TUI and desktop role management uses WebSockets. Remote editing of
provider credentials, URLs and pool settings is not part of this capability; those
remain server configuration until a dedicated configuration workflow is added.

## Model pricing and usage statistics

Server administrators can view and edit server prices over the authenticated WebSocket.
In the desktop GUI, open **Server administration → Pricing**. The editor lists each
served billing route and exact wire model, its pools, configured rate cards and any
active operator override. Rates use decimal text in currency units per million tokens.
Input/output and optional cache read/write rates, input-size tiers and a fee per
upstream attempt are supported. Modes are `TOKEN`, `INCLUDED`, `ZERO_RATE` and
`UNPRICED`; missing prices remain unknown.

CLI example (illustrative operator rates, not a provider quote):

```sh
plowshare-cli admin pricing list --json
plowshare-cli admin pricing set '{"billingRoute":"hosted","model":"deployment-name","expectedVersion":"boot:","mode":"TOKEN","currency":"USD","rates":{"input":"0.40","output":"1.60","cacheRead":"0.10"},"source":"operator"}' --json
```

Copy `billingRoute`, `model` and `version` from the list response; pass that exact
`version` as `expectedVersion`. `boot:` only applies when no startup prices or
operator override exist. A stale editor is refused: refresh, review the current
rates and explicitly resubmit. Price changes commit with an audit entry and persist
across restarts. Overrides take precedence over startup configuration for that
route/model. Changes apply to future admissions. Queued requests, retries and
recorded costs keep the immutable rate card selected when their call was admitted.
An `UNPRICED` override explicitly disables price estimation for future calls.

In the TUI, use `/admin pricing list` and `/admin pricing set {…}` with the same
JSON payload. Pricing administration requires a current server administrator;
project Manager authority alone does not grant it. Regular-user accounts created
with `admin account create` default to no server-admin role. Their Viewer,
Contributor and Manager roles continue to control project resources independently.

Usage statistics already have CLI and TUI queries:

```sh
plowshare-cli usage pools --json
plowshare-cli usage models --json
plowshare-cli usage project '{"project":"automation"}' --json
plowshare-cli usage conversation '{"conversation":"conversation-id"}' --json
```

Use `/usage pools`, `/usage models` or `/usage project automation` in the TUI.
The GUI's **Usage** panel shows recorded tokens, cost estimates, incomplete usage
and pricing coverage; the pricing editor has an **Open usage statistics** button.
`admin.status` only reports the signed-in account’s own role; it does not grant
admin authority. Every other `admin.*` operation passes the central server-admin
gate and the owning service’s authorization check on the server. Account or role
revocation is checked on subsequent actions. Fleet-wide `usage pools` requires server-admin authority and rechecks it on updates.
Regular users can query their permitted project/conversation statistics; foreign
resources remain refused. Pricing is an operator estimate, not a provider invoice.
Local reference comparisons are separate from server prices and booked costs.


## Live configuration authorization

`GET /v1/config` and `PUT /v1/config/{key}` require the authenticated account's
current server administrator role. The controller checks the account attribute
supplied by the authentication filter before reading settings, disclosing live
keys or writing a value. Regular accounts and service credentials cannot access
these endpoints through project membership, tool grants or client-side checks.
Anonymous requests are refused by authentication; authenticated callers without
administrator authority receive HTTP 403. An operator credential must resolve to
an explicitly configured account that still has the administrator role.

The role is rechecked for each request. Disabled, missing, bootstrap and service
identities are refused, and disabling the authentication filter does not bypass
the controller's role check. Request parameters and servlet-principal names are
not substitutes for the filter's account context. Successful writes retain the
existing raw-text, live-key and deployment-pin behavior.

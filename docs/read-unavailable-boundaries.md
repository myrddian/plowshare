# Read backpressure, failed queries and slow work

`ReadUnavailableException` means **actual backpressure**. It must not mean that a
query timed out, a cache token changed, a service closed or an entire workflow
crossed a performance threshold. Those different signals previously shared one
exception and one misleading "capacity is busy" message.

Backpressure stops or paces incoming work before it starts. A proven pre-handler
refusal uses `CAPACITY_EXHAUSTED` / HTTP 429; the SDK can queue and pace the same
identity. It never authorizes replay of work whose handler already started.
Accepted physical work stays accounted for until it settles, even after a caller
cancels observation.

A query uses a configurable two-second default SQL completion budget; statements
inside one actual transaction share that transaction's remaining time. Separate
transactions, provider calls, cache coordination and an entire job do not share a
two-second lifetime. Slow whole-work duration produces a **warning only** through
`WorkPerformanceReporter`: its bounded queue and singleton logger cannot throw,
cancel the job or change its result.

## Complete owner audit

Merged revision `abef4b11` had 45 production references, including 15 construction
or factory sites in ten throwing owners. The other references were imports,
catches, telemetry and interface documentation. This table traces those owners
and consumers; it does not treat every mention as a throw.

| Owner | Audited boundary and correction |
| --- | --- |
| [BoundedReadAdmission](../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/BoundedReadAdmission.java) | Global/principal occupancy and full wait queues are real backpressure. Eligible incoming reads wait fairly before dispatch. An expired existing SQL deadline or interruption is a distinct `ReadFailedException`, not a capacity signal. No queue wait budget becomes a handler deadline. |
| [FrameRouter](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java) | Acquisition is separate from handler execution. Only acquisition can prove a pre-handler capacity refusal; an arbitrary handler cannot return that proof. The physical source owns its lease and warning observation until settlement, independent of reply cancellation. |
| [EventChannelHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/EventChannelHandler.java) | A proven refused request releases its admitted input ID/body after execution settles and before the physical capacity reply permits same-ID resubmission. Encoded output bytes remain owned until send settles. Both legacy and segmented SDK retries execute the eventual handler once. |
| [HttpReadAdmission](../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/HttpReadAdmission.java) | Its narrow pre-handler marker maps to 429. This does not change a mid-operation failure into permission to replay a mutation. HTTP async physical settlement remains an owning-lifecycle gap. |
| [JdbcInformationLogVisibility](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationLogVisibility.java) | A global version change used to fail an otherwise successful inbox/orchestration page. Discard that tentative cached page and run one pure authoritative SQL callback. Each independent callback has its own query budget; an active transaction's deadline is not renewed. Already-authoritative reads do not depend on unrelated cache-token churn. Unsafe dirty/historical/expiry-spanning sharing remains withheld with `INVALIDATED`, never "no seats". |
| [VersionedProjectionCache](../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/VersionedProjectionCache.java) | Actual occupied load/byte/waiter capacity can apply backpressure. An impossible intrinsic reservation is `LOAD_TOO_LARGE`, not occupied capacity, and follows its ordinary projection fallback. Accepted inline hits/loads/joins do not create a hard two-second workflow timer. Only an already existing SQL deadline limits their wait; slow work emits warnings. |
| [BoundedSnapshotCache](../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/BoundedSnapshotCache.java) | Both elected and joining accepted `readInline` calls are exempt from the asynchronous API's caller-wait timer. Count, byte, dependency, load and waiter bounds remain. The separate explicit asynchronous `read` API retains its documented timeout contract. No timed-out observer releases another caller's physical load. |
| [QueuedSocketSessions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/QueuedSocketSessions.java) | Full global/principal intake is capacity. Closure, timeout and interruption have distinct failed-read reasons. Each SQL batch retains its own configured wait bound, narrowed by a real existing SQL deadline. A background multi-batch sweep has no new whole-workflow timer. Fatal fact failures settle waiters and release physical ownership before propagating. |
| [ReadDeadline](../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/ReadDeadline.java) | Represents an explicitly owned SQL unit only. Its monotonic cutoff throws `ReadFailedException.DEADLINE_EXCEEDED`, not backpressure. Opening a scope is not remote cancellation; physical SQL owners must enforce it. |
| [JdbcReadDeadline](../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/JdbcReadDeadline.java) | Uses pgJDBC millisecond cancellation with a two-second default query cap and any shorter active SQL deadline. Less than one millisecond cannot start a useful millisecond timer. Acquisition/materialization completion checks must not be installed around an entire provider workflow. |
| [ProjectIds](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectIds.java) and [EntryStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryStore.java) | Selection reuse is confined to one phase, with fresh resolution after provider IO. Each real query owns its completion deadline. Independent auto-commit queries/visibility preparation do not acquire a combined search timer. |
| [JdbcPassageRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcPassageRepository.java) | Actual ranked disclosure/profile SQL transactions retain a shared SQL budget. Entry ANN hashes one statement-scoped authorized-ID projection before the candidate limit, avoiding a source/conversation lookup for every rejected vector neighbor. Selected-key revisions still fence detail loading. |
| [ConversationSearch](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationSearch.java) | Provider capture and query-owner cleanup run outside SQL scopes. Slow total search duration warns only. SQL cancellation becomes a typed `QUERY_CANCELLED` after transaction/query cleanup, with no partial window and no automatic provider/tool replay. |
| [ArchiveQueryCancelledException](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ArchiveQueryCancelledException.java) | Is a `ReadFailedException`, not `ArchiveUnavailableException`. A failed SQL unit rolls back without becoming a fatal lost-database dependency for the entire job. Genuine lost archive connections retain their separate policy. |
| [BusyReadRetry](../plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/BusyReadRetry.java) | Recovers only explicitly pure checks under the existing bounded retry policy. Independent attempts keep their owning SQL budgets; an already active transaction deadline is never renewed. It creates no hard retry-workflow timer, retries no provider/effect and preserves interruption. |
| [SocketAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/SocketAuthorization.java) | Temporary capacity/session-read failure uses overload 1013, preserving 1008 for an actual revoked-session fact. Busy sweeps do not invent revocation. Socket leases settle physically. |
| [FileChannelHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FileChannelHandler.java) and [AgentsConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java) | Typed query failure/backpressure retains the existing metadata-recovery path, rather than dropping a usable file connection or leaving a declared presence without its provider. Unknown membership never grants a new root. |

## Introduction history

| Change | Introduced behavior |
| --- | --- |
| [`81bceaf1`, PR #142](https://github.com/myrddian/plowshare/commit/81bceaf1) | Introduced the exception and immediate global/per-account admission gates. The reported pre-handler `admit` branch checks capacity; it contains no timer. |
| [`95d9c42d`, PR #146](https://github.com/myrddian/plowshare/commit/95d9c42d) | Added the global collection visibility-fence throw used by inbox/orchestration pages. |
| [`004e08ec`, PR #160](https://github.com/myrddian/plowshare/commit/004e08ec) | Added socket-session batch admission and waits. |
| [`2c305443`, PR #252](https://github.com/myrddian/plowshare/commit/2c305443) | Added cache-entry/FETCH deadlines and pure-check retry recovery. Its inline wait timer could fail accepted composed reads. |
| [`c35f9e28`, PR #280](https://github.com/myrddian/plowshare/commit/c35f9e28) | Added aggregate failure counters and sampled diagnostics. Correcting logging alone would not fix the read failures. |
| [`faba1699`, PR #284](https://github.com/myrddian/plowshare/commit/faba1699) | Wrapped the whole conversation search, including provider embedding, in the SQL budget. That workflow scope is removed. |

## Catch and reporting semantics

[Faults](../plowshare-server/src/main/java/io/aeyer/plowshare/server/faults/Faults.java)
keeps proven incoming capacity distinct from failures after dispatch. Existing
clients retain the 503 compatibility outcome for failed reads; it never promises
that a larger operation did nothing. `ReadFailedException.reason()` distinguishes
SQL deadline, invalidation, interruption, closure and cancellation.

[JobRuntime](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java)
retains earlier completed tools after a failed SQL unit and can continue with its
evidence; it does not automatically replay the failed tool. A genuine archive
outage remains a distinct dependency failure.
[WorkspaceCodeMap](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/WorkspaceCodeMap.java)
and [CodeWorkspaceMonitor](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/CodeWorkspaceMonitor.java)
do not publish empty replacement manifests after read failure. Coalesced hints
can requeue after a pure prepublication read fails; once publication starts,
uncertain mutation work is not blindly repeated.
[EmbeddingConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/EmbeddingConfig.java)
defers maintenance discovery under capacity pressure, while post-attempt repair
failures retain durable checkpoints.

[AggregateFailureReporter](../plowshare-server/src/main/java/io/aeyer/plowshare/server/telemetry/AggregateFailureReporter.java)
counts source/kind observations, not unique incidents. `WEBSOCKET` names the
handling boundary, not a broken connection. Capacity is not conflated with failed
read reasons. True failed-operation samples are ERROR; expected recovery and
warning-only performance observations remain WARN without throwing exceptions.
A failed inference *attempt* is separate from a failed terminal call/job: a retry
may succeed, so those metric series must not be interpreted interchangeably.

## Remaining enforcement limits

Some version probes, datasource connection acquisition and HTTP async lifecycles
still need owner-specific physical cancellation/settlement adoption. A warning or
caller timeout cannot establish remote abort. No change here turns unsafe
visibility, unknown commits or genuine outage into fabricated success. Count and
byte bounds remain; removing a workflow timer does not remove physical ownership.

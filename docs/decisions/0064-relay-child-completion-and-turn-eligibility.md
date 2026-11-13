# Relay child completion and turn eligibility

Status: implemented for Slice 7 of [Finishing Relay](../relay-finishing.md),
13 November 2026. Implements the remaining completion-consumer direction of
[0041](0041-waiting-is-turn-eligibility.md). This is platform delegation work;
the A2A rewrite remains deferred.

## Arrival and recovery

Child terminal results have one transport: the built-in consumer group
`builtin.orchestrations.children` on `orchestration.child.completed`, independently
leased in each project or server/global scope. The transactional lifecycle outbox
already owns notice publication. V247 extends that notice to failed, capped and
cancelled children as well as finished children. It backfills only undelivered
terminal attempts on upgrade; delivered history never becomes new work.

The consumer's input is availability, not instructions, report text or authority.
Report selection comes only from scoped terminal orchestration records. Payloads
cannot select another conversation, forge a parent, supply result text or start
a child. Root orchestration starts, internal child dispatch and `message.*` retain
their APIs and ownership. Child questions keep their existing question path;
they are not terminal completion notices.

Trusted notice references are validated in one scoped metadata batch. Recovery
does not bypass an outstanding source outbox entry: the source must publish before
that terminal attempt becomes eligible for owning-row reconciliation.

`Delivery.runEnded`, free-conversation drains and boot drains do not deliver child
terminal results. Only `ChildCompletionDelivery`, called by the leased consumer,
may do so. Root results retain their existing caller/person routes. The owning
delivery code rechecks the exact child, parent and `endedAt` attempt before
composing from retained records. Delayed notices cannot settle a resumed attempt.

The Relay cursor acknowledges availability, never successful report delivery.
Undelivered terminal rows are the recovery source when a parent is busy or asking,
a publication expires, notifications are lost, or a process restarts. V247 adds
a separate, lease-fenced reconciliation keyset cursor, atomically advanced with
each bounded owning-row scan. It is not a wait, delivery receipt or authorization
lease. Persistent rotation prevents a busy first page from starving later reports
and does not require an unbounded in-memory map. Each cycle captures a fixed
upper ID boundary, so continuous new arrivals cannot postpone the next revisit
of an older busy report indefinitely. Discovery rotates scopes too.
The parent wake policy is unchanged: a report from any live child can wake it,
not only the child named in `waiting_for`.

## Ownership and failure behavior

Every pass uses the same built-in subscriber name, with a unique worker identity
and a bounded distributed lease. The lease's account label is an internal
coordination label, never a login, usage identity or authority fallback. Lease
renewal continues while delivery resolves definitions or schedules a parent turn.
Renewal and epoch-sensitive repository checks serialize locally; no model,
network call or delivery effect occurs under a database transaction or that lock.

Ownership is checked before report processing, immediately before effects and
settlement, and on cursor advancement. Loss of ownership stops additional work;
a stale release cannot disturb a successor. The delivered mark and terminal
attempt guard retain existing report idempotency and at-least-once recovery
semantics. This does not claim exactly-once parent turn scheduling across a crash
between accepted scheduling and the delivered marker. It never re-executes the
child or treats an absent publication as permission to do so.

Transient refusal keeps the report pending. Existing permanent-refusal behavior,
person fallback, input-disclosure checks, delivery hooks, budgets and parent
authority remain in their owning APIs. Database failures propagate to the worker's
bounded retry loop and are logged without report bodies. Closing workers stops
discovery and lease renewal; interrupted passes must stop before further effects.

## One eligibility decision, separate legality

`TurnEligibility` is the shared immutable scheduling decision for triggers, board
wakes, messaging wakes and orchestration continuation routing. Owners provide a
typed hold plus lazy occupancy and resource facts. The decision resolves a child
wait, caller-answer wait or terminal state first, then turn occupancy, then resource
availability. A known block does not perform unnecessary resource queries.

Pending terminal child reports are also a child hold, even when the parent row
is running. A typed SQL snapshot combines that hold with its live children in
one read. Routing reuses it for subsequent nudge decisions, preventing both a
nudge and plain-text auto-finish from overtaking the report. Explicit owning
finish/cancel operations retain their own legality checks.

The value adds no persistent waiter and grants no authority. Messaging retains
its instance-aware concurrency and request ordering; the board retains pot and
seat legality; orchestration retains its tree, `ROOT_CANNOT_WAIT`, depth cap,
one wait per row and durable compare-and-set wait/wake transitions. Atomic start
claims still recheck owning state. Causation bounds effects, never waits. Relay
continues to answer arrival questions and does not park conversations.

## Verification

Default tests use mocked repositories and voices for lease exclusion, lost
ownership, bounded recovery, gap acknowledgement, expiry-independent discovery,
input isolation and shared eligibility decisions. PostgreSQL tests are selected
only for V247, transactional lifecycle capture/backfill, lease contention and
fencing, durable reconciliation rotation and retained terminal-attempt guards.
Owning delivery regressions verify busy parents, refusal, hooks, caller/person
fallback and stale attempt settlement without live model inference.

Validation completed: Google formatting and `formatCheck check` passed in default
mode. Selected PostgreSQL tests passed for the V247 upgrade/backfill, transaction
rollback, project/global scope isolation, same-group contention, lease fencing,
retention recovery, fixed-cycle pagination under new arrivals and the owning
delivery/wait/nudge regressions. Mocked tests passed for the consumer and shared
eligibility contract. No live model inference was required.
